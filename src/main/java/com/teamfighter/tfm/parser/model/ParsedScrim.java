package com.teamfighter.tfm.parser.model;

import java.util.List;

/**
 * ScrimStat — 연습경기.
 *
 * <p>밴이 없고 Season/Day 도 없다. 시점은 워처가 관측 시각으로 붙인다(D8).
 *
 * <p>규칙은 4v4 고정이다. 구 데이터에 2v2 / 3v3 가 섞여 있어 파서는 그대로 읽지만,
 * <b>적재는 {@code teamSize == 4} 만 받는다</b>(D35). 조건이 다른 경기의 승률을 섞을 수 없다.
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
