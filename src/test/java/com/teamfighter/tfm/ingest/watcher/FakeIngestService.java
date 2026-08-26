package com.teamfighter.tfm.ingest.watcher;

import com.teamfighter.tfm.ingest.IngestService;

import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link SaveWatcher} 테스트용 {@link IngestService} 대역.
 *
 * <p>DB 를 쓰지 않는다 — 절대 규칙 4. 호출 여부·횟수·인자만 기록한다.
 * 워처는 백그라운드 스레드에서 {@code ingest()} 를 부르므로 기록 자료구조는
 * 스레드 안전해야 한다 ({@link CopyOnWriteArrayList}).
 */
final class FakeIngestService implements IngestService {

    private final List<Path> calls = new CopyOnWriteArrayList<>();
    private final Queue<RuntimeException> exceptionsToThrowOnce = new ConcurrentLinkedQueue<>();

    private final AtomicReference<CountDownLatch[]> blockOnce = new AtomicReference<>();

    /**
     * 지금 이 순간 {@code ingest()} 안에 들어와 있는 호출 수. 워처와 재훑기(rescan)가
     * 정말 같은 단일 스레드 스케줄러를 타는지 재는 용도다 — 별도 스레드로 바뀌면
     * 이 값이 1을 넘는 순간이 생긴다.
     */
    private final AtomicInteger concurrentCalls = new AtomicInteger();
    private final AtomicInteger maxConcurrentCalls = new AtomicInteger();

    @Override
    public IngestResult ingest(Path saveFile) {
        calls.add(saveFile);
        int inFlight = concurrentCalls.incrementAndGet();
        maxConcurrentCalls.updateAndGet(prevMax -> Math.max(prevMax, inFlight));
        try {
            blockIfAsked();
            RuntimeException toThrow = exceptionsToThrowOnce.poll();
            if (toThrow != null) {
                throw toThrow;
            }
            return IngestResult.duplicate(1);
        } finally {
            concurrentCalls.decrementAndGet();
        }
    }

    /** 지금까지 관측된 {@code ingest()} 동시 진입 최댓값. */
    int maxConcurrentCalls() {
        return maxConcurrentCalls.get();
    }

    List<Path> calls() {
        return calls;
    }

    int callCount() {
        return calls.size();
    }

    long countCallsFor(Path path) {
        return calls.stream().filter(path::equals).count();
    }

    /**
     * 다음 {@code ingest()} 호출 한 번을 붙잡아 둔다. {@code entered} 는 적재가 실제로
     * 시작됐을 때 내려가고, {@code release} 가 내려가야 반환한다.
     *
     * <p><b>인터럽트에 반응하지 않는다.</b> 일부러 그렇다 — {@code scheduler.shutdownNow()} 가
     * 실행 중인 작업을 인터럽트하는데, 실제 적재가 하는 일(JDBC 왕복)은 그 인터럽트로
     * 중단되지 않는다. 인터럽트에 순순히 끝나는 대역을 쓰면 실제로는 안 끝나는 상황을
     * 검증하지 못한다.
     */
    void blockOnNextCall(CountDownLatch entered, CountDownLatch release) {
        blockOnce.set(new CountDownLatch[] {entered, release});
    }

    private void blockIfAsked() {
        CountDownLatch[] latches = blockOnce.getAndSet(null);
        if (latches == null) {
            return;
        }
        latches[0].countDown();
        boolean interrupted = false;
        while (true) {
            try {
                latches[1].await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;   // 삼키지 않고 끝에서 되살린다
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 다음 {@code ingest()} 호출 한 번만 이 예외를 던지게 한다. */
    void throwOnNextCall(RuntimeException exception) {
        exceptionsToThrowOnce.add(exception);
    }
}
