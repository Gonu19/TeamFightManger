package com.teamfighter.tfm.analysis.dao;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import com.teamfighter.tfm.analysis.MatchObservation;
import com.teamfighter.tfm.analysis.ReferencePoint;
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
 * 집계용 조회가 실제 스키마와 맞는지 확인한다.
 *
 * <p>DB 가 필요하다. 각 테스트는 트랜잭션 롤백으로 격리되고 DB 에 남는 것이 없다.
 * 픽스처 세이브 파일은 쓰지 않는다 — 필요한 행을 직접 넣는다. 그래야 "team_size 가 4가
 * 아닌 경기" 처럼 실제 세이브에 몇 건 없는 경우를 확실히 만들 수 있다.
 *
 * <p>여기서 보는 것은 <b>SQL 이 무엇을 빼먹는가</b> 다. 조인이 하나 어긋나 경기가 통째로
 * 빠져도 결과는 "데이터가 좀 적네" 로 보일 뿐 아무 예외도 나지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnalysisDaoTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MatchObservationDao dao;

    @Autowired
    private AnalysisConfigDao configDao;

    private int newSlot() {
        return jdbc.queryForObject("""
                INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id
                """, Integer.class, "test_" + System.nanoTime());
    }

    private int patch(int slotId, int season, int day, int seq) {
        return jdbc.queryForObject("""
                INSERT INTO patch (slot_id, season, day, seq)
                VALUES (?, ?, ?, ?) RETURNING patch_id
                """, Integer.class, slotId, season, day, seq);
    }

    private void patchEvent(int patchId, int championId, boolean isNew) {
        jdbc.update("""
                INSERT INTO champion_patch_event (patch_id, champion_id, attack, is_new)
                VALUES (?, ?, 1, ?)
                """, patchId, championId, isNew);
    }

    private List<Integer> someChampionIds(int count) {
        return jdbc.queryForList(
                "SELECT champion_id FROM champion ORDER BY champion_id LIMIT ?",
                Integer.class, count);
    }

    private long match(int slotId, String type, Integer patchId, int teamSize, int sourceId) {
        return jdbc.queryForObject("""
                INSERT INTO match_record
                    (slot_id, match_type, source_game_id, season, day, patch_id,
                     team_size, winner_side)
                VALUES (?, ?::match_type, ?, 1, 10, ?, ?, 'BLUE'::team_side)
                RETURNING match_id
                """, Long.class, slotId, type, sourceId, patchId, teamSize);
    }

    private void participant(long matchId, String side, int pickOrder, int championId, int changeCount) {
        jdbc.update("""
                INSERT INTO match_participant
                    (match_id, side, pick_order, champion_id, change_count)
                VALUES (?, ?::team_side, ?, ?, ?)
                """, matchId, side, pickOrder, championId, changeCount);
    }

    /** 4v4 한 경기. 승리 진영은 BLUE 로 고정했다. */
    private void fillFourVersusFour(long matchId, List<Integer> champions) {
        for (int i = 0; i < 4; i++) {
            participant(matchId, "BLUE", i + 1, champions.get(i), 0);
        }
        for (int i = 0; i < 4; i++) {
            participant(matchId, "RED", i + 1, champions.get(i + 4), 0);
        }
    }

    @Test
    @DisplayName("analysis_config 여섯 키가 마이그레이션이 남긴 값 그대로 읽힌다 (D44)")
    void configDao_readsSeededValues() {
        AnalysisConfig config = configDao.load();

        assertThat(config.minSample()).isEqualTo(10);
        assertThat(config.priorK0()).isEqualTo(24.0);
        assertThat(config.priorK1()).isEqualTo(15.0);
        assertThat(config.synergyMaxSize()).isEqualTo(3);      // 이름이 "여섯 키" 인데 다섯만 봤다
        assertThat(config.selfDecayHalfLife()).isEqualTo(2.0);
        // V1 이 12 로 시드하고 V5 가 2 로 바꾼다 (D53). 기대값은 마지막 마이그레이션 뒤의
        // 값이어야 한다 — 시드값을 적어두면 이 테스트가 V1 만 지키고 그 뒤를 놓친다.
        assertThat(config.metaDecayHalfLife()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("4v4 경기가 승리 팀 4명 · 패배 팀 4명으로 내려온다 — 진영이 아니라 승패다")
    void loadMatches_splitsByWinLossNotSide() {
        int slot = newSlot();
        int patchId = patch(slot, 1, 5, 1);
        List<Integer> champs = someChampionIds(8);
        long matchId = match(slot, "OFFICIAL", patchId, 4, 1);
        fillFourVersusFour(matchId, champs);

        List<MatchObservation> matches = dao.loadMatches(slot, true);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).winners()).hasSize(4);
        assertThat(matches.get(0).losers()).hasSize(4);
        assertThat(matches.get(0).patchSeq()).isEqualTo(1);
        // 승리 진영이 BLUE 였으므로 앞의 4명이 승자다.
        assertThat(matches.get(0).winners())
                .extracting(MatchObservation.Participant::championId)
                .containsExactlyInAnyOrderElementsOf(champs.subList(0, 4));
    }

    @Test
    @DisplayName("DB 가 비4v4 경기를 애초에 거부한다 — 규칙이 문서가 아니라 제약으로 있다 (V2·D35)")
    void schema_rejectsNonFourVersusFour() {
        int slot = newSlot();

        // 이 프로젝트에서 D35 는 코드 규약이 아니라 CHECK 제약이다. 집계가 "인원이 다른
        // 경기를 걸러낸다" 고 주장하기 전에, 그런 경기가 DB 에 들어올 수 있는지부터 봐야 한다.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> match(slot, "SCRIM", null, 3, 1))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("team_size 필터는 DB 제약이 풀려도 남는 두 번째 방어선이다 (D35)")
    void loadMatches_excludesNonFourVersusFourEvenWithoutTheConstraint() {
        int slot = newSlot();
        List<Integer> champs = someChampionIds(8);

        // 제약을 이 트랜잭션 안에서만 떼어낸다. Postgres 는 DDL 도 롤백하므로 테스트가
        // 끝나면 제약이 그대로 돌아온다. D35 의 "뒤집힐 조건" 이 실현돼 2·3인 경기를
        // 다시 적재하게 되더라도, 카운터 집계는 그것을 섞으면 안 된다 — 그때 이 필터가
        // 유일한 방어선이 된다.
        jdbc.execute("ALTER TABLE match_record DROP CONSTRAINT match_record_team_size_check");

        long threeVersusThree = match(slot, "SCRIM", null, 3, 1);
        for (int i = 0; i < 3; i++) {
            participant(threeVersusThree, "BLUE", i + 1, champs.get(i), 0);
            participant(threeVersusThree, "RED", i + 1, champs.get(i + 4), 0);
        }
        long fourVersusFour = match(slot, "SCRIM", null, 4, 2);
        fillFourVersusFour(fourVersusFour, champs);

        // 변조: WHERE 절의 team_size = 4 를 지우면 2건이 나온다.
        assertThat(dao.loadMatches(slot, true)).hasSize(1);
        assertThat(dao.loadMatches(slot, true).get(0).winners()).hasSize(4);
    }

    @Test
    @DisplayName("include_scrim=false 면 스크림이 빠지고 공식전만 남는다")
    void loadMatches_scrimFilter() {
        int slot = newSlot();
        List<Integer> champs = someChampionIds(8);
        long official = match(slot, "OFFICIAL", null, 4, 1);
        fillFourVersusFour(official, champs);
        long scrim = match(slot, "SCRIM", null, 4, 2);
        fillFourVersusFour(scrim, champs);

        assertThat(dao.loadMatches(slot, true)).hasSize(2);
        // 변조: WHERE 절의 include_scrim 조건을 지우면 여기서도 2가 나온다.
        assertThat(dao.loadMatches(slot, false)).hasSize(1);
    }

    @Test
    @DisplayName("패치가 배정되지 않은 경기도 내려온다 — INNER JOIN 이면 통째로 사라진다")
    void loadMatches_keepsMatchesWithoutPatch() {
        int slot = newSlot();
        List<Integer> champs = someChampionIds(8);
        long matchId = match(slot, "OFFICIAL", null, 4, 1);
        fillFourVersusFour(matchId, champs);

        List<MatchObservation> matches = dao.loadMatches(slot, true);

        // 변조: patch 조인을 LEFT 에서 INNER 로 바꾸면 이 경기가 사라져 hasSize(1) 이 깨진다.
        //       그 변조는 예외를 내지 않는다 — 표본이 조용히 줄 뿐이다.
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).patchSeq()).isNull();
    }

    @Test
    @DisplayName("다른 슬롯의 경기는 섞이지 않는다 — 패치 역사가 슬롯마다 다르다")
    void loadMatches_isScopedToSlot() {
        int mine = newSlot();
        int other = newSlot();
        List<Integer> champs = someChampionIds(8);
        fillFourVersusFour(match(mine, "OFFICIAL", null, 4, 1), champs);
        fillFourVersusFour(match(other, "OFFICIAL", null, 4, 1), champs);

        assertThat(dao.loadMatches(mine, true)).hasSize(1);
    }

    @Test
    @DisplayName("경기가 여러 건이면 각각 따로 조립된다 — 경기 경계가 뭉개지지 않는다")
    void loadMatches_separatesConsecutiveMatches() {
        int slot = newSlot();
        List<Integer> champs = someChampionIds(8);
        fillFourVersusFour(match(slot, "OFFICIAL", null, 4, 1), champs);
        fillFourVersusFour(match(slot, "OFFICIAL", null, 4, 2), champs);
        fillFourVersusFour(match(slot, "OFFICIAL", null, 4, 3), champs);

        List<MatchObservation> matches = dao.loadMatches(slot, true);

        // 변조: 경기가 바뀔 때 flush 하지 않으면 한 덩어리가 되어 hasSize(1) 이 되고,
        //       그때는 같은 챔피언이 여러 번 들어가 MatchObservation 이 던진다.
        assertThat(matches).hasSize(3);
        assertThat(matches).allSatisfy(m -> {
            assertThat(m.winners()).hasSize(4);
            assertThat(m.losers()).hasSize(4);
        });
    }

    @Test
    @DisplayName("경기 시점의 change_count 가 그대로 실려 온다")
    void loadMatches_carriesChangeCount() {
        int slot = newSlot();
        List<Integer> champs = someChampionIds(8);
        long matchId = match(slot, "OFFICIAL", null, 4, 1);
        participant(matchId, "BLUE", 1, champs.get(0), 3);
        for (int i = 1; i < 4; i++) {
            participant(matchId, "BLUE", i + 1, champs.get(i), 0);
        }
        for (int i = 0; i < 4; i++) {
            participant(matchId, "RED", i + 1, champs.get(i + 4), 0);
        }

        MatchObservation observation = dao.loadMatches(slot, true).get(0);

        assertThat(observation.winners())
                .filteredOn(p -> p.championId() == champs.get(0))
                .singleElement()
                .extracting(MatchObservation.Participant::changeCountAtMatch)
                .isEqualTo(3);
    }

    @Test
    @DisplayName("기준 시점은 그 슬롯의 마지막 패치이고, 누적 변경 횟수는 적재와 같은 방식으로 센다")
    void latestReference_countsEveryPatchEvent() {
        int slot = newSlot();
        List<Integer> champs = someChampionIds(2);
        int first = patch(slot, 1, 5, 1);
        int second = patch(slot, 1, 9, 2);
        int third = patch(slot, 2, 3, 3);
        patchEvent(first, champs.get(0), true);     // 신규 등장도 센다 — 적재가 그렇게 센다
        patchEvent(second, champs.get(0), false);
        patchEvent(third, champs.get(1), false);

        ReferencePoint ref = dao.latestReference(slot);

        assertThat(ref.patchSeq()).isEqualTo(3);
        // 변조: is_new 인 행을 빼면 2가 아니라 1이 나온다. 그러면 적재가 넣은
        //       change_count 와 기준이 어긋나서 감쇠 지수가 아무 의미 없는 값이 된다.
        assertThat(ref.changeCountByChampion()).containsEntry(champs.get(0), 2);
        assertThat(ref.changeCountByChampion()).containsEntry(champs.get(1), 1);
    }

    @Test
    @DisplayName("과거 패치를 기준으로 잡으면 그 시점까지만 센다 (D24)")
    void referenceAtSeq_truncatesAtChosenPatch() {
        int slot = newSlot();
        List<Integer> champs = someChampionIds(1);
        patchEvent(patch(slot, 1, 5, 1), champs.get(0), false);
        patchEvent(patch(slot, 1, 9, 2), champs.get(0), false);
        patchEvent(patch(slot, 2, 3, 3), champs.get(0), false);

        ReferencePoint ref = dao.referenceAtSeq(slot, 2);

        // 변조: seq 조건을 지우면 3이 나온다 — 고른 패치 이후의 변경까지 세게 된다.
        assertThat(ref.changeCountByChampion()).containsEntry(champs.get(0), 2);
    }

    @Test
    @DisplayName("패치가 하나도 없는 슬롯도 기준 시점을 낸다 — 커리어 시작 직후다")
    void latestReference_slotWithoutPatches() {
        ReferencePoint ref = dao.latestReference(newSlot());

        assertThat(ref.patchSeq()).isZero();
        assertThat(ref.changeCountByChampion()).isEmpty();
    }

    @Test
    @DisplayName("경기가 없는 슬롯은 빈 목록이다 — 예외가 아니다")
    void loadMatches_emptySlot() {
        assertThat(dao.loadMatches(newSlot(), true)).isEmpty();
    }

    @Test
    @DisplayName("슬롯 목록을 낸다")
    void slotIds_listsSlots() {
        int slot = newSlot();

        assertThat(dao.slotIds()).contains(slot);
    }
}
