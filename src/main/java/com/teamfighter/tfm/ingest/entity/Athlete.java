package com.teamfighter.tfm.ingest.entity;

import com.teamfighter.tfm.parser.common.ParsedAthlete;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * 선수 (D58).
 *
 * <p><b>여기 담긴 값은 전부 "지금" 스냅샷이다.</b> 세이브에 이력이 없다 — 나이·연봉·팬 수는
 * 마지막 적재 시점 값이지 과거 경기 시점 값이 아니다. 기사에 "그때 연봉" 으로 쓰면 틀린다.
 *
 * <p>그래서 팀 이름(D55·D56)과 <b>갱신 규칙이 반대</b>다. 팀 이름은 그 커리어의 기록이라
 * 덮지 않지만, 선수 속성은 시간에 따라 실제로 변하는 값이라 적재할 때마다 최신으로 덮는다.
 * 경기별 소속은 이 표가 아니라 경기 자체(진영과 팀)가 알려주므로 덮어써도 잃는 것이 없다.
 */
@Entity
@Table(name = "athlete")
public class Athlete {

    @EmbeddedId
    private AthleteId id;

    /** {@code athlete_name_seed.idx}. 이름이 참조 형식이 아니면 {@code null}. */
    @Column(name = "name_index")
    private Integer nameIndex;

    private String name;

    /** 현재 소속. 무소속이면 {@code null} — 실측 443명 중 228명이 그렇다. */
    @Column(name = "team_id")
    private Integer teamId;

    private Integer age;
    private Integer salary;
    private Integer fan;
    private Integer condition;
    private Integer potential;

    @Column(name = "playing_season")
    private Integer playingSeason;

    /** 선수의 <b>주특기</b>. 챔피언 분류가 아니다 ({@code savefile.md}). */
    private Short category;

    /** {@code AthleteBelong} 원값. 의미는 확인되지 않았다 — 실측 0이 440명, 1이 3명. */
    private Short belong;

    @Column(name = "career_set")
    private Integer careerSet;
    @Column(name = "career_kill")
    private Integer careerKill;
    @Column(name = "career_death")
    private Integer careerDeath;
    @Column(name = "career_assist")
    private Integer careerAssist;
    @Column(name = "career_deal")
    private Long careerDeal;
    @Column(name = "career_tank")
    private Long careerTank;
    @Column(name = "career_heal")
    private Long careerHeal;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected Athlete() {
    }

    public Athlete(AthleteId id) {
        this.id = id;
    }

    public AthleteId getId() {
        return id;
    }

    public Integer getNameIndex() {
        return nameIndex;
    }

    public String getName() {
        return name;
    }

    public Integer getTeamId() {
        return teamId;
    }

    public Integer getAge() {
        return age;
    }

    public Integer getSalary() {
        return salary;
    }

    public Integer getFan() {
        return fan;
    }

    public Integer getCareerSet() {
        return careerSet;
    }

    /**
     * 세이브의 스냅샷으로 갱신한다. <b>덮어쓰는 것이 맞다</b> — 클래스 문서 참고.
     *
     * @param teamId 우리 {@code team.team_id}. 무소속이면 {@code null}
     * @param name   해석된 이름. 풀에 없으면 {@code null} — 인덱스는 그대로 남는다
     */
    public void snapshot(ParsedAthlete parsed, Integer teamId, String name) {
        this.nameIndex = parsed.nameIndex();
        this.name = name;
        this.teamId = teamId;
        this.age = parsed.age();
        this.salary = parsed.salary();
        this.fan = parsed.fan();
        this.condition = parsed.condition();
        this.potential = parsed.potential();
        this.playingSeason = parsed.playingSeason();
        this.category = narrow(parsed.category());
        this.belong = narrow(parsed.belong());
        applyCareer(parsed.career());
        this.updatedAt = OffsetDateTime.now();
    }

    private void applyCareer(ParsedAthlete.Career career) {
        if (career == null) {
            return;                                  // 누적이 없는 선수. 나머지 값은 유효하다
        }
        this.careerSet = career.sets();
        this.careerKill = career.kill();
        this.careerDeath = career.death();
        this.careerAssist = career.assist();
        this.careerDeal = career.deal();
        this.careerTank = career.tank();
        this.careerHeal = career.heal();
    }

    private static Short narrow(Integer v) {
        return v == null ? null : v.shortValue();
    }
}
