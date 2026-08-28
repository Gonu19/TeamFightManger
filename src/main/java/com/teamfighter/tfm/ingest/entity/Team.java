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

    /** 세이브 {@code TeamInfo.NameKey}. 커스텀 이름이면 {@code null} (D56). */
    @Column(name = "name_key")
    private String nameKey;

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

    /** 세이브가 말한 로컬라이제이션 키. 표시 이름과 달리 <b>해석되지 않은 사실</b>이다 (D56). */
    public String getNameKey() {
        return nameKey;
    }

    /**
     * 신원을 붙인다. <b>이미 있는 값은 덮지 않는다</b> — 적재 시점의 이름이 그 커리어의 기록이고,
     * 나중에 프로필을 커스터마이즈해도 지나간 커리어의 표가 바뀌면 안 된다 (D55).
     *
     * <p>이름과 키를 <b>따로</b> 채운다. 키는 알지만 시드에 이름이 없는 경우가 있어서다 —
     * 나중에 시드를 채우면 그때 이름만 붙는다.
     *
     * @return 둘 중 하나라도 새로 붙었으면 {@code true}
     */
    public boolean identifyIfAbsent(String name, String nameKey) {
        boolean changed = false;
        if (isBlank(this.name) && !isBlank(name)) {
            this.name = name;
            changed = true;
        }
        if (isBlank(this.nameKey) && !isBlank(nameKey)) {
            this.nameKey = nameKey;
            changed = true;
        }
        return changed;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public boolean isPlayer() {
        return player;
    }
}
