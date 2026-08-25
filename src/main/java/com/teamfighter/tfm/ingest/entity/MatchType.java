package com.teamfighter.tfm.ingest.entity;

/** Postgres {@code match_type} ENUM. */
public enum MatchType {
    /** 리그·대회. 밴이 있고 Season/Day 가 있다. */
    OFFICIAL,
    /** 연습경기. 밴이 없고 시점 정보도 없다 — 워처가 관측 시점을 붙인다. */
    SCRIM
}
