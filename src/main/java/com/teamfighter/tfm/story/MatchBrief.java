package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;

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
            boolean isSuddenDeath) {
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
            boolean swapped = swapped(schedule, game);
            boolean blueWon = (Integer.valueOf(0).equals(game.winTeam())) != swapped;
            int bk = orZero(swapped ? game.redScore() : game.blueScore());
            int rk = orZero(swapped ? game.blueScore() : game.redScore());

            normalized.add(new SetBrief(
                    orZero(game.setNo()), swapped, blueWon, bk, rk,
                    picks(swapped ? game.redPick() : game.bluePick()),
                    picks(swapped ? game.bluePick() : game.redPick()),
                    picks(swapped ? game.redBan() : game.blueBan()),
                    picks(swapped ? game.blueBan() : game.redBan()),
                    Boolean.TRUE.equals(game.isOvertime()),
                    Boolean.TRUE.equals(game.isSuddenDeath())));

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
}
