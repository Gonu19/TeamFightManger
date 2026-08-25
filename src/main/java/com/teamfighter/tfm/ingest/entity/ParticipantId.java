package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import com.teamfighter.tfm.common.Narrow;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@code (match_id, side, pick_order)} 자연키.
 *
 * <p>{@code pickOrder} 는 <b>드래프트 순서가 아니라 배열 슬롯 번호</b>다.
 * {@code BluePick[4]} 의 인덱스가 실제 픽 순서라는 증거가 없다(D25).
 * 시뮬레이터의 스텝 순서는 {@code draft_step} 에서 온다.
 */
@Embeddable
public class ParticipantId implements Serializable {

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "team_side")
    private TeamSide side;

    @Column(name = "pick_order", nullable = false)
    private short pickOrder;

    protected ParticipantId() {
    }

    public ParticipantId(Long matchId, TeamSide side, int pickOrder) {
        this.matchId = matchId;
        this.side = side;
        this.pickOrder = Narrow.toShort(pickOrder, "pick_order");
    }

    public Long getMatchId() {
        return matchId;
    }

    public TeamSide getSide() {
        return side;
    }

    public short getPickOrder() {
        return pickOrder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof ParticipantId other
                && pickOrder == other.pickOrder
                && Objects.equals(matchId, other.matchId)
                && side == other.side;
    }

    @Override
    public int hashCode() {
        return Objects.hash(matchId, side, pickOrder);
    }
}
