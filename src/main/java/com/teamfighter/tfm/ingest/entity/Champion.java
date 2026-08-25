package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 챔피언 40종. {@code code} 는 세이브 파일에 그대로 들어 있는 값이다.
 *
 * <p>진영 매칭을 인덱스가 아니라 이 {@code code} 로 한다(D20).
 */
@Entity
@Table(name = "champion")
public class Champion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "champion_id")
    private Integer championId;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "name_ko", nullable = false)
    private String nameKo;

    // Postgres 의 named ENUM 타입에 그대로 매핑한다. varchar 로 두면 스키마 검증에서 걸린다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "champion_category")
    private ChampionCategory category;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "is_playable", nullable = false)
    private boolean playable = true;

    protected Champion() {
    }

    public Champion(String code, String nameKo, ChampionCategory category) {
        this.code = code;
        this.nameKo = nameKo;
        this.category = category;
    }

    public Integer getChampionId() {
        return championId;
    }

    public String getCode() {
        return code;
    }

    public String getNameKo() {
        return nameKo;
    }

    public ChampionCategory getCategory() {
        return category;
    }

    public boolean isPlayable() {
        return playable;
    }
}
