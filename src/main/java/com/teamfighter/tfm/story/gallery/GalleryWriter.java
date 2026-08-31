package com.teamfighter.tfm.story.gallery;

import com.teamfighter.tfm.story.MatchBrief;
import com.teamfighter.tfm.story.NameBook;
import com.teamfighter.tfm.story.StoryClient;
import com.teamfighter.tfm.story.StoryProperties;
import com.teamfighter.tfm.story.StoryRequest;
import com.teamfighter.tfm.story.dao.GalleryDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 기사 한 편 아래에 갤러리 한 페이지를 만든다. <b>호출 넷, 글 스물.</b>
 *
 * <h2>조각 하나가 실패해도 페이지는 산다</h2>
 *
 * 네 번의 호출 중 하나가 깨져도(파싱 실패 · 타임아웃 · 빈 응답) <b>나머지로 저장한다.</b>
 * 열다섯 개짜리 게시판은 성립하고, 0개짜리는 성립하지 않기 때문이다.
 *
 * <p>대신 그 사실을 지운 채 저장하지는 않는다 — {@code gallery_batch.chunks} 에
 * 몇 조각을 <b>시도했는지</b>가 남으므로, 글 수가 할당보다 적으면 어디선가 실패했다는
 * 뜻이 된다. 로그도 남긴다. <b>조용히 적게 나오는 것</b>이 이 구조의 유일한 위험이고,
 * 그래서 그것만은 읽을 수 있게 둔다.
 *
 * <h2>앞 조각의 제목을 뒤 조각에 넘긴다</h2>
 *
 * 그러지 않으면 네 조각이 서로를 모른 채 같은 각도의 글을 쓴다. 제목만 넘기는 이유는
 * 본문까지 넘기면 토큰이 조각마다 불어나기 때문이다 — 제목이면 겹침을 피하고
 * 흐름을 잇는 데 충분하다. 모드도 같은 것을 넘긴다({@code recentTitles}).
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> {@code ArticleWriter} 와 같은 이유다 —
 * 이 메서드는 대부분의 시간을 네트워크에서 보내고, 그동안 DB 연결을 붙들고 있으면
 * 커넥션 풀이 모델 응답 속도에 묶인다. 저장은 {@link GalleryDao#save} 안에서 한 번에 끝난다.
 */
public class GalleryWriter {

    private static final Logger log = LoggerFactory.getLogger(GalleryWriter.class);

    private final StoryClient client;
    private final GalleryDao gallery;
    private final StoryProperties properties;

    public GalleryWriter(StoryClient client, GalleryDao gallery, StoryProperties properties) {
        this.client = client;
        this.gallery = gallery;
        this.properties = properties;
    }

    /**
     * 기사 하나에 갤러리 한 페이지를 붙인다.
     *
     * @param articleId 앵커 기사. 갤러가 읽은 것이 이 기사다
     * @param headline  기사 제목. SCRAP 유형이 퍼온다
     * @param body      기사 본문
     * @param brief     그 매치의 사실. 선수별 표가 여기서 나온다
     * @param tags      맥락 태그(순위·연패·라이벌). 민심의 근거다
     * @return 저장된 {@code batch_id}. 네 조각이 <b>전부</b> 실패하면 {@link Optional#empty()} —
     *         예외가 아니다. 화면은 "이번엔 아무것도 못 건졌다" 를 말하면 된다
     */
    public Optional<Long> write(long articleId, String headline, String body,
                                MatchBrief brief, NameBook names, List<String> tags) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(tags, "tags");

        List<GalleryChunk> chunks = GalleryChunk.page();
        List<GalleryPost> posts = new ArrayList<>();
        List<String> earlierTitles = new ArrayList<>();

        for (GalleryChunk chunk : chunks) {                                     // 1. 조각을 순서대로 부른다. 순서가 곧 갤의 시간이다
            StoryRequest request = GalleryPrompts.chunk(
                    chunk, brief, names, headline, body, tags, earlierTitles);

            List<GalleryPost> written = callChunk(chunk, request);              // 2. 실패하면 빈 목록이다 — 다음 조각은 계속 돈다
            posts.addAll(written);
            written.forEach(post -> earlierTitles.add(post.title()));           // 3. 뒤 조각이 이 제목들을 보고 겹침을 피한다
        }

        if (posts.isEmpty()) {
            log.warn("기사 {}: 조각 {}개가 전부 실패했다 — 저장할 글이 없다",
                    articleId, chunks.size());
            return Optional.empty();
        }

        int expected = chunks.stream().mapToInt(GalleryChunk::size).sum();
        if (posts.size() < expected) {                                          // 4. 적게 나온 것을 조용히 넘기지 않는다
            log.warn("기사 {}: 글이 {}개 나왔다 (요청 {}개). 조각 하나가 깨졌거나 모델이 덜 썼다",
                    articleId, posts.size(), expected);
        }

        long batchId = gallery.save(articleId, properties.model(), chunks.size(), posts);
        log.info("기사 {}: 갤러리 {} 를 만들었다 (글 {}편, 댓글 {}개)",
                articleId, batchId, posts.size(),
                posts.stream().mapToInt(p -> p.comments().size()).sum());
        return Optional.of(batchId);
    }

    /**
     * 조각 하나를 부르고 파싱한다. <b>어떤 실패도 이 메서드 밖으로 안 나간다.</b>
     *
     * <p>여기서 던지면 앞 조각이 이미 뽑아 둔 글까지 함께 사라진다. 그건 실패 하나가
     * 페이지 전체를 죽이는 것이고, 조각을 나눈 이유(D72)를 정면으로 거스른다.
     */
    private List<GalleryPost> callChunk(GalleryChunk chunk, StoryRequest request) {
        try {
            String raw = client.complete(request);
            List<GalleryPost> posts = GalleryPosts.parse(raw, chunk);
            if (posts.isEmpty()) {
                log.warn("조각 \"{}\": 응답에서 글을 하나도 못 건졌다 ({}자)",
                        chunk.mood(), raw == null ? 0 : raw.length());
            }
            return posts;
        } catch (RuntimeException e) {
            log.warn("조각 \"{}\" 실패 — 나머지 조각은 계속 간다: {}", chunk.mood(), e.toString());
            return List.of();
        }
    }
}
