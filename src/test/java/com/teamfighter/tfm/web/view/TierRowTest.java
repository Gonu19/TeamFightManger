package com.teamfighter.tfm.web.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 순위와 막대 — <b>등급 대신 쓰는 것</b>이 정직한가.
 *
 * <p>등급(S·A·B)은 아직 못 매긴다. 기저 승률의 흩어짐(표준편차 3.3%p)이 표본 오차
 * (±3.4%p)와 비슷해서 컷을 그으면 잡음에 등급을 매기는 것이 되기 때문이다 (D51).
 * 순위는 컷라인을 요구하지 않아 그 자리를 대신 쓴다 — 다만 그것도 조건이 있다:
 *
 * <ul>
 *   <li>출전이 없는 줄에는 순위를 안 준다. 0경기의 "63위" 는 성적처럼 읽힌다</li>
 *   <li>동점을 임의로 가르지 않는다. 그러면 같은 데이터가 새로고침마다 다르게 보인다</li>
 * </ul>
 *
 * <p>막대는 σ 와 같은 규칙을 따른다 (D63 결정 5): <b>숫자를 바꾸지 않고 읽는 법만</b>
 * 바꾼다. 눈금이 잘린다는 사실은 화면이 말한다.
 */
class TierRowTest {

    private static TierRow row(String code, Double adjusted, int games) {
        return new TierRow(TierRow.UNRANKED, code.hashCode(), code, code, "MELEE",
                games, 0, null,
                adjusted == null ? null : BigDecimal.valueOf(adjusted),
                null, null, null);
    }

    @Test
    @DisplayName("순위는 1부터 차례로 붙는다")
    void ranksCountFromOne() {
        List<TierRow> ranked = TierRow.rank(List.of(
                row("A", 0.561, 30), row("B", 0.523, 25), row("C", 0.498, 40)));

        assertThat(ranked).extracting(TierRow::rank).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("동점은 같은 순위를 받고 다음 순위를 건너뛴다")
    void tiesShareARankAndSkipTheNext() {
        // 임의로 가르면 정렬이 흔들릴 때마다 순위가 뒤바뀐다 — 같은 데이터인데
        // 새로고침마다 다른 화면이 나오는 것으로 보인다.
        List<TierRow> ranked = TierRow.rank(List.of(
                row("A", 0.561, 30), row("B", 0.523, 25),
                row("C", 0.523, 22), row("D", 0.498, 40)));

        assertThat(ranked).extracting(TierRow::rank).containsExactly(1, 2, 2, 4);
    }

    @Test
    @DisplayName("추정 승률이 없는 줄은 순위를 안 받는다")
    void unplayedChampionsGetNoRank() {
        // 목록에서 지우지는 않는다 — 빠지면 "이 챔피언 어디 갔지" 가 되고
        // 그 답이 화면 어디에도 없다. 순위만 안 준다.
        List<TierRow> ranked = TierRow.rank(List.of(
                row("A", 0.561, 30), row("Z", null, 0)));

        assertThat(ranked.get(0).isRanked()).isTrue();
        assertThat(ranked.get(1).isRanked()).isFalse();
        assertThat(ranked.get(1).rank()).isEqualTo(TierRow.UNRANKED);
    }

    @Test
    @DisplayName("순위를 못 매기는 줄이 뒤 순위를 밀지 않는다")
    void unrankedRowsDoNotShiftTheOnesBelow() {
        // SQL 이 NULLS LAST 로 내려주므로 실제로는 맨 아래에만 오지만,
        // 규칙이 순서에 기대고 있으면 정렬을 바꿀 때 조용히 깨진다.
        List<TierRow> ranked = TierRow.rank(List.of(
                row("A", 0.561, 30), row("Z", null, 0), row("B", 0.523, 25)));

        assertThat(ranked).extracting(TierRow::rank)
                .containsExactly(1, TierRow.UNRANKED, 3);
    }

    @Test
    @DisplayName("막대의 눈금은 40~60% 다 — 그 밖은 양끝에 눕는다")
    void barsUseTheRangeWhereTheDifferenceLives() {
        // 0~100 으로 그리면 실제 흩어짐이 3.3%p 뿐이라 모든 막대가 절반쯤에서
        // 똑같아 보인다. 차이를 보이려면 차이가 사는 구간을 그려야 한다.
        assertThat(TierRow.barWidth(BigDecimal.valueOf(0.50))).isEqualTo(50);
        assertThat(TierRow.barWidth(BigDecimal.valueOf(0.55))).isEqualTo(75);
        assertThat(TierRow.barWidth(BigDecimal.valueOf(0.30))).isEqualTo(0);
        assertThat(TierRow.barWidth(BigDecimal.valueOf(0.70))).isEqualTo(100);
    }

    @Test
    @DisplayName("정렬 키는 두 자리로 그린다 — 같은 숫자에 다른 순위가 붙지 않게")
    void theSortKeyIsShownWithEnoughDigitsToExplainTheRank() {
        // 한 자리로 자르면 둘 다 "52.8%" 인데 순위는 2위·3위로 갈린다.
        // 그러면 읽는 사람은 순위가 틀렸다고 본다 — 실제로는 자릿수가 모자란 것이다.
        assertThat(TierRow.sortPercent(BigDecimal.valueOf(0.5283)))
                .isNotEqualByComparingTo(TierRow.sortPercent(BigDecimal.valueOf(0.5278)));
        assertThat(TierRow.percent(BigDecimal.valueOf(0.5283)))
                .isEqualByComparingTo(TierRow.percent(BigDecimal.valueOf(0.5278)));
    }

    @Test
    @DisplayName("없는 값은 0 이 아니다")
    void missingValuesStayMissing() {
        // "출전이 없다" 와 "승률 0%" 는 다른 뜻이다. 화면이 "—" 를 그려야 한다.
        assertThat(TierRow.barWidth(null)).isNull();
        assertThat(TierRow.percent(null)).isNull();
        assertThat(TierRow.sortPercent(null)).isNull();
    }

    @Test
    @DisplayName("역할군은 한글로 그린다")
    void categoriesAreShownInKorean() {
        assertThat(row("A", 0.5, 10).categoryLabel()).isEqualTo("전사");
    }
}
