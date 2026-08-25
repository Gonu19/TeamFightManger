package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/** 밴 한 건. 스크림에는 밴이 없으므로 공식 경기에만 행이 생긴다. */
@Entity
@Table(name = "match_ban")
public class MatchBan implements Persistable<BanId> {

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

    @Override
    public BanId getId() {
        return id;
    }

    /**
     * 항상 새 행이다.
     *
     * <p>{@code @EmbeddedId} 는 ID 가 미리 채워져 있어서, Spring Data 의 기본 판정은
     * "ID 가 있으니 기존 행" 이 된다. 그러면 {@code save()} 가 {@code persist()} 가 아니라
     * {@code merge()} 를 호출하고, <b>INSERT 전에 SELECT 를 한 번 더 낸다.</b>
     *
     * <p>실측했다 — 경기 475건 적재에 statement 11,901개 중 5,720개가 그 SELECT 였다.
     * 있을 리 없는 행을 매번 확인한 셈이다.
     *
     * <p>이 테이블들은 <b>적재에서만 쓰고 수정하지 않는다.</b> 그래서 항상 새 행으로 본다.
     * 혹시 같은 키를 두 번 넣으면 유니크 제약에서 크게 실패한다 — 조용히 덮어쓰지 않는다.
     */
    @Override
    public boolean isNew() {
        return true;
    }

    public Integer getChampionId() {
        return championId;
    }
}
