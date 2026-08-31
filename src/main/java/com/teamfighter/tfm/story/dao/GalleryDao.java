package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.gallery.GalleryComment;
import com.teamfighter.tfm.story.gallery.GalleryPost;
import com.teamfighter.tfm.story.gallery.GalleryPostKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 갤러리 페이지를 저장하고 읽는다.
 *
 * <h2>덮어쓰지 않는다 — 쌓는다</h2>
 *
 * {@link ArticleDao} 는 업서트다. 기사는 <b>같은 매치를 다시 맞춰본 결과</b>라 새 것이
 * 옛 것보다 낫기 때문이다. 갤러리는 반대다 — 이 층에는 정답이 없고, 다시 뽑으면
 * 그냥 <b>다른 갤</b>이 나온다. 그래서 배치가 쌓이고 화면이 페이지로 넘긴다.
 *
 * <p>그 차이가 스키마에도 있다: {@code gallery_batch} 에는 UNIQUE 가 없다.
 *
 * <h2>기사에 매달리지 않는다 (D73)</h2>
 *
 * V11 은 갤러리를 기사에 걸었다. 그러면 기사를 먼저 써야 갤러리가 생기므로 기사가
 * <b>관문</b>이 된다. 지금은 매치에 직접 붙고, 기사가 있으면 링크로만 잇는다.
 */
@Repository
public class GalleryDao {

    private static final String INSERT_BATCH = """
            INSERT INTO gallery_batch
                (slot_id, article_id, season, day, blue_team_id, red_team_id,
                 blue_score, red_score, model, chunks)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING batch_id
            """;

    /**
     * {@code is_concept} 를 넣지 않는다 — <b>생성 컬럼</b>이라 DB 가 계산한다(V11).
     * 여기서 값을 주면 Postgres 가 거부하고, 그게 맞다. 규칙이 한 곳에만 있어야 한다.
     */
    private static final String INSERT_POST = """
            INSERT INTO gallery_post
                (batch_id, ordinal, kind, title, author, body, views, likes,
                 declared_concept, image_desc, posted_at)
            VALUES (?, ?, CAST(? AS gallery_post_kind), ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING post_id
            """;

    private static final String INSERT_COMMENT = """
            INSERT INTO gallery_comment (post_id, ordinal, parent_ordinal, author, body, posted_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    /**
     * 페이지 목록. <b>경기 시점 순</b>이다 — {@code generated_at} 으로 정렬하면
     * 옛 시즌을 나중에 뽑았을 때 그게 맨 위로 올라온다.
     */
    private static final String SELECT_BATCHES = """
            SELECT g.batch_id, g.slot_id, g.article_id, g.season, g.day,
                   g.blue_team_id, g.red_team_id, g.blue_score, g.red_score,
                   g.generated_at, g.model, g.chunks,
                   bt.name AS blue_team_name, rt.name AS red_team_name
            FROM gallery_batch g
            LEFT JOIN team bt ON bt.team_id = g.blue_team_id
            LEFT JOIN team rt ON rt.team_id = g.red_team_id
            WHERE g.slot_id = ?
            ORDER BY g.season DESC, g.day DESC, g.generated_at DESC, g.batch_id DESC
            """;

    private static final String SELECT_BATCH = """
            SELECT g.batch_id, g.slot_id, g.article_id, g.season, g.day,
                   g.blue_team_id, g.red_team_id, g.blue_score, g.red_score,
                   g.generated_at, g.model, g.chunks,
                   bt.name AS blue_team_name, rt.name AS red_team_name
            FROM gallery_batch g
            LEFT JOIN team bt ON bt.team_id = g.blue_team_id
            LEFT JOIN team rt ON rt.team_id = g.red_team_id
            WHERE g.batch_id = ?
            """;

    private static final String SELECT_POSTS = """
            SELECT post_id, ordinal, kind, title, author, body, views, likes,
                   is_concept, image_desc, posted_at
            FROM gallery_post
            WHERE batch_id = ?
            ORDER BY ordinal
            """;

    /**
     * 페이지 하나의 댓글을 <b>한 번에</b> 읽는다. 글마다 조회를 날리면 스무 번이 나가는데,
     * 그건 느린 게 아니라 안 보이게 느린 종류다 ({@code ArticleDao} 가 적어 둔 그대로).
     */
    private static final String SELECT_COMMENTS = """
            SELECT c.post_id, c.ordinal, c.parent_ordinal, c.author, c.body, c.posted_at
            FROM gallery_comment c
            JOIN gallery_post p ON p.post_id = c.post_id
            WHERE p.batch_id = ?
            ORDER BY c.post_id, c.ordinal
            """;

    /** 갤러리가 이미 있는 매치. 생성기가 "다음에 뽑을 매치" 를 고를 때 쓴다. */
    private static final String SELECT_KEYS = """
            SELECT DISTINCT season, day, blue_team_id, red_team_id
            FROM gallery_batch WHERE slot_id = ?
            """;

    /** 갤러리가 있는 커리어. 화면이 기본 슬롯을 고를 때 쓴다. */
    private static final String SELECT_SLOTS = """
            SELECT DISTINCT slot_id FROM gallery_batch ORDER BY slot_id
            """;

    private final JdbcTemplate jdbc;

    public GalleryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 페이지 하나를 통째로 저장한다.
     *
     * <p>배치·글·댓글이 <b>한 트랜잭션</b>이다. 갈리면 글은 있는데 댓글이 없는
     * 페이지가 화면에 뜨고, 그건 예외 없이 조용히 일어난다.
     *
     * @return 저장된 {@code batch_id}
     */
    @Transactional
    public long save(GalleryBatch batch, List<GalleryPost> posts) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(posts, "posts");
        if (posts.isEmpty()) {
            throw new IllegalArgumentException("글이 하나도 없는 페이지는 저장하지 않는다");
        }

        Long batchId = jdbc.queryForObject(INSERT_BATCH, Long.class,
                batch.slotId(), batch.articleId(), batch.season(), batch.day(),
                batch.blueTeamId(), batch.redTeamId(), batch.blueScore(), batch.redScore(),
                batch.model(), batch.chunks());
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

    /**
     * 그 커리어의 페이지 목록. <b>글도 댓글도 안 읽는다</b> — 페이지 번호를 그리는 데
     * 필요한 것은 머리말뿐이고, 페이지 열 개의 글을 다 끌어오면 화면 하나가 수 MB 를 읽는다.
     */
    @Transactional(readOnly = true)
    public List<GalleryView> pages(int slotId) {
        return jdbc.query(SELECT_BATCHES,
                (rs, rowNum) -> readBatch(rs, List.of()), slotId);
    }

    /** 페이지 하나를 통째로. 없으면 {@link Optional#empty()} — 예외가 아니다. */
    @Transactional(readOnly = true)
    public Optional<GalleryView> find(long batchId) {
        // 자식 행을 RowMapper 밖에서 먼저 읽는다. 매퍼 안에서 또 조회하면 열린 ResultSet
        // 위에 같은 연결로 질의를 얹게 된다 (ArticleDao 와 같은 이유).
        Map<Long, List<GalleryComment>> commentsByPost = loadComments(batchId);
        List<GalleryView.Post> posts = jdbc.query(SELECT_POSTS,
                (rs, rowNum) -> readPost(rs, commentsByPost), batchId);

        List<GalleryView> found = jdbc.query(SELECT_BATCH,
                (rs, rowNum) -> readBatch(rs, posts), batchId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * 갤러리가 이미 있는 매치의 신원 전부.
     *
     * <p>{@link ArticleKey} 를 그대로 쓴다. 이름은 기사에서 왔지만 담는 값은
     * <b>매치 신원</b>(시즌 · 일 · DB 팀 번호 둘)이고, 그건 기사든 갤러리든 같다.
     */
    @Transactional(readOnly = true)
    public Set<ArticleKey> writtenKeys(int slotId) {
        return Set.copyOf(jdbc.query(SELECT_KEYS,
                (rs, rowNum) -> new ArticleKey(rs.getInt("season"), rs.getInt("day"),
                        rs.getInt("blue_team_id"), rs.getInt("red_team_id")), slotId));
    }

    /** 갤러리가 있는 커리어. */
    @Transactional(readOnly = true)
    public List<Integer> slotsWithGalleries() {
        return jdbc.queryForList(SELECT_SLOTS, Integer.class);
    }

    private long insertPost(long batchId, int ordinal, GalleryPost post) {
        Long postId = jdbc.queryForObject(INSERT_POST, Long.class,
                batchId, ordinal, post.kind().name(), post.title(), post.author(),
                post.body(), post.views(), post.likes(),
                post.declaredConcept(), post.imageDesc(), post.postedAt());
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
    private void insertComments(long postId, List<GalleryComment> comments) {
        int ordinal = 1;
        for (GalleryComment line : comments) {
            jdbc.update(INSERT_COMMENT, postId, ordinal++,
                    line.parentOrdinal(), line.author(), line.body(), line.postedAt());
        }
    }

    private Map<Long, List<GalleryComment>> loadComments(long batchId) {
        Map<Long, List<GalleryComment>> out = new LinkedHashMap<>();
        jdbc.query(SELECT_COMMENTS, rs -> {
            out.computeIfAbsent(rs.getLong("post_id"), key -> new ArrayList<>())
                    .add(new GalleryComment(
                            rs.getString("author"),
                            rs.getString("body"),
                            nullableInt(rs, "parent_ordinal"),
                            rs.getString("posted_at")));
        }, batchId);
        return out;
    }

    private static GalleryView readBatch(ResultSet rs, List<GalleryView.Post> posts)
            throws SQLException {
        return new GalleryView(
                rs.getLong("batch_id"),
                rs.getInt("slot_id"),
                nullableLong(rs, "article_id"),
                rs.getInt("season"),
                rs.getInt("day"),
                nullableInt(rs, "blue_team_id"),
                nullableInt(rs, "red_team_id"),
                rs.getString("blue_team_name"),
                rs.getString("red_team_name"),
                nullableInt(rs, "blue_score"),
                nullableInt(rs, "red_score"),
                rs.getObject("generated_at", OffsetDateTime.class),
                rs.getString("model"),
                rs.getInt("chunks"),
                posts);
    }

    private static GalleryView.Post readPost(ResultSet rs,
                                             Map<Long, List<GalleryComment>> comments)
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
                rs.getString("posted_at"),
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

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
