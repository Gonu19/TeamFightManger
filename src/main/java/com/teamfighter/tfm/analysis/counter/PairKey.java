package com.teamfighter.tfm.analysis.counter;

/**
 * 카운터 한 쌍. <b>방향이 있다</b> — {@code (A,B)} 는 "A 가 B 를 상대했을 때" 다.
 *
 * <p>{@code champion_matchup} 은 A→B 와 B→A 를 둘 다 적재한다. 조회를 단순하게 하려는
 * 의도된 중복이다.
 */
public record PairKey(int championId, int opponentId) {

    public PairKey {
        if (championId == opponentId) {
            throw new IllegalArgumentException(
                    "자기 자신과의 쌍이다: " + championId + " (champion_matchup_not_self)");
        }
    }

    public PairKey reversed() {
        return new PairKey(opponentId, championId);
    }
}
