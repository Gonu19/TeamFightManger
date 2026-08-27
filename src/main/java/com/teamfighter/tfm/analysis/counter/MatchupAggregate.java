package com.teamfighter.tfm.analysis.counter;

import com.teamfighter.tfm.analysis.strength.BradleyTerry;

import java.util.List;
import java.util.Map;

/**
 * 한 번의 전개가 낸 것 — 쌍별 누적과 Bradley-Terry 관측.
 *
 * <p>둘을 한 번에 내놓는다. 같은 경기 목록을 두 번 훑을 이유가 없고, 무엇보다
 * <b>같은 가중치를 써야 하기 때문이다.</b> 따로 만들면 한쪽만 감쇠 규칙이 바뀌어도
 * 아무 데서도 안 잡힌다 — 기대 승률과 실제 승률이 서로 다른 데이터에서 나오게 된다.
 */
public record MatchupAggregate(
        Map<PairKey, WeightedTally> pairs,
        List<BradleyTerry.Outcome> outcomes) {

    public MatchupAggregate {
        pairs = Map.copyOf(pairs);
        outcomes = List.copyOf(outcomes);
    }

    /**
     * 여러 슬롯의 집계를 합친다 — {@code GLOBAL} 스코프 (D45).
     *
     * <p><b>감쇠는 이미 끝난 상태로 들어온다.</b> 슬롯마다 패치 역사가 따로 생성되므로
     * 슬롯을 가로지르는 패치 축이 없다(D24). 합치고 나서 감쇠하려 하면 어느 커리어의
     * 몇 번째 패치를 기준으로 삼을지 정할 수 없다. 그래서 슬롯별로 각자의 기준 시점으로
     * 깎은 뒤 여기서 더하기만 한다.
     *
     * <p>Bradley-Terry 관측은 이어붙인다. 기저 강도는 전체를 보고 잡아야 한다 —
     * 슬롯마다 따로 잡으면 커리어별 강도가 나오는데 그건 {@code CAREER} 스코프의 답이다.
     */
    public static MatchupAggregate merge(java.util.Collection<MatchupAggregate> parts) {
        java.util.Map<PairKey, WeightedTally> pairs = new java.util.HashMap<>();
        java.util.List<com.teamfighter.tfm.analysis.strength.BradleyTerry.Outcome> outcomes =
                new java.util.ArrayList<>();
        for (MatchupAggregate part : parts) {
            part.pairs().forEach((key, tally) -> pairs.merge(key, tally, WeightedTally::plus));
            outcomes.addAll(part.outcomes());
        }
        return new MatchupAggregate(pairs, outcomes);
    }
}
