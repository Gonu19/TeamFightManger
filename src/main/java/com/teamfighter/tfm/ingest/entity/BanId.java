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
 * {@code (match_id, side, ban_order)} 자연키.
 *
 * <p>{@code banOrder} 는 그 진영 안에서 몇 번째 밴인지다(1~3).
 * 두 팀의 교대 순서는 여기 없다 — 게임 규칙이라 {@code draft_step} 이 원본이다(D26).
 */
@Embeddable
public class BanId implements Serializable {

    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "team_side")
    private TeamSide side;

    @Column(name = "ban_order", nullable = false)
    private short banOrder;

    protected BanId() {
    }

    public BanId(Long matchId, TeamSide side, int banOrder) {
        this.matchId = matchId;
        this.side = side;
        this.banOrder = Narrow.toShort(banOrder, "ban_order");
    }

    public Long getMatchId() {
        return matchId;
    }

    public TeamSide getSide() {
        return side;
    }

    public short getBanOrder() {
        return banOrder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof BanId other
                && banOrder == other.banOrder
                && Objects.equals(matchId, other.matchId)
                && side == other.side;
    }

    @Override
    public int hashCode() {
        return Objects.hash(matchId, side, banOrder);
    }
}
