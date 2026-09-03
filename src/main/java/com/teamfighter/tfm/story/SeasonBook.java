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
     * <p><b>둘이었다가 넷으로 올렸다.</b> 기사의 첫 문단이 "이 경기가 리그에 무엇을
     * 했나" 를 다루는 자리가 되면서 태그가 <b>재료가 아니라 주제</b>가 됐기 때문이다.
     *
     * <p>그래도 상한을 없애지는 않는다. 다 붙이면 기사가 태그를 차례로 소개하기
     * 시작한다 — 세트를 나열하던 실패와 같은 모양이고, 그때 첫 문장의 힘이 오히려 죽는다.
     * 순서가 곧 우선순위다: <b>우리 팀 → 순위 → 연승·연패 → 라이벌</b>.
     */
    private static final int MAX_TAGS = 4;

    /**
     * 연속이라고 부르기 위한 최소 횟수.
     *
     * <p><b>1은 연속이 아니다.</b> 지난 경기에 한 번 진 것을 "1연패" 라고 부르면
     * 기사가 「1연패 종결」을 제목으로 뽑는다 — 실물에서 그렇게 나왔다.
     * 그리고 모든 경기가 연승/연패 태그를 달게 되므로 태그가 흔해져 무게를 잃는다.
     */
    private static final int STREAK_MIN = 2;

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

        Map<Integer, TeamRecord> recordsBefore = new java.util.LinkedHashMap<>(recordsBefore(match));
        Map<Integer, Integer> playedBefore = playedBefore(match);

        // 아직 한 번도 이기지 못한 팀은 앞선 경기 기록에 안 잡힌다. 그렇다고 순위표에서
        // 빼면 "꼴찌가 1위를 잡았다" 를 영영 말할 수 없다. 지금 뛰고 있으니 이 대회 소속인
        // 것은 확실하므로 0승으로 넣는다. 단, 앞선 결과가 하나도 없으면 넣지 않는다 —
        // 0승끼리의 순위는 근거가 아니다.
        if (!recordsBefore.isEmpty()) {
            recordsBefore.putIfAbsent(match.blueTeamId(), new TeamRecord(0, 0, 0));
            recordsBefore.putIfAbsent(match.redTeamId(), new TeamRecord(0, 0, 0));
        }

        // 브래킷은 순위표가 없다. 승수로 줄을 세우면 "1위 대 1위"(둘 다 1라운드 통과)
        // 같은 말이 나온다 — 사실은 맞지만 리그 순위와 다른 뜻이라 기사에서 거짓이 된다.
        if (isBracket(match)) {
            recordsBefore = Map.of();
        }

        Integer size = recordsBefore.isEmpty() ? null : recordsBefore.size();
        Integer blueRank = rank(recordsBefore, match.blueTeamId());
        Integer redRank = rank(recordsBefore, match.redTeamId());

        return new NotabilityContext(
                playerTeamId,
                blueRank,
                redRank,
                size,
                winProbability(recordsBefore, playedBefore, match),
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
        return tagsFor(match, names, null);
    }

    /**
     * 플레이어 팀까지 아는 태그.
     *
     * @param playerTeamId 플레이어 팀 번호(D54). 모르면 {@code null} — 그때는 우리 팀
     *                     관점 태그를 만들지 않는다
     */
    public List<String> tagsFor(ParsedSchedule match, NameBook names, Integer playerTeamId) {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(names, "names");

        List<String> tags = new ArrayList<>();
        Map<Integer, TeamRecord> recordsBefore = recordsBefore(match);
        boolean bracket = isBracket(match);

        String blue = label(match.blueTeamId(), names);
        String red = label(match.redTeamId(), names);
        Integer winnerId = match.winnerTeamId();
        Integer loserId = winnerId == null ? null : (Objects.equals(winnerId, match.blueTeamId()) ? match.redTeamId() : match.blueTeamId());

        if (!recordsBefore.isEmpty() && !bracket) {
            Map<Integer, TeamRecord> recordsAfter = new java.util.LinkedHashMap<>(recordsBefore);
            if (winnerId != null) {
                TeamRecord winnerRec = recordsAfter.getOrDefault(winnerId, new TeamRecord(0, 0, 0));
                TeamRecord loserRec = recordsAfter.getOrDefault(loserId, new TeamRecord(0, 0, 0));
                
                int blueScore = match.blueScore();
                int redScore = match.redScore();
                int setDiffBlue = blueScore - redScore;
                int setDiffRed = redScore - blueScore;
                
                int blueKill = match.blueKill();
                int redKill = match.redKill();
                int kdDiffBlue = blueKill - redKill;
                int kdDiffRed = redKill - blueKill;

                if (Objects.equals(winnerId, match.blueTeamId())) {
                    recordsAfter.put(winnerId, new TeamRecord(winnerRec.wins() + 1, winnerRec.setDiff() + setDiffBlue, winnerRec.kdDiff() + kdDiffBlue));
                    recordsAfter.put(loserId, new TeamRecord(loserRec.wins(), loserRec.setDiff() + setDiffRed, loserRec.kdDiff() + kdDiffRed));
                } else {
                    recordsAfter.put(winnerId, new TeamRecord(winnerRec.wins() + 1, winnerRec.setDiff() + setDiffRed, winnerRec.kdDiff() + kdDiffRed));
                    recordsAfter.put(loserId, new TeamRecord(loserRec.wins(), loserRec.setDiff() + setDiffBlue, loserRec.kdDiff() + kdDiffBlue));
                }
            }
            Integer blueRankBefore = rank(recordsBefore, match.blueTeamId());
            Integer redRankBefore = rank(recordsBefore, match.redTeamId());
            Integer blueRankAfter = rank(recordsAfter, match.blueTeamId());
            Integer redRankAfter = rank(recordsAfter, match.redTeamId());
            int teams = recordsBefore.size();

            if (blueRankBefore != null && redRankBefore != null) {
                int blueWinsBefore = recordsBefore.containsKey(match.blueTeamId()) ? recordsBefore.get(match.blueTeamId()).wins() : 0;
                int redWinsBefore = recordsBefore.containsKey(match.redTeamId()) ? recordsBefore.get(match.redTeamId()).wins() : 0;

                // 전·후를 <b>한 태그</b>로 붙인다. 둘로 나누면 태그 두 자리를 쓰는데
                // 사실은 한 가지 이야기("이 경기가 순위를 어떻게 움직였나")다.
                // 태그가 늘수록 기사는 그것을 차례로 소개하기 시작한다.
                StringBuilder rankTag = new StringBuilder("순위: ")
                        .append(blue).append(' ').append(blueRankBefore).append("위(")
                        .append(blueWinsBefore).append("승)");
                if (winnerId != null) {
                    rankTag.append(" → ").append(blueRankAfter).append('위');
                }
                rankTag.append(", ").append(red).append(' ').append(redRankBefore).append("위(")
                        .append(redWinsBefore).append("승)");
                if (winnerId != null) {
                    rankTag.append(" → ").append(redRankAfter).append('위');
                }
                rankTag.append(" (").append(teams).append("팀 중");

                // 남은 경기 수가 순위의 무게를 정한다. 32경기 남은 1위와 2경기 남은 1위는
                // 같은 1위가 아니다. 그 차이를 안 주면 기사가 매번 "굳혔다" 로만 쓴다.
                int remaining = remainingInCompetition(match);
                if (remaining > 0) {
                    rankTag.append(" · 이 대회 ").append(remaining).append("경기 남음");
                }
                tags.add(rankTag.append(')').toString());
            }
        }

        for (Integer team : List.of(match.blueTeamId(), match.redTeamId())) {
            int streakBefore = streakBefore(match, team);
            String who = label(team, names);
            int count = Math.abs(streakBefore);

            // 1은 연속이 아니다. 지난 경기에 한 번 진 것을 "1연패" 라고 부르면
            // 기사가 "1연패 종결" 을 제목으로 뽑는다 — 실물에서 그렇게 나왔다.
            // 연속이라는 말이 성립하려면 최소 둘이다.
            if (count < STREAK_MIN) {
                continue;
            }
            boolean wonThis = Objects.equals(team, winnerId);
            boolean lostThis = Objects.equals(team, loserId);

            if (streakBefore > 0 && wonThis) {
                tags.add(String.format("%s: 경기 전 %d연승 중이었으며, 이 승리로 %d연승을 달성함", who, count, count + 1));
            } else if (streakBefore > 0 && lostThis) {
                tags.add(String.format("%s: 경기 전 %d연승 중이었으나, 이 패배로 연승이 끊김", who, count));
            } else if (streakBefore < 0 && wonThis) {
                tags.add(String.format("%s: 경기 전 %d연패 중이었으나, 이 승리로 연패를 끊어냄", who, count));
            } else if (streakBefore < 0 && lostThis) {
                tags.add(String.format("%s: 경기 전 %d연패 중이었으며, 이 패배로 %d연패의 수렁에 빠짐", who, count, count + 1));
            }
        }

        if (metInPastBracket(match)) {
            tags.add("두 팀은 과거 토너먼트(플레이오프/월즈) 등 큰 무대에서 맞붙은 전적이 있는 라이벌 관계다");
        }

        // 우리 팀 관점을 <b>맨 뒤가 아니라 맨 앞에</b> 놓는다. 이 앱은 커리어 게임이고,
        // 같은 2:0 도 내 팀이 뛰었는지에 따라 완전히 다른 기사가 된다.
        // 넣지 않으면 기사가 남의 리그 중계처럼 읽힌다 — 실물이 정확히 그랬다.
        perspective(match, names, playerTeamId, recordsBefore, bracket).ifPresent(t -> tags.add(0, t));

        return tags.size() <= MAX_TAGS ? List.copyOf(tags) : List.copyOf(tags.subList(0, MAX_TAGS));
    }

    /**
     * 이 경기가 <b>플레이어에게</b> 무엇인가.
     *
     * <p>세 갈래다. 내 팀이 뛰었으면 그 결과가 곧 내 결과이고, 안 뛰었으면 이 경기는
     * <b>내 순위에 일어난 일</b>이다 — 내가 안 뛴 경기가 내 순위를 떨어뜨린다는 것이
     * 리그의 긴장이고, 그걸 말해 주지 않으면 남의 경기는 그냥 남의 경기로 읽힌다.
     *
     * <p>플레이어 팀을 모르면({@code null}) 아무 말도 하지 않는다. 모르는 것으로
     * 기사를 키우지 않는다 — {@link NotabilityContext} 가 세운 규칙 그대로다.
     */
    private java.util.Optional<String> perspective(ParsedSchedule match, NameBook names,
                                                   Integer playerTeamId,
                                                   Map<Integer, TeamRecord> recordsBefore,
                                                   boolean bracket) {
        if (playerTeamId == null) {
            return java.util.Optional.empty();
        }
        boolean plays = Objects.equals(playerTeamId, match.blueTeamId())
                || Objects.equals(playerTeamId, match.redTeamId());
        String me = label(playerTeamId, names);

        if (plays) {
            Integer winnerId = match.winnerTeamId();
            String outcome = winnerId == null ? "무승부다"
                    : (Objects.equals(winnerId, playerTeamId) ? "우리가 이겼다" : "우리가 졌다");
            return java.util.Optional.of("이 경기는 우리 팀(" + me + ")의 경기다 — " + outcome);
        }

        // 안 뛴 경기. 순위표가 없으면(브래킷·시즌 초반) 그 사실만 말한다.
        if (bracket || recordsBefore.isEmpty() || !recordsBefore.containsKey(playerTeamId)) {
            return java.util.Optional.of("우리 팀(" + me + ")은 이 경기에 없다");
        }
        Integer myRank = rank(recordsBefore, playerTeamId);
        int myWins = recordsBefore.get(playerTeamId).wins();
        Integer winnerId = match.winnerTeamId();
        if (winnerId == null || !recordsBefore.containsKey(winnerId)) {
            return java.util.Optional.of(
                    "우리 팀(" + me + ", " + myRank + "위)은 이 경기에 없다");
        }
        // 승자가 우리보다 아래였다면 이 경기가 우리를 쫓아온 것이다. 승차로 말한다 —
        // "몇 위" 보다 "몇 경기 차" 가 남은 일정과 이어진다.
        int winnerWinsAfter = recordsBefore.get(winnerId).wins() + 1;
        int gap = myWins - winnerWinsAfter;
        String gapText = gap > 0 ? gap + "경기 앞선다"
                : gap == 0 ? "승수가 같아졌다"
                : (-gap) + "경기 뒤진다";
        return java.util.Optional.of("우리 팀(" + me + ", " + myRank + "위 " + myWins + "승)은 이 경기에 없다 — "
                + label(winnerId, names) + "가 이겨 우리가 " + gapText);
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

    /** 팀별 승수, 세트 득실차, 그리고 킬-데스 득실차를 담는다 */
    private record TeamRecord(int wins, int setDiff, int kdDiff) implements Comparable<TeamRecord> {
        @Override
        public int compareTo(TeamRecord o) {
            if (this.wins != o.wins) {
                return Integer.compare(this.wins, o.wins);
            }
            if (this.setDiff != o.setDiff) {
                return Integer.compare(this.setDiff, o.setDiff);
            }
            return Integer.compare(this.kdDiff, o.kdDiff);
        }
    }

    private Map<Integer, TeamRecord> recordsBefore(ParsedSchedule match) {
        Map<Integer, Integer> wins = new java.util.LinkedHashMap<>();
        Map<Integer, Integer> setDiffs = new java.util.LinkedHashMap<>();
        Map<Integer, Integer> kdDiffs = new java.util.LinkedHashMap<>();

        for (ParsedSchedule other : earlierInSameCompetition(match)) {
            Integer winner = other.winnerTeamId();
            for (Integer team : List.of(other.blueTeamId(), other.redTeamId())) {
                wins.putIfAbsent(team, 0);
                setDiffs.putIfAbsent(team, 0);
                kdDiffs.putIfAbsent(team, 0);
            }
            if (winner != null) {
                wins.merge(winner, 1, Integer::sum);
            }
            setDiffs.merge(other.blueTeamId(), other.blueScore() - other.redScore(), Integer::sum);
            setDiffs.merge(other.redTeamId(), other.redScore() - other.blueScore(), Integer::sum);
            
            kdDiffs.merge(other.blueTeamId(), other.blueKill() - other.redKill(), Integer::sum);
            kdDiffs.merge(other.redTeamId(), other.redKill() - other.blueKill(), Integer::sum);
        }
        
        Map<Integer, TeamRecord> records = new java.util.LinkedHashMap<>();
        for (Integer team : wins.keySet()) {
            records.put(team, new TeamRecord(wins.get(team), setDiffs.get(team), kdDiffs.get(team)));
        }
        return records;
    }

    /**
     * 이 대회에서 <b>아직 안 치른</b> 매치 수.
     *
     * <p>순위의 무게를 정하는 값이다. 32경기 남은 1위와 2경기 남은 1위는 같은 1위가
     * 아닌데, 그 차이를 안 주면 기사가 매번 "굳혔다" 로만 쓴다.
     *
     * <p><b>미래를 보지만 결과는 안 본다.</b> 이 클래스의 다른 메서드가 미래를 안 보는
     * 이유는 <i>결과</i>가 새어 들어오기 때문이다. 일정의 존재 자체는 경기 전에도
     * 알 수 있는 사실이라 그 규칙에 걸리지 않는다.
     */
    private int remainingInCompetition(ParsedSchedule match) {
        if (match.competitionId() == null) {
            return 0;
        }
        int count = 0;
        for (ParsedSchedule other : schedules) {
            if (other == match || other.isPlayed()) {
                continue;
            }
            if (Objects.equals(other.competitionId(), match.competitionId())
                    && Objects.equals(other.season(), match.season())) {
                count++;
            }
        }
        return count;
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

    /** 승수, 득실차 내림차순 순위. 같은 기록이면 같은 등수다. 그 팀이 표에 없으면 {@code null}. */
    private static Integer rank(Map<Integer, TeamRecord> records, Integer teamId) {
        TeamRecord mine = records.get(teamId);
        if (mine == null) {
            return null;
        }
        long ahead = records.values().stream().filter(r -> r.compareTo(mine) > 0).count();
        return (int) ahead + 1;
    }

    /**
     * 블루팀의 사전 승률. D14 의 기대 승률 식을 그대로 쓴다 —
     * 두 팀의 기저 강도만으로 예측되는 승률이다.
     *
     * <p>표본이 모자란 팀이 하나라도 있으면 {@code null} 이다.
     */
    private static Double winProbability(Map<Integer, TeamRecord> records,
                                         Map<Integer, Integer> played,
                                         ParsedSchedule match) {
        Double blue = rate(records, played, match.blueTeamId());
        Double red = rate(records, played, match.redTeamId());
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

    private static Double rate(Map<Integer, TeamRecord> records, Map<Integer, Integer> played,
                               Integer teamId) {
        int n = played.getOrDefault(teamId, 0);
        if (n < MIN_MATCHES_FOR_STRENGTH) {
            return null;
        }
        TeamRecord rec = records.get(teamId);
        int wins = rec == null ? 0 : rec.wins();
        return (double) wins / n;
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
