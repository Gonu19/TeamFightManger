package com.teamfighter.tfm.analysis.scrim;

/**
 * 스크림 덱에 넣을 수 있는 챔피언 하나.
 *
 * @param category 역할군 이름(ENUM 이름 그대로). 역할군 수를 세는 데만 쓰므로
 *                 {@code analysis/} 가 {@code ingest/} 의 enum 을 알 필요는 없다 —
 *                 그 의존은 규칙 1이 막아 둔 방향이다
 * @param games    공식전 출전 수. <b>적을수록 발굴 값이 크다</b> — 이 값이 추천의
 *                 순서를 정한다. 강한지 약한지는 여기서 안 본다
 */
public record ScrimCandidate(
        int championId,
        String code,
        String nameKo,
        String category,
        int games) {
}
