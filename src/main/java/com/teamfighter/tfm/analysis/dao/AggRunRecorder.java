package com.teamfighter.tfm.analysis.dao;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 집계 한 번을 {@code agg_run} 에 남긴다.
 *
 * <p>남기는 것은 시각만이 아니라 <b>그때 쓴 임계값</b>이다. {@code analysis_config} 는
 * 나중에 바뀔 수 있는데(D9·D15 가 그러라고 빼둔 값들이다), 그러면 지금 화면에 떠 있는 숫자가
 * 어떤 설정으로 나온 것인지 알 수 없게 된다. 값이 이상해 보일 때 "설정을 언제 바꿨더라" 를
 * 기억에 의존해 따지는 상황을 만들지 않는다.
 *
 * <p>완료 시각은 끝난 뒤에 채운다. <b>실패한 집계의 흔적이 여기 남지는 않는다</b> —
 * 집계 전체가 한 트랜잭션이라 도중에 죽으면 이 행도 함께 롤백된다. 그건 의도한 것이다:
 * 절반만 갱신된 카운터 표가 화면에 뜨는 것보다 아무것도 안 바뀌는 편이 낫고, 실패는
 * 예외로 올라가므로 조용하지 않다. {@code finished_at} 이 NULL 인 행이 보인다면 그건
 * 실패의 기록이 아니라 <b>지금 돌고 있는 집계</b>다.
 */
@Repository
public class AggRunRecorder {

    private final JdbcTemplate jdbc;

    public AggRunRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start(AnalysisConfig config, String note) {
        Long runId = jdbc.queryForObject("""
                INSERT INTO agg_run (min_sample, prior_strength, note)
                VALUES (?, ?, ?)
                RETURNING agg_run_id
                """, Long.class, config.minSample(), config.priorK0(), note);
        if (runId == null) {
            throw new IllegalStateException("agg_run 을 만들지 못했다 — RETURNING 이 비었다");
        }
        return runId;
    }

    public void finish(long aggRunId) {
        int updated = jdbc.update(
                "UPDATE agg_run SET finished_at = now() WHERE agg_run_id = ?", aggRunId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "agg_run " + aggRunId + " 를 닫지 못했다. 갱신된 행: " + updated);
        }
    }
}
