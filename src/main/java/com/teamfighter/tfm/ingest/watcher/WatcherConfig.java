package com.teamfighter.tfm.ingest.watcher;

import com.teamfighter.tfm.ingest.IngestService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 워처를 애플리케이션 수명에 건다.
 *
 * <p>{@link SaveWatcher} 에 {@code @Component} 를 붙이지 않고 여기서 만드는 이유는 두 가지다.
 * 생성자가 둘이라(테스트용 {@code (Path, long, IngestService)} 포함) 어느 쪽을 쓸지 명시해야
 * 하고, 시작·정지를 {@code initMethod}/{@code destroyMethod} 로 컨테이너에 맡기면
 * 종료 시 감시 스레드가 확실히 join 된다.
 *
 * <p><b>세이브 폴더가 없으면 앱이 뜨지 않는다.</b> {@link SaveWatcher#start()} 가 던진다.
 * 일부러 그렇다 — 워처가 조용히 안 도는 상태는 이 프로젝트에서 이미 한 번 크게 당한 실패
 * 방식이다(Flyway 가 로그 한 줄 없이 안 돌았던 것). 감시가 안 되면 누적이 안 되고,
 * 누적이 안 되면 이 앱은 존재 이유가 없다.
 *
 * <p>그래서 {@code tfm.watch-enabled=false} 로 끌 수 있는 곳은 통합 테스트뿐이다. 워처가
 * 컨텍스트에 끼면 DB 테스트가 세이브 폴더 존재 여부에 묶인다 — 이 PC 밖에서는 그 폴더가 없다.
 */
@Configuration
public class WatcherConfig {

    @ConditionalOnProperty(name = "tfm.watch-enabled", havingValue = "true", matchIfMissing = true)
    @Bean(initMethod = "start", destroyMethod = "stop")
    public SaveWatcher saveWatcher(TfmProperties properties, IngestService ingestService) {
        return new SaveWatcher(properties, ingestService);
    }
}
