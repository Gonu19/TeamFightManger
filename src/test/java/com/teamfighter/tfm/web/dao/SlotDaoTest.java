package com.teamfighter.tfm.web.dao;

import com.teamfighter.tfm.web.view.SlotOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커리어 목록의 규칙.
 *
 * <p>{@code @Transactional} 이라 각 테스트가 넣은 슬롯은 끝나면 사라진다. 그래도 다른
 * 테스트와 실물이 남긴 슬롯이 목록에 섞여 있으므로, <b>내가 넣은 슬롯만 골라서</b> 본다 —
 * 목록의 길이를 세면 옆 테스트가 슬롯을 하나 더 넣을 때마다 이 테스트가 깨진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SlotDaoTest {

    @Autowired
    private SlotDao dao;

    @Autowired
    private JdbcTemplate jdbc;

    private int newSlot() {
        return jdbc.queryForObject("""
                INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id
                """, Integer.class, "slotdao_" + System.nanoTime());
    }

    private void team(int slotId, int gameTeamId, String name, boolean player) {
        jdbc.update("""
                INSERT INTO team (slot_id, game_team_id, name, is_player) VALUES (?, ?, ?, ?)
                """, slotId, gameTeamId, name, player);
    }

    private SlotOption find(List<SlotOption> options, int slotId) {
        return options.stream().filter(o -> o.slotId() == slotId).findFirst().orElse(null);
    }

    /**
     * <b>이 클래스가 생긴 이유다.</b> 티어 화면은 목록을 {@code champion_performance}
     * (집계 <b>결과</b> 표)에서 뽑고 있었다. 그래서 적재는 됐는데 집계를 안 돌린 커리어가
     * 고르개에서 통째로 사라졌고, 실물에서 사용자가 그것을 "적재가 안 됐다" 로 읽었다.
     */
    @Test
    @DisplayName("집계를 안 돌린 커리어도 목록에 남는다 — 빠지지 않고 '비어 있음' 이 된다")
    void 집계를_안_돌린_커리어도_목록에_남는다() {
        int slot = newSlot();

        SlotOption option = find(dao.options(Set.of()), slot);

        assertThat(option).isNotNull();
        assertThat(option.filled()).isFalse();
        assertThat(option.label()).contains("비어 있음");
    }

    /** 팀은 공식전에서만 식별된다 (D54). 스크림만 치른 커리어도 고를 수 있어야 한다. */
    @Test
    @DisplayName("팀이 아직 없는 새 커리어도 목록에 남는다 — 이름만 비어 있다")
    void 팀이_없는_새_커리어도_목록에_남는다() {
        int slot = newSlot();

        SlotOption option = find(dao.options(Set.of(slot)), slot);

        assertThat(option).isNotNull();
        assertThat(option.teamName()).isNull();
        assertThat(option.label()).isEqualTo("슬롯 " + slot);
    }

    @Test
    @DisplayName("이름은 플레이어 팀에서 온다 — 상대 팀은 안 쓴다")
    void 이름은_플레이어_팀에서_온다() {
        int slot = newSlot();
        team(slot, 1, "Ketos", true);
        team(slot, 2, "Ember scale", false);

        assertThat(find(dao.options(Set.of(slot)), slot).teamName()).isEqualTo("Ketos");
    }

    /**
     * {@code is_player} 는 유일성 제약이 아니라 그냥 불리언 칸이다. 조인으로 이름을 붙이면
     * 플레이어 팀이 둘인 커리어가 <b>고르개에 두 줄</b>로 그려진다 — 목록의 길이가 데이터의
     * 상태에 따라 달라지면 안 된다. 스칼라 서브질의가 커리어당 한 줄을 문법으로 보장한다.
     */
    @Test
    @DisplayName("플레이어 팀이 둘이어도 커리어는 한 줄이다")
    void 플레이어_팀이_둘이어도_한_줄이다() {
        int slot = newSlot();
        team(slot, 1, "Ketos", true);
        team(slot, 2, "Ketos II", true);

        List<SlotOption> mine = dao.options(Set.of()).stream()
                .filter(o -> o.slotId() == slot).toList();

        assertThat(mine).hasSize(1);
    }

    @Test
    @DisplayName("내용이 있는 커리어에는 표시가 안 붙는다")
    void 내용이_있으면_표시가_없다() {
        int slot = newSlot();

        assertThat(find(dao.options(Set.of(slot)), slot).filled()).isTrue();
        assertThat(find(dao.options(Set.of(slot)), slot).label()).doesNotContain("비어 있음");
    }

    @Test
    @DisplayName("번호 순으로 나온다")
    void 번호_순으로_나온다() {
        newSlot();
        newSlot();

        List<Integer> ids = dao.options(Set.of()).stream().map(SlotOption::slotId).toList();

        assertThat(ids).isSorted();
    }
}
