package com.teamfighter.tfm.parser.model;

import java.util.List;

/**
 * GameStat — 공식 경기 한 <b>세트</b>.
 *
 * <p>다전제는 스케줄 1건에 세트 2~5개가 붙는다. 세트마다 밴픽이 따로 있으므로
 * 분석 단위는 스케줄이 아니라 세트다.
 *
 * @param winTeam TeamType. 0 = BLUE, 1 = RED
 * @param blueBan 팀당 3개가 규칙이지만 커리어 초반 데이터에는 2개인 경기가 있다
 */
public record ParsedGame(
        Integer id,
        Integer scheduleId,
        Integer season,
        Integer day,
        Integer setNo,
        Integer blueTeamId,
        Integer redTeamId,
        Integer blueScore,
        Integer redScore,
        Integer winTeam,
        List<String> blueBan,
        List<String> bluePick,
        List<String> redBan,
        List<String> redPick,
        List<ParsedStat> champStat,
        Boolean isOvertime,
        Boolean isSuddenDeath) {
}
