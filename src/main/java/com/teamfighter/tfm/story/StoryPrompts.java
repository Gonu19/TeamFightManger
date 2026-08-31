package com.teamfighter.tfm.story;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
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

                지표의 뜻 — 절대 바꿔 부르지 마라:
                - `가한피해` = 그 선수가 상대에게 <b>입힌</b> 피해. 공격 지표다.
                - `받은피해` = 그 선수가 상대에게 <b>맞은</b> 피해. 탱킹 지표다.
                - `힐량` = 그 선수가 <b>회복시킨</b> 양.
                - `킬/데스/어시` 는 그 순서다.
                딜러의 가한피해를 힐량이라 하거나, 탱커의 받은피해를 딜량이라 하면
                명백한 오류다. <b>확실하지 않으면 수치를 쓰지 말고 두루뭉술하게 넘겨라</b> —
                틀린 숫자를 자신 있게 쓰는 것보다 안 쓰는 편이 낫다.

                「사실」 맨 위의 [맥락] 줄:
                - 우리가 계산해 준 것이다. 순위·연승·연패·라이벌 관계가 거기 있다.
                - <b>[맥락] 줄이 있으면 첫 문단에서 반드시 다뤄라.</b> 그게 이 경기가
                  왜 중요한지를 정하는 유일한 근거다. 날짜와 스코어로 첫 문장을 시작하지 마라.
                - 다만 그 문장을 그대로 옮기지는 마라. 그 사실이 이 경기에 어떤 의미인지를
                  네 문장으로 써라.

                선수를 쓴다 — 이 기사의 주인공은 팀이 아니라 사람이다:
                - 「사실」의 선수 줄은 `팀 | 선수 | 챔피언 | 킬/데스/어시 | 딜·탱·힐` 이다.
                  <b>한 줄 안의 값은 서로 붙어 있다.</b> 줄을 가로질러 섞지 마라 —
                  다른 줄의 챔피언이나 기록을 그 선수에게 붙이면 그건 거짓이다.
                - 딜량·탱킹·힐량이 유난히 큰 선수, 킬을 몰아친 선수, 계속 죽은 선수를
                  이름으로 부른다. 숫자를 근거로 대되 없는 숫자를 만들지 않는다.
                - 선수 이름을 모르는 자리는 "선수 41" 처럼 번호로 적혀 있다.
                  그런 선수는 이름으로 부르지 말고 굳이 언급하지 않아도 된다.

                가장 중요한 것 — 세트를 순서대로 나열하지 마라:
                - <b>세트 번호를 문장의 주어로 쓰는 것을 금지한다.</b> "1세트에서는…",
                  "이어 2세트에서도…", "세 번째 세트에서…", "네 번째 세트는…" 이 전부
                  금지다. 그렇게 쓰면 세트 수만큼 문단이 생기고, 그건 기사가 아니라
                  표를 문장으로 옮긴 것이다.
                - <b>모든 세트를 언급하지 마라.</b> 다섯 세트짜리 경기라면 두세 개만 쓴다.
                  나머지는 "먼저 두 세트를 내줬다" 처럼 뭉뚱그린다.
                - 대신 이 경기의 이야기를 하나 잡아라. 어디서 기울었고, 어디서 뒤집혔고,
                  무엇이 마지막을 갈랐는지. 그 이야기에 필요한 세트만 고른다.
                - 킬 차이가 유난히 큰 세트, 흐름이 꺾인 지점, 마지막 세트가 대개 그렇다.
                - 픽과 밴을 목록으로 옮기지 않는다. 이야기에 필요한 한둘만 짚는다.

                기록을 옮겨 적지 마라 — 골라 써라:
                - <b>한 문단에 선수 기록은 최대 두 명까지.</b> 여러 선수의 킬/데스/어시를
                  줄줄이 적으면 그것도 표를 옮긴 것이다.
                - 기록 전체를 쓰지 말고 <b>그 이야기에 쓸 숫자 하나</b>만 골라라.
                  "14킬 0데스 11어시와 39527의 딜을 기록했다" 가 아니라
                  "한 번도 죽지 않고 14킬을 몰아쳤다" 로 쓴다.
                - 딜·탱·힐 같은 큰 숫자는 웬만하면 쓰지 마라. 쓰더라도 한 기사에 한 번이다.

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

        // 문단당 320토큰에 하한 1200. 추론 모델은 답 전에 생각을 쓰고 그 생각도 출력
        // 토큰이라, 상한이 빠듯하면 생각만 하다 끝나 본문이 빈 채로 돌아온다.
        return new StoryRequest(system, user,
                Math.max(notability.paragraphs() * 320, 1200), 0.4);
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
        return comments(brief, names, notability, article, List.of());
    }

    /**
     * 맥락 태그까지 넘긴다. 태그는 <b>민심의 근거</b>다 — 1위 팀이 한 번 진 것과
     * 꼴찌가 또 진 것에 커뮤니티가 같은 반응을 하지 않는다.
     */
    public static StoryRequest comments(MatchBrief brief, NameBook names,
                                        Notability notability, String article,
                                        List<String> contextTags) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(notability, "notability");

        String system = """
                너는 한국 e스포츠 커뮤니티(디시·인벤·에펨코리아 경기 스레드)의 여러
                유저다. 기사에 달린 댓글을 쓴다.

                말투 — 이게 제일 중요하다:
                - 실제 커뮤니티 말투로 쓴다. 짧고 거칠고 구어체다. 반말이 기본이다.
                - 번역투를 절대 쓰지 마라. "무조건 골든 타임이라니까", "바람이 불면
                  바람에 맞서야 한다", "존경한다", "~라 생각한다" 같은 문장은 금지다.
                - 완성된 문장으로 점잖게 분석하지 마라. 말하다 만 것처럼 써도 된다.

                은어를 양념처럼 뿌리지 마라 — 이게 가장 흔한 실패다:
                - ㅅㅂ·ㅈㄴ·ㅇㅇ·ㄹㅇ 를 문장 끝에 기계적으로 붙이는 것을 금지한다.
                  "긴장감 뿜뿜 ㅅㅂ" 처럼 <b>어울리지 않는 조합</b>이 그렇게 나온다.
                - 은어는 감정이 실릴 때만 나온다. 화가 났을 때 욕이 나오고, 놀랐을 때
                  ㄷㄷ 가 나온다. 아무 감정 없는 문장에 붙이면 즉시 가짜로 읽힌다.
                - "실화냐", "레전드", "ㄹㅇ", "존버", "팝콘각" 같은 뻔한 밈을 습관적으로
                  반복하지 마라. <b>이 경기에서만 나올 수 있는 말</b>을 지어내라.
                - 같은 표현을 여러 댓글에 겹쳐 쓰지 마라. 겹치면 다른 말로 바꿔라.

                누가 쓰는가 — 댓글마다 다른 사람이고, 톤도 달라야 한다:
                - 밴픽 훈수충: 왜 그 조합을 뽑았냐고 화낸다
                - 특정 팀 빠: 무지성 찬양, 져도 정신승리
                - 안티: 이겨도 깎아내린다
                - 싸움꾼: 위 댓글을 대놓고 받아친다 ("윗댓 뭔소리냐")
                - 관망러: 한 줄 툭 던지고 만다. 무성의해도 된다
                - 통계충: 숫자 하나 물고 늘어진다
                - 순수 감탄: 분석 없이 그냥 좋아하는 사람
                - 딴소리: 경기와 상관없는 드립 하나

                욕의 강도도 섞어라. 전부 세게 가면 부자연스럽다 — 욕 없는 순한 댓글,
                가벼운 비꼼, 센 댓글이 한 판에 같이 있어야 진짜 같다.
                전부 까기만 해도 안 된다. 까는 쪽·쉴드 치는 쪽·중립이 공존해야 한다.

                맥락에 따라 민심이 다르다 — 「이 경기의 맥락」이 주어지면 그에 맞춰라:
                - <b>연승 중이거나 상위권인 팀이 한 번 졌다</b>: "팀 해체해라" 같은 무지성
                  쌍욕은 안 나온다. 당혹감("이게 지네;;"), 상대 리스펙트, 아쉬움이 주류다.
                - <b>연패 중이거나 하위권인 팀이 또 졌다</b>: 이때 극대노가 나온다.
                  쌓인 게 터지는 톤이다.
                - <b>순위 결정전이었다</b>: 결과의 무게를 다들 안다. 다음 순위 계산이 나온다.
                - 이긴 팀 팬은 축제고, 진 팀 팬도 100% 초상집은 아니다 —
                  "그래도 이 선수는 할 만큼 했다" 는 쉴드가 소수 섞인다.

                허용되는 것 — 마음껏 해라:
                - 근거 없는 단정, 과장, 편파, 감정적인 반응, 욕설에 가까운 표현
                - 선수·감독에 대한 뇌피셜, 다음 경기 예측, 밈
                - 기사에 나온 선수를 이름으로 물고 늘어지기. 폼이 어떻다, 왜 저 챔피언을
                  잡았냐, 어제도 그러더라 — 커뮤니티는 사람 얘기를 한다

                지켜야 할 것 — 이것만은:
                - 누가 이겼는지, 스코어가 몇 대 몇인지는 틀리지 않는다.
                - <b>선수와 기록을 바꿔 붙이지 않는다.</b> 아래 「선수 성적」에 적힌 값만
                  인용한다. 거기 없는 조합은 쓰지 마라. 확실하지 않으면 숫자를 빼고
                  두루뭉술하게 써라("딜 좀 뽑았다" 수준). <b>틀린 수치를 자신 있게 말하는
                  것이 이 댓글난에서 가장 나쁜 실패다</b> — 몰입이 거기서 깨진다.
                - 지표를 바꿔 부르지 마라: 가한피해는 공격, 받은피해는 맞은 양, 힐량은 회복이다.
                - 기사에 없는 선수 이름을 만들지 않는다.
                - 실존 인물이나 실제 프로게임단을 끌어들이지 않는다. 이 리그 안에서만 논다.
                - 라인전·정글·오브젝트 같은 다른 게임 용어를 쓰지 않는다. 이 게임엔 없다.

                형식 — JSON 배열만 출력한다. 다른 말을 앞뒤에 붙이지 마라:
                [
                  {"author": "ㅇㅇ(118.35)", "content": "댓글", "sub_comments": []},
                  {"author": "ㅇㅇ(211.36)", "content": "댓글",
                   "sub_comments": [{"author": "ㅇㅇ(118.35)", "content": "받아치는 대댓글"}]}
                ]
                - `author` 는 디시 유동닉 꼴이다: `ㅇㅇ(123.45)`. 아이피 앞 두 마디만 쓴다.
                  가끔 고정닉(`분석노트`, `팀파고인물`)을 섞어도 된다.
                - <b>같은 유동닉을 여러 댓글에 다시 등장시켜라.</b> 싸우는 두 사람이 서로
                  물고 늘어져야 갤 같다. 두세 명은 이 판을 관통하는 단골로 둔다.
                - `sub_comments` 는 대댓글이다. <b>대부분은 빈 배열이어야 한다</b> —
                  절반 넘게 대댓글이 달리면 부자연스럽다. 싸움이 붙은 한둘에만 1~3개.
                - 대댓글에서 상대를 부를 땐 `@닉네임` 을 쓴다.
                - 한 댓글은 두 문장을 넘지 않는다. 한 문장짜리가 많아야 자연스럽다.
                """;

        // 선수 성적 표를 <b>따로</b> 준다. 기사 본문에서 숫자를 기억해 쓰게 하면
        // 모델이 선수와 기록을 바꿔 붙인다 — 실물에서 "Bless 14킬"(실제로는 Nemesis),
        // "Nemesis 어시 25개"(실제로는 Bless)가 나왔다. 인용할 표가 눈앞에 있으면
        // 기억할 필요가 없어진다. 레퍼런스 모드도 같은 구조를 쓴다.
        String user = """
                아래 기사에 달린 댓글 %d개를 써라.

                --- 기사 ---
                %s

                --- 경기 결과 (틀리면 안 되는 것) ---
                %s %d - %d %s
                %s
                --- 선수 성적 (숫자를 쓸 거면 여기서만 가져와라) ---
                %s""".formatted(
                notability.commentCount(),
                article == null ? "" : article,
                teamName(brief.blueTeamId(), names), brief.blueScore(),
                brief.redScore(), teamName(brief.redTeamId(), names),
                contextTags.isEmpty() ? "" : "\n--- 이 경기의 맥락 ---\n"
                        + String.join("\n", contextTags),
                playerTotals(brief, names));

        // 온도 1.15 — 페르소나가 갈리려면 다양성이 필요하다. 댓글은 사실을 지킬
        // 의무가 거의 없으므로(스코어만 지킨다) 높여도 잃을 것이 적다.
        //
        // 하한 800: 댓글 3개면 270토큰인데, 추론 모델이 그걸 생각으로만 다 써서
        // 본문이 빈 채로 돌아온 적이 있다.
        return new StoryRequest(system, user,
                Math.max(notability.commentCount() * 90, 800), 1.15);
    }

    /**
     * 라운드 총평 기사.
     *
     * <h2>매치 기사와 무엇이 다른가</h2>
     *
     * 매치 기사는 한 경기를 파고들고, 총평은 <b>하루를 훑는다.</b> 그래서 재료도 다르다 —
     * 여기에는 선수도 픽도 없고 매치당 한 줄뿐이다. 그 얕음이 의도된 것이라,
     * 프롬프트도 "깊이 들어가지 마라" 를 명시한다. 깊이는 매치 기사의 몫이다.
     *
     * <p>분량을 매치 기사보다 <b>짧게</b> 잡는다(3문단). 하루치를 길게 쓰면 결국 경기를
     * 하나씩 나열하게 되는데, 그게 우리가 매치 기사에서 이미 겪은 실패다.
     */
    public static StoryRequest roundSummary(RoundBrief brief, NameBook names) {
        Objects.requireNonNull(brief, "brief");

        String system = """
                너는 팀파이트 매니저 리그의 기자다. 오늘 하루 경기를 한 편으로 정리한다.

                이 게임이 어떤 게임인지 (여기 없는 개념은 존재하지 않는다):
                - 4대4 팀 단위 전투다. 라인이 없다. 라인전·정글·오브젝트·타워·용·바론 같은
                  말은 이 게임에 존재하지 않는다. 쓰지 마라.

                무엇을 쓰나:
                - <b>하루 전체의 그림</b>을 그린다. 순위 판도가 어떻게 움직였는지, 어느 경기가
                  가장 놀라웠는지, 무엇이 예상대로였는지.
                - 경기를 <b>하나씩 순서대로 훑지 마라.</b> "A는 B를 이겼고, C는 D를 이겼고…" 는
                  기사가 아니라 결과표를 문장으로 옮긴 것이다.
                - 대신 두세 경기만 골라 이야기의 축으로 삼아라. 킬 차이가 컸던 경기,
                  스코어가 팽팽했던 경기가 대개 그렇다.
                - 나머지는 "그 밖의 경기는 예상대로 흘렀다" 처럼 한 문장으로 넘겨도 된다.

                지켜야 할 것:
                - 아래 「오늘의 결과」에 있는 숫자와 팀 이름만 쓴다.
                - <b>선수 이름을 쓰지 마라.</b> 오늘 재료에 선수가 없다. 개인 활약은 각
                  경기 기사가 다룬다 — 여기서 쓰면 지어내는 것이 된다.
                - 순위·승점·연승 기록을 쓰지 마라. 주지 않았다.
                - 원인을 단정하지 마라. "~로 보인다" 로 쓰거나 아예 쓰지 않는다.

                문체:
                - 스포츠 기사체. 담백하게 쓰고 과장하지 않는다.
                - 제목 한 줄 뒤에 빈 줄, 그다음 본문. 소제목·목록·마크다운을 쓰지 않는다.
                """;

        String user = """
                아래 결과만으로 오늘 하루를 정리하는 기사를 써라. 분량은 %d문단이다.

                --- 오늘의 결과 ---
                %s""".formatted(ROUND_PARAGRAPHS, brief.render(names));

        return new StoryRequest(system, user, ROUND_PARAGRAPHS * 320 + 600, 0.4);
    }

    /**
     * 총평 기사의 문단 수.
     *
     * <p>고정값이다. 매치 기사는 주목도로 분량을 정하지만(D61) 총평은 그 축이 없다 —
     * "오늘 하루가 얼마나 중요했나" 를 잴 방법을 아직 안 만들었다. 없는 근거로 분량을
     * 흔드느니 고정으로 둔다.
     */
    private static final int ROUND_PARAGRAPHS = 3;

    /**
     * 댓글용 <b>선수 성적 표</b>. 매치 전체를 선수 한 명당 한 줄로 접는다.
     *
     * <h2>왜 세트별이 아니라 합계인가</h2>
     *
     * 사실 블록은 세트마다 여덟 줄이라 5세트면 40줄이다. 그걸 댓글 프롬프트에 그대로
     * 넣으면 토큰이 두 배가 되고(무료 티어 TPM 8,000 을 넘긴다), 무엇보다 <b>댓글은
     * 세트별 세부까지 인용하지 않는다.</b> "누가 몇 킬 했냐" 수준이면 충분하다.
     *
     * <h2>가장 큰 값에 별표를 단다</h2>
     *
     * 커뮤니티가 물고 늘어지는 것은 극단값이다 — 제일 많이 죽인 선수, 제일 많이 죽은 선수.
     * 그걸 우리가 표시해 주면 모델이 스스로 비교하다 틀릴 일이 없다.
     */
    private static String playerTotals(MatchBrief brief, NameBook names) {
        record Totals(String team, int kill, int death, int assist, int dealt) {
        }
        Map<String, Totals> byPlayer = new LinkedHashMap<>();                   // 1. 선수 이름 → 합계

        for (MatchBrief.SetBrief set : brief.sets()) {
            for (MatchBrief.PlayerLine line : set.players()) {
                String team = teamName(line.blue() ? brief.blueTeamId() : brief.redTeamId(), names);
                String who = names.athleteName(line.athleteId());
                if (who == null || who.isBlank()) {                             // 2. 이름을 모르면 표에 안 넣는다
                    continue;                                                   //    댓글이 번호로 부를 일은 없다
                }
                Totals now = byPlayer.getOrDefault(who, new Totals(team, 0, 0, 0, 0));
                byPlayer.put(who, new Totals(team,                              // 3. 세트를 가로질러 더한다
                        now.kill() + line.kill(),
                        now.death() + line.death(),
                        now.assist() + line.assist(),
                        now.dealt() + line.dealing()));
            }
        }
        if (byPlayer.isEmpty()) {
            return "(선수 기록 없음)";
        }

        int mostKills = byPlayer.values().stream().mapToInt(Totals::kill).max().orElse(0);
        int mostDeaths = byPlayer.values().stream().mapToInt(Totals::death).max().orElse(0);

        StringBuilder out = new StringBuilder();
        byPlayer.forEach((who, t) -> {
            out.append(t.team()).append(" | ").append(who)
                    .append(" | ").append(t.kill()).append('/')
                    .append(t.death()).append('/').append(t.assist())
                    .append(" | 가한피해 ").append(t.dealt());
            if (t.kill() == mostKills && mostKills > 0) {                       // 4. 극단값에 별표
                out.append("  ★최다 킬");
            }
            if (t.death() == mostDeaths && mostDeaths > 0) {
                out.append("  ★최다 데스");
            }
            out.append('\n');
        });
        return out.toString();
    }

    /**
     * 총평 기사에 달릴 댓글.
     *
     * <p>매치 댓글과 달리 <b>선수 성적 표를 안 준다.</b> 총평에 선수가 없으므로 줄 것이
     * 없고, 없는 것을 주면 댓글이 기사에 없는 얘기를 하게 된다.
     */
    public static StoryRequest roundComments(RoundBrief brief, NameBook names, String article) {
        Objects.requireNonNull(brief, "brief");

        String system = """
                너는 한국 e스포츠 커뮤니티의 여러 유저다. 오늘 경기 총평 글에 댓글을 단다.

                말투와 태도는 평소 갤 그대로다 — 반말, 구어체, 짧게. 번역투 금지.
                은어를 문장 끝에 기계적으로 붙이지 마라. 감정이 실릴 때만 나온다.
                뻔한 밈("실화냐", "레전드")을 습관적으로 반복하지 마라.
                까는 쪽·쉴드 치는 쪽·중립·딴소리가 섞여야 한다.

                지켜야 할 것:
                - 기사에 있는 팀과 스코어만 쓴다. <b>선수 이름을 지어내지 마라</b> —
                  오늘 총평에는 선수 얘기가 없다.
                - 확실하지 않으면 숫자를 빼고 두루뭉술하게 써라.

                형식 — JSON 배열만 출력한다:
                [{"author": "ㅇㅇ(118.35)", "content": "댓글", "sub_comments": []}]
                - `author` 는 디시 유동닉 꼴이다: `ㅇㅇ(123.45)`. 고정닉을 섞어도 된다.
                - 같은 유동닉을 여러 댓글에 다시 등장시켜라.
                - `sub_comments` 는 대부분 빈 배열이다. 싸움이 붙은 한둘에만 1~3개.
                - 한 댓글은 두 문장을 넘지 않는다.
                """;

        String user = """
                아래 총평 기사에 달린 댓글 %d개를 써라.

                --- 기사 ---
                %s

                --- 오늘의 결과 (틀리면 안 되는 것) ---
                %s""".formatted(ROUND_COMMENTS, article == null ? "" : article, brief.render(names));

        return new StoryRequest(system, user, ROUND_COMMENTS * 90 + 400, 1.15);
    }

    /** 총평 댓글 수. 매치 댓글보다 적다 — 총평은 개별 경기만큼 감정을 자극하지 않는다. */
    private static final int ROUND_COMMENTS = 8;

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
