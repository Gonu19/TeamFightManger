package com.teamfighter.tfm.analysis.performance;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import com.teamfighter.tfm.analysis.shrink.Shrinkage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 챔피언 티어 계산 (D14 · D21).
 *
 * <p><b>카운터와 정반대의 규칙이 여기 있다.</b> 카운터는 챔피언 강도를 걷어내야 상성이
 * 남지만, 티어는 "이 챔피언이 센가" 를 묻는 것이므로 <b>기저 승률 자체가 답이다.</b>
 * Bradley-Terry 기대 승률을 빼면 모든 챔피언이 50% 근처로 눌려 티어표가 통째로
 * 무의미해지는데, 값은 여전히 0~1 사이라 그럴듯하다.
 *
 * <p>그래서 이 함수는 <b>상대 정보를 아예 받지 않는다.</b> 문서가 아니라 시그니처가
 * D14 를 강제한다 — 강도를 빼도록 바꾸려면 입력부터 바꿔야 한다.
 *
 * <p>축소 목표값이 0.5 인 근거는 산술이다. 4v4 한 경기에 승자 넷과 패자 넷이 있으므로
 * 전체 챔피언 출전의 승률은 <b>정확히</b> 50% 다(테스트가 이 등식을 못 박는다).
 * 표본이 없는 챔피언을 평균으로 두는 것이지, 임의로 고른 상수가 아니다.
 */
public final class PerformanceCalculator {

    /**
     * 축소 목표값. 전체 챔피언 출전의 승률이 정확히 50% 이므로 이 값이다.
     *
     * <p>D19 는 "표본이 적을 때 상수(50%)보다 경기력으로 예측한 승률로 수렴시키는 것이
     * 낫다" 고 했다. 맞는 말이지만 그 회귀식이 아직 정해지지 않았다 — 어떤 지표를 어떤
     * 가중으로 넣을지가 미결이다. 정해지면 이 상수 자리에 챔피언별 예측값이 들어간다.
     */
    private static final double PRIOR_TARGET = 0.5;

    private PerformanceCalculator() {
    }

    /**
     * @param tallies       챔피언별 출전 누적
     * @param bansByChampion 챔피언별 피밴 수. 밴을 한 번도 안 당했으면 없는 키다
     * @param matchCount    픽률의 분모 — 해당 스코프의 총 경기 수
     * @param banMatchCount 밴률의 분모 — 해당 스코프의 공식전 수 (D50)
     */
    public static List<PerformanceRow> calculate(
            Map<Integer, ChampionTally> tallies,
            Map<Integer, Integer> bansByChampion,
            int matchCount,
            int banMatchCount,
            AnalysisConfig config) {

        // 출전 기록이 없어도 밴만 당한 챔피언은 표에 있어야 한다.
        // "아무도 안 뽑는데 늘 밴당하는" 챔피언이야말로 티어표가 보여줘야 하는 것이다.
        Set<Integer> championIds = new HashSet<>(tallies.keySet());
        championIds.addAll(bansByChampion.keySet());

        List<PerformanceRow> rows = new ArrayList<>(championIds.size());
        for (int championId : championIds) {
            ChampionTally tally = tallies.getOrDefault(championId, ChampionTally.EMPTY);
            double adjusted = Shrinkage.overall(
                    tally.weightedWins(), tally.weightedGames(), PRIOR_TARGET, config.priorK0());

            rows.add(new PerformanceRow(
                    championId,
                    tally.games(),
                    tally.wins(),
                    bansByChampion.getOrDefault(championId, 0),
                    matchCount,
                    banMatchCount,
                    tally.weightedGames(),
                    tally.weightedWins(),
                    tally.ess(),
                    adjusted,
                    null));
        }
        return rows;
    }
}
