package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.watcher.TfmProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 적재를 애플리케이션 수명에 거는 자리 중, <b>평소에는 돌지 않는</b> 쪽.
 *
 * <p>평소 경로(워처 · 기동 시 따라잡기)는 {@code watcher/WatcherConfig} 에 있다.
 * 여기 있는 것은 수리용이라 플래그를 켜야 등록된다 — {@code AnalysisConfiguration} 과 같은 모양이다.
 */
@Configuration
public class IngestConfiguration {

    @ConditionalOnProperty(name = "tfm.reingest-on-start", havingValue = "true")
    @Bean
    public ReingestRunner reingestRunner(TfmProperties properties, SlotRegistry slotRegistry, SaveLoader loader) {
        return new ReingestRunner(properties, slotRegistry, loader);
    }
}
