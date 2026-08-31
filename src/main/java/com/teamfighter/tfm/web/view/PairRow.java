package com.teamfighter.tfm.web.view;

import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Side;
import com.teamfighter.tfm.analysis.pair.PerfMetric;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 챔피언 화면의 쌍 한 줄. <b>지표 하나가 아니라 지표 묶음</b>이다 (D65 결정 1).
 *
 * <h2>왜 벡터로 들고 다니나</h2>
 *
 * 같은 죽음 증가라도 함께 오는 값이 다르면 다른 현상이다.
 *
 * <pre>
 *   딜↓ · 죽음↑ (양쪽 다)       역시너지 — 서로 방해한다
 *   죽음↑ · 탱↑ · 딜↓ (한쪽만)   간접 카운터 — 버프를 못 끊어 녹는다
 *   죽음↓ · 탱↓                 어그로 분산 — 동료가 대신 맞아준다
 *   죽음↓ · 탱 변화 없음         힐 보호
 * </pre>
 *
 * 지표 하나만 뽑아 순위를 매기면 이 구분이 통째로 사라진다. 그래서 한 줄이
 * 여섯 지표를 다 들고 다니고, 화면이 그것을 나란히 그린다.
 *
 * <h2>서명을 단정하지 않는다</h2>
 *
 * 위 네 서명은 <b>세 쌍에서 읽어낸 초안</b>이고 일반화가 검증되지 않았다(D65).
 * 그래서 {@link #signature()} 는 <b>힌트</b>다 — 화면이 작게, 물음표를 달아 그린다.
 * 확정은 사례가 더 쌓인 뒤에 한다.
 *
 * @param observations 이 쌍이 함께 나온 횟수. <b>효과와 떼어 놓지 않는다</b> (D13·D60)
 */
public record PairRow(
        Side side,
        String code,
        String nameKo,
        String category,
        int observations,
        Map<PerfMetric, BigDecimal> effects) {

    public PairRow {
        effects = Map.copyOf(effects);
    }

    /** 그 지표의 효과. 없으면 {@code null} — 0 이 아니다. */
    public BigDecimal effect(PerfMetric metric) {
        return effects.get(metric);
    }

    /** 화면이 순서대로 그릴 지표. 딜·죽음·탱이 서명을 가르는 셋이다. */
    public static java.util.List<PerfMetric> shown() {
        return java.util.List.of(PerfMetric.DEALING, PerfMetric.DEATH,
                PerfMetric.TANKING, PerfMetric.KILL, PerfMetric.HEALING);
    }

    /**
     * 이 줄을 정렬하는 크기.
     *
     * <p><b>딜과 죽음의 절대값 중 큰 쪽</b>이다. 그 둘이 서명을 가르는 축이고,
     * 힐처럼 표본이 얇아 t 가 낮은 지표(D63: 다른 지표의 1/5)로 순위를 매기면
     * 상위 목록이 잡음으로 찬다.
     */
    public double magnitude() {
        double dealing = abs(effect(PerfMetric.DEALING));
        double death = abs(effect(PerfMetric.DEATH));
        return Math.max(dealing, death);
    }

    /**
     * 서명 힌트. 확정이 아니다 — 위 표가 초안이기 때문이다.
     *
     * <h2>같은 숫자도 편이 다르면 다른 말이다</h2>
     *
     * 딜↓·죽음↑ 이 <b>상대</b> 쪽이면 "저 챔피언에게 눌린다" 이고, <b>동료</b> 쪽이면
     * "옆에 두면 서로 방해한다" 이다. 편을 모르고 문구를 고르면 동료 줄에 "카운터" 가
     * 찍히는데, 그건 읽는 사람을 정확히 반대로 이끈다.
     *
     * @return 짚이는 것이 없으면 {@code null}. 그때 화면은 아무 말도 안 한다
     */
    public String signature() {
        BigDecimal dealing = effect(PerfMetric.DEALING);
        BigDecimal death = effect(PerfMetric.DEATH);
        BigDecimal tanking = effect(PerfMetric.TANKING);
        if (dealing == null || death == null || tanking == null) {
            return null;
        }

        // 문턱 0.15σ. 잡음만 있는 데이터에서도 쌍 1,000개 중 몇은 0.4 에 닿으므로
        // (PairEffectCalculatorTest 가 그것을 재 뒀다) 낮은 문턱은 서명을 남발하게 한다.
        double d = dealing.doubleValue();
        double x = death.doubleValue();
        double t = tanking.doubleValue();

        return side == Side.ALLY ? allySignature(d, x, t) : foeSignature(d, x, t);
    }

    /** 같은 팀일 때. 여기서 "카운터" 라는 말은 쓰지 않는다 — 상대가 아니다. */
    private static String allySignature(double dealing, double death, double tanking) {
        if (dealing < -0.15 && death > 0.15) {
            return "역시너지 — 딜은 줄고 죽음은 는다";
        }
        if (death < -0.15 && tanking < -0.15) {
            return "어그로 분산 — 옆에서 대신 맞아준다";
        }
        if (death < -0.15 && Math.abs(tanking) <= 0.15) {
            return "힐 보호 — 맞는 양은 그대론데 덜 죽는다";
        }
        if (dealing > 0.15 && death <= 0.15) {
            return "딜이 열린다 — 옆에 두면 더 때린다";
        }
        return null;
    }

    /** 맞은편일 때. 카운터의 뜻에 맞는 지표는 데스다 (D64 결정 3). */
    private static String foeSignature(double dealing, double death, double tanking) {
        if (dealing < -0.15 && death > 0.15 && tanking > 0.15) {
            return "간접 카운터 — 더 맞고, 못 뚫고, 죽는다";
        }
        if (death > 0.15) {
            return "눌린다 — 만나면 더 죽는다";
        }
        if (death < -0.15) {
            return "만만하다 — 만나면 덜 죽는다";
        }
        if (dealing > 0.15) {
            // D64 결정 3 이 경고한 자리다. 딜이 오르는 것은 내가 강해서가 아니라
            // 저쪽이 내 딜을 받아내 주기 때문이다 — 카운터로 읽으면 정반대가 된다.
            return "딜을 받아낸다 — 내가 강한 것이 아니다";
        }
        return null;
    }

    /**
     * 화면이 붉게 그릴 줄인가. 역시너지 경고는 1급 기능이다 (D65 결정 2).
     *
     * <p><b>동료일 때만</b>이다. 상대가 내 딜을 줄이고 죽음을 늘리는 것은 지뢰가 아니라
     * <b>그냥 카운터</b>다 — 피할 수 없고, 경고할 것도 아니다.
     */
    public boolean isWarning() {
        if (side != Side.ALLY) {
            return false;
        }
        BigDecimal dealing = effect(PerfMetric.DEALING);
        BigDecimal death = effect(PerfMetric.DEATH);
        return dealing != null && death != null
                && dealing.doubleValue() < -0.15 && death.doubleValue() > 0.15;
    }

    private static double abs(BigDecimal value) {
        return value == null ? 0.0 : Math.abs(value.doubleValue());
    }

    /** 지표 → 효과 를 모으는 상자. DAO 가 행을 훑으며 채운다. */
    public static final class Builder {
        private final Map<PerfMetric, BigDecimal> effects = new LinkedHashMap<>();

        public void put(PerfMetric metric, BigDecimal effect) {
            effects.put(metric, effect);
        }

        public PairRow build(Side side, String code, String nameKo, String category,
                             int observations) {
            return new PairRow(side, code, nameKo, category, observations, effects);
        }
    }
}
