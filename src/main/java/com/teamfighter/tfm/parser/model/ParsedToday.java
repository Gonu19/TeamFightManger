package com.teamfighter.tfm.parser.model;

/**
 * 현재 게임 내 날짜.
 *
 * <p>{@code TodayData.<Time>k__BackingField} 에서 읽는다.
 * {@code max(GameStat.Day)} 로 추정하면 최대 5일 뒤처진다 (D18).
 */
public record ParsedToday(Integer season, Integer day, Integer run) {
}
