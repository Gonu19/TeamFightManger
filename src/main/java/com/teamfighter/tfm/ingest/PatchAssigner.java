package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.Patch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 경기에 패치를 배정하고, 그 시점까지의 챔피언 변경 횟수를 센다.
 *
 * <p>패치는 커리어마다 고유하게 생성되므로 이 계산은 <b>한 슬롯 안에서만</b> 유효하다.
 *
 * <p>{@code change_count} 는 감쇠 가중치 {@code 0.5^(변경횟수/2)} 의 지수가 된다 (D15).
 * 패치로 구간을 자르지 않고 연속적으로 가중하기 위한 값이다 — 자르면 표본이 죽는다.
 */
public final class PatchAssigner {

    /** 커리어 안의 패치를 순번대로. 배정은 "경기 시점 이전의 마지막 패치". */
    private final List<Patch> patches;

    /** 챔피언별로, 그 챔피언을 건드린 패치들의 순번. 오름차순. */
    private final Map<Integer, List<Integer>> changeSeqByChampion;

    public PatchAssigner(List<Patch> patches, Map<Integer, List<Integer>> changeSeqByChampion) {
        this.patches = new ArrayList<>(patches);
        this.patches.sort((a, b) -> Integer.compare(a.getSeq(), b.getSeq()));
        this.changeSeqByChampion = new HashMap<>(changeSeqByChampion);
    }

    /**
     * 그 시점에 적용 중인 패치.
     *
     * <p>경기 시점보다 <b>늦은</b> 패치는 그 경기에 영향을 주지 않았다.
     * 첫 패치보다 이른 경기는 배정할 패치가 없다({@code null}) — 커리어 시작 직후가 그렇다.
     */
    public Patch patchAt(int season, int day) {
        Patch found = null;
        for (Patch p : patches) {
            if (isAtOrBefore(p, season, day)) {
                found = p;
            } else {
                break;
            }
        }
        return found;
    }

    /**
     * 그 시점까지 해당 챔피언이 패치로 바뀐 횟수.
     *
     * <p>경기와 <b>같은 날</b> 나온 패치는 포함한다 — 패치가 먼저 적용되고 그날 경기가 치러진다.
     */
    public int changeCountAt(Integer championId, int season, int day) {
        Patch at = patchAt(season, day);
        if (at == null) {
            return 0;
        }
        List<Integer> seqs = changeSeqByChampion.get(championId);
        if (seqs == null) {
            return 0;
        }
        int count = 0;
        for (int seq : seqs) {
            if (seq <= at.getSeq()) {
                count++;
            }
        }
        return count;
    }

    private static boolean isAtOrBefore(Patch p, int season, int day) {
        if (p.getSeason() != season) {
            return p.getSeason() < season;
        }
        return p.getDay() <= day;
    }
}
