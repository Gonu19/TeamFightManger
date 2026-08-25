package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.model.ParsedStat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 참가자를 진영에 배정한다.
 *
 * <p><b>이 클래스가 존재하는 이유가 D20 이다.</b> {@code GameStat.ChampStat} 의 순서는
 * {@code BluePick + RedPick} 과 <b>경기의 20.5% 에서 어긋난다</b>(실측 640/805).
 * 인덱스로 매칭했다면 다섯 경기 중 하나에서 진영이 통째로 뒤바뀌고,
 * 카운터 분석이 오염된다. 예외도 경고도 없이.
 *
 * <p>그래서 <b>챔피언 이름</b>으로 매칭한다. 한 경기에 같은 챔피언이 두 번 나올 수 없으므로
 * (스키마의 {@code match_participant_unique_champ}) 이름은 그 경기 안에서 유일한 키다.
 */
public final class ParticipantMatcher {

    private ParticipantMatcher() {
    }

    /**
     * 한 참가자의 자리.
     *
     * @param champion  챔피언 코드
     * @param pickOrder 그 진영 안에서 몇 번째 픽 슬롯인지. <b>드래프트 순서가 아니다</b>(D25)
     * @param stat      그 챔피언의 경기 기록. 없을 수 있다
     */
    public record Slot(String champion, int pickOrder, ParsedStat stat) {
    }

    /**
     * 한 진영의 픽을 자리 목록으로 만든다.
     *
     * @param picks     그 진영의 픽 목록 (BluePick 또는 RedPick)
     * @param champStat 경기 전체의 ChampStat. <b>순서를 쓰지 않고 이름으로만 찾는다</b>
     */
    public static List<Slot> slotsOf(List<String> picks, List<ParsedStat> champStat) {
        Map<String, ParsedStat> byChampion = new HashMap<>();
        for (ParsedStat s : champStat) {
            if (s.champion() != null) {
                byChampion.put(s.champion(), s);
            }
        }

        List<Slot> out = new java.util.ArrayList<>(picks.size());
        for (int i = 0; i < picks.size(); i++) {
            String champion = picks.get(i);
            if (champion == null || champion.isBlank()) {
                throw new IllegalStateException(
                        "픽에 챔피언 이름이 없다 (슬롯 " + (i + 1) + "). "
                                + "이름이 없으면 진영을 정할 수 없고, 그 경기는 분석에 쓸 수 없다");
            }
            out.add(new Slot(champion, i + 1, byChampion.get(champion)));
        }
        return out;
    }

    /**
     * 이 경기의 {@code ChampStat} 순서가 픽 순서와 일치하는지.
     *
     * <p>적재에는 쓰지 않는다 — 어긋나도 이름으로 매칭하므로 상관없다.
     * D20 의 근거 수치를 계속 관측하기 위한 것이다.
     */
    public static boolean champStatOrderMatchesPicks(ParsedGame game) {
        List<ParsedStat> stats = game.champStat();
        List<String> picks = new java.util.ArrayList<>(game.bluePick());
        picks.addAll(game.redPick());
        if (stats.size() != picks.size()) {
            return false;
        }
        for (int i = 0; i < picks.size(); i++) {
            if (!picks.get(i).equals(stats.get(i).champion())) {
                return false;
            }
        }
        return true;
    }
}
