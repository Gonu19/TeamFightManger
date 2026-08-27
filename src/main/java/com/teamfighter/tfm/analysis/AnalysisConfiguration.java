package com.teamfighter.tfm.analysis;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 집계를 애플리케이션 수명에 거는 유일한 자리.
 *
 * <p>{@link CounterAggregationService} 자체는 {@code @Service} 라 늘 있다 — 있다고 도는
 * 것은 아니다. 무언가가 부르지 않으면 아무 일도 안 한다. 지금 부르는 곳은 여기와
 * 통합 테스트뿐이고, 여기도 <b>플래그를 켜야</b> 등록된다.
 */
@Configuration
public class AnalysisConfiguration {

    @ConditionalOnProperty(name = "tfm.aggregate-on-start", havingValue = "true")
    @Bean
    public AggregationRunner aggregationRunner(CounterAggregationService service) {
        return new AggregationRunner(service);
    }
}
