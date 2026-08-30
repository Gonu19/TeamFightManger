package com.teamfighter.tfm.story;

import java.util.List;
import java.util.Objects;

/**
 * {@link MatchBrief} → 사람이 읽는 텍스트.
 *
 * <p><b>이 문자열은 두 곳에 쓰이고, 두 곳에서 같아야 한다.</b>
 *
 * <ol>
 *   <li>LLM 프롬프트의 사실 블록 — 모델이 보는 전부</li>
 *   <li>화면의 「이 기사가 쓴 숫자」 — 독자가 보는 전부 (D61)</li>
 * </ol>
 *
 * <p>렌더링을 둘로 나누면 언젠가 갈린다. 갈리는 순간 그 블록은 검증 장치가 아니라
 * 장식이 된다 — 독자가 보는 숫자와 모델이 본 숫자가 다르기 때문이다.
 * <b>그래서 하나만 만든다.</b> 프롬프트는 이 블록 <i>주위에</i> 지시문을 두르는 것이지
 * 블록 자체를 다시 쓰는 것이 아니다.
 *
 * <p>따라서 여기에는 <b>사실만</b> 들어간다. 해석도 형용사도 없다. "치열했다" 는
 * 창작층의 몫이고, 이 층은 13:8 을 준다.
 */
public final class BriefRenderer {

    private static final String SEP = " · ";

    private BriefRenderer() {
    }

    /**
     * 사실 블록을 만든다. 같은 brief 는 항상 같은 문자열이 된다.
     *
     * <p>이름을 모르면 번호를 적는다. 대회 이름을 모르면 키를 그대로 적는다 —
     * 빈 칸으로 두면 기사가 그 자리를 지어낸다.
     */
    public static String render(MatchBrief brief, NameBook names) {
        return render(brief, names, List.of());
    }

    /**
     * 맥락 태그를 함께 적는다.
     *
     * <p>태그는 {@code SeasonBook} 이 <b>계산한 사실</b>이다 — "블루 3연패 중", "공동 1위끼리".
     * 형용사가 아니므로 이 층(사실)에 들어와도 경계가 깨지지 않는다. 형용은 기사가 짓는다.
     *
     * <p><b>머리글 바로 아래에 둔다.</b> 맨 뒤에 붙이면 모델이 세트 기록을 다 읽은 뒤에야
     * 만나서 첫 문장에 못 쓴다 — 이 태그의 목적이 바로 첫 문장이다.
     */
    public static String render(MatchBrief brief, NameBook names, List<String> contextTags) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(contextTags, "contextTags");

        String blue = team(brief.blueTeamId(), names);
        String red = team(brief.redTeamId(), names);

        StringBuilder out = new StringBuilder();
        out.append('[').append(brief.season()).append(" 시즌 ")
                .append(brief.day()).append("일차] ")
                .append(competition(brief, names));
        if (brief.round() != null) {
            out.append(SEP).append(brief.round()).append("라운드");
        }
        if (brief.isEvent()) {
            out.append(SEP).append("이벤트전");
        }
        out.append('\n');

        for (String tag : contextTags) {                                        // 머리글 바로 아래 — 첫 문장에 쓰이라고 여기 둔다
            out.append("[맥락] ").append(tag).append('\n');
        }

        out.append(blue).append(' ')
                .append(brief.blueScore()).append(" - ").append(brief.redScore())
                .append(' ').append(red)
                .append("  (킬 ").append(brief.blueKill())
                .append(" - ").append(brief.redKill()).append(")\n");

        for (MatchBrief.SetBrief set : brief.sets()) {
            String winner = set.blueWon() ? blue : red;
            out.append('\n').append(set.setNo()).append("세트");
            if (set.sideSwapped()) {
                out.append(SEP).append("진영 교체");
            }
            if (set.isOvertime()) {
                out.append(SEP).append("연장");
            }
            if (set.isSuddenDeath()) {
                out.append(SEP).append("서든데스");
            }
            out.append(SEP).append(winner).append(" 승")
                    .append("  (").append(set.blueKill()).append(" - ").append(set.redKill()).append(")\n");
            // 팀 이름을 줄마다 붙인다. `A · B / C · D` 로 두면 슬래시 좌우가 누구인지
            // 알 수 없다 — 실물 호출에서 모델이 2세트 진영을 반대로 썼고, 챔피언은
            // 전부 이 매치의 것이라 FactCheck 도 잡지 못했다. 사람도 같은 실수를 한다.
            // 선수 기록이 있으면 픽 목록 대신 <b>선수 줄</b>을 쓴다. 같은 정보가 두 꼴로
            // 있으면 모델이 둘을 대조하다 섞는다 — 픽 목록은 선수 줄에 이미 다 들어 있다.
            if (set.players().isEmpty()) {
                out.append("  픽  ").append(blue).append(": ")
                        .append(champs(set.bluePick())).append('\n');
                out.append("      ").append(red).append(": ")
                        .append(champs(set.redPick())).append('\n');
            } else {
                appendPlayers(out, set, blue, red, names);
            }
            out.append("  밴  ").append(blue).append(": ")
                    .append(champs(set.blueBan())).append('\n');
            out.append("      ").append(red).append(": ")
                    .append(champs(set.redBan())).append('\n');
        }
        return out.toString();
    }

    /**
     * 선수 한 명당 한 줄. <b>이 프로젝트에서 환각을 가장 많이 막는 한 줄이다.</b>
     *
     * <h2>왜 한 줄에 다 묶는가</h2>
     *
     * 모델은 정보가 많아지면 <b>관계를 섞는다.</b> 선수 목록과 픽 목록과 기록 표를 따로
     * 주면 "Faker 가 닌자로 3킬, Chovy 가 마법사로 10킬" 이 "Faker 가 마법사로 10킬" 로
     * 나온다. 낱말은 전부 사실이라 숫자 대조로도 안 걸린다 — <b>틀린 것은 연결이지 값이 아니다.</b>
     *
     * 그래서 선수 · 팀 · 챔피언 · 기록을 <b>한 줄 안에서 떨어지지 않게</b> 붙인다.
     * 모델이 이 줄을 통째로 인용하면 관계가 자동으로 지켜지고, 줄을 쪼개 섞으면
     * {@code FactCheck} 의 관계 검사에 걸린다.
     *
     * <pre>
     *   MiG | Faker | Ninja | 3/1/4 | 딜 12000 · 탱 4300 · 힐 0
     * </pre>
     *
     * <p>구분자를 {@code |} 로 둔 것도 같은 이유다. 쉼표는 문장에도 쓰여서 경계가 흐려지는데,
     * 세로줄은 표의 칸처럼 읽힌다.
     *
     * <p>이름을 모르면 번호를 적는다({@code 선수 41}). 빈 칸으로 두면 기사가 채운다(D57).
     */
    private static void appendPlayers(StringBuilder out, MatchBrief.SetBrief set,
                                      String blue, String red, NameBook names) {
        for (MatchBrief.PlayerLine line : set.players()) {                      // 1. 세트의 선수 여덟 명
            out.append("  ")
                    .append(line.blue() ? blue : red)                           // 2. 어느 팀인지를 줄 맨 앞에
                    .append(" | ").append(athlete(line.athleteId(), names))     // 3. 누가
                    .append(" | ").append(line.champion())                      // 4. 무엇으로
                    .append(" | ").append(line.kill()).append('/')              // 5. 킬/데스/어시
                    .append(line.death()).append('/').append(line.assist())
                    .append(" | 딜 ").append(line.dealing())                    // 6. 기여도 세 가지
                    .append(" · 탱 ").append(line.tanking())
                    .append(" · 힐 ").append(line.healing())
                    .append('\n');
        }
    }

    /** 선수 이름. 모르면 번호를 적는다 — 빈 칸을 남기지 않는다 (D57). */
    private static String athlete(Integer athleteId, NameBook names) {
        String name = names.athleteName(athleteId);
        if (name != null && !name.isBlank()) {
            return name;
        }
        return athleteId == null ? "선수 미상" : "선수 " + athleteId;
    }

    /**
     * 대회 이름. 모르면 키를 그대로 쓰고, 이벤트전처럼 대회 자체가 없으면 그렇게 적는다.
     *
     * <p>{@code null} 을 빈 문자열로 흘리지 않는다 — 자리가 비면 기사가 채운다.
     */
    private static String competition(MatchBrief brief, NameBook names) {
        if (brief.competitionKey() == null) {
            return "소속 대회 없음";
        }
        String name = names.competitionName(brief.competitionKey());
        return name != null ? name : brief.competitionKey();
    }

    private static String team(Integer id, NameBook names) {
        String name = id == null ? null : names.teamName(id);
        if (name != null) {
            return name;
        }
        return id == null ? "팀 미상" : "팀 " + id;
    }

    private static String champs(List<String> list) {
        return list == null || list.isEmpty() ? "없음" : String.join(SEP, list);
    }
}
