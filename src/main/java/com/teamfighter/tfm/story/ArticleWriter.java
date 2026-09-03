package com.teamfighter.tfm.story;

import com.teamfighter.tfm.story.dao.ArticleDao;
import com.teamfighter.tfm.story.dao.StoryReference;
import com.teamfighter.tfm.story.dao.StoryReferenceDao;

import java.util.List;
import java.util.Objects;

/**
 * 매치 하나로 기사를 만들어 저장한다. {@code story/} 의 네 계층을 <b>이어 붙이기만</b> 한다 —
 * 사실({@link MatchBrief}) → 해석({@link Notability}) → 창작({@link StoryClient}) →
 * 대조({@link FactCheck}) → 저장({@link ArticleDao}).
 *
 * <p><b>여기에 판단을 두지 않는다.</b> 분량도 프롬프트도 대조 규칙도 각 계층이 이미 정했다.
 * 오케스트레이션이 그 결정을 한 번 더 내리면 두 곳이 갈리고, 갈린 쪽은 테스트가 없다.
 *
 * <p><b>{@code @Transactional} 이 없다. 일부러 그렇다.</b> 모델 호출이 두 번 들어 있고
 * 한 번에 수십 초가 걸린다. 이 메서드를 트랜잭션으로 감싸면 그동안 DB 연결을 붙잡고 있게 되는데,
 * 커넥션 풀이 열 개뿐이라 기사 열 편을 동시에 쓰면 적재도 화면도 함께 멈춘다.
 * 트랜잭션은 {@link ArticleDao#save} 안에만 있으면 된다 — 한 기사의 본문·댓글·지적이
 * 함께 저장되는 것이 지켜야 할 전부다. {@code IngestServiceImpl} 이 같은 이유로 같은 선택을 했다.
 *
 * <p><b>실패를 삼키지 않는다.</b> 호출이 실패하면 예외가 그대로 올라가고 아무것도 저장되지 않는다.
 * 댓글만 실패했을 때 기사만 저장하는 길도 있었지만 그러지 않았다 — 댓글 없는 기사가 화면에
 * 정상으로 보이면 "왜 댓글이 없지" 를 사람이 나중에 따져야 한다. 다시 부르면 갱신이 되므로
 * (유일 키가 매치 신원이다) 실패한 기사를 재시도하는 비용은 한 편치뿐이다.
 *
 * <p><b>{@code @Service} 가 아니다.</b> {@link StoryConfiguration} 이 플래그를 보고 등록한다 —
 * 꺼진 설치에서는 이 클래스의 빈이 아예 없다 (D61 결정 4).
 */
public class ArticleWriter {

    private final StoryClient client;
    private final ArticleDao articles;
    private final StoryReferenceDao references;
    private final StoryProperties properties;

    public ArticleWriter(StoryClient client, ArticleDao articles,
                         StoryReferenceDao references, StoryProperties properties) {
        this.client = client;
        this.articles = articles;
        this.references = references;
        this.properties = properties;
    }

    /**
     * 매치 하나를 기사로 쓴다. 이미 있으면 덮는다.
     *
     * @param context {@link SeasonBook#contextFor} 가 준 해석 재료. 모르는 축은 모르는 채로 온다
     * @return 저장된 {@code article_id}
     */
    public long write(int slotId, MatchBrief brief, NotabilityContext context) {
        return write(references.load(slotId), brief, context, List.of());
    }

    /**
     * 이름표를 이미 들고 있을 때. <b>여러 편을 쓸 때는 이쪽을 쓴다</b> —
     * {@link StoryReferenceDao} 가 매치마다 다시 읽지 말라고 만든 값이다.
     */
    public long write(StoryReference reference, MatchBrief brief, NotabilityContext context) {
        return write(reference, brief, context, List.of());
    }

    /**
     * 맥락 태그까지 넘긴다.
     *
     * @param contextTags {@code SeasonBook.tagsFor} 가 준 <b>계산된 사실</b>. 형용사가 아니다
     */
    public long write(StoryReference reference, MatchBrief brief, NotabilityContext context,
                      List<String> contextTags) {
        return write(reference, brief, context, contextTags, Progress.NONE);
    }

    /**
     * 진행 상황을 흘리며 쓴다.
     *
     * <p>호출 둘이 20~30초를 쓰고 429 를 맞으면 더 간다. 그동안 화면에 아무 신호가
     * 없으면 <b>도는 중인지 멈춘 것인지 구분되지 않는다</b> — 갤러리가 먼저 겪고
     * 고친 병인데 기사에는 그 장치가 없었다 (D81).
     */
    public long write(StoryReference reference, MatchBrief brief, NotabilityContext context,
                      List<String> contextTags, Progress progress) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(contextTags, "contextTags");
        Objects.requireNonNull(progress, "progress");

        Notability notability = Notability.of(brief, context);                  // 1. 해석 — 분량(문단 수)을 정한다. 이유 문자열은 프롬프트로 안 간다 (D66 ②)
        String briefText = BriefRenderer.render(brief, reference, contextTags); // 2. 사실 + 맥락 태그. 프롬프트와 화면이 이 같은 문자열을 쓴다

        progress.at("기사를 쓰는 중", 0, 2);
        String raw = client.complete(                                           // 3. 창작 1 — 기사. 프롬프트는 "제목 한 줄, 빈 줄, 본문" 을 요구한다
                StoryPrompts.article(brief, reference, notability, contextTags));
        String[] split = ArticleDraft.splitHeadline(raw);                       // 4. [제목, 본문] 두 칸. 형식을 어긴 답이면 제목 칸이 빈 문자열이다
        String body = split[1];
        String headline = split[0].isBlank()                                    // 5. 제목이 비면 스코어라인으로 짓는다 — 지어낸 말을 안 넣는다
                ? fallbackHeadline(brief, reference) : split[0];

        FactCheckResult factCheck = FactCheck.run(                              // 6. 대조 — 제목을 떼기 전 원문(raw)을 본다. 제목에 든 숫자도 잡으려고
                brief, reference,
                reference.championNames(),                                      //    챔피언 어휘는 한글이다. 렌더러가 한글로 쓰므로 코드를 쓰면 검사가 조용히 죽는다 (D66 · D80)
                reference.teamNames(),
                reference.athleteNames(),                                       //    선수 이름을 넘겨야 관계 검사(선수↔챔피언)가 돈다
                contextTags,                                                    //    태그의 숫자도 "아는 숫자" 다
                raw);

        progress.at("댓글을 다는 중", 1, 2);
        List<ArticleDraft.CommentLine> comments = StoryComments.parse(          // 7. 창작 2 — 댓글. JSON 배열을 닉네임·대댓글까지 살려 편다
                client.complete(
                        StoryPrompts.comments(brief, reference, notability, body, contextTags)));

        progress.at("대조하고 저장하는 중", 2, 2);

        ArticleDraft draft = ArticleDraft.of(                                   // 8. 저장 꼴로. fact_status 는 안 넘긴다 — 타입이 findings 로 계산한다
                reference.slotId(), brief, notability,
                reference.teamId(brief.blueTeamId()),                           //    세이브 팀 번호 → DB 팀 번호. 모르는 번호면 여기서 던진다
                reference.teamId(brief.redTeamId()),
                headline, body, briefText, properties.model(), comments, factCheck);

        return articles.save(draft);                                            // 9. 기사·댓글·지적이 한 트랜잭션으로 들어간다 (업서트)
    }

    /**
     * 하루치 총평을 쓴다. 이미 있으면 덮는다.
     *
     * <h2>매치 기사와 같은 뼈대, 다른 재료</h2>
     *
     * 호출 두 번(기사·댓글) · 대조 · 저장이라는 순서는 같다. 다른 것은 재료다 —
     * 선수도 픽도 없고 매치당 한 줄뿐이라, 대조도 그만큼 얕다(팀과 숫자만 본다).
     *
     * <p>{@code @Transactional} 이 없는 이유도 같다. 모델 호출이 안에 있다.
     *
     * @return 저장된 {@code article_id}
     */
    public long writeRoundSummary(StoryReference reference, RoundBrief brief) {
        return writeRoundSummary(reference, brief, Progress.NONE);
    }

    /** 진행 상황을 흘리며 쓴다. 매치 기사와 같은 이유다 (D81). */
    public long writeRoundSummary(StoryReference reference, RoundBrief brief, Progress progress) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(progress, "progress");

        String briefText = brief.render(reference);                             // 1. 하루치 사실. 매치당 한 줄

        progress.at("총평을 쓰는 중", 0, 2);
        String raw = client.complete(StoryPrompts.roundSummary(brief, reference));   // 2. 창작 1 — 총평
        String[] split = ArticleDraft.splitHeadline(raw);                       // 3. [제목, 본문]
        String body = split[1];
        String headline = split[0].isBlank()                                    // 4. 제목이 비면 날짜로 짓는다
                ? brief.season() + "시즌 " + brief.day() + "일차 " + brief.results().size() + "경기"
                : split[0];

        FactCheckResult factCheck = FactCheck.runRound(                         // 5. 대조 — 그날 없던 팀·숫자만 본다
                brief, reference, reference.teamNames(), raw);

        progress.at("댓글을 다는 중", 1, 2);
        List<ArticleDraft.CommentLine> comments = StoryComments.parse(          // 6. 창작 2 — 댓글
                client.complete(StoryPrompts.roundComments(brief, reference, body)));

        progress.at("대조하고 저장하는 중", 2, 2);

        ArticleDraft draft = ArticleDraft.ofRound(                              // 7. 팀이 없는 기사 = 총평 (V10)
                reference.slotId(), brief.season(), brief.day(),
                ROUND_NOTABILITY, List.of("하루 총평"),
                headline, body, briefText, properties.model(), comments, factCheck);

        return articles.save(draft);                                            // 8. 매치 기사와 같은 표에 들어간다
    }

    /**
     * 총평의 주목도.
     *
     * <p>고정값이다. 주목도는 <b>매치</b>가 얼마나 볼 만한가를 재는 축이고(접전·순위·라이벌),
     * 하루 전체에는 그 축이 없다. 목록 정렬은 시즌·일 순이라 이 값이 순서를 바꾸지도 않는다.
     * 0 으로 두지 않는 이유는 화면이 그것을 "볼 것 없음" 으로 읽을 수 있어서다.
     */
    private static final double ROUND_NOTABILITY = 0.5;

    /**
     * 모델이 "제목 한 줄, 빈 줄, 본문" 형식을 안 지켰을 때의 제목.
     *
     * <p>던지지 않는 이유는 {@link ArticleDraft#splitHeadline} 이 적어 둔 것과 같다 —
     * 형식 위반은 사실 오류가 아니다. 대신 <b>스코어라인으로만</b> 짓는다. brief 에 있는 숫자와
     * 이름뿐이므로 지어낸 말이 한 마디도 안 들어가고, 대조가 걸 것도 없다.
     */
    private static String fallbackHeadline(MatchBrief brief, StoryReference reference) {
        String blue = teamLabel(brief.blueTeamId(), reference);
        String red = teamLabel(brief.redTeamId(), reference);
        return blue + " " + brief.blueScore() + " - " + brief.redScore() + " " + red;
    }

    private static String teamLabel(Integer gameTeamId, StoryReference reference) {
        String name = reference.teamName(gameTeamId);
        return name != null ? name : "팀 " + gameTeamId;
    }
}
