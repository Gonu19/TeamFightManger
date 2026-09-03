package com.teamfighter.tfm.analysis.scrim;

import java.util.List;

/**
 * 스크림에서 해 볼 덱 하나.
 *
 * <p><b>세 수의 합은 항상 {@link ScrimSuggester#PAIRS_PER_DECK}(6)이다.</b>
 * 4인을 뽑으면 같은 편 듀오가 여섯 쌍 생기고, 그 여섯은 셋 중 하나에 반드시 속한다.
 * 화면이 이 등식을 그대로 보여 주면 "왜 이 덱이냐" 가 숫자로 답해진다.
 *
 * @param champions      네 명. 고른 순서 그대로다
 * @param unexploredPairs 아무 데서도 같이 안 나온 쌍 — <b>이 덱을 추천하는 이유</b>
 * @param scrimTriedPairs 스크림에서만 해 본 쌍. 공식전 표본은 아직 없다
 * @param knownPairs      공식전으로 이미 아는 쌍. 새로 알 것이 없는 자리다
 */
public record ScrimDeck(
        List<ScrimCandidate> champions,
        int unexploredPairs,
        int scrimTriedPairs,
        int knownPairs) {

    public ScrimDeck {
        champions = champions == null ? List.of() : List.copyOf(champions);
    }

    /** 여섯 쌍 중 모르는 쌍의 비율. 화면의 막대가 쓴다. */
    public int unexploredPercent() {
        return Math.round(100f * unexploredPairs / ScrimSuggester.PAIRS_PER_DECK);
    }
}
