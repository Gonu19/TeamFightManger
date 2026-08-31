package com.teamfighter.tfm.story.gallery;

import com.teamfighter.tfm.story.gallery.GalleryJobs.Status;
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
 * <p>{@link GalleryGenerator} 를 가짜로 둔다. 진짜를 부르면 세이브 파일과 모델이 필요하고,
 * 그 둘은 여기서 검증하려는 것과 아무 상관이 없다.
 */
class GalleryJobsTest {

    /** 부르면 정해진 답을 주는 생성기. 몇 번 불렸는지 센다. */
    private static final class FakeGenerator extends GalleryGenerator {

        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch release;
        private final Optional<Long> result;
        private final RuntimeException failure;

        FakeGenerator(CountDownLatch release, Optional<Long> result, RuntimeException failure) {
            super(null, null, null, null, null);
            this.release = release;
            this.result = result;
            this.failure = failure;
        }

        @Override
        public Optional<Long> writeNext(int slotId, GalleryWriter.Progress progress) {
            calls.incrementAndGet();
            progress.at("이슈 취재 중", 0, 5);
            try {
                // 테스트가 놓아줄 때까지 잡아 둔다 — 진행 중 상태를 관찰하려면 필요하다
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    @Test
    @DisplayName("도는 동안 단계를 알린다")
    void reportsProgressWhileRunning() {
        CountDownLatch hold = new CountDownLatch(1);
        GalleryJobs jobs = new GalleryJobs(new FakeGenerator(hold, Optional.of(7L), null));

        assertThat(jobs.start(1)).isTrue();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            Status status = jobs.status(1).orElseThrow();
            assertThat(status.isRunning()).isTrue();
            assertThat(status.step()).isEqualTo("이슈 취재 중");
        });

        hold.countDown();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(jobs.status(1).orElseThrow().state())
                        .isEqualTo(Status.State.DONE));
        assertThat(jobs.status(1).orElseThrow().batchId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("예외가 나도 진행 중에 머물지 않는다 — FAILED 로 간다")
    void failureEndsInFailedState() {
        // 이것이 이 클래스의 존재 이유다. 예외가 스레드 풀에 조용히 삼켜지면
        // 화면은 "진행 중" 에서 영원히 멈춘다.
        CountDownLatch hold = new CountDownLatch(0);
        GalleryJobs jobs = new GalleryJobs(new FakeGenerator(
                hold, Optional.empty(), new IllegalStateException("세이브 파일을 읽을 수 없다")));

        jobs.start(2);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            Status status = jobs.status(2).orElseThrow();
            assertThat(status.state()).isEqualTo(Status.State.FAILED);
            assertThat(status.message()).contains("세이브 파일");
        });
    }

    @Test
    @DisplayName("뽑을 매치가 없으면 실패가 아니라 NOTHING_TO_DO 다")
    void nothingToDoIsNotAFailure() {
        GalleryJobs jobs = new GalleryJobs(
                new FakeGenerator(new CountDownLatch(0), Optional.empty(), null));

        jobs.start(3);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(jobs.status(3).orElseThrow().state())
                        .isEqualTo(Status.State.NOTHING_TO_DO));
    }

    @Test
    @DisplayName("이미 도는 커리어는 두 번 시작하지 않는다")
    void refusesConcurrentStartsForTheSameSlot() {
        CountDownLatch hold = new CountDownLatch(1);
        FakeGenerator generator = new FakeGenerator(hold, Optional.of(1L), null);
        GalleryJobs jobs = new GalleryJobs(generator);

        assertThat(jobs.start(4)).isTrue();
        await().atMost(Duration.ofSeconds(3))
                .until(() -> jobs.status(4).orElseThrow().isRunning());

        // 두 번째 누름은 거절된다. 둘이 동시에 돌면 같은 매치를 두 번 뽑거나
        // 분당 토큰을 서로 잡아먹는다.
        assertThat(jobs.start(4)).isFalse();

        hold.countDown();
        await().atMost(Duration.ofSeconds(3))
                .until(() -> !jobs.status(4).orElseThrow().isRunning());
        assertThat(generator.calls).hasValue(1);
    }

    @Test
    @DisplayName("한 번도 안 눌렀으면 상태가 없다")
    void noStatusBeforeFirstRun() {
        GalleryJobs jobs = new GalleryJobs(
                new FakeGenerator(new CountDownLatch(0), Optional.empty(), null));

        assertThat(jobs.status(99)).isEmpty();
    }

    @Test
    @DisplayName("진행률은 0~100 안에 있다")
    void percentIsBounded() {
        assertThat(Status.running("x", 0, 5).percent()).isZero();
        assertThat(Status.running("x", 5, 5).percent()).isEqualTo(100);
        // total 이 0 이면 나눗셈이 터진다. 그 경우가 없어야 하지만, 있으면 0 이다.
        assertThat(new Status(Status.State.RUNNING, "x", 3, 0, null, null).percent()).isZero();
    }
}
