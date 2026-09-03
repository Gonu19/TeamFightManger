package com.teamfighter.tfm.analysis.scrim;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 듀오 쌍마다 <b>우리가 무엇을 아는가</b>.
 *
 * <p>DB 를 모른다. 그래서 DB 없이 시험할 수 있고, 추천 규칙({@link ScrimSuggester})이
 * 순수 함수로 남는다 — {@code analysis/} 의 다른 계산과 같은 이유다.
 *
 * <p>쌍의 <b>방향을 접는다.</b> 쌍 효과는 방향이 있지만("A 옆의 B" 와 "B 옆의 A" 가
 * 다른 행) "같이 뽑아 봤나" 라는 질문에는 방향이 없다.
 */
public final class PairCoverage {

    private final Set<Long> knownOfficial;
    private final Map<Long, Integer> scrimTimes;

    private PairCoverage(Set<Long> knownOfficial, Map<Long, Integer> scrimTimes) {
        this.knownOfficial = knownOfficial;
        this.scrimTimes = scrimTimes;
    }

    /**
     * @param official {@code [작은 챔피언 번호, 큰 번호, 0]} 행들. 공식전으로 아는 쌍
     * @param scrim    {@code [작은 번호, 큰 번호, 같이 나온 횟수]} 행들
     */
    public static PairCoverage of(List<long[]> official, List<long[]> scrim) {
        Set<Long> known = new HashSet<>();
        for (long[] row : official) {
            known.add(key((int) row[0], (int) row[1]));
        }
        Map<Long, Integer> times = new HashMap<>();
        for (long[] row : scrim) {
            times.merge(key((int) row[0], (int) row[1]), (int) row[2], Integer::sum);
        }
        return new PairCoverage(known, times);
    }

    public static PairCoverage empty() {
        return new PairCoverage(Set.of(), Map.of());
    }

    /**
     * 두 챔피언 번호를 순서 없는 키 하나로 접는다.
     *
     * <p>{@code long} 을 쓰는 이유는 {@code int} 두 개를 <b>겹치지 않게</b> 담기
     * 위해서다. 챔피언 번호가 40 남짓이라 지금은 {@code int} 로도 되지만, 그렇게 하면
     * 번호가 커졌을 때 다른 쌍이 같은 키가 되고 그건 조용히 틀린다.
     */
    private static long key(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    /** 공식전 표본이 충분해서 <b>말할 수 있는</b> 쌍인가. */
    public boolean knownFromOfficial(int a, int b) {
        return knownOfficial.contains(key(a, b));
    }

    /** 스크림에서 같은 편으로 같이 나온 횟수. 한 번도 없으면 0. */
    public int scrimTimes(int a, int b) {
        return scrimTimes.getOrDefault(key(a, b), 0);
    }

    /**
     * 어디서도 같이 안 나온 쌍인가.
     *
     * <p><b>"나쁜 조합" 이 아니라 "모르는 조합" 이다</b> (D62). 이 판정이 추천의 전부다.
     */
    public boolean unexplored(int a, int b) {
        return !knownFromOfficial(a, b) && scrimTimes(a, b) == 0;
    }
}
