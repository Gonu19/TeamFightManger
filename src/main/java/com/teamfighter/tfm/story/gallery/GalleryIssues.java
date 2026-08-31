package com.teamfighter.tfm.story.gallery;

import com.teamfighter.tfm.story.JsonSalvage;
import com.teamfighter.tfm.story.gallery.GalleryIssue.GalleryIssueCategory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 모델이 준 덩어리를 {@link GalleryIssue} 목록으로 만든다.
 *
 * <p>게시글 파서와 규칙이 같다 — 배열은 {@link JsonSalvage} 가 건지고, 항목은 하나씩
 * 따로 판단한다. 이슈 하나가 깨져도 나머지 다섯은 사이드바에 뜬다.
 *
 * <p>다른 점은 <b>실패해도 갤러리 생성이 계속된다</b>는 것이다. 이슈는 부가 기능이라
 * 없어도 게시판이 성립한다 — 모드도 이슈 실패를 조용히 넘기고 게시글 생성을 계속한다.
 * 다만 우리는 조용히 넘기지 않고 로그를 남긴다({@link GalleryWriter} 가 한다).
 */
public final class GalleryIssues {

    /** 사이드바에 그리는 이슈 수. 모드와 같다 — 더 넣으면 목록이 스크롤된다. */
    static final int MAX_ISSUES = 6;

    private GalleryIssues() {
    }

    /** 모델 응답 → 이슈 목록. 하나도 못 읽으면 빈 목록이다 — 예외가 아니다. */
    public static List<GalleryIssue> parse(String raw) {
        JsonNode array = JsonSalvage.readArray(raw);
        if (array == null) {
            return List.of();
        }

        List<GalleryIssue> out = new ArrayList<>();
        for (JsonNode node : array) {
            if (out.size() >= MAX_ISSUES) {
                break;
            }
            String headline = JsonSalvage.text(node, "headline");
            String body = JsonSalvage.text(node, "content");
            if (headline.isBlank() || body.isBlank()) {                         // 1. 제목이나 본문이 비면 그 항목만 버린다
                continue;
            }

            GalleryIssueCategory category =                                     // 2. 분류를 못 읽으면 '리그' 로 채운다.
                    GalleryIssueCategory.parse(JsonSalvage.text(node, "category"));
            out.add(new GalleryIssue(                                           //    버리면 사이드바가 빈다
                    category == null ? GalleryIssueCategory.LEAGUE : category,
                    headline,
                    body,
                    JsonSalvage.text(node, "date")));
        }
        return List.copyOf(out);
    }
}
