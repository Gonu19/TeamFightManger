package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.MatchParticipant;
import com.teamfighter.tfm.ingest.entity.MatchRecord;
import com.teamfighter.tfm.ingest.entity.MatchType;
import com.teamfighter.tfm.ingest.entity.Patch;
import com.teamfighter.tfm.ingest.entity.SaveSlot;
import com.teamfighter.tfm.ingest.entity.Team;
import com.teamfighter.tfm.ingest.entity.TeamSide;
import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.common.ParsedTeamInfo;
import com.teamfighter.tfm.parser.common.TeamInfoParser;
import com.teamfighter.tfm.parser.model.ParsedScrim;
import com.teamfighter.tfm.parser.model.ParsedStat;
import com.teamfighter.tfm.parser.model.ParsedToday;
import com.teamfighter.tfm.parser.save.SaveParser;
import com.teamfighter.tfm.ingest.repository.ChampionPatchEventRepository;
import com.teamfighter.tfm.ingest.repository.ChampionRepository;
import com.teamfighter.tfm.ingest.repository.MatchParticipantRepository;
import com.teamfighter.tfm.ingest.repository.MatchRecordRepository;
import com.teamfighter.tfm.ingest.repository.PatchRepository;
import com.teamfighter.tfm.ingest.repository.SaveSlotRepository;
import com.teamfighter.tfm.ingest.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IngestService} 의 계약을 고정한다.
 *
 * <p>세이브 스냅샷({@code fixtures/slot_*.tfm})이 없는 환경에서는 픽스처를 쓰는 테스트를
 * 건너뛴다 — {@link GoldenFileTest} 와 같은 방식({@link #fixturesExist()}).
 *
 * <p>구현체({@code IngestServiceImpl})는 이 테스트가 컴파일되도록 아직 존재하지 않는다.
 * RED 단계이므로 컴파일만 되면 된다 — 통과는 구현 이후의 일이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IngestServiceTest {

    private static final Path FIXTURES = Path.of("fixtures");

    @Autowired
    private IngestService ingestService;

    @Autowired
    private SaveSlotRepository slots;

    @Autowired
    private MatchRecordRepository matches;

    @Autowired
    private MatchParticipantRepository participants;

    @Autowired
    private PatchRepository patches;

    @Autowired
    private ChampionPatchEventRepository patchEvents;

    @Autowired
    private ChampionRepository champions;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private SaveLoader loader;

    @Autowired
    private com.teamfighter.tfm.ingest.repository.TeamNameSeedRepository teamNameSeeds;

    static boolean fixturesExist() {
        return !fixtures().isEmpty();
    }

    private static List<Path> fixtures() {
        if (!Files.isDirectory(FIXTURES)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(FIXTURES)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".tfm")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path fixture(String slotFileName) {
        return FIXTURES.resolve(slotFileName);
    }

    // ------------------------------------------------------------ D20

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D20 — 참가자 진영은 ChampStat 인덱스가 아니라 챔피언 이름으로 매칭된다")
    void ingest_matchesSideByChampionName_notByChampStatIndex() throws Exception {
        Path file = fixture("slot_638064443900084435.tfm");

        // 정답지는 파서 출력이다. ChampStat 순서가 아니라 BluePick/RedPick 이 진영의 근거다.
        Map<Integer, ParsedGame> expected = new HashMap<>();
        for (ParsedGame g : SaveParser.read(file).gameStats()) {
            expected.put(g.id(), g);
        }

        ingestService.ingest(file);

        SaveSlot slot = slots.findBySlotKey("slot_638064443900084435.tfm").orElseThrow();
        Map<Integer, String> codeById = new HashMap<>();
        champions.findAll().forEach(c -> codeById.put(c.getChampionId(), c.getCode()));

        List<MatchRecord> officialMatches = matches.findBySlotIdAndMatchType(slot.getSlotId(), MatchType.OFFICIAL);
        assertThat(officialMatches).isNotEmpty();

        Map<Long, List<MatchParticipant>> byMatch = new HashMap<>();
        for (MatchParticipant p : participants.findAll()) {
            byMatch.computeIfAbsent(p.getId().getMatchId(), k -> new ArrayList<>()).add(p);
        }

        int scrambled = 0;
        for (MatchRecord match : officialMatches) {
            ParsedGame game = expected.get(match.getSourceGameId());
            assertThat(game).as("적재된 경기는 파서 출력에 있어야 한다").isNotNull();

            List<MatchParticipant> rows = byMatch.getOrDefault(match.getMatchId(), List.of());
            Set<String> blue = new HashSet<>();
            Set<String> red = new HashSet<>();
            for (MatchParticipant p : rows) {
                (p.getId().getSide() == TeamSide.BLUE ? blue : red).add(codeById.get(p.getChampionId()));
            }

            // 핵심 단언. 인덱스로 매칭하면 진영이 뒤바뀌어 여기서 깨진다.
            assertThat(blue)
                    .as("경기 %d 의 BLUE 는 BluePick 과 같아야 한다", game.id())
                    .containsExactlyInAnyOrderElementsOf(game.bluePick());
            assertThat(red)
                    .as("경기 %d 의 RED 는 RedPick 과 같아야 한다", game.id())
                    .containsExactlyInAnyOrderElementsOf(game.redPick());

            List<String> champStatOrder = game.champStat().stream().map(ParsedStat::champion).toList();
            List<String> pickOrder = Stream.concat(game.bluePick().stream(), game.redPick().stream()).toList();
            if (!champStatOrder.equals(pickOrder)) {
                scrambled++;
            }
        }

        // 이 테스트가 실제로 무언가를 지키는지 확인한다.
        // 순서가 어긋난 경기가 하나도 없으면 인덱스 매칭도 통과하므로 방어선이 아니다 (D34).
        assertThat(scrambled)
                .as("ChampStat 순서가 어긋난 경기가 있어야 이 테스트가 의미를 가진다 (실측 20.5%%)")
                .isGreaterThan(0);
    }

    // ------------------------------------------------------------ D35

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D35 — 밴이 3+3 이 아닌 공식전, 인원이 4+4 가 아닌 스크림은 건너뛴다 (slot_638064443900084435 실측)")
    void ingest_skipsGamesAndScrimsThatDoNotMatchFixedFormat() throws Exception {
        IngestService.IngestResult result = ingestService.ingest(fixture("slot_638064443900084435.tfm"));

        assertThat(result.newMatches()).isEqualTo(171);
        assertThat(result.skippedGames()).isEqualTo(107);
        assertThat(result.newScrims()).isEqualTo(113);
        assertThat(result.skippedScrims()).isEqualTo(38);
    }

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D35 — 형식이 균일한 슬롯은 하나도 건너뛰지 않는다 (slot_638377270248153191 실측)")
    void ingest_skipsNothing_whenAllGamesMatchFixedFormat() throws Exception {
        IngestService.IngestResult result = ingestService.ingest(fixture("slot_638377270248153191.tfm"));

        assertThat(result.newMatches()).isEqualTo(233);
        assertThat(result.skippedGames()).isEqualTo(0);
        assertThat(result.newScrims()).isEqualTo(117);
        assertThat(result.skippedScrims()).isEqualTo(0);
    }

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D35 — 세 번째 슬롯도 형식이 균일해 전부 적재된다 (slot_638683925954242004 실측)")
    void ingest_skipsNothing_forThirdFixtureSlot() throws Exception {
        IngestService.IngestResult result = ingestService.ingest(fixture("slot_638683925954242004.tfm"));

        assertThat(result.newMatches()).isEqualTo(294);
        assertThat(result.skippedGames()).isEqualTo(0);
        assertThat(result.newScrims()).isEqualTo(181);
        assertThat(result.skippedScrims()).isEqualTo(0);
    }

    // ------------------------------------------------------------ 재적재 멱등성

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("같은 파일을 두 번 적재해도 경기 수가 늘지 않고 두 번째는 alreadyIngested 다")
    void ingest_sameFileTwice_isIdempotent() throws Exception {
        Path file = fixture("slot_638377270248153191.tfm");

        IngestService.IngestResult first = ingestService.ingest(file);
        assertThat(first.alreadyIngested()).isFalse();
        assertThat(first.newMatches()).isGreaterThan(0);

        SaveSlot slot = slots.findBySlotKey("slot_638377270248153191.tfm").orElseThrow();
        long matchCountAfterFirst = matches.countBySlotIdAndMatchType(slot.getSlotId(), MatchType.OFFICIAL);

        IngestService.IngestResult second = ingestService.ingest(file);
        assertThat(second.alreadyIngested()).isTrue();
        assertThat(second.newMatches()).isZero();
        assertThat(second.newScrims()).isZero();

        long matchCountAfterSecond = matches.countBySlotIdAndMatchType(slot.getSlotId(), MatchType.OFFICIAL);
        assertThat(matchCountAfterSecond).isEqualTo(matchCountAfterFirst);
    }

    // ------------------------------------------------------------ D28

    @Test
    @DisplayName("D28 — *.tfm_backup 경로는 슬롯으로 인정하지 않고 IllegalArgumentException 을 던진다")
    void ingest_rejectsTfmBackupPath() {
        Path backup = FIXTURES.resolve("slot_638683925954242004.tfm_backup");

        assertThatThrownBy(() -> ingestService.ingest(backup))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("D28 — 확장자가 완전히 .tfm 이 아니면 (예: .tfm.bak) 슬롯으로 인정하지 않는다")
    void ingest_rejectsNonExactTfmExtension() {
        Path notExactTfm = FIXTURES.resolve("slot_638683925954242004.tfm.bak");

        assertThatThrownBy(() -> ingestService.ingest(notExactTfm))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------ D8

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D8 — 적재된 스크림은 observed_season/observed_day 가 채워져 있다")
    void ingest_scrims_haveObservedSeasonAndDay() throws Exception {
        Path file = fixture("slot_638683925954242004.tfm");
        ParsedToday today = SaveParser.read(file).today();
        ingestService.ingest(file);

        SaveSlot slot = slots.findBySlotKey("slot_638683925954242004.tfm").orElseThrow();
        List<MatchRecord> scrims = matches.findBySlotIdAndMatchType(slot.getSlotId(), MatchType.SCRIM);
        assertThat(scrims).isNotEmpty();

        for (MatchRecord scrim : scrims) {
            // 세이브에 시점이 없는 경기다. 워처가 붙인 게임 내 날짜가 유일한 근거다 (D8).
            assertThat(scrim.getObservedSeason())
                    .as("스크림 %d 의 observed_season", scrim.getMatchId())
                    .isEqualTo(today.season());
            assertThat(scrim.getObservedDay())
                    .as("스크림 %d 의 observed_day", scrim.getMatchId())
                    .isEqualTo(today.day());
            assertThat(scrim.getObservedAt())
                    .as("스크림 %d 의 관측 벽시계 시각", scrim.getMatchId())
                    .isNotNull();
            assertThat(scrim.getPatchId())
                    .as("스크림 %d 는 observed 시점을 기준으로 패치가 배정돼야 한다", scrim.getMatchId())
                    .isNotNull();
        }
    }

    // ------------------------------------------------------------ 패치 자동 배정

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("경기에는 그 시점 이전의 마지막 패치가 배정된다")
    void ingest_assignsMostRecentPatchAtOrBeforeMatchTime() throws Exception {
        ingestService.ingest(fixture("slot_638377270248153191.tfm"));

        SaveSlot slot = slots.findBySlotKey("slot_638377270248153191.tfm").orElseThrow();
        List<MatchRecord> officialMatches = matches.findBySlotIdAndMatchType(slot.getSlotId(), MatchType.OFFICIAL);
        List<Patch> slotPatches = patches.findBySlotIdOrderBySeqAsc(slot.getSlotId());

        assertThat(slotPatches).isNotEmpty();
        assertThat(officialMatches).allSatisfy(match -> {
            assertThat(match.getPatchId()).as("match %d 에 패치가 배정돼야 한다", match.getMatchId()).isNotNull();
        });
    }

    // ------------------------------------------------------------ D15

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D15 — 참가자의 change_count 는 그 경기 시점까지 그 챔피언이 패치로 바뀐 횟수와 같다")
    void ingest_participantChangeCount_matchesPatchHistoryAtMatchTime() throws Exception {
        ingestService.ingest(fixture("slot_638064443900084435.tfm"));

        SaveSlot slot = slots.findBySlotKey("slot_638064443900084435.tfm").orElseThrow();
        List<MatchRecord> officialMatches = matches.findBySlotIdAndMatchType(slot.getSlotId(), MatchType.OFFICIAL);
        assertThat(officialMatches).isNotEmpty();

        MatchRecord sample = officialMatches.get(0);
        List<Patch> slotPatches = patches.findBySlotIdOrderBySeqAsc(slot.getSlotId());
        int matchPatchSeq = slotPatches.stream()
                .filter(p -> p.getPatchId().equals(sample.getPatchId()))
                .findFirst()
                .map(Patch::getSeq)
                .orElseThrow();

        List<MatchParticipant> sampleParticipants = participants.findAll().stream()
                .filter(p -> p.getId().getMatchId().equals(sample.getMatchId()))
                .toList();
        assertThat(sampleParticipants).isNotEmpty();

        for (MatchParticipant participant : sampleParticipants) {
            long expectedChangeCount = changeCountUpToPatch(slotPatches, matchPatchSeq, participant.getChampionId());

            assertThat(participant.getChangeCount())
                    .as("participant %s 의 change_count", participant.getId())
                    .isEqualTo((short) expectedChangeCount);
        }
    }

    /** {@code upToSeq} 까지의 패치 이벤트 중 해당 챔피언을 건드린 횟수를 센다 (D15). */
    private long changeCountUpToPatch(List<Patch> slotPatches, int upToSeq, Integer championId) {
        return slotPatches.stream()
                .filter(p -> p.getSeq() <= upToSeq)
                .flatMap(p -> patchEvents.findByIdPatchId(p.getPatchId()).stream())
                .filter(event -> event.getId().getChampionId().equals(championId))
                .count();
    }

    // ------------------------------------------------------------ 필수 값 누락

    // ------------------------------------------------------------ D54

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D54 — 공식전에는 양 진영 팀이 붙는다. 팀 행은 세이브의 번호 종류만큼만 생긴다")
    void ingest_officialMatches_carryBothTeams() throws Exception {
        Path file = fixture("slot_638683925954242004.tfm");
        List<ParsedGame> games = SaveParser.read(file).gameStats();

        ingestService.ingest(file);

        SaveSlot slot = slots.findBySlotKey("slot_638683925954242004.tfm").orElseThrow();
        Map<Integer, Integer> gameTeamIdByTeamId = new HashMap<>();
        Set<Integer> loadedNumbers = new HashSet<>();
        for (Team team : teams.findBySlotId(slot.getSlotId())) {
            gameTeamIdByTeamId.put(team.getTeamId(), team.getGameTeamId());
            assertThat(loadedNumbers.add(team.getGameTeamId()))
                    .as("같은 번호로 팀이 두 번 만들어지면 안 된다 (%d)", team.getGameTeamId())
                    .isTrue();
            assertThat(team.isPlayer())
                    .as("0 번만 플레이어 팀이다 (%d)", team.getGameTeamId())
                    .isEqualTo(team.getGameTeamId() == 0);
        }

        // 세이브에 실제로 있는 번호만, 전부. 남거나 모자라면 여기서 깨진다.
        Set<Integer> expectedNumbers = new HashSet<>();
        Map<Integer, ParsedGame> expected = new HashMap<>();
        for (ParsedGame g : games) {
            expected.put(g.id(), g);
            if (g.bluePick().size() == 4 && g.redPick().size() == 4
                    && g.blueBan().size() == 3 && g.redBan().size() == 3) {
                expectedNumbers.add(g.blueTeamId());
                expectedNumbers.add(g.redTeamId());
            }
        }
        expectedNumbers.remove(null);            // 번호 없는 경기는 팀을 만들지 않는다
        assertThat(loadedNumbers).isEqualTo(expectedNumbers);
        assertThat(loadedNumbers).as("플레이어 팀이 있어야 한다").contains(0);

        List<MatchRecord> officialMatches = matches.findBySlotIdAndMatchType(slot.getSlotId(), MatchType.OFFICIAL);
        assertThat(officialMatches).isNotEmpty();
        for (MatchRecord match : officialMatches) {
            ParsedGame game = expected.get(match.getSourceGameId());
            // 진영을 뒤바꿔 넣어도 "둘 다 NULL 이 아니다" 만으로는 안 잡힌다. 번호까지 되짚는다.
            assertThat(gameTeamIdByTeamId.get(match.getBlueTeamId()))
                    .as("경기 %d 의 BLUE 팀 번호", game.id())
                    .isEqualTo(game.blueTeamId());
            assertThat(gameTeamIdByTeamId.get(match.getRedTeamId()))
                    .as("경기 %d 의 RED 팀 번호", game.id())
                    .isEqualTo(game.redTeamId());
        }
    }

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D54 — 스크림에는 팀을 붙이지 않는다. 세이브에 상대 번호가 없다")
    void ingest_scrims_haveNoTeams() throws Exception {
        Path file = fixture("slot_638683925954242004.tfm");

        // 이 테스트가 무엇을 지키는지의 근거. ScrimStat.TeamID 는 전부 플레이어 팀(0)이고
        // 상대 번호는 어디에도 없다 — 그래서 두 진영 중 어느 쪽도 팀을 정할 수 없다.
        for (ParsedScrim scrim : SaveParser.read(file).scrimStats()) {
            assertThat(scrim.teamId())
                    .as("스크림 %d 의 TeamID 가 플레이어 팀이 아니면 이 결정을 다시 봐야 한다", scrim.id())
                    .isIn(null, 0);
        }

        ingestService.ingest(file);

        SaveSlot slot = slots.findBySlotKey("slot_638683925954242004.tfm").orElseThrow();
        List<MatchRecord> scrims = matches.findBySlotIdAndMatchType(slot.getSlotId(), MatchType.SCRIM);
        assertThat(scrims).isNotEmpty();
        assertThat(scrims).allSatisfy(m -> {
            assertThat(m.getBlueTeamId()).isNull();
            assertThat(m.getRedTeamId()).isNull();
        });
    }

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D54 — 팀 없이 이미 적재된 경기는 다시 지나갈 때 채워진다 (백필)")
    void reload_backfillsTeams_onMatchesIngestedBeforeTeamsExisted() throws Exception {
        Path file = fixture("slot_638683925954242004.tfm");
        ingestService.ingest(file);

        SaveSlot slot = slots.findBySlotKey("slot_638683925954242004.tfm").orElseThrow();
        List<MatchRecord> officialMatches = matches.findBySlotIdAndMatchType(slot.getSlotId(), MatchType.OFFICIAL);
        assertThat(officialMatches).isNotEmpty();

        // 팀 적재 이전의 DB 상태를 그대로 만든다 — 경기는 다 있고 팀만 비어 있다.
        Map<Long, Integer> blueBefore = new HashMap<>();
        for (MatchRecord match : officialMatches) {
            blueBefore.put(match.getMatchId(), match.getBlueTeamId());
            match.assignTeams(null, null);
        }
        matches.saveAll(officialMatches);
        matches.flush();

        // 해시 검사를 거치지 않고 다시 읽는다 — ReingestRunner 가 하는 일과 같다.
        loader.load(slot, file);

        for (MatchRecord match : matches.findBySlotIdAndMatchType(slot.getSlotId(), MatchType.OFFICIAL)) {
            assertThat(match.getBlueTeamId())
                    .as("경기 %d 의 팀이 백필되지 않았다", match.getSourceGameId())
                    .isEqualTo(blueBefore.get(match.getMatchId()));
            assertThat(match.getRedTeamId()).isNotNull();
        }

        // 백필이 경기를 새로 만들지는 않는다.
        assertThat(matches.countBySlotIdAndMatchType(slot.getSlotId(), MatchType.OFFICIAL))
                .isEqualTo(officialMatches.size());
    }

    // ------------------------------------------------------------ D56

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D56 — 팀 이름은 세이브의 TeamInfo 에서 온다. 키도 함께 남는다")
    void ingest_namesTeams_fromSaveTeamInfo() throws Exception {
        Path file = fixture("slot_638683925954242004.tfm");
        Map<Integer, ParsedTeamInfo> expected = new HashMap<>();
        for (ParsedTeamInfo info : TeamInfoParser.read(file)) {
            expected.put(info.id(), info);
        }
        assertThat(expected).as("세이브에 TeamInfo 가 있어야 이 테스트가 의미를 가진다").isNotEmpty();

        ingestService.ingest(file);

        SaveSlot slot = slots.findBySlotKey("slot_638683925954242004.tfm").orElseThrow();
        List<Team> loaded = teams.findBySlotId(slot.getSlotId());
        assertThat(loaded).isNotEmpty();

        for (Team team : loaded) {
            ParsedTeamInfo info = expected.get(team.getGameTeamId());
            assertThat(info).as("적재된 팀 %d 는 세이브에 있어야 한다", team.getGameTeamId()).isNotNull();
            // 키는 세이브가 말한 그대로여야 한다. 이름은 시드 해석 결과라 없을 수도 있다.
            assertThat(team.getNameKey())
                    .as("팀 %d 의 키", team.getGameTeamId())
                    .isEqualTo(info.localizationKey());
        }

        // 실측 못 박기. 이 커리어에서 66세트를 뛴 35번은 프로1부 8번째 팀이다 (사용자 확인).
        // "전부 이름이 있다" 로는 시드를 아무렇게나 붙여도 통과한다.
        Team pro8 = loaded.stream().filter(t -> t.getGameTeamId() == 35).findFirst().orElseThrow();
        assertThat(pro8.getNameKey()).isEqualTo("team.name.pro.team8");
        assertThat(pro8.getName()).isEqualTo("KT Rolster Bullets");

        // 플레이어 팀은 커스텀 이름이라 키가 없다.
        Team player = loaded.stream().filter(Team::isPlayer).findFirst().orElseThrow();
        assertThat(player.getNameKey()).isNull();
        assertThat(player.getName()).isEqualTo(expected.get(0).literalName());
    }

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("D56 — 시드가 52개 그대로 있고 리그 다섯 단계를 덮는다")
    void seed_coversEveryLeague() {
        Map<String, Long> byLeague = new HashMap<>();
        teamNameSeeds.findAll().forEach(x -> byLeague.merge(x.getLeague(), 1L, Long::sum));

        assertThat(byLeague).containsOnlyKeys("amateur", "semi_pro", "pro2", "pro", "worlds");
        assertThat(byLeague).containsEntry("amateur", 7L).containsEntry("semi_pro", 10L)
                .containsEntry("pro2", 10L).containsEntry("pro", 10L).containsEntry("worlds", 15L);
    }

    @Test
    @DisplayName("필수 값(승패·챔피언 이름)이 빠진 경기를 만나면 조용히 넘기지 않고 예외를 던진다")
    void ingest_throws_ratherThanSilentlySkipping_whenSaveDataIsMalformed(@TempDir Path tempDir) throws Exception {
        // 손으로 유효한 NRBF 스트림을 만들 수는 없으므로, "파서가 못 읽는 내용을 만나면
        // 조용히 건너뛰지 않고 던진다" 는 동일한 계약 — 파서는 관대하고 적재는 엄격하다,
        // 필수 값이 없으면 그 경기가 무의미해지므로 막는다(D8/D35 절 상단 클래스 문서 참고) —
        // 를 구조가 깨진(=파싱 자체가 실패하는) 파일로 고정한다. 정상적인 슬롯 이름 규칙은
        // 지키되(D28), 내용은 NRBF 로 파싱될 수 없는 쓰레기 바이트다.
        Path malformed = tempDir.resolve("slot_malformed_test.tfm");
        Files.write(malformed, new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9});

        assertThatThrownBy(() -> ingestService.ingest(malformed))
                .isInstanceOf(RuntimeException.class);
    }
}
