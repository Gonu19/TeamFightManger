package com.teamfighter.tfm.analysis.performance;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 챔피언 티어 계산을 못 박는다.
 *
 * <p>DB 는 필요 없다.
 *
 * <p><b>카운터와 정반대의 규칙이 여기 있다.</b> 카운터는 챔피언 강도를 걷어내야 상성이
 * 남지만, 티어는 <b>"이 챔피언이 센가" 를 묻는 것이므로 기저 승률 자체가 답이다</b> (D14).
 * 그래서 Bradley-Terry 기대 승률을 빼지 않는다. 실수로 빼면 모든 챔피언이 50% 근처로
 * 눌려서 티어표가 통째로 무의미해지는데, 값은 여전히 0~1 사이라 그럴듯하다.
 *
 * <p>축소 목표값이 0.5 인 근거는 산술이다 — 4v4 한 경기에 승자 넷과 패자 넷이 있으므로
 * 전체 챔피언 출전의 승률 합은 <b>정확히</b> 50% 다. 모르는 챔피언을 평균으로 두는 것이다.
 */
class PerformanceCalculatorTest {

    private static final AnalysisConfig CONFIG = new AnalysisConfig(10, 24, 15, 3, 2, 12);

    private static ChampionTally tally(int games, int wins) {
        return new ChampionTally(games, wins, games, wins, games);
    }

    private static PerformanceRow only(Map<Integer, ChampionTally> tallies) {
        List<PerformanceRow> rows = PerformanceCalculator.calculate(
                tallies, Map.of(), tallies.size(), 0, CONFIG);
        return rows.get(0);
    }

    @Test
    @DisplayName("데이터가 없으면 추정이 50% 다 — 모르는 챔피언은 평균으로 둔다")
    void calculate_noDataFallsBackToHalf() {
        PerformanceRow row = only(Map.of(7, ChampionTally.EMPTY));

        assertThat(row.adjustedWinRate()).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("표본이 적으면 50% 쪽으로 당겨진다 — 4경기 100% 가 상위로 못 올라온다 (D9·D10)")
    void calculate_smallSampleShrinksTowardHalf() {
        PerformanceRow row = only(Map.of(7, tally(4, 4)));

        assertThat(row.rawWinRate()).isEqualTo(1.0);
        // (4 + 24×0.5) / (4 + 24) = 16/28 = 0.571
        assertThat(row.adjustedWinRate()).isCloseTo(0.5714285714, within(1e-9));
    }

    @Test
    @DisplayName("표본이 많으면 관측 승률로 수렴한다")
    void calculate_largeSampleConvergesToObserved() {
        PerformanceRow row = only(Map.of(7, tally(1000, 610)));

        assertThat(row.adjustedWinRate()).isCloseTo(0.61, within(0.005));
    }

    @Test
    @DisplayName("적은 표본의 높은 승률이 많은 표본의 낮은 승률보다 아래로 간다 (D10)")
    void calculate_rankingPrefersEvidenceOverExtremes() {
        List<PerformanceRow> rows = PerformanceCalculator.calculate(
                Map.of(1, tally(10, 9), 2, tally(60, 44)), Map.of(), 100, 100, CONFIG);

        PerformanceRow small = rows.stream().filter(r -> r.championId() == 1).findFirst().orElseThrow();
        PerformanceRow large = rows.stream().filter(r -> r.championId() == 2).findFirst().orElseThrow();

        assertThat(small.rawWinRate()).isGreaterThan(large.rawWinRate());
        // 변조: 축소를 건너뛰고 raw 를 그대로 쓰면 이 부등호가 뒤집힌다.
        //       10경기 90% 가 60경기 73% 위에 오면 순위표가 망가진다.
        assertThat(small.adjustedWinRate()).isLessThan(large.adjustedWinRate());
    }

    @Test
    @DisplayName("같은 누적이면 같은 추정이다 — 상대가 누구였는지는 티어에 안 들어간다 (D14)")
    void calculate_ignoresOpponentStrength() {
        List<PerformanceRow> rows = PerformanceCalculator.calculate(
                Map.of(1, tally(50, 30), 2, tally(50, 30)), Map.of(), 100, 100, CONFIG);

        // 상대 정보는 이 함수의 입력에 아예 없다 — 타입이 D14 를 강제한다.
        // 변조: Bradley-Terry 기대 승률을 빼도록 바꾸려면 시그니처부터 바꿔야 한다.
        assertThat(rows.get(0).adjustedWinRate())
                .isCloseTo(rows.get(1).adjustedWinRate(), within(1e-12));
    }

    @Test
    @DisplayName("밴 수가 실린다. 밴이 없는 챔피언은 0 이다")
    void calculate_carriesBanCounts() {
        List<PerformanceRow> rows = PerformanceCalculator.calculate(
                Map.of(1, tally(10, 5), 2, tally(10, 5)), Map.of(1, 7), 100, 80, CONFIG);

        PerformanceRow banned = rows.stream().filter(r -> r.championId() == 1).findFirst().orElseThrow();
        PerformanceRow clean = rows.stream().filter(r -> r.championId() == 2).findFirst().orElseThrow();

        assertThat(banned.bans()).isEqualTo(7);
        assertThat(clean.bans()).isZero();
    }

    @Test
    @DisplayName("픽률과 밴률의 분모가 서로 다르다 — 밴은 공식전에만 있다 (D50)")
    void calculate_carriesSeparateDenominators() {
        PerformanceRow row = PerformanceCalculator.calculate(
                Map.of(1, tally(10, 5)), Map.of(), 1110, 698, CONFIG).get(0);

        // 변조: 두 분모를 하나로 합치면 스크림 포함 스코프에서 밴률이 실제의 63% 가 된다.
        assertThat(row.matchCount()).isEqualTo(1110);
        assertThat(row.banMatchCount()).isEqualTo(698);
    }

    @Test
    @DisplayName("한 번도 출전하지 않았지만 밴만 당한 챔피언도 행이 생긴다")
    void calculate_includesChampionsThatWereOnlyBanned() {
        List<PerformanceRow> rows = PerformanceCalculator.calculate(
                Map.of(1, tally(10, 5)), Map.of(9, 3), 100, 80, CONFIG);

        // 변조: 출전 누적만 훑으면 챔피언 9 가 표에서 사라진다. "아무도 안 뽑는데 늘 밴당하는"
        //       챔피언이야말로 티어표가 보여줘야 하는 것이다.
        assertThat(rows).hasSize(2);
        PerformanceRow bannedOnly = rows.stream()
                .filter(r -> r.championId() == 9).findFirst().orElseThrow();
        assertThat(bannedOnly.games()).isZero();
        assertThat(bannedOnly.bans()).isEqualTo(3);
        assertThat(bannedOnly.adjustedWinRate()).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("티어 등급은 아직 매기지 않는다 — 컷라인을 분포를 보고 정해야 한다")
    void calculate_tierGradeIsNotAssignedYet() {
        PerformanceRow row = only(Map.of(7, tally(50, 30)));

        // 기저 승률 표준편차가 3.6%p 로 작아서 고정 컷(55% 이상 S)을 쓰면 대부분 한 등급에
        // 몰린다. 근거 없는 등급을 붙이느니 비워 둔다 — 화면은 추정 승률로 정렬하면 된다.
        assertThat(row.tierGrade()).isNull();
    }

    @Test
    @DisplayName("빈 입력은 빈 결과다")
    void calculate_emptyInput() {
        assertThat(PerformanceCalculator.calculate(Map.of(), Map.of(), 0, 0, CONFIG)).isEmpty();
    }
}
