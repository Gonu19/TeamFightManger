package com.teamfighter.tfm.story.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이름표가 <b>플레이어 팀을 들고 오는지</b> 본다.
 *
 * <p>이 한 값이 비면 {@code Notability} 의 "내 팀" 항이 통째로 빠지고, 내 팀 경기가
 * 남의 팀 경기와 똑같은 분량으로 나온다. 화면은 멀쩡해 보이므로 <b>여기서 안 잡으면
 * 아무 데서도 안 잡힌다</b> — 기사가 짧다는 느낌만 남는다.
 *
 * <p>DB 가 필요하다. 각 테스트는 트랜잭션 롤백으로 격리된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StoryReferenceDaoTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StoryReferenceDao dao;

    private int newSlot() {
        return jdbc.queryForObject("""
                INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id
                """, Integer.class, "ref_" + System.nanoTime());
    }

    private void team(int slotId, int gameTeamId, String name, boolean isPlayer) {
        jdbc.update("""
                INSERT INTO team (slot_id, game_team_id, name, is_player) VALUES (?, ?, ?, ?)
                """, slotId, gameTeamId, name, isPlayer);
    }

    @Test
    @DisplayName("is_player 팀의 세이브 번호를 들고 온다")
    void loadsPlayerTeam() {
        int slotId = newSlot();
        team(slotId, 0, "Seorabal Gaming", true);                               // D54: 플레이어 팀은 보통 0 이다
        team(slotId, 33, "Bahamut", false);

        assertThat(dao.load(slotId).playerGameTeamId()).isEqualTo(0);
    }

    @Test
    @DisplayName("플레이어 팀이 없으면 null — 0 이 아니다")
    void nullWhenNoPlayerTeam() {
        int slotId = newSlot();
        team(slotId, 33, "Bahamut", false);

        // 0 으로 채우면 game_team_id = 0 인 AI 팀이 있는 커리어에서 남의 팀이 내 팀이 된다.
        assertThat(dao.load(slotId).playerGameTeamId()).isNull();
    }

    @Test
    @DisplayName("플레이어 팀이 둘이면 던진다 — 하나를 고르면 남의 커리어 기사가 된다")
    void throwsWhenTwoPlayerTeams() {
        int slotId = newSlot();
        team(slotId, 0, "Seorabal Gaming", true);
        team(slotId, 33, "Bahamut", true);

        // @Repository 가 예외를 변환한다 — IllegalStateException 이 그대로 나오지 않는다
        assertThatThrownBy(() -> dao.load(slotId))
                .isInstanceOfAny(IllegalStateException.class, InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("플레이어 팀이 둘");
    }
}
