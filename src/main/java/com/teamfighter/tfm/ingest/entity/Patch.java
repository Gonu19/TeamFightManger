package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 패치 한 건.
 *
 * <p>커리어마다 고유하게 생성된다 — 롤처럼 전 유저가 "14.3" 을 공유하는 구조가 아니다.
 * 그래서 패치 기준 분석은 한 슬롯 안에서만 유효하다.
 */
@Entity
@Table(name = "patch", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"slot_id", "season", "day"}),
        @UniqueConstraint(columnNames = {"slot_id", "seq"})
})
public class Patch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "patch_id")
    private Integer patchId;

    @Column(name = "slot_id", nullable = false)
    private Integer slotId;

    @Column(nullable = false)
    private Integer season;

    @Column(nullable = false)
    private Integer day;

    /** 커리어 안에서의 순번. 메타 감쇠의 "경과 패치 수" 를 여기서 센다(D15). */
    @Column(nullable = false)
    private Integer seq;

    protected Patch() {
    }

    public Patch(Integer slotId, Integer season, Integer day, Integer seq) {
        this.slotId = slotId;
        this.season = season;
        this.day = day;
        this.seq = seq;
    }

    public Integer getPatchId() {
        return patchId;
    }

    public Integer getSlotId() {
        return slotId;
    }

    public Integer getSeason() {
        return season;
    }

    public Integer getDay() {
        return day;
    }

    public Integer getSeq() {
        return seq;
    }
}
