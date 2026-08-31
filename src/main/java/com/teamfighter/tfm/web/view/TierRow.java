package com.teamfighter.tfm.web.view;

import java.math.BigDecimal;

/**
 * 티어 목록의 한 줄.
 *
 * <h2>등급이 비어 있다</h2>
 *
 * {@code tierGrade} 는 아직 언제나 {@code null} 이다. 컷라인을 못 정했기 때문이다 —
 * 실측에서 40명의 기저 승률 평균 49.71% · 표준편차 3.32%p 였고 55% 이상은 둘뿐이라
 * 고정 컷은 못 쓴다. 백분위도 못 쓴다: <b>표본 오차만으로 ±3.4%p 가 흩어져서</b>
 * 관측된 흩어짐의 상당 부분이 잡음일 수 있다 (D51 · {@code decisions/OPEN.md}).
 *
 * <p>그래서 화면은 등급 대신 <b>추정 승률로 정렬</b>한다(D50). 빈 칸을 "미정" 으로
 * 그리는 것이 아무 근거 없는 S/A/B 를 찍는 것보다 정직하다.
 *
 * @param adjustedWinRate 축소를 거친 추정 승률. 정렬의 기준이다.
 *                        표본이 얇으면 전체 평균 쪽으로 당겨져 있다 (D15)
 * @param winRate         날것의 승률. 추정치와 얼마나 다른지 보이라고 같이 준다
 * @param games           출전 경기 수. <b>추정치와 떼어 놓지 않는다</b> —
 *                        얇은 표본의 값을 두꺼운 것과 같은 무게로 읽으면 안 된다
 */
public record TierRow(
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

    /** 등급을 아직 못 정했는가. 화면이 "미정" 을 그린다. */
    public boolean hasNoGrade() {
        return tierGrade == null || tierGrade.isBlank();
    }

    /** 백분율로 그릴 값. {@code null} 이면 {@code null} 을 돌려준다 — 0 으로 바꾸지 않는다. */
    public static BigDecimal percent(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(BigDecimal.valueOf(100))
                .setScale(1, java.math.RoundingMode.HALF_UP);
    }
}
