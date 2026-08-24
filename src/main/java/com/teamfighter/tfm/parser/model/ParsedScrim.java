package com.teamfighter.tfm.parser.model;

import java.util.List;

/**
 * ScrimStat — 연습경기.
 *
 * <p>밴이 없고 Season/Day 도 없다. 시점은 워처가 관측 시각으로 붙인다(D8).
 * 인원이 4명이 아닐 수 있어서 카운터·시너지 집계는 {@code teamSize == 4} 만 쓴다.
 */
public record ParsedScrim(
        Integer id,
        Integer teamId,
        Integer blueScore,
        Integer redScore,
        List<ParsedStat> blueStat,
        List<ParsedStat> redStat,
        int teamSize) {
}
