package com.teamfighter.tfm.story.gallery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 번의 호출로 뽑을 글 묶음. <b>페이지 하나가 호출 넷으로 만들어진다</b> (D72).
 *
 * <h2>왜 한 번에 스무 개를 안 뽑는가</h2>
 *
 * 레퍼런스 모드는 한 호출로 20개를 뽑는다({@code maxTokens: 8192}). 우리는 그럴 수 없다 —
 * 무료 티어의 <b>분당 토큰 8,000</b> 이 그 한 번에 다 들어간다. 걸리면 재시도가 받아주므로
 * 실패는 아니지만, 버튼 하나에 1분을 보게 된다.
 *
 * <p>더 큰 이유가 따로 있다. <b>한 호출이 실패하면 스무 개가 통째로 날아간다.</b>
 * 넷으로 나누면 하나가 깨져도 열다섯 개가 남고, 남은 것만으로도 게시판이 성립한다.
 *
 * <h2>왜 하필 이 묶음인가</h2>
 *
 * 유형을 무작위로 쪼개지 않고 <b>갤의 시간 순서</b>로 묶었다. 실제 커뮤니티는 경기 직후
 * 실황·저격이 먼저 터지고, 한참 뒤에 분석과 키배가 오고, 그다음 서사와 잡담이 온다.
 * 묶음이 곧 그 순서다 — 뒤 조각은 앞 조각의 제목을 받아 보므로 <b>이어지는 흐름</b>이 된다.
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
                        GalleryPostKind.PLAYER, 2,
                        GalleryPostKind.SKIT, 1)),

                new GalleryChunk("몇 시간 지나 분석과 싸움이 시작된다", quotaOf(
                        GalleryPostKind.ANALYSIS, 1,
                        GalleryPostKind.FLAME, 2,
                        GalleryPostKind.BAIT, 2)),

                new GalleryChunk("떡밥이 서사로 번진다", quotaOf(
                        GalleryPostKind.SAGA, 2,
                        GalleryPostKind.TRANSFER, 2,
                        GalleryPostKind.SCRAP, 1)),

                new GalleryChunk("밤. 여운과 잡담이 섞인다", quotaOf(
                        GalleryPostKind.SCRAP, 1,
                        GalleryPostKind.PLAYER, 1,
                        GalleryPostKind.SKIT, 1,
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
