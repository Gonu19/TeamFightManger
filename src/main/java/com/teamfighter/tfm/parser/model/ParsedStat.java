package com.teamfighter.tfm.parser.model;

/**
 * MatchStat / AthleteMatchStat 하나.
 *
 * <p>{@code athleteId} 는 공식 경기에만 있다 — 스크림의 MatchStat 에는 선수 정보가 없다.
 */
public record ParsedStat(
        String champion,
        Integer kill,
        Integer death,
        Integer assist,
        Integer dealing,
        Integer tanking,
        Integer healing,
        Integer liveDuration,
        Integer athleteId) {
}
