package com.teamfighter.tfm.ingest.entity;

/** Postgres {@code team_side} ENUM. 이름이 DB 값과 같아야 한다. */
public enum TeamSide {
    BLUE,
    RED;

    /** {@code GameStat.WinTeam} 은 TeamType enum 이다: 0 = BLUE, 1 = RED. */
    public static TeamSide ofWinTeam(Integer winTeam) {
        if (winTeam == null) {
            throw new IllegalArgumentException("WinTeam 이 없다. 승패를 알 수 없는 경기는 적재하지 않는다");
        }
        return switch (winTeam) {
            case 0 -> BLUE;
            case 1 -> RED;
            default -> throw new IllegalArgumentException("알 수 없는 TeamType: " + winTeam);
        };
    }
}
