package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import com.teamfighter.tfm.common.Narrow;
import jakarta.persistence.Table;

/**
 * 경기 참가자 한 명.
 *
 * <p>진영은 <b>챔피언 이름으로 매칭</b>해서 정한다. {@code GameStat.ChampStat} 의 인덱스 순서는
 * 경기의 20.5% 에서 {@code BluePick+RedPick} 과 어긋난다(D20, 실측 640/805).
 * 한 경기에 같은 챔피언이 두 번 나올 수 없어서 이름 매칭은 안전하다.
 *
 * <p>경기력 지표(딜·탱·힐·KDA)는 티어 점수에 넣지 않는다. 축소 목표값과 조기 지표로만 쓴다(D19).
 */
@Entity
@Table(name = "match_participant")
public class MatchParticipant {

    @EmbeddedId
    private ParticipantId id;

    @Column(name = "champion_id", nullable = false)
    private Integer championId;

    /** 이 경기 시점까지 해당 챔피언이 패치로 바뀐 횟수. 감쇠 가중치의 지수로 쓴다(D15). */
    @Column(name = "change_count", nullable = false)
    private short changeCount;

    @Column(name = "athlete_id")
    private Integer athleteId;

    private Short kills;
    private Short deaths;
    private Short assists;
    private Integer dealing;
    private Integer tanking;
    private Integer healing;

    @Column(name = "live_duration")
    private Integer liveDuration;

    protected MatchParticipant() {
    }

    public MatchParticipant(ParticipantId id, Integer championId) {
        this.id = id;
        this.championId = championId;
    }

    public ParticipantId getId() {
        return id;
    }

    public Integer getChampionId() {
        return championId;
    }

    public short getChangeCount() {
        return changeCount;
    }

    public void setChangeCount(int changeCount) {
        this.changeCount = Narrow.toShort(changeCount, "change_count");
    }

    public void setStats(Integer athleteId, Integer kills, Integer deaths, Integer assists,
                         Integer dealing, Integer tanking, Integer healing, Integer liveDuration) {
        this.athleteId = athleteId;
        this.kills = Narrow.toShort(kills, "kills");
        this.deaths = Narrow.toShort(deaths, "deaths");
        this.assists = Narrow.toShort(assists, "assists");
        this.dealing = dealing;
        this.tanking = tanking;
        this.healing = healing;
        this.liveDuration = liveDuration;
    }
}
