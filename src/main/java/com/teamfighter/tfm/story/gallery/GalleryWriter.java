package com.teamfighter.tfm.story.gallery;

import com.teamfighter.tfm.story.MatchBrief;
import com.teamfighter.tfm.story.NameBook;
import com.teamfighter.tfm.story.StoryClient;
import com.teamfighter.tfm.story.StoryProperties;
import com.teamfighter.tfm.story.StoryRequest;
import com.teamfighter.tfm.story.dao.GalleryBatch;
import com.teamfighter.tfm.story.dao.GalleryDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 갤러리 한 페이지를 만든다. <b>호출 둘, 글 스물.</b>
 *
 * <h2>조각 하나가 실패해도 페이지는 산다</h2>
 *
 * 둘 중 하나가 깨져도 나머지로 저장한다 — 열 개짜리 게시판은 성립하고, 0개짜리는
 * 성립하지 않기 때문이다.
 *
 * <p>대신 그 사실을 지운 채 저장하지는 않는다. {@code gallery_batch.chunks} 에 몇 조각을
 * <b>시도했는지</b>가 남으므로 글 수가 할당보다 적으면 어디선가 실패했다는 뜻이 되고,
 * 로그에도 남는다. <b>조용히 적게 나오는 것</b>이 이 구조의 유일한 위험이라 그것만은
 * 읽을 수 있게 둔다 (D72 결정 4).
 *
 * <h2>1분 남짓 걸린다. 그래서 진행 상황을 흘린다</h2>
 *
 * 무료 티어의 분당 토큰이 8,000 인데 이 페이지 하나가 그 언저리를 쓴다 — 429 를 한 번쯤
 * 맞고 기다린다. 그동안 아무 신호가 없으면 화면은 멈춘 것과 구분되지 않는다.
 * 실제로 첫 실물에서 그렇게 보였고, 그때는 조각이 넷에 이슈까지 있어 3분이 넘었다(D74).
 *
 * <p>그래서 {@link Progress} 로 단계마다 알린다. 부르는 쪽(백그라운드 작업)이 그것을
 * 받아 두고 화면이 물어볼 때 돌려준다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 이 메서드는 대부분의 시간을
 * 네트워크에서 보내고, 그동안 DB 연결을 붙들고 있으면 커넥션 풀이 모델 응답 속도에 묶인다.
 * 저장은 {@link GalleryDao#save} 안에서 한 번에 끝난다.
 */
public class GalleryWriter {

    private static final Logger log = LoggerFactory.getLogger(GalleryWriter.class);

    /** 진행 상황을 받는 쪽. 화면이 폴링해서 읽는다. */
    @FunctionalInterface
    public interface Progress {

        /**
         * @param step  사람이 읽을 단계 이름 ("이슈 취재 중")
         * @param done  끝낸 호출 수
         * @param total 이 페이지가 낼 총 호출 수
         */
        void at(String step, int done, int total);

        /** 아무 데도 안 알린다. 테스트가 쓴다. */
        Progress NONE = (step, done, total) -> { };
    }

    private final StoryClient client;
    private final GalleryDao gallery;
    private final StoryProperties properties;

    public GalleryWriter(StoryClient client, GalleryDao gallery, StoryProperties properties) {
        this.client = client;
        this.gallery = gallery;
        this.properties = properties;
    }

    /**
     * 매치 하나로 갤러리 페이지를 만든다.
     *
     * @param batch    저장할 머리말. 조각 수는 여기서 채워 넣는다
     * @param brief    그 매치의 사실. 선수별 표가 여기서 나온다
     * @param tags     맥락 태그(순위·연패·라이벌). 민심의 근거다
     * @param progress 단계 알림. 없으면 {@link Progress#NONE}
     * @return 저장된 {@code batch_id}. 조각이 <b>전부</b> 실패하면 {@link Optional#empty()} —
     *         예외가 아니다. 화면은 "이번엔 아무것도 못 건졌다" 를 말하면 된다
     */
    public Optional<Long> write(GalleryBatch batch, MatchBrief brief, NameBook names,
                                List<String> tags, Progress progress) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(tags, "tags");

        List<GalleryChunk> chunks = GalleryChunk.page();
        List<GalleryPost> posts = new ArrayList<>();
        List<String> earlierTitles = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {                               // 1. 조각을 순서대로. 순서가 곧 갤의 시간이다
            GalleryChunk chunk = chunks.get(i);
            progress.at("갤 반응 " + (i + 1) + "/" + chunks.size() + " — " + chunk.mood(),
                    i, chunks.size());

            StoryRequest request = GalleryPrompts.chunk(chunk, brief, names, tags, earlierTitles);

            List<GalleryPost> written = callChunk(chunk, request);              // 2. 실패하면 빈 목록 — 다음 조각은 계속 돈다
            posts.addAll(written);
            written.forEach(post -> earlierTitles.add(post.title()));           // 3. 뒤 조각이 이 제목들을 보고 겹침을 피한다
        }

        progress.at("저장 중", chunks.size(), chunks.size());

        if (posts.isEmpty()) {
            log.warn("슬롯 {} 시즌 {} {}일: 조각 {}개가 전부 실패했다 — 저장할 글이 없다",
                    batch.slotId(), batch.season(), batch.day(), chunks.size());
            return Optional.empty();
        }

        int expected = chunks.stream().mapToInt(GalleryChunk::size).sum();
        if (posts.size() < expected) {                                          // 4. 적게 나온 것을 조용히 넘기지 않는다
            log.warn("글이 {}개 나왔다 (요청 {}개). 조각 하나가 깨졌거나 모델이 덜 썼다",
                    posts.size(), expected);
        }

        long batchId = gallery.save(batch, posts);
        log.info("슬롯 {} 시즌 {} {}일: 갤러리 {} 를 만들었다 (글 {}편 · 댓글 {}개)",
                batch.slotId(), batch.season(), batch.day(), batchId, posts.size(),
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

    /** 저장에 남길 모델 이름. 배치를 만드는 쪽이 쓴다. */
    public String model() {
        return properties.model();
    }
}
