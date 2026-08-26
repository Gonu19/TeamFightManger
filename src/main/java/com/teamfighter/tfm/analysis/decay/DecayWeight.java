package com.teamfighter.tfm.analysis.decay;

import com.teamfighter.tfm.analysis.AnalysisConfig;

/**
 * 이중 감쇠 — 한 경기가 지금 얼마나 유효한지 (D15a).
 *
 * <pre>w = 0.5^(자기변경 / 자기반감기) × 0.5^(경과패치 / 메타반감기)</pre>
 *
 * <p>패치가 Werewolf 를 너프하면 Werewolf 를 안 건드렸어도 Fighter 의 승률이 바뀐다.
 * 상대 풀이 달라지기 때문이다. 그래서 안 바뀐 챔피언도 감쇠를 받아야 하는데, 그러면
 * 하드 컷으로는 남는 데이터가 없다. 두 축을 나눠 <b>자기 변경은 세게, 메타 변화는 약하게</b>
 * 깎는 것이 그 답이다.
 *
 * <p><b>두 인자는 "경기 이후 기준 시점까지 흐른 양"이다.</b> 경기 시점의 누적값이 아니다.
 * {@code match_participant.change_count} 는 경기 시점의 누적이므로, 호출자가
 * {@code 기준시점_누적 − 경기시점_누적} 을 넘겨야 한다. 이걸 뒤집으면 최신 경기가 가장 세게
 * 깎이고 오래된 경기가 살아남아 감쇠가 정확히 반대로 동작하는데, 값은 여전히 (0,1] 안이라
 * 눈으로는 못 잡는다.
 */
public final class DecayWeight {

    private DecayWeight() {
    }

    public static double of(int selfChanges, int elapsedPatches, AnalysisConfig config) {
        requireNonNegative(selfChanges, "자기 변경 횟수");
        requireNonNegative(elapsedPatches, "경과 패치 수");

        double self = Math.pow(0.5, selfChanges / config.selfDecayHalfLife());
        double meta = Math.pow(0.5, elapsedPatches / config.metaDecayHalfLife());
        return self * meta;
    }

    /**
     * 두 챔피언이 얽힌 관측(카운터·시너지)의 가중치.
     *
     * <p><b>변경 횟수를 합친다.</b> 어느 쪽이 바뀌어도 그 쌍의 상성은 낡기 때문이다 —
     * 최댓값을 쓰면 "둘 다 두 번씩 바뀐 쌍"이 "한쪽만 두 번 바뀐 쌍"과 같은 무게를 받는다.
     * 대신 쌍은 단일 챔피언보다 두 배 빠르게 낡으므로 유효표본이 그만큼 빨리 마른다.
     *
     * <p><b>뒤집힐 조건.</b> 이 합산 때문에 {@code ess} 가 표본 기준선(D9)을 못 넘기는 쌍이
     * 많아지면 최댓값으로 바꾼다. 아직 측정하지 않았다.
     */
    public static double forPair(int selfChangesA, int selfChangesB, int elapsedPatches, AnalysisConfig config) {
        requireNonNegative(selfChangesA, "자기 변경 횟수(A)");
        requireNonNegative(selfChangesB, "자기 변경 횟수(B)");
        return of(selfChangesA + selfChangesB, elapsedPatches, config);
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    field + " 가 음수다: " + value
                            + ". 기준 시점이 경기보다 앞이라는 뜻이라 호출자가 뺄셈을 뒤집었다."
                            + " 그대로 두면 0.5^음수 = 1 보다 큰 가중치가 되어 오래된 경기가 최신보다 무거워진다");
        }
    }
}
