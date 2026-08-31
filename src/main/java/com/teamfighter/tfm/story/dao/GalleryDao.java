package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.ArticleDraft.CommentLine;
import com.teamfighter.tfm.story.gallery.GalleryPost;
import com.teamfighter.tfm.story.gallery.GalleryPostKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 갤러리 페이지를 저장하고 읽는다.
 *
 * <h2>덮어쓰지 않는다 — 쌓는다</h2>
 *
 * {@link ArticleDao} 는 업서트다. 기사는 <b>같은 매치를 다시 맞춰본 결과</b>라 새 것이
 * 옛 것보다 낫기 때문이다. 갤러리는 반대다 — 이 층에는 정답이 없고, 다시 뽑으면
 * 그냥 <b>다른 갤</b>이 나온다. 그래서 배치가 쌓이고 화면이 최신 것을 고른다.
 *
 * <p>그 차이가 스키마에도 있다: {@code gallery_batch} 에는 UNIQUE 가 없다.
 */
@Repository
public class GalleryDao {

    private static final String INSERT_BATCH = """
            INSERT INTO gallery_batch (article_id, model, chunks)
            VALUES (?, ?, ?) RETURNING batch_id
            """;

    /**
     * {@code is_concept} 를 넣지 않는다 — <b>생성 컬럼</b>이라 DB 가 계산한다(V11).
     * 여기서 값을 주면 Postgres 가 거부하고, 그게 맞다. 규칙이 한 곳에만 있어야 한다.
     */
    private static final String INSERT_POST = """
            INSERT INTO gallery_post
                (batch_id, ordinal, kind, title, author, body, views, likes,
                 declared_concept, image_desc)
            VALUES (?, ?, CAST(? AS gallery_post_kind), ?, ?, ?, ?, ?, ?, ?)
            RETURNING post_id
            """;

    private static final String INSERT_COMMENT = """
            INSERT INTO gallery_comment (post_id, ordinal, parent_ordinal, author, body)
            VALUES (?, ?, ?, ?, ?)
            """;

    /** 그 기사의 <b>가장 최근</b> 페이지 하나. 옛 페이지는 아직 화면이 안 그린다. */
    private static final String SELECT_LATEST_BATCH = """
            SELECT batch_id, article_id, generated_at, model, chunks
            FROM gallery_batch
            WHERE article_id = ?
            ORDER BY generated_at DESC, batch_id DESC
            LIMIT 1
            """;

    private static final String SELECT_POSTS = """
            SELECT post_id, ordinal, kind, title, author, body, views, likes,
                   is_concept, image_desc
            FROM gallery_post
            WHERE batch_id = ?
            ORDER BY ordinal
            """;

    /**
     * 페이지 하나의 댓글을 <b>한 번에</b> 읽는다. 글마다 조회를 날리면 스무 번이 나가는데,
     * 그건 느린 게 아니라 안 보이게 느린 종류다 ({@code ArticleDao} 가 적어 둔 그대로).
     */
    private static final String SELECT_COMMENTS = """
            SELECT c.post_id, c.ordinal, c.parent_ordinal, c.author, c.body
            FROM gallery_comment c
            JOIN gallery_post p ON p.post_id = c.post_id
            WHERE p.batch_id = ?
            ORDER BY c.post_id, c.ordinal
            """;

    /** 그 기사에 갤러리가 몇 페이지 쌓였나. 화면이 "다시 불러오기" 옆에 적는다. */
    private static final String COUNT_BATCHES = """
            SELECT count(*) FROM gallery_batch WHERE article_id = ?
            """;

    /**
     * 갤러리가 아직 없는 <b>가장 최근 매치 기사</b> 하나.
     *
     * <p>매치 기사만 본다({@code kind = 'MATCH'}). 총평에는 선수별 표가 없고
     * ({@code RoundBrief} 는 매치당 한 줄이다) 그 표가 없으면 갤 글이 "잘했다/못했다" 로
     * 뭉개진다 — 그건 우리가 프롬프트로 못 고친다고 결론 낸 바로 그 실패다.
     *
     * <p>최근 순은 <b>경기 시점</b> 순이다. {@code generated_at} 으로 정렬하면 옛 시즌을
     * 나중에 생성했을 때 그게 맨 위로 올라온다 ({@code ArticleDao.SELECT_RECENT} 와 같은 이유).
     */
    private static final String SELECT_ANCHOR = """
            SELECT a.article_id, a.season, a.day, a.blue_team_id, a.red_team_id,
                   a.headline, a.body
            FROM article a
            WHERE a.slot_id = ? AND a.kind = 'MATCH'
              AND NOT EXISTS (SELECT 1 FROM gallery_batch g WHERE g.article_id = a.article_id)
            ORDER BY a.season DESC, a.day DESC, a.article_id DESC
            LIMIT 1
            """;

    private final JdbcTemplate jdbc;

    public GalleryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 페이지 하나를 통째로 저장한다.
     *
     * <p>배치·글·댓글이 <b>한 트랜잭션</b>이다. 갈리면 글은 있는데 댓글이 없는 페이지가
     * 화면에 뜨고, 그건 예외 없이 조용히 일어난다.
     *
     * @param chunks 이 페이지를 만드는 데 든 호출 수. 조각 하나가 실패해도 저장은 한다 —
     *               그때 이 값과 글 수가 안 맞고, 그 불일치가 곧 기록이다 (D72)
     * @return 저장된 {@code batch_id}
     */
    @Transactional
    public long save(long articleId, String model, int chunks, List<GalleryPost> posts) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(posts, "posts");
        if (posts.isEmpty()) {
            throw new IllegalArgumentException("글이 하나도 없는 페이지는 저장하지 않는다");
        }

        Long batchId = jdbc.queryForObject(INSERT_BATCH, Long.class, articleId, model, chunks);
        if (batchId == null) {
            throw new IllegalStateException("배치를 넣었는데 batch_id 가 안 나왔다");
        }

        int ordinal = 1;
        for (GalleryPost post : posts) {
            long postId = insertPost(batchId, ordinal++, post);
            insertComments(postId, post.comments());
        }
        return batchId;
    }

    /** 그 기사의 최신 갤러리. 아직 없으면 {@link Optional#empty()} — 예외가 아니다. */
    @Transactional(readOnly = true)
    public Optional<GalleryView> findLatest(long articleId) {
        List<long[]> ids = new ArrayList<>();
        List<GalleryView> found = jdbc.query(SELECT_LATEST_BATCH, (rs, rowNum) -> {
            ids.add(new long[] {rs.getLong("batch_id")});
            return new GalleryView(
                    rs.getLong("batch_id"),
                    rs.getLong("article_id"),
                    rs.getObject("generated_at", java.time.OffsetDateTime.class),
                    rs.getString("model"),
                    rs.getInt("chunks"),
                    List.of());
        }, articleId);

        if (found.isEmpty()) {
            return Optional.empty();
        }
        long batchId = ids.get(0)[0];

        // 자식 행을 배치보다 나중에, 그러나 RowMapper 밖에서 읽는다. 매퍼 안에서 또
        // 조회하면 열린 ResultSet 위에 같은 연결로 질의를 얹게 된다 (ArticleDao 와 같은 이유).
        Map<Long, List<CommentLine>> commentsByPost = loadComments(batchId);
        List<GalleryView.Post> posts = jdbc.query(SELECT_POSTS,
                (rs, rowNum) -> readPost(rs, commentsByPost), batchId);

        GalleryView head = found.get(0);
        return Optional.of(new GalleryView(head.batchId(), head.articleId(),
                head.generatedAt(), head.model(), head.chunks(), posts));
    }

    /**
     * 갤러리를 아직 안 붙인 최근 매치 기사. 다 붙였으면 {@link Optional#empty()} —
     * <b>예외가 아니다.</b> "다 했다" 는 정상 상태이고, 화면은 그걸 그대로 말하면 된다.
     */
    @Transactional(readOnly = true)
    public Optional<GalleryAnchor> nextAnchor(int slotId) {
        List<GalleryAnchor> found = jdbc.query(SELECT_ANCHOR, (rs, rowNum) -> new GalleryAnchor(
                rs.getLong("article_id"),
                new ArticleKey(rs.getInt("season"), rs.getInt("day"),
                        rs.getInt("blue_team_id"), rs.getInt("red_team_id")),
                rs.getString("headline"),
                rs.getString("body")), slotId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * 그 배치가 어느 기사에 붙었나. 화면 주소가 {@code article_id} 라 되짚을 일이 있다.
     */
    @Transactional(readOnly = true)
    public Optional<Long> articleOf(long batchId) {
        List<Long> found = jdbc.queryForList(
                "SELECT article_id FROM gallery_batch WHERE batch_id = ?", Long.class, batchId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** 그 기사에 쌓인 페이지 수. */
    @Transactional(readOnly = true)
    public int countBatches(long articleId) {
        Integer count = jdbc.queryForObject(COUNT_BATCHES, Integer.class, articleId);
        return count == null ? 0 : count;
    }

    private long insertPost(long batchId, int ordinal, GalleryPost post) {
        Long postId = jdbc.queryForObject(INSERT_POST, Long.class,
                batchId, ordinal, post.kind().name(), post.title(), post.author(),
                post.body(), post.views(), post.likes(),
                post.declaredConcept(), post.imageDesc());
        if (postId == null) {
            throw new IllegalStateException("글을 넣었는데 post_id 가 안 나왔다");
        }
        return postId;
    }

    /**
     * 댓글을 순번대로 넣는다. 대댓글의 {@code parent_ordinal} 은 파서가 이미 붙여 뒀다.
     *
     * <p>외래키가 {@code DEFERRABLE INITIALLY DEFERRED} 라(V11) 부모보다 자식이 먼저
     * 들어가도 트랜잭션 끝에서만 검사한다. 파서는 부모를 먼저 넣지만, 그 순서에
     * 저장이 <b>의존하지 않게</b> 스키마가 받쳐 준다.
     */
    private void insertComments(long postId, List<CommentLine> comments) {
        int ordinal = 1;
        for (CommentLine line : comments) {
            jdbc.update(INSERT_COMMENT, postId, ordinal++,
                    line.parentOrdinal(), line.author(), line.body());
        }
    }

    private Map<Long, List<CommentLine>> loadComments(long batchId) {
        Map<Long, List<CommentLine>> out = new LinkedHashMap<>();
        jdbc.query(SELECT_COMMENTS, rs -> {
            out.computeIfAbsent(rs.getLong("post_id"), key -> new ArrayList<>())
                    .add(new CommentLine(
                            rs.getString("author"),
                            rs.getString("body"),
                            nullableInt(rs, "parent_ordinal")));
        }, batchId);
        return out;
    }

    private static GalleryView.Post readPost(ResultSet rs,
                                             Map<Long, List<CommentLine>> comments)
            throws SQLException {
        long postId = rs.getLong("post_id");
        return new GalleryView.Post(
                postId,
                rs.getInt("ordinal"),
                GalleryPostKind.valueOf(rs.getString("kind")),
                rs.getString("title"),
                rs.getString("author"),
                rs.getString("body"),
                nullableInt(rs, "views"),
                nullableInt(rs, "likes"),
                rs.getBoolean("is_concept"),
                rs.getString("image_desc"),
                comments.getOrDefault(postId, List.of()));
    }

    /**
     * {@code getInt} 는 NULL 을 0 으로 준다. 조회수·추천수에서 그 둘은 다른 뜻이라
     * ({@code null} = 모델이 안 줬다 · {@code 0} = 아무도 안 봤다) 여기서 갈라야 한다.
     */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
