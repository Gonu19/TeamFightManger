package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.ArticleDraft;
import com.teamfighter.tfm.story.ArticleDraft.CommentLine;
import com.teamfighter.tfm.story.ArticleDraft.FactStatus;
import com.teamfighter.tfm.story.ArticleDraft.Finding;
import com.teamfighter.tfm.story.ArticleDraft.Severity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 기사·댓글·지적을 {@code article} · {@code article_comment} · {@code article_finding} 에 넣고 꺼낸다
 * (D22: 적재·집계는 JdbcTemplate).
 *
 * <p><b>업서트다. 재생성이 갱신이 된다.</b> 같은 매치를 다시 돌리면 기사가 한 편 더 생기는 게
 * 아니라 있던 것이 덮인다 — 그래서 유일 키가 {@code schedule_id} 가 아니라
 * {@code (slot_id, season, day, blue_team_id, red_team_id)} 다. {@code MatchSchedule.ID} 는
 * 대회마다 ID 공간이 따로라 단독으로는 유일하지 않다(실측 190건이 114개 값에 겹친다).
 *
 * <p><b>{@code fact_status} 를 여기서 계산하지 않는다.</b> {@link ArticleDraft#factStatus()} 가
 * 준 값을 그대로 넣는다. V8 주석이 "모순이 하나라도 있으면 {@code CONTRADICTED}" 를 적재의
 * 책임으로 남겼는데(트리거를 두지 않기로 한 D35 의 선례다), 그 책임을 DAO 코드가 지면 저장
 * 경로가 하나 늘 때마다 다시 틀릴 수 있다. 타입이 계산하면 틀리게 넣을 방법 자체가 없다.
 *
 * <p><b>자식 행은 지우고 다시 넣는다.</b> 댓글과 지적은 {@code ordinal} 로만 구분되는데,
 * 다시 생성한 기사의 댓글 수가 줄면(15개 → 12개) 업서트만으로는 <b>옛 댓글 3개가 남는다.</b>
 * 그것들은 지금 본문과 아무 관계가 없고, 화면에서는 그냥 댓글로 보인다. 삭제와 삽입이 한
 * 트랜잭션 안에 있어야 그 사이의 조회가 빈 댓글난을 보지 않는다.
 */
@Repository
public class ArticleDao {

    /**
     * 유일 키에 든 다섯 컬럼은 {@code DO UPDATE SET} 에 넣지 않는다 — 그 값들로 행을 찾았으니
     * 같을 수밖에 없다. {@code generated_at} 은 {@code EXCLUDED} 로 새로 찍는다: 재생성은
     * 새 기사를 쓴 것이므로 시각도 그때가 맞다.
     *
     * <p>{@code now()} 가 아니라 {@code clock_timestamp()} 다. {@code now()} 는 트랜잭션
     * <b>시작</b> 시각이라 한 트랜잭션에서 여러 편을 저장하면 전부 같은 시각이 찍힌다 —
     * 목록의 정렬 기준이 되는 값이 뭉개진다 ({@code AggRunRecorder} 에서 같은 것에 데었다).
     */
    private static final String UPSERT = """
            INSERT INTO article
                (slot_id, schedule_id, competition_id, competition_key, season, day, round,
                 blue_team_id, red_team_id, blue_score, red_score, blue_kill, red_kill,
                 notability, notability_reasons, headline, body, brief_text, model,
                 generated_at, fact_status)
            VALUES (?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?,
                    clock_timestamp(), CAST(? AS article_fact_status))
            ON CONFLICT (slot_id, season, day, blue_team_id, red_team_id)
            DO UPDATE SET
                schedule_id        = EXCLUDED.schedule_id,
                competition_id     = EXCLUDED.competition_id,
                competition_key    = EXCLUDED.competition_key,
                round              = EXCLUDED.round,
                blue_score         = EXCLUDED.blue_score,
                red_score          = EXCLUDED.red_score,
                blue_kill          = EXCLUDED.blue_kill,
                red_kill           = EXCLUDED.red_kill,
                notability         = EXCLUDED.notability,
                notability_reasons = EXCLUDED.notability_reasons,
                headline           = EXCLUDED.headline,
                body               = EXCLUDED.body,
                brief_text         = EXCLUDED.brief_text,
                model              = EXCLUDED.model,
                generated_at       = EXCLUDED.generated_at,
                fact_status        = EXCLUDED.fact_status
            RETURNING article_id
            """;

    private static final String INSERT_COMMENT = """
            INSERT INTO article_comment (article_id, ordinal, body, author, parent_ordinal)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String INSERT_FINDING = """
            INSERT INTO article_finding (article_id, ordinal, severity, what, evidence)
            VALUES (?, ?, CAST(? AS article_finding_severity), ?, ?)
            """;

    /**
     * 팀 이름은 조인해서 준다. {@code team.name} 은 NULL 일 수 있고, 그때는 화면이
     * 번호로 그린다 — 여기서 "팀 33" 같은 문자열을 지어내면 그게 이름인지 자리표시자인지
     * 화면이 구분할 수 없게 된다.
     */
    private static final String SELECT_ARTICLE = """
            SELECT a.*, bt.name AS blue_team_name, rt.name AS red_team_name
            FROM article a
            JOIN team bt ON bt.team_id = a.blue_team_id
            JOIN team rt ON rt.team_id = a.red_team_id
            WHERE a.article_id = ?
            """;

    private static final String SELECT_COMMENTS = """
            SELECT ordinal, body, author, parent_ordinal FROM article_comment
            WHERE article_id = ? ORDER BY ordinal
            """;

    private static final String SELECT_FINDINGS = """
            SELECT severity, what, evidence FROM article_finding
            WHERE article_id = ? ORDER BY ordinal
            """;

    /**
     * 최근 순은 <b>경기 시점</b> 순이다 ({@code season DESC, day DESC}).
     * {@code generated_at} 으로 정렬하면 옛 시즌을 나중에 다시 생성했을 때 그게 맨 위로 올라온다.
     * {@code article_slot_time_idx} 가 이 정렬을 그대로 받는다.
     */
    private static final String SELECT_RECENT = """
            SELECT a.article_id, a.slot_id, a.season, a.day,
                   a.blue_team_id, a.red_team_id,
                   bt.name AS blue_team_name, rt.name AS red_team_name,
                   a.blue_score, a.red_score, a.notability, a.headline,
                   a.generated_at, a.fact_status
            FROM article a
            JOIN team bt ON bt.team_id = a.blue_team_id
            JOIN team rt ON rt.team_id = a.red_team_id
            WHERE a.slot_id = ?
            ORDER BY a.season DESC, a.day DESC, a.article_id DESC
            LIMIT ?
            """;

    /**
     * 기사가 <b>있는</b> 슬롯만 준다. {@code save_slot} 전체가 아니다 —
     * 화면이 기본 커리어를 고를 때 빈 목록으로 시작하지 않게 하는 것이 이 질의의 목적이고,
     * 기사가 하나도 없는 슬롯을 골라주면 그 목적이 그대로 실패한다.
     */
    private static final String SELECT_SLOTS = """
            SELECT DISTINCT slot_id FROM article ORDER BY slot_id
            """;

    /** 매치 신원 네 값만. 본문을 안 읽는 이유는 {@link #writtenKeys} 가 적어 뒀다. */
    private static final String SELECT_KEYS = """
            SELECT season, day, blue_team_id, red_team_id FROM article WHERE slot_id = ?
            """;

    private final JdbcTemplate jdbc;

    public ArticleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 기사 한 편을 통째로 저장한다. 이미 있으면 덮는다.
     *
     * <p>기사·댓글·지적이 <b>한 트랜잭션</b>이다. 셋이 갈리면 댓글만 새것이고 본문은 옛것인
     * 기사가 화면에 뜨는데, 그건 예외 없이 조용히 일어난다.
     *
     * @return 저장된 {@code article_id}. 재생성이면 원래 있던 것과 같은 값이다
     */
    @Transactional
    public long save(ArticleDraft draft) {
        long articleId = upsert(draft);
        replaceComments(articleId, draft.comments());
        replaceFindings(articleId, draft.findings());
        return articleId;
    }

    @Transactional(readOnly = true)
    public List<Integer> slotsWithArticles() {
        return jdbc.queryForList(SELECT_SLOTS, Integer.class);
    }

    /**
     * 그 슬롯에서 <b>이미 기사를 쓴 매치</b>의 신원 전부.
     *
     * <p>생성기가 "다음에 쓸 매치" 를 고를 때 쓴다. 한 커리어가 100편 남짓이라 통째로 올려도
     * 몇 KB 이고, {@link Set} 이므로 매치마다 조회를 날리는 대신 메모리에서 한 번에 거른다.
     * 매치마다 {@code SELECT ... WHERE} 를 날리면 질의 100번이 나가는데, 그건 느린 게 아니라
     * <b>안 보이게 느린</b> 종류다.
     *
     * <p>업서트라서 <b>안 걸러도 결과는 같다</b> — 다시 쓰면 덮일 뿐이다. 그래서 이 메서드는
     * 정확성이 아니라 <b>비용</b>을 위한 것이다: 걸러내지 않으면 이미 쓴 기사를 다시 쓰느라
     * 모델 호출이 두 번씩 더 나간다.
     */
    @Transactional(readOnly = true)
    public Set<ArticleKey> writtenKeys(int slotId) {
        List<ArticleKey> rows = jdbc.query(SELECT_KEYS,
                (rs, rowNum) -> new ArticleKey(
                        rs.getInt("season"),
                        rs.getInt("day"),
                        rs.getInt("blue_team_id"),
                        rs.getInt("red_team_id")),
                slotId);
        return Set.copyOf(rows);
    }

    /**
     * 자식 행을 <b>기사보다 먼저</b> 읽는다. {@code RowMapper} 안에서 또 조회하면 열린
     * {@code ResultSet} 위에서 같은 연결에 질의를 얹게 된다 — 지금은 돌지만 커서를 쓰는
     * 순간 깨지는 종류의 코드다. 없는 기사면 두 조회가 빈 목록을 줄 뿐이고, 그 비용은
     * 인덱스 조회 두 번이다.
     */
    @Transactional(readOnly = true)
    public Optional<ArticleView> find(long articleId) {
        // queryForList(sql, 타입, 인자...) 는 컬럼이 하나일 때 쓰는 지름길이다.
        // 행이 없으면 빈 목록을 준다 — queryForObject 와 달리 예외가 아니다.
        List<CommentLine> comments = jdbc.query(SELECT_COMMENTS,
                (rs, rowNum) -> new CommentLine(
                        rs.getString("author"),
                        rs.getString("body"),
                        nullableInt(rs, "parent_ordinal")),
                articleId);

        // query(sql, RowMapper, 인자...) — RowMapper 는 "행 하나 → 객체 하나" 함수다.
        // 람다의 두 번째 인자 rowNum 은 0부터의 행 번호인데 여기서는 안 쓴다.
        // rs.getString 이 준 문자열을 enum 으로 되돌리는 것은 valueOf 다 — DB 의 enum 값과
        // 자바 enum 이름이 같아야 하고, 그 계약이 깨지면 여기서 예외로 즉시 드러난다.
        List<Finding> findings = jdbc.query(SELECT_FINDINGS,
                (rs, rowNum) -> new Finding(
                        Severity.valueOf(rs.getString("severity")),
                        rs.getString("what"),
                        rs.getString("evidence")),
                articleId);

        // 기사 본체. 유일 키로 찾으므로 행은 0개 아니면 1개다. queryForObject 를 쓰면
        // 0개일 때 EmptyResultDataAccessException 이 나는데, "없는 기사" 는 예외로 다룰
        // 일이 아니라 Optional 로 답할 일이다 — 그래서 query 로 받아 목록 크기를 본다.
        List<ArticleView> found = jdbc.query(SELECT_ARTICLE,
                (rs, rowNum) -> toView(rs, comments, findings), articleId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * 최근 기사 목록.
     *
     * <p><b>{@code limit} 이 0 이하면 던진다.</b> 빈 목록을 돌려주면 화면이 비어도 기사가 없는
     * 것인지 인자가 틀린 것인지 구분되지 않는다.
     *
     * <p>다만 <b>밖에서 잡히는 예외 타입은 {@link IllegalArgumentException} 이 아니다.</b>
     * {@code @Repository} 빈이라 예외 변환이 걸려 있어서, JPA 규약대로
     * {@code InvalidDataAccessApiUsageException} 으로 바뀌어 나간다(원인은 그대로 달려 있다).
     * 테스트가 그 사실에 걸려서 알게 됐다 — 부르는 쪽이 {@code IllegalArgumentException} 을
     * 잡으려 들면 안 잡힌다.
     */
    @Transactional(readOnly = true)
    public List<ArticleCard> recent(int slotId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 은 1 이상이어야 한다: " + limit);
        }
        // 마지막 두 인자(slotId, limit)가 SQL 의 ? 두 개에 순서대로 들어간다.
        // LIMIT 도 파라미터로 넘긴다 — 문자열로 이어 붙이면 SQL 주입의 통로가 되고,
        // 프리페어드 스테이트먼트 캐시도 limit 값마다 따로 잡힌다.
        return jdbc.query(SELECT_RECENT, (rs, rowNum) -> new ArticleCard(
                rs.getLong("article_id"),
                rs.getInt("slot_id"),
                rs.getInt("season"),
                rs.getInt("day"),
                rs.getInt("blue_team_id"),
                rs.getInt("red_team_id"),
                rs.getString("blue_team_name"),
                rs.getString("red_team_name"),
                rs.getInt("blue_score"),
                rs.getInt("red_score"),
                rs.getDouble("notability"),
                rs.getString("headline"),
                // timestamptz → OffsetDateTime. getTimestamp 로 받으면 시간대가 날아가
                // JVM 기본 시간대로 해석되는데, 그 오차는 화면에서 "몇 시간 전에 쓴 기사"
                // 로만 보여서 눈에 안 띈다.
                rs.getObject("generated_at", java.time.OffsetDateTime.class),
                FactStatus.valueOf(rs.getString("fact_status"))), slotId, limit);
    }

    /**
     * {@code notability_reasons} 가 {@code text[]} 라 {@code PreparedStatement} 를 직접 만진다.
     * 배열은 연결에서 만들어야 하고({@code createArrayOf}), 문자열로 넘겨 캐스팅하면
     * 쉼표·중괄호가 든 이유 한 줄이 조용히 여러 원소로 쪼개진다.
     */
    private long upsert(ArticleDraft draft) {
        // jdbc.execute(sql, PreparedStatementCallback) 는 JdbcTemplate 의 가장 낮은 층이다.
        // update()/query() 와 달리 준비된 PreparedStatement 를 그대로 넘겨주고, 연결을 닫는
        // 것과 예외를 스프링 예외로 바꾸는 것만 대신해 준다. 여기서 이 층까지 내려온 이유는
        // 두 가지를 동시에 해야 하기 때문이다 — 배열 파라미터를 만들고(연결이 필요하다),
        // INSERT ... RETURNING 의 결과를 읽는다(update() 는 갱신된 행 수만 준다).
        Long articleId = jdbc.execute(UPSERT, (PreparedStatement ps) -> {
            // text[] 파라미터. 배열은 드라이버가 연결 위에서 만들어야 한다 —
            // "{a,b}" 같은 문자열로 넘겨 CAST 하면 값 안의 쉼표·중괄호가 구분자로 읽혀
            // 이유 한 줄이 조용히 여러 원소로 쪼개진다.
            Array reasons = ps.getConnection()
                    .createArrayOf("text", draft.notabilityReasons().toArray(String[]::new));
            try {
                // JDBC 의 ? 는 1번부터다. i++ 로 세는 이유는 컬럼을 하나 끼워 넣을 때
                // 아래 번호를 전부 고쳐야 하는 상황을 없애기 위해서다 — 그 수정은
                // 빠뜨려도 컴파일이 되고, 값이 옆 컬럼으로 들어가도 타입만 맞으면
                // 예외도 안 난다.
                int i = 1;
                ps.setInt(i++, draft.slotId());
                ps.setObject(i++, draft.scheduleId(), Types.INTEGER);
                ps.setObject(i++, draft.competitionId(), Types.INTEGER);
                ps.setObject(i++, draft.competitionKey(), Types.VARCHAR);
                ps.setInt(i++, draft.season());
                ps.setInt(i++, draft.day());
                ps.setObject(i++, draft.round(), Types.INTEGER);
                ps.setInt(i++, draft.blueTeamId());
                ps.setInt(i++, draft.redTeamId());
                ps.setInt(i++, draft.blueScore());
                ps.setInt(i++, draft.redScore());
                ps.setInt(i++, draft.blueKill());
                ps.setInt(i++, draft.redKill());
                ps.setDouble(i++, draft.notability());
                ps.setArray(i++, reasons);
                ps.setString(i++, draft.headline());
                ps.setString(i++, draft.body());
                ps.setString(i++, draft.briefText());
                ps.setString(i++, draft.model());
                // enum 은 문자열로 넘기고 SQL 쪽에서 CAST(? AS article_fact_status) 한다.
                // 드라이버는 PostgreSQL 사용자 정의 enum 을 모르기 때문이다.
                ps.setString(i, draft.factStatus().name());

                // INSERT 든 UPDATE 든 RETURNING 이 행 하나를 주므로 executeQuery 다
                // (executeUpdate 는 결과 집합을 못 읽는다). try-with-resources 로 닫는다.
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException(
                                "기사를 저장하지 못했다 — RETURNING 이 비었다: "
                                        + describe(draft));
                    }
                    return rs.getLong(1);
                }
            } finally {
                reasons.free();
            }
        });
        if (articleId == null) {
            throw new IllegalStateException("기사를 저장하지 못했다: " + describe(draft));
        }
        return articleId;
    }

    /**
     * 댓글을 통째로 갈아 끼운다. 지우고 → 넣는다.
     *
     * <p>부르는 쪽({@link #save})이 {@code @Transactional} 이라 이 삭제와 아래 삽입은
     * 같은 트랜잭션 안에 있다. 그래서 그 사이에 다른 요청이 조회해도 <b>빈 댓글난을 보지
     * 않는다</b> — 커밋 전의 중간 상태는 남에게 안 보이기 때문이다.
     */
    private void replaceComments(long articleId, List<CommentLine> comments) {
        jdbc.update("DELETE FROM article_comment WHERE article_id = ?", articleId);
        if (comments.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(comments.size());
        for (int i = 0; i < comments.size(); i++) {
            CommentLine line = comments.get(i);
            Integer parent = line.parentOrdinal();
            // 자기 자신이나 뒤쪽을 가리키는 부모는 여기서 끊는다. DB 의 외래키가 잡아주긴
            // 하지만, 그때는 기사 전체 저장이 실패한다 — 댓글 하나 때문에 기사를 잃는 것보다
            // 그 댓글을 원댓글로 내리는 편이 낫다. 화면에서는 들여쓰기만 사라진다.
            Short parentOrdinal = (parent == null || parent < 1 || parent > i)
                    ? null : parent.shortValue();
            batch.add(new Object[] {
                    articleId, (short) (i + 1), line.body(), line.author(), parentOrdinal });
        }
        // batchUpdate 는 INSERT 를 한 번에 묶어 보낸다 — 댓글 15개면 왕복이 15번에서
        // 1번으로 준다. 세 번째 인자는 각 ? 의 SQL 타입인데, 이걸 주면 드라이버가 값을
        // 추측하지 않는다 (특히 null 을 넘길 때 타입 없이는 어떤 컬럼인지 모른다).
        jdbc.batchUpdate(INSERT_COMMENT, batch,
                new int[] { Types.BIGINT, Types.SMALLINT, Types.VARCHAR,
                        Types.VARCHAR, Types.SMALLINT });
    }

    private void replaceFindings(long articleId, List<Finding> findings) {
        jdbc.update("DELETE FROM article_finding WHERE article_id = ?", articleId);
        if (findings.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(findings.size());
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            batch.add(new Object[] {
                    articleId, (short) (i + 1), finding.severity().name(),
                    finding.what(), finding.evidence() });
        }
        jdbc.batchUpdate(INSERT_FINDING, batch,
                new int[] { Types.BIGINT, Types.SMALLINT, Types.VARCHAR,
                        Types.VARCHAR, Types.VARCHAR });
    }

    private static ArticleView toView(ResultSet rs, List<CommentLine> comments, List<Finding> findings)
            throws SQLException {
        return new ArticleView(
                rs.getLong("article_id"),
                rs.getInt("slot_id"),
                nullableInt(rs, "schedule_id"),
                nullableInt(rs, "competition_id"),
                rs.getString("competition_key"),
                rs.getInt("season"),
                rs.getInt("day"),
                nullableInt(rs, "round"),
                rs.getInt("blue_team_id"),
                rs.getInt("red_team_id"),
                rs.getString("blue_team_name"),
                rs.getString("red_team_name"),
                rs.getInt("blue_score"),
                rs.getInt("red_score"),
                rs.getInt("blue_kill"),
                rs.getInt("red_kill"),
                rs.getDouble("notability"),
                textArray(rs, "notability_reasons"),
                rs.getString("headline"),
                rs.getString("body"),
                rs.getString("brief_text"),
                rs.getString("model"),
                rs.getObject("generated_at", java.time.OffsetDateTime.class),
                FactStatus.valueOf(rs.getString("fact_status")),
                comments,
                findings);
    }

    /** {@code getInt} 는 NULL 을 0 으로 준다. 0 은 실제로 있을 수 있는 라운드 번호다. */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * {@code text[]} 컬럼 → 자바 목록.
     *
     * <p>{@link Array#getArray()} 는 {@code Object} 를 주는데 실제 타입은 {@code String[]} 이다
     * (드라이버가 컬럼 타입을 보고 정한다). 그래서 캐스팅이 필요하고, 그 캐스팅이 맞다는
     * 근거는 SQL 의 컬럼 타입뿐이다 — 컬럼 타입을 바꾸면 여기서 {@code ClassCastException} 이 난다.
     *
     * <p>{@code free()} 로 닫는 이유는 드라이버가 배열을 위해 잡아둔 자원을 바로 놓게 하기
     * 위해서다. 안 해도 결국 GC 가 정리하지만, 그 "결국" 이 언제인지는 보장이 없다.
     */
    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private static String describe(ArticleDraft draft) {
        return "슬롯 " + draft.slotId() + " 시즌 " + draft.season() + " 일 " + draft.day()
                + " (" + draft.blueTeamId() + " vs " + draft.redTeamId() + ")";
    }
}
