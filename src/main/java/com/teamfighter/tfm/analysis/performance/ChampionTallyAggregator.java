package com.teamfighter.tfm.analysis.performance;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import com.teamfighter.tfm.analysis.MatchObservation;
import com.teamfighter.tfm.analysis.ReferencePoint;
import com.teamfighter.tfm.analysis.decay.DecayWeight;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 경기를 챔피언별 출전 누적으로 편다 — 티어의 재료 (D21).
 *
 * <p><b>감쇠에 챔피언 하나만 쓴다.</b> D42 가 정한 "쌍은 두 챔피언의 변경을 합친다" 는
 * 상성 전용이다. 티어는 "이 챔피언이 센가" 를 묻는 것이므로 그 챔피언의 변경만이 관련
 * 있다. 여기서 {@link DecayWeight#forPair} 를 쓰면 유효표본이 이유 없이 절반 속도로
 * 마르는데, 승률은 거의 안 변해서 눈으로는 보이지 않는다.
 *
 * <p>같은 이유로 가중치를 <b>경기가 아니라 참가자마다</b> 계산한다. 한 경기 안에서도
 * 챔피언마다 패치로 바뀐 횟수가 다르다.
 */
public final class ChampionTallyAggregator {

    private ChampionTallyAggregator() {
    }

    public static Map<Integer, ChampionTally> aggregate(
            Collection<MatchObservation> matches, ReferencePoint reference, AnalysisConfig config) {

        Map<Integer, ChampionTally> tallies = new HashMap<>();
        for (MatchObservation match : matches) {
            int elapsed = reference.elapsedPatchesFrom(match.patchSeq());
            accumulate(tallies, match.winners(), true, elapsed, reference, config);
            accumulate(tallies, match.losers(), false, elapsed, reference, config);
        }
        return tallies;
    }

    /**
     * 여러 슬롯의 누적을 합친다 — {@code GLOBAL} 스코프 (D45).
     *
     * <p>감쇠는 이미 슬롯별로 끝난 상태로 들어온다. 카운터의
     * {@code MatchupAggregate.merge} 와 같은 이유다.
     */
    public static Map<Integer, ChampionTally> merge(
            Collection<Map<Integer, ChampionTally>> parts) {
        Map<Integer, ChampionTally> merged = new HashMap<>();
        for (Map<Integer, ChampionTally> part : parts) {
            part.forEach((championId, tally) ->
                    merged.merge(championId, tally, ChampionTally::plus));
        }
        return merged;
    }

    /** 슬롯별 피밴 수를 합친다. */
    public static Map<Integer, Integer> mergeBans(Collection<Map<Integer, Integer>> parts) {
        Map<Integer, Integer> merged = new HashMap<>();
        for (Map<Integer, Integer> part : parts) {
            part.forEach((championId, bans) -> merged.merge(championId, bans, Integer::sum));
        }
        return merged;
    }

    /** 픽률의 분모. 참가자 수가 아니라 경기 수다. */
    public static int matchCount(Collection<MatchObservation> matches) {
        return matches.size();
    }

    private static void accumulate(
            Map<Integer, ChampionTally> tallies,
            Collection<MatchObservation.Participant> side,
            boolean won,
            int elapsed,
            ReferencePoint reference,
            AnalysisConfig config) {

        for (MatchObservation.Participant participant : side) {
            int selfChanges = reference.selfChangesFrom(
                    participant.championId(), participant.changeCountAtMatch());
            double weight = DecayWeight.of(selfChanges, elapsed, config);
            tallies.merge(
                    participant.championId(),
                    ChampionTally.EMPTY.plus(won, weight),
                    (existing, ignored) -> existing.plus(won, weight));
        }
    }
}
