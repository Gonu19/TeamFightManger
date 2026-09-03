package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.model.ParsedStat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 매치 하나의 <b>사실</b>만 모은 순수 레코드. 기사 한 편의 입력이다.
 *
 * <p><b>LLM 도 HTTP 도 DB 도 모른다.</b> 그래서 DB 없이 테스트되고, 텍스트 렌더링과
 * 따로 검증된다. 3층 경계(사실 · 해석 · 창작)의 맨 아래에 있다.
 *
 * <p><b>이 레코드가 곧 화면의 「이 기사가 쓴 숫자」 블록이다 (D61).</b> 기사를 만든
 * 시점의 값으로 고정되며, 나중에 집계가 갱신돼도 바뀌지 않는다 — 기사가 <i>그때</i>
 * 무엇을 보고 썼는지가 남아야 검증이 되기 때문이다.
 *
 * <p><b>진영이 통일돼 있다.</b> 세트마다 진영이 바뀌므로(실측 294세트 중 122세트)
 * 모든 값을 <b>매치 기준</b>({@link ParsedSchedule#blueTeamId()})으로 돌려세운다.
 * 이걸 안 하면 "3세트에서 30번 팀이 이겼다" 를 세트마다 다르게 읽게 된다.
 *
 * @param blueTeamId 매치 기준 블루팀
 * @param sets       세트 번호 순. 이벤트전은 비어 있다 (D16)
 */
public record MatchBrief(
        Integer scheduleId,
        Integer competitionId,
        String competitionKey,
        Integer season,
        Integer day,
        Integer round,
        Integer blueTeamId,
        Integer redTeamId,
        int blueScore,
        int redScore,
        int blueKill,
        int redKill,
        int needWin,
        boolean isEvent,
        List<SetBrief> sets) {

    /**
     * 세트 하나. <b>매치 기준 진영으로 돌려세운 값</b>이다.
     *
     * @param sideSwapped 이 세트의 실제 진영이 매치 기준과 반대였나. 기사가
     *                    "진영을 바꿔" 를 말할 수 있어야 하므로 버리지 않고 남긴다
     * @param blueWon     매치 기준 블루팀이 이겼나
     * @param bluePick    매치 기준 블루팀이 고른 챔피언
     */
    public record SetBrief(
            int setNo,
            boolean sideSwapped,
            boolean blueWon,
            int blueKill,
            int redKill,
            List<String> bluePick,
            List<String> redPick,
            List<String> blueBan,
            List<String> redBan,
            boolean isOvertime,
            boolean isSuddenDeath,
            List<PlayerLine> players) {

        public SetBrief {
            players = players == null ? List.of() : List.copyOf(players);
        }
    }

    /**
     * 세트 하나에서 <b>선수 한 명</b>이 한 일. 기사가 사람 이야기를 하려면 이게 있어야 한다.
     *
     * <p><b>한 줄에 전부 묶여 있다는 것이 핵심이다.</b> 선수 · 챔피언 · 기록이 따로 놀면
     * 모델이 그 셋을 섞는다 — "Faker 가 닌자로 3킬, Chovy 가 마법사로 10킬" 을 주면
     * "Faker 가 마법사로 10킬" 이 나온다. 관계는 프롬프트에서 한 덩어리로 붙어 있어야 하고,
     * 그렇게 붙여 주는 것이 {@link BriefRenderer} 의 일이다.
     *
     * <p><b>진영은 챔피언 이름으로 가른다.</b> {@code champStat} 의 순서는 믿을 수 없다 —
     * D20 이 적재에서 같은 이유로 챔피언 이름 매칭을 골랐다(인덱스 순서가 경기의 20.5%에서
     * 어긋난다). 골든 파일 805세트 6,440행을 실측했다: 양쪽이 같은 챔피언을 고른 세트 0건,
     * 픽 목록에 없는 스탯 행 0건이므로 이름으로 가르는 것이 유일하게 정해진다.
     *
     * @param blue      매치 기준 블루팀인가. 세트의 진영이 아니라 <b>매치 기준</b>이다
     * @param athleteId 세이브의 {@code Athlete.ID}. 이름은 슬롯별 표에서 찾는다
     *                  (공식전에만 있다 — 스크림 {@code MatchStat} 에는 선수가 없다)
     */
    public record PlayerLine(
            boolean blue,
            Integer athleteId,
            String champion,
            int kill,
            int death,
            int assist,
            int dealing,
            int tanking,
            int healing) {
    }

    /**
     * 스케줄과 그 세트들로 brief 를 만든다.
     *
     * <p><b>두 등식을 강제한다.</b> 세트 승수의 합 = 스케줄 스코어, 세트 킬의 합 =
     * 스케줄 킬. 실측 109/109 매치에서 성립했으므로, 깨지면 데이터가 아니라
     * <b>우리 조인이나 진영 처리가 틀린 것</b>이다. 조용히 넘어가면 기사가 틀린 숫자를
     * 그럴듯하게 쓴다 — 그게 이 프로젝트에서 가장 나쁜 실패다.
     *
     * @throws IllegalArgumentException 매치가 안 끝났거나, 남의 세트가 섞였거나, 등식이 깨졌을 때
     */
    public static MatchBrief of(ParsedSchedule schedule, List<ParsedGame> sets) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(sets, "sets");

        if (!schedule.isPlayed()) {
            throw new IllegalArgumentException(
                    "아직 끝나지 않은 매치로는 brief 를 만들지 않는다: progress=" + schedule.progress());
        }

        List<SetBrief> normalized = new ArrayList<>();
        int wins = 0;
        int losses = 0;
        int blueKills = 0;
        int redKills = 0;

        List<ParsedGame> ordered = new ArrayList<>(sets);
        ordered.sort(Comparator.comparing(g -> g.setNo() == null ? 0 : g.setNo()));

        for (ParsedGame game : ordered) {
            boolean swapped = swapped(schedule, game);                          // 이 세트의 진영이 매치 기준과 반대인가
            boolean blueWon = (Integer.valueOf(0).equals(game.winTeam())) != swapped;
            int bk = orZero(swapped ? game.redScore() : game.blueScore());
            int rk = orZero(swapped ? game.blueScore() : game.redScore());

            List<String> bluePicks = picks(swapped ? game.redPick() : game.bluePick());
            List<String> redPicks = picks(swapped ? game.bluePick() : game.redPick());

            normalized.add(new SetBrief(
                    orZero(game.setNo()), swapped, blueWon, bk, rk,
                    bluePicks,
                    redPicks,
                    picks(swapped ? game.redBan() : game.blueBan()),
                    picks(swapped ? game.blueBan() : game.redBan()),
                    Boolean.TRUE.equals(game.isOvertime()),
                    Boolean.TRUE.equals(game.isSuddenDeath()),
                    playerLines(game, bluePicks, redPicks)));

            wins += blueWon ? 1 : 0;
            losses += blueWon ? 0 : 1;
            blueKills += bk;
            redKills += rk;
        }

        if (!normalized.isEmpty()) {
            requireEquals("세트 승수의 합이 스케줄 스코어와 다르다",
                    wins, losses, schedule.blueScore(), schedule.redScore());
            requireEquals("세트 킬의 합이 스케줄 킬과 다르다",
                    blueKills, redKills, schedule.blueKill(), schedule.redKill());
        }

        return new MatchBrief(
                schedule.scheduleId(), schedule.competitionId(), schedule.competitionKey(),
                schedule.season(), schedule.day(), schedule.round(),
                schedule.blueTeamId(), schedule.redTeamId(),
                schedule.blueScore(), schedule.redScore(),
                schedule.blueKill(), schedule.redKill(),
                schedule.needWin(), schedule.isEvent(),
                List.copyOf(normalized));
    }

    /** 이 세트의 진영이 매치 기준과 반대인가. 남의 세트면 던진다 — 조용히 버리지 않는다. */
    private static boolean swapped(ParsedSchedule schedule, ParsedGame game) {
        if (Objects.equals(game.blueTeamId(), schedule.blueTeamId())
                && Objects.equals(game.redTeamId(), schedule.redTeamId())) {
            return false;
        }
        if (Objects.equals(game.blueTeamId(), schedule.redTeamId())
                && Objects.equals(game.redTeamId(), schedule.blueTeamId())) {
            return true;
        }
        throw new IllegalArgumentException(
                "이 매치의 팀이 아닌 세트가 섞였다: 세트 " + game.blueTeamId() + " vs " + game.redTeamId()
                        + ", 매치 " + schedule.blueTeamId() + " vs " + schedule.redTeamId());
    }

    private static void requireEquals(String what, int blue, int red, int expectBlue, int expectRed) {
        if (blue != expectBlue || red != expectRed) {
            throw new IllegalArgumentException(
                    what + ": 세트 합 " + blue + ":" + red + ", 스케줄 " + expectBlue + ":" + expectRed);
        }
    }

    /**
     * 개인 기록을 매치 기준 진영에 붙인다.
     *
     * <p>{@code champStat} 은 진영을 안 알려준다. 그래서 <b>챔피언 이름이 어느 픽 목록에
     * 있는지</b>로 가른다 — D20 이 적재에서 고른 것과 같은 방법이고, 이유도 같다
     * ({@code champStat} 의 인덱스 순서가 경기의 20.5%에서 어긋난다).
     *
     * <p>어느 쪽에도 없거나 양쪽에 다 있으면 <b>던진다.</b> 골든 파일 805세트에서 한 번도
     * 없었던 상황이라(겹치는 픽 0건 · 픽에 없는 스탯 0건), 그게 나온다면 우리 가정이 깨진
     * 것이다. 조용히 버리면 그 선수만 기사에서 사라지는데, 빠졌다는 사실 자체가 안 보인다.
     *
     * @param bluePicks 매치 기준 블루팀의 픽 (이미 진영 정규화된 목록)
     */
    private static List<PlayerLine> playerLines(ParsedGame game,
                                                List<String> bluePicks, List<String> redPicks) {
        if (game.champStat() == null) {                                         // 이벤트전은 개인 기록이 없다 (D16)
            return List.of();
        }
        List<PlayerLine> lines = new ArrayList<>();
        for (ParsedStat stat : game.champStat()) {
            String champion = stat.champion();
            boolean inBlue = bluePicks.contains(champion);                      // 1. 블루 픽에 있나
            boolean inRed = redPicks.contains(champion);                        // 2. 레드 픽에 있나
            if (inBlue == inRed) {                                              // 3. 둘 다이거나 둘 다 아니면 가를 수 없다
                throw new IllegalArgumentException(
                        "개인 기록의 챔피언을 진영에 붙일 수 없다: " + champion
                                + " (블루 " + bluePicks + ", 레드 " + redPicks + ")");
            }
            lines.add(new PlayerLine(                                           // 4. 매치 기준 진영으로 한 줄
                    inBlue, stat.athleteId(), champion,
                    orZero(stat.kill()), orZero(stat.death()), orZero(stat.assist()),
                    orZero(stat.dealing()), orZero(stat.tanking()), orZero(stat.healing())));
        }
        return lines;
    }

    private static List<String> picks(List<String> raw) {
        return raw == null ? List.of() : List.copyOf(raw);
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 이긴 팀. 무승부면 {@code null} — 이벤트전에서 나올 수 있다.
     *
     * <p>{@code ParsedSchedule} 과 같은 규칙이다. brief 는 스케줄에서 스코어를
     * 그대로 받았으므로 같은 답이 나온다.
     */
    public Integer winnerTeamId() {
        if (blueScore == redScore) {
            return null;
        }
        return blueScore > redScore ? blueTeamId : redTeamId;
    }

    /** 진 팀. 무승부면 {@code null}. */
    public Integer loserTeamId() {
        if (blueScore == redScore) {
            return null;
        }
        return blueScore > redScore ? redTeamId : blueTeamId;
    }

    /** 한 세트도 안 내주고 이겼나. 기사 첫 문장이 쓰는 값이다. */
    public boolean isSweep() {
        return winnerTeamId() != null && Math.min(blueScore, redScore) == 0;
    }

    public int setCount() {
        return sets.size();
    }

    /**
     * 선수 한 명의 <b>매치 전체</b> 합계.
     *
     * @param champions 이 매치에서 그 선수가 쓴 챔피언. 세트마다 다를 수 있어서 목록이다.
     *                  <b>코드 그대로</b>다 — 한글 이름으로 바꾸는 것은 이름표를 아는
     *                  쪽(렌더러)의 몫이고, 이 레코드는 이름표를 모른다
     */
    public record AthleteTotals(
            Integer athleteId,
            boolean blue,
            int kill, int death, int assist,
            int dealing, int tanking, int healing,
            List<String> champions) {

        public AthleteTotals {
            champions = champions == null ? List.of() : List.copyOf(champions);
        }
    }

    /**
     * 세트를 가로질러 선수별로 더한 값.
     *
     * <h2>왜 brief 가 이걸 갖고 있나</h2>
     *
     * <b>두 곳이 같은 합계를 봐야 하기 때문이다.</b> 프롬프트는 이 표를 모델에게 보여주고
     * ({@code StoryPrompts.playerTotals}), 대조는 그 합계를 <b>아는 숫자</b>로 넣는다
     * ({@code FactCheck}). 각자 더하면 언젠가 갈라지고, 갈라지는 순간
     * <b>우리가 준 숫자를 우리가 지적하는</b> 상태가 된다 — 거짓 양성이 이 장치를 죽인다.
     *
     * <p>세트별 줄을 그대로 두고 이걸 <b>더한</b> 이유는 실물 실패에 있다. 모델에게
     * 세트별 줄만 주고 "수훈 선수를 골라라" 를 시켰더니 합계를 스스로 못 냈다 —
     * 두 세트의 딜을 "10898의 가한피해와 12468의 가한피해" 로 두 번 적었다.
     *
     * <p>이름을 모르는 선수도 <b>뺀 채로 두지 않는다.</b> 화면에 안 쓰는 것과 대조가
     * 모르는 것은 다른 문제다 — 빼면 그 선수의 숫자가 "brief 에 없는 숫자" 가 된다.
     *
     * @return 등장 순서대로. 개인 기록이 없는 매치(이벤트전, D16)에서는 비어 있다
     */
    public List<AthleteTotals> athleteTotals() {
        record Key(Integer athleteId, boolean blue) {
        }
        java.util.Map<Key, int[]> sums = new java.util.LinkedHashMap<>();
        java.util.Map<Key, java.util.LinkedHashSet<String>> picks = new java.util.LinkedHashMap<>();

        for (SetBrief set : sets) {
            for (PlayerLine line : set.players()) {
                Key key = new Key(line.athleteId(), line.blue());
                int[] s = sums.computeIfAbsent(key, k -> new int[6]);
                s[0] += line.kill();
                s[1] += line.death();
                s[2] += line.assist();
                s[3] += line.dealing();
                s[4] += line.tanking();
                s[5] += line.healing();
                picks.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>())
                        .add(line.champion());
            }
        }

        List<AthleteTotals> out = new ArrayList<>();
        sums.forEach((key, s) -> out.add(new AthleteTotals(
                key.athleteId(), key.blue(),
                s[0], s[1], s[2], s[3], s[4], s[5],
                List.copyOf(picks.get(key)))));
        return out;
    }
}
