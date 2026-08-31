package com.teamfighter.tfm.story.gallery;

import java.util.Locale;
import java.util.Objects;

/**
 * 이슈 하나. 모드의 <b>[🔥 팀파 이슈]</b> 사이드바에 뜨는 뉴스다.
 *
 * <h2>이것만은 경기 데이터에서 안 나온다</h2>
 *
 * 갤 글은 선수별 표를 근거로 쓰지만, 이슈는 <b>리그 전체의 소문</b>이다 — 이적설,
 * 감독 경질, 스캔들. 세이브에 그런 값이 없으므로 전부 지어낸 것이다.
 *
 * <p>그래도 두는 이유는 게시판이 <b>경기 하나에만 매달리면 갤이 안 되기</b> 때문이다.
 * 실제 커뮤니티에는 어제 경기 얘기와 이적 루머와 감독 욕이 같이 굴러다니고,
 * 갤러가 뉴스를 퍼와서 싸우는 글(SCRAP)이 그 절반을 만든다.
 *
 * <p>지어낸 것이라는 사실은 화면이 말한다 — 갤러리 하단의 고지가 글·댓글·조회수와
 * 함께 이슈도 지어낸 것이라고 적는다 (D71 의 경계를 그대로 따른다).
 *
 * @param issueDate 모델이 준 {@code "MM.DD"} 문자열 그대로. 날짜로 파싱하지 않는다 —
 *                  게임 안의 날짜라 우리 달력의 연도가 없고, 없는 연도를 붙이면 사실이 된다
 */
public record GalleryIssue(
        GalleryIssueCategory category,
        String headline,
        String body,
        String issueDate) {

    public GalleryIssue {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(headline, "headline");
        Objects.requireNonNull(body, "body");
        issueDate = issueDate == null || issueDate.isBlank() ? null : issueDate.strip();

        if (headline.isBlank()) {
            throw new IllegalArgumentException("헤드라인 없는 이슈는 사이드바에 그릴 수 없다");
        }
    }

    /**
     * 이슈 분류. 모드의 여섯 가지를 그대로 옮겼다.
     *
     * <p>ENUM 인 이유는 <b>화면의 배지 색과 짝</b>이기 때문이다. text 로 두면 모델이
     * 지어낸 분류가 새 값으로 들어오고, 화면은 그것을 색 없는 빈 배지로 조용히 그린다.
     */
    public enum GalleryIssueCategory {

        LEAGUE("리그", ""),
        TRANSFER("이적설", "transfer"),
        SCANDAL("스캔들", "scandal"),
        BROADCAST("방송", "broadcast"),
        ANALYSIS("전력분석", ""),
        RUMOR("루머", "scandal");

        private final String label;
        private final String badgeClass;

        GalleryIssueCategory(String label, String badgeClass) {
            this.label = label;
            this.badgeClass = badgeClass;
        }

        /** 배지에 찍는 한글 이름. */
        public String label() {
            return label;
        }

        /**
         * 배지의 CSS 클래스. 모드의 {@code catClass()} 를 옮겼다 —
         * 루머는 스캔들과 같은 붉은색이다(둘 다 확정 사실이 아니라는 뜻).
         * 빈 문자열이면 기본 남색이다.
         */
        public String badgeClass() {
            return badgeClass;
        }

        /**
         * 모델이 준 분류 문자열을 값으로 바꾼다. 한글로 줄 때가 많아 라벨도 함께 본다.
         *
         * @return 모르는 값이면 {@code null}. <b>예외가 아니다</b> — 분류 하나 때문에
         *         이슈를 버리면 사이드바가 빈다. 부르는 쪽이 {@link #LEAGUE} 로 채운다
         */
        public static GalleryIssueCategory parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String normalized = raw.strip();
            for (GalleryIssueCategory category : values()) {
                if (category.name().equalsIgnoreCase(normalized)
                        || category.label.equals(normalized)) {
                    return category;
                }
            }
            // 모델이 "이적" · "루머설" 처럼 라벨을 조금 다르게 줄 때가 있다.
            // 라벨이 들어 있기만 하면 그 분류로 본다 — 여기서 놓치면 배지가 전부 '리그' 가 된다.
            String lower = normalized.toLowerCase(Locale.ROOT);
            for (GalleryIssueCategory category : values()) {
                if (normalized.contains(category.label) || lower.contains(category.name().toLowerCase(Locale.ROOT))) {
                    return category;
                }
            }
            return null;
        }
    }
}
