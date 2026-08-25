package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 밴 한 건. 스크림에는 밴이 없으므로 공식 경기에만 행이 생긴다. */
@Entity
@Table(name = "match_ban")
public class MatchBan {

    @EmbeddedId
    private BanId id;

    @Column(name = "champion_id", nullable = false)
    private Integer championId;

    protected MatchBan() {
    }

    public MatchBan(BanId id, Integer championId) {
        this.id = id;
        this.championId = championId;
    }

    public BanId getId() {
        return id;
    }

    public Integer getChampionId() {
        return championId;
    }
}
