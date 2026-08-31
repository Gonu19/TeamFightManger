package com.teamfighter.tfm.analysis;

import com.teamfighter.tfm.analysis.counter.CounterCalculator;
import com.teamfighter.tfm.analysis.counter.MatchupAggregate;
import com.teamfighter.tfm.analysis.counter.MatchupAggregator;
import com.teamfighter.tfm.analysis.dao.AggRunRecorder;
import com.teamfighter.tfm.analysis.dao.ChampionRoleDao;
import com.teamfighter.tfm.analysis.dao.PairEffectWriter;
import com.teamfighter.tfm.analysis.dao.PairObservationDao;
import com.teamfighter.tfm.analysis.pair.PairEffectCalculator;
import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Effect;
import com.teamfighter.tfm.analysis.pair.PairObservation;
import com.teamfighter.tfm.analysis.pair.PerfMetric;
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
 * <p><b>{@code CAREER} 만 생산한다 (D53).</b> 패치는 커리어마다 고유 생성되므로 19패치를
 * 거치면 같은 이름의 챔피언이라도 커리어별로 스탯이 반대 방향으로 가 있다 — 실측 예:
 * {@code Dancer} 가 한 커리어에서 {@code max_hp +1224}, 다른 커리어에서 {@code -292} 다.
 * {@code GLOBAL} 로 합치면 표본은 3배지만 합쳐진 대상이 같은 챔피언이 아니다.
 * 유저는 한 슬롯을 플레이하고, 그 슬롯 안에서는 밸런스가 일관된다.
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
    private final PairObservationDao pairObservations;
    private final PairEffectWriter pairWriter;
    private final ChampionRoleDao roles;

    public AggregationService(
            AnalysisConfigDao configDao,
            MatchObservationDao observations,
            CounterWriter counterWriter,
            PerformanceWriter performanceWriter,
            AggRunRecorder runs,
            PairObservationDao pairObservations,
            PairEffectWriter pairWriter,
            ChampionRoleDao roles) {
        this.configDao = configDao;
        this.observations = observations;
        this.counterWriter = counterWriter;
        this.performanceWriter = performanceWriter;
        this.runs = runs;
        this.pairObservations = pairObservations;
        this.pairWriter = pairWriter;
        this.roles = roles;
    }

    /** 한 번의 집계가 무엇을 썼는지. */
    public record Result(long aggRunId, int counterRows, int performanceRows, int pairRows) {
    }

    @Transactional
    public Result run() {
        AnalysisConfig config = configDao.load();
        long runId = runs.start(config, "집계 — 카운터(D14) · 티어(D21)");

        List<SlotData> slots = observations.slotIds().stream().map(this::loadSlot).toList();

        int counterRows = 0;
        int performanceRows = 0;
        for (boolean includeScrim : new boolean[] { true, false }) {
            for (SlotData slot : slots) {
                List<MatchObservation> matches = slot.matches(includeScrim);
                log.debug("슬롯 {} — 경기 {}건(공식 {}건) · 기준 패치 {} · 스크림포함 {}",
                        slot.slotId(), matches.size(), slot.officialCount(),
                        slot.reference().patchSeq(), includeScrim);

                MatchupAggregate matchups =
                        MatchupAggregator.aggregate(matches, slot.reference(), config);
                counterRows += counterWriter.write(
                        AggScope.CAREER, slot.slotId(), null, includeScrim, runId,
                        CounterCalculator.calculate(matchups, config));

                Map<Integer, ChampionTally> tallies =
                        ChampionTallyAggregator.aggregate(matches, slot.reference(), config);
                performanceRows += performanceWriter.write(
                        AggScope.CAREER, slot.slotId(), null, includeScrim, runId,
                        PerformanceCalculator.calculate(
                                tallies, slot.bans(), matches.size(), slot.officialCount(), config));
            }
        }

        int pairRows = 0;
        for (SlotData slot : slots) {
            pairRows += writePairEffects(slot, config);
        }

        runs.finish(runId);
        log.info("집계 완료 run={} — 카운터 {}행 · 티어 {}행 · 쌍 효과 {}행 (슬롯 {}개)",
                runId, counterRows, performanceRows, pairRows, slots.size());
        return new Result(runId, counterRows, performanceRows, pairRows);
    }

    /**
     * 출력 기반 쌍 효과 (D63~D65).
     *
     * <h2>왜 위 반복문 안이 아닌가</h2>
     *
     * 위쪽은 <b>스크림 포함/제외 두 벌</b>을 만든다(D47). 쌍 효과는 공식전만 본다 —
     * D63~D65 의 측정이 공식전으로 이뤄졌고, 스크림은 팀 식별이 없어(D54) 팀 강도 항이
     * 아예 안 켜진다. 모형의 모양이 다른 것을 같은 반복문에 넣으면 그 차이가 안 보인다.
     *
     * <h2>지표마다 한 번씩 적합한다</h2>
     *
     * 여섯 지표가 각자 다른 모형이다. 관측은 한 번만 읽고 지표별로 나눠 쓴다.
     *
     * <h2>패치 감쇠가 걸린다 (D78 이 D52 를 고친다)</h2>
     *
     * 슬롯의 기준 시점을 DAO 로 내려보내면 DAO 가 관측마다 무게를 붙이고, 릿지가 그
     * 무게로 적합한다. 티어·카운터와 <b>같은 기준 시점 · 같은 반감기</b>다 — 세 값이
     * 다른 시점을 보면 화면에서 서로 어긋나는데, 그 어긋남은 숫자만 봐서는 안 보인다.
     */
    private int writePairEffects(SlotData slot, AnalysisConfig config) {
        Map<Integer, Integer> roleByChampion = roles.load();
        Map<PerfMetric, List<PairObservation>> byMetric =
                pairObservations.load(slot.slotId(), slot.reference(), config);

        int written = 0;
        for (Map.Entry<PerfMetric, List<PairObservation>> entry : byMetric.entrySet()) {
            List<Effect> effects = PairEffectCalculator.effects(
                    entry.getValue(), roleByChampion::get);
            pairWriter.replace(slot.slotId(), entry.getKey(), false, effects);
            written += effects.size();
            // 유효 표본 = 무게의 합. 원 행 수와 크게 벌어지면 감쇠가 세게 걸린 것이고,
            // 그건 반감기를 다시 볼 신호다 (D52 의 재측정 조건).
            long effective = Math.round(
                    entry.getValue().stream().mapToDouble(PairObservation::weight).sum());
            log.debug("슬롯 {} 지표 {} — 관측 {}행(유효 {}행) → 쌍 {}개",
                    slot.slotId(), entry.getKey(), entry.getValue().size(),
                    effective, effects.size());
        }
        return written;
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
