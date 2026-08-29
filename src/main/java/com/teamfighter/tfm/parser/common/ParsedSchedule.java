package com.teamfighter.tfm.parser.common;

import java.util.Objects;

/**
 * 세이브 안의 매치 하나({@code MatchSchedule}). <b>기사 한 편의 단위</b>다 (D61).
 *
 * <p>경기(세트)가 아니라 <b>매치</b>다. 하나의 매치가 2~5세트를 갖는다
 * (실측 분포: 2세트 53 · 3세트 43 · 4세트 6 · 5세트 7).
 *
 * <p><b>{@code scheduleId} 를 조인 키로 쓰면 안 된다.</b> 대회마다 ID 공간이 따로라
 * 실측 190건이 114개 값에 겹쳐 있다. {@link #matchKey()} 를 쓴다.
 *
 * @param scheduleId    {@code MatchSchedule.ID}. 대회 안에서만 유일하다
 * @param competitionId {@code Competition.ID}. {@code scheduleId} 와 짝이면 유일해진다
 * @param competitionKey 대회 이름의 로컬라이제이션 키 (예: {@code league.amateur})
 * @param season        {@code Date.Season}
 * @param day           {@code Date.Day}
 * @param round         라운드 번호
 * @param blueTeamId    매치 기준 블루팀. <b>세트의 진영과 다를 수 있다</b>
 * @param redTeamId     매치 기준 레드팀
 * @param blueScore     매치 스코어(세트 승수). 세트 승수의 합과 일치한다 — 실측 109/109
 * @param redScore      같음
 * @param blueKill      매치 전체 킬. 세트 킬 합과 일치한다 — 실측 109/109
 * @param redKill       같음
 * @param needWin       승리에 필요한 세트 수 (Bo3 면 2)
 * @param progress      진행도. {@code 1.0} 이면 끝난 매치다
 * @param isEvent       이벤트전이면 {@code true}. <b>밴픽·챔피언·개인스탯이 없다</b> (D16)
 */
public record ParsedSchedule(
        Integer scheduleId,
        Integer competitionId,
        String competitionKey,
        Integer season,
        Integer day,
        Integer round,
        Integer blueTeamId,
        Integer redTeamId,
        int blueScore,
        int redScore,
        int blueKill,
        int redKill,
        int needWin,
        double progress,
        boolean isEvent) {

    /** 끝난 매치인가. 기사는 끝난 매치에 대해서만 쓴다. */
    public boolean isPlayed() {
        return progress >= 1.0;
    }

    /**
     * 경기({@code GameStat})와 잇는 키.
     *
     * <p>실측으로 190건 전부 고유하고, 경기 294건이 <b>하나도 빠짐없이</b> 붙는다.
     * {@code scheduleId} 단독으로는 122건이 모호해진다.
     */
    public MatchKey matchKey() {
        return MatchKey.of(season, day, blueTeamId, redTeamId);
    }

    /**
     * 매치를 가리키는 키 — 시즌 · 일 · <b>순서 없는</b> 두 팀.
     *
     * <p><b>순서가 없어야 하는 이유.</b> 세트마다 진영이 바뀐다. 실측 294세트 중
     * <b>122세트</b>가 매치 기준과 진영이 반대다. 순서를 지키면 그 122건이 안 붙는다.
     */
    public record MatchKey(Integer season, Integer day, Integer teamLow, Integer teamHigh) {

        public static MatchKey of(Integer season, Integer day, Integer teamA, Integer teamB) {
            boolean ordered = teamA == null
                    || (teamB != null && teamA <= teamB);
            return ordered
                    ? new MatchKey(season, day, teamA, teamB)
                    : new MatchKey(season, day, teamB, teamA);
        }

        public MatchKey {
            Objects.requireNonNull(season, "season");
            Objects.requireNonNull(day, "day");
        }
    }
}
