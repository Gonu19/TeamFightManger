package com.teamfighter.tfm.story.gallery;

import com.teamfighter.tfm.story.MatchBrief;
import com.teamfighter.tfm.story.NameBook;
import com.teamfighter.tfm.story.StoryPrompts;
import com.teamfighter.tfm.story.StoryRequest;

import java.util.List;
import java.util.Objects;

/**
 * 갤러리 한 조각을 부르는 프롬프트. 레퍼런스 모드의
 * {@code GAME_IDENTITY} + {@code defaultPromptBase} 를 우리 재료에 맞춰 옮긴 것이다.
 *
 * <h2>모드에서 무엇을 그대로 가져왔나</h2>
 *
 * <ul>
 *   <li><b>게임 정체성 블록</b> — 우리도 같은 병을 앓는다. 세이브에 든 선수 이름이
 *       실제 프로 선수와 같은 꼴이라, 그냥 두면 모델이 "그건 딴 게임 얘기 아니냐" 로
 *       샌다. 이 게임 안의 존재로 못박아야 갤이 성립한다</li>
 *   <li><b>하이라이트 발굴 규칙</b> — 최종 스코어만 보고 뭉뚱그리지 말고 선수별 수치에서
 *       장면을 파내라. 이것이 세트 나열을 막는 진짜 장치다</li>
 *   <li><b>민심 균형 규칙</b> — 스무 개가 전부 억까면 갤이 아니다. 찬양·훈훈글·쉴드가 섞인다</li>
 *   <li><b>성적별 민심 규칙</b> — 1위 팀이 한 번 진 것과 꼴찌가 또 진 것에 같은 반응이 안 나온다</li>
 *   <li><b>말투·댓글 다양성 규칙</b> — 이미 우리 댓글 프롬프트에 있던 것과 거의 같다</li>
 * </ul>
 *
 * <h2>무엇을 안 가져왔나</h2>
 *
 * 모드는 연봉·팬 수 같은 값을 데이터로 넘겨 이적 떡밥을 만든다. <b>우리는 그 값이
 * 없다.</b> 없는 값을 넘기는 대신 "연봉은 안 준다, 스탯만 근거로 삼아라" 를 명시한다 —
 * 안 그러면 모델이 연봉을 지어내고, 그건 D71 이 허용한 예외(조회수·추천수) 밖이다.
 *
 * <p>모드의 [이슈] 뉴스는 <b>가져왔다</b>(D73). D72 는 그 자리에 우리 매치 기사를 넣었지만,
 * 그러면 기사를 먼저 써야 갤러리를 만들 수 있어서 기사가 관문이 됐다. 이슈는 경기와 무관한
 * 리그 전체의 소문이라 매치 기사가 대신할 수 있는 것도 아니었다 — 둘은 다른 것이다.
 */
public final class GalleryPrompts {

    /**
     * 모든 호출 앞에 붙는 게임 정체성. 모드의 {@code GAME_IDENTITY} 를 옮겼다.
     *
     * <p><b>이 블록이 없으면 갤이 자기 게임을 부정한다.</b> 실물 데이터의 선수 이름은
     * 세이브의 이름 풀에서 오는데 그게 실제 프로 선수 이름과 겹친다. 그대로 두면
     * 모델이 "이건 딴 게임 선수인데 왜 여기서 말하냐" 는 반응을 쓰기 시작하고,
     * 그 한 줄이 갤 전체를 가짜로 만든다.
     */
    private static final String IDENTITY = """
            [게임 세계관 — 반드시 지켜라]
            - 이곳은 '팀파이트 매니저'라는 e스포츠 시뮬레이션 게임의 팬 갤러리다.
            - 이 세계관 안에서는 갤러리에 나오는 모든 챔피언·구단·선수가 이 게임의
              챔피언·구단·선수다. 다른 게임에서 온 것처럼 보이는 이름이 나와도 마찬가지다.
              유저가 창작마당에서 이름을 가져다 쓰기 때문이다.
            - 절대 금지: "그건 딴 게임 아니냐", "여긴 그 게임 갤 아니다", "저 선수가
              왜 여기 나오냐" 는 식의 반응. 그 순간 완전히 틀린 반응이 된다.
            - 어떤 이름이 나오든 이 게임에 실재하는 것으로 자연스럽게 취급하라.
              그 이름의 출처를 따지지 마라.
            - 이 게임에 없는 용어를 쓰지 마라: 라인전 · 정글 · 미드 · 봇 · 오브젝트 · 드래곤.

            [지표 정의 — 절대 혼동 금지]
            - 가한피해 = 그 선수가 상대에게 '가한' 피해량 (공격 지표)
            - 받은피해 = 그 선수가 상대에게서 '받은' 피해량 (탱킹 지표)
            - 힐량     = 그 선수가 '회복시킨' 양
            - K/D/A    = 킬 / 데스 / 어시스트
            지표를 바꿔 부르는 것은 명백한 오류다. 딜러의 가한피해를 힐량이라 하지 마라.

            [불확실할 때]
            아래 표에서 확실히 확인되는 이름과 수치만 콕 집어 말하라. 확실하지 않으면
            숫자를 지어내지 말고 두루뭉술하게 넘겨라("딜 좀 뽑았다" 수준).
            틀린 수치를 자신 있게 말하는 것이 이 게시판에서 가장 나쁜 실패다.
            """;

    /**
     * 게시글 하나에 잡는 출력 토큰. 본문 세 문단 + 댓글 대여섯 개가 그쯤이다.
     */
    private static final int TOKENS_PER_POST = 800;

    /**
     * 조각 하나의 출력 하한.
     *
     * <p>조각이 작을 때(글 다섯 개) 상한이 빠듯하면 추론 모델이 <b>생각으로만 다 쓰고</b>
     * 본문이 빈 채로 돌아온다 — 실물에서 댓글 프롬프트가 그렇게 실패했다.
     */
    private static final int MIN_TOKENS = 2_000;

    /** 이슈 본문 중 조각 프롬프트에 실어 보낼 앞머리 길이. 모드와 같은 120자다. */
    private static final int ISSUE_DIGEST_CHARS = 120;

    /** 이슈 여섯 개의 출력 상한. 본문이 8~15문장이라 넉넉히 잡는다. */
    private static final int ISSUE_TOKENS = 4_000;

    /**
     * 이슈 취재 초점. 매번 하나를 골라 붙인다.
     *
     * <p>그냥 "이슈 6개" 를 시키면 모델은 매번 비슷한 것을 낸다 — 유형 할당이 없을 때
     * 게시글이 같은 각도로 쏠리는 것과 같은 실패다. 모드의 {@code angles} 를 옮겼다.
     */
    public static final List<String> ISSUE_ANGLES = List.of(
            "이번엔 이적시장과 FA 관련 이슈에 무게를 실어라",
            "이번엔 신인·유망주와 세대교체 관련 이슈를 부각하라",
            "이번엔 감독·코칭스태프와 프런트 관련 이슈를 부각하라",
            "이번엔 선수 개인의 방송·예능·사생활 관련 이슈를 부각하라",
            "이번엔 팀 간 라이벌 구도와 대회 판도 관련 이슈를 부각하라",
            "이번엔 메타·패치·밴픽 트렌드 관련 분석 이슈를 부각하라",
            "이번엔 하위권 팀의 반등이나 상위권 팀의 위기를 부각하라");

    private GalleryPrompts() {
    }

    /**
     * 조각 하나를 부르는 요청.
     *
     * @param chunk  이번에 뽑을 유형과 개수. 이것이 "다양하게 써라" 를 대신한다
     * @param issues 이 페이지의 이슈. SCRAP 유형이 이걸 퍼온다. 비어 있어도 된다
     * @param earlier 앞 조각들이 이미 쓴 제목. 겹침을 막고 흐름을 잇는다
     */
    public static StoryRequest chunk(GalleryChunk chunk, MatchBrief brief, NameBook names,
                                     List<GalleryIssue> issues,
                                     List<String> contextTags, List<String> earlier) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(issues, "issues");
        Objects.requireNonNull(contextTags, "contextTags");
        Objects.requireNonNull(earlier, "earlier");

        String system = IDENTITY + SYSTEM_RULES;

        String user = """
                [분위기] %s

                이번에 쓸 것은 아래 %d개다. 유형과 개수를 정확히 지켜라.

                --- 이번 조각의 할당 ---
                %s
                --- 경기 결과 (틀리면 안 되는 것) ---
                %s %d - %d %s
                %s%s%s
                --- 선수 성적 (숫자를 쓸 거면 여기서만 가져와라) ---
                %s""".formatted(
                chunk.mood(),
                chunk.size(),
                chunk.describeQuota(),
                StoryPrompts.teamName(brief.blueTeamId(), names), brief.blueScore(),
                brief.redScore(), StoryPrompts.teamName(brief.redTeamId(), names),
                block("이 경기의 맥락", contextTags),
                issueBlock(issues),
                block("이미 올라온 글 (제목이 겹치면 안 된다. 이어지는 흐름으로 써라)", earlier),
                StoryPrompts.playerTotals(brief, names));

        // 온도 1.1 — 유형 할당이 이미 다양성을 강제하므로 댓글(1.15)만큼 올릴 필요가 없다.
        // 더 올리면 JSON 형식 자체가 흔들려 파싱 실패가 는다.
        return new StoryRequest(system, user,
                Math.max(chunk.size() * TOKENS_PER_POST, MIN_TOKENS), 1.1);
    }

    /**
     * 이 페이지의 이슈를 프롬프트에 넣는다. SCRAP 유형이 이걸 퍼와서 반응글을 쓴다.
     *
     * <p>본문은 <b>앞머리만</b> 넘긴다. 이슈 여섯 개의 본문을 통째로 넣으면 조각마다
     * 그만큼이 다시 실려 나가는데, 갤러가 스크랩할 때 필요한 것은 헤드라인과 대강의
     * 내용이지 기사 전문이 아니다. 모드도 120자만 넘긴다.
     */
    private static String issueBlock(List<GalleryIssue> issues) {
        if (issues.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(
                "\n--- 현재 팀파 이슈 (갤러가 이걸 스크랩해 반응글을 쓴다) ---\n");
        for (GalleryIssue issue : issues) {
            String body = issue.body();
            out.append("- [").append(issue.category().label()).append("] ")
                    .append(issue.headline()).append(" :: ")
                    .append(body.length() <= ISSUE_DIGEST_CHARS
                            ? body : body.substring(0, ISSUE_DIGEST_CHARS) + "...")
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * 이슈 여섯 개를 만드는 요청. 모드의 {@code generateIssues} 를 옮겼다.
     *
     * <h2>이 호출만 경기 밖을 본다</h2>
     *
     * 갤 글은 전부 이 경기의 선수별 표에 묶여 있다. 이슈는 그렇지 않다 — 이적설·감독
     * 경질·스캔들은 리그 전체의 소문이고 세이브에 대응하는 값이 없다. <b>전부 지어낸 것</b>이고,
     * 화면이 그렇게 말한다.
     *
     * <h2>매번 다른 각도를 강제한다</h2>
     *
     * 그냥 "이슈 6개" 를 시키면 모델은 매번 비슷한 것을 낸다. 모드는 취재 초점을
     * 무작위로 하나 골라 붙이는데, 그 장치를 그대로 가져왔다 — 이것도 유형 할당과 같은
     * 수법이다. <b>다양성을 부탁하지 않고 배분한다.</b>
     *
     * @param focus  이번 취재 초점. {@link #ISSUE_ANGLES} 중 하나
     * @param recent 이미 나온 헤드라인. 겹치면 안 된다
     */
    public static StoryRequest issues(MatchBrief brief, NameBook names,
                                      String focus, List<String> recent) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(recent, "recent");

        String system = IDENTITY + """

                너는 e스포츠 전문 매체 기자 겸 커뮤니티 이슈 큐레이터다. 이 리그에서 지금
                가장 임팩트 있고 화제성 높은 이슈 딱 6개만 엄선해 만든다.

                [규칙]
                - 6개는 분류가 겹치지 않게 다양하게: 경기 결과 분석, 이적설/FA 루머,
                  감독-선수 불화, 선수 방송 출연, 파워랭킹/전력분석, 팬덤 사건사고를 섞어라.
                - 사소한 소식보다 판을 흔드는 대형 이슈 우선(대형 이적, 우승, 감독 경질,
                  대형 스캔들, 신인 돌풍).
                - headline 은 실제 기사처럼 써라. "[단독]" "[오피셜]" "[루머]" 말머리를
                  적극 활용하고, 어그로성 제목을 환영한다.
                - content 는 8~15문장의 기사 본문이다. 기자 문체로 쓰되 익명 관계자 인용
                  ("팀 사정에 정통한 관계자에 따르면...")과 커뮤니티 여론 인용
                  ("커뮤니티에서는 ~라는 반응이 지배적이다")을 섞어 현실감을 살려라.
                - <b>확정 사실과 루머를 구분하라.</b> 루머는 "~인 것으로 알려졌다",
                  "~라는 후문이다" 로 쓴다. 경기 결과는 단정해도 된다.
                - 전력분석·리그 기사는 <b>아래 선수 성적 표의 실제 수치</b>를 근거로 삼아라.
                  표에 없는 선수 이름과 수치는 만들지 마라.
                - 연봉·계약금·이적료의 <b>구체적 금액</b>은 쓰지 마라. 우리가 그 값을 모른다.

                [출력 형식 — JSON 배열만. 앞뒤에 다른 말을 붙이지 마라]
                [{"category": "TRANSFER", "headline": "제목", "content": "본문", "date": "08.14"}]
                - category 는 다음 중 하나다:
                  LEAGUE(리그) · TRANSFER(이적설) · SCANDAL(스캔들) ·
                  BROADCAST(방송) · ANALYSIS(전력분석) · RUMOR(루머)
                - date 는 "MM.DD" 다. 아래 경기 날짜 언저리로 잡아라.
                - 딱 6개만 낸다.
                """;

        String user = """
                [이번 취재 초점] %s

                --- 방금 끝난 경기 ---
                %s %d - %d %s (시즌 %d · %d일차)
                %s
                --- 선수 성적 (수치를 쓸 거면 여기서만 가져와라) ---
                %s""".formatted(
                focus,
                StoryPrompts.teamName(brief.blueTeamId(), names), brief.blueScore(),
                brief.redScore(), StoryPrompts.teamName(brief.redTeamId(), names),
                brief.season(), brief.day(),
                block("이미 나온 이슈 (제목·소재가 겹치면 안 된다. 후속 전개만 허용)", recent),
                StoryPrompts.playerTotals(brief, names));

        // 온도 0.95 — 모드와 같다. 이슈는 사실에 묶일 의무가 거의 없어 높여도 잃을 것이 적지만,
        // 기사 문체를 유지해야 하므로 댓글(1.15)만큼 올리지는 않는다.
        return new StoryRequest(system, user, ISSUE_TOKENS, 0.95);
    }

    /** 값이 있을 때만 붙는 블록. 비면 빈 문자열이라 프롬프트에 빈 제목만 남지 않는다. */
    private static String block(String title, List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n--- ").append(title).append(" ---\n");
        for (String line : lines) {
            out.append("- ").append(line).append('\n');
        }
        return out.toString();
    }

    /** 정체성 뒤에 붙는 본 규칙. 조각이 달라도 이 부분은 같다. */
    private static final String SYSTEM_RULES = """

            너는 한국 e스포츠 커뮤니티 갤러리의 유저 집단 전체다. 한 사람이 아니라
            수십 명이고, 서로 사이가 나쁘다. 아래 경기를 놓고 게시글을 쓴다.

            [하이라이트 발굴 — 이게 이 갤의 핵심이다. 최우선으로 지켜라]
            - 최종 스코어만 보고 뭉뚱그리지 마라. 아래 선수별 표를 실제로 읽고,
              그 안에서 극적인 장면을 발굴해 구체적으로 언급하라.
            - 글마다 표의 실제 수치를 최소 하나는 콕 집어야 한다. 하드캐리한 선수,
              폭발한 가한피해, 세트마다 폼이 출렁인 선수, 딜은 넣었는데 데스가 많은 선수.
            - 선수 이름·챔피언 이름·정확한 수치를 그대로 인용해 현장감을 살려라.
              두루뭉술한 "잘했다/못했다" 금지.
            - 세트를 1세트부터 차례로 나열하지 마라. 글 하나는 한 장면만 문다.
              "1세트는… 이어 2세트도…" 는 갤 글이 아니라 리포트다.

            [민심 균형 — 부정 일변도 금지]
            - 전부 억까·저격·조롱이면 안 된다. 진심 어린 찬양글, 훈훈한 글,
              저격당하는 선수를 데이터로 변호하는 쉴드글이 반드시 섞인다.
            - 이긴 팀 쪽은 축제고, 진 팀 쪽도 100% 초상집은 아니다.
            - 논리가 맞는 말에는 "ㅇㅈ" 하고 수긍하는 사람도 나온다.

            [성적별 민심 — 「이 경기의 맥락」이 주어지면 그에 맞춰라]
            - 상위권·연승 팀이 한 번 졌다: "팀 해체해라" 같은 무지성 쌍욕은 안 나온다.
              당혹감("이게 지네;;"), 상대 리스펙트, 아쉬움이 주류다.
            - 하위권·연패 팀이 또 졌다: 이때 비로소 극대노가 터진다. 쌓인 게 터지는 톤이다.

            [말투]
            - 실제 커뮤니티 말투로 거칠게 써라. ㅅㅂ·시발·존나·지랄·미친·~노 같은
              욕설과 비속어를 자연스럽게 섞어라. 단정적으로 내리꽂아라.
            - 단, 패드립(가족 관련) · 지역/성별/집단 혐오 · 성적인 표현은 절대 금지다.
              욕은 경기력과 상황을 향해서만 해라.
            - 은어를 양념처럼 뿌리지 마라. 감정이 실릴 때만 나온다 — 아무 감정 없는
              문장 끝에 ㅅㅂ 를 붙이면 즉시 가짜로 읽힌다.
            - "실화냐" "레전드" "ㄹㅇ" "팝콘각" 같은 뻔한 밈의 반복 금지.
              이 경기에서만 나올 수 있는 말을 지어내라. 비유는 매번 새로 만들어라.
            - 제목 자체가 드립이어야 한다. "오늘 경기 후기" 같은 무미건조한 제목 금지.

            [댓글]
            - 댓글 수는 그 글의 조회수·추천수에 비례해야 한다. 밋밋한 글엔 두세 개,
              키배 떡밥엔 열 개 넘게.
            - 댓글이 전부 같은 공식(욕 + 팩폭)이면 안 된다. 짧은 무성의 반응, 진지한
              분석, 동의, 딴소리 드립, 질문, 자기 경험담, 근거 있는 반박, 순수 감탄을 섞어라.
            - 잡담글에 경기 분석을 억지로 우겨넣지 마라. 잡담엔 잡담으로 답한다.
            - sub_comments 는 무작위성을 지켜라: 대부분은 빈 배열, 일부에 1~3개,
              키배가 붙은 한둘에만 4~7개.
            - 대댓글에서 상대를 부를 땐 @닉네임 을 쓴다. 같은 유동닉이 여러 번
              재출몰하며 물고 늘어져야 싸움이 성립한다.

            [유동닉]
            - `ㅇㅇ(123.45)` 꼴이다. 아이피 앞 두 마디만 쓴다.
            - 분석글은 `분석노트` 같은 고정닉 느낌으로 써도 된다.
            - 두세 명은 이 판 전체를 관통하는 단골로 둔다. 앞 글에 나온 닉이
              다른 글 댓글에 또 나오면 갤 특유의 생동감이 산다.

            [지켜야 할 것]
            - 누가 이겼는지, 스코어가 몇 대 몇인지는 틀리지 않는다.
            - 선수와 기록을 바꿔 붙이지 않는다. 아래 표에 적힌 값만 인용한다.
            - 표에 없는 선수 이름을 만들지 않는다.
            - 연봉·계약금·팬 수는 우리가 안 준다. 그 숫자를 지어내지 마라.
              이적 떡밥은 스탯만 근거로 삼아라.
            - 실존 인물이나 실제 프로게임단을 끌어들이지 않는다. 이 리그 안에서만 논다.

            [출력 형식 — JSON 배열만. 앞뒤에 다른 말을 붙이지 마라]
            [
              {
                "kind": "LIVE",
                "title": "글 제목",
                "author": "ㅇㅇ(124.50)",
                "content": "본문. 두세 문단 이내로 짧게.",
                "date": "16:40",
                "views": 144,
                "likes": 32,
                "is_concept": false,
                "image_desc": "연승 깨지고 멍때리는 감독.jpg",
                "comments": [
                  {"author": "ㅇㅇ(220.76)", "content": "댓글", "date": "16:41",
                   "sub_comments": []}
                ]
              }
            ]
            - kind 는 위 할당표에 적힌 값을 그대로 쓴다. 지어내지 마라.
            - views·likes 는 그 글이 갤에서 받은 반응이다. 평범한 글은 조회수 50~300 ·
              추천 0~10, 개념글은 조회수 1000 이상 · 추천 30 이상으로 벌려라.
              추천 30 이상이면 자동으로 개념글이 된다.
            - image_desc 는 짤방 파일명이다. 드립글·개념글 성격의 글 절반쯤에만 넣고
              나머지는 빈 문자열로 둬라. 상황이 눈에 그려지는 파일명일수록 좋다.
            - content 는 짧아야 한다. 갤 글은 길지 않다 — 세 문단을 넘기지 마라.
            - date 는 "HH:MM" 이다. 경기가 끝난 뒤 몇 시간에 걸쳐 올라온 것처럼 벌려라.
              댓글의 date 는 그 글보다 뒤여야 한다. <b>연도·날짜는 쓰지 마라</b> —
              게임 안의 날짜라 우리가 모른다.
            """;
}
