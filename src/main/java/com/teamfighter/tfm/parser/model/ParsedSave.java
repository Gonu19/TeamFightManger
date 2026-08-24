package com.teamfighter.tfm.parser.model;

import java.util.List;

/** 세이브 파일 하나에서 뽑아낸 전부. 골든 파일의 최상위 구조와 같다. */
public record ParsedSave(
        ParsedToday today,
        List<ParsedPatch> patches,
        List<ParsedGame> gameStats,
        List<ParsedScrim> scrimStats) {
}
