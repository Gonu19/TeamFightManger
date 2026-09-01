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

    /**
     * 선수 이름. 인자는 세이브의 {@code Athlete.ID} 다. 모르면 {@code null}.
     *
     * <p>기본 구현이 {@code null} 인 이유는 선수 이름 없이도 기사가 성립하기 때문이다 —
     * 이 인터페이스를 구현한 기존 코드를 전부 고치게 만들 값어치는 없다.
     * 이름을 아는 구현({@code StoryReference})만 덮어쓰면 된다.
     */
    default String athleteName(Integer athleteId) {
        return null;
    }

    /**
     * 챔피언 이름. 인자는 세이브의 코드다({@code 'Werewolf'}). 모르면 <b>코드를 그대로</b>.
     *
     * <p><b>왜 여기서 바꾸는가.</b> 세이브도 {@code MatchBrief} 도 코드를 들고 있는데
     * 기사와 갤 글은 한글로 읽혀야 한다("Ember scale는 DuelBlader와 Demon을" 은 몰입을
     * 깬다). 팀·선수 이름이 이미 이 인터페이스를 거치므로 챔피언만 다른 길로 갈 이유가 없다.
     *
     * <p><b>{@code null} 이 아니라 코드를 돌려준다.</b> 다른 메서드와 다른 이유는, 코드는
     * 번호와 달리 <b>이미 사람이 읽을 수 있는 이름</b>이라서다 — "Werewolf" 는 "팀 33" 과
     * 달리 그 자체로 말이 된다. 모르는 챔피언에 빈 칸을 남기면 기사가 채운다(D57).
     *
     * <p><b>대조도 이 이름을 쓴다.</b> 프롬프트는 한글인데 대조 어휘가 코드면 기사에 나온
     * 챔피언이 하나도 안 잡혀 검사가 <b>조용히 죽는다</b> (D66 · D80).
     */
    default String championName(String code) {
        return code;
    }

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
