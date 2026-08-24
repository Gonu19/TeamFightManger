package com.teamfighter.tfm.parser.model;

import java.util.List;

/** PatchNews — 패치 한 건. 사용자 입력이 필요 없다. */
public record ParsedPatch(
        Integer season,
        Integer day,
        Integer run,
        List<String> newChamps,
        List<Change> changes) {

    /** PatchData — 챔피언 하나의 스탯 변화량. */
    public record Change(
            String name,
            Integer attack,
            Integer magic,
            Integer defence,
            Integer maxHp,
            Integer attackSpeed,
            Integer skillCool,
            Integer moveSpeed) {
    }
}
