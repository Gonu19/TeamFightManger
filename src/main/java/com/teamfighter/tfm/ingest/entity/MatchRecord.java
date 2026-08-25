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

import java.time.OffsetDateTime;

/**
 * 경기 한 <b>세트</b>. 다전제는 스케줄 1건에 세트 여러 개가 붙으므로 분석 단위는 세트다.
 *
 * <p>스크림에는 Season/Day 가 없다. 워처가 발견 시점의 게임 내 날짜를
 * {@code observedSeason}/{@code observedDay} 에 채운다(D8). 그 값으로 패치를 배정한다 —
 * day 만으로는 커리어가 시즌을 넘길 때 배정이 모호해진다.
 */
@Entity
@Table(name = "match_record")
public class MatchRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "match_id")
    private Long matchId;

    @Column(name = "slot_id", nullable = false)
    private Integer slotId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "match_type", nullable = false, columnDefinition = "match_type")
    private MatchType matchType;

    /** {@code GameStat.ID} / {@code ScrimStat.ID}. 슬롯 안에서만 유일하다. */
    @Column(name = "source_game_id", nullable = false)
    private Integer sourceGameId;

    private Integer season;
    private Integer day;

    @Column(name = "set_no")
    private Integer setNo;

    @Column(name = "schedule_id")
    private Integer scheduleId;

    @Column(name = "patch_id")
    private Integer patchId;

    @Column(name = "observed_season")
    private Integer observedSeason;

    @Column(name = "observed_day")
    private Integer observedDay;

    /** 워처가 실제로 발견한 벽시계 시각. 게임 내 날짜와 다른 값이다. */
    @Column(name = "observed_at")
    private OffsetDateTime observedAt;

    /** 공식전은 항상 4. 스크림은 2·3·4 가 섞인다 — 집계는 4 만 쓴다. */
    @Column(name = "team_size", nullable = false)
    private short teamSize = 4;

    @Column(name = "blue_team_id")
    private Integer blueTeamId;

    @Column(name = "red_team_id")
    private Integer redTeamId;

    @Column(name = "blue_score")
    private Integer blueScore;

    @Column(name = "red_score")
    private Integer redScore;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "winner_side", nullable = false, columnDefinition = "team_side")
    private TeamSide winnerSide;

    @Column(name = "is_overtime", nullable = false)
    private boolean overtime;

    @Column(name = "is_sudden_death", nullable = false)
    private boolean suddenDeath;

    protected MatchRecord() {
    }

    public MatchRecord(Integer slotId, MatchType matchType, Integer sourceGameId, TeamSide winnerSide) {
        this.slotId = slotId;
        this.matchType = matchType;
        this.sourceGameId = sourceGameId;
        this.winnerSide = winnerSide;
    }

    public Long getMatchId() {
        return matchId;
    }

    public Integer getSlotId() {
        return slotId;
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public Integer getSourceGameId() {
        return sourceGameId;
    }

    public TeamSide getWinnerSide() {
        return winnerSide;
    }

    public short getTeamSize() {
        return teamSize;
    }

    public Integer getPatchId() {
        return patchId;
    }

    public Integer getObservedSeason() {
        return observedSeason;
    }

    public Integer getObservedDay() {
        return observedDay;
    }

    public OffsetDateTime getObservedAt() {
        return observedAt;
    }

    public Integer getSeason() {
        return season;
    }

    public Integer getDay() {
        return day;
    }

    public void setSchedule(Integer season, Integer day, Integer setNo, Integer scheduleId) {
        this.season = season;
        this.day = day;
        this.setNo = setNo;
        this.scheduleId = scheduleId;
    }

    public void setTeams(Integer blueTeamId, Integer redTeamId, Integer blueScore, Integer redScore) {
        this.blueTeamId = blueTeamId;
        this.redTeamId = redTeamId;
        this.blueScore = blueScore;
        this.redScore = redScore;
    }

    public void setFlags(boolean overtime, boolean suddenDeath) {
        this.overtime = overtime;
        this.suddenDeath = suddenDeath;
    }

    public void setTeamSize(int teamSize) {
        this.teamSize = (short) teamSize;
    }

    /**
     * 워처가 이 경기를 발견한 시점을 기록한다.
     *
     * <p>{@code season}/{@code day} 는 <b>게임 내 날짜</b>로 패치 배정에 쓰고(D8),
     * {@code at} 은 <b>벽시계 시각</b>으로 저장 주기 측정(D17)에 쓴다. 둘은 다른 값이다.
     */
    public void markObserved(Integer season, Integer day, OffsetDateTime at) {
        this.observedSeason = season;
        this.observedDay = day;
        this.observedAt = at;
    }

    public void assignPatch(Integer patchId) {
        this.patchId = patchId;
    }
}
