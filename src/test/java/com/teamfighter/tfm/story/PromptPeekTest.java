package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.MatchScheduleParser;
import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.save.SaveParser;
import com.teamfighter.tfm.story.dao.StoryReference;
import com.teamfighter.tfm.story.dao.StoryReferenceDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실물 세이브로 <b>프롬프트를 눈으로 본다</b>. 모델은 안 부른다.
 *
 * <p>기본으로 꺼져 있다 — 픽스처 세이브가 gitignore 라 이 PC 밖에서는 파일이 없다.
 * {@code TFM_PEEK_SAVE} 에 세이브 경로를 넣으면 돈다.
 *
 * <p>{@code MatchBriefRealSaveTest} 와 나누는 이유: 저쪽은 <b>사실이 맞는가</b>를 재고
 * 이쪽은 <b>모델에게 무엇이 나가는가</b>를 본다. 한글 이름이 실제로 프롬프트까지
 * 흘러가는지는 합성 데이터로는 확인이 안 된다 — 코드 → 한글 표가 DB 에서 오기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@EnabledIfEnvironmentVariable(named = "TFM_PEEK_SAVE", matches = ".+")
class PromptPeekTest {

    @Autowired
    private StoryReferenceDao references;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("실물 세이브의 프롬프트에 영어 챔피언 코드가 없다")
    void therealPromptSpeaksKorean() throws Exception {
        Path save = Path.of(System.getenv("TFM_PEEK_SAVE"));
        assertThat(Files.exists(save)).as("세이브 파일").isTrue();

        List<ParsedSchedule> schedules = MatchScheduleParser.read(save);
        List<ParsedGame> sets = SaveParser.read(save).gameStats();

        Map<ParsedSchedule.MatchKey, List<ParsedGame>> byMatch = new LinkedHashMap<>();
        for (ParsedGame set : sets) {
            if (set.season() == null || set.day() == null
                    || set.blueTeamId() == null || set.redTeamId() == null) {
                continue;
            }
            byMatch.computeIfAbsent(ParsedSchedule.MatchKey.of(
                    set.season(), set.day(), set.blueTeamId(), set.redTeamId()),
                    k -> new ArrayList<>()).add(set);
        }

        ParsedSchedule match = schedules.stream()
                .filter(ParsedSchedule::isPlayed)
                .filter(m -> byMatch.containsKey(m.matchKey()))
                .max(Comparator.comparingInt((ParsedSchedule m) -> m.season() == null ? 0 : m.season())
                        .thenComparingInt(m -> m.day() == null ? 0 : m.day()))
                .orElseThrow(() -> new IllegalStateException("끝난 매치가 없다"));

        // 시험 DB 에는 슬롯이 없다(운영 DB 와 다른 데이터베이스다). 챔피언 시드는
        // V3 로 들어와 있으므로 빈 슬롯 하나만 만들면 이름표가 선다 —
        // 팀·선수 이름은 번호로 떨어지지만 여기서 보는 것은 챔피언이다.
        int slotId = jdbc.queryForObject(
                "INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id",
                Integer.class, "peek_" + System.nanoTime());
        StoryReference reference = references.load(slotId);
        MatchBrief brief = MatchBrief.of(match, byMatch.get(match.matchKey()));

        String rendered = BriefRenderer.render(brief, reference);
        String totals = StoryPrompts.playerTotals(brief, reference);

        System.out.println("=== 기사 프롬프트가 보는 사실 블록 ===\n" + rendered);
        System.out.println("=== 갤러리·댓글이 보는 선수 표 ===\n" + totals);

        // 이 매치에 실제로 나온 코드가 하나라도 그대로 남아 있으면 어딘가 이름표를 안 거친 것이다.
        for (String code : reference.championNameByCode().keySet()) {
            String korean = reference.championName(code);
            if (korean.equals(code)) {
                continue;                                   // 한글 이름이 없는 챔피언은 코드가 정상이다
            }
            assertThat(rendered).as("사실 블록에 남은 코드: " + code).doesNotContain(code);
            assertThat(totals).as("선수 표에 남은 코드: " + code).doesNotContain(code);
        }
    }
}
