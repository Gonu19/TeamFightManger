package com.teamfighter.tfm.web.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고르개 한 줄의 글자.
 *
 * <p>드롭다운은 <b>닫혀 있을 때 색이 안 보인다.</b> 칩이었을 때는 흐린 색이 "아직 안 쓴
 * 커리어" 를 말해 줬지만, 이제는 라벨 글자가 그 말을 혼자 해야 한다 — 그래서 이 클래스의
 * 규칙은 스타일이 아니라 <b>동작</b>이고, 여기서 고정한다.
 */
class SlotOptionTest {

    @Test
    @DisplayName("팀 이름이 있으면 번호 뒤에 붙는다")
    void 팀_이름이_있으면_번호_뒤에_붙는다() {
        assertThat(new SlotOption(2, "Ketos", true).label()).isEqualTo("슬롯 2 — Ketos");
    }

    /**
     * 번호를 지우고 이름만 남기면 안 되는 이유다. 실측에서 슬롯 2와 3이 둘 다 "Ketos" 라,
     * 이름만 그리면 <b>같은 줄이 둘</b>이 되어 어느 쪽을 고른 것인지 알 수 없다.
     */
    @Test
    @DisplayName("이름이 겹쳐도 번호가 남아 두 줄이 구별된다")
    void 이름이_겹쳐도_번호가_남아_두_줄이_구별된다() {
        String second = new SlotOption(2, "Ketos", true).label();
        String third = new SlotOption(3, "Ketos", true).label();

        assertThat(second).isNotEqualTo(third);
    }

    /**
     * 팀은 공식전에서만 식별된다 (D54). 스크림만 치른 새 커리어는 이름이 없고,
     * 그래도 목록에 남아야 한다 — 안 그러면 그 커리어를 고를 길이 없다.
     */
    @Test
    @DisplayName("팀 이름이 없으면 번호만 남긴다 — 빈 구분자를 안 붙인다")
    void 팀_이름이_없으면_번호만_남긴다() {
        assertThat(new SlotOption(4, null, true).label()).isEqualTo("슬롯 4");
        assertThat(new SlotOption(4, "  ", true).label()).isEqualTo("슬롯 4");
    }

    @Test
    @DisplayName("비어 있다는 사실을 색이 아니라 글자로 말한다")
    void 비어_있다는_사실을_글자로_말한다() {
        assertThat(new SlotOption(4, null, false).label()).isEqualTo("슬롯 4 (비어 있음)");
        assertThat(new SlotOption(2, "Ketos", false).label())
                .isEqualTo("슬롯 2 — Ketos (비어 있음)");
    }

    @Test
    @DisplayName("내용이 있으면 아무 표시도 안 붙는다")
    void 내용이_있으면_표시가_없다() {
        assertThat(new SlotOption(1, "Ember scale", true).label())
                .doesNotContain("비어 있음");
    }
}
