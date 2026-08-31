package com.teamfighter.tfm.analysis.pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 관측을 받아 <b>방향 있는 쌍 효과</b>를 낸다. D63~D65 의 측정을 그대로 옮긴 것이다.
 *
 * <h2>이것은 검증된 추정치가 아니다</h2>
 *
 * 교차검증은 <b>"효과가 있다" 까지만</b> 말했다(dealing 기준 t 22.11 · D64). 개별 쌍의
 * <i>크기</i>는 표본이 얇으면 그만큼 흔들린다. 그래서 {@link Effect} 가 관측 수를
 * 반드시 함께 들고 다니고, 화면은 그것을 같이 보여준다 — D13·D60 의 "판정 불가" 규칙이다.
 *
 * <h2>왜 역할군을 같이 넣는가</h2>
 *
 * 넣지 않으면 <b>"전사 둘이 좋다"</b> 가 <b>"이 전사 옆의 저 전사가 좋다"</b> 로
 * 잘못 읽힌다. 역할군은 챔피언의 고정 속성이라(D05) 쌍 1,560개를 25개로 뭉갠 것이고,
 * <b>적은 모수로 같은 일을 해내면 그쪽이 맞는 설명</b>이다. 실측에서는 역할군을 먼저
 * 넣어도 챔피언 쌍이 거의 그대로 남았다 — 그래서 우리는 쌍을 화면에 낼 자격이 있다.
 *
 * <h2>부호의 뜻은 지표마다 다르다</h2>
 *
 * 여기서는 <b>해석하지 않는다.</b> 계수를 그대로 낸다. {@code DEALING} 의 상대 효과가
 * 양수인 것은 "그 상대가 내 딜을 흡수해 준다" 는 뜻이지 "내가 강하다" 가 아니고,
 * 그 구분은 화면이 지표 묶음을 나란히 놓아 사람이 읽게 한다(D65 결정 1).
 */
public final class PairEffectCalculator {

    /**
     * 팀 강도의 λ.
     *
     * <p>{@code tools/perf_role_control.py} 의 {@code show_top()} 이 쓰는 값이다.
     * 팀은 수십 개뿐이고 관측이 두꺼워 세게 눌러도 값이 선다.
     */
    private static final double TEAM_RIDGE = 64.0;

    /**
     * 조합·역할군의 λ.
     *
     * <p>쌍이 1,560개인데 표본이 얇다. 이 값이 낮으면 두세 번 나온 쌍이 큰 계수를 갖고
     * 상위 목록을 차지한다 — 그건 발견이 아니라 잡음이다.
     */
    private static final double PAIR_RIDGE = 16.0;

    /**
     * 화면에 낼 수 있는 최소 관측 수.
     *
     * <h2>20 에서 10 으로 내렸다 (D78)</h2>
     *
     * 20 은 D64·D65 의 상위 쌍 <b>표</b>가 쓴 문턱이었다. 그 표는 "무엇이 제일 센가" 를
     * 고르는 자리라 얇은 쌍을 보수적으로 잘라도 잃는 것이 없었다. 화면은 다르다 —
     * <b>"이 챔피언"</b> 을 열었는데 쌍이 넷뿐이면 그 넷이 전부다. 자를수록 남는 것이
     * 아니라 없어진다.
     *
     * <p>내려도 되는 근거는 릿지에 있다. {@link #PAIR_RIDGE} 가 16 이므로 관측 10회짜리
     * 쌍의 갱신 분모는 {@code 10 + 16 = 26} — <b>계수의 6할 이상이 릿지에 눌린다.</b>
     * 얇은 쌍이 큰 값을 갖고 상위 목록을 차지하는 일은 문턱이 아니라 릿지가 막는다.
     * 문턱이 하는 일은 그 위에서 <b>"이건 아예 말하지 않는다"</b> 를 정하는 것뿐이다.
     *
     * <p>그리고 문턱은 혼자 서 있지 않다. 관측 수가 화면의 값 옆에 그대로 붙고(D13·D60),
     * 효과 크기가 {@code PairRow.SIGNAL} 을 못 넘으면 흐리게 그려진다(D77 결정 6-1).
     * 10회짜리 쌍은 그 두 장치를 통과해도 "10경기" 라고 적힌 채 나간다.
     */
    public static final int MIN_OBSERVATIONS = 10;

    private PairEffectCalculator() {
    }

    /**
     * 지표 하나에 대해 전체 적합을 돌리고 쌍 효과를 뽑는다.
     *
     * <p><b>교차검증을 하지 않는다.</b> 여기서 하는 일은 모형 고르기가 아니라
     * <i>값 내기</i>이고, λ 는 이미 {@code tools/} 가 격자탐색으로 골라 둔 것을 쓴다.
     * 모형이 맞는지는 그 도구가 답했다(D63·D64) — 이 코드가 다시 물을 것이 아니다.
     *
     * @param rows   그 지표의 관측 전부
     * @param roleOf 챔피언 → 역할군 번호
     * @return 관측 {@value #MIN_OBSERVATIONS} 회 이상인 쌍만
     */
    public static List<Effect> effects(List<PairObservation> rows, IntFunction<Integer> roleOf) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Standardizer standardizer = Standardizer.from(rows);                 // 1. 챔피언 주효과를 0 으로
        DesignMatrix design = new DesignMatrix(List.of(                      // 2. 역할군을 통제한 채 쌍을 본다
                DesignMatrix.Block.TEAM,
                DesignMatrix.Block.MATE_ROLE,
                DesignMatrix.Block.FOE_ROLE,
                DesignMatrix.Block.MATE,
                DesignMatrix.Block.FOE), roleOf);

        List<RidgeFit.Row> built = design.train(rows, standardizer);
        double[] theta = RidgeFit.fit(built, design.params(),                // 3. 좌표하강 릿지
                design.ridge(TEAM_RIDGE, PAIR_RIDGE));

        Map<DesignMatrix.Key, Integer> counts = countPairs(rows);            // 4. 쌍마다 몇 번 함께 나왔나

        List<Effect> out = new ArrayList<>();
        for (int j = 0; j < theta.length; j++) {
            DesignMatrix.Key key = design.keyAt(j);
            Side side = sideOf(key.block());
            if (side == null) {
                continue;                                                     // 팀·역할군 항은 화면에 안 낸다
            }
            int seen = counts.getOrDefault(key, 0);
            if (seen < MIN_OBSERVATIONS) {                                    // 5. 얇은 쌍은 버린다
                continue;
            }
            out.add(new Effect(side, key.subject(), key.other(), theta[j], seen));
        }
        return List.copyOf(out);
    }

    /**
     * 쌍마다 함께 나온 횟수.
     *
     * <p>계수가 아니라 <b>원자료</b>에서 센다. 설계행렬은 특성이 몇 번 켜졌는지 알지만
     * 그건 같은 값이고, 여기서 따로 세는 편이 "이 숫자가 무엇인지" 가 분명하다 —
     * 화면에 "63경기" 로 나가는 값이라 오해의 여지를 남기지 않는 편이 낫다.
     */
    private static Map<DesignMatrix.Key, Integer> countPairs(List<PairObservation> rows) {
        Map<DesignMatrix.Key, Integer> counts = new HashMap<>();
        for (PairObservation row : rows) {
            for (int mate : row.mates()) {
                counts.merge(new DesignMatrix.Key(
                        DesignMatrix.Block.MATE, row.championId(), mate), 1, Integer::sum);
            }
            for (int foe : row.foes()) {
                counts.merge(new DesignMatrix.Key(
                        DesignMatrix.Block.FOE, row.championId(), foe), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Side sideOf(DesignMatrix.Block block) {
        return switch (block) {
            case MATE -> Side.ALLY;
            case FOE -> Side.FOE;
            default -> null;
        };
    }

    /** 같은 팀인가 맞은편인가. Postgres 의 {@code pair_side} ENUM 과 이름이 같아야 한다. */
    public enum Side { ALLY, FOE }

    /**
     * 쌍 효과 하나.
     *
     * @param subjectChampionId 값을 받는 쪽. "이 챔피언의 출력이 달라진다"
     * @param otherChampionId   옆이나 맞은편의 챔피언
     * @param effect            σ 단위. 승률이 아니다 (D63 결정 5)
     * @param observations      함께 나온 횟수. <b>효과와 떼어 놓지 않는다</b>
     */
    public record Effect(Side side, int subjectChampionId, int otherChampionId,
                         double effect, int observations) {
    }
}
