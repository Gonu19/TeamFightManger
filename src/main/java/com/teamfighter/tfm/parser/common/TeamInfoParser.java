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
 * 세이브에서 팀 신원({@code TeamInfo})을 꺼낸다 (D56).
 *
 * <p><b>{@code SaveParser} 와 나눠 둔 이유.</b> {@code ParsedSave} 는 골든 파일
 * ({@code tests/baseline/*.json})과 바이트 단위로 비교되는 <b>언어 무관 계약</b>이다.
 * 거기에 필드를 더하면 파이썬 레퍼런스({@code tools/save_model.py})와 베이스라인 세 벌을
 * 같이 갈아야 하고, 그러면 "구조 변경" 과 "스냅샷 갱신" 이 한 커밋에 섞인다.
 * 팀 신원은 경기 기록이 아니라 <b>식별자</b>라 관심사도 다르다.
 *
 * <p>비용은 경기 스트림을 한 번 더 읽는 것이다. 적재는 세이브가 바뀔 때만 도는데
 * 그 간격이 분 단위라 문제가 되지 않는다 — 실제 비용은 {@code IngestCostTest} 가 지킨다.
 */
public final class TeamInfoParser {

    /** 경기 데이터가 들어 있는 스트림. {@code SaveParser} 와 같은 값이어야 한다. */
    private static final int MATCH_STREAM = 1;

    private static final String CLASS_NAME = "TeamInfo";

    private TeamInfoParser() {
    }

    /**
     * 팀 목록을 {@code ID} 순으로 읽는다.
     *
     * @throws IllegalArgumentException 세이브 파일이 아니거나 잘렸을 때
     */
    public static List<ParsedTeamInfo> read(Path saveFile) throws IOException {
        byte[] buf = Files.readAllBytes(saveFile);
        List<int[]> streams = NrbfParser.splitStreams(buf);
        if (streams.size() <= MATCH_STREAM) {
            throw new IllegalArgumentException(
                    "NRBF 스트림이 " + streams.size() + "개뿐이다. 세이브 파일이 아니거나 잘렸다: " + saveFile);
        }
        int[] bounds = streams.get(MATCH_STREAM);
        NrbfParser parser = new NrbfParser(buf, bounds[0], bounds[1]).parse();

        // 같은 팀이 여러 곳(경기 일정·선수 소속)에서 참조되므로 ID 로 접는다.
        Map<Integer, ParsedTeamInfo> byId = new LinkedHashMap<>();
        for (Object o : parser.objects().values()) {
            if (!(o instanceof NrbfObject obj) || !CLASS_NAME.equals(obj.className())) {
                continue;
            }
            if (!(obj.get("ID") instanceof Integer id)) {
                continue;                                    // 번호 없는 팀은 경기와 이을 수 없다
            }
            byId.putIfAbsent(id, new ParsedTeamInfo(
                    id,
                    obj.get("NameKey") instanceof String s ? s : null,
                    Boolean.TRUE.equals(obj.get("UseKey"))));
        }

        List<ParsedTeamInfo> teams = new ArrayList<>(byId.values());
        teams.sort((a, b) -> Integer.compare(a.id(), b.id()));
        return List.copyOf(teams);
    }
}
