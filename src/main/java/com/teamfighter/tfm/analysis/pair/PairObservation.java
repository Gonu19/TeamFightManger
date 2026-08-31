package com.teamfighter.tfm.analysis.pair;

import java.util.List;
import java.util.Objects;

/**
 * 한 관측 = <b>"이 경기에서 이 챔피언이 낸 출력"</b>.
 *
 * <h2>이 단위가 D63 의 전부다</h2>
 *
 * 지금까지 조합 효과는 <b>승패</b>로 쟀다. 경기 하나가 승패로는 1비트라 조합 하나의 값을
 * 그 통로로 추정하려면 수백 경기가 필요했다 — 805경기로도 t 가 2 를 못 넘었다(D60).
 *
 * <p>같은 경기가 출력으로는 <b>8명 × 6지표</b> 다. 통로가 넓어지자 같은 데이터에서
 * 시너지 t 가 15.60 으로 올랐다. <b>새로 파싱할 것은 없었다</b> — {@code match_participant}
 * 이 이미 그 열들을 갖고 있었다.
 *
 * <h2>몫이 아니라 절대값을 담는다</h2>
 *
 * 팀 안의 딜 <b>점유율</b>을 쓰면 A 가 더 하면 B 가 덜 하는 기계적 음의 상관이 생기고,
 * 그것이 화면에서 "역시너지" 로 읽힌다. 절대값에는 그 함정이 없다.
 *
 * @param championId 이 관측의 주인. 값을 낸 챔피언이다
 * @param teamKey    팀 강도를 묶는 열쇠. 팀을 모르면(스크림) {@code null} —
 *                   그때는 팀 항을 아예 안 켠다
 * @param mates      같은 팀의 나머지. 자기 자신은 빠져 있다
 * @param foes       맞은편 넷
 * @param value      그 지표의 원값. 표준화는 {@link Standardizer} 가 한다 —
 *                   <b>학습 폴드 안에서만</b> 해야 정답을 훔쳐보지 않는다
 */
public record PairObservation(
        int championId,
        String teamKey,
        List<Integer> mates,
        List<Integer> foes,
        double value) {

    public PairObservation {
        Objects.requireNonNull(mates, "mates");
        Objects.requireNonNull(foes, "foes");
        mates = List.copyOf(mates);
        foes = List.copyOf(foes);

        if (mates.contains(championId)) {
            // 자기 자신이 동료로 들어오면 ("m", A, A) 특성이 생기고, 그건 챔피언 주효과다.
            // 표준화가 이미 0 으로 만든 값이 특성으로 되살아나 모형을 흔든다.
            throw new IllegalArgumentException(
                    "동료 목록에 자기 자신이 있다: 챔피언 " + championId);
        }
    }
}
