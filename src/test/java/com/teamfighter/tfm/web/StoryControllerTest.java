package com.teamfighter.tfm.web;

import com.teamfighter.tfm.story.ArticleDraft;
import com.teamfighter.tfm.story.ArticleDraft.Finding;
import com.teamfighter.tfm.story.ArticleDraft.Severity;
import com.teamfighter.tfm.story.dao.ArticleDao;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 화면이 그려지는지 본다. <b>템플릿까지 진짜로 렌더링한다.</b>
 *
 * <p>뷰 이름만 확인하면 Thymeleaf 표현식의 오타는 하나도 안 잡힌다 — 그런 오류는 컴파일도
 * 통과하고 테스트도 통과한 다음 브라우저에서만 터진다. 그래서 응답 본문까지 읽는다.
 *
 * <p>{@code @Transactional} 이라 각 테스트가 넣은 기사는 끝나면 사라진다. 목록 화면이
 * "기사가 있는 첫 슬롯" 을 고르므로, 다른 테스트가 남긴 기사가 있으면 결과가 흔들린다 —
 * 그래서 목록 테스트는 <b>슬롯을 명시</b>해서 부른다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StoryControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ArticleDao articles;

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

    private long article(int slotId, int blue, int red, int season, int day,
                         String headline, List<Finding> findings) {
        return articles.save(new ArticleDraft(
                slotId, 7, 3, "competition.name.spring", season, day, 2,
                blue, red, 2, 1, 41, 38,
                0.62, List.of("순위 다툼"),
                headline, "첫 세트를 내준 쪽이 남은 둘을 가져갔다.",
                "블루 2 - 1 레드 · 킬 41 - 38",
                "openai/gpt-oss-120b",
                List.of("이게 실화냐", "다음 경기도 보자"),
                findings));
    }

    @Test
    @DisplayName("목록이 제목과 팀 실명을 그린다")
    void listRenders() throws Exception {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal Gaming");
        int red = team(slotId, 34, "OZ Gaming");
        article(slotId, blue, red, 2, 30, "완봉으로 끝난 승부", List.of());

        mvc().perform(get("/story").param("slot", String.valueOf(slotId)))
                .andExpect(status().isOk())
                .andExpect(view().name("story/list"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("완봉으로 끝난 승부")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Seorabal Gaming")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("OZ Gaming")));
    }

    @Test
    @DisplayName("목록이 모순 기사를 표시한다 — 열어보기 전에 보인다")
    void listShowsContradiction() throws Exception {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal Gaming");
        int red = team(slotId, 34, "OZ Gaming");
        article(slotId, blue, red, 2, 30, "의심스러운 기사",
                List.of(new Finding(Severity.CONTRADICTION, "스코어가 다르다", "3-0 으로")));

        mvc().perform(get("/story").param("slot", String.valueOf(slotId)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("대조에서 모순")));
    }

    @Test
    @DisplayName("기사가 없는 커리어는 빈 화면이다 — 오류가 아니다")
    void emptySlotIsNotAnError() throws Exception {
        int slotId = newSlot();

        mvc().perform(get("/story").param("slot", String.valueOf(slotId)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("아직 쓴 기사가 없다")));
    }

    @Test
    @DisplayName("상세가 본문·댓글·사실 블록·지적을 한 화면에 놓는다")
    void detailRenders() throws Exception {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal Gaming");
        int red = team(slotId, 34, "OZ Gaming");
        long id = article(slotId, blue, red, 2, 30, "완봉으로 끝난 승부",
                List.of(new Finding(Severity.UNVERIFIED, "brief 에 없는 숫자", "20")));

        mvc().perform(get("/story/" + id))
                .andExpect(status().isOk())
                .andExpect(view().name("story/detail"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("첫 세트를 내준 쪽이")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("이게 실화냐")))
                // 「이 기사가 쓴 숫자」 — 생성 시점의 문자열 그대로 (D61 결정 2)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("블루 2 - 1 레드")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("미확인")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("brief 에 없는 숫자")));
    }

    @Test
    @DisplayName("상세에 통계 화면으로 가는 링크가 없다 (D61)")
    void detailHasNoLinkIntoStatistics() throws Exception {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal Gaming");
        int red = team(slotId, 34, "OZ Gaming");
        long id = article(slotId, blue, red, 2, 30, "완봉으로 끝난 승부", List.of());

        String html = mvc().perform(get("/story/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
                .doesNotContain("/tier")
                .doesNotContain("/champion/")
                .doesNotContain("/synergy");
    }

    @Test
    @DisplayName("기사가 한 편도 없어도 커리어를 고를 수 있다 — 첫 기사를 쓸 길이 막히면 안 된다")
    void slotIsSelectableEvenWithNoArticlesAnywhere() throws Exception {
        // 이 테스트가 없을 때 진짜로 막혔다: 슬롯 목록을 article 에서만 뽑았더니
        // 기사 0편 → 고를 커리어 없음 → 생성 버튼 없음 → 기사를 영영 못 씀.
        // article 을 비우고(이 트랜잭션 안에서만) save_slot 만 남겨 그 상황을 만든다.
        jdbc.update("DELETE FROM article");
        int slotId = newSlot();

        String html = mvc().perform(get("/story"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 슬롯이 하나면 선택 폼은 안 그리므로, 고른 커리어가 있다는 증거는
        // "기사가 없다" 문구가 정상적으로 나오는 것과 200 응답이다
        org.assertj.core.api.Assertions.assertThat(html).contains("아직 쓴 기사가 없다");
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT count(*)::int FROM save_slot WHERE slot_id = ?",
                        Integer.class, slotId)).isEqualTo(1);
    }

    @Test
    @DisplayName("루트는 연대기로 보낸다 — 404 Whitelabel 이 아니다")
    void rootRedirectsToStory() throws Exception {
        mvc().perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .redirectedUrl("/story"));
    }

    @Test
    @DisplayName("slot 없이 열어도 200 이다 — 기본 커리어를 스스로 고른다")
    void listWithoutSlotStillRenders() throws Exception {
        mvc().perform(get("/story")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("story 가 꺼져 있으면 생성 버튼을 안 그린다 — 눌러야 알려주는 버튼은 버튼이 아니다")
    void generateButtonHiddenWhenStoryDisabled() throws Exception {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal Gaming");
        int red = team(slotId, 34, "OZ Gaming");
        article(slotId, blue, red, 2, 30, "완봉으로 끝난 승부", List.of());

        // 테스트 프로파일은 tfm.story.enabled 가 없다(=false). 그래서 StoryGenerator 빈이
        // 아예 없고, 컨트롤러는 Optional.empty() 를 받는다
        String html = mvc().perform(get("/story").param("slot", String.valueOf(slotId)))
                .andReturn().getResponse().getContentAsString();

        // 주석에도 /story/generate 가 나오므로(Thymeleaf 는 HTML 주석을 지우지 않는다)
        // 경로가 아니라 버튼 자체가 없음을 본다
        org.assertj.core.api.Assertions.assertThat(html)
                .doesNotContain("최근 경기 기사 쓰기")
                .doesNotContain("<form class=\"generate\"");
    }

    @Test
    @DisplayName("꺼진 상태로 생성을 부르면 503 이다 — 조용히 아무 일도 안 일어나지 않는다")
    void generateWhenDisabledIsServiceUnavailable() throws Exception {
        mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/story/generate").param("slot", "1"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("주석이 본문으로 새지 않는다 — 주석 안에 닫는 기호를 쓰면 그 뒤가 화면에 찍힌다")
    void templateCommentsDoNotLeakIntoTheBody() throws Exception {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal Gaming");
        int red = team(slotId, 34, "OZ Gaming");
        long id = article(slotId, blue, red, 2, 30, "완봉으로 끝난 승부", List.of());

        for (String url : List.of("/story?slot=" + slotId, "/story/" + id)) {
            String html = mvc().perform(get(url)).andReturn().getResponse().getContentAsString();

            // 온전한 주석을 다 걷어낸다. 주석이 제대로 닫혀 있으면 설명 문구도 같이 사라진다.
            // 하나라도 조기 종료됐다면 그 뒤 설명이 본문으로 남아 아래 검사에 걸린다.
            String withoutComments = html.replaceAll("(?s)<!--.*?-->", "");

            org.assertj.core.api.Assertions.assertThat(withoutComments)
                    .as("주석 밖으로 새어 나온 설명이 있다: " + url)
                    .doesNotContain("Thymeleaf")
                    .doesNotContain("th:")
                    .doesNotContain("자리표시자")
                    // 렌더링이 끝난 응답에 ${ 가 남아 있을 이유가 없다.
                    // 실제로 샌 문구가 이것이었다 — 위 세 낱말로는 안 잡혔다
                    .doesNotContain("${");
        }
    }

    @Test
    @DisplayName("없는 기사는 404 다 — 빈 화면 200 이 아니다")
    void missingArticleIsNotFound() throws Exception {
        mvc().perform(get("/story/999999999")).andExpect(status().isNotFound());
    }
}
