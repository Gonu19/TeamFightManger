package com.teamfighter.tfm.story;

/**
 * 번호를 사람이 읽는 이름으로 바꾼다. {@link BriefRenderer} 가 쓰는 유일한 바깥 의존이다.
 *
 * <p><b>왜 인터페이스인가.</b> 이름의 출처는 DB(`team.name`)인데, 렌더러가 DB 를 알면
 * 순수하지 않게 되고 테스트에 DB 가 필요해진다. 이름 찾기만 떼어 두면 렌더러는
 * {@code MatchBrief} 와 이 인터페이스만 알면 된다.
 *
 * <p><b>모르면 {@code null} 을 준다.</b> 렌더러가 번호를 그대로 적는다.
 * 빈 칸으로 두면 기사가 그 자리를 지어낸다 — 틀린 이름은 없는 이름보다 나쁘다(D57).
 */
public interface NameBook {

    /** 팀 이름. 모르면 {@code null}. */
    String teamName(Integer teamId);

    /** 대회 이름. 인자는 로컬라이제이션 키다. 모르면 {@code null}. */
    String competitionName(String key);

    /** 아무 이름도 모르는 이름표. 테스트와 이름 적재 전 단계에서 쓴다. */
    static NameBook ids() {
        return new NameBook() {
            @Override
            public String teamName(Integer teamId) {
                return null;
            }

            @Override
            public String competitionName(String key) {
                return null;
            }
        };
    }
}
