package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.Team;
import com.teamfighter.tfm.ingest.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TeamRegistry} 의 계약을 고정한다. DB 없이 돈다 — 저장소는 목이다.
 *
 * <p>여기서 지키는 것은 넷이다: <b>슬롯 안에서 번호가 팀을 정한다</b> ·
 * <b>0 번은 플레이어 팀이다</b> · <b>같은 번호를 두 번 물어도 한 행이다</b> ·
 * <b>번호가 없으면 팀도 없다</b>. 마지막이 없으면 팀 없는 경기가 0 번(= 플레이어 팀)으로
 * 둔갑한다 — 조용히 틀리는 쪽이다.
 */
class TeamRegistryTest {

    private static final int SLOT = 7;

    @Test
    @DisplayName("처음 보는 번호는 팀을 만든다 — 슬롯과 번호가 그대로 들어간다")
    void ensure_createsTeam_whenNumberIsNew() {
        TeamRepository teams = mock(TeamRepository.class);
        when(teams.findBySlotIdAndGameTeamId(eq(SLOT), eq(36))).thenReturn(Optional.empty());
        when(teams.save(any(Team.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100));

        Team team = new TeamRegistry(SLOT, teams).ensure(36);

        ArgumentCaptor<Team> saved = ArgumentCaptor.forClass(Team.class);
        verify(teams).save(saved.capture());
        assertThat(saved.getValue().getSlotId()).isEqualTo(SLOT);
        assertThat(saved.getValue().getGameTeamId()).isEqualTo(36);
        assertThat(team.getTeamId()).isEqualTo(100);
    }

    @Test
    @DisplayName("0 번은 플레이어 팀이고, 나머지는 아니다")
    void ensure_marksTeamZeroAsPlayer() {
        TeamRepository teams = mock(TeamRepository.class);
        when(teams.findBySlotIdAndGameTeamId(eq(SLOT), any())).thenReturn(Optional.empty());
        when(teams.save(any(Team.class))).thenAnswer(inv -> withId(inv.getArgument(0), 1));

        TeamRegistry registry = new TeamRegistry(SLOT, teams);

        assertThat(registry.ensure(0).isPlayer()).isTrue();
        assertThat(registry.ensure(36).isPlayer()).isFalse();
    }

    @Test
    @DisplayName("같은 번호를 다시 물으면 조회도 저장도 하지 않는다 — 경기 수만큼 왕복하지 않는다")
    void ensure_cachesWithinOneLoad() {
        TeamRepository teams = mock(TeamRepository.class);
        when(teams.findBySlotIdAndGameTeamId(eq(SLOT), eq(36))).thenReturn(Optional.empty());
        when(teams.save(any(Team.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100));

        TeamRegistry registry = new TeamRegistry(SLOT, teams);
        Team first = registry.ensure(36);
        Team second = registry.ensure(36);

        assertThat(second).isSameAs(first);
        verify(teams, times(1)).findBySlotIdAndGameTeamId(eq(SLOT), eq(36));
        verify(teams, times(1)).save(any(Team.class));
    }

    @Test
    @DisplayName("이미 있는 팀은 다시 만들지 않는다 — 두 번째 적재가 행을 늘리면 안 된다")
    void ensure_reusesExistingTeam() {
        TeamRepository teams = mock(TeamRepository.class);
        Team existing = withId(new Team(SLOT, 36), 42);
        when(teams.findBySlotIdAndGameTeamId(eq(SLOT), eq(36))).thenReturn(Optional.of(existing));

        Team team = new TeamRegistry(SLOT, teams).ensure(36);

        assertThat(team.getTeamId()).isEqualTo(42);
        verify(teams, never()).save(any(Team.class));
    }

    @Test
    @DisplayName("번호가 없으면 팀도 없다 — 0 번(플레이어 팀)으로 둔갑시키지 않는다")
    void ensure_returnsNull_whenNumberIsMissing() {
        TeamRepository teams = mock(TeamRepository.class);

        assertThat(new TeamRegistry(SLOT, teams).ensure(null)).isNull();

        verify(teams, never()).findBySlotIdAndGameTeamId(any(), any());
        verify(teams, never()).save(any(Team.class));
    }

    /** {@code team_id} 는 DB 가 만든다. 목에는 그 자리가 없어 여기서 채운다. */
    private static Team withId(Team team, int teamId) {
        ReflectionTestUtils.setField(team, "teamId", teamId);
        return team;
    }
}
