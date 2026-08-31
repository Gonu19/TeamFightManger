package com.teamfighter.tfm.web;

import com.teamfighter.tfm.story.dao.GalleryBatch;
import com.teamfighter.tfm.story.dao.GalleryDao;
import com.teamfighter.tfm.story.gallery.GalleryPost;
import com.teamfighter.tfm.story.gallery.GalleryPostKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게시판 화면. <b>여기서 지키는 것은 "방금 만든 갤러리로 갈 수 있는가" 하나다.</b>
 *
 * <h2>왜 그것이 시험할 값어치가 있나</h2>
 *
 * 페이지는 <b>경기 시점 순</b>이다. 그런데 생성기는 <b>갤러리가 아직 없는</b> 매치 중
 * 최근 것을 고르므로, 방금 만든 갤러리는 이미 갤러리가 있는 매치보다 <b>과거</b>이고
 * 따라서 첫 페이지가 아니다.
 *
 * <p>그 사실을 모르고 "다 됐으니 첫 페이지로" 를 보내면 두 가지가 한꺼번에 깨진다 —
 * 새 글이 안 보이고, 화면이 "아직 그 배치가 아니네" 하며 영원히 새로고침한다.
 * 실물에서 정확히 그렇게 돌았고, 배치는 멀쩡히 저장돼 있는데 사용자는 끝내 못 봤다.
 *
 * <p>그래서 <b>배치 번호로 여는 길</b>이 있고, 이 테스트가 그 길을 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GalleryControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GalleryDao galleries;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private int newSlot() {
        return jdbc.queryForObject("""
                INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id
                """, Integer.class, "web_" + System.nanoTime());
    }

    private int team(int slotId, int gameTeamId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO team (slot_id, game_team_id, name)
                VALUES (?, ?, ?) RETURNING team_id
                """, Integer.class, slotId, gameTeamId, name);
    }

    private long batch(int slotId, int blue, int red, int day, String title) {
        return galleries.save(
                new GalleryBatch(slotId, null, 2025, day, blue, red, 2, 0, "test-model", 2),
                List.of(new GalleryPost(GalleryPostKind.LIVE, title, "ㅇㅇ(1.2)", "본문",
                        100, 3, false, null, "16:40", List.of())));
    }

    @Test
    @DisplayName("배치 번호로 열면 그 페이지가 뜬다 — 첫 페이지가 아니어도")
    void opensByBatchIdEvenWhenItIsNotTheFirstPage() throws Exception {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        long recent = batch(slotId, blue, red, 41, "41일 경기 갤");
        long older = batch(slotId, blue, red, 40, "40일 경기 갤");   // 나중에 만들었지만 과거 경기다

        // 첫 페이지는 경기 시점이 최근인 쪽이다
        assertThat(galleries.pages(slotId)).extracting(v -> v.batchId())
                .containsExactly(recent, older);

        // batch 를 주면 그 배치가 뜬다. 이게 없으면 방금 만든 갤을 영영 못 본다.
        mvc().perform(get("/gallery").param("slot", String.valueOf(slotId))
                        .param("batch", String.valueOf(older)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("40일 경기 갤")));
    }

    @Test
    @DisplayName("batch 없이 열면 최근 경기 페이지다")
    void defaultsToTheMostRecentMatch() throws Exception {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        batch(slotId, blue, red, 41, "41일 경기 갤");
        batch(slotId, blue, red, 40, "40일 경기 갤");

        mvc().perform(get("/gallery").param("slot", String.valueOf(slotId)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("41일 경기 갤")));
    }

    @Test
    @DisplayName("없는 배치 번호는 무시하고 page 로 물러선다 — 404 가 아니다")
    void unknownBatchFallsBackInsteadOfFailing() throws Exception {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");
        batch(slotId, blue, red, 41, "41일 경기 갤");

        // 지워졌거나 다른 커리어의 번호다. 화면이 죽는 것보다 첫 페이지를 그리는 편이 낫다.
        mvc().perform(get("/gallery").param("slot", String.valueOf(slotId))
                        .param("batch", "999999"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("41일 경기 갤")));
    }

    @Test
    @DisplayName("갤러리가 하나도 없어도 화면은 뜬다")
    void emptyGalleryStillRenders() throws Exception {
        // 첫 갤러리를 뽑으려면 이 화면의 버튼이 필요하다 — 여기서 죽으면 영영 못 뽑는다
        mvc().perform(get("/gallery").param("slot", String.valueOf(newSlot())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("진행 상황은 한 번도 안 눌렀으면 IDLE 이다")
    void statusIsIdleBeforeAnyRun() throws Exception {
        mvc().perform(get("/gallery/status").param("slot", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("IDLE")));
    }
}
