package com.teamfighter.tfm.analysis;

import com.teamfighter.tfm.analysis.counter.CounterCalculator;
import com.teamfighter.tfm.analysis.counter.CounterRow;
import com.teamfighter.tfm.analysis.counter.MatchupAggregate;
import com.teamfighter.tfm.analysis.counter.MatchupAggregator;
import com.teamfighter.tfm.analysis.dao.AggRunRecorder;
import com.teamfighter.tfm.analysis.dao.AnalysisConfigDao;
import com.teamfighter.tfm.analysis.dao.CounterWriter;
import com.teamfighter.tfm.analysis.dao.MatchObservationDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 카운터 집계 한 바퀴.
 *
 * <p>스코프마다 답이 다르다. {@code CAREER} 는 그 커리어 안의 상성이고, {@code GLOBAL} 은
 * 슬롯을 합친 상성이다 — 합칠 때 감쇠는 <b>슬롯별로 각자의 기준 시점</b>으로 이미 끝나 있다
 * (D45). 그리고 기저 강도는 스코프마다 따로 잡는다. 커리어 안의 "센 챔피언" 과 전체의
 * "센 챔피언" 은 다른 값이고, 그걸 섞으면 어느 쪽도 아닌 잔차가 나온다.
 *
 * <p>스크림 포함 여부도 둘 다 계산한다. 스키마가 {@code include_scrim} 을 유일키에 넣어둔
 * 것이 그 뜻이다 — 화면에서 껐다 켰다 하려면 두 벌이 미리 있어야 한다. 공식전만 698경기,
 * 스크림까지 1,110경기라 어느 쪽을 보느냐로 표가 실제로 달라진다.
 *
 * <p><b>한 트랜잭션이다.</b> 도중에 죽으면 아무것도 안 바뀐다. 절반만 갱신된 카운터 표가
 * 화면에 뜨는 것보다 낫고, 실패는 예외로 올라가므로 조용하지 않다.
 *
 * <p><b>아직 아무도 이걸 부르지 않는다.</b> 언제 돌릴지는 정하지 않았다 — 적재 직후마다
 * 돌릴지, 화면 요청 때 돌릴지, 수동으로 돌릴지는 화면이 생겨야 판단할 수 있다.
 * 그때까지는 통합 테스트가 유일한 호출자다.
 */
@Service
public class CounterAggregationService {

    private static final Logger log = LoggerFactory.getLogger(CounterAggregationService.class);

    private final AnalysisConfigDao configDao;
    private final MatchObservationDao observations;
    private final CounterWriter writer;
    private final AggRunRecorder runs;

    public CounterAggregationService(
            AnalysisConfigDao configDao,
            MatchObservationDao observations,
            CounterWriter writer,
            AggRunRecorder runs) {
        this.configDao = configDao;
        this.observations = observations;
        this.writer = writer;
        this.runs = runs;
    }

    /** 한 번의 집계가 무엇을 썼는지. */
    public record Result(long aggRunId, int careerRows, int globalRows) {
    }

    @Transactional
    public Result run() {
        AnalysisConfig config = configDao.load();
        long runId = runs.start(config, "카운터 집계 (D14)");
        List<Integer> slotIds = observations.slotIds();

        int careerRows = 0;
        int globalRows = 0;
        for (boolean includeScrim : new boolean[] { true, false }) {
            List<MatchupAggregate> perSlot = new ArrayList<>(slotIds.size());

            for (int slotId : slotIds) {
                MatchupAggregate aggregate = aggregateSlot(slotId, includeScrim, config);
                perSlot.add(aggregate);

                List<CounterRow> rows = CounterCalculator.calculate(aggregate, config);
                careerRows += writer.write(
                        AggScope.CAREER, slotId, null, includeScrim, runId, rows);
            }

            MatchupAggregate global = MatchupAggregate.merge(perSlot);
            List<CounterRow> globalCounters = CounterCalculator.calculate(global, config);
            globalRows += writer.write(
                    AggScope.GLOBAL, null, null, includeScrim, runId, globalCounters);
        }

        runs.finish(runId);
        log.info("카운터 집계 완료 run={} — 커리어 {}행 · 전체 {}행 (슬롯 {}개)",
                runId, careerRows, globalRows, slotIds.size());
        return new Result(runId, careerRows, globalRows);
    }

    private MatchupAggregate aggregateSlot(int slotId, boolean includeScrim, AnalysisConfig config) {
        ReferencePoint reference = observations.latestReference(slotId);
        List<MatchObservation> matches = observations.loadMatches(slotId, includeScrim);
        log.debug("슬롯 {} — 경기 {}건 · 기준 패치 {} · 스크림포함 {}",
                slotId, matches.size(), reference.patchSeq(), includeScrim);
        return MatchupAggregator.aggregate(matches, reference, config);
    }
}
