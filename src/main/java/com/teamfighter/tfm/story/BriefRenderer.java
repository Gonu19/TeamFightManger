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
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(names, "names");

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
            out.append("  픽  ").append(champs(set.bluePick()))
                    .append("  /  ").append(champs(set.redPick())).append('\n');
            out.append("  밴  ").append(champs(set.blueBan()))
                    .append("  /  ").append(champs(set.redBan())).append('\n');
        }
        return out.toString();
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
