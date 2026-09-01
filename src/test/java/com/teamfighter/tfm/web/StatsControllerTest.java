package com.teamfighter.tfm.web;

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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 티어 화면의 <b>커리어 고르개</b>.
 *
 * <p>템플릿까지 진짜로 렌더링한다 — 조각 호출의 인자 수가 바뀌면 컴파일은 통과하고
 * 브라우저에서만 터지기 때문이다. {@code StoryControllerTest} 가 같은 이유로 본문을 읽는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatsControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** 적재만 된 커리어. 집계 결과({@code champion_performance})는 한 줄도 없다. */
    private int newSlot() {
        return jdbc.queryForObject("""
                INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id
                """, Integer.class, "stats_" + System.nanoTime());
    }

    /**
     * <b>고쳐야 했던 증상 그대로다.</b> 사용자가 새 커리어를 만들어 적재했더니 연대기·
     * 갤러리에는 뜨는데 티어 고르개에서만 통째로 사라졌다. 적재는 됐고 집계가 안 됐을
     * 뿐인데, 목록에서 빠지면 그것이 "적재가 안 됐다" 로 읽힌다.
     */
    @Test
    @DisplayName("집계 전인 커리어도 고르개에 남는다")
    void 집계_전인_커리어도_고르개에_남는다() throws Exception {
        int slot = newSlot();

        mvc().perform(get("/tier").param("slot", String.valueOf(slot)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "<option value=\"" + slot + "\"")));
    }

    /**
     * 빈 화면에는 두 가지 뜻이 있다 — 집계를 안 돌렸거나, 돌렸는데 볼 것이 없거나.
     * 화면에는 똑같이 보이는데 <b>할 일이 정반대</b>라, 어느 쪽인지를 문구가 말해야 한다.
     */
    @Test
    @DisplayName("집계 전인 커리어는 '적재하라' 가 아니라 '집계를 돌려라' 라고 말한다")
    void 집계_전인_빈_화면은_할_일을_말한다() throws Exception {
        int slot = newSlot();

        mvc().perform(get("/tier").param("slot", String.valueOf(slot)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("아직 집계 전이다")))
                .andExpect(content().string(not(
                        containsString("세이브를 적재하고"))));
    }

    /**
     * 표가 <b>비는 것이 아니라</b>는 점이 이 검사의 이유다. 티어 질의는 champion 을
     * 왼쪽에 두고 성적을 붙이므로, 집계가 한 번도 안 돈 커리어에서도 40여 줄이 전부
     * "—" 로 나온다. 그 화면은 "볼 것이 없다" 가 아니라 "값이 깨졌다" 로 읽힌다.
     */
    @Test
    @DisplayName("집계 전인 커리어에 '—' 만 채운 표를 그리지 않는다")
    void 집계_전이면_표를_안_그린다() throws Exception {
        int slot = newSlot();

        mvc().perform(get("/tier").param("slot", String.valueOf(slot)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("tier-table"))));
    }

    /**
     * 고르개는 폼이라 제출하면 다른 인자가 <b>주소에서 사라진다</b>. 숨은 칸으로 같이
     * 싣지 않으면 커리어를 고를 때마다 역할군 탭이 조용히 "전체" 로 돌아가고,
     * 사용자는 자기가 누르지 않은 변화를 보게 된다.
     */
    @Test
    @DisplayName("고르개가 역할군·경기 조건을 같이 싣는다")
    void 고르개가_다른_거르개를_같이_싣는다() throws Exception {
        int slot = newSlot();

        mvc().perform(get("/tier")
                        .param("slot", String.valueOf(slot))
                        .param("category", "MELEE")
                        .param("scrim", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "name=\"category\" value=\"MELEE\"")))
                .andExpect(content().string(containsString(
                        "name=\"scrim\" value=\"true\"")));
    }

    /**
     * "전체" 는 널이고 그건 정상이다. 널을 그대로 실으면 {@code ?category=} 라는 빈 인자가
     * 주소에 붙고, 그 값은 다시 읽을 때 빈 문자열이라 "전체" 와 뜻이 갈릴 여지가 생긴다.
     */
    @Test
    @DisplayName("역할군이 '전체' 면 빈 인자를 안 싣는다")
    void 역할군이_전체면_빈_인자를_안_싣는다() throws Exception {
        int slot = newSlot();

        mvc().perform(get("/tier").param("slot", String.valueOf(slot)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(
                        containsString("name=\"category\""))));
    }
}
