package com.teamfighter.tfm.story.gallery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 갤러리 생성을 <b>요청 밖에서</b> 돌리고 진행 상황을 들고 있는다.
 *
 * <h2>왜 필요한가 — 첫 실물이 이걸 가르쳤다</h2>
 *
 * 페이지 하나가 모델 호출 다섯이고, 무료 티어의 분당 토큰 8,000 안에서 그건 산술적으로
 * <b>최소 3분</b>이다. 429 재시도가 붙으면 더 간다. 그것을 요청 스레드 안에서 하면
 * 브라우저는 그동안 <b>스피너만 돌린다</b> — 실제로 첫 시도가 그렇게 보였고,
 * 사용자에게 그것은 "멈췄다" 와 구분되지 않는다.
 *
 * <p>그래서 버튼은 <b>작업을 시작만</b> 하고 곧바로 돌아온다. 화면은 몇 초마다 상태를
 * 물어 "이슈 취재 중 · 1/5" 같은 단계를 그린다. 모드도 같은 모양이다 —
 * 브라우저에서 비동기로 부르고 상태 문구를 갱신한다.
 *
 * <h2>메모리에 둔다. DB 에 두지 않는다</h2>
 *
 * 이 앱은 루프백에만 바인딩되는 <b>1인용 로컬 앱</b>이다(D59). 작업 상태는 앱이 살아 있는
 * 동안만 의미가 있고, 재시작하면 진행 중이던 호출도 함께 죽는다 — 그때 DB 에 남은
 * "진행 중" 은 사실이 아니라 <b>거짓말</b>이 된다. 남길 가치가 있는 것은 결과뿐이고,
 * 결과는 {@code gallery_batch} 에 이미 남는다.
 *
 * <h2>커리어당 하나만 돈다</h2>
 *
 * 같은 슬롯에 두 번 누르면 두 번째는 거절한다. 둘이 동시에 돌면 같은 매치를 두 번 뽑거나
 * (둘 다 "안 뽑은 것 중 최근" 을 고른다) 분당 토큰을 서로 잡아먹어 둘 다 느려진다.
 */
public class GalleryJobs {

    private static final Logger log = LoggerFactory.getLogger(GalleryJobs.class);

    /** 진행 상황 한 조각. 화면이 이걸 그린다. */
    public record Status(State state, String step, int done, int total,
                         Long batchId, String message) {

        public enum State { RUNNING, DONE, NOTHING_TO_DO, FAILED }

        static Status running(String step, int done, int total) {
            return new Status(State.RUNNING, step, done, total, null, null);
        }

        /** 몇 %인가. 화면의 진행 막대가 쓴다. */
        public int percent() {
            return total <= 0 ? 0 : Math.min(100, done * 100 / total);
        }

        public boolean isRunning() {
            return state == State.RUNNING;
        }
    }

    /**
     * 스레드 하나짜리 풀.
     *
     * <p>둘 이상을 동시에 돌릴 이유가 없다 — 분당 토큰이 하나로 정해져 있어서 병렬로
     * 부르면 서로 429 를 만들 뿐이다. 데몬 스레드로 두어 앱을 끌 때 붙잡지 않는다.
     */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gallery-generator");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<Integer, Status> statuses = new ConcurrentHashMap<>();

    private final GalleryGenerator generator;

    public GalleryJobs(GalleryGenerator generator) {
        this.generator = generator;
    }

    /**
     * 그 커리어의 갤러리 생성을 시작한다.
     *
     * @return 시작했으면 {@code true}. 이미 돌고 있으면 {@code false} —
     *         <b>예외가 아니다.</b> 버튼을 두 번 누른 것은 오류가 아니라 흔한 일이다
     */
    public boolean start(int slotId) {
        return submit(slotId, progress -> generator.writeNext(slotId, progress));
    }

    /**
     * <b>지정한 매치</b>의 갤러리 생성을 시작한다. 연대기 화면의 줄마다 붙은 버튼이다.
     *
     * <p>{@link #start(int)} 와 같은 자리(커리어 하나에 작업 하나)를 쓴다. 매치마다
     * 따로 돌게 하면 버튼 셋을 연달아 눌러 셋이 동시에 도는데, 분당 토큰이 하나로
     * 정해져 있어 그건 셋 다 429 를 만드는 길이다.
     */
    public boolean startFor(int slotId, int season, int day, int teamA, int teamB) {
        return submit(slotId, progress ->
                generator.writeFor(slotId, season, day, teamA, teamB, progress));
    }

    private boolean submit(int slotId, java.util.function.Function<
            GalleryWriter.Progress, Optional<Long>> work) {
        Status now = statuses.get(slotId);
        if (now != null && now.isRunning()) {
            return false;
        }
        statuses.put(slotId, Status.running("시작하는 중", 0, GalleryChunk.page().size() + 1));
        pool.submit(() -> run(slotId, work));
        return true;
    }

    /** 지금 어디까지 갔나. 아직 한 번도 안 눌렀으면 {@link Optional#empty()}. */
    public Optional<Status> status(int slotId) {
        return Optional.ofNullable(statuses.get(slotId));
    }

    /**
     * 작업 본체. <b>어떤 예외도 이 메서드 밖으로 안 나간다</b> — 나가면 스레드 풀이
     * 조용히 삼키고, 화면은 "진행 중" 에서 영원히 멈춘다. 그것이 이 클래스가 고치려는
     * 바로 그 증상이다.
     */
    private void run(int slotId, java.util.function.Function<
            GalleryWriter.Progress, Optional<Long>> work) {
        try {
            Optional<Long> batchId = work.apply(
                    (step, done, total) -> statuses.put(slotId, Status.running(step, done, total)));

            statuses.put(slotId, batchId
                    .map(id -> new Status(Status.State.DONE, "끝났다", 1, 1, id, null))
                    .orElseGet(() -> new Status(Status.State.NOTHING_TO_DO, "끝났다", 1, 1, null,
                            "뽑을 매치가 없거나 이번 호출이 전부 실패했다.")));

        } catch (RuntimeException e) {
            // 화면이 읽을 수 있게 메시지를 남기고, 원인은 스택과 함께 로그로 간다.
            // 삼키는 것과 다르다 — 사용자가 할 일("키를 확인한다")이 화면에서 읽혀야 한다.
            log.error("슬롯 {} 갤러리 생성 실패", slotId, e);
            statuses.put(slotId, new Status(Status.State.FAILED, "실패", 0, 1, null,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
}
