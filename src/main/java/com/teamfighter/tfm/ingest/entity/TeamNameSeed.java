package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 로컬라이제이션 키 → 표시 이름 (D56).
 *
 * <p>게임 에셋에는 키만 있고 표시 문자열이 없어 손으로 넣은 값이다. 그래서 <b>틀릴 수 있고,
 * 틀리면 이 표만 고친다</b> — 적재는 키를 저장하므로 재적재가 필요 없다.
 *
 * <p>{@code tier} 는 리그 단계다. 실측상 표본 크기와 직결된다 — 플레이어가 속한 리그는
 * 팀당 41~66세트, {@code worlds} 는 3~18세트다.
 */
@Entity
@Table(name = "team_name_seed")
public class TeamNameSeed {

    @Id
    @Column(name = "name_key")
    private String nameKey;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String league;

    @Column(nullable = false)
    private short tier;

    @Column(nullable = false)
    private short seq;

    protected TeamNameSeed() {
    }

    public String getNameKey() {
        return nameKey;
    }

    public String getName() {
        return name;
    }

    public String getLeague() {
        return league;
    }

    public short getTier() {
        return tier;
    }

    public short getSeq() {
        return seq;
    }
}
