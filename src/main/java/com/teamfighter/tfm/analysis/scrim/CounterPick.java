package com.teamfighter.tfm.analysis.scrim;

/**
 * 메타 상위 하나를 잡는 픽 한 장.
 *
 * <h2>이 값이 곧 주장이다</h2>
 *
 * "{@code target} 이 맞은편에 있을 때 {@code effect}σ 만큼 <b>더 죽는다</b>" —
 * 그것이 카운터의 뜻이다. 가르는 축은 {@code DEATH} 이고, 딜로 가르면 정확히 거꾸로
 * 읽힌다 (D64 결정 3: 상대 쪽 딜 상승은 "내가 강하다" 가 아니라 "저쪽이 내 딜을
 * 받아낸다" 는 뜻이다).
 *
 * @param targetNameKo 잡는 대상 — 메타 상위 챔피언
 * @param effect       그 대상의 죽음이 얼마나 늘어나나 (σ). 클수록 세게 잡는다
 * @param observations 그 쌍을 본 횟수. <b>적을수록 스크림에서 확인할 값어치가 크다</b>
 */
public record CounterPick(
        int championId,
        String code,
        String nameKo,
        String category,
        int games,
        String targetNameKo,
        double effect,
        int observations) {

    /**
     * 스크림에서 확인할 값어치가 있나.
     *
     * <p><b>효과는 있는데 표본이 얇은 것</b>이 그렇다. 40번 본 카운터는 이미 알고
     * 있으므로 스크림에서 확인할 것이 없다 — 그냥 쓰면 된다. 11번 본 카운터는
     * 릿지에 세게 눌린 값이라(관측 10 + 릿지 16 = 갱신 분모 26) 아직 흔들린다.
     *
     * <p>그래서 이 화면이 고르는 것은 <b>가장 센 카운터가 아니라 가장 덜 확인된
     * 카운터</b>다. 그것이 스크림에서 할 일이다.
     */
    public boolean worthTesting() {
        return observations < THIN_OBSERVATIONS;
    }

    /**
     * 이 아래면 "얇다" 고 부른다.
     *
     * <p>{@code MIN_OBSERVATIONS}(10)의 두 배다. 문턱을 갓 넘긴 쌍과 넉넉히 본 쌍을
     * 가르는 자리인데, <b>잰 값은 아니다.</b> 실측 분포에서 카운터의 관측 수가
     * 11~40 에 흩어져 있어 그 가운데를 잡았다.
     */
    public static final int THIN_OBSERVATIONS = 20;
}
