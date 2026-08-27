package com.teamfighter.tfm.analysis;

/**
 * 집계 스코프. Postgres 의 {@code agg_scope} ENUM 과 이름이 같아야 한다.
 *
 * <p><b>{@code GLOBAL} 에는 패치 축이 없다.</b> 패치는 커리어마다 고유하게 생성되므로
 * 슬롯을 가로지르는 "패치 14.3" 같은 축이 존재하지 않는다 (D24). 그래서 {@code GLOBAL}
 * 행은 {@code slot_id} 도 {@code patch_id} 도 NULL 이다 — 그 NULL 을 유일키에 포함시키려고
 * 스키마가 {@code UNIQUE NULLS NOT DISTINCT} 를 쓴다.
 */
public enum AggScope {
    /** 슬롯 합산. 슬롯별로 각자의 기준 시점으로 감쇠한 뒤 더한다 (D45). */
    GLOBAL,
    /** 한 커리어 안. 패치 선택이 가능한 유일한 스코프다 (D24). */
    CAREER
}
