package com.teamfighter.tfm.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AggregationRunner} 가 올바른 조건으로만 배선되는지 고정한다.
 *
 * <p>DB 는 필요 없다.
 *
 * <p><b>기본값이 꺼짐이라는 것이 이 테스트의 본론이다.</b> 워처의 {@code StartupCatchUp}(D39)과
 * 모양이 같아서 무심코 {@code matchIfMissing = true} 로 맞추기 쉬운데, 그러면 집계를 언제
 * 돌릴지 정하지도 않고 "기동할 때마다" 로 굳혀버리게 된다. 경기가 쌓이면 기동이 그만큼
 * 느려지는데, 그건 느린 것이지 고장 난 것이 아니라 아무도 문제로 보지 않는다.
 *
 * <p>{@link ApplicationContextRunner} 는 {@code ApplicationRunner} 빈을 부르지 않는다 —
 * 그건 {@code SpringApplication.run()} 의 일이다. 그래서 "등록되는가" 와 "부르면 무엇을
 * 하는가" 를 나눠서 본다 ({@code StartupCatchUpTest} 와 같은 방식).
 */
class AggregationRunnerTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(AnalysisConfiguration.class)
            .withBean(AggregationService.class, () -> mock(AggregationService.class));

    @Test
    @DisplayName("플래그가 없으면 빈이 없다 — 집계는 기본으로 돌지 않는다")
    void runnerIsAbsentByDefault() {
        // 변조: matchIfMissing = true 로 바꾸면 이 단언이 깨진다. 워처와 같은 모양이라
        //       실수하기 쉬운 자리다.
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(AggregationRunner.class));
    }

    @Test
    @DisplayName("tfm.aggregate-on-start=false 여도 빈이 없다")
    void runnerIsAbsentWhenExplicitlyDisabled() {
        context.withPropertyValues("tfm.aggregate-on-start=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AggregationRunner.class));
    }

    @Test
    @DisplayName("tfm.aggregate-on-start=true 면 ApplicationRunner 로 등록된다")
    void runnerIsRegisteredWhenEnabled() {
        context.withPropertyValues("tfm.aggregate-on-start=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(AggregationRunner.class));
    }

    @Test
    @DisplayName("run() 은 집계 서비스에 위임한다")
    void run_delegatesToService() {
        AggregationService service = mock(AggregationService.class);
        when(service.run()).thenReturn(new AggregationService.Result(7L, 100, 20, 0));

        new AggregationRunner(service).run(new DefaultApplicationArguments());

        // 변조: run() 을 빈 메서드로 두면 플래그를 켜도 아무 일이 안 일어난다.
        //       로그에는 "집계를 돌린다" 만 찍혀서 성공처럼 보인다.
        verify(service).run();
    }

    @Test
    @DisplayName("집계가 던지면 삼키지 않는다 — 기동을 막는다")
    void run_doesNotSwallowFailure() {
        AggregationService service = mock(AggregationService.class);
        doThrow(new IllegalStateException("집계 실패")).when(service).run();

        // 변조: try-catch 로 감싸 로그만 남기면 앱이 멀쩡히 뜨고, 화면에는 이전 집계
        //       결과가 그대로 남는다 — 성공과 구별되지 않는다.
        assertThatThrownBy(() -> new AggregationRunner(service).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
    }
}
