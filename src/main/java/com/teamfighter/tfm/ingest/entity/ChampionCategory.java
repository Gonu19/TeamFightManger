package com.teamfighter.tfm.ingest.entity;

/**
 * Postgres {@code champion_category} ENUM. 게임의 {@code ChampionCategory} 와 대응한다.
 *
 * <p>세이브 파일에는 챔피언→역할군 매핑이 없다. 게임 에셋에 있고,
 * 40종뿐인 정적 데이터라 {@code seed/champions.csv} 로 넣는다.
 */
public enum ChampionCategory {
    /** 전사 */
    MELEE,
    /** 원거리 */
    RANGER,
    /** 마법사 */
    MAGICIAN,
    /** 전투 보조 */
    PRIEST,
    /** 암살자 */
    ASSASSIN
}
