package com.teamfighter.tfm.analysis.pair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 관측을 <b>켜진 특성 번호 목록</b>으로 바꾼다. 그리고 그 번호가 무엇이었는지 기억한다.
 *
 * <h2>무엇을 켜나</h2>
 *
 * <pre>
 *   z(A, 경기) = 팀강도 + Σ_동료B 시너지(A←B) + Σ_상대C 카운터(A←C)
 *                       + Σ_동료 역할군 + Σ_상대 역할군 + 잡음
 * </pre>
 *
 * 역할군 항이 있는 이유가 D64 다. 그것 없이 챔피언 쌍만 넣으면 <b>"전사 둘이 좋다"</b> 가
 * <b>"이 전사 옆의 저 전사가 좋다"</b> 로 잘못 읽힌다. 역할군을 먼저 넣고도 챔피언 쌍이
 * 거의 그대로 남았기 때문에(t 22.11) 우리는 쌍을 화면에 낼 수 있다 —
 * <b>역할군 항은 그 자격을 지켜주는 대조군</b>이다.
 *
 * <h2>번호를 키우는 때와 안 키우는 때</h2>
 *
 * 학습에서는 새 특성을 만나면 번호를 준다. 검증에서는 <b>주지 않는다</b> — 학습에 없던
 * 쌍은 계수가 없으므로 그냥 빼고 예측한다. 거기서 번호를 새로 만들면 계수 없는 특성이
 * 생겨 예측이 0 으로 밀린다.
 */
public final class DesignMatrix {

    /** 어떤 항을 켤 것인가. 교차검증이 모형을 비교할 때 이걸 갈아 끼운다. */
    public enum Block {
        /** 팀 강도. 같은 팀의 경기를 묶는다 — 강한 팀은 모두가 잘한다 */
        TEAM,
        /** 동료 챔피언 쌍 (시너지) */
        MATE,
        /** 상대 챔피언 쌍 (카운터) */
        FOE,
        /** 동료 역할군 쌍. 챔피언 쌍의 대조군이다 (D64) */
        MATE_ROLE,
        /** 상대 역할군 쌍 */
        FOE_ROLE
    }

    /**
     * 특성 하나의 신원.
     *
     * <p>{@code subject} 는 값을 받는 챔피언, {@code other} 는 옆이나 맞은편의 챔피언이다.
     * 역할군 항에서는 둘 다 역할군 번호이고, 팀 항에서는 {@code subject} 만 쓴다.
     */
    public record Key(Block block, int subject, int other) {
    }

    private final Map<Key, Integer> index = new LinkedHashMap<>();
    private final List<Key> keys = new ArrayList<>();

    /**
     * 팀 이름 → 번호.
     *
     * <p>{@link Key} 가 int 만 담으므로 팀 문자열에 번호를 붙여야 한다.
     * <b>{@code hashCode()} 를 쓰지 않는다</b> — 두 팀이 같은 해시를 가지면 한 팀으로
     * 뭉개지고, 그 결과는 "팀 강도가 이상하다" 로만 보인다. 원인을 찾을 길이 없는 종류다.
     */
    private final Map<String, Integer> teamIndex = new LinkedHashMap<>();
    private final List<Block> blocks;
    private final IntFunction<Integer> roleOf;

    /**
     * @param blocks 켤 항들
     * @param roleOf 챔피언 → 역할군 번호. 역할군 항을 안 켜면 안 불린다
     */
    public DesignMatrix(List<Block> blocks, IntFunction<Integer> roleOf) {
        this.blocks = List.copyOf(blocks);
        this.roleOf = roleOf;
    }

    /**
     * 학습 행으로 바꾼다. <b>새 특성에 번호를 준다.</b>
     *
     * @param standardizer 목표값을 z 로 만드는 자
     */
    public List<RidgeFit.Row> train(List<PairObservation> rows, Standardizer standardizer) {
        return build(rows, standardizer, true);
    }

    /**
     * 검증 행으로 바꾼다. <b>새 특성에 번호를 주지 않는다</b> — 학습에 없던 쌍은 빠진다.
     */
    public List<RidgeFit.Row> project(List<PairObservation> rows, Standardizer standardizer) {
        return build(rows, standardizer, false);
    }

    private List<RidgeFit.Row> build(List<PairObservation> rows, Standardizer standardizer,
                                     boolean grow) {
        List<RidgeFit.Row> out = new ArrayList<>(rows.size());
        for (PairObservation row : rows) {
            List<Integer> features = new ArrayList<>();

            // 팀을 모르는 관측(스크림)은 팀 항을 안 켠다. 0 을 켜면 "팀 0" 이라는
            // 가짜 팀이 생겨 스크림 전체가 한 팀으로 묶인다.
            if (blocks.contains(Block.TEAM) && row.teamKey() != null) {
                add(features, new Key(Block.TEAM, teamNumber(row.teamKey()), 0), grow);
            }
            if (blocks.contains(Block.MATE)) {
                for (int mate : row.mates()) {
                    add(features, new Key(Block.MATE, row.championId(), mate), grow);
                }
            }
            if (blocks.contains(Block.FOE)) {
                for (int foe : row.foes()) {
                    add(features, new Key(Block.FOE, row.championId(), foe), grow);
                }
            }
            if (blocks.contains(Block.MATE_ROLE)) {
                for (int mate : row.mates()) {
                    add(features, new Key(Block.MATE_ROLE, role(row.championId()), role(mate)), grow);
                }
            }
            if (blocks.contains(Block.FOE_ROLE)) {
                for (int foe : row.foes()) {
                    add(features, new Key(Block.FOE_ROLE, role(row.championId()), role(foe)), grow);
                }
            }

            int[] packed = new int[features.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = features.get(i);
            }
            // 감쇠는 여기서 적합으로 넘어간다. 표준화(z)에는 안 걸린다 —
            // 표준화가 지우는 것은 챔피언 주효과이고 그건 시간의 함수가 아니다.
            // 무게를 거기까지 걸면 "이 챔피언의 평균 딜" 이 최근 패치 쪽으로 끌리고,
            // 그러면 조합 효과가 아니라 메타 변화가 z 에 섞여 들어온다 (D78).
            out.add(new RidgeFit.Row(packed, standardizer.z(row), row.weight()));
        }
        return out;
    }

    /** 팀 이름에 번호를 붙인다. 처음 보는 이름이면 다음 번호를 준다. */
    private int teamNumber(String teamKey) {
        return teamIndex.computeIfAbsent(teamKey, key -> teamIndex.size());
    }

    private int role(int championId) {
        Integer role = roleOf.apply(championId);
        if (role == null) {
            throw new IllegalStateException(
                    "챔피언 " + championId + " 의 역할군을 모른다 — 시드(V3)를 확인한다");
        }
        return role;
    }

    private void add(List<Integer> features, Key key, boolean grow) {
        Integer j = index.get(key);
        if (j == null) {
            if (!grow) {
                return;
            }
            j = keys.size();
            index.put(key, j);
            keys.add(key);
        }
        features.add(j);
    }

    /** 특성 수. 적합에 넘긴다. */
    public int params() {
        return keys.size();
    }

    /** 번호 → 신원. 적합이 끝난 뒤 계수를 쌍으로 되돌릴 때 쓴다. */
    public Key keyAt(int feature) {
        return keys.get(feature);
    }

    /**
     * 특성마다의 λ.
     *
     * <p><b>팀 강도와 조합 효과에 다른 값을 준다.</b> 팀은 수십 개뿐이고 관측이 두껍지만
     * 조합은 1,560개에 표본이 얇다 — 같은 λ 를 걸면 한쪽이 반드시 틀린다.
     * 두 값은 {@code tools/perf_role_control.py} 가 격자탐색으로 고른 것을 그대로 쓴다.
     */
    public double[] ridge(double team, double other) {
        double[] out = new double[keys.size()];
        for (int j = 0; j < out.length; j++) {
            out[j] = keys.get(j).block() == Block.TEAM ? team : other;
        }
        return out;
    }
}
