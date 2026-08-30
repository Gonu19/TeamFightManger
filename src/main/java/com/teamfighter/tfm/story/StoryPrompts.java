package com.teamfighter.tfm.story;

import java.util.List;
import java.util.Objects;

/**
 * 프롬프트를 만든다. <b>두 목소리로 쪼갠다</b> (D61 · architecture.md 6절).
 *
 * <p><b>왜 쪼개나.</b> 기사와 댓글은 요구가 정반대다. 기사는 사실에 묶여야 하고
 * 댓글은 날조가 허용된다. 한 프롬프트에 둘을 넣으면 모델이 중간을 택해서
 * <b>재밌지만 틀린 글</b>만 나온다 — 두 요구 중 어느 쪽도 만족하지 못한 결과다.
 *
 * <p><b>사실 블록은 {@link BriefRenderer} 가 만든 문자열을 그대로 넣는다.</b>
 * 여기서 다시 쓰지 않는다. 다시 쓰면 화면의 「이 기사가 쓴 숫자」와 갈리고,
 * 갈리는 순간 그 블록이 검증 장치가 아니라 장식이 된다.
 */
public final class StoryPrompts {

    private StoryPrompts() {
    }

    /**
     * 기사 프롬프트. <b>사실에 묶는다.</b>
     *
     * <p>지시가 강한 이유는 {@link FactCheck} 가 뒤에서 잡아주기 때문이 아니라,
     * FactCheck 가 잡을 수 있는 것이 스코어·챔피언·팀 이름뿐이기 때문이다.
     * 문장의 인과("넉백 때문에 졌다")는 코드가 검증하지 못한다 —
     * 그러니 애초에 지어내지 않게 해야 한다.
     */
    public static StoryRequest article(MatchBrief brief, NameBook names, Notability notability) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(notability, "notability");

        String system = """
                너는 팀파이트 매니저 리그의 경기 기사를 쓰는 기자다. 한국어로 쓴다.

                지켜야 할 것:
                - 아래 「사실」에 있는 숫자만 쓴다. 없는 숫자는 쓰지 않는다.
                - 「사실」에 없는 챔피언·팀·선수를 등장시키지 않는다.
                - 밴된 챔피언이 활약했다고 쓰지 않는다. 밴은 나오지 않았다는 뜻이다.
                - 경기 밖의 사건(이적, 부상, 관중 반응, 과거 전적)을 지어내지 않는다.
                - 원인을 단정하지 않는다. "밴픽이 갈랐다" 처럼 확인할 수 없는 인과는
                  "~로 보인다" 로 쓰거나 아예 쓰지 않는다.

                문체:
                - 스포츠 기사체. 담백하게 쓰고 과장하지 않는다.
                - 제목 한 줄 뒤에 빈 줄, 그다음 본문.
                - 소제목·목록·마크다운을 쓰지 않는다. 문단만 쓴다.
                """;

        String user = """
                아래 사실만으로 기사를 써라. 분량은 %d문단이다.

                이 경기가 주목되는 이유: %s

                --- 사실 ---
                %s""".formatted(
                notability.paragraphs(),
                notability.reasons().isEmpty() ? "특별한 것 없음" : String.join(", ", notability.reasons()),
                BriefRenderer.render(brief, names));

        return new StoryRequest(system, user, notability.paragraphs() * 320, 0.4);
    }

    /**
     * 댓글 프롬프트. <b>날조를 허용한다.</b>
     *
     * <p>댓글은 창작층 안에서만 산다 — 집계로 올라가지 않고(D61 결정 3), 통계 화면과
     * 링크로 이어지지도 않는다(D61 결정 1). 그래서 마음껏 틀려도 된다.
     * 다만 <b>스코어와 승패는 지킨다</b>. 진 팀 팬이 이겼다고 좋아하면 몰입이 깨진다.
     */
    public static StoryRequest comments(MatchBrief brief, NameBook names,
                                        Notability notability, String article) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(notability, "notability");

        String system = """
                너는 e스포츠 커뮤니티의 여러 유저다. 한국어로 짧은 댓글을 쓴다.

                허용되는 것 — 마음껏 해라:
                - 근거 없는 단정, 과장, 편파, 감정적인 반응
                - 선수·감독에 대한 뇌피셜, 다음 경기 예측, 밈과 유행어
                - 서로 싸우기. 답글처럼 앞 댓글을 받아치기

                지켜야 할 것 — 이것만은:
                - 누가 이겼는지, 스코어가 몇 대 몇인지는 틀리지 않는다.
                - 실존 인물이나 실제 프로게임단을 끌어들이지 않는다. 이 리그 안에서만 논다.

                형식:
                - 한 줄에 댓글 하나. 번호나 기호를 붙이지 않는다.
                - 닉네임을 쓰지 않는다. 댓글 본문만 쓴다.
                - 한 댓글은 두 문장을 넘지 않는다.
                """;

        String user = """
                아래 기사에 달린 댓글 %d개를 써라.

                --- 기사 ---
                %s

                --- 경기 결과 (틀리면 안 되는 것) ---
                %s %d - %d %s""".formatted(
                notability.commentCount(),
                article == null ? "" : article,
                teamName(brief.blueTeamId(), names), brief.blueScore(),
                brief.redScore(), teamName(brief.redTeamId(), names));

        return new StoryRequest(system, user, notability.commentCount() * 90, 1.0);
    }

    /** 댓글을 줄 단위로 자른다. 모델이 번호나 따옴표를 붙이면 떼어낸다. */
    public static List<String> splitComments(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return raw.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .map(StoryPrompts::stripBullet)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private static String stripBullet(String line) {
        String out = line.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", "");
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1);
        }
        return out.strip();
    }

    private static String teamName(Integer id, NameBook names) {
        String name = id == null ? null : names.teamName(id);
        if (name != null) {
            return name;
        }
        return id == null ? "팀 미상" : "팀 " + id;
    }
}
