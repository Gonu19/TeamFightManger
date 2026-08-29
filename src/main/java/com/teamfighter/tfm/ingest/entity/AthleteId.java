package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@code (slot_id, game_athlete_id)} 자연키.
 *
 * <p>{@code gameAthleteId} 는 <b>슬롯 안에서만</b> 유효하다 — 커리어가 다르면 같은 번호가
 * 다른 선수다. 팀({@code game_team_id})과 같은 규칙이다.
 *
 * <p>대리키를 두지 않았다. {@code match_participant.athlete_id} 가 이미 이 번호를 들고 있어
 * 바로 이어지는데, 대리키를 두면 같은 이름의 컬럼 둘이 서로 다른 값을 뜻하게 된다.
 */
@Embeddable
public class AthleteId implements Serializable {

    @Column(name = "slot_id", nullable = false)
    private Integer slotId;

    @Column(name = "game_athlete_id", nullable = false)
    private Integer gameAthleteId;

    protected AthleteId() {
    }

    public AthleteId(Integer slotId, Integer gameAthleteId) {
        this.slotId = slotId;
        this.gameAthleteId = gameAthleteId;
    }

    public Integer getSlotId() {
        return slotId;
    }

    public Integer getGameAthleteId() {
        return gameAthleteId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AthleteId other)) {
            return false;
        }
        return Objects.equals(slotId, other.slotId)
                && Objects.equals(gameAthleteId, other.gameAthleteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slotId, gameAthleteId);
    }
}
