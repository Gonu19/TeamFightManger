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

    public boolean isPlayer() {
        return player;
    }
}
