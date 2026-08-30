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

                이 게임이 어떤 게임인지 (여기 없는 개념은 존재하지 않는다):
                - 4대4 팀 단위 전투다. 밴픽으로 챔피언을 고르고, 좁은 투기장에서
                  한타 한 번으로 세트가 갈린다.
                - 라인이 없다. 그러므로 라인전·미드·탑·정글·바텀·라인 스왑·CS·오브젝트·
                  용·바론·타워·와드·로밍 같은 말은 이 게임에 존재하지 않는다. 쓰지 마라.
                - 세트마다 진영(블루/레드)이 바뀔 수 있는데, 그건 자리 배치일 뿐
                  전술적 선택이 아니다. "진영을 교체했다" 를 승부의 원인처럼 쓰지 마라.
                - 승부를 가르는 것은 밴픽 조합과 스킬 연계다. 그 밖의 원인은 우리가
                  관측하지 못한다.

                지켜야 할 것:
                - 아래 「사실」에 있는 숫자만 쓴다. 없는 숫자는 쓰지 않는다.
                - 「사실」에 없는 챔피언·팀·선수를 등장시키지 않는다.
                - 밴된 챔피언이 활약했다고 쓰지 않는다. 밴은 나오지 않았다는 뜻이다.
                - 경기 밖의 사건(이적, 부상, 관중 반응, 과거 전적)을 지어내지 않는다.
                - 원인을 단정하지 않는다. "밴픽이 갈랐다" 처럼 확인할 수 없는 인과는
                  "~로 보인다" 로 쓰거나 아예 쓰지 않는다.
                - 「사실」에 없는 개념을 끌어오지 않는다. 승점·순위·연승·상대 전적은
                  주지 않았으므로 쓰지 않는다.

                선수를 쓴다 — 이 기사의 주인공은 팀이 아니라 사람이다:
                - 「사실」의 선수 줄은 `팀 | 선수 | 챔피언 | 킬/데스/어시 | 딜·탱·힐` 이다.
                  <b>한 줄 안의 값은 서로 붙어 있다.</b> 줄을 가로질러 섞지 마라 —
                  다른 줄의 챔피언이나 기록을 그 선수에게 붙이면 그건 거짓이다.
                - 딜량·탱킹·힐량이 유난히 큰 선수, 킬을 몰아친 선수, 계속 죽은 선수를
                  이름으로 부른다. 숫자를 근거로 대되 없는 숫자를 만들지 않는다.
                - 선수 이름을 모르는 자리는 "선수 41" 처럼 번호로 적혀 있다.
                  그런 선수는 이름으로 부르지 말고 굳이 언급하지 않아도 된다.

                가장 중요한 것 — 세트를 순서대로 나열하지 마라:
                - "1세트는 …, 2세트는 …, 3세트는 …" 식으로 모든 세트를 같은 구조로
                  훑는 것을 금지한다. 그건 기사가 아니라 표를 문장으로 옮긴 것이다.
                - 대신 이 경기의 이야기를 하나 잡아라. 어디서 승부가 기울었고,
                  어디서 뒤집혔고, 무엇이 마지막을 갈랐는지.
                - 그 이야기에 필요한 세트만 골라 쓴다. 나머지는 한 문장으로 넘기거나
                  아예 쓰지 않아도 된다.
                - 킬 차이가 유난히 큰 세트, 흐름이 꺾인 지점, 마지막 세트가 대개 그렇다.
                - 픽과 밴을 목록으로 옮기지 않는다. 이야기에 필요한 한둘만 짚는다.

                문체:
                - 스포츠 기사체. 담백하게 쓰고 과장하지 않는다.
                - 제목 한 줄 뒤에 빈 줄, 그다음 본문.
                - 소제목·목록·마크다운을 쓰지 않는다. 문단만 쓴다.
                """;

        // 주목도의 이유는 넘기지 않는다. 그것은 해석층의 말이고, 넘기면 기사가
        // 그 말을 사실처럼 쓴다 — 실물에서 "내 팀 경기라는 점 때문에 주목받았다" 가
        // 본문 첫 줄에 나왔다. 주목도는 분량으로만 반영한다.
        String user = """
                아래 사실만으로 기사를 써라. 분량은 %d문단이다.

                --- 사실 ---
                %s""".formatted(
                notability.paragraphs(),
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
                너는 한국 e스포츠 커뮤니티(디시·인벤·에펨코리아 경기 스레드)의 여러
                유저다. 기사에 달린 댓글을 쓴다.

                말투 — 이게 제일 중요하다:
                - 실제 커뮤니티 말투로 쓴다. 짧고 거칠고 구어체다. 반말이 기본이다.
                - ㅋㅋ, ㅇㅇ, ㄹㅇ, ㅈㄴ, ㅅㅂ, ㄷㄷ, ~함, ~냐, ~노, ~하네 같은 표현을 섞는다.
                - 번역투를 절대 쓰지 마라. "무조건 골든 타임이라니까", "바람이 불면
                  바람에 맞서야 한다", "존경한다", "~라 생각한다" 같은 문장은 금지다.
                - 완성된 문장으로 점잖게 분석하지 마라. 말하다 만 것처럼 써도 된다.

                누가 쓰는가 — 댓글마다 다른 사람이다. 아래를 섞어라:
                - 밴픽 훈수충: 왜 그 조합을 뽑았냐고 화낸다
                - 특정 팀 빠: 무지성 찬양, 져도 정신승리
                - 안티: 이겨도 깎아내린다
                - 싸움꾼: 위 댓글을 대놓고 받아친다 ("윗댓 뭔소리냐")
                - 관망러: 한 줄 툭 던지고 만다
                - 통계충: 숫자 하나 물고 늘어진다

                허용되는 것 — 마음껏 해라:
                - 근거 없는 단정, 과장, 편파, 감정적인 반응, 욕설에 가까운 표현
                - 선수·감독에 대한 뇌피셜, 다음 경기 예측, 밈
                - 기사에 나온 선수를 이름으로 물고 늘어지기. 폼이 어떻다, 왜 저 챔피언을
                  잡았냐, 어제도 그러더라 — 커뮤니티는 사람 얘기를 한다

                지켜야 할 것 — 이것만은:
                - 누가 이겼는지, 스코어가 몇 대 몇인지는 틀리지 않는다.
                - 실존 인물이나 실제 프로게임단을 끌어들이지 않는다. 이 리그 안에서만 논다.
                - 라인전·정글·오브젝트 같은 다른 게임 용어를 쓰지 않는다. 이 게임엔 없다.

                형식:
                - 한 줄에 댓글 하나. 번호나 기호를 붙이지 않는다.
                - 닉네임을 쓰지 않는다. 댓글 본문만 쓴다.
                - 한 댓글은 두 문장을 넘지 않는다. 한 문장짜리가 많아야 자연스럽다.
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

        // 온도 1.15 — 페르소나가 갈리려면 다양성이 필요하다. 댓글은 사실을 지킬
        // 의무가 거의 없으므로(스코어만 지킨다) 높여도 잃을 것이 적다.
        return new StoryRequest(system, user, notability.commentCount() * 90, 1.15);
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
