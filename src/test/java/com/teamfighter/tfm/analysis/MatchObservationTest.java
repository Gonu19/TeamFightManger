package com.teamfighter.tfm.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 집계의 입력 단위 {@link MatchObservation} 의 불변조건을 못 박는다.
 *
 * <p>DB 는 필요 없다.
 *
 * <p>이 타입이 지키는 것은 스키마의 {@code match_participant_unique_champ} 와 같은 것이다.
 * 한 경기에 같은 챔피언이 두 번 나오면 그 챔피언은 <b>자기 자신과 카운터 관계를 맺는다</b>.
 * 그러면 Bradley-Terry 가 자기대결 관측을 받아 던지는데, 예외가 나는 자리는 DB 에서
 * 한참 떨어진 곳이라 원인을 찾기 어렵다. 여기서 막는다.
 */
class MatchObservationTest {

    private static MatchObservation.Participant p(int championId) {
        return new MatchObservation.Participant(championId, 0);
    }

    @Test
    @DisplayName("승리 팀과 패배 팀을 담는다")
    void holdsBothSides() {
        MatchObservation m = new MatchObservation(1L, 3, List.of(p(1), p(2)), List.of(p(3), p(4)));

        assertThat(m.winners()).hasSize(2);
        assertThat(m.losers()).hasSize(2);
        assertThat(m.patchSeq()).isEqualTo(3);
    }

    @Test
    @DisplayName("패치가 배정되지 않은 경기도 담는다 — 첫 패치 이전이면 null 이다")
    void allowsNullPatchSeq() {
        MatchObservation m = new MatchObservation(1L, null, List.of(p(1)), List.of(p(2)));

        assertThat(m.patchSeq()).isNull();
    }

    @Test
    @DisplayName("같은 챔피언이 양 팀에 있으면 던진다 — 자기 자신과 카운터 관계가 생긴다")
    void duplicateChampionAcrossSidesThrows() {
        assertThatThrownBy(() ->
                new MatchObservation(1L, 3, List.of(p(1), p(2)), List.of(p(2), p(4))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");
    }

    @Test
    @DisplayName("같은 챔피언이 한 팀에 두 번 있으면 던진다 — 시너지 조합이 자기 자신과 묶인다")
    void duplicateChampionOnSameSideThrows() {
        assertThatThrownBy(() ->
                new MatchObservation(1L, 3, List.of(p(1), p(1)), List.of(p(3), p(4))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("한쪽이 비어 있으면 던진다 — 관측이 하나도 안 나오는 경기다")
    void emptySideThrows() {
        assertThatThrownBy(() -> new MatchObservation(1L, 3, List.of(), List.of(p(3))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchObservation(1L, 3, List.of(p(1)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("참가자 목록은 방어적으로 복사된다 — 밖에서 바꿔도 관측이 안 흔들린다")
    void participantListsAreCopied() {
        List<MatchObservation.Participant> winners = new java.util.ArrayList<>(List.of(p(1)));
        MatchObservation m = new MatchObservation(1L, 3, winners, List.of(p(2)));

        winners.add(p(9));

        assertThat(m.winners()).hasSize(1);
    }

    @Test
    @DisplayName("경기 시점 변경 횟수가 음수면 던진다")
    void negativeChangeCountThrows() {
        assertThatThrownBy(() -> new MatchObservation.Participant(1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
