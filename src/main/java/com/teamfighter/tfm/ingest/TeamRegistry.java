package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.Team;
import com.teamfighter.tfm.ingest.repository.TeamRepository;

import java.util.HashMap;
import java.util.Map;

/**
 * 한 슬롯의 팀을 찾거나 만든다. <b>적재 한 번에 하나씩 새로 만든다</b>.
 *
 * <p>{@code @Component} 가 아닌 이유가 캐시에 있다. 이 클래스는 조회를 아끼려고 이미 만든
 * 팀을 들고 있는데, {@link SaveLoader} 의 트랜잭션이 롤백되면 그 팀 행은 사라진다.
 * 빈으로 두면 캐시만 살아남아 <b>없는 {@code team_id} 를 다음 적재에 넘긴다</b> —
 * 외래키 위반으로 죽거나, 더 나쁘게는 재사용된 번호에 붙는다.
 * 수명을 트랜잭션에 맞추는 가장 확실한 방법은 트랜잭션 안에서 만드는 것이다.
 *
 * <p><b>이름은 여기서 붙이지 않는다</b> (D56). 이름 붙이기를 이 클래스에 매달았더니
 * {@code ensure()} 가 불리지 않는 경로 — 팀 번호가 이미 다 채워진 재적재 — 에서 통째로
 * 건너뛰어졌다. <b>이미 끝난 일에 새 일을 매달면 그 일도 같이 안 돈다.</b>
 * 이름은 {@code SaveLoader} 가 슬롯의 팀 전체를 훑는 독립된 단계에서 붙인다.
 *
 * <p>{@link SlotRegistry} 와 이름은 닮았지만 트랜잭션 성격은 정반대다. 슬롯은 적재가
 * 실패해도 <b>남아야</b> 하고(실패 기록이 참조한다), 팀은 적재가 실패하면
 * <b>같이 사라져야</b> 한다 — 팀은 그 경기들 없이는 아무 의미가 없다.
 */
final class TeamRegistry {

    private final Integer slotId;
    private final TeamRepository teams;
    private final Map<Integer, Team> cache = new HashMap<>();

    TeamRegistry(Integer slotId, TeamRepository teams) {
        this.slotId = slotId;
        this.teams = teams;
    }

    /**
     * 게임 안의 팀 번호로 팀을 찾거나 만든다.
     *
     * <p>번호는 <b>슬롯 안에서만</b> 유효하다 — 커리어가 다르면 같은 36번이 다른 팀이다.
     * 그래서 자연키가 {@code (slot_id, game_team_id)} 다.
     *
     * @param gameTeamId {@code GameStat.BlueTeamID} / {@code RedTeamID}. 0 이 플레이어 팀이다
     * @return 팀. {@code gameTeamId} 가 {@code null} 이면 {@code null} — 번호가 없는 경기를
     *         0 번(플레이어 팀)으로 만들지 않는다
     */
    Team ensure(Integer gameTeamId) {
        if (gameTeamId == null) {
            return null;
        }
        return cache.computeIfAbsent(gameTeamId, id ->
                teams.findBySlotIdAndGameTeamId(slotId, id)
                        .orElseGet(() -> teams.save(new Team(slotId, id))));
    }

    /** {@link #ensure(Integer)} 의 결과에서 식별자만 꺼낸다. 팀이 없으면 {@code null}. */
    Integer teamIdOf(Integer gameTeamId) {
        Team team = ensure(gameTeamId);
        return team == null ? null : team.getTeamId();
    }
}
