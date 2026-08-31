package com.teamfighter.tfm.story.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조각 나누기가 <b>배분</b>인지 확인한다.
 *
 * <p>"다양하게 써라" 를 프롬프트로 부탁하는 대신 유형과 개수를 숫자로 박는 것이
 * 이 설계의 전부다(D72). 그 숫자가 틀어지면 세트 나열을 못 고쳤던 실패로 돌아간다 —
 * 화면은 멀쩡해 보이고 글만 똑같아진다.
 */
class GalleryChunkTest {

    @Test
    @DisplayName("한 페이지는 조각 둘, 글 스물이다")
    void pageAddsUpToTwenty() {
        List<GalleryChunk> page = GalleryChunk.page();

        assertThat(page).hasSize(2);
        assertThat(page.stream().mapToInt(GalleryChunk::size).sum()).isEqualTo(20);
    }

    @Test
    @DisplayName("조각 하나가 분당 토큰 한도를 넘지 않는다")
    void noChunkExceedsTheMinuteBudget() {
        // 글 하나에 380토큰이므로 열이면 3,800 이다. 무료 티어의 분당 한도가 8,000 이라
        // 요청 하나가 그 안에 들어간다 — 넘으면 몇 번을 다시 보내도 통과하지 못한다.
        // 조각을 하나로 합칠 수 없는 이유가 바로 이것이다 (D74).
        assertThat(GalleryChunk.page()).allSatisfy(chunk ->
                assertThat(chunk.size() * 380).isLessThan(8_000));
    }

    @Test
    @DisplayName("유형 열 가지를 한 페이지 안에서 모두 쓴다")
    void everyKindAppearsInAPage() {
        Set<GalleryPostKind> used = GalleryChunk.page().stream()
                .flatMap(chunk -> chunk.quota().keySet().stream())
                .collect(Collectors.toSet());

        // 하나라도 빠지면 그 유형은 코드에만 있고 갤에는 영영 안 나온다
        assertThat(used).isEqualTo(EnumSet.allOf(GalleryPostKind.class));
    }

    @Test
    @DisplayName("기본 유형은 그 조각이 가장 많이 요구한 것이다")
    void fallbackIsTheMostRequestedKind() {
        // 유형을 못 읽은 글이 갈 자리다. 가장 많이 요구한 유형이 가장 그럴듯하다.
        assertThat(GalleryChunk.page().get(0).fallbackKind())
                .isIn(GalleryPostKind.PLAYER, GalleryPostKind.BAIT);
    }

    @Test
    @DisplayName("할당표에 유형 이름과 개수가 그대로 들어간다")
    void quotaDescriptionCarriesNamesAndCounts() {
        String described = GalleryChunk.page().get(1).describeQuota();

        // 모델은 이 문자열만 보고 kind 값을 고른다 — enum 이름이 빠지면
        // 파싱이 전부 기본값으로 떨어진다
        assertThat(described).contains("ANALYSIS", "FLAME", "SAGA", "DAILY");
        assertThat(described).contains("2개");
    }

    @Test
    @DisplayName("빈 할당량으로는 조각을 만들 수 없다")
    void rejectsEmptyQuota() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new GalleryChunk("빈 조각", java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
