package com.teamfighter.tfm.analysis.performance;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import com.teamfighter.tfm.analysis.MatchObservation;
import com.teamfighter.tfm.analysis.ReferencePoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 챔피언별 출전·승리 누적을 못 박는다 (D19 · D21).
 *
 * <p>DB 는 필요 없다.
 *
 * <p>카운터와 결정적으로 다른 점이 하나 있다 — <b>여기서는 감쇠에 챔피언 하나만 쓴다.</b>
 * 쌍이 아니라 그 챔피언 자신의 변경 횟수다(D42 의 합산은 상성에만 해당한다). 이걸 헷갈려
 * {@code forPair} 를 쓰면 티어의 유효표본이 이유 없이 절반 속도로 마르는데, 승률은 거의
 * 안 변해서 눈으로는 안 보인다.
 */
class ChampionTallyAggregatorTest {

    private static final AnalysisConfig CONFIG = new AnalysisConfig(10, 24, 15, 3, 2, 12);
    private static final ReferencePoint NOW = new ReferencePoint(0, Map.of());

    private static MatchObservation.Participant p(int championId) {
        return new MatchObservation.Participant(championId, 0);
    }

    private static MatchObservation fourVersusFour(long id) {
        return new MatchObservation(id, 0,
                List.of(p(1), p(2), p(3), p(4)),
                List.of(p(5), p(6), p(7), p(8)));
    }

    @Test
    @DisplayName("한 경기에서 여덟 챔피언이 각각 1출전 — 이긴 넷만 1승이다")
    void aggregate_countsEveryParticipantOnce() {
        Map<Integer, ChampionTally> tallies =
                ChampionTallyAggregator.aggregate(List.of(fourVersusFour(1L)), NOW, CONFIG);

        assertThat(tallies).hasSize(8);
        assertThat(tallies.get(1).games()).isEqualTo(1);
        assertThat(tallies.get(1).wins()).isEqualTo(1);
        assertThat(tallies.get(5).games()).isEqualTo(1);
        // 변조: 패배 팀에도 승리를 세면 모든 챔피언 승률이 100% 가 된다.
        assertThat(tallies.get(5).wins()).isZero();
    }

    @Test
    @DisplayName("경기 수는 참가자 수가 아니라 경기 수다 — 픽률의 분모다")
    void aggregate_matchCountIsMatchesNotParticipants() {
        Map<Integer, ChampionTally> tallies = ChampionTallyAggregator.aggregate(
                List.of(fourVersusFour(1L), fourVersusFour(2L)), NOW, CONFIG);

        // 변조: 분모를 참가자 수(16)로 세면 픽률이 8분의 1로 줄어든다.
        //       그래도 0~1 사이라 표는 멀쩡해 보인다.
        assertThat(ChampionTallyAggregator.matchCount(
                List.of(fourVersusFour(1L), fourVersusFour(2L)))).isEqualTo(2);
        assertThat(tallies.get(1).games()).isEqualTo(2);
    }

    @Test
    @DisplayName("감쇠는 그 챔피언 자신의 변경만 본다 — 쌍의 합산이 아니다 (D42 는 상성 전용)")
    void aggregate_decayUsesSingleChampionNotPair() {
        MatchObservation match = new MatchObservation(1L, 4,
                List.of(new MatchObservation.Participant(1, 0)),
                List.of(new MatchObservation.Participant(5, 0)));
        ReferencePoint ref = new ReferencePoint(4, Map.of(1, 2, 5, 2));

        Map<Integer, ChampionTally> tallies =
                ChampionTallyAggregator.aggregate(List.of(match), ref, CONFIG);

        // 챔피언 1 은 변경 2회 · 반감기 2 → 0.5. 쌍으로 합쳤다면(2+2=4) 0.25 가 된다.
        assertThat(tallies.get(1).weightedGames()).isCloseTo(0.5, within(1e-12));
        assertThat(tallies.get(5).weightedGames()).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("챔피언마다 감쇠가 다르다 — 한쪽만 패치로 바뀌었으면 그쪽만 깎인다")
    void aggregate_decayIsPerChampion() {
        MatchObservation match = new MatchObservation(1L, 4,
                List.of(new MatchObservation.Participant(1, 0)),
                List.of(new MatchObservation.Participant(5, 0)));
        ReferencePoint ref = new ReferencePoint(4, Map.of(1, 2));

        Map<Integer, ChampionTally> tallies =
                ChampionTallyAggregator.aggregate(List.of(match), ref, CONFIG);

        // 변조: 경기 단위로 가중치를 한 번만 계산해 모든 참가자에게 같은 값을 주면
        //       두 값이 같아진다. 경기 수만 보면 안 잡힌다.
        assertThat(tallies.get(1).weightedGames()).isCloseTo(0.5, within(1e-12));
        assertThat(tallies.get(5).weightedGames()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("가중 승수는 이긴 경기의 가중치만 더한다")
    void aggregate_weightedWinsOnlyCountVictories() {
        MatchObservation won = new MatchObservation(1L, 0,
                List.of(p(1)), List.of(p(5)));
        MatchObservation lost = new MatchObservation(2L, 0,
                List.of(p(5)), List.of(p(1)));

        Map<Integer, ChampionTally> tallies =
                ChampionTallyAggregator.aggregate(List.of(won, lost), NOW, CONFIG);

        assertThat(tallies.get(1).games()).isEqualTo(2);
        assertThat(tallies.get(1).wins()).isEqualTo(1);
        assertThat(tallies.get(1).weightedGames()).isCloseTo(2.0, within(1e-12));
        assertThat(tallies.get(1).weightedWins()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    @DisplayName("전체 승률은 정확히 50% 다 — 한 경기에 승자 넷과 패자 넷이 있다")
    void aggregate_overallWinRateIsExactlyHalf() {
        List<MatchObservation> matches = List.of(
                fourVersusFour(1L), fourVersusFour(2L), fourVersusFour(3L));

        Map<Integer, ChampionTally> tallies =
                ChampionTallyAggregator.aggregate(matches, NOW, CONFIG);

        int games = tallies.values().stream().mapToInt(ChampionTally::games).sum();
        int wins = tallies.values().stream().mapToInt(ChampionTally::wins).sum();
        // 이 등식이 티어 축소의 목표값을 0.5 로 두는 근거다 (D50).
        assertThat(wins * 2).isEqualTo(games);
    }

    @Test
    @DisplayName("유효표본수는 가중치가 흩어질수록 경기 수보다 작아진다")
    void aggregate_effectiveSampleSize() {
        MatchObservation recent = new MatchObservation(1L, 12, List.of(p(1)), List.of(p(5)));
        MatchObservation old = new MatchObservation(2L, 0, List.of(p(1)), List.of(p(5)));
        ReferencePoint ref = new ReferencePoint(12, Map.of());

        ChampionTally tally =
                ChampionTallyAggregator.aggregate(List.of(recent, old), ref, CONFIG).get(1);

        assertThat(tally.games()).isEqualTo(2);
        assertThat(tally.ess()).isCloseTo(1.8, within(1e-12));
    }

    @Test
    @DisplayName("슬롯을 합치면 누적이 더해지고, 슬롯별 감쇠는 보존된다 (D45)")
    void merge_addsAcrossSlotsPreservingPerSlotDecay() {
        Map<Integer, ChampionTally> fresh = ChampionTallyAggregator.aggregate(
                List.of(new MatchObservation(1L, 5, List.of(p(1)), List.of(p(5)))),
                new ReferencePoint(5, Map.of()), CONFIG);
        Map<Integer, ChampionTally> faded = ChampionTallyAggregator.aggregate(
                List.of(new MatchObservation(2L, 0, List.of(p(1)), List.of(p(5)))),
                new ReferencePoint(12, Map.of()), CONFIG);

        ChampionTally merged = ChampionTallyAggregator.merge(List.of(fresh, faded)).get(1);

        assertThat(merged.games()).isEqualTo(2);
        // 변조: 두 슬롯 경기를 한 목록으로 합쳐 하나의 기준으로 감쇠하면 1.5 가 아니다.
        assertThat(merged.weightedGames()).isCloseTo(1.5, within(1e-12));
    }

    @Test
    @DisplayName("슬롯별 피밴 수도 합쳐진다")
    void mergeBans_addsAcrossSlots() {
        Map<Integer, Integer> merged = ChampionTallyAggregator.mergeBans(
                List.of(Map.of(1, 3, 2, 1), Map.of(1, 4)));

        assertThat(merged).containsEntry(1, 7).containsEntry(2, 1);
    }

    @Test
    @DisplayName("빈 입력은 빈 결과다")
    void aggregate_emptyInput() {
        assertThat(ChampionTallyAggregator.aggregate(List.of(), NOW, CONFIG)).isEmpty();
        assertThat(ChampionTallyAggregator.matchCount(List.of())).isZero();
    }
}
