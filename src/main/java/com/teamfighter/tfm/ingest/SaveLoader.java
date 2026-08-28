package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.BanId;
import com.teamfighter.tfm.ingest.entity.Champion;
import com.teamfighter.tfm.ingest.entity.ChampionPatchEvent;
import com.teamfighter.tfm.ingest.entity.MatchBan;
import com.teamfighter.tfm.ingest.entity.MatchParticipant;
import com.teamfighter.tfm.ingest.entity.MatchRecord;
import com.teamfighter.tfm.ingest.entity.MatchType;
import com.teamfighter.tfm.ingest.entity.ParticipantId;
import com.teamfighter.tfm.ingest.entity.Patch;
import com.teamfighter.tfm.ingest.entity.PatchEventId;
import com.teamfighter.tfm.ingest.entity.SaveSlot;
import com.teamfighter.tfm.ingest.entity.TeamSide;
import com.teamfighter.tfm.ingest.repository.ChampionPatchEventRepository;
import com.teamfighter.tfm.ingest.repository.ChampionRepository;
import com.teamfighter.tfm.ingest.repository.MatchBanRepository;
import com.teamfighter.tfm.ingest.repository.MatchParticipantRepository;
import com.teamfighter.tfm.ingest.repository.MatchRecordRepository;
import com.teamfighter.tfm.ingest.repository.PatchRepository;
import com.teamfighter.tfm.ingest.repository.TeamRepository;
import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.model.ParsedPatch;
import com.teamfighter.tfm.parser.model.ParsedSave;
import com.teamfighter.tfm.parser.model.ParsedScrim;
import com.teamfighter.tfm.parser.model.ParsedStat;
import com.teamfighter.tfm.parser.model.ParsedToday;
import com.teamfighter.tfm.parser.save.SaveParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 세이브 파일 하나의 내용을 DB 에 쓴다.
 *
 * <p><b>이 클래스가 트랜잭션 경계다.</b> 파일 하나가 통째로 들어가거나 통째로 안 들어간다.
 * 중간에 실패하면 그 파일의 적재는 없던 일이 된다 — "적재 건수가 파서가 세는 건수와
 * 정확히 일치한다" 는 완료 기준이 부분 적재를 허용하지 않는다.
 *
 * <p>적재 시도의 <b>기록</b>은 이 트랜잭션 밖에 있다({@link IngestRunRecorder}).
 * 같은 트랜잭션에 두면 실패 기록이 롤백과 함께 사라진다.
 *
 * <p>규칙은 하나로 요약된다 — <b>파서는 관대하고 적재는 엄격하다.</b>
 * 파서는 구 데이터를 읽을 수 있어야 하므로 무엇이든 통과시키지만,
 * 여기서는 {@code 4v4 · 픽 4 · 밴 3} 한 가지 형식만 받는다 (D35).
 */
@Component
public class SaveLoader {

    private static final Logger log = LoggerFactory.getLogger(SaveLoader.class);

    /** 고정 형식. 이 값이 아닌 경기는 적재하지 않는다 (D35). */
    private static final int TEAM_SIZE = 4;
    private static final int BANS_PER_TEAM = 3;

    private final ChampionRepository champions;
    private final PatchRepository patches;
    private final ChampionPatchEventRepository patchEvents;
    private final MatchRecordRepository matches;
    private final MatchParticipantRepository participants;
    private final MatchBanRepository bans;
    private final TeamRepository teams;

    public SaveLoader(ChampionRepository champions,
                      PatchRepository patches,
                      ChampionPatchEventRepository patchEvents,
                      MatchRecordRepository matches,
                      MatchParticipantRepository participants,
                      MatchBanRepository bans,
                      TeamRepository teams) {
        this.champions = champions;
        this.patches = patches;
        this.patchEvents = patchEvents;
        this.matches = matches;
        this.participants = participants;
        this.bans = bans;
        this.teams = teams;
    }

    // ------------------------------------------------------------------ 본체

    @Transactional
    public IngestService.IngestResult load(SaveSlot slot, Path saveFile) {
        ParsedSave save;
        try {
            save = SaveParser.read(saveFile);
        } catch (IOException e) {
            throw new UncheckedIOException("세이브 파일 파싱 실패: " + saveFile, e);
        }

        Map<String, Champion> championByCode = new HashMap<>();
        champions.findAll().forEach(c -> championByCode.put(c.getCode(), c));

        int newPatches = savePatches(slot, save.patches(), championByCode);
        PatchAssigner assigner = buildAssigner(slot);

        // 적재 한 번에 하나. 롤백되면 캐시도 같이 버려져야 한다 (TeamRegistry 참고).
        TeamRegistry teamRegistry = new TeamRegistry(slot.getSlotId(), teams);

        Counts official = saveGames(slot, save.gameStats(), championByCode, assigner, teamRegistry);
        Counts scrim = saveScrims(slot, save.scrimStats(), save.today(), championByCode, assigner);

        log.info("적재 완료 {} — 공식 {}건(제외 {}) · 스크림 {}건(제외 {}) · 패치 {}건 · 팀 백필 {}건",
                slot.getSlotKey(), official.saved, official.skipped, scrim.saved, scrim.skipped,
                newPatches, official.backfilled);

        return new IngestService.IngestResult(slot.getSlotId(), official.saved, scrim.saved,
                official.skipped, scrim.skipped, newPatches, false);
    }

    // ------------------------------------------------------------------ 패치

    private int savePatches(SaveSlot slot, List<ParsedPatch> parsed, Map<String, Champion> byCode) {
        int seq = patches.findBySlotIdOrderBySeqAsc(slot.getSlotId()).size();
        int added = 0;

        for (ParsedPatch p : parsed) {
            if (p.season() == null || p.day() == null) {
                continue;                                        // 시점 없는 패치는 배정에 못 쓴다
            }
            if (patches.findBySlotIdAndSeasonAndDay(slot.getSlotId(), p.season(), p.day()).isPresent()) {
                continue;
            }
            Patch patch = patches.save(new Patch(slot.getSlotId(), p.season(), p.day(), ++seq));
            added++;

            Set<String> newChamps = new HashSet<>(p.newChamps());
            for (ParsedPatch.Change c : p.changes()) {
                Champion champion = byCode.get(c.name());
                if (champion == null) {
                    continue;                                    // 시드에 없는 챔피언은 집계 대상이 아니다
                }
                ChampionPatchEvent event = new ChampionPatchEvent(
                        new PatchEventId(patch.getPatchId(), champion.getChampionId()));
                event.setChanges(zero(c.attack()), zero(c.magic()), zero(c.defence()), zero(c.maxHp()),
                        zero(c.attackSpeed()), zero(c.skillCool()), zero(c.moveSpeed()));
                if (newChamps.contains(c.name())) {
                    event.markNew();
                }
                patchEvents.save(event);
            }
        }
        return added;
    }

    private PatchAssigner buildAssigner(SaveSlot slot) {
        List<Patch> slotPatches = patches.findBySlotIdOrderBySeqAsc(slot.getSlotId());
        Map<Integer, Integer> seqByPatchId = new HashMap<>();
        List<Integer> patchIds = new ArrayList<>(slotPatches.size());
        for (Patch p : slotPatches) {
            seqByPatchId.put(p.getPatchId(), p.getSeq());
            patchIds.add(p.getPatchId());
        }

        // 패치마다 따로 조회하면 패치 수만큼 왕복한다. 한 번에 가져온다.
        Map<Integer, List<Integer>> changeSeq = new HashMap<>();
        for (ChampionPatchEvent e : patchEvents.findByIdPatchIdIn(patchIds)) {
            changeSeq.computeIfAbsent(e.getId().getChampionId(), k -> new ArrayList<>())
                    .add(seqByPatchId.get(e.getId().getPatchId()));
        }
        changeSeq.values().forEach(java.util.Collections::sort);
        return new PatchAssigner(slotPatches, changeSeq);
    }

    // ------------------------------------------------------------------ 공식 경기

    private Counts saveGames(SaveSlot slot, List<ParsedGame> games,
                             Map<String, Champion> byCode, PatchAssigner assigner,
                             TeamRegistry teamRegistry) {
        Map<Integer, MatchRecord> existing = new HashMap<>();
        matches.findBySlotIdAndMatchType(slot.getSlotId(), MatchType.OFFICIAL)
                .forEach(m -> existing.put(m.getSourceGameId(), m));

        Counts counts = new Counts();
        for (ParsedGame g : games) {
            if (!isFixedFormat(g)) {
                counts.skipped++;                                // 픽 4+4 · 밴 3+3 이 아니다 (D35)
                continue;
            }
            if (g.id() == null) {
                continue;
            }
            MatchRecord already = existing.get(g.id());
            if (already != null) {
                if (backfillTeams(already, g, teamRegistry)) {
                    counts.backfilled++;
                }
                continue;
            }

            MatchRecord match;
            try {
                match = new MatchRecord(slot.getSlotId(), MatchType.OFFICIAL, g.id(),
                        TeamSide.ofWinTeam(g.winTeam()));        // 승패가 없으면 여기서 던진다
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "공식 경기 " + g.id() + " (S" + g.season() + " Day" + g.day() + ") 적재 실패: "
                                + e.getMessage(), e);
            }
            match.setSchedule(g.season(), g.day(), g.setNo(), g.scheduleId());
            match.setTeams(teamRegistry.teamIdOf(g.blueTeamId()), teamRegistry.teamIdOf(g.redTeamId()),
                    g.blueScore(), g.redScore());
            match.setFlags(Boolean.TRUE.equals(g.isOvertime()), Boolean.TRUE.equals(g.isSuddenDeath()));
            match.setTeamSize(TEAM_SIZE);

            Patch patch = (g.season() != null && g.day() != null)
                    ? assigner.patchAt(g.season(), g.day()) : null;
            match.assignPatch(patch == null ? null : patch.getPatchId());
            matches.save(match);

            try {
                saveSide(match, TeamSide.BLUE, g.bluePick(), g.champStat(), byCode, assigner, g.season(), g.day());
                saveSide(match, TeamSide.RED, g.redPick(), g.champStat(), byCode, assigner, g.season(), g.day());
                saveBans(match, TeamSide.BLUE, g.blueBan(), byCode);
                saveBans(match, TeamSide.RED, g.redBan(), byCode);
            } catch (RuntimeException e) {
                // 파일 단위로 롤백되므로, 어느 경기에서 넘어졌는지가 유일한 단서다.
                throw new IllegalStateException(
                        "공식 경기 " + g.id() + " (S" + g.season() + " Day" + g.day() + ") 적재 실패: "
                                + e.getMessage(), e);
            }

            counts.saved++;
        }
        return counts;
    }

    /**
     * 이미 적재된 경기에 팀만 뒤늦게 채운다 (D54).
     *
     * <p>팀을 적재하기 전에 들어온 경기가 수천 건 있다. 그 경기들은 다시 적재되지 않는다 —
     * 같은 내용이면 {@code ingest_run} 의 해시 중복에서, 내용이 늘었어도 여기 위쪽
     * {@code existing} 검사에서 걸린다. 그래서 <b>다시 지나갈 때 채우는</b> 자리가 필요하다.
     *
     * <p>이미 팀이 붙은 경기는 건드리지 않는다. 덮어쓰면 재적재가 값을 바꿀 수 있게 되고,
     * 그러면 "같은 파일을 다시 적재해도 아무것도 안 바뀐다" 는 계약이 깨진다.
     *
     * @return 실제로 채웠으면 {@code true}
     */
    private static boolean backfillTeams(MatchRecord match, ParsedGame g, TeamRegistry teamRegistry) {
        if (match.getBlueTeamId() != null || match.getRedTeamId() != null) {
            return false;
        }
        Integer blue = teamRegistry.teamIdOf(g.blueTeamId());
        Integer red = teamRegistry.teamIdOf(g.redTeamId());
        if (blue == null && red == null) {
            return false;                                    // 세이브에도 번호가 없다. 채울 것이 없다
        }
        match.assignTeams(blue, red);                        // 같은 트랜잭션의 영속 객체다 — 더티 체킹이 쓴다
        return true;
    }

    /** 고정 형식인지. 픽 4+4 · 밴 3+3 이어야 한다 (D35). */
    private static boolean isFixedFormat(ParsedGame g) {
        return g.bluePick().size() == TEAM_SIZE
                && g.redPick().size() == TEAM_SIZE
                && g.blueBan().size() == BANS_PER_TEAM
                && g.redBan().size() == BANS_PER_TEAM;
    }

    // ------------------------------------------------------------------ 스크림

    private Counts saveScrims(SaveSlot slot, List<ParsedScrim> scrims, ParsedToday today,
                              Map<String, Champion> byCode, PatchAssigner assigner) {
        Set<Integer> existing = new HashSet<>();
        matches.findBySlotIdAndMatchType(slot.getSlotId(), MatchType.SCRIM)
                .forEach(m -> existing.add(m.getSourceGameId()));

        Counts counts = new Counts();
        OffsetDateTime now = OffsetDateTime.now();

        for (ParsedScrim s : scrims) {
            if (s.blueStat().size() != TEAM_SIZE || s.redStat().size() != TEAM_SIZE) {
                counts.skipped++;                                // 4v4 가 아니다 (D35)
                continue;
            }
            if (s.id() == null || existing.contains(s.id())) {
                continue;
            }

            TeamSide winner = winnerOf(s);
            MatchRecord match = new MatchRecord(slot.getSlotId(), MatchType.SCRIM, s.id(), winner);
            // 스크림에는 팀이 없다. ScrimStat.TeamID 는 관측 전부가 0 — 플레이어 팀 자신이고
            // 상대 번호가 어디에도 없다. 어느 진영이 플레이어인지도 모른다 (D54).
            match.setTeams(null, null, s.blueScore(), s.redScore());
            match.setTeamSize(TEAM_SIZE);

            // 세이브에 시점이 없다. 워처가 발견한 게임 내 날짜가 유일한 근거다 (D8).
            match.markObserved(today.season(), today.day(), now);
            Patch patch = assigner.patchAt(today.season(), today.day());
            match.assignPatch(patch == null ? null : patch.getPatchId());
            matches.save(match);

            saveScrimSide(match, TeamSide.BLUE, s.blueStat(), byCode, assigner, today);
            saveScrimSide(match, TeamSide.RED, s.redStat(), byCode, assigner, today);
            counts.saved++;
        }
        return counts;
    }

    /** 스크림의 승패는 점수 비교로만 판정한다. 동점은 승자를 정할 수 없다. */
    private static TeamSide winnerOf(ParsedScrim s) {
        if (s.blueScore() == null || s.redScore() == null) {
            throw new IllegalStateException("스크림 " + s.id() + " 에 점수가 없어 승패를 알 수 없다");
        }
        if (s.blueScore().equals(s.redScore())) {
            throw new IllegalStateException("스크림 " + s.id() + " 이 동점이다. 승패를 정할 수 없다");
        }
        return s.blueScore() > s.redScore() ? TeamSide.BLUE : TeamSide.RED;
    }

    // ------------------------------------------------------------------ 참가자·밴

    private void saveSide(MatchRecord match, TeamSide side, List<String> picks, List<ParsedStat> champStat,
                          Map<String, Champion> byCode, PatchAssigner assigner,
                          Integer season, Integer day) {
        // 이름으로 매칭한다. ChampStat 인덱스는 20.5% 에서 어긋난다 (D20).
        for (ParticipantMatcher.Slot slot : ParticipantMatcher.slotsOf(picks, champStat)) {
            Champion champion = requireChampion(byCode, slot.champion());
            MatchParticipant p = new MatchParticipant(
                    new ParticipantId(match.getMatchId(), side, slot.pickOrder()),
                    champion.getChampionId());
            if (season != null && day != null) {
                p.setChangeCount(assigner.changeCountAt(champion.getChampionId(), season, day));
            }
            applyStat(p, slot.stat());
            participants.save(p);
        }
    }

    private void saveScrimSide(MatchRecord match, TeamSide side, List<ParsedStat> stats,
                               Map<String, Champion> byCode, PatchAssigner assigner, ParsedToday today) {
        for (int i = 0; i < stats.size(); i++) {
            ParsedStat s = stats.get(i);
            Champion champion = requireChampion(byCode, s.champion());
            MatchParticipant p = new MatchParticipant(
                    new ParticipantId(match.getMatchId(), side, i + 1),
                    champion.getChampionId());
            p.setChangeCount(assigner.changeCountAt(champion.getChampionId(), today.season(), today.day()));
            applyStat(p, s);
            participants.save(p);
        }
    }

    private static void applyStat(MatchParticipant p, ParsedStat s) {
        if (s == null) {
            return;                                              // ChampStat 에 없는 픽. 지표만 비고 참가는 유효하다
        }
        p.setStats(s.athleteId(), s.kill(), s.death(), s.assist(),
                s.dealing(), s.tanking(), s.healing(), s.liveDuration());
    }

    private void saveBans(MatchRecord match, TeamSide side, List<String> banned, Map<String, Champion> byCode) {
        for (int i = 0; i < banned.size(); i++) {
            Champion champion = requireChampion(byCode, banned.get(i));
            bans.save(new MatchBan(new BanId(match.getMatchId(), side, i + 1), champion.getChampionId()));
        }
    }

    /**
     * 챔피언을 찾는다. 없으면 던진다.
     *
     * <p>시드에 없는 이름이 나왔다는 것은 게임에 챔피언이 추가됐거나 시드가 낡았다는 뜻이다.
     * 그 참가자를 건너뛰면 경기가 3명이 되어 조용히 통계를 오염시킨다.
     */
    private static Champion requireChampion(Map<String, Champion> byCode, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("챔피언 이름이 없다. 이 경기는 분석에 쓸 수 없다");
        }
        Champion champion = byCode.get(code);
        if (champion == null) {
            throw new IllegalStateException(
                    "시드에 없는 챔피언이다: " + code + ". seed/champions.csv 를 갱신해야 한다");
        }
        return champion;
    }

    private static int zero(Integer v) {
        return v == null ? 0 : v;
    }

    /** 적재된 수 · 형식이 맞지 않아 건너뛴 수 · 뒤늦게 팀을 채운 수. */
    private static final class Counts {
        private int saved;
        private int skipped;
        private int backfilled;
    }
}
