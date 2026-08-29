package com.teamfighter.tfm.parser.common;

import com.teamfighter.tfm.parser.nrbf.NrbfObject;
import com.teamfighter.tfm.parser.nrbf.NrbfParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 세이브에서 선수({@code Athlete})를 꺼낸다 (D58).
 *
 * <p>{@link TeamInfoParser} 와 같은 이유로 {@code SaveParser} 와 나눠 뒀다 —
 * {@code ParsedSave} 는 골든 파일과 바이트 단위로 비교되는 언어 무관 계약이라
 * 거기에 필드를 더하면 파이썬 레퍼런스와 베이스라인을 같이 갈아야 한다.
 *
 * <p><b>이름은 여기서 풀지 않는다.</b> {@code Athlete.Name} 은 {@code `33} 같은
 * 참조라 실제 이름은 게임 에셋에 있고, 그건 DB 시드({@code athlete_name_seed})가 안다.
 * 파서는 인덱스만 넘긴다 — <b>파서에 DB 를 끌어들이지 않는다.</b>
 */
public final class AthleteParser {

    /** 경기 데이터가 들어 있는 스트림. {@code SaveParser} 와 같은 값이어야 한다. */
    private static final int MATCH_STREAM = 1;

    private static final String CLASS_NAME = "Athlete";

    /** 이름 참조의 접두사. 이 글자로 시작하지 않으면 인덱스가 아니다. */
    private static final char NAME_REFERENCE_PREFIX = '`';

    private AthleteParser() {
    }

    /**
     * 선수 목록을 {@code ID} 순으로 읽는다.
     *
     * @throws IllegalArgumentException 세이브 파일이 아니거나 잘렸을 때
     */
    public static List<ParsedAthlete> read(Path saveFile) throws IOException {
        byte[] buf = Files.readAllBytes(saveFile);
        List<int[]> streams = NrbfParser.splitStreams(buf);
        if (streams.size() <= MATCH_STREAM) {
            throw new IllegalArgumentException(
                    "NRBF 스트림이 " + streams.size() + "개뿐이다. 세이브 파일이 아니거나 잘렸다: " + saveFile);
        }
        int[] bounds = streams.get(MATCH_STREAM);
        NrbfParser parser = new NrbfParser(buf, bounds[0], bounds[1]).parse();

        // 같은 선수가 팀·경기 여러 곳에서 참조된다. ID 로 접는다.
        Map<Integer, ParsedAthlete> byId = new LinkedHashMap<>();
        for (Object o : parser.objects().values()) {
            if (!(o instanceof NrbfObject obj) || !CLASS_NAME.equals(obj.className())) {
                continue;
            }
            if (!(obj.get("ID") instanceof Integer id)) {
                continue;                                    // 번호가 없으면 경기와 이을 수 없다
            }
            byId.putIfAbsent(id, toAthlete(id, obj));
        }

        List<ParsedAthlete> athletes = new ArrayList<>(byId.values());
        athletes.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return List.copyOf(athletes);
    }

    private static ParsedAthlete toAthlete(Integer id, NrbfObject obj) {
        return new ParsedAthlete(
                id,
                nameIndexOf(obj.get("Name")),
                obj.get("Team") instanceof NrbfObject team ? intOf(team.get("ID")) : null,
                intOf(obj.get("Age")),
                intOf(obj.get("Salary")),
                intOf(obj.get("Fan")),
                intOf(obj.get("Condition")),
                intOf(obj.get("Potential")),
                intOf(obj.get("PlayingSeason")),
                enumValue(obj.get("Category")),
                enumValue(obj.get("Belong")),
                career(obj.get("Result")));
    }

    /**
     * {@code `33} → 33. 형식이 다르면 {@code null} 이다.
     *
     * <p>숫자만 있는 이름을 인덱스로 오해하지 않도록 접두사를 반드시 확인한다 —
     * 사용자가 선수 이름을 직접 바꾸면 접두사 없는 문자열이 들어온다.
     */
    public static Integer nameIndexOf(Object name) {
        if (!(name instanceof String s) || s.length() < 2 || s.charAt(0) != NAME_REFERENCE_PREFIX) {
            return null;
        }
        String digits = s.substring(1);
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return null;
            }
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            return null;                                     // 자릿수가 너무 크다. 인덱스가 아니다
        }
    }

    /** 참조가 아닌 이름(사용자가 직접 지은 것)이면 그 문자열. 아니면 {@code null}. */
    public static String literalName(Object name) {
        return name instanceof String s && !s.isBlank() && s.charAt(0) != NAME_REFERENCE_PREFIX ? s : null;
    }

    private static ParsedAthlete.Career career(Object result) {
        if (!(result instanceof NrbfObject r)) {
            return null;
        }
        return new ParsedAthlete.Career(
                intOf(r.get("Set")), intOf(r.get("Kill")), intOf(r.get("Death")), intOf(r.get("Assist")),
                longOf(r.get("Deal")), longOf(r.get("Tank")), longOf(r.get("Heal")));
    }

    /** {@code enum} 은 {@code value__} 를 감싼 객체로 온다. */
    private static Integer enumValue(Object v) {
        return v instanceof NrbfObject o ? intOf(o.get("value__")) : intOf(v);
    }

    private static Integer intOf(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Long longOf(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }
}
