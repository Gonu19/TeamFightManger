package com.teamfighter.tfm.story.dao;

/**
 * 기사 하나를 가리키는 <b>매치 신원</b>. {@code article} 표의 UNIQUE 제약과 같은 네 값이다.
 *
 * <h2>왜 이 네 값인가</h2>
 *
 * 기사는 매치 하나당 한 편이다. 그런데 "같은 매치" 를 무엇으로 판정하느냐가 문제였다.
 * 세이브에는 {@code MatchSchedule.ID} 가 있지만 <b>대회마다 ID 공간이 따로</b>라서
 * 실측 190건이 114개 값에 겹친다 — 봄 대회 3번과 여름 대회 3번이 같은 번호다.
 * 그래서 (시즌, 일, 두 팀) 으로 판정한다. 같은 날 같은 두 팀이 두 번 붙지 않으므로 유일하다.
 *
 * <h2>여기 담기는 팀 번호는 DB 번호다</h2>
 *
 * {@code team.team_id} 이지 세이브의 {@code game_team_id} 가 아니다. 두 번호 공간이 섞이면
 * "이미 쓴 기사" 판정이 조용히 어긋나서 같은 매치를 매번 다시 쓰게 된다 —
 * 업서트라 결과 화면은 멀쩡해 보이고, 늘어나는 것은 <b>모델 호출 요금</b>뿐이다.
 * 변환은 {@link StoryReference#teamId(Integer)} 가 한다.
 *
 * <p>record 라 {@code equals}/{@code hashCode} 가 자동이고, 그래서 {@code Set} 에 그대로
 * 넣어 "이 매치를 썼나" 를 O(1) 로 물을 수 있다. 이 클래스가 존재하는 이유의 절반이 그것이다.
 */
public record ArticleKey(int season, int day, int blueTeamId, int redTeamId) {
}
