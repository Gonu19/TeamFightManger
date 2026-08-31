package com.teamfighter.tfm.ingest.entity;

/**
 * Postgres {@code champion_category} ENUM. 게임의 {@code ChampionCategory} 와 대응한다.
 *
 * <p>세이브 파일에는 챔피언→역할군 매핑이 없다. 게임 에셋에 있고,
 * 40종뿐인 정적 데이터라 {@code seed/champions.csv} 로 넣는다.
 *
 * <h2>한글 이름을 여기에 두는 이유</h2>
 *
 * 화면이 {@code MELEE} 를 그대로 그리면 읽는 사람이 머릿속에서 번역해야 한다. 그 번역을
 * 템플릿마다 다시 적으면 <b>화면끼리 다른 말</b>을 하게 된다 — 한쪽은 "전사", 한쪽은
 * "근접" 이 되는 식이다. 이름은 값이 사는 곳에 붙여 둔다.
 */
public enum ChampionCategory {
    /** 전사 */
    MELEE("전사"),
    /** 원거리 */
    RANGER("원거리"),
    /** 마법사 */
    MAGICIAN("마법사"),
    /** 전투 보조 */
    PRIEST("전투 보조"),
    /** 암살자 */
    ASSASSIN("암살자");

    private final String label;

    ChampionCategory(String label) {
        this.label = label;
    }

    /** 화면에 쓰는 한글 이름. */
    public String label() {
        return label;
    }

    /**
     * ENUM 이름을 한글 이름으로. 모르는 이름은 <b>그대로 돌려준다</b>.
     *
     * <p>여기서 예외를 던지면 역할군 하나가 늘었을 때 티어 화면 전체가 500 이 된다.
     * 이름표 하나 때문에 통계를 통째로 못 보는 것은 손해가 이득보다 크다.
     */
    public static String labelOf(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name).label();
        } catch (IllegalArgumentException unknown) {
            return name;
        }
    }
}
