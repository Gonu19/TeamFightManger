package com.teamfighter.tfm.parser.save;

import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.model.ParsedPatch;
import com.teamfighter.tfm.parser.model.ParsedSave;
import com.teamfighter.tfm.parser.model.ParsedScrim;
import com.teamfighter.tfm.parser.model.ParsedStat;
import com.teamfighter.tfm.parser.model.ParsedToday;
import com.teamfighter.tfm.parser.nrbf.NrbfObject;
import com.teamfighter.tfm.parser.nrbf.NrbfParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.teamfighter.tfm.parser.save.SaveValues.boolOf;
import static com.teamfighter.tfm.parser.save.SaveValues.get;
import static com.teamfighter.tfm.parser.save.SaveValues.intOf;
import static com.teamfighter.tfm.parser.save.SaveValues.list;
import static com.teamfighter.tfm.parser.save.SaveValues.names;
import static com.teamfighter.tfm.parser.save.SaveValues.stringOf;

/**
 * 세이브 파일에서 경기 데이터를 꺼낸다.
 *
 * <p>레퍼런스 구현은 {@code tools/save_model.py} 이고, 결과는
 * {@code tests/baseline/*.json} 골든 파일과 바이트 단위로 일치해야 한다.
 *
 * <p>세이브 파일은 읽기만 한다. 어떤 경우에도 쓰지 않는다.
 */
public final class SaveParser {

    /** 경기 데이터가 들어 있는 스트림. 0 은 미리보기, 2 는 밴픽 AI 가중치다. */
    private static final int MATCH_STREAM = 1;

    private SaveParser() {
    }

    public static ParsedSave read(Path path) throws IOException {
        byte[] buf = Files.readAllBytes(path);
        List<int[]> streams = NrbfParser.splitStreams(buf);
        if (streams.size() <= MATCH_STREAM) {
            throw new IllegalArgumentException(
                    "NRBF 스트림이 " + streams.size() + "개뿐이다. 세이브 파일이 아니거나 잘렸다: " + path);
        }
        int[] bounds = streams.get(MATCH_STREAM);
        NrbfParser parser = new NrbfParser(buf, bounds[0], bounds[1]).parse();
        return new ParsedSave(
                readToday(parser),
                readPatches(parser),
                readGames(parser),
                readScrims(parser));
    }

    // ------------------------------------------------------------------ 경기

    static List<ParsedGame> readGames(NrbfParser parser) {
        List<ParsedGame> out = new ArrayList<>();
        for (NrbfObject g : walk(parser, "GameStat")) {
            out.add(new ParsedGame(
                    intOf(g, "ID"),
                    intOf(g, "ScheduleID"),
                    intOf(g, "Season"),
                    intOf(g, "Day"),
                    intOf(g, "Set"),
                    intOf(g, "BlueTeamID"),
                    intOf(g, "RedTeamID"),
                    intOf(g, "BlueScore"),
                    intOf(g, "RedScore"),
                    intOf(g, "WinTeam"),
                    names(get(g, "BlueBan")),
                    names(get(g, "BluePick")),
                    names(get(g, "RedBan")),
                    names(get(g, "RedPick")),
                    champStats(get(g, "ChampStat")),
                    boolOf(g, "IsOvertime"),
                    boolOf(g, "IsSuddenDeath")));
        }
        out.sort(Comparator.comparingInt(x -> x.id() == null ? -1 : x.id()));
        return out;
    }

    static List<ParsedScrim> readScrims(NrbfParser parser) {
        List<ParsedScrim> out = new ArrayList<>();
        for (NrbfObject s : walk(parser, "ScrimStat")) {
            List<ParsedStat> blue = plainStats(get(s, "BlueStat"));
            List<ParsedStat> red = plainStats(get(s, "RedStat"));
            out.add(new ParsedScrim(
                    intOf(s, "ID"),
                    intOf(s, "TeamID"),
                    intOf(s, "BlueScore"),
                    intOf(s, "RedScore"),
                    blue,
                    red,
                    Math.max(blue.size(), red.size())));
        }
        out.sort(Comparator.comparingInt(x -> x.id() == null ? -1 : x.id()));
        return out;
    }

    // ------------------------------------------------------------------ 스탯

    /** AthleteMatchStat 목록. {@code Stat} 안에 MatchStat 이 들어 있고 선수 id 가 바깥에 있다. */
    private static List<ParsedStat> champStats(Object listValue) {
        List<ParsedStat> out = new ArrayList<>();
        for (Object o : list(listValue)) {
            if (o == null) {
                continue;
            }
            Object inner = get(o, "Stat");
            Object source = (inner instanceof NrbfObject) ? inner : o;
            out.add(stat(source, intOf(o, "AthleteID")));
        }
        return out;
    }

    /** 스크림의 MatchStat 목록. 선수 정보가 없다. */
    private static List<ParsedStat> plainStats(Object listValue) {
        List<ParsedStat> out = new ArrayList<>();
        for (Object o : list(listValue)) {
            if (o != null) {
                out.add(stat(o, null));
            }
        }
        return out;
    }

    private static ParsedStat stat(Object o, Integer athleteId) {
        return new ParsedStat(
                stringOf(o, "Champion"),
                intOf(o, "Kill"),
                intOf(o, "Death"),
                intOf(o, "Assist"),
                intOf(o, "Dealing"),
                intOf(o, "Tanking"),
                intOf(o, "Healing"),
                intOf(o, "LiveDuration"),
                athleteId);
    }

    // ------------------------------------------------------------------ 패치·날짜

    static List<ParsedPatch> readPatches(NrbfParser parser) {
        List<ParsedPatch> out = new ArrayList<>();
        for (NrbfObject n : walk(parser, "PatchNews")) {
            Object date = get(n, "Date");
            List<ParsedPatch.Change> changes = new ArrayList<>();
            for (Object p : list(get(n, "Patches"))) {
                if (p == null) {
                    continue;
                }
                changes.add(new ParsedPatch.Change(
                        stringOf(p, "Name"),
                        intOf(p, "Attack"),
                        intOf(p, "Magic"),
                        intOf(p, "Defence"),
                        intOf(p, "MaxHp"),
                        intOf(p, "AttackSpeed"),
                        intOf(p, "SkillCool"),
                        intOf(p, "MoveSpeed")));
            }
            List<String> newChamps = names(get(n, "NewChamps"));
            if (changes.isEmpty() && newChamps.isEmpty()) {
                continue;                          // 패치가 아닌 일반 뉴스
            }
            changes.sort(Comparator.comparing(c -> c.name() == null ? "" : c.name()));
            out.add(new ParsedPatch(
                    intOf(date, "Season"),
                    intOf(date, "Day"),
                    intOf(date, "Run"),
                    newChamps,
                    changes));
        }
        out.sort(Comparator
                .comparingInt((ParsedPatch p) -> p.season() == null ? 0 : p.season())
                .thenComparingInt(p -> p.day() == null ? 0 : p.day())
                .thenComparingInt(p -> p.run() == null ? 0 : p.run()));
        return out;
    }

    /**
     * 현재 게임 내 날짜.
     *
     * <p>없으면 던진다. {@code TodayData} 는 모든 세이브에 반드시 있으므로,
     * 못 찾았다는 것은 "날짜를 모른다" 가 아니라 <b>스트림 구조가 어긋났다</b> 는 뜻이다.
     * null 로 넘기면 스크림 시점 배정(D8)이 통째로 조용히 실패한다.
     */
    static ParsedToday readToday(NrbfParser parser) {
        for (NrbfObject t : walk(parser, "TodayData")) {
            Object time = get(t, "Time");
            if (time instanceof NrbfObject) {
                return new ParsedToday(intOf(time, "Season"), intOf(time, "Day"), intOf(time, "Run"));
            }
        }
        throw new IllegalStateException(
                "TodayData 를 찾지 못했다. 세이브 파일 구조가 예상과 다르다 — "
                        + "이 값 없이는 스크림의 시점을 정할 수 없다 (D18)");
    }

    // ------------------------------------------------------------------ 공통

    private static List<NrbfObject> walk(NrbfParser parser, String className) {
        List<NrbfObject> out = new ArrayList<>();
        for (Object v : parser.objects().values()) {
            if (v instanceof NrbfObject obj && obj.className().equals(className)) {
                out.add(obj);
            }
        }
        return out;
    }
}
