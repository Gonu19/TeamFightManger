package com.teamfighter.tfm.analysis;

import com.teamfighter.tfm.analysis.counter.CounterCalculator;
import com.teamfighter.tfm.analysis.counter.MatchupAggregate;
import com.teamfighter.tfm.analysis.counter.MatchupAggregator;
import com.teamfighter.tfm.analysis.dao.AggRunRecorder;
import com.teamfighter.tfm.analysis.dao.AnalysisConfigDao;
import com.teamfighter.tfm.analysis.dao.CounterWriter;
import com.teamfighter.tfm.analysis.dao.MatchObservationDao;
import com.teamfighter.tfm.analysis.dao.PerformanceWriter;
import com.teamfighter.tfm.analysis.performance.ChampionTally;
import com.teamfighter.tfm.analysis.performance.ChampionTallyAggregator;
import com.teamfighter.tfm.analysis.performance.PerformanceCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 집계 한 바퀴 — 카운터(D14)와 챔피언 티어(D21)를 함께 만든다.
 *
 * <p>둘을 한 바퀴에 두는 이유는 <b>같은 관측을 쓰기 때문이다.</b> 따로 돌리면 경기를 두 번
 * 읽는 것도 문제지만, 더 나쁜 것은 두 표가 서로 다른 시점의 데이터로 만들어질 수 있다는
 * 것이다. 화면은 티어와 카운터를 나란히 놓는데(D21) 그 둘이 다른 경기 집합에서 나왔다면
 * 어긋난 것을 아무도 알아채지 못한다.
 *
 * <p>스코프마다 답이 다르다. {@code CAREER} 는 그 커리어 안의 값이고, {@code GLOBAL} 은
 * 슬롯을 합친 값이다 — 합칠 때 감쇠는 <b>슬롯별로 각자의 기준 시점</b>으로 이미 끝나 있다
 * (D45). 기저 강도도 스코프마다 따로 잡는다 (D47).
 *
 * <p>스크림 포함 여부도 두 벌 다 계산한다. 그래서 슬롯마다 경기를 <b>공식만·전체 두 벌로
 * 한 번씩만</b> 읽고 두 변형이 나눠 쓴다. 밴률의 분모(공식전 수)가 스크림 포함 스코프에서도
 * 필요하기 때문에 어차피 둘 다 있어야 한다 (D50).
 *
 * <p><b>한 트랜잭션이다.</b> 도중에 죽으면 아무것도 안 바뀐다. 절반만 갱신된 표가 화면에
 * 뜨는 것보다 낫고, 실패는 예외로 올라가므로 조용하지 않다.
 *
 * <p><b>언제 돌릴지는 아직 정하지 않았다.</b> 지금 부르는 곳은 {@code AggregationRunner}
 * (기본 꺼짐)와 통합 테스트뿐이다. 기동 시 켜면 따라잡기 적재보다 먼저 끝난다는 것도
 * 실측으로 확인됐다 — decision.md 의 "아직 안 정한 것" 참고.
 */
@Service
public class AggregationService {

    private static final Logger log = LoggerFactory.getLogger(AggregationService.class);

    private final AnalysisConfigDao configDao;
    private final MatchObservationDao observations;
    private final CounterWriter counterWriter;
    private final PerformanceWriter performanceWriter;
    private final AggRunRecorder runs;

    public AggregationService(
            AnalysisConfigDao configDao,
            MatchObservationDao observations,
            CounterWriter counterWriter,
            PerformanceWriter performanceWriter,
            AggRunRecorder runs) {
        this.configDao = configDao;
        this.observations = observations;
        this.counterWriter = counterWriter;
        this.performanceWriter = performanceWriter;
        this.runs = runs;
    }

    /** 한 번의 집계가 무엇을 썼는지. */
    public record Result(long aggRunId, int counterRows, int performanceRows) {
    }

    @Transactional
    public Result run() {
        AnalysisConfig config = configDao.load();
        long runId = runs.start(config, "집계 — 카운터(D14) · 티어(D21)");

        List<SlotData> slots = observations.slotIds().stream().map(this::loadSlot).toList();

        int counterRows = 0;
        int performanceRows = 0;
        for (boolean includeScrim : new boolean[] { true, false }) {
            List<MatchupAggregate> perSlotMatchups = new ArrayList<>(slots.size());
            List<Map<Integer, ChampionTally>> perSlotTallies = new ArrayList<>(slots.size());
            List<Map<Integer, Integer>> perSlotBans = new ArrayList<>(slots.size());
            int globalMatchCount = 0;
            int globalBanMatchCount = 0;

            for (SlotData slot : slots) {
                List<MatchObservation> matches = slot.matches(includeScrim);
                log.debug("슬롯 {} — 경기 {}건(공식 {}건) · 기준 패치 {} · 스크림포함 {}",
                        slot.slotId(), matches.size(), slot.officialCount(),
                        slot.reference().patchSeq(), includeScrim);

                MatchupAggregate matchups =
                        MatchupAggregator.aggregate(matches, slot.reference(), config);
                perSlotMatchups.add(matchups);
                counterRows += counterWriter.write(
                        AggScope.CAREER, slot.slotId(), null, includeScrim, runId,
                        CounterCalculator.calculate(matchups, config));

                Map<Integer, ChampionTally> tallies =
                        ChampionTallyAggregator.aggregate(matches, slot.reference(), config);
                perSlotTallies.add(tallies);
                perSlotBans.add(slot.bans());
                performanceRows += performanceWriter.write(
                        AggScope.CAREER, slot.slotId(), null, includeScrim, runId,
                        PerformanceCalculator.calculate(
                                tallies, slot.bans(), matches.size(), slot.officialCount(), config));

                globalMatchCount += matches.size();
                globalBanMatchCount += slot.officialCount();
            }

            counterRows += counterWriter.write(
                    AggScope.GLOBAL, null, null, includeScrim, runId,
                    CounterCalculator.calculate(MatchupAggregate.merge(perSlotMatchups), config));
            performanceRows += performanceWriter.write(
                    AggScope.GLOBAL, null, null, includeScrim, runId,
                    PerformanceCalculator.calculate(
                            ChampionTallyAggregator.merge(perSlotTallies),
                            ChampionTallyAggregator.mergeBans(perSlotBans),
                            globalMatchCount, globalBanMatchCount, config));
        }

        runs.finish(runId);
        log.info("집계 완료 run={} — 카운터 {}행 · 티어 {}행 (슬롯 {}개)",
                runId, counterRows, performanceRows, slots.size());
        return new Result(runId, counterRows, performanceRows);
    }

    private SlotData loadSlot(int slotId) {
        return new SlotData(
                slotId,
                observations.latestReference(slotId),
                observations.loadMatches(slotId, false),
                observations.loadMatches(slotId, true),
                observations.banCounts(slotId));
    }

    /**
     * 한 슬롯의 재료. 경기를 공식만·전체 두 벌로 <b>한 번씩만</b> 읽는다.
     *
     * <p>스크림 포함 스코프에서도 공식전 수가 필요하다 — 밴률의 분모이기 때문이다 (D50).
     * 변형마다 따로 읽으면 같은 질의가 네 번 나가고, 그 사이에 워처가 새 경기를 넣으면
     * 두 변형이 서로 다른 데이터를 보게 된다.
     */
    private record SlotData(
            int slotId,
            ReferencePoint reference,
            List<MatchObservation> official,
            List<MatchObservation> all,
            Map<Integer, Integer> bans) {

        List<MatchObservation> matches(boolean includeScrim) {
            return includeScrim ? all : official;
        }

        int officialCount() {
            return official.size();
        }
    }
}
