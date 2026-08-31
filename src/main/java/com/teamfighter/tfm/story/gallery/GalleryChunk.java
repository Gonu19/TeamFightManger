package com.teamfighter.tfm.story.gallery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 번의 호출로 뽑을 글 묶음. <b>페이지 하나가 호출 둘로 만들어진다</b> (D74).
 *
 * <h2>왜 하나도 아니고 넷도 아닌가</h2>
 *
 * 레퍼런스 모드는 <b>한 호출</b>로 20개를 뽑는다({@code maxTokens: 8192}). 우리는 그럴 수
 * 없다 — 무료 티어의 분당 한도가 8,000 토큰이라 <b>요청 하나가 한도보다 커진다.</b>
 * 그런 요청은 몇 번을 다시 보내도 통과하지 못한다. 재시도로 넘길 수 있는 종류가 아니다.
 *
 * <p>처음에는 <b>넷</b>으로 나눴다(D72). 조각이 작으면 실패가 격리되고 429 를 덜 맞는다는
 * 이유였는데, 실물에서 그 대가가 더 컸다 — 조각마다 프롬프트(정체성 · 규칙 · 선수 표)가
 * 통째로 다시 실려 나가서 <b>입력 토큰이 네 배</b>가 됐다. 페이지 하나에 3~5분이 걸렸고,
 * 그 시간이 곧 이 기능의 값어치를 깎았다.
 *
 * <p>둘이 그 사이다. 요청 하나는 한도 안에 들어가고, 프롬프트는 두 번만 실린다.
 * 하나가 깨져도 열 개가 남아 게시판은 여전히 성립한다.
 *
 * <h2>왜 하필 이 묶음인가</h2>
 *
 * 유형을 무작위로 쪼개지 않고 <b>갤의 시간 순서</b>로 묶었다. 실제 커뮤니티는 경기 직후
 * 실황·저격이 먼저 터지고, 그 뒤에 분석과 키배와 잡담이 온다. 묶음이 곧 그 순서다 —
 * 뒤 조각은 앞 조각의 제목을 받아 보므로 <b>이어지는 흐름</b>이 된다.
 */
public record GalleryChunk(String mood, Map<GalleryPostKind, Integer> quota) {

    public GalleryChunk {
        quota = Map.copyOf(quota);
        if (quota.isEmpty()) {
            throw new IllegalArgumentException("빈 할당량으로는 부를 것이 없다: " + mood);
        }
    }

    /**
     * 페이지 하나를 이루는 네 조각. 합이 스무 개다.
     *
     * <p>순서가 의미를 갖는다 — 앞에서 뒤로 시간이 흐른다.
     */
    public static List<GalleryChunk> page() {
        return List.of(
                new GalleryChunk("경기 직후. 아직 흥분이 안 가셨다", quotaOf(
                        GalleryPostKind.LIVE, 2,
                        GalleryPostKind.PLAYER, 3,
                        GalleryPostKind.SKIT, 2,
                        GalleryPostKind.BAIT, 3)),

                new GalleryChunk("몇 시간 뒤. 분석과 싸움과 잡담이 섞인다", quotaOf(
                        GalleryPostKind.ANALYSIS, 1,
                        GalleryPostKind.FLAME, 2,
                        GalleryPostKind.SAGA, 2,
                        GalleryPostKind.TRANSFER, 2,
                        GalleryPostKind.SCRAP, 1,
                        GalleryPostKind.DAILY, 2)));
    }

    /** 이 조각이 요구하는 글 수. */
    public int size() {
        return quota.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * 유형을 못 읽은 글에 붙일 기본값. 이 조각에서 <b>가장 많이 요구한</b> 유형이다 —
     * 모르는 값을 버리는 대신 가장 그럴듯한 자리에 넣는다.
     */
    public GalleryPostKind fallbackKind() {
        return quota.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
    }

    /** 프롬프트에 박아 넣을 할당 문장. "실황/직관 2개" 꼴로 줄마다 하나씩. */
    public String describeQuota() {
        StringBuilder out = new StringBuilder();
        quota.forEach((kind, count) -> out
                .append("- ").append(kind.name()).append(" (").append(kind.label()).append(") ")
                .append(count).append("개: ").append(kind.guidance()).append('\n'));
        return out.toString();
    }

    private static Map<GalleryPostKind, Integer> quotaOf(Object... pairs) {
        Map<GalleryPostKind, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((GalleryPostKind) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }
}
