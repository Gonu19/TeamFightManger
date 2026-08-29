package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 일정 전체를 놓고 매치 하나의 맥락({@link NotabilityContext})을 만든다.
 *
 * <p><b>DB 를 쓰지 않는다.</b> 필요한 것이 전부 세이브 안에 있기 때문이다 —
 * 게임은 지난 시즌의 <i>경기</i> 기록은 버리지만(D6) <b>일정은 남긴다.</b>
 * 실측: 세이브 하나에 2021~2025 시즌 매치 190건이 있다.
 *
 * <p><b>가장 중요한 성질은 미래를 보지 않는 것이다.</b> 순위도 승률도 그 매치
 * <i>이전</i> 경기만으로 센다. 시즌이 끝난 뒤의 순위로 기사를 쓰면
 * "3위 팀이 2위 팀을 잡았다" 가 경기 후에야 정해진 순위가 된다.
 *
 * <p><b>지난 시즌은 순위를 못 낸다.</b> 세이브가 남기는 것은 브래킷뿐이다 —
 * 실측으로 지난 시즌마다 19건(플레이오프 4 + 월즈 15)이고 정규 리그 일정은 없다.
 * 그래서 순위는 현재 시즌 리그에서만 나온다. 대신 그 브래킷이 <b>라이벌의 근거</b>가 된다.
 */
public final class SeasonBook {

    /**
     * 승률을 말하려면 필요한 최소 매치 수.
     *
     * <p><b>측정된 값이 아니다.</b> 1승 0패를 100% 라고 부르지 않으려는 하한일 뿐이다.
     * 10팀 리그가 팀당 18경기쯤 하므로 시즌 3분의 1 지점에서 열린다.
     * 업셋 판정이 이상하면 여기부터 본다.
     */
    private static final int MIN_MATCHES_FOR_STRENGTH = 5;

    private final List<ParsedSchedule> schedules;

    public SeasonBook(List<ParsedSchedule> schedules) {
        this.schedules = List.copyOf(Objects.requireNonNull(schedules, "schedules"));
    }

    /**
     * 그 매치 시점의 맥락. 모르는 것은 {@code null} 로 남긴다 — 없는 근거로
     * 기사를 키우지 않는다.
     *
     * @param playerTeamId 플레이어 팀 번호. 모르면 {@code null}
     */
    public NotabilityContext contextFor(ParsedSchedule match, Integer playerTeamId) {
        Objects.requireNonNull(match, "match");

        Map<Integer, Integer> winsBefore = new java.util.LinkedHashMap<>(winsBefore(match));
        Map<Integer, Integer> playedBefore = playedBefore(match);

        // 아직 한 번도 이기지 못한 팀은 앞선 경기 기록에 안 잡힌다. 그렇다고 순위표에서
        // 빼면 "꼴찌가 1위를 잡았다" 를 영영 말할 수 없다. 지금 뛰고 있으니 이 대회 소속인
        // 것은 확실하므로 0승으로 넣는다. 단, 앞선 결과가 하나도 없으면 넣지 않는다 —
        // 0승끼리의 순위는 근거가 아니다.
        if (!winsBefore.isEmpty()) {
            winsBefore.putIfAbsent(match.blueTeamId(), 0);
            winsBefore.putIfAbsent(match.redTeamId(), 0);
        }

        // 브래킷은 순위표가 없다. 승수로 줄을 세우면 "1위 대 1위"(둘 다 1라운드 통과)
        // 같은 말이 나온다 — 사실은 맞지만 리그 순위와 다른 뜻이라 기사에서 거짓이 된다.
        if (isBracket(match)) {
            winsBefore = Map.of();
        }

        Integer size = winsBefore.isEmpty() ? null : winsBefore.size();
        Integer blueRank = rank(winsBefore, match.blueTeamId());
        Integer redRank = rank(winsBefore, match.redTeamId());

        return new NotabilityContext(
                playerTeamId,
                blueRank,
                redRank,
                size,
                winProbability(winsBefore, playedBefore, match),
                metInPastBracket(match));
    }

    /** 같은 시즌·같은 대회에서 이 매치보다 <b>먼저</b> 치러진 경기의 팀별 승수. */
    private Map<Integer, Integer> winsBefore(ParsedSchedule match) {
        Map<Integer, Integer> wins = new java.util.LinkedHashMap<>();
        for (ParsedSchedule other : earlierInSameCompetition(match)) {
            Integer winner = other.winnerTeamId();
            for (Integer team : List.of(other.blueTeamId(), other.redTeamId())) {
                wins.putIfAbsent(team, 0);
            }
            if (winner != null) {
                wins.merge(winner, 1, Integer::sum);
            }
        }
        return wins;
    }

    private Map<Integer, Integer> playedBefore(ParsedSchedule match) {
        Map<Integer, Integer> played = new java.util.HashMap<>();
        for (ParsedSchedule other : earlierInSameCompetition(match)) {
            played.merge(other.blueTeamId(), 1, Integer::sum);
            played.merge(other.redTeamId(), 1, Integer::sum);
        }
        return played;
    }

    /**
     * 같은 대회에서 더 먼저 치러진 매치들.
     *
     * <p>대회가 없으면(이벤트전) 비어 있다 — 이벤트전에는 순위가 없다.
     * 같은 날짜의 매치는 <b>넣지 않는다</b>. 같은 날 치러진 경기의 결과를 쓰면
     * 그 매치가 먼저였는지 뒤였는지 알 수 없는데도 안다고 말하는 것이 된다.
     */
    private List<ParsedSchedule> earlierInSameCompetition(ParsedSchedule match) {
        if (match.competitionId() == null) {
            return List.of();
        }
        List<ParsedSchedule> out = new ArrayList<>();
        for (ParsedSchedule other : schedules) {
            if (other == match || !other.isPlayed()) {
                continue;
            }
            if (!Objects.equals(other.competitionId(), match.competitionId())
                    || !Objects.equals(other.season(), match.season())) {
                continue;
            }
            if (other.day() != null && match.day() != null && other.day() < match.day()) {
                out.add(other);
            }
        }
        return out;
    }

    /**
     * 이 대회가 토너먼트 브래킷인가.
     *
     * <p><b>매치 수가 팀 수보다 정확히 하나 적으면 단판 토너먼트다.</b> 16팀이면 15매치,
     * 5팀이면 4매치. 실측으로 월즈와 지난 시즌 플레이오프가 전부 이 꼴이고,
     * 현재 시즌 리그(10팀 94매치)는 아니다.
     *
     * <p>브래킷에서 승수는 <b>몇 라운드까지 올라왔나</b>이지 순위가 아니다.
     * 그것을 순위라고 부르면 준결승이 "1위 대 1위" 가 된다.
     */
    private boolean isBracket(ParsedSchedule match) {
        if (match.competitionId() == null) {
            return false;
        }
        List<ParsedSchedule> all = schedules.stream()
                .filter(o -> Objects.equals(o.competitionId(), match.competitionId())
                        && Objects.equals(o.season(), match.season()))
                .toList();
        Set<Integer> teams = new HashSet<>();
        all.forEach(o -> {
            teams.add(o.blueTeamId());
            teams.add(o.redTeamId());
        });
        return !teams.isEmpty() && all.size() == teams.size() - 1;
    }

    /** 승수 내림차순 순위. 같은 승수면 같은 등수다. 그 팀이 표에 없으면 {@code null}. */
    private static Integer rank(Map<Integer, Integer> wins, Integer teamId) {
        Integer mine = wins.get(teamId);
        if (mine == null) {
            return null;
        }
        long ahead = wins.values().stream().filter(w -> w > mine).count();
        return (int) ahead + 1;
    }

    /**
     * 블루팀의 사전 승률. D14 의 기대 승률 식을 그대로 쓴다 —
     * 두 팀의 기저 강도만으로 예측되는 승률이다.
     *
     * <p>표본이 모자란 팀이 하나라도 있으면 {@code null} 이다.
     */
    private static Double winProbability(Map<Integer, Integer> wins,
                                         Map<Integer, Integer> played,
                                         ParsedSchedule match) {
        Double blue = rate(wins, played, match.blueTeamId());
        Double red = rate(wins, played, match.redTeamId());
        if (blue == null || red == null) {
            return null;
        }
        double numerator = blue * (1 - red);
        double denominator = numerator + red * (1 - blue);
        if (denominator <= 0) {
            return 0.5;                       // 둘 다 전승이거나 둘 다 전패면 가릴 수 없다
        }
        return numerator / denominator;
    }

    private static Double rate(Map<Integer, Integer> wins, Map<Integer, Integer> played,
                               Integer teamId) {
        int n = played.getOrDefault(teamId, 0);
        if (n < MIN_MATCHES_FOR_STRENGTH) {
            return null;
        }
        return (double) wins.getOrDefault(teamId, 0) / n;
    }

    /**
     * 지난 시즌 브래킷에서 만난 적이 있나.
     *
     * <p><b>왜 이것이 라이벌인가.</b> 세이브는 지난 시즌의 정규 리그 일정을 버리고
     * <b>브래킷만</b> 남긴다(실측: 시즌마다 플레이오프 4 + 월즈 15). 즉 지난 시즌
     * 기록에 남아 있다는 것 자체가 <b>떨어뜨리거나 떨어진 사이</b>라는 뜻이다.
     *
     * <p>같은 시즌에 여러 번 만난 것은 라이벌이 아니다 — 리그 일정이면 당연한 일이다.
     * 맞대결 횟수로 재려 해도 실측 최대가 4회라 표본이 서지 않는다.
     */
    private boolean metInPastBracket(ParsedSchedule match) {
        if (match.blueTeamId() == null || match.redTeamId() == null) {
            return false;
        }
        Set<Integer> pair = new HashSet<>(List.of(match.blueTeamId(), match.redTeamId()));
        return schedules.stream()
                .filter(ParsedSchedule::isPlayed)
                .filter(o -> o.season() != null && match.season() != null
                        && o.season() < match.season())
                .anyMatch(o -> pair.equals(Set.of(
                        o.blueTeamId() == null ? -1 : o.blueTeamId(),
                        o.redTeamId() == null ? -2 : o.redTeamId())));
    }

    /** 이 책이 아는 매치들. 디버깅과 화면에서 쓴다. */
    public List<ParsedSchedule> schedules() {
        return schedules;
    }

    /** 시즌별 매치 수. "지난 시즌은 브래킷만 남는다" 를 눈으로 확인할 때 쓴다. */
    public Map<Integer, Long> matchesBySeason() {
        return schedules.stream()
                .filter(s -> s.season() != null)
                .collect(Collectors.groupingBy(ParsedSchedule::season, Collectors.counting()));
    }
}
