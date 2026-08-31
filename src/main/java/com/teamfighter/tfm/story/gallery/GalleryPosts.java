package com.teamfighter.tfm.story.gallery;

import com.teamfighter.tfm.story.JsonSalvage;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 모델이 준 덩어리를 {@link GalleryPost} 목록으로 만든다.
 *
 * <h2>글 하나가 깨져도 나머지는 산다</h2>
 *
 * 게시글은 댓글보다 크고, 크면 잘릴 확률이 높다. 그래서 <b>객체 단위로 건진다</b> —
 * 배열을 읽은 뒤에는 항목마다 따로 판단하고, 제목이나 본문이 빈 항목만 버린다.
 * 열다섯 개짜리 게시판은 성립하지만 0개짜리는 성립하지 않기 때문이다.
 *
 * <p>배열을 꺼내는 일 자체는 {@link JsonSalvage} 가 한다. 댓글 파서와 같은 규칙이다.
 *
 * <h2>유형을 못 읽으면 조각의 기본값으로 채운다</h2>
 *
 * 모델이 {@code "kind"} 를 빼먹거나 지어낸 값을 주는 일이 있다. 그때 글을 버리면
 * 할당량이 조용히 무너진다 — 그래서 {@link GalleryChunk#fallbackKind()} 로 채운다.
 * 그 조각이 <b>가장 많이 요구한</b> 유형이므로 가장 그럴듯한 자리다.
 */
public final class GalleryPosts {

    /** 한 원댓글에 달릴 수 있는 대댓글 수. 넘으면 화면이 대댓글로만 찬다. */
    private static final int MAX_REPLIES = 7;

    /** 한 글에 달릴 수 있는 댓글 수(대댓글 포함). 모델이 폭주할 때의 상한이다. */
    private static final int MAX_COMMENTS = 40;

    private GalleryPosts() {
    }

    /**
     * 모델 응답 → 게시글 목록. 하나도 못 읽으면 빈 목록이다 —
     * <b>예외가 아니다.</b> 조각 하나가 실패해도 나머지 조각은 계속 돌아야 한다.
     *
     * @param chunk 이 응답을 요구한 조각. 유형을 못 읽은 글을 채우는 데 쓴다
     */
    public static List<GalleryPost> parse(String raw, GalleryChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");

        JsonNode array = JsonSalvage.readArray(raw);                            // 1. 배열을 건진다 (앞뒤 말·잘림 복구 포함)
        if (array == null) {
            return List.of();
        }

        List<GalleryPost> out = new ArrayList<>();
        for (JsonNode node : array) {
            GalleryPost post = readPost(node, chunk);                           // 2. 항목마다 따로 판단한다 — 하나가 깨져도 나머지는 산다
            if (post != null) {
                out.add(post);
            }
        }
        return List.copyOf(out);
    }

    /** 항목 하나. 제목이나 본문이 비면 {@code null} — 목록에도 상세에도 그릴 것이 없다. */
    private static GalleryPost readPost(JsonNode node, GalleryChunk chunk) {
        String title = JsonSalvage.text(node, "title");
        String body = JsonSalvage.text(node, "content");
        if (title.isBlank() || body.isBlank()) {
            return null;
        }

        GalleryPostKind kind = GalleryPostKind.parse(JsonSalvage.text(node, "kind"));
        if (kind == null) {
            kind = chunk.fallbackKind();
        }

        // has_image 를 따로 안 본다. 파일명이 있으면 짤방이 있는 것이고, 플래그가 true 인데
        // 파일명이 비어 있으면 화면에 그릴 것이 없다 — 그 경우 플래그를 믿으면 빈 칸이 남는다.
        String imageDesc = JsonSalvage.text(node, "image_desc");

        return new GalleryPost(
                kind,
                title,
                JsonSalvage.text(node, "author"),
                body,
                JsonSalvage.intOrNull(node, "views"),
                JsonSalvage.intOrNull(node, "likes"),
                node.path("is_concept").asBoolean(false),
                imageDesc,
                JsonSalvage.text(node, "date"),
                readComments(node.path("comments")));
    }

    /**
     * 댓글 트리를 <b>평평한 목록</b>으로 편다. 대댓글은 부모의 순번을 들고 있다.
     *
     * <p>중첩 대신 평평하게 두는 이유는 저장이 그렇기 때문이다 —
     * {@code gallery_comment} 는 행 하나에 {@code parent_ordinal} 하나를 갖는다.
     */
    private static List<GalleryComment> readComments(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }

        List<GalleryComment> out = new ArrayList<>();
        for (JsonNode node : array) {
            if (out.size() >= MAX_COMMENTS) {
                break;
            }
            String body = JsonSalvage.text(node, "content");
            if (body.isBlank()) {
                continue;
            }

            int parentOrdinal = out.size() + 1;                                 // 1. 이 원댓글의 순번 (1부터)
            out.add(new GalleryComment(JsonSalvage.text(node, "author"), body, null,
                    JsonSalvage.text(node, "date")));

            JsonNode replies = node.path("sub_comments");                       // 2. 대댓글은 부모 순번을 들고 붙는다
            if (!replies.isArray()) {
                continue;
            }
            int added = 0;
            for (JsonNode reply : replies) {
                if (added >= MAX_REPLIES || out.size() >= MAX_COMMENTS) {
                    break;
                }
                String replyBody = JsonSalvage.text(reply, "content");
                if (replyBody.isBlank()) {
                    continue;
                }
                out.add(new GalleryComment(JsonSalvage.text(reply, "author"), replyBody, parentOrdinal,
                        JsonSalvage.text(reply, "date")));
                added++;
            }
        }
        return List.copyOf(out);
    }
}
