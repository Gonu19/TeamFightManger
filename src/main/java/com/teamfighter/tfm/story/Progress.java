package com.teamfighter.tfm.story;

/**
 * 오래 걸리는 생성이 <b>어디까지 갔는지</b> 흘리는 통로.
 *
 * <h2>왜 필요한가</h2>
 *
 * 무료 티어의 분당 토큰이 8,000 이고 한 번의 생성이 그 언저리를 쓴다 — 429 를 한 번쯤
 * 맞고 기다린다. 그동안 아무 신호가 없으면 화면은 <b>멈춘 것과 구분되지 않는다.</b>
 * 실제로 갤러리의 첫 실물이 그렇게 보였다(D74).
 *
 * <p>기사도 같은 문제를 갖고 있었는데 장치가 없었다 — 동기 POST 라 브라우저가 20~30초
 * 흰 화면을 물고 있었고, 실패하면 그제서야 한 줄이 떴다.
 *
 * <h2>왜 {@code GalleryWriter} 밖으로 나왔나</h2>
 *
 * 갤러리 전용일 때는 그 안에 있어도 됐다. 기사도 쓰게 되면서 <b>둘의 공용</b>이 됐고,
 * 한쪽 안에 두면 다른 쪽이 "갤러리" 라는 이름을 import 하게 된다 — 그 순간 기사가
 * 갤러리에 딸린 것처럼 읽힌다.
 */
@FunctionalInterface
public interface Progress {

    /**
     * @param step  사람이 읽을 단계 이름 ("댓글을 다는 중")
     * @param done  끝낸 모델 호출 수
     * @param total 이 작업이 낼 총 호출 수
     */
    void at(String step, int done, int total);

    /** 아무 데도 안 알린다. 테스트와 동기 호출이 쓴다. */
    Progress NONE = (step, done, total) -> { };
}
