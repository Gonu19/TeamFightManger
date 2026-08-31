package com.teamfighter.tfm.web.view;

import java.time.OffsetDateTime;

/**
 * 연대기 화면의 한 줄 = <b>매치 하나가 사이클의 어디까지 왔는가</b>.
 *
 * <h2>사이클</h2>
 *
 * <pre>
 *   ① 경기 진행 · 세이브 저장   워처가 자동으로 적재한다
 *   ② 데이터 반영             집계가 돈다 (티어·카운터·쌍 효과)
 *   ③ 기사 작성               사람이 누른다 — 모델 호출 2회
 *   ④ 갤러리 반응             사람이 누른다 — 모델 호출 2회
 * </pre>
 *
 * 세 계층이 각자 "가장 최근 안 한 것" 을 알아서 고르고 있었다. 그러면 <b>기사를 쓴
 * 경기와 갤러리를 뽑은 경기가 다를 수 있고</b>, 화면에는 그 사실이 안 나온다 —
 * 목록 둘이 따로 있으니 나란히 놓고 볼 수가 없었다. 그래서 매치를 한 줄로 세우고
 * 그 줄이 자기 상태를 들고 있게 한다.
 *
 * <h2>세이브를 다시 안 읽는다</h2>
 *
 * {@code StoryGenerator} 와 {@code GalleryGenerator} 는 <b>쓸 때</b> 세이브를 파싱한다 —
 * 매치 일정({@code MatchSchedule})이 DB 에 없기 때문이다. 그런데 <b>목록을 그릴 때</b>는
 * 그럴 필요가 없다: 끝난 매치는 {@code match_record} 에 세트 단위로 들어와 있고,
 * (시즌 · 일 · 두 팀) 으로 묶으면 매치가 된다. 화면 한 번에 파일을 파싱하면 목록을
 * 여는 것만으로 수백 밀리초가 나간다.
 *
 * <p>대가: <b>아직 안 치른 매치는 목록에 없다.</b> 그건 옳다 — 사이클은 경기가
 * 끝난 뒤부터 시작한다.
 *
 * @param sets       세트 수. 매치 하나가 몇 판이었나
 * @param homeWins   {@link #homeTeamId} 가 이긴 세트 수. <b>진영이 아니라 팀으로</b> 센다 —
 *                   세트의 진영은 매치 기준과 반대인 경우가 실측 294세트 중 122건이다
 * @param articleId  기사가 있으면 그 번호. 없으면 {@code null}
 * @param batchId    갤러리가 있으면 <b>가장 최근</b> 배치 번호. 없으면 {@code null}.
 *                   갤은 쌓이므로(D72 결정 5) 여럿일 수 있다
 * @param batches    그 매치에 쌓인 갤러리 수
 * @param ingestedAt 마지막 세트가 적재된 시각. ① 이 언제 끝났는지가 이 값이다
 */
public record CycleRow(
        int season,
        int day,
        int homeTeamId,
        int awayTeamId,
        String homeTeamName,
        String awayTeamName,
        boolean homeIsPlayer,
        boolean awayIsPlayer,
        int sets,
        int homeWins,
        Long articleId,
        Long batchId,
        int batches,
        OffsetDateTime ingestedAt) {

    /** 상대가 이긴 세트 수. 저장하지 않는 이유는 두 값이 어긋날 자리를 안 만들려고다. */
    public int awayWins() {
        return sets - homeWins;
    }

    /** 이름이 없으면 번호로 부른다. 빈 칸보다 낫다 — 적어도 두 줄을 구분할 수 있다. */
    public String homeLabel() {
        return label(homeTeamName, homeTeamId);
    }

    public String awayLabel() {
        return label(awayTeamName, awayTeamId);
    }

    /** 플레이어 팀이 낀 매치인가. 화면이 그 줄을 강조한다. */
    public boolean hasPlayer() {
        return homeIsPlayer || awayIsPlayer;
    }

    /**
     * 플레이어 팀이 이겼는가. 플레이어 팀이 없으면 {@code null} —
     * <b>false 가 아니다.</b> "졌다" 와 "내 경기가 아니다" 는 다른 말이다.
     */
    public Boolean playerWon() {
        if (homeIsPlayer) {
            return homeWins > awayWins();
        }
        if (awayIsPlayer) {
            return awayWins() > homeWins;
        }
        return null;
    }

    public boolean hasArticle() {
        return articleId != null;
    }

    public boolean hasGallery() {
        return batchId != null;
    }

    /**
     * 사이클이 어디까지 왔나 (0~2). 화면이 진행 점 세 개를 그린다.
     *
     * <p>적재는 언제나 끝나 있다 — 이 줄이 존재한다는 것이 곧 적재됐다는 뜻이다.
     */
    public int stage() {
        if (hasGallery()) {
            return 2;
        }
        return hasArticle() ? 1 : 0;
    }

    private static String label(String name, int teamId) {
        return name == null || name.isBlank() ? "팀 " + teamId : name;
    }
}
