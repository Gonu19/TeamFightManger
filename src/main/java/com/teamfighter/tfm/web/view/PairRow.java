package com.teamfighter.tfm.web.view;

import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Side;
import com.teamfighter.tfm.analysis.pair.PerfMetric;
import com.teamfighter.tfm.ingest.entity.ChampionCategory;

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
 * <h2>묶음은 서명과 다른 것이다</h2>
 *
 * {@link #bucket()} 은 줄을 화면의 어느 칸에 넣을지만 정한다. 서명이 못 붙은 줄도
 * 묶음은 받는다 — "죽음이 늘지만 딜·탱은 조용하다" 는 이름 붙일 현상은 아니어도
 * <b>상대하기 어려운 것은 맞기</b> 때문이다. 둘을 한 함수로 합치면 이름 없는 효과가
 * 화면에서 통째로 사라진다.
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

    /**
     * 효과가 있다고 말하기 시작하는 크기.
     *
     * <p><b>측정된 값이 아니라 고른 값이다</b> ({@code decisions/OPEN.md}).
     *
     * <h2>0.15 에서 0.10 으로 내렸다 (D77 을 고친다)</h2>
     *
     * 0.15 는 화면을 너무 자주 비웠다. 실측에서 데스 효과가 0.15σ 를 넘는 쌍은
     * 넷 중 하나(FOE 52/200 · ALLY 30/114)뿐이라, 챔피언 하나를 열면 세 칸 중 둘이
     * 비는 일이 흔했다. 0.10 에서는 FOE 82/200 · ALLY 53/114 가 남는다.
     *
     * <p>내린 값도 여전히 <b>고른 값</b>이고, 내린 만큼 잡음이 더 섞인다. 그래서
     * 문턱 하나에 기대지 않는다 — 관측 수가 값 옆에 붙고(D13·D60), 문턱 아래로
     * 채운 줄은 화면이 따로 흐리게 그린다({@link PairBucket}).
     *
     * <p>아래로는 근거가 있다: 잡음만 있는 데이터에서도 쌍 1,000개 중 몇은 0.4σ 에
     * 닿는다({@code PairEffectCalculatorTest} 가 재 뒀다). 그 수는 쌍의 수와 함께
     * 커지므로, 챔피언 하나가 보는 대여섯 쌍에서는 훨씬 낮다.
     *
     * <p>서명·묶음·경고가 <b>전부 이 하나</b>를 본다. 셋이 다른 문턱을 쓰면 "경고는
     * 떴는데 묶음에는 없는" 줄이 생기고, 그건 읽는 사람이 화면을 못 믿게 만든다.
     */
    public static final double SIGNAL = 0.10;

    /**
     * 이 줄이 들어갈 칸. lol.ps 의 세 묶음과 같은 구조다.
     *
     * <p>가르는 축은 <b>{@code DEATH}</b> 다 (D64 결정 3). {@code DEALING} 으로 가르면
     * 정확히 거꾸로 읽힌다 — 상대 쪽 딜 상승은 "내가 강하다" 가 아니라 "저쪽이 내 딜을
     * 받아낸다" 는 뜻이기 때문이다.
     */
    public enum Bucket {
        /** 동료. 죽음이 줄거나 딜이 오른다 — 같이 뽑을 만하다 */
        DUO,
        /** 동료인데 딜은 줄고 죽음은 는다. 목록이 아니라 경고로 나간다 (D65 결정 2) */
        ANTI_SYNERGY,
        /** 상대. 만나면 더 죽는다 */
        HARD_FOE,
        /** 상대. 만나면 덜 죽는다 */
        EASY_FOE,
        /** 어느 칸에도 안 들어간다. 화면이 그리지 않는다 */
        NEUTRAL
    }

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

    /** 역할군의 한글 이름. */
    public String categoryLabel() {
        return ChampionCategory.labelOf(category);
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
     * 어느 칸에 들어가는가.
     *
     * <p>지표가 하나라도 비면 {@link Bucket#NEUTRAL} 이다. 없는 값을 0 으로 놓고 가르면
     * "관측이 없다" 가 "효과가 없다" 로 바뀌어 화면에 나간다.
     */
    public Bucket bucket() {
        BigDecimal dealing = effect(PerfMetric.DEALING);
        BigDecimal death = effect(PerfMetric.DEATH);
        if (dealing == null || death == null) {
            return Bucket.NEUTRAL;
        }
        double d = dealing.doubleValue();
        double x = death.doubleValue();

        if (side == Side.ALLY) {
            if (d < -SIGNAL && x > SIGNAL) {
                return Bucket.ANTI_SYNERGY;
            }
            return x < -SIGNAL || d > SIGNAL ? Bucket.DUO : Bucket.NEUTRAL;
        }
        if (x > SIGNAL) {
            return Bucket.HARD_FOE;
        }
        return x < -SIGNAL ? Bucket.EASY_FOE : Bucket.NEUTRAL;
    }

    /**
     * 그 칸 안에서 줄을 세우는 값. <b>큰 것이 위로</b> 온다.
     *
     * <p>칸마다 "센 것" 의 뜻이 다르다. 어려운 상대는 죽음이 클수록, 쉬운 상대는 죽음이
     * 작을수록(음수로 클수록) 위다. 동료는 죽음이 줄고 딜이 오르는 정도를 더한다 —
     * 둘 중 하나만 보면 "안 죽지만 딜도 안 나오는" 조합이 "안 죽는" 것만으로 1위가 된다.
     */
    public double rankValue() {
        double death = value(PerfMetric.DEATH);
        return switch (bucket()) {
            case HARD_FOE -> death;
            case EASY_FOE -> -death;
            case DUO -> value(PerfMetric.DEALING) - death;
            case ANTI_SYNERGY -> death - value(PerfMetric.DEALING);
            case NEUTRAL -> magnitude();
        };
    }

    /**
     * 이 줄을 그 칸에 넣은 지표. 묶음 목록이 <b>이 하나</b>를 크게 그린다.
     *
     * <p>지표를 하나로 줄이는 것이 아니다 — 여섯은 그대로 있고 펼치면 나온다(D65 결정 1).
     * 줄이는 것은 <b>첫눈에 보이는 것</b>뿐이다. 여섯을 한꺼번에 들이밀면 "그래서 이걸
     * 뽑아도 되나" 에 답이 안 나오는데, 그게 이 화면을 다시 만든 이유다.
     *
     * <p>상대는 언제나 {@code DEATH} 다 (D64 결정 3). 동료는 죽음이 줄어서 좋은 것과
     * 딜이 올라서 좋은 것이 다른 현상이라, <b>실제로 문턱을 넘은 쪽</b>을 보여준다 —
     * "죽음 −0.31σ" 인 줄에 딜을 그리면 0.02 가 뜨고 읽는 사람은 이 줄이 왜 여기
     * 있는지 알 수 없다.
     */
    public PerfMetric leadMetric() {
        return leadMetric(bucket());
    }

    /**
     * <b>그 칸 기준으로</b> 크게 그릴 지표.
     *
     * <p>칸을 채우려고 문턱 아래 줄을 끌어올 때 필요하다({@link PairBucket}). 그런 줄의
     * {@link #bucket()} 은 {@code NEUTRAL} 이라 자기 자신에게 물으면 언제나 데스가
     * 나오는데, 듀오 칸에 딜로 기운 줄을 넣고 데스를 그리면 <b>왜 거기 있는지 모를
     * 숫자</b>가 뜬다.
     */
    public PerfMetric leadMetric(Bucket toward) {
        if (toward != Bucket.DUO) {
            return PerfMetric.DEATH;
        }
        double death = value(PerfMetric.DEATH);
        double dealing = value(PerfMetric.DEALING);
        return -death >= dealing ? PerfMetric.DEATH : PerfMetric.DEALING;
    }

    /** {@link #leadMetric()} 의 값. */
    public BigDecimal leadEffect() {
        return effect(leadMetric());
    }

    /** {@link #leadMetric(Bucket)} 의 값. */
    public BigDecimal leadEffect(Bucket toward) {
        return effect(leadMetric(toward));
    }

    /**
     * 이 줄이 그 칸 쪽으로 <b>얼마나 기울어 있는가</b> (σ).
     *
     * <p>문턱을 넘은 줄만 보면 화면이 자주 텅 빈다 — 실측에서 데스 효과가 0.15σ 를
     * 넘는 쌍은 넷 중 하나뿐이다. 그때 "짚이는 상대가 없다" 만 그리면 <b>관측이 없다는
     * 뜻으로 읽힌다.</b> 실제로는 쟀는데 작았던 것이고, 늑대인간 ← 동료 악마 −0.147σ
     * 처럼 0.003 차이로 잘린 것도 있다.
     *
     * <p>그래서 빈 칸이 "여섯 쌍을 쟀고 가장 큰 것이 −0.147σ 였다" 고 말하게 한다.
     * 문턱을 내리는 것이 아니다 — 문턱 아래라는 사실을 <b>같이</b> 보여주는 것이다.
     */
    public double affinity(Bucket toward) {
        double death = value(PerfMetric.DEATH);
        return switch (toward) {
            case HARD_FOE -> death;
            case EASY_FOE -> -death;
            case DUO -> Math.max(-death, value(PerfMetric.DEALING));
            case ANTI_SYNERGY -> Math.min(death, -value(PerfMetric.DEALING));
            case NEUTRAL -> 0.0;
        };
    }

    /**
     * 이 줄이 이 챔피언에게 <b>좋은</b> 소식인가. 막대 색이 이것을 따른다.
     *
     * <p>부호로 색을 정하면 안 된다. 죽음 −0.3σ 는 좋은 것이고 딜 −0.3σ 는 나쁜
     * 것인데, 둘 다 음수다.
     */
    public boolean isFavourable() {
        return switch (bucket()) {
            case EASY_FOE, DUO -> true;
            case HARD_FOE, ANTI_SYNERGY -> false;
            case NEUTRAL -> false;
        };
    }

    /**
     * 막대의 길이 (0~100). σ 를 바꾸지 않고 <b>읽는 법만</b> 바꾸는 장치다 (D63 결정 5).
     *
     * <p>{@link #BAR_FULL}σ 에서 가득 찬다. 그 위는 눕는다 — 0.8σ 와 2.0σ 를 길이로
     * 구분해 봐야 둘 다 "아주 크다" 이고, 눈금을 넓히면 정작 흔한 0.2σ 대가 전부
     * 보이지 않게 된다. 숫자는 막대 옆에 그대로 있다.
     */
    public static final double BAR_FULL = 0.8;

    public static Integer barWidth(BigDecimal effect) {
        if (effect == null) {
            return null;
        }
        double filled = Math.abs(effect.doubleValue()) / BAR_FULL;
        return (int) Math.round(Math.min(1.0, filled) * 100.0);
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

        double d = dealing.doubleValue();
        double x = death.doubleValue();
        double t = tanking.doubleValue();

        return side == Side.ALLY ? allySignature(d, x, t) : foeSignature(d, x, t);
    }

    /** 같은 팀일 때. 여기서 "카운터" 라는 말은 쓰지 않는다 — 상대가 아니다. */
    private static String allySignature(double dealing, double death, double tanking) {
        if (dealing < -SIGNAL && death > SIGNAL) {
            return "역시너지 — 딜은 줄고 죽음은 는다";
        }
        if (death < -SIGNAL && tanking < -SIGNAL) {
            return "어그로 분산 — 옆에서 대신 맞아준다";
        }
        if (death < -SIGNAL && Math.abs(tanking) <= SIGNAL) {
            return "힐 보호 — 맞는 양은 그대론데 덜 죽는다";
        }
        if (dealing > SIGNAL && death <= SIGNAL) {
            return "딜이 열린다 — 옆에 두면 더 때린다";
        }
        return null;
    }

    /** 맞은편일 때. 카운터의 뜻에 맞는 지표는 데스다 (D64 결정 3). */
    private static String foeSignature(double dealing, double death, double tanking) {
        if (dealing < -SIGNAL && death > SIGNAL && tanking > SIGNAL) {
            return "간접 카운터 — 더 맞고, 못 뚫고, 죽는다";
        }
        if (death > SIGNAL) {
            return "눌린다 — 만나면 더 죽는다";
        }
        if (death < -SIGNAL) {
            return "만만하다 — 만나면 덜 죽는다";
        }
        if (dealing > SIGNAL) {
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
        return bucket() == Bucket.ANTI_SYNERGY;
    }

    private double value(PerfMetric metric) {
        BigDecimal effect = effects.get(metric);
        return effect == null ? 0.0 : effect.doubleValue();
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
