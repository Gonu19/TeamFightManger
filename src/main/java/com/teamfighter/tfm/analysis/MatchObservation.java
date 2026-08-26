package com.teamfighter.tfm.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 집계의 입력 단위 — 한 경기를 승리 팀과 패배 팀으로만 본다.
 *
 * <p>진영(BLUE/RED)이 아니라 승패로 나눠 담는 이유는, 집계가 진영을 전혀 쓰지 않기
 * 때문이다. 카운터도 시너지도 "누가 이겼나" 와 "누가 같은 편이었나" 만 있으면 된다.
 * 진영을 들고 다니면 승패로 환산하는 코드가 집계 곳곳에 흩어지고, 그중 하나만 뒤집혀도
 * 결과는 여전히 그럴듯하다.
 *
 * <p>같은 챔피언이 한 경기에 두 번 나오지 못하게 여기서 막는다. 스키마의
 * {@code match_participant_unique_champ} 와 같은 불변조건이다. 이게 새면 그 챔피언은
 * 자기 자신과 카운터 관계를 맺고, 예외는 한참 떨어진 Bradley-Terry 에서 난다.
 */
public record MatchObservation(
        long matchId,
        /** 경기에 적용 중이던 패치의 커리어 내 순번. 첫 패치 이전이면 {@code null}. */
        Integer patchSeq,
        List<Participant> winners,
        List<Participant> losers) {

    /**
     * @param changeCountAtMatch 그 경기 시점까지 이 챔피언이 패치로 바뀐 누적 횟수.
     *                           {@code match_participant.change_count} 그대로다.
     *                           감쇠에 쓰는 값은 이것이 아니라 기준 시점과의 <b>차이</b>다 (D42).
     */
    public record Participant(int championId, int changeCountAtMatch) {
        public Participant {
            if (changeCountAtMatch < 0) {
                throw new IllegalArgumentException(
                        "챔피언 " + championId + " 의 경기 시점 변경 횟수가 음수다: " + changeCountAtMatch);
            }
        }
    }

    public MatchObservation {
        winners = List.copyOf(winners);
        losers = List.copyOf(losers);
        if (winners.isEmpty() || losers.isEmpty()) {
            throw new IllegalArgumentException(
                    "경기 " + matchId + " 의 한쪽 팀이 비어 있다(승 " + winners.size()
                            + " · 패 " + losers.size() + "). 관측이 하나도 안 나온다");
        }
        requireDistinctChampions(matchId, winners, losers);
    }

    private static void requireDistinctChampions(
            long matchId, List<Participant> winners, List<Participant> losers) {
        Set<Integer> seen = new HashSet<>();
        List<Participant> all = new ArrayList<>(winners);
        all.addAll(losers);
        for (Participant participant : all) {
            if (!seen.add(participant.championId())) {
                throw new IllegalArgumentException(
                        "경기 " + matchId + " 에 챔피언 " + participant.championId()
                                + " 가 두 번 나온다. 그대로 두면 그 챔피언이 자기 자신과"
                                + " 카운터 관계를 맺는다(match_participant_unique_champ 와 같은 규칙)");
            }
        }
    }
}
