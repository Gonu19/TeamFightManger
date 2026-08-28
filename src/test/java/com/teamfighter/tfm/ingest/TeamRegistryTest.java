package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.Team;
import com.teamfighter.tfm.ingest.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
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

        Team team = new TeamRegistry(SLOT, teams, null).ensure(36);

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

        TeamRegistry registry = new TeamRegistry(SLOT, teams, null);

        assertThat(registry.ensure(0).isPlayer()).isTrue();
        assertThat(registry.ensure(36).isPlayer()).isFalse();
    }

    @Test
    @DisplayName("같은 번호를 다시 물으면 조회도 저장도 하지 않는다 — 경기 수만큼 왕복하지 않는다")
    void ensure_cachesWithinOneLoad() {
        TeamRepository teams = mock(TeamRepository.class);
        when(teams.findBySlotIdAndGameTeamId(eq(SLOT), eq(36))).thenReturn(Optional.empty());
        when(teams.save(any(Team.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100));

        TeamRegistry registry = new TeamRegistry(SLOT, teams, null);
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

        Team team = new TeamRegistry(SLOT, teams, null).ensure(36);

        assertThat(team.getTeamId()).isEqualTo(42);
        verify(teams, never()).save(any(Team.class));
    }

    @Test
    @DisplayName("번호가 없으면 팀도 없다 — 0 번(플레이어 팀)으로 둔갑시키지 않는다")
    void ensure_returnsNull_whenNumberIsMissing() {
        TeamRepository teams = mock(TeamRepository.class);

        assertThat(new TeamRegistry(SLOT, teams, null).ensure(null)).isNull();

        verify(teams, never()).findBySlotIdAndGameTeamId(any(), any());
        verify(teams, never()).save(any(Team.class));
    }

    @Test
    @DisplayName("이름표를 안 주면 이름 없이 만든다 — 경기 기록(GameStat)에는 이름이 없다")
    void ensure_leavesNameEmpty() {
        TeamRepository teams = mock(TeamRepository.class);
        when(teams.findBySlotIdAndGameTeamId(eq(SLOT), eq(36))).thenReturn(Optional.empty());
        when(teams.save(any(Team.class))).thenAnswer(inv -> withId(inv.getArgument(0), 100));

        assertThat(new TeamRegistry(SLOT, teams, null).ensure(36).getName()).isNull();
    }

    // ------------------------------------------------------------ D55 이름

    private static TeamNaming naming() {
        return TeamNaming.of(
                java.util.List.of(new com.teamfighter.tfm.parser.common.ParsedTeamInfo(36, "team.name.pro.team9", false),
                        new com.teamfighter.tfm.parser.common.ParsedTeamInfo(0, "내 팀", true)),
                Map.of("team.name.pro.team9", "Afreeca Freecs"),
                null);
    }

    @Test
    @DisplayName("이름표가 있으면 이름을 붙인다. 0 번은 플레이어 팀 이름을 받는다")
    void ensure_namesTeamsFromRoster() {
        TeamRepository teams = mock(TeamRepository.class);
        when(teams.findBySlotIdAndGameTeamId(eq(SLOT), any())).thenReturn(Optional.empty());
        when(teams.save(any(Team.class))).thenAnswer(inv -> withId(inv.getArgument(0), 1));

        TeamRegistry registry = new TeamRegistry(SLOT, teams, naming());

        assertThat(registry.ensure(36).getName()).isEqualTo("Afreeca Freecs");
        assertThat(registry.ensure(36).getNameKey()).isEqualTo("team.name.pro.team9");
        // 0 번은 딕셔너리가 아니라 CommonStore 에서 온다 — 여기서 빠지면 내 팀만 이름이 없다.
        assertThat(registry.ensure(0).getName()).isEqualTo("내 팀");
        assertThat(registry.ensure(99).getName()).isNull();      // 이름표에 없는 번호
        assertThat(registry.namedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("이미 이름이 있으면 덮지 않는다 — 지나간 커리어의 표가 바뀌면 안 된다")
    void ensure_doesNotOverwriteExistingName() {
        TeamRepository teams = mock(TeamRepository.class);
        Team existing = withId(new Team(SLOT, 36), 42);
        existing.identifyIfAbsent("KT Rolster Bullets", "team.name.pro.team8");   // 예전 적재 때 붙은 신원
        when(teams.findBySlotIdAndGameTeamId(eq(SLOT), eq(36))).thenReturn(Optional.of(existing));

        Team team = new TeamRegistry(SLOT, teams, naming()).ensure(36);

        // 변조: identifyIfAbsent 를 무조건 대입으로 바꾸면 프로필을 커스터마이즈할 때마다
        // 과거 커리어의 팀 이름이 통째로 갈아엎힌다.
        assertThat(team.getName()).isEqualTo("KT Rolster Bullets");
    }

    @Test
    @DisplayName("이름표가 없어도 적재는 돈다 — 번호로만 보일 뿐이다")
    void ensure_worksWithoutRoster() {
        TeamRepository teams = mock(TeamRepository.class);
        when(teams.findBySlotIdAndGameTeamId(eq(SLOT), eq(36))).thenReturn(Optional.empty());
        when(teams.save(any(Team.class))).thenAnswer(inv -> withId(inv.getArgument(0), 1));

        TeamRegistry registry = new TeamRegistry(SLOT, teams, null);

        assertThat(registry.ensure(36).getGameTeamId()).isEqualTo(36);
        assertThat(registry.ensure(36).getName()).isNull();
        assertThat(registry.namedCount()).isZero();
    }

    /** {@code team_id} 는 DB 가 만든다. 목에는 그 자리가 없어 여기서 채운다. */
    private static Team withId(Team team, int teamId) {
        ReflectionTestUtils.setField(team, "teamId", teamId);
        return team;
    }
}
