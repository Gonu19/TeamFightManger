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
}
