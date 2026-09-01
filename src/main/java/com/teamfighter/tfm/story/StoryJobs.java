package com.teamfighter.tfm.story;

import com.teamfighter.tfm.story.gallery.GalleryGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 생성을 <b>요청 밖에서</b> 돌리고 진행 상황을 들고 있는다. 기사 · 총평 · 갤러리 공용.
 *
 * <h2>왜 셋이 한 자리를 쓰나 — 분당 토큰이 하나다</h2>
 *
 * 무료 티어의 분당 토큰이 8,000 이고 <b>어느 생성이든</b> 그 언저리를 쓴다. 기사와
 * 갤러리를 동시에 돌리면 둘 다 429 를 맞고, 재시도로 서로를 더 밀어낸다. 두 배 빨라지는
 * 것이 아니라 <b>둘 다 느려지고 실패 확률만 오른다.</b>
 *
 * <p>그래서 <b>커리어 하나에 작업 하나</b>다. 갤러리가 도는 중에 기사 버튼을 누르면
 * 거절하고, 그 반대도 같다. 거절은 예외가 아니다 — 버튼을 두 번 누르는 것은 오류가
 * 아니라 흔한 일이고, 화면이 "이미 뽑는 중" 이라고 말하면 된다.
 *
 * <h2>기사도 여기로 온다 (D81)</h2>
 *
 * 갤러리만 이 장치를 갖고 있었다. 기사는 동기 POST 라 브라우저가 20~30초 흰 화면을
 * 물고 있었고, 실패하면 그제서야 한 줄이 떴다 — <b>도는 중인지 멈춘 것인지 구분되지
 * 않았고</b> 그동안 갤러리 버튼도 멀쩡히 눌렸다. 같은 병을 한쪽만 고쳐 두고 있었던 셈이다.
 *
 * <h2>메모리에 둔다. DB 에 두지 않는다</h2>
 *
 * 이 앱은 루프백에만 바인딩되는 <b>1인용 로컬 앱</b>이다(D59). 작업 상태는 앱이 살아 있는
 * 동안만 의미가 있고, 재시작하면 진행 중이던 호출도 함께 죽는다 — 그때 DB 에 남은
 * "진행 중" 은 사실이 아니라 <b>거짓말</b>이 된다. 남길 가치가 있는 것은 결과뿐이고,
 * 결과는 {@code article} · {@code gallery_batch} 에 이미 남는다.
 */
public class StoryJobs {

    private static final Logger log = LoggerFactory.getLogger(StoryJobs.class);

    /**
     * 무엇을 만드는 중인가. <b>화면이 끝난 뒤 어디로 보낼지</b>를 이걸로 정한다 —
     * 기사는 {@code /story/{id}}, 갤러리는 {@code /gallery?batch=}.
     */
    public enum Kind {
        ARTICLE("기사", "기사를"),
        ROUND("총평", "총평을"),
        GALLERY("갤러리 반응", "갤러리 반응을");

        private final String label;
        private final String object;

        Kind(String label, String object) {
            this.label = label;
            this.object = object;
        }

        /** 화면에 쓰는 이름. "기사 — 댓글을 다는 중" 의 그 낱말이다. */
        public String label() {
            return label;
        }

        /**
         * 목적어 꼴. <b>조사를 붙여 둔다.</b>
         *
         * <p>문장에서 이름 뒤에 을/를 을 코드로 이어 붙이면 "기사 를" 처럼 띄어지고,
         * 받침에 따라 을/를 이 갈리는 것도 못 맞춘다("총평를"). 낱말마다 한 번 적어
         * 두는 편이 규칙을 흉내 내는 것보다 짧고 확실하다 — 종류가 셋뿐이다.
         */
        public String object() {
            return object;
        }
    }

    /** 진행 상황 한 조각. 화면이 이걸 그린다. */
    public record Status(State state, Kind kind, String step, int done, int total,
                         Long resultId, String message) {

        public enum State { RUNNING, DONE, NOTHING_TO_DO, FAILED }

        static Status running(Kind kind, String step, int done, int total) {
            return new Status(State.RUNNING, kind, step, done, total, null, null);
        }

        /** 몇 %인가. 화면의 진행 막대가 쓴다. */
        public int percent() {
            return total <= 0 ? 0 : Math.min(100, done * 100 / total);
        }

        public boolean isRunning() {
            return state == State.RUNNING;
        }

        /** 끝나고 갈 곳. 결과가 없으면 {@code null} — 화면은 그 자리에 머문다. */
        public String destination(Integer slotId) {
            if (state != State.DONE || resultId == null) {
                return null;
            }
            return kind == Kind.GALLERY
                    ? "/gallery?slot=" + slotId + "&batch=" + resultId
                    : "/story/" + resultId;
        }
    }

    /**
     * 스레드 하나짜리 풀.
     *
     * <p>둘 이상을 동시에 돌릴 이유가 없다 — 분당 토큰이 하나로 정해져 있어서 병렬로
     * 부르면 서로 429 를 만들 뿐이다. 데몬 스레드로 두어 앱을 끌 때 붙잡지 않는다.
     */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "story-generator");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<Integer, Status> statuses = new ConcurrentHashMap<>();

    private final StoryGenerator articles;
    private final GalleryGenerator galleries;

    public StoryJobs(StoryGenerator articles, GalleryGenerator galleries) {
        this.articles = articles;
        this.galleries = galleries;
    }

    /** 모델 호출 횟수. 기사도 갤러리 조각도 "창작 + 댓글" 둘이다. */
    private static final int CALLS = 2;

    /** <b>지정한 매치</b>의 기사를 쓴다. 연대기 줄의 「기사 쓰기」다. */
    public boolean startArticle(int slotId, int season, int day, int teamA, int teamB) {
        return submit(slotId, Kind.ARTICLE, CALLS, progress ->
                articles.writeFor(slotId, season, day, teamA, teamB, progress));
    }

    /** 아직 총평이 없는 날 중 가장 최근 하루. */
    public boolean startRound(int slotId) {
        return submit(slotId, Kind.ROUND, CALLS, progress ->
                articles.writeLatestRoundSummary(slotId, progress));
    }

    /** <b>지정한 매치</b>의 갤러리. 연대기 줄의 「반응 생성」이다. */
    public boolean startGallery(int slotId, int season, int day, int teamA, int teamB) {
        return submit(slotId, Kind.GALLERY, galleryCalls(), progress ->
                galleries.writeFor(slotId, season, day, teamA, teamB, progress));
    }

    /** 아직 갤이 없는 매치 중 가장 최근. 갤러리 화면의 「반응 불러오기」다. */
    public boolean startGalleryNext(int slotId) {
        return submit(slotId, Kind.GALLERY, galleryCalls(), progress ->
                galleries.writeNext(slotId, progress));
    }

    private static int galleryCalls() {
        return com.teamfighter.tfm.story.gallery.GalleryChunk.page().size();
    }

    /** 지금 어디까지 갔나. 아직 한 번도 안 눌렀으면 {@link Optional#empty()}. */
    public Optional<Status> status(int slotId) {
        return Optional.ofNullable(statuses.get(slotId));
    }

    /** 지금 이 커리어에서 무언가 돌고 있나. 화면이 버튼을 잠글지 정할 때 쓴다. */
    public boolean isBusy(int slotId) {
        Status now = statuses.get(slotId);
        return now != null && now.isRunning();
    }

    /**
     * 자리를 잡고 작업을 띄운다.
     *
     * <p><b>자리는 종류를 안 가린다.</b> 갤러리가 도는 중에 기사를 눌러도 거절된다 —
     * 분당 토큰이 하나라 그 둘은 서로의 경쟁자다.
     *
     * @return 시작했으면 {@code true}. 이미 무언가 돌고 있으면 {@code false}
     */
    private boolean submit(int slotId, Kind kind, int calls,
                           Function<Progress, Optional<Long>> work) {
        Status now = statuses.get(slotId);
        if (now != null && now.isRunning()) {
            return false;
        }
        statuses.put(slotId, Status.running(kind, "시작하는 중", 0, calls));
        pool.submit(() -> run(slotId, kind, calls, work));
        return true;
    }

    /**
     * 작업 본체. <b>어떤 예외도 이 메서드 밖으로 안 나간다</b> — 나가면 스레드 풀이
     * 조용히 삼키고, 화면은 "진행 중" 에서 영원히 멈춘다. 그것이 이 클래스가 고치려는
     * 바로 그 증상이다.
     */
    private void run(int slotId, Kind kind, int calls,
                     Function<Progress, Optional<Long>> work) {
        try {
            Optional<Long> id = work.apply((step, done, total) ->
                    statuses.put(slotId, Status.running(kind, step, done, total)));

            statuses.put(slotId, id
                    .map(value -> new Status(Status.State.DONE, kind, "끝났다",
                            calls, calls, value, null))
                    .orElseGet(() -> new Status(Status.State.NOTHING_TO_DO, kind, "끝났다",
                            calls, calls, null, nothingToDo(kind))));

        } catch (RuntimeException e) {
            // 화면이 읽을 수 있게 메시지를 남기고, 원인은 스택과 함께 로그로 간다.
            // 삼키는 것과 다르다 — 사용자가 할 일("키를 확인한다")이 화면에서 읽혀야 한다.
            log.error("슬롯 {} {} 생성 실패", slotId, kind, e);
            statuses.put(slotId, new Status(Status.State.FAILED, kind, "실패", 0, calls, null,
                    reason(e)));
        }
    }

    /** "할 일이 없다" 는 실패가 아니다. 종류마다 뜻이 달라서 문구도 다르다. */
    private static String nothingToDo(Kind kind) {
        return switch (kind) {
            case ARTICLE -> "그 매치를 세이브에서 못 찾았다. 세이브가 바뀌었거나 세트 기록이 버려졌다 (D6).";
            case ROUND -> "총평을 쓸 날이 없다. 경기가 둘 이상인 날은 모두 정리했다.";
            case GALLERY -> "뽑을 매치가 없거나 이번 호출이 전부 실패했다.";
        };
    }

    /**
     * 사람이 읽을 실패 이유.
     *
     * <p><b>맨 밑의 원인까지 내려간다.</b> 감싸인 예외의 겉껍질만 보여주면
     * "UncheckedIOException" 같은 말이 뜨는데, 그건 사용자가 할 일을 못 정한다.
     * 키가 없다 · 파일이 없다 · 429 가 안 풀린다 는 서로 다른 대응을 요구한다.
     */
    private static String reason(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String type = root.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }
}
