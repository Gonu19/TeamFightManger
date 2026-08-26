package com.teamfighter.tfm.ingest.watcher;

import com.teamfighter.tfm.ingest.IngestService;
import com.teamfighter.tfm.ingest.SlotFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 세이브 폴더를 감시해 변경이 조용해지면 {@link IngestService#ingest(Path)} 를 부른다 (D12).
 *
 * <p><b>왜 디바운스인가.</b> 게임이 쓰는 도중에 읽으면 NRBF 스트림이 잘린다. 마지막 변경 후
 * 1.5초 조용해야 읽는다. D17 실측으로 게임은 스크림 한 판(66~93초)마다 저장하므로,
 * 1.5초를 더 기다린다고 놓치는 것은 없다.
 *
 * <p><b>디바운스는 파일별로 돈다.</b> 폴더 전체에 타이머 하나를 두면 한 슬롯이 계속 저장되는
 * 동안 다른 슬롯이 영영 적재되지 않는다.
 *
 * <p><b>중복 판정은 하지 않는다.</b> 같은 내용이 다시 저장돼도 그냥 부른다. D17 에서 쓰기
 * 3~4건 중 1건은 경기가 늘지 않았는데, 그 판단은 {@link IngestService} 의 해시 검사가 할 일이다.
 * 워처가 자체 캐시로 가로채면 판단이 두 곳으로 갈라진다.
 *
 * <p><b>일회용이다.</b> {@link #stop()} 한 워처는 다시 시작할 수 없고, 시도하면 던진다.
 * 디바운스 스케줄러가 한 번 shutdown 되면 되살아나지 않아서인데, 되살아난 척하면
 * 감시 스레드는 멀쩡히 돌고 이벤트도 들어오는데 적재만 한 건도 안 되는 상태가 된다 —
 * 로그 한 줄 없이. 이 프로젝트가 Flyway 로 이미 한 번 당한 실패 방식이 정확히 그것이다.
 *
 * <p><b>세이브 파일을 열지 않는다.</b> 이 클래스는 경로만 다룬다. 파일을 여는 것은 적재 쪽이다.
 */
public final class SaveWatcher {

    private static final Logger log = LoggerFactory.getLogger(SaveWatcher.class);

    /** 종료 시 진행 중인 적재를 기다려 주는 시간. 넘기면 포기하되 조용히 넘어가지는 않는다. */
    private static final long SHUTDOWN_WAIT_SECONDS = 10L;

    private final Path watchDir;
    private final long debounceMs;
    private final IngestService ingestService;

    /** 파일별 대기 중인 적재. 새 이벤트가 오면 이전 것을 취소하고 다시 건다 — 그게 리셋이다. */
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    /**
     * 생성자에서 만든다. {@link #handleOverflow()} 는 감시 스레드 없이도 동작해야 하고
     * (그래야 재훑기만 따로 검증된다), 여기서 만들어 두면 그 경로가 {@code start()} 에
     * 의존하지 않는다.
     */
    private final ScheduledExecutorService scheduler;

    private volatile WatchService watchService;
    private volatile Thread thread;
    private volatile boolean stopping;

    /** Spring 이 쓰는 생성자. */
    public SaveWatcher(TfmProperties properties, IngestService ingestService) {
        this(properties.getSaveDir(), properties.getWatchDebounceMs(), ingestService);
    }

    /**
     * @param watchDir      감시할 세이브 폴더 (하위 폴더는 뒤지지 않는다)
     * @param debounceMs    마지막 변경 후 이만큼 조용해야 {@code ingest()} 를 부른다
     * @param ingestService 실제 적재를 맡을 서비스
     */
    public SaveWatcher(Path watchDir, long debounceMs, IngestService ingestService) {
        if (debounceMs <= 0) {
            // 0 이나 음수를 넣으면 스케줄러가 즉시 실행한다. 그러면 게임이 쓰는 도중에 읽어
            // 스트림이 잘리는데, 증상은 워처가 아니라 파서에서 "적재 실패" 로만 나타난다 —
            // 설정 실수가 다른 계층의 잡음으로 둔갑한다 (D12).
            throw new IllegalArgumentException(
                    "디바운스는 양수여야 한다: " + debounceMs
                            + "ms. 0 이면 게임이 쓰는 도중에 읽어 스트림이 잘린다");
        }
        this.watchDir = watchDir;
        this.debounceMs = debounceMs;
        this.ingestService = ingestService;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tfm-save-debounce");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 감시 스레드를 띄운다. 이미 돌고 있으면 아무 일도 하지 않는다.
     *
     * @throws IllegalStateException 이미 {@link #stop()} 된 워처일 때. 되살릴 수 없다
     */
    public synchronized void start() {
        if (scheduler.isShutdown()) {
            throw new IllegalStateException(
                    "이미 정지된 워처는 다시 시작할 수 없다: " + watchDir
                            + ". 새 SaveWatcher 를 만들어라 — 여기서 조용히 넘어가면 "
                            + "감시는 도는데 적재만 안 되는 상태가 된다");
        }
        if (thread != null) {
            log.info("이미 감시 중이다. 다시 시작하지 않는다: {}", watchDir);
            return;
        }
        // 폴더가 없으면 여기서 시끄럽게 죽는다. 감시 대상이 없는 워처가 조용히 도는 것보다
        // 안 뜨는 게 낫다 — 조용히 돌면 "왜 적재가 안 되지" 를 며칠 뒤에 알게 된다.
        SlotPathResolver.resolve(watchDir);

        try {
            watchService = FileSystems.getDefault().newWatchService();
            // ENTRY_DELETE 는 등록하지 않는다. 지워진 파일을 적재할 일이 없다.
            watchDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            throw new UncheckedIOException("세이브 폴더를 감시할 수 없다: " + watchDir, e);
        }

        Thread t = new Thread(this::watchLoop, "tfm-save-watcher");
        t.setDaemon(true);
        thread = t;
        t.start();
        log.info("세이브 폴더 감시 시작: {} (디바운스 {}ms)", watchDir, debounceMs);
    }

    private void watchLoop() {
        try {
            while (!stopping) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        // 이벤트가 유실됐다. 무엇이 유실됐는지는 알 길이 없으니 전부 다시 훑는다.
                        log.warn("감시 이벤트가 유실됐다(OVERFLOW). 폴더를 다시 훑는다: {}", watchDir);
                        try {
                            handleOverflow();
                        } catch (RuntimeException e) {
                            // 재훑기 실패가 감시 자체를 끝내면 안 된다. 폴더가 잠깐 사라졌다
                            // 돌아오는 일(외장 드라이브·백신 격리)이 있고, 그때 워처가 죽으면
                            // 이후 저장은 전부 유실된다.
                            log.error("재훑기 실패 {} — 감시는 계속한다", watchDir, e);
                        }
                        continue;
                    }
                    Path file = watchDir.resolve((Path) event.context());
                    if (!SlotFile.isSlotFile(file)) {
                        // slot_*.tfm_backup 이 여기로 온다. 게임이 저장할 때마다 같이 쓰므로
                        // 이 가지는 실제로 매번 지나간다 (D28).
                        continue;
                    }
                    scheduleIngest(file);
                }
                if (!key.reset()) {
                    log.error("감시 대상이 사라졌다. 이제부터 저장은 적재되지 않는다: {}", watchDir);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // stop() 이 닫았다. 정상 종료 경로다.
        } catch (Throwable e) {
            // RuntimeException 만 잡으면 Error 는 JVM 기본 핸들러로 가서 stderr 에만 찍힌다 —
            // 이 앱의 로그에는 한 줄도 안 남고 적재만 조용히 멈춘다.
            log.error("감시 루프가 예기치 못한 오류로 끝났다. 적재가 멈춘다: {}", watchDir, e);
        }
    }

    /**
     * 파일 하나의 적재를 디바운스 뒤로 건다. 이미 걸린 것이 있으면 취소하고 다시 건다 —
     * 그래서 "마지막 변경 기준" 이 된다. "첫 변경 후 고정 지연" 으로 바꾸면 게임이 길게
     * 쓰는 동안 잘린 스트림을 읽게 된다.
     */
    private void scheduleIngest(Path file) {
        pending.compute(file, (key, previous) -> {
            if (previous != null) {
                previous.cancel(false);
            }
            if (stopping || scheduler.isShutdown()) {
                log.warn("정지 중이라 적재를 걸지 않는다: {}", key.getFileName());
                return null;
            }
            // 자기 자신을 가리키는 상자. runIngest 가 "지금 맵에 있는 게 나인가" 를 확인하고
            // 그때만 지우기 위한 것이다. 그냥 remove(file) 하면, 만료 직후 새 이벤트가 걸어둔
            // 다음 타이머의 항목을 지워버려 stop() 이 그 타이머를 못 찾는다.
            // schedule() 이 먼저 반환하고 작업은 debounceMs 뒤에 도니 상자는 그때 이미 차 있다.
            ScheduledFuture<?>[] self = new ScheduledFuture<?>[1];
            self[0] = scheduler.schedule(() -> runIngest(key, self[0]), debounceMs, TimeUnit.MILLISECONDS);
            return self[0];
        });
    }

    private void runIngest(Path file, ScheduledFuture<?> self) {
        pending.remove(file, self);
        if (stopping) {
            return;
        }
        try {
            IngestService.IngestResult result = ingestService.ingest(file);
            if (result.alreadyIngested()) {
                log.debug("변경은 있었지만 내용이 같다: {}", file.getFileName());
            } else {
                log.info("적재 완료 {} — 경기 +{} · 스크림 +{}",
                        file.getFileName(), result.newMatches(), result.newScrims());
            }
        } catch (RuntimeException e) {
            // 삼키지 않는다. 그렇다고 워처를 죽이지도 않는다 — 게임은 60~90초 뒤에 또 저장하고,
            // 그때 같은 파일을 다시 시도한다. 여기서 루프가 죽으면 그 재시도가 영영 없다.
            log.error("적재 실패 {} — 다음 저장 때 다시 시도한다", file.getFileName(), e);
        }
    }

    /**
     * {@code WatchService} 의 {@code OVERFLOW} 처리 — 폴더를 다시 훑어 놓친 변경을 따라잡는다.
     *
     * <p>재훑기도 디바운스를 거친다. 오버플로가 났다는 것은 쓰기가 몰렸다는 뜻이라
     * 지금 이 순간에도 쓰는 중일 수 있다.
     *
     * <p>패키지 전용인 이유: 실제 OS 오버플로는 감시 큐가 찰 만큼 이벤트를 쏟아부어야 나고
     * 그 임계값이 OS·JVM 마다 달라 테스트에서 결정적으로 재현할 수 없다.
     */
    void handleOverflow() {
        for (Path slot : SlotPathResolver.resolve(watchDir)) {
            scheduleIngest(slot);
        }
    }

    /**
     * 감시 스레드와 디바운스 스레드를 멈추고, 둘 다 실제로 끝날 때까지 기다린다.
     * 두 번 불러도 안전하다. 한 번 부르면 이 인스턴스는 끝이다 — {@link #start()} 참고.
     */
    public synchronized void stop() {
        stopping = true;

        // 대기 중인 적재를 먼저 끊는다. 이걸 안 하면 정지 후에 한 박자 늦은 적재가 한 번 더 돈다.
        pending.values().forEach(future -> future.cancel(false));
        pending.clear();
        scheduler.shutdownNow();

        WatchService service = watchService;
        if (service != null) {
            try {
                service.close();   // take() 에서 대기 중인 스레드를 깨운다
            } catch (IOException e) {
                log.warn("감시 서비스를 닫는 중 오류: {}", watchDir, e);
            }
            watchService = null;
        }

        Thread t = thread;
        if (t != null) {
            join(t);
            thread = null;
        }

        // 이미 시작된 적재는 취소가 안 먹는다(cancel 은 대기 중인 것만 끊고, JDBC 는 인터럽트에
        // 반응하지 않는다). 기다리지 않고 stop() 이 반환하면 컨테이너가 DataSource 를 먼저
        // 닫아버려, 멀쩡하던 적재가 종료 순서 때문에 실패한다.
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("진행 중인 적재가 {}초 안에 끝나지 않았다: {}", SHUTDOWN_WAIT_SECONDS, watchDir);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void join(Thread t) {
        try {
            t.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_WAIT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            log.warn("감시 스레드가 {}초 안에 끝나지 않았다: {}", SHUTDOWN_WAIT_SECONDS, watchDir);
        }
    }

    /** 감시 스레드가 살아있는지. {@code stop()} 이 실제로 스레드를 끝냈는지 확인하는 용도. */
    boolean isRunning() {
        Thread t = thread;
        return t != null && t.isAlive();
    }
}
