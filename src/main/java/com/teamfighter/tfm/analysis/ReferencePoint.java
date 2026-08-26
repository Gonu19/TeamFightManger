package com.teamfighter.tfm.analysis;

import java.util.Map;

/**
 * 추정 시점 — "언제 기준으로 보는가" (D24).
 *
 * <p>화면의 패치 선택은 집계 대상을 자르는 <b>필터가 아니라 추정 시점의 선택</b>이다.
 * 패치 P 를 고르면 그 구간 경기만 세는 것이 아니라, 모든 경기를 P 로부터의 거리로 감쇠해
 * 본다. 자르면 표본이 0~38% 만 남기 때문이다 (D15).
 *
 * <p><b>감쇠가 재는 것은 방향이 아니라 거리다.</b> 선택한 패치보다 뒤에 치러진 경기도
 * 그 패치에 대한 정보를 담고 있다 — 인접한 패치의 메타는 서로 닮았다. 그래서 두 인자 모두
 * 절댓값으로 준다. 기본 시점(가장 최근 패치)에서는 모든 경기가 과거라 절댓값을 씌우나
 * 마나 같은 값이 되고, 과거 패치를 골랐을 때만 차이가 난다.
 *
 * <p>{@code GLOBAL} 스코프에는 패치 축이 없다 — 슬롯마다 패치 역사가 따로 만들어지기
 * 때문이다. 그래서 {@code GLOBAL} 은 슬롯별로 그 슬롯의 마지막 패치를 기준 시점으로 잡아
 * 각각 감쇠한 뒤 합산한다. 슬롯을 가로지르는 "패치 14.3" 같은 축은 존재하지 않는다.
 *
 * @param patchSeq              기준이 되는 패치의 커리어 내 순번
 * @param changeCountByChampion 기준 시점까지 각 챔피언이 패치로 바뀐 누적 횟수
 */
public record ReferencePoint(int patchSeq, Map<Integer, Integer> changeCountByChampion) {

    public ReferencePoint {
        if (patchSeq < 0) {
            throw new IllegalArgumentException("기준 패치 순번이 음수다: " + patchSeq);
        }
        changeCountByChampion = Map.copyOf(changeCountByChampion);
    }

    /**
     * 경기와 기준 시점 사이의 패치 거리.
     *
     * @param matchPatchSeq 경기에 적용 중이던 패치의 순번. 첫 패치 이전 경기면 {@code null}
     *                      이고 순번 0 으로 친다 — 커리어에서 가장 오래된 데이터다.
     */
    public int elapsedPatchesFrom(Integer matchPatchSeq) {
        int seq = matchPatchSeq == null ? 0 : matchPatchSeq;
        if (seq < 0) {
            throw new IllegalArgumentException("경기 패치 순번이 음수다: " + seq + ". 순번은 1부터다");
        }
        return Math.abs(patchSeq - seq);
    }

    /** 경기 시점 이후 기준 시점까지 그 챔피언이 바뀐 횟수(거리). */
    public int selfChangesFrom(int championId, int changeCountAtMatch) {
        if (changeCountAtMatch < 0) {
            throw new IllegalArgumentException(
                    "경기 시점 변경 횟수가 음수다: " + changeCountAtMatch);
        }
        int atReference = changeCountByChampion.getOrDefault(championId, 0);
        return Math.abs(atReference - changeCountAtMatch);
    }
}
