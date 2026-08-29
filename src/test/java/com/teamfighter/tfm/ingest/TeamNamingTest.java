package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.parser.common.ParsedTeamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeamNaming} 의 계약을 고정한다. DB 도 파일도 안 쓴다 — 규칙만 있다.
 *
 * <p>여기서 지키는 것은 <b>출처의 우선순위</b>다. 세이브가 커리어 시점의 사실이고,
 * {@code common.data} 는 지금 프로필이라 어긋날 수 있다 (D56 이 D55 를 고쳤다).
 */
class TeamNamingTest {

    private static final ParsedTeamInfo PRO8 =
            new ParsedTeamInfo(35, "team.name.pro.team8", false);
    private static final ParsedTeamInfo PLAYER =
            new ParsedTeamInfo(0, "Ember scale", true);

    private static final Map<String, String> SEED =
            Map.of("team.name.pro.team8", "KT Rolster Bullets");

    @Test
    @DisplayName("UseKey 면 NameKey 가 이름 그 자체다 — 키로 취급하면 내 팀 이름이 사라진다")
    void literalName_whenUseKey() {
        TeamNaming naming = TeamNaming.of(List.of(PLAYER), SEED);

        TeamNaming.Name name = naming.nameOf(0).orElseThrow();
        assertThat(name.display()).isEqualTo("Ember scale");
        assertThat(name.nameKey()).isNull();
    }

    @Test
    @DisplayName("UseKey 가 아니면 시드로 해석하고, 키도 함께 남긴다")
    void resolvesLocalizationKey_andKeepsTheKey() {
        TeamNaming naming = TeamNaming.of(List.of(PRO8), SEED);

        TeamNaming.Name name = naming.nameOf(35).orElseThrow();
        assertThat(name.display()).isEqualTo("KT Rolster Bullets");
        // 키를 안 남기면 시드가 틀렸을 때 무엇을 고쳐야 하는지 알 수 없다.
        assertThat(name.nameKey()).isEqualTo("team.name.pro.team8");
    }

    @Test
    @DisplayName("시드에 없는 키는 이름을 비우고 키만 남긴다 — 키를 이름 자리에 넣지 않는다")
    void unknownKey_keepsKeyButLeavesNameEmpty() {
        TeamNaming naming = TeamNaming.of(List.of(PRO8), Map.of());

        TeamNaming.Name name = naming.nameOf(35).orElseThrow();
        // 변조: display 에 키를 그대로 넣으면 화면에 team.name.pro.team8 이 팀 이름처럼 뜬다.
        assertThat(name.display()).isNull();
        assertThat(name.nameKey()).isEqualTo("team.name.pro.team8");
    }

    @Test
    @DisplayName("세이브가 모르는 번호는 이름을 붙이지 않는다 — 추측하지 않는다")
    void unknownTeamId_getsNoName() {
        TeamNaming naming = TeamNaming.of(List.of(PRO8), SEED);

        // common.data 를 폴백으로 두었다가 뺐다 (D57). 그 딕셔너리의 키가 game_team_id 와
        // 맞는다는 근거가 없다 — 실측은 반대를 가리킨다(키 1 = OKSavingsBank BRION,
        // 세이브의 1번 = team.name.amateur.team1). 틀린 이름은 없는 이름보다 나쁘다.
        assertThat(naming.nameOf(41)).isEmpty();
    }

    @Test
    @DisplayName("아무 출처에도 없으면 비어 있다 — 번호만으로 적재된다")
    void empty_whenNothingKnows() {
        assertThat(TeamNaming.of(List.of(PRO8), SEED).nameOf(99)).isEmpty();
        assertThat(TeamNaming.of(List.of(PRO8), SEED).nameOf(null)).isEmpty();
        assertThat(TeamNaming.empty().nameOf(35)).isEmpty();
    }
}
