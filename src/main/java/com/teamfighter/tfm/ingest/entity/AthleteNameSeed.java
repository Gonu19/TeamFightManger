package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 선수 이름 풀 (D58).
 *
 * <p>게임 에셋({@code sharedassets0.assets})의 로컬라이제이션 JSON {@code "names"} 배열에서
 * 그대로 뽑았다. 사람이 옮겨 적은 {@link TeamNameSeed} 와 달리 <b>기계가 추출한 값</b>이라
 * 순서가 어긋날 여지가 없다.
 */
@Entity
@Table(name = "athlete_name_seed")
public class AthleteNameSeed {

    @Id
    @Column(name = "idx")
    private Integer idx;

    @Column(nullable = false)
    private String name;

    protected AthleteNameSeed() {
    }

    public Integer getIdx() {
        return idx;
    }

    public String getName() {
        return name;
    }
}
