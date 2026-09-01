package com.teamfighter.tfm.story;

import com.teamfighter.tfm.story.StoryJobs.Kind;
import com.teamfighter.tfm.story.StoryJobs.Status;
import com.teamfighter.tfm.story.gallery.GalleryGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 백그라운드 작업의 계약. <b>이 클래스가 고치려는 증상은 하나다</b> —
 * 버튼을 눌렀는데 화면이 몇 분 동안 아무 말도 안 하는 것.
 *
 * <p>그래서 여기서 보는 것은 생성 품질이 아니라 <b>상태가 반드시 끝난 값에 도달하는가</b>
 * 이다. 실패해도, 뽑을 것이 없어도, 예외가 나도 {@code RUNNING} 에 머물면 안 된다 —
 * 머무르면 화면의 진행 막대가 영원히 돈다.
 *
 * <p><b>기사도 같은 자리를 쓴다 (D81).</b> 분당 토큰이 하나라 기사와 갤러리가 동시에
 * 돌면 서로 429 를 만든다. 그래서 종류를 안 가리고 커리어 하나에 작업 하나다 —
 * 그 규칙을 여기서 고정한다.
 *
 * <p>생성기 둘을 가짜로 둔다. 진짜를 부르면 세이브 파일과 모델이 필요하고,
 * 그 둘은 여기서 검증하려는 것과 아무 상관이 없다.
 */
class StoryJobsTest {

    /** 부르면 정해진 답을 주는 갤러리 생성기. 몇 번 불렸는지 센다. */
    private static final class FakeGallery extends GalleryGenerator {

        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch release;
        private final Optional<Long> result;
        private final RuntimeException failure;

        FakeGallery(CountDownLatch release, Optional<Long> result, RuntimeException failure) {
            super(null, null, null, null, null);
            this.release = release;
            this.result = result;
            this.failure = failure;
        }

        @Override
        public Optional<Long> writeNext(int slotId, Progress progress) {
            calls.incrementAndGet();
            progress.at("이슈 취재 중", 0, 5);
            hold(release);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    /** 부르면 정해진 답을 주는 기사 생성기. */
    private static final class FakeArticles extends StoryGenerator {

        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch release;
        private final Optional<Long> result;
        private final RuntimeException failure;

        FakeArticles(CountDownLatch release, Optional<Long> result, RuntimeException failure) {
            super(null, null, null, null);
            this.release = release;
            this.result = result;
            this.failure = failure;
        }

        @Override
        public Optional<Long> writeFor(int slotId, int season, int day, int teamA, int teamB,
                                       Progress progress) {
            calls.incrementAndGet();
            progress.at("기사를 쓰는 중", 0, 2);
            hold(release);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    /** 테스트가 놓아줄 때까지 잡아 둔다 — 진행 중 상태를 관찰하려면 필요하다. */
    private static void hold(CountDownLatch release) {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static StoryJobs jobs(FakeArticles articles, FakeGallery gallery) {
        return new StoryJobs(articles, gallery);
    }

    private static FakeArticles idleArticles() {
        return new FakeArticles(new CountDownLatch(0), Optional.of(1L), null);
    }

    private static FakeGallery idleGallery() {
        return new FakeGallery(new CountDownLatch(0), Optional.of(1L), null);
    }

    @Test
    @DisplayName("도는 동안 단계를 알린다")
    void reportsProgressWhileRunning() {
        CountDownLatch hold = new CountDownLatch(1);
        StoryJobs jobs = jobs(idleArticles(), new FakeGallery(hold, Optional.of(7L), null));

        assertThat(jobs.startGalleryNext(1)).isTrue();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            Status status = jobs.status(1).orElseThrow();
            assertThat(status.isRunning()).isTrue();
            assertThat(status.step()).isEqualTo("이슈 취재 중");
            assertThat(status.kind()).isEqualTo(Kind.GALLERY);
        });

        hold.countDown();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(jobs.status(1).orElseThrow().state())
                        .isEqualTo(Status.State.DONE));
        assertThat(jobs.status(1).orElseThrow().resultId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("기사도 같은 장치를 쓴다 — 단계와 결과가 나온다")
    void articlesReportProgressToo() {
        // 전에는 기사가 동기 POST 라 브라우저가 20~30초 흰 화면을 물고 있었다 (D81).
        CountDownLatch hold = new CountDownLatch(1);
        StoryJobs jobs = jobs(new FakeArticles(hold, Optional.of(42L), null), idleGallery());

        assertThat(jobs.startArticle(1, 2026, 7, 10, 11)).isTrue();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            Status status = jobs.status(1).orElseThrow();
            assertThat(status.isRunning()).isTrue();
            assertThat(status.kind()).isEqualTo(Kind.ARTICLE);
            assertThat(status.step()).isEqualTo("기사를 쓰는 중");
        });

        hold.countDown();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(jobs.status(1).orElseThrow().state()).isEqualTo(Status.State.DONE));
        assertThat(jobs.status(1).orElseThrow().destination(1)).isEqualTo("/story/42");
    }

    @Test
    @DisplayName("갤러리가 도는 중에는 기사를 시작하지 않는다 — 분당 토큰이 하나다")
    void oneJobPerSlotRegardlessOfKind() {
        // 이것이 D81 의 핵심이다. 전에는 갤러리만 잠금을 갖고 있어서, 갤이 도는 중에도
        // 기사 버튼이 멀쩡히 눌렸고 둘이 서로 429 를 만들었다.
        CountDownLatch hold = new CountDownLatch(1);
        FakeArticles articles = new FakeArticles(new CountDownLatch(0), Optional.of(1L), null);
        StoryJobs jobs = jobs(articles, new FakeGallery(hold, Optional.of(7L), null));

        assertThat(jobs.startGalleryNext(5)).isTrue();
        await().atMost(Duration.ofSeconds(3)).until(() -> jobs.isBusy(5));

        assertThat(jobs.startArticle(5, 2026, 7, 10, 11)).isFalse();
        assertThat(jobs.startRound(5)).isFalse();
        assertThat(articles.calls).hasValue(0);

        hold.countDown();
        await().atMost(Duration.ofSeconds(3)).until(() -> !jobs.isBusy(5));
    }

    @Test
    @DisplayName("다른 커리어는 서로 막지 않는다")
    void differentSlotsDoNotBlockEachOther() {
        // 잠금은 커리어 단위다. 슬롯이 다르면 같은 매치를 두 번 뽑을 일이 없다.
        CountDownLatch hold = new CountDownLatch(1);
        StoryJobs jobs = jobs(new FakeArticles(hold, Optional.of(1L), null), idleGallery());

        assertThat(jobs.startArticle(10, 2026, 7, 1, 2)).isTrue();
        await().atMost(Duration.ofSeconds(3)).until(() -> jobs.isBusy(10));

        assertThat(jobs.isBusy(11)).isFalse();

        hold.countDown();
    }

    @Test
    @DisplayName("예외가 나도 진행 중에 머물지 않는다 — FAILED 로 간다")
    void failureEndsInFailedState() {
        // 이것이 이 클래스의 존재 이유다. 예외가 스레드 풀에 조용히 삼켜지면
        // 화면은 "진행 중" 에서 영원히 멈춘다.
        StoryJobs jobs = jobs(idleArticles(), new FakeGallery(
                new CountDownLatch(0), Optional.empty(),
                new IllegalStateException("세이브 파일을 읽을 수 없다")));

        jobs.startGalleryNext(2);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            Status status = jobs.status(2).orElseThrow();
            assertThat(status.state()).isEqualTo(Status.State.FAILED);
            assertThat(status.message()).contains("세이브 파일");
        });
    }

    @Test
    @DisplayName("실패 이유는 맨 밑의 원인까지 내려간다")
    void theReasonReachesTheRootCause() {
        // 겉껍질만 보여주면 "UncheckedIOException" 이 뜨는데, 그걸로는 사용자가
        // 할 일을 못 정한다. 키가 없다 · 파일이 없다 · 429 가 안 풀린다 는 다른 대응이다.
        StoryJobs jobs = jobs(idleArticles(), new FakeGallery(
                new CountDownLatch(0), Optional.empty(),
                new IllegalStateException("겉껍질",
                        new java.io.UncheckedIOException(
                                new java.io.IOException("TFM_GROQ_API_KEY 가 없다")))));

        jobs.startGalleryNext(6);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(jobs.status(6).orElseThrow().message())
                        .contains("TFM_GROQ_API_KEY"));
    }

    @Test
    @DisplayName("뽑을 매치가 없으면 실패가 아니라 NOTHING_TO_DO 다")
    void nothingToDoIsNotAFailure() {
        StoryJobs jobs = jobs(idleArticles(),
                new FakeGallery(new CountDownLatch(0), Optional.empty(), null));

        jobs.startGalleryNext(3);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(jobs.status(3).orElseThrow().state())
                        .isEqualTo(Status.State.NOTHING_TO_DO));
    }

    @Test
    @DisplayName("할 일이 없다는 문구는 종류마다 다르다")
    void theNothingToDoMessageDependsOnTheKind() {
        // "뽑을 매치가 없다" 를 기사에도 쓰면 사용자가 할 일을 못 찾는다 —
        // 기사가 빈손인 것은 세이브에서 그 매치를 못 찾았다는 뜻이다.
        StoryJobs jobs = jobs(
                new FakeArticles(new CountDownLatch(0), Optional.empty(), null), idleGallery());

        jobs.startArticle(7, 2026, 7, 1, 2);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(jobs.status(7).orElseThrow().message()).contains("세이브"));
    }

    @Test
    @DisplayName("한 번도 안 눌렀으면 상태가 없다")
    void noStatusBeforeFirstRun() {
        assertThat(jobs(idleArticles(), idleGallery()).status(99)).isEmpty();
    }

    @Test
    @DisplayName("진행률은 0~100 안에 있다")
    void percentIsBounded() {
        assertThat(Status.running(Kind.GALLERY, "x", 0, 5).percent()).isZero();
        assertThat(Status.running(Kind.GALLERY, "x", 5, 5).percent()).isEqualTo(100);
        // total 이 0 이면 나눗셈이 터진다. 그 경우가 없어야 하지만, 있으면 0 이다.
        assertThat(new Status(Status.State.RUNNING, Kind.GALLERY, "x", 3, 0, null, null)
                .percent()).isZero();
    }

    @Test
    @DisplayName("끝나고 갈 곳은 종류가 정한다")
    void theDestinationFollowsTheKind() {
        assertThat(new Status(Status.State.DONE, Kind.ARTICLE, "끝", 2, 2, 42L, null)
                .destination(1)).isEqualTo("/story/42");
        assertThat(new Status(Status.State.DONE, Kind.GALLERY, "끝", 2, 2, 7L, null)
                .destination(1)).isEqualTo("/gallery?slot=1&batch=7");
        // 아직 안 끝났으면 갈 곳이 없다 — 화면은 그 자리에 머문다.
        assertThat(Status.running(Kind.ARTICLE, "x", 0, 2).destination(1)).isNull();
    }
}
