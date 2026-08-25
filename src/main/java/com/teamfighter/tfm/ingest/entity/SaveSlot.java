package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 세이브 슬롯 = 커리어 하나.
 *
 * <p>패치가 슬롯마다 고유하게 생성되므로 분석 스코프의 단위가 된다.
 * {@code slotKey} 는 파일명이다 — {@code *.tfm_backup} 을 슬롯으로 잡으면
 * 같은 커리어가 두 벌 적재된다(D28).
 */
@Entity
@Table(name = "save_slot")
public class SaveSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "slot_id")
    private Integer slotId;

    @Column(name = "slot_key", nullable = false, unique = true)
    private String slotKey;

    private String label;

    @Column(name = "team_name")
    private String teamName;

    @Column(name = "first_seen_at", insertable = false, updatable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_ingest_at")
    private OffsetDateTime lastIngestAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected SaveSlot() {
    }

    public SaveSlot(String slotKey, String teamName) {
        this.slotKey = slotKey;
        this.teamName = teamName;
    }

    public Integer getSlotId() {
        return slotId;
    }

    public String getSlotKey() {
        return slotKey;
    }

    public String getLabel() {
        return label;
    }

    public String getTeamName() {
        return teamName;
    }

    public OffsetDateTime getLastIngestAt() {
        return lastIngestAt;
    }

    public void markIngested(OffsetDateTime at) {
        this.lastIngestAt = at;
    }

    public void rename(String label) {
        this.label = label;
    }
}
