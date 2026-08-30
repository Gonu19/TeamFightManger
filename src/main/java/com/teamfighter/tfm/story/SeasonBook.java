package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;

import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * 프롬프트에 넘길 맥락 태그의 최대 개수.
     *
     * <p>둘이다. 셋을 넘기면 기사가 태그를 차례로 소개하기 시작한다 — 세트를 나열하던
     * 실패와 같은 모양이고, 그때 첫 문장의 힘이 오히려 죽는다.
     */
    private static final int MAX_TAGS = 2;

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
    /**
     * 이 매치의 <b>맥락 태그</b>. 기사 첫 문장이 밋밋해지지 않게 하는 장치다.
     *
     * <h2>왜 순위표를 통째로 안 주는가</h2>
     *
     * 순위표를 프롬프트에 다 넣으면 토큰만 쓰고, 모델이 "아 이게 1위 결정전이구나" 를
     * 스스로 알아채지 못한다. 그래서 <b>판정을 우리가 하고 결론만 준다.</b>
     *
     * <h2>태그는 사실이지 형용이 아니다</h2>
     *
     * "꼴찌들의 단두대 매치" 같은 말은 여기서 만들지 않는다. 그건 해석이고, 넘기면
     * 기사가 그 표현을 그대로 베껴 쓰면서 우리가 지은 말이 사실처럼 굳는다 —
     * D66 ② 가 주목도의 <b>이유</b>를 프롬프트에서 뺀 것과 같은 이유다.
     * 여기서는 "승자가 단독 1위", "블루 3연패 중" 처럼 <b>계산된 사실</b>만 준다.
     * 그 사실을 받아 "단두대 매치" 라고 부르는 것은 창작층의 몫이다.
     *
     * <h2>최대 두 개만 준다</h2>
     *
     * 다 붙이면 기사가 태그를 나열한다 — 세트를 나열하던 것과 같은 실패다.
     * 우선순위는 아래 순서다: 1위 다툼 → 연패/연승 → 라이벌 → 최하위 다툼.
     *
     * <p>브래킷(토너먼트)에서는 순위 태그를 만들지 않는다. 거기서 승수는 순위가 아니라
     * 몇 라운드까지 올라왔나이기 때문이다 — {@link #isBracket} 이 적어 둔 그대로다.
     */
    public List<String> tagsFor(ParsedSchedule match, NameBook names) {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(names, "names");

        List<String> tags = new ArrayList<>();
        Map<Integer, Integer> wins = winsBefore(match);                         // 1. 이 매치 <b>전</b>까지의 승수
        boolean bracket = isBracket(match);

        String blue = label(match.blueTeamId(), names);
        String red = label(match.redTeamId(), names);

        if (!wins.isEmpty() && !bracket) {                                      // 2. 순위 다툼 — 리그일 때만
            Integer blueRank = rank(wins, match.blueTeamId());
            Integer redRank = rank(wins, match.redTeamId());
            int teams = wins.size();

            if (blueRank != null && redRank != null) {
                if (blueRank == 1 && redRank == 1) {                            // 2-1. 공동 1위끼리 — 승자가 단독 1위
                    tags.add("공동 1위끼리 맞붙는다. 이기는 쪽이 단독 1위가 된다"
                            + " (" + blue + " " + wins.get(match.blueTeamId()) + "승 · "
                            + red + " " + wins.get(match.redTeamId()) + "승)");
                } else if (blueRank <= 2 && redRank <= 2) {                     // 2-2. 1위 대 2위
                    tags.add("선두 다툼이다. " + blue + " " + blueRank + "위 · "
                            + red + " " + redRank + "위");
                } else if (blueRank == teams && redRank == teams) {             // 2-3. 공동 최하위
                    tags.add("공동 최하위끼리 맞붙는다 (" + teams + "팀 중)");
                } else if (blueRank >= teams - 1 && redRank >= teams - 1) {     // 2-4. 하위권 맞대결
                    tags.add("하위권 맞대결이다. " + blue + " " + blueRank + "위 · "
                            + red + " " + redRank + "위 (" + teams + "팀 중)");
                }
            }
        }

        for (Integer team : List.of(match.blueTeamId(), match.redTeamId())) {   // 3. 연패·연승
            int streak = streakBefore(match, team);
            String who = label(team, names);
            if (streak <= -3) {
                tags.add(who + " " + (-streak) + "연패 중이다");
            } else if (streak >= 3) {
                tags.add(who + " " + streak + "연승 중이다");
            }
        }

        if (metInPastBracket(match)) {                                          // 4. 라이벌 (이미 있던 판정을 재사용)
            tags.add("두 팀은 앞선 토너먼트에서도 만났다");
        }

        return tags.size() <= MAX_TAGS ? List.copyOf(tags) : List.copyOf(tags.subList(0, MAX_TAGS));
    }

    /**
     * 이 매치 <b>직전</b>까지의 연승·연패. 양수면 연승, 음수면 연패, 0이면 둘 다 아니다.
     *
     * <p><b>대회 안에서만 센다.</b> 시즌 전체로 세면 다른 대회의 경기가 섞여서
     * "3연패 중" 이 어느 기준인지 기사도 독자도 알 수 없게 된다. 순위를 대회 단위로
     * 보는 것과 같은 기준이다.
     *
     * <p>무승부는 연속을 <b>끊는다</b>. 이기지도 지지도 않았으므로 연승도 연패도 아니다.
     */
    private int streakBefore(ParsedSchedule match, Integer teamId) {
        List<ParsedSchedule> earlier = new ArrayList<>(earlierInSameCompetition(match));
        earlier.removeIf(other -> !Objects.equals(other.blueTeamId(), teamId)   // 1. 그 팀의 경기만
                && !Objects.equals(other.redTeamId(), teamId));
        earlier.sort(Comparator.comparing(                                      // 2. 최근 경기가 앞에 오게
                (ParsedSchedule other) -> other.day() == null ? 0 : other.day()).reversed());

        int streak = 0;
        for (ParsedSchedule other : earlier) {                                  // 3. 최근부터 거슬러 오르며
            Integer winner = other.winnerTeamId();
            if (winner == null) {                                               // 3-1. 무승부는 끊는다
                break;
            }
            boolean won = Objects.equals(winner, teamId);
            if (streak == 0) {                                                  // 3-2. 첫 경기가 방향을 정한다
                streak = won ? 1 : -1;
            } else if (won == (streak > 0)) {                                   // 3-3. 같은 방향이면 이어간다
                streak += won ? 1 : -1;
            } else {                                                            // 3-4. 방향이 바뀌면 끝
                break;
            }
        }
        return streak;
    }

    /** 태그에 쓸 팀 이름. 모르면 번호를 적는다 (D57). */
    private static String label(Integer teamId, NameBook names) {
        String name = teamId == null ? null : names.teamName(teamId);
        return name != null ? name : "팀 " + teamId;
    }

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
