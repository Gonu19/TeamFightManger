package com.teamfighter.tfm.analysis;

import com.teamfighter.tfm.analysis.counter.CounterRow;
import com.teamfighter.tfm.analysis.dao.AggRunRecorder;
import com.teamfighter.tfm.analysis.dao.CounterWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 업서트와 집계 한 바퀴를 확인한다.
 *
 * <p>DB 가 필요하다. 트랜잭션 롤백으로 격리된다.
 *
 * <p>여기서 보는 것은 계산이 아니라 <b>쓰기가 같은 행을 덮어쓰는가</b> 다. 유일키에 NULL 이
 * 들어가는데({@code GLOBAL} 은 slot·patch 가 NULL, 1단은 patch 가 NULL) 보통의 UNIQUE 는
 * NULL 을 서로 다른 값으로 본다. 그러면 집계를 돌릴 때마다 같은 쌍이 한 벌씩 쌓이는데,
 * 화면은 그중 아무거나 하나를 보여주므로 <b>오래된 값이 그대로 떠 있어도 아무도 모른다.</b>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CounterAggregationServiceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CounterWriter writer;

    @Autowired
    private AggRunRecorder runs;

    @Autowired
    private CounterAggregationService service;

    private List<Integer> twoChampions() {
        return jdbc.queryForList(
                "SELECT champion_id FROM champion ORDER BY champion_id LIMIT 2", Integer.class);
    }

    private CounterRow row(int championId, int opponentId, double effect) {
        return new CounterRow(championId, opponentId, 10, 7, 10, 7, 10, 0.5, 0.6, effect);
    }

    private long newRun() {
        return runs.start(new AnalysisConfig(10, 24, 15, 3, 2, 12), "테스트");
    }

    private int countRows(String scopeClause) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM champion_matchup WHERE " + scopeClause, Integer.class);
    }

    @Test
    @DisplayName("GLOBAL 행을 두 번 써도 한 행이다 — slot·patch 가 NULL 인데도 겹쳐 쓴다")
    void write_globalRowIsUpsertedNotDuplicated() {
        List<Integer> champs = twoChampions();
        long runId = newRun();

        writer.write(AggScope.GLOBAL, null, null, true, runId,
                List.of(row(champs.get(0), champs.get(1), 0.11)));
        writer.write(AggScope.GLOBAL, null, null, true, runId,
                List.of(row(champs.get(0), champs.get(1), 0.22)));

        // 변조: ON CONFLICT 절을 지우면 두 행이 쌓이고, 화면은 그중 하나만 본다.
        //       예외도 로그도 없이 오래된 값이 계속 떠 있게 된다.
        assertThat(countRows("champion_id = " + champs.get(0))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT counter_effect FROM champion_matchup WHERE champion_id = ?",
                Double.class, champs.get(0)))
                .isEqualTo(0.22);
    }

    @Test
    @DisplayName("스코프가 다르면 다른 행이다 — CAREER 와 GLOBAL 이 서로를 덮지 않는다")
    void write_scopesAreSeparateRows() {
        List<Integer> champs = twoChampions();
        long runId = newRun();
        int slot = jdbc.queryForObject(
                "INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id",
                Integer.class, "upsert_" + System.nanoTime());

        writer.write(AggScope.GLOBAL, null, null, true, runId,
                List.of(row(champs.get(0), champs.get(1), 0.11)));
        writer.write(AggScope.CAREER, slot, null, true, runId,
                List.of(row(champs.get(0), champs.get(1), 0.33)));

        assertThat(countRows("champion_id = " + champs.get(0))).isEqualTo(2);
    }

    @Test
    @DisplayName("include_scrim 이 다르면 다른 행이다 — 화면이 두 벌을 껐다 켰다 한다")
    void write_scrimVariantsAreSeparateRows() {
        List<Integer> champs = twoChampions();
        long runId = newRun();

        writer.write(AggScope.GLOBAL, null, null, true, runId,
                List.of(row(champs.get(0), champs.get(1), 0.11)));
        writer.write(AggScope.GLOBAL, null, null, false, runId,
                List.of(row(champs.get(0), champs.get(1), 0.44)));

        assertThat(countRows("champion_id = " + champs.get(0))).isEqualTo(2);
    }

    @Test
    @DisplayName("agg_run 은 시작 때 열리고 끝나면 닫힌다 — 쓴 임계값이 함께 남는다")
    void aggRun_recordsThresholdsAndClosingTime() {
        long runId = runs.start(new AnalysisConfig(10, 24, 15, 3, 2, 12), "테스트");

        assertThat(jdbc.queryForObject(
                "SELECT min_sample FROM agg_run WHERE agg_run_id = ?", Integer.class, runId))
                .isEqualTo(10);
        assertThat(jdbc.queryForObject(
                "SELECT finished_at FROM agg_run WHERE agg_run_id = ?", Object.class, runId))
                .isNull();

        runs.finish(runId);

        assertThat(jdbc.queryForObject(
                "SELECT finished_at FROM agg_run WHERE agg_run_id = ?", Object.class, runId))
                .isNotNull();
    }

    @Test
    @DisplayName("경기가 없어도 집계 한 바퀴가 돈다 — 빈 DB 에서 죽지 않는다")
    void run_survivesEmptyDatabase() {
        CounterAggregationService.Result result = service.run();

        assertThat(result.aggRunId()).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT finished_at FROM agg_run WHERE agg_run_id = ?",
                Object.class, result.aggRunId()))
                .isNotNull();
    }
}
