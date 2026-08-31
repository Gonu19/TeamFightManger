package com.teamfighter.tfm.web.view;

import com.teamfighter.tfm.ingest.entity.ChampionCategory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 티어 목록의 한 줄.
 *
 * <h2>등급 대신 순위다</h2>
 *
 * {@code tierGrade} 는 언제나 {@code null} 이다. 컷라인을 못 정했기 때문이다 —
 * 실측에서 40명의 기저 승률 평균 49.71% · 표준편차 3.32%p 였고 55% 이상은 둘뿐이라
 * 고정 컷은 못 쓴다. 백분위도 못 쓴다: <b>표본 오차만으로 ±3.4%p 가 흩어져서</b>
 * 관측된 흩어짐의 상당 부분이 잡음일 수 있다 (D51 · {@code decisions/OPEN.md}).
 *
 * <p>그래서 화면은 그 칸에 <b>순위</b>를 그린다. 순위는 컷라인을 요구하지 않는다 —
 * "1등이 2등보다 위다" 는 등급 경계를 긋지 않고도 말할 수 있는 사실이고, 근거 없는
 * S/A/B 를 찍지 않는다는 D51 을 어기지 않는다. 빈 칸을 "미정" 으로 두는 것보다
 * 순위가 읽는 사람에게 실제로 쓸모가 있다.
 *
 * <p>정렬 기준은 여전히 추정 승률이다(D50).
 *
 * @param rank            추정 승률 순위. 동점은 같은 순위를 받고 다음 순위를 건너뛴다
 *                        (1,2,2,4). 출전이 없어 추정치가 없으면 {@link #UNRANKED}
 * @param adjustedWinRate 축소를 거친 추정 승률. 정렬과 순위의 기준이다.
 *                        표본이 얇으면 전체 평균 쪽으로 당겨져 있다 (D15)
 * @param winRate         날것의 승률. 추정치와 얼마나 다른지 보이라고 같이 준다
 * @param games           출전 경기 수. <b>추정치와 떼어 놓지 않는다</b> —
 *                        얇은 표본의 값을 두꺼운 것과 같은 무게로 읽으면 안 된다
 */
public record TierRow(
        int rank,
        int championId,
        String code,
        String nameKo,
        String category,
        int games,
        int wins,
        BigDecimal winRate,
        BigDecimal adjustedWinRate,
        BigDecimal pickRate,
        BigDecimal banRate,
        String tierGrade) {

    /** 순위를 매길 수 없다 — 출전이 없어 추정 승률이 {@code null} 인 줄. */
    public static final int UNRANKED = 0;

    /**
     * 막대를 그릴 때 쓰는 승률의 아래·위 끝.
     *
     * <p>0~100%로 그리면 모든 챔피언의 막대가 절반쯤에서 거기서 거기가 된다 — 실제
     * 흩어짐이 표준편차 3.3%p 뿐이기 때문이다. 차이를 보이려면 <b>차이가 사는 구간</b>을
     * 그려야 한다. 40~60%는 실측 최소·최대를 넉넉히 감싼다.
     */
    public static final double BAR_FLOOR = 40.0;
    public static final double BAR_CEILING = 60.0;

    /** 순위를 붙인 사본. 순위는 정렬한 뒤에야 알 수 있어 나중에 채운다. */
    public TierRow withRank(int newRank) {
        return new TierRow(newRank, championId, code, nameKo, category, games, wins,
                winRate, adjustedWinRate, pickRate, banRate, tierGrade);
    }

    /** 순위가 있는가. 없으면 화면이 "—" 를 그린다. */
    public boolean isRanked() {
        return rank != UNRANKED;
    }

    /** 등급을 아직 못 정했는가. 지금은 언제나 참이다 (D51). */
    public boolean hasNoGrade() {
        return tierGrade == null || tierGrade.isBlank();
    }

    /** 역할군의 한글 이름. */
    public String categoryLabel() {
        return ChampionCategory.labelOf(category);
    }

    /**
     * 막대의 길이 (0~100).
     *
     * <p>승률이 아니라 <b>막대 길이</b>다. 40% 이하는 0, 60% 이상은 100 으로 눕는다.
     * 잘린다는 사실은 눈금이 말한다 — 눈금 없이 자르면 60%와 70%가 같아 보인다.
     */
    public static Integer barWidth(BigDecimal ratio) {
        if (ratio == null) {
            return null;
        }
        double percent = ratio.doubleValue() * 100.0;
        double clamped = Math.max(BAR_FLOOR, Math.min(BAR_CEILING, percent));
        return (int) Math.round((clamped - BAR_FLOOR) / (BAR_CEILING - BAR_FLOOR) * 100.0);
    }

    /**
     * 이미 정렬된 목록에 순위를 붙인다.
     *
     * <p><b>동점은 같은 순위를 받고 다음 순위를 건너뛴다</b> (1,2,2,4). 추정 승률은
     * 소수 넷째 자리까지 저장돼 있어 실제 동점이 흔하지는 않지만, 동점을 임의로 갈라
     * 붙이면 <b>정렬이 흔들릴 때마다 순위가 뒤바뀐다</b> — 같은 데이터인데 새로고침마다
     * 다른 화면이 나오는 것으로 보인다.
     *
     * <p>추정 승률이 {@code null} 인 줄(출전 0)은 순위를 안 받는다. 0경기에 순위를
     * 매기면 "63위" 가 성적처럼 읽힌다.
     *
     * @param sorted 추정 승률 내림차순으로 이미 정렬된 목록. 여기서 다시 정렬하지 않는다
     */
    public static List<TierRow> rank(List<TierRow> sorted) {
        List<TierRow> out = new ArrayList<>(sorted.size());
        BigDecimal previous = null;
        int lastRank = UNRANKED;

        for (int i = 0; i < sorted.size(); i++) {
            TierRow row = sorted.get(i);
            BigDecimal value = row.adjustedWinRate();
            if (value == null) {
                out.add(row);                       // 순위 없음. 목록 맨 아래에 그대로 둔다
                continue;
            }
            if (previous != null && value.compareTo(previous) == 0) {
                out.add(row.withRank(lastRank));    // 동점 — 앞줄과 같은 순위
                continue;
            }
            lastRank = i + 1;                       // 건너뛴다: 1,2,2,4
            previous = value;
            out.add(row.withRank(lastRank));
        }
        return List.copyOf(out);
    }

    /** 백분율로 그릴 값. {@code null} 이면 {@code null} 을 돌려준다 — 0 으로 바꾸지 않는다. */
    public static BigDecimal percent(BigDecimal ratio) {
        return percent(ratio, 1);
    }

    /**
     * <b>정렬 키만</b> 소수 둘째 자리까지 그린다.
     *
     * <p>한 자리로 자르면 52.83% 와 52.78% 가 둘 다 "52.8%" 가 되는데, 순위는 저장된
     * 값으로 갈리므로 화면에는 <b>같은 숫자에 2위와 3위</b>가 나란히 놓인다. 그러면
     * 읽는 사람은 순위가 틀렸다고 본다 — 실제로는 그리는 자리가 모자란 것이다.
     *
     * <p>나머지 칸은 한 자리로 둔다. 정렬에 안 쓰이니 그 문제가 없고, 여덟 칸이 전부
     * 두 자리면 표가 숫자로 빽빽해져 정작 순위가 안 보인다.
     */
    public static BigDecimal sortPercent(BigDecimal ratio) {
        return percent(ratio, 2);
    }

    private static BigDecimal percent(BigDecimal ratio, int scale) {
        return ratio == null ? null : ratio.multiply(BigDecimal.valueOf(100))
                .setScale(scale, java.math.RoundingMode.HALF_UP);
    }
}
