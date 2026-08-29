package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import com.teamfighter.tfm.common.Narrow;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

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
public class MatchParticipant implements Persistable<ParticipantId> {

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

    @Override
    public ParticipantId getId() {
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

    /** {@code Athlete.ID}. 슬롯 안에서만 유효하고 {@code athlete} 표와 이어진다 (D58). */
    public Integer getAthleteId() {
        return athleteId;
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
