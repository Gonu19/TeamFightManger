package com.teamfighter.tfm.parser.save;

import com.teamfighter.tfm.common.CanonicalJson;
import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.model.ParsedPatch;
import com.teamfighter.tfm.parser.model.ParsedSave;
import com.teamfighter.tfm.parser.model.ParsedScrim;
import com.teamfighter.tfm.parser.model.ParsedStat;
import com.teamfighter.tfm.parser.model.ParsedToday;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ParsedSave} 를 골든 파일과 같은 모양으로 옮긴다.
 *
 * <p>키 이름은 {@code tools/save_model.py} 가 만드는 dict 의 키와 같아야 한다.
 * 여기서 이름 하나가 어긋나면 {@code GoldenFileTest} 가 즉시 잡는다.
 *
 * <p><b>스크림의 스탯에는 {@code athlete_id} 키가 아예 없다.</b> null 로 넣는 것과 다르다 —
 * 스크림의 {@code MatchStat} 에는 선수 정보가 없기 때문이고, 레퍼런스 구현도 그렇게 만든다.
 */
public final class SaveJson {

    private SaveJson() {
    }

    public static String write(ParsedSave save) {
        return CanonicalJson.write(toMap(save));
    }

    public static Map<String, Object> toMap(ParsedSave save) {
        Map<String, Object> out = CanonicalJson.map();
        out.put("today", today(save.today()));
        out.put("patches", mapEach(save.patches(), SaveJson::patch));
        out.put("game_stats", mapEach(save.gameStats(), SaveJson::game));
        out.put("scrim_stats", mapEach(save.scrimStats(), SaveJson::scrim));
        return out;
    }

    private static Map<String, Object> today(ParsedToday t) {
        if (t == null) {
            return null;
        }
        Map<String, Object> out = CanonicalJson.map();
        out.put("season", t.season());
        out.put("day", t.day());
        out.put("run", t.run());
        return out;
    }

    private static Map<String, Object> game(ParsedGame g) {
        Map<String, Object> out = CanonicalJson.map();
        out.put("id", g.id());
        out.put("schedule_id", g.scheduleId());
        out.put("season", g.season());
        out.put("day", g.day());
        out.put("set_no", g.setNo());
        out.put("blue_team_id", g.blueTeamId());
        out.put("red_team_id", g.redTeamId());
        out.put("blue_score", g.blueScore());
        out.put("red_score", g.redScore());
        out.put("win_team", g.winTeam());
        out.put("blue_ban", g.blueBan());
        out.put("blue_pick", g.bluePick());
        out.put("red_ban", g.redBan());
        out.put("red_pick", g.redPick());
        out.put("champ_stat", mapEach(g.champStat(), s -> stat(s, true)));
        out.put("is_overtime", g.isOvertime());
        out.put("is_sudden_death", g.isSuddenDeath());
        return out;
    }

    private static Map<String, Object> scrim(ParsedScrim s) {
        Map<String, Object> out = CanonicalJson.map();
        out.put("id", s.id());
        out.put("team_id", s.teamId());
        out.put("blue_score", s.blueScore());
        out.put("red_score", s.redScore());
        out.put("blue_stat", mapEach(s.blueStat(), x -> stat(x, false)));
        out.put("red_stat", mapEach(s.redStat(), x -> stat(x, false)));
        out.put("team_size", s.teamSize());
        return out;
    }

    private static Map<String, Object> stat(ParsedStat s, boolean withAthlete) {
        Map<String, Object> out = CanonicalJson.map();
        out.put("champion", s.champion());
        out.put("kill", s.kill());
        out.put("death", s.death());
        out.put("assist", s.assist());
        out.put("dealing", s.dealing());
        out.put("tanking", s.tanking());
        out.put("healing", s.healing());
        out.put("live_duration", s.liveDuration());
        if (withAthlete) {
            out.put("athlete_id", s.athleteId());
        }
        return out;
    }

    private static Map<String, Object> patch(ParsedPatch p) {
        Map<String, Object> out = CanonicalJson.map();
        out.put("season", p.season());
        out.put("day", p.day());
        out.put("run", p.run());
        out.put("new_champs", p.newChamps());
        out.put("changes", mapEach(p.changes(), SaveJson::change));
        return out;
    }

    private static Map<String, Object> change(ParsedPatch.Change c) {
        Map<String, Object> out = CanonicalJson.map();
        out.put("name", c.name());
        out.put("attack", c.attack());
        out.put("magic", c.magic());
        out.put("defence", c.defence());
        out.put("max_hp", c.maxHp());
        out.put("attack_speed", c.attackSpeed());
        out.put("skill_cool", c.skillCool());
        out.put("move_speed", c.moveSpeed());
        return out;
    }

    private static <T> List<Object> mapEach(List<T> src, java.util.function.Function<T, ?> fn) {
        List<Object> out = new ArrayList<>(src.size());
        for (T item : src) {
            out.add(fn.apply(item));
        }
        return out;
    }
}
