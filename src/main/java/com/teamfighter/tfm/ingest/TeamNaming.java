package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.parser.common.ParsedTeamInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 팀 번호에 이름을 붙이는 규칙 하나. <b>순수 함수다 — DB 를 모른다</b> (D56).
 *
 * <p>출처가 셋이고 우선순위가 있다.
 *
 * <ol>
 *   <li><b>세이브의 {@code TeamInfo}</b> — 커리어 시점의 신원이다. 1순위인 이유가 이것이다.
 *       {@code UseKey} 면 이름이 직접 들어 있고, 아니면 로컬라이제이션 키다</li>
 *   <li><b>시드</b> — 키를 표시 이름으로 바꾼다. 게임 에셋에 표시 문자열이 없어 손으로 넣었다</li>
 *   <li><b>{@code common.data}</b> — 세이브에 {@code TeamInfo} 가 없을 때만 쓰는 폴백.
 *       <b>지금</b> 프로필의 로스터라 커리어와 어긋날 수 있어 뒤로 뺐다 (D55 를 D56 이 고쳤다)</li>
 * </ol>
 *
 * <p><b>키는 알지만 이름을 모를 수 있다.</b> 시드에 없는 키가 그렇다. 그때는 이름 없이
 * 키만 저장한다 — 나중에 시드를 채우면 그 행만 고치면 된다. 모르는 것을 키 문자열
 * 그대로 화면에 내보내면 {@code team.name.pro.team8} 이 팀 이름처럼 보인다.
 */
final class TeamNaming {

    /**
     * @param display 화면에 쓸 이름. 모르면 {@code null}
     * @param nameKey 세이브가 말한 로컬라이제이션 키. 커스텀 이름이면 {@code null}
     */
    record Name(String display, String nameKey) {

        boolean isEmpty() {
            return display == null && nameKey == null;
        }
    }

    private static final TeamNaming EMPTY = new TeamNaming(Map.of(), Map.of());

    private final Map<Integer, ParsedTeamInfo> byId;
    private final Map<String, String> seed;

    private TeamNaming(Map<Integer, ParsedTeamInfo> byId, Map<String, String> seed) {
        this.byId = byId;
        this.seed = seed;
    }

    /** 아무것도 모르는 이름표. 이름 없이 번호만 적재된다. */
    static TeamNaming empty() {
        return EMPTY;
    }

    /**
     * @param teamInfos 세이브에서 읽은 팀 목록. 비어 있으면 아무 이름도 못 붙인다
     * @param seed      {@code 로컬라이제이션 키 → 표시 이름}
     */
    static TeamNaming of(List<ParsedTeamInfo> teamInfos, Map<String, String> seed) {
        Map<Integer, ParsedTeamInfo> byId = new HashMap<>();
        if (teamInfos != null) {
            for (ParsedTeamInfo info : teamInfos) {
                if (info.id() != null) {
                    byId.put(info.id(), info);
                }
            }
        }
        return new TeamNaming(byId, seed == null ? Map.of() : seed);
    }

    /** 번호로 이름을 찾는다. 아무 출처에도 없으면 비어 있다. */
    Optional<Name> nameOf(Integer gameTeamId) {
        if (gameTeamId == null) {
            return Optional.empty();
        }
        ParsedTeamInfo info = byId.get(gameTeamId);
        if (info != null) {
            String literal = info.literalName();
            if (literal != null) {
                return Optional.of(new Name(literal, null));
            }
            String key = info.localizationKey();
            if (key != null) {
                // 시드에 없으면 이름은 비우고 키만 남긴다. 키를 이름 자리에 넣지 않는다.
                return Optional.of(new Name(blankToNull(seed.get(key)), key));
            }
        }
        // 세이브가 이 번호를 모른다. 추측하지 않는다 — 번호만으로 적재된다.
        return Optional.empty();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
