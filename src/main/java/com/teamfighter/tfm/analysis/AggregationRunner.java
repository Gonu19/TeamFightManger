package com.teamfighter.tfm.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 기동 시 집계를 한 번 돌린다. <b>기본은 꺼져 있다.</b>
 *
 * <p>워처의 {@code StartupCatchUp}(D39)과 같은 모양이지만 기본값이 반대다. 따라잡기는
 * 안 돌면 이 앱이 존재 이유를 잃지만, 집계는 <b>언제 돌릴지가 아직 안 정해졌다</b> —
 * 적재 직후마다인지, 화면 요청 때인지, 수동인지는 화면이 생겨야 판단할 수 있다.
 * 정해지지 않은 것을 기본값으로 굳히지 않는다.
 *
 * <p>그때까지 이 플래그가 하는 일은 하나다: 실제 데이터로 한 번 돌려보는 것.
 *
 * <pre>gradlew.bat bootRun --args="--tfm.aggregate-on-start=true"</pre>
 *
 * <p>예외를 삼키지 않는다. 집계가 실패했는데 앱이 멀쩡히 떠 있으면 화면에는 이전 집계
 * 결과가 그대로 남아 있고, 그건 성공과 구별되지 않는다.
 */
public class AggregationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AggregationRunner.class);

    private final CounterAggregationService service;

    public AggregationRunner(CounterAggregationService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("기동 시 집계를 돌린다 (tfm.aggregate-on-start=true)");
        CounterAggregationService.Result result = service.run();
        log.info("집계 결과 — run={} · 커리어 {}행 · 전체 {}행",
                result.aggRunId(), result.careerRows(), result.globalRows());
    }
}
