package com.teamfighter.tfm.parser.common;

import com.teamfighter.tfm.parser.nrbf.NrbfObject;
import com.teamfighter.tfm.parser.nrbf.NrbfParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 세이브에서 매치 일정({@code MatchSchedule})을 꺼낸다 — {@code story/} 의 첫 단계.
 *
 * <p><b>왜 따로 두나.</b> {@code TeamInfoParser}·{@code AthleteParser} 와 같은 이유다 —
 * {@code ParsedSave} 는 골든 파일과 바이트 단위로 비교되는 언어 무관 계약이라
 * 거기에 필드를 더하면 파이썬 레퍼런스와 베이스라인 세 벌을 같이 갈아야 한다.
 *
 * <p><b>여기서 꺼내는 것은 경기가 아니라 매치다.</b> 경기(세트)는 {@code SaveParser} 가
 * 이미 읽는다. 이 파서는 그 세트들을 묶는 상위 단위와, 기사가 쓸 대회·라운드·스코어를 준다.
 */
public final class MatchScheduleParser {

    /** 경기 데이터가 들어 있는 스트림. {@code SaveParser} 와 같은 값이어야 한다. */
    private static final int MATCH_STREAM = 1;

    private static final String CLASS_NAME = "MatchSchedule";

    private MatchScheduleParser() {
    }

    /**
     * 매치를 시즌 · 일 · 대회 순으로 읽는다.
     *
     * <p>같은 매치가 여러 곳(대회 일정·플레이오프 대진)에서 참조될 수 있으므로
     * <b>객체 동일성으로</b> 접는다. {@code ID} 로 접으면 대회가 다른 서로 다른 매치가
     * 하나로 뭉개진다 — 실측 190건이 114건이 된다.
     *
     * @throws IllegalArgumentException 세이브 파일이 아니거나 잘렸을 때
     */
    public static List<ParsedSchedule> read(Path saveFile) throws IOException {
        byte[] buf = Files.readAllBytes(saveFile);
        List<int[]> streams = NrbfParser.splitStreams(buf);
        if (streams.size() <= MATCH_STREAM) {
            throw new IllegalArgumentException(
                    "NRBF 스트림이 " + streams.size() + "개뿐이다. 세이브 파일이 아니거나 잘렸다: " + saveFile);
        }
        int[] bounds = streams.get(MATCH_STREAM);
        NrbfParser parser = new NrbfParser(buf, bounds[0], bounds[1]).parse();

        List<ParsedSchedule> schedules = new ArrayList<>();
        for (Object o : parser.objects().values()) {
            if (!(o instanceof NrbfObject obj) || !CLASS_NAME.equals(obj.className())) {
                continue;
            }
            ParsedSchedule parsed = toSchedule(obj);
            if (parsed != null) {
                schedules.add(parsed);
            }
        }

        schedules.sort(Comparator
                .comparing(ParsedSchedule::season, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ParsedSchedule::day, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ParsedSchedule::competitionId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ParsedSchedule::scheduleId, Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(schedules);
    }

    /** 날짜가 없는 매치는 경기와 이을 수 없다 — 조인 키가 서지 않으므로 버린다. */
    private static ParsedSchedule toSchedule(NrbfObject obj) {
        NrbfObject date = child(obj, "Date");
        if (date == null) {
            return null;
        }
        Integer season = intOf(date, "Season");
        Integer day = intOf(date, "Day");
        if (season == null || day == null) {
            return null;
        }

        NrbfObject competition = child(obj, "Competition");
        NrbfObject info = competition == null ? null : child(competition, "Info");

        return new ParsedSchedule(
                intOf(obj, "ID"),
                competition == null ? null : intOf(competition, "ID"),
                info != null && info.get("Name") instanceof String s ? s : null,
                season,
                day,
                intOf(obj, "Round"),
                teamId(obj, "BlueTeam"),
                teamId(obj, "RedTeam"),
                intOrZero(obj, "BlueScore"),
                intOrZero(obj, "RedScore"),
                intOrZero(obj, "BlueKill"),
                intOrZero(obj, "RedKill"),
                intOrZero(obj, "NeedWin"),
                obj.get("Progress") instanceof Number n ? n.doubleValue() : 0.0,
                obj.get("EventMatch") != null);
    }

    private static Integer teamId(NrbfObject obj, String member) {
        NrbfObject team = child(obj, member);
        return team == null ? null : intOf(team, "ID");
    }

    private static NrbfObject child(NrbfObject obj, String member) {
        return obj.get(member) instanceof NrbfObject c ? c : null;
    }

    private static Integer intOf(NrbfObject obj, String member) {
        return obj.get(member) instanceof Integer i ? i : null;
    }

    private static int intOrZero(NrbfObject obj, String member) {
        Integer v = intOf(obj, member);
        return v == null ? 0 : v;
    }
}
