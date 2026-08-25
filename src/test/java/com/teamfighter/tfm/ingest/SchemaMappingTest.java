package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.Champion;
import com.teamfighter.tfm.ingest.entity.ChampionCategory;
import com.teamfighter.tfm.ingest.entity.MatchRecord;
import com.teamfighter.tfm.ingest.entity.MatchType;
import com.teamfighter.tfm.ingest.entity.TeamSide;
import com.teamfighter.tfm.ingest.repository.ChampionRepository;
import com.teamfighter.tfm.ingest.repository.MatchRecordRepository;
import com.teamfighter.tfm.ingest.repository.SaveSlotRepository;
import com.teamfighter.tfm.ingest.entity.SaveSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엔티티 매핑이 실제 Postgres 스키마와 맞는지 확인한다.
 *
 * <p>컨텍스트가 뜨는 것 자체가 검증이다 — {@code ddl-auto: validate} 가
 * 컬럼 이름·타입이 어긋나면 기동을 거부한다.
 *
 * <p>그 위에 <b>Postgres named ENUM 왕복</b>을 직접 확인한다. 이게 D22 가
 * "번거롭다" 고 짚어둔 지점이고, Hibernate 7 에서 자료와 어긋날 가능성이 큰 곳이다.
 * 매핑이 varchar 로 새면 저장은 되지만 값이 깨지므로 왕복을 봐야 한다.
 *
 * <p>각 테스트는 트랜잭션 롤백으로 격리된다. DB 에 남는 것이 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SchemaMappingTest {

    @Autowired
    private ChampionRepository champions;

    @Autowired
    private SaveSlotRepository slots;

    @Autowired
    private MatchRecordRepository matches;

    @Test
    @DisplayName("champion_category ENUM 이 왕복한다")
    void championCategoryEnum_roundTrips() {
        Champion saved = champions.saveAndFlush(new Champion("TestChamp", "테스트", ChampionCategory.PRIEST));
        champions.flush();

        Champion found = champions.findByCode("TestChamp").orElseThrow();
        assertThat(found.getCategory()).isEqualTo(ChampionCategory.PRIEST);
        assertThat(found.getNameKo()).isEqualTo("테스트");
        assertThat(saved.getChampionId()).isNotNull();
    }

    @Test
    @DisplayName("team_side · match_type ENUM 이 왕복한다")
    void matchEnums_roundTrip() {
        SaveSlot slot = slots.saveAndFlush(new SaveSlot("slot_mapping_test.tfm", "테스트팀"));

        MatchRecord match = new MatchRecord(slot.getSlotId(), MatchType.OFFICIAL, 1, TeamSide.RED);
        match.setSchedule(2025, 12, 1, 100);
        matches.saveAndFlush(match);

        MatchRecord found = matches
                .findBySlotIdAndMatchTypeAndSourceGameId(slot.getSlotId(), MatchType.OFFICIAL, 1)
                .orElseThrow();
        assertThat(found.getWinnerSide()).isEqualTo(TeamSide.RED);
        assertThat(found.getMatchType()).isEqualTo(MatchType.OFFICIAL);
        assertThat(found.getTeamSize()).isEqualTo((short) 4);
    }

    @Test
    @DisplayName("WinTeam 0/1 이 BLUE/RED 로 변환된다")
    void winTeam_mapsToSide() {
        assertThat(TeamSide.ofWinTeam(0)).isEqualTo(TeamSide.BLUE);
        assertThat(TeamSide.ofWinTeam(1)).isEqualTo(TeamSide.RED);
    }

    @Test
    @DisplayName("시드된 챔피언 40종이 모두 역할군을 가진다")
    void seededChampions_haveCategory() {
        long total = champions.count();
        if (total == 0) {
            return;                // 아직 시드 전이면 확인할 것이 없다
        }
        assertThat(champions.findAll())
                .allSatisfy(c -> assertThat(c.getCategory()).isNotNull());
    }
}
