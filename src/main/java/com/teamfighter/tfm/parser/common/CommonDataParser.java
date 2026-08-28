package com.teamfighter.tfm.parser.common;

import com.teamfighter.tfm.parser.nrbf.NrbfObject;
import com.teamfighter.tfm.parser.nrbf.NrbfParser;
import com.teamfighter.tfm.parser.save.SaveValues;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code common.data} 에서 팀 이름표를 꺼낸다.
 *
 * <p>세이브 파일에는 <b>팀 이름이 없다.</b> 경기에는 번호만 들어 있고, 이름은 세이브 폴더의
 * {@code common.data} 에 따로 산다. 형식은 세이브와 같은 NRBF 라 파서를 그대로 쓴다.
 *
 * <pre>
 * CommonStore
 *  ├ TeamName   : String              ← 플레이어 팀 (번호 0)
 *  ├ CoachName  : String
 *  └ AITeamData : AITeamListData
 *      ├ Name : String                ← 프로필 이름
 *      └ Data : Dictionary&lt;int, AITeamData&gt;
 *          └ KeyValuePairs[] { key, value }
 *              └ value : AITeamData { Name, Logo }
 * </pre>
 *
 * <p><b>딕셔너리의 키가 곧 {@code game_team_id} 다.</b> 그래서 경기의
 * {@code BlueTeamID}/{@code RedTeamID} 와 바로 붙는다.
 *
 * <p>이 파일은 읽기만 한다. 세이브와 같은 규칙이다.
 */
public final class CommonDataParser {

    /** 세이브 폴더 안의 파일 이름. 슬롯 파일들과 같은 폴더에 있다. */
    public static final String FILE_NAME = "common.data";

    private CommonDataParser() {
    }

    /** {@code saveDir} 안의 {@code common.data} 경로. */
    public static Path pathIn(Path saveDir) {
        return saveDir.resolve(FILE_NAME);
    }

    /**
     * 팀 이름표를 읽는다.
     *
     * @throws IllegalArgumentException NRBF 스트림이 아닐 때 (파일이 아니거나 잘렸다)
     * @throws IllegalStateException    구조가 예상과 다를 때. 조용히 빈 이름표를 내지 않는다 —
     *                                  게임이 형식을 바꾸면 이름이 통째로 사라지는데,
     *                                  그건 "팀 이름이 원래 없는 것" 과 화면에서 구별되지 않는다
     */
    public static ParsedRoster read(Path file) throws IOException {
        byte[] buf = Files.readAllBytes(file);
        List<int[]> streams = NrbfParser.splitStreams(buf);
        if (streams.isEmpty()) {
            throw new IllegalArgumentException(
                    "NRBF 스트림이 없다. common.data 가 아니거나 잘렸다: " + file);
        }
        int[] bounds = streams.get(0);
        Object root = new NrbfParser(buf, bounds[0], bounds[1]).parse().root();

        if (!(root instanceof NrbfObject store)) {
            throw new IllegalStateException("common.data 의 최상위가 객체가 아니다: " + root);
        }
        if (!(store.get("AITeamData") instanceof NrbfObject roster)) {
            throw new IllegalStateException(
                    "common.data 에 AITeamData 가 없다. 게임이 형식을 바꿨을 수 있다: "
                            + store.members().keySet());
        }

        return new ParsedRoster(
                text(roster.get("Name")),
                text(store.get("TeamName")),
                text(store.get("CoachName")),
                aiTeamNames(roster.get("Data")));
    }

    /** {@code Dictionary<int, AITeamData>} 를 {@code 번호 → 이름} 으로 편다. */
    private static Map<Integer, String> aiTeamNames(Object data) {
        if (!(data instanceof NrbfObject dict)) {
            throw new IllegalStateException("AITeamData.Data 가 딕셔너리가 아니다: " + data);
        }
        // KeyValuePairs 는 배열이 아니라 리스트로 온다. SaveValues.list 가 두 모양을 다 받는다.
        List<Object> pairs = SaveValues.list(dict.get("KeyValuePairs"));
        if (pairs.isEmpty()) {
            throw new IllegalStateException(
                    "딕셔너리에 항목이 없다. 게임이 형식을 바꿨을 수 있다: " + dict.members().keySet());
        }
        Map<Integer, String> names = new LinkedHashMap<>();
        for (Object pair : pairs) {
            if (!(pair instanceof NrbfObject kv)) {
                continue;                                  // 빈 버킷. 딕셔너리는 용량만큼 자리를 잡는다
            }
            if (!(kv.get("key") instanceof Integer id) || !(kv.get("value") instanceof NrbfObject team)) {
                continue;
            }
            String name = text(team.get("Name"));
            if (name != null && !name.isBlank()) {
                names.put(id, name);
            }
        }
        return names;
    }

    private static String text(Object v) {
        return v instanceof String s ? s : null;
    }
}
