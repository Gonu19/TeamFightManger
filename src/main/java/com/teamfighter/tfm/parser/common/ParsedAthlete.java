package com.teamfighter.tfm.parser.common;

/**
 * 세이브 안의 선수 하나 (D58).
 *
 * <p><b>값은 전부 "지금" 스냅샷이다.</b> 세이브에 이력이 없어 나이·연봉·팬 수는 마지막
 * 저장 시점 값이지 과거 경기 시점 값이 아니다. 경기별 소속은 이 타입이 아니라
 * 경기 자체가 알려준다.
 *
 * @param id          {@code Athlete.ID}. {@code match_participant.athlete_id} 와 같은 값이다
 * @param nameIndex   이름 풀 인덱스. {@code Athlete.Name} 이 {@code `33} 이면 33.
 *                    형식이 다르면 {@code null}
 * @param gameTeamId  현재 소속 팀의 게임 내 번호. 무소속이면 {@code null}
 * @param category    선수의 <b>주특기</b>. 챔피언 분류가 아니다 ({@code savefile.md})
 * @param belong      {@code AthleteBelong} 원값. 의미는 확인되지 않았다
 * @param career      커리어 누적 성적. 없으면 {@code null}
 */
public record ParsedAthlete(
        Integer id,
        Integer nameIndex,
        Integer gameTeamId,
        Integer age,
        Integer salary,
        Integer fan,
        Integer condition,
        Integer potential,
        Integer playingSeason,
        Integer category,
        Integer belong,
        Career career) {

    /**
     * {@code PlayerResult}. 커리어 누적이라 경기별로 쪼갤 수 없다.
     *
     * @param sets 출전 세트 수
     */
    public record Career(
            Integer sets,
            Integer kill,
            Integer death,
            Integer assist,
            Long deal,
            Long tank,
            Long heal) {
    }
}
