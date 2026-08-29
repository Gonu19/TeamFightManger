package com.teamfighter.tfm.story;

import java.util.List;

/**
 * 기사 하나를 {@link MatchBrief} 와 대조한 결과.
 *
 * <p><b>심각도를 둘로 나눈다.</b> 이 구분이 이 장치의 생사를 가른다 — 산문에 나오는
 * 숫자를 전부 오류로 부르면 목록이 잡음으로 가득 차고, 그러면 아무도 보지 않게 되어
 * 검증 장치가 죽는다.
 *
 * @param contradictions brief 와 <b>어긋나는</b> 것. 기사가 틀렸다
 * @param unverified     brief 가 <b>모르는</b> 것. 틀렸다는 뜻이 아니다 —
 *                       "20분 만에" 같은 산문이 여기 들어온다
 */
public record FactCheckResult(List<Finding> contradictions, List<Finding> unverified) {

    /**
     * 지적 하나.
     *
     * @param what     무엇이 문제인가 (사람이 읽는 한 줄)
     * @param evidence 기사에서 뽑아낸 그 부분
     */
    public record Finding(String what, String evidence) {

        @Override
        public String toString() {
            return what + ": " + evidence;
        }
    }

    public FactCheckResult {
        contradictions = List.copyOf(contradictions);
        unverified = List.copyOf(unverified);
    }

    /** 기사를 그대로 실어도 되는가. 모순이 하나라도 있으면 안 된다. */
    public boolean isClean() {
        return contradictions.isEmpty();
    }
}
