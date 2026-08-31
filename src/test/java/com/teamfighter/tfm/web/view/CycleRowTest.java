package com.teamfighter.tfm.web.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사이클 줄이 <b>"내 경기가 아니다" 와 "졌다" 를 구분하는가.</b>
 *
 * <p>이 구분이 없으면 화면이 모든 남의 경기에 패배 색을 칠한다. 숫자는 멀쩡하고
 * 색만 틀리는 종류라, 스코어를 하나하나 대조하기 전에는 안 보인다.
 */
class CycleRowTest {

    private static CycleRow row(int sets, int homeWins,
                                boolean homeIsPlayer, boolean awayIsPlayer,
                                Long articleId, Long batchId) {
        return new CycleRow(3, 12, 7, 9, "T1", "GEN", homeIsPlayer, awayIsPlayer,
                sets, homeWins, articleId, batchId, batchId == null ? 0 : 1, null);
    }

    @Test
    @DisplayName("상대 승수는 세트 수에서 뺀다 — 따로 담지 않는다")
    void awayWinsIsDerived() {
        // 둘을 다 저장하면 어긋날 자리가 생기고, 어긋나면 "3세트인데 2:2" 가 화면에 뜬다.
        assertThat(row(3, 2, false, false, null, null).awayWins()).isEqualTo(1);
    }

    @Test
    @DisplayName("플레이어 팀이 없으면 승패를 말하지 않는다 — false 가 아니라 null 이다")
    void aMatchWithoutThePlayerHasNoVerdict() {
        assertThat(row(3, 2, false, false, null, null).playerWon()).isNull();
        assertThat(row(3, 2, false, false, null, null).hasPlayer()).isFalse();
    }

    @Test
    @DisplayName("플레이어가 어느 편이든 그 편 기준으로 이겼는지 본다")
    void theVerdictFollowsWhicheverSideThePlayerIsOn() {
        assertThat(row(3, 2, true, false, null, null).playerWon()).isTrue();   // 홈이 나 · 2:1
        assertThat(row(3, 2, false, true, null, null).playerWon()).isFalse();  // 원정이 나 · 1:2
        assertThat(row(3, 1, false, true, null, null).playerWon()).isTrue();   // 원정이 나 · 2:1
    }

    @Test
    @DisplayName("사이클 단계는 기사와 갤러리가 정한다")
    void theStageFollowsWhatExists() {
        assertThat(row(3, 2, false, false, null, null).stage()).isEqualTo(0);
        assertThat(row(3, 2, false, false, 11L, null).stage()).isEqualTo(1);
        assertThat(row(3, 2, false, false, 11L, 22L).stage()).isEqualTo(2);
    }

    @Test
    @DisplayName("갤러리만 있고 기사가 없어도 된다 — 둘은 독립이다 (D73)")
    void aGalleryCanExistWithoutAnArticle() {
        CycleRow onlyGallery = row(3, 2, false, false, null, 22L);

        assertThat(onlyGallery.hasArticle()).isFalse();
        assertThat(onlyGallery.hasGallery()).isTrue();
        assertThat(onlyGallery.stage()).isEqualTo(2);
    }

    @Test
    @DisplayName("이름이 없으면 번호로 부른다")
    void teamsWithoutNamesFallBackToTheirNumber() {
        // 빈 칸보다 낫다 — 적어도 두 줄을 구분할 수 있다.
        CycleRow nameless = new CycleRow(3, 12, 7, 9, null, "  ", false, false,
                3, 2, null, null, 0, null);

        assertThat(nameless.homeLabel()).isEqualTo("팀 7");
        assertThat(nameless.awayLabel()).isEqualTo("팀 9");
    }
}
