package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 팀. {@code gameTeamId} 는 슬롯 안에서만 유효한 번호다 — 0 이 플레이어 팀이다.
 * 슬롯이 다르면 같은 번호라도 다른 팀이므로 {@code (slot_id, game_team_id)} 가 자연키다.
 */
@Entity
@Table(name = "team", uniqueConstraints = @UniqueConstraint(columnNames = {"slot_id", "game_team_id"}))
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "team_id")
    private Integer teamId;

    @Column(name = "slot_id", nullable = false)
    private Integer slotId;

    @Column(name = "game_team_id", nullable = false)
    private Integer gameTeamId;

    private String name;

    @Column(name = "is_player", nullable = false)
    private boolean player;

    protected Team() {
    }

    public Team(Integer slotId, Integer gameTeamId) {
        this.slotId = slotId;
        this.gameTeamId = gameTeamId;
        this.player = gameTeamId != null && gameTeamId == 0;
    }

    public Integer getTeamId() {
        return teamId;
    }

    public Integer getSlotId() {
        return slotId;
    }

    public Integer getGameTeamId() {
        return gameTeamId;
    }

    /** 팀 이름. 세이브가 아니라 {@code common.data} 에서 온다 (D55). 없으면 {@code null}. */
    public String getName() {
        return name;
    }

    /**
     * 이름을 붙인다. <b>이미 있는 이름은 덮지 않는다</b> — 적재 시점의 이름이 그 커리어의 기록이고,
     * 나중에 프로필을 다시 커스터마이즈해도 지나간 커리어의 표가 바뀌면 안 된다 (D55).
     *
     * @return 실제로 붙였으면 {@code true}
     */
    public boolean nameIfAbsent(String name) {
        if (this.name != null && !this.name.isBlank()) {
            return false;
        }
        if (name == null || name.isBlank()) {
            return false;
        }
        this.name = name;
        return true;
    }

    public boolean isPlayer() {
        return player;
    }
}
