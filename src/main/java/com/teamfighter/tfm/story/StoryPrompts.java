package com.teamfighter.tfm.story;

import java.util.ArrayList;
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
    public static StoryRequest article(MatchBrief brief, NameBook names, Notability notability, List<String> contextTags) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(notability, "notability");
        Objects.requireNonNull(contextTags, "contextTags");

        String system = """
                너는 팀파이트 매니저 리그의 경기 기사를 쓰는 기자다. 한국어로 쓴다.

                이 게임이 어떤 게임인지 (여기 없는 개념은 존재하지 않는다):
                - 4대4 팀 단위 전투다. 밴픽으로 챔피언을 고르고, 좁은 투기장에서
                  한타 한 번으로 세트가 갈린다.
                - 라인이 없다. 그러므로 라인전·미드·탑·정글·바텀·라인 스왑·CS·오브젝트·
                  용·바론·타워·와드·로밍 같은 말은 이 게임에 존재하지 않는다. 쓰지 마라.
                - 승부를 가르는 것은 밴픽 조합과 스킬 연계다. 그 밖의 원인은 우리가
                  관측하지 못한다.

                지켜야 할 기본 규칙:
                - 아래 「사실」에 있는 숫자만 쓴다. 없는 숫자는 쓰지 않는다.
                - 「사실」에 없는 챔피언·팀·선수를 등장시키지 않는다.
                - 밴된 챔피언이 활약했다고 쓰지 않는다. 밴은 나오지 않았다는 뜻이다.
                - 경기 밖의 사건(이적, 부상, 관중 반응, 과거 전적)을 지어내지 않는다.

                지표의 뜻 — 절대 바꿔 부르지 마라:
                - `가한피해` = 상대에게 입힌 피해. 공격 지표.
                - `받은피해` = 상대에게 맞은 피해. 탱킹 지표.
                - `힐량` = 아군을 회복시킨 양.
                - 확실하지 않으면 수치를 쓰지 말고 두루뭉술하게 넘겨라.

                ★★★ 기사 작성 구조 (반드시 지킬 것) ★★★

                1. 첫 문단: 대회 정보와 [맥락] 중심의 메인 테마 서술 (가장 비중 있게)
                - 이 경기가 어떤 대회(정규 리그, 플레이오프, 월드 챔피언십 등)인지, 그리고
                  [맥락] 줄에 주어진 "순위 변동"과 "연승/연패 단절" 정보를 바탕으로
                  이 경기가 가지는 무게감과 서사를 집중적으로 서술하라.
                - 세부 전투 내용보다 "이 승리로 팀 A가 1위로 도약했다" 식의 거시적 맥락이 기사의 핵심이다.

                2. 중간 문단: 전체 경기 흐름 요약 (단 1~2줄로 압축)
                - 세트별 상세 전개나 픽/밴 나열을 절대 하지 마라. "1세트에 이어 2세트에서도..." 식의 서술 금지.
                - 대신 경기 전체 흐름을 1~2줄로만 요약해라. (예: "1세트를 압도적인 킬 차이로 잡은 A팀이 그 기세를 몰아 세트 스코어 2:0으로 깔끔하게 마무리했다.")

                3. 마지막 문단: 수훈 선수 (MVP) 선정 (1~2줄)
                - 「선수 합계」 표에서 고른다. 그 표는 <b>매치 전체를 이미 더해 둔 것</b>이다.
                  세트별 숫자를 네가 다시 더하지 마라 — 그러면 같은 값을 두 번 적게 된다.
                - 줄 맨 앞이 `[승]` 인 선수 중에서만 고른다. `[패]` 는 수훈 선수가 아니다.
                - ★ 표시가 근거다. ★최다 킬 · ★최다 가한피해 · ★최다 힐량이 붙은 선수가 후보다.
                  ★최다 데스와 ★최다 받은피해는 <b>수훈의 근거가 아니다</b> — 많이 죽은 것과
                  많이 맞아준 것은 다른 말이고, 표만 보고는 어느 쪽인지 못 가른다.
                - <b>그 선수가 쓴 챔피언을 반드시 이름으로 적는다.</b> 표의 세 번째 칸에 있다.
                - 숫자는 <b>하나만</b> 고른다. "10898의 가한피해와 12468의 가한피해" 처럼
                  여러 값을 늘어놓지 마라. 합계 한 개면 충분하다.

                문체:
                - 스포츠 기사체. 담백하게 쓰고 과장하지 않는다.
                - 제목 한 줄 뒤에 빈 줄, 그다음 본문.
                - 소제목·목록·마크다운을 쓰지 않는다. 문단만 쓴다.
                """;

        // 선수 합계 표를 같이 준다. 세트별 줄만 주면 모델이 매치 합계를 <b>스스로 더해야
        // 하고</b>, 실물에서 그걸 못 했다 — 두 세트의 딜을 "10898의 가한피해와 12468의
        // 가한피해" 로 두 번 적었고 챔피언은 아예 빠졌다. 댓글·갤러리 프롬프트는 이미
        // 이 표를 쓰고 있었다. 기사만 안 쓰고 있었던 것이 구멍이었다.
        String user = """
                아래 사실만으로 기사를 써라. 분량은 %d문단이다.

                --- 사실 ---
                %s
                --- 선수 합계 (매치 전체. 수훈 선수는 여기서 고른다) ---
                %s""".formatted(
                notability.paragraphs(),
                BriefRenderer.render(brief, names, contextTags),
                playerTotals(brief, names));

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
    /**
     * 선수별 합계 표. 세트를 가로질러 더하고 극단값에 별표를 붙인다.
     *
     * <p><b>갤러리 프롬프트도 이걸 쓴다</b>({@code gallery/GalleryPrompts}) — 그래서 public 이다.
     * 표를 눈앞에 두는 것이 선수와 기록이 섞이는 것을 막는 유일한 장치이고, 두 벌로 두면
     * 한쪽만 고쳐진다.
     */
    public static String playerTotals(MatchBrief brief, NameBook names) {
        // 합계는 brief 가 낸다. 여기서 다시 더하면 대조(FactCheck)가 보는 숫자와
        // 갈라지고, 그때 우리가 준 숫자를 우리가 지적하게 된다.
        List<MatchBrief.AthleteTotals> totals = brief.athleteTotals();
        boolean blueWonMatch = brief.blueScore() > brief.redScore();

        record Row(String team, String who, boolean won, MatchBrief.AthleteTotals t) {
        }
        List<Row> rows = new ArrayList<>();
        for (MatchBrief.AthleteTotals t : totals) {
            String who = names.athleteName(t.athleteId());
            if (who == null || who.isBlank()) {                                 // 이름을 모르면 표에 안 넣는다
                continue;                                                       // 기사가 번호로 부를 일은 없다
            }
            rows.add(new Row(teamName(t.blue() ? brief.blueTeamId() : brief.redTeamId(), names),
                    who, t.blue() == blueWonMatch, t));
        }
        if (rows.isEmpty()) {
            return "(선수 기록 없음)";
        }

        int mostKills = rows.stream().mapToInt(r -> r.t().kill()).max().orElse(0);
        int mostDeaths = rows.stream().mapToInt(r -> r.t().death()).max().orElse(0);
        // 수훈 선수를 고르려면 딜·탱·힐의 최댓값도 있어야 한다. 안 주면 모델이
        // 스무 줄을 눈으로 비교하다 틀린다 — 실물 기사가 두 세트의 딜을 "10898의
        // 가한피해와 12468의 가한피해" 로 두 번 적은 것이 그 증상이다.
        int mostDealt = rows.stream().mapToInt(r -> r.t().dealing()).max().orElse(0);
        int mostTaken = rows.stream().mapToInt(r -> r.t().tanking()).max().orElse(0);
        int mostHealed = rows.stream().mapToInt(r -> r.t().healing()).max().orElse(0);

        StringBuilder out = new StringBuilder();
        for (Row r : rows) {
            MatchBrief.AthleteTotals t = r.t();
            out.append(r.won() ? "[승]" : "[패]").append(' ')                   // 이긴 팀인지를 줄 맨 앞에
                    .append(r.team()).append(" | ").append(r.who())
                    // 챔피언을 선수 <b>옆에</b> 붙인다 (D80). 전에는 갤러리 프롬프트에
                    // 챔피언이 아예 없었고, 그래서 갤 글이 "Exorcist" 같은 이름을
                    // 학습 지식에서 지어냈다 — 이 매치에 없는 챔피언이 나오는 것도,
                    // 영어로 나오는 것도 같은 구멍에서 왔다.
                    .append(" | ").append(t.champions().stream()
                            .map(names::championName)
                            .collect(java.util.stream.Collectors.joining("·")))
                    .append(" | ").append(t.kill()).append('/')
                    .append(t.death()).append('/').append(t.assist())
                    .append(" | 가한피해 ").append(t.dealing())
                    .append(" · 받은피해 ").append(t.tanking())
                    .append(" · 힐량 ").append(t.healing());
            if (t.kill() == mostKills && mostKills > 0) {                       // 극단값에 별표
                out.append("  ★최다 킬");
            }
            if (t.death() == mostDeaths && mostDeaths > 0) {
                out.append("  ★최다 데스");
            }
            if (t.dealing() == mostDealt && mostDealt > 0) {
                out.append("  ★최다 가한피해");
            }
            if (t.tanking() == mostTaken && mostTaken > 0) {
                out.append("  ★최다 받은피해");
            }
            if (t.healing() == mostHealed && mostHealed > 0) {
                out.append("  ★최다 힐량");
            }
            out.append('\n');
        }
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

    /** 팀 이름. 모르면 번호를 적는다 (D57). 갤러리 프롬프트도 쓴다. */
    public static String teamName(Integer id, NameBook names) {
        String name = id == null ? null : names.teamName(id);
        if (name != null) {
            return name;
        }
        return id == null ? "팀 미상" : "팀 " + id;
    }
}
