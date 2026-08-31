package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * <b>하루치 사실.</b> 라운드 총평 기사가 보는 것이다.
 *
 * <h2>왜 매치 brief 를 여러 개 넘기지 않는가</h2>
 *
 * 하루에 매치가 열 건이면 세트가 서른 개, 선수 줄이 240개다. 그걸 다 넘기면 프롬프트가
 * 수만 토큰이 되고 무료 티어에서는 아예 못 보낸다. 총평이 필요로 하는 것은 그 깊이가
 * 아니라 <b>넓이</b>다 — 누가 누구를 이겼고 어디가 놀라웠나.
 *
 * <p>그래서 매치당 한 줄만 담는다. 선수도 픽도 없다. 그 대신 매치 기사가 이미 그 깊이를
 * 맡고 있고, 두 기사가 같은 것을 두 번 말하지 않게 된다.
 *
 * <h2>이것도 사실층이다</h2>
 *
 * {@link MatchBrief} 와 같은 규칙을 따른다 — 숫자와 이름만 담고 형용사를 담지 않는다.
 * "치열했다" 는 총평 기사가 쓸 말이지 여기서 정할 것이 아니다.
 *
 * @param results 그날 끝난 매치들. 스케줄에서 그대로 온다
 */
public record RoundBrief(int season, int day, List<ParsedSchedule> results) {

    public RoundBrief {
        results = List.copyOf(Objects.requireNonNull(results, "results"));
    }

    /**
     * 그날 끝난 매치만 모은다. 진행 중인 매치는 결과가 없으므로 총평에 못 들어간다.
     *
     * @throws IllegalArgumentException 끝난 매치가 하나도 없을 때. 총평을 쓸 것이 없다는 뜻이라
     *                                  부르는 쪽이 그 사실을 알아야 한다
     */
    public static RoundBrief of(int season, int day, List<ParsedSchedule> allSchedules) {
        List<ParsedSchedule> sameDay = allSchedules.stream()                    // 1. 그 날짜의 매치만
                .filter(m -> m.season() != null && m.season() == season)
                .filter(m -> m.day() != null && m.day() == day)
                .filter(ParsedSchedule::isPlayed)                               // 2. 끝난 것만
                .toList();

        if (sameDay.isEmpty()) {
            throw new IllegalArgumentException(
                    "시즌 " + season + " " + day + "일에 끝난 매치가 없다 — 총평을 쓸 것이 없다");
        }
        return new RoundBrief(season, day, sameDay);
    }

    /**
     * 사실 블록. 매치 하나가 한 줄이다.
     *
     * <pre>
     *   Ember scale 3 - 0 Afreeca Freecs  (킬 71 - 58)
     * </pre>
     *
     * <p>이름을 모르면 번호를 적는다(D57). 대회 이름은 키가 그대로 나올 수 있다 —
     * 대회 이름표가 DB 에 아직 없다.
     */
    public String render(NameBook names) {
        Objects.requireNonNull(names, "names");

        StringBuilder out = new StringBuilder();
        out.append('[').append(season).append(" 시즌 ").append(day).append("일차] ")
                .append("경기 ").append(results.size()).append("건\n\n");

        for (ParsedSchedule match : results) {
            out.append(team(match.blueTeamId(), names)).append(' ')
                    .append(match.blueScore()).append(" - ").append(match.redScore())
                    .append(' ').append(team(match.redTeamId(), names))
                    .append("  (킬 ").append(match.blueKill())
                    .append(" - ").append(match.redKill()).append(')');
            if (match.competitionKey() != null) {
                out.append("  ").append(competition(match, names));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** 그날 뛴 팀 이름 전부. {@code FactCheck} 가 "이 날 없던 팀" 을 가려내는 데 쓴다. */
    public Set<String> teamNames(NameBook names) {
        Set<String> out = new LinkedHashSet<>();
        for (ParsedSchedule match : results) {
            for (Integer id : List.of(match.blueTeamId(), match.redTeamId())) {
                String name = names.teamName(id);
                if (name != null) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /**
     * 이 브리프가 아는 숫자 전부.
     *
     * <p>대조가 "총평에 없는 숫자" 를 가려내는 기준이다. 스코어·킬·시즌·일에 더해
     * <b>매치 수</b>도 넣는다 — 기사가 "오늘 다섯 경기가 있었다" 를 쓸 것이기 때문이다.
     */
    public Set<Integer> knownNumbers() {
        Set<Integer> out = new LinkedHashSet<>(List.of(season, day, results.size()));
        for (ParsedSchedule match : results) {
            out.add(match.blueScore());
            out.add(match.redScore());
            out.add(match.blueKill());
            out.add(match.redKill());
            if (match.round() != null) {
                out.add(match.round());
            }
        }
        return out;
    }

    private static String team(Integer teamId, NameBook names) {
        String name = teamId == null ? null : names.teamName(teamId);
        return name != null ? name : "팀 " + teamId;
    }

    private static String competition(ParsedSchedule match, NameBook names) {
        String name = names.competitionName(match.competitionKey());
        return name != null ? name : match.competitionKey();
    }

    /** 총평이 다룰 만큼 경기가 있었나. 한 경기뿐이면 매치 기사와 같은 말을 하게 된다. */
    public boolean isWorthSummarising() {
        return results.size() >= 2;
    }

    /** 하루 안에서 가장 큰 킬 차이. 총평이 "일방적이었던 경기" 를 짚을 근거다. */
    public List<ParsedSchedule> byMargin() {
        List<ParsedSchedule> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> Integer.compare(
                Math.abs(b.blueKill() - b.redKill()), Math.abs(a.blueKill() - a.redKill())));
        return List.copyOf(sorted);
    }
}
