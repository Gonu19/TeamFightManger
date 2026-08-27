package com.teamfighter.tfm.analysis.counter;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import com.teamfighter.tfm.analysis.shrink.Shrinkage;
import com.teamfighter.tfm.analysis.strength.BradleyTerry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 카운터 최종 계산 — 챔피언 강도를 빼고 남은 것이 상성이다 (D14).
 *
 * <pre>
 * expected = s_A / (s_A + s_B)                       두 기저 강도로 예측되는 승률
 * adjusted = (Σw·win + k₀·expected) / (Σw + k₀)      1단 축소 (D15b)
 * effect   = adjusted − expected
 * </pre>
 *
 * <p>여기서 하는 일은 raw 승률에서 <b>"이 챔피언이 원래 세서 이긴 만큼"</b> 을 덜어내는
 * 것이다. 안 덜어내면 결과는 카운터표가 아니라 센 챔피언 목록이 된다 — Werewolf 기저 승률이
 * 61% 라 raw 정렬로는 거의 모든 상대에서 상위를 차지하는데, 그건 질문에 답을 안 한 것이다.
 *
 * <p><b>축소의 목표값이 기대 승률이라는 점이 핵심이다.</b> 상수 0.5 로 당기면 약한 챔피언의
 * 정당한 열세까지 중앙으로 밀려서, 걷어내려던 챔피언 강도가 잔차에 다시 섞인다.
 * 그리고 목표값이 방향마다 합쳐 1 이 되기 때문에({@code expected(A,B) + expected(B,A) = 1})
 * 상성 이득도 부호만 반대인 대칭을 유지한다 — 한쪽의 이득은 다른 쪽의 손해다.
 *
 * <p><b>표본 기준선(D9)으로 여기서 거르지 않는다.</b> 미달 조합도 계산해서 저장하고,
 * 노출 여부는 화면이 정한다. 원시 카운트를 계속 갖고 있어야 "이 패치 12경기 · 추정 54.1%"
 * 처럼 읽히는 화면을 만들 수 있다 (D24).
 */
public final class CounterCalculator {

    private CounterCalculator() {
    }

    public static List<CounterRow> calculate(MatchupAggregate aggregate, AnalysisConfig config) {
        Map<Integer, Double> strength = BradleyTerry.fit(aggregate.outcomes());

        List<CounterRow> rows = new ArrayList<>(aggregate.pairs().size());
        for (Map.Entry<PairKey, WeightedTally> entry : aggregate.pairs().entrySet()) {
            PairKey key = entry.getKey();
            WeightedTally tally = entry.getValue();

            double expected = BradleyTerry.expected(
                    strengthOf(strength, key.championId()),
                    strengthOf(strength, key.opponentId()));
            double adjusted = Shrinkage.overall(
                    tally.weightedWins(), tally.weightedGames(), expected, config.priorK0());

            rows.add(new CounterRow(
                    key.championId(),
                    key.opponentId(),
                    tally.games(),
                    tally.wins(),
                    tally.weightedGames(),
                    tally.weightedWins(),
                    tally.ess(),
                    expected,
                    adjusted,
                    adjusted - expected));
        }
        return rows;
    }

    private static double strengthOf(Map<Integer, Double> strength, int championId) {
        Double value = strength.get(championId);
        if (value == null) {
            throw new IllegalStateException(
                    "챔피언 " + championId + " 의 기저 강도가 없다."
                            + " 쌍에는 있는데 Bradley-Terry 관측에는 없다는 뜻이라 전개가 어긋났다");
        }
        return value;
    }
}
