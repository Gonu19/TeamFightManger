package com.teamfighter.tfm.story;

import com.teamfighter.tfm.ingest.watcher.TfmProperties;
import com.teamfighter.tfm.parser.common.MatchScheduleParser;
import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.save.SaveParser;
import com.teamfighter.tfm.story.dao.ArticleDao;
import com.teamfighter.tfm.story.dao.ArticleKey;
import com.teamfighter.tfm.story.dao.StoryReference;
import com.teamfighter.tfm.story.dao.StoryReferenceDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 기사 생성을 <b>사람이 누를 때만</b> 시작하는 진입점.
 *
 * <h2>왜 자동이 아닌가</h2>
 *
 * 적재는 워처가 자동으로 돈다(파일이 바뀌면 곧바로). 기사 생성을 거기 붙이면 세이브를
 * 저장할 때마다 모델 호출이 나가는데, 그건 <b>돈이 나가는 부수 효과</b>다. 게임을 하다
 * 저장을 열 번 하면 요청도 열 번 나가고, 그 사실은 화면 어디에도 안 보인다.
 * 그래서 수동 트리거다 — 누른 만큼만 나간다.
 *
 * <h2>한 번 누르면 기사 한 편</h2>
 *
 * "아직 안 쓴 매치 중 가장 최근 것" 하나만 쓴다. 전부 한꺼번에 쓰지 않는 이유가 둘이다.
 * <ul>
 *   <li><b>비용이 예측 가능해야 한다.</b> 한 편 = 모델 호출 두 번(기사 · 댓글)이다.
 *       109편을 한 번에 돌리면 218번이 나가고, 중간에 실패하면 어디까지 갔는지 모른다</li>
 *   <li><b>사람이 결과를 보고 다음을 정할 수 있어야 한다.</b> 프롬프트가 마음에 안 들면
 *       한 편 보고 고치는 것과, 109편 뽑고 나서 고치는 것은 값이 다르다</li>
 * </ul>
 *
 * <h2>동작 순서</h2>
 *
 * <pre>
 *   1. 슬롯 → 세이브 파일 경로        (save_slot.slot_key 가 곧 파일명이다)
 *   2. 파일 → 매치 목록 + 세트 목록    (MatchScheduleParser · SaveParser)
 *   3. 세트를 매치에 붙인다           (시즌·일·무순 팀쌍 = MatchKey)
 *   4. 이미 쓴 매치를 뺀다            (ArticleDao.writtenKeys)
 *   5. 남은 것 중 가장 최근 하나       (시즌 내림 → 일 내림)
 *   6. MatchBrief → ArticleWriter    (여기부터는 ArticleWriter 의 일이다)
 * </pre>
 *
 * <p><b>3번이 이 클래스에서 제일 조심스러운 곳이다.</b> 세트({@code GameStat})와
 * 매치({@code MatchSchedule})는 세이브에서 따로 저장되고, 둘을 잇는 안전한 열쇠가
 * {@code scheduleId} 가 아니다 — 대회마다 ID 공간이 따로라 실측 190건이 114개 값에 겹친다.
 * 그래서 {@link ParsedSchedule.MatchKey} 로 잇는다(시즌 · 일 · 팀 두 개를 정렬해 무순으로).
 * 팀을 정렬하는 이유는 세트의 진영이 매치 기준과 반대인 경우가 실측 294세트 중 122건이기
 * 때문이다 — 순서를 그대로 두면 그 122건이 다른 매치로 간다.
 */
public class StoryGenerator {

    private static final Logger log = LoggerFactory.getLogger(StoryGenerator.class);

    private final ArticleWriter writer;
    private final ArticleDao articles;
    private final StoryReferenceDao references;
    private final TfmProperties properties;

    public StoryGenerator(ArticleWriter writer, ArticleDao articles,
                          StoryReferenceDao references, TfmProperties properties) {
        this.writer = writer;
        this.articles = articles;
        this.references = references;
        this.properties = properties;
    }

    /**
     * 그 커리어에서 아직 기사가 없는 매치 중 <b>가장 최근 것</b> 한 편을 쓴다.
     *
     * @return 저장된 {@code article_id}. 쓸 매치가 없으면 {@link Optional#empty()} —
     *         <b>예외가 아니다.</b> "다 썼다" 는 정상 상태이고, 화면은 그걸 그대로 말하면 된다
     */
    public Optional<Long> writeLatestUnwritten(int slotId) {
        Path saveFile = locateSaveFile(slotId);                                 // 1. 슬롯 → 파일 경로 (save_slot.slot_key 가 곧 파일명이다)

        List<ParsedSchedule> schedules;
        List<ParsedGame> sets;
        try {
            schedules = MatchScheduleParser.read(saveFile);                     // 2. 매치 목록 (MatchSchedule 구역)
            sets = SaveParser.read(saveFile).gameStats();                       // 3. 세트 목록 (GameStat 구역). 파서가 따로라 파일을 두 번 읽는다
        } catch (IOException e) {
            throw new UncheckedIOException("세이브를 읽지 못했다: " + saveFile, e);
        }

        return writeLatestUnwritten(references.load(slotId), schedules, sets);  // 4. 이름표를 읽어 아래 오버로드로 넘긴다
    }

    /**
     * 파일을 이미 읽었을 때. <b>테스트가 쓰는 입구이기도 하다</b> —
     * 이 메서드는 디스크를 모르므로 세이브 파일 없이 매치 고르는 규칙만 검증할 수 있다.
     */
    public Optional<Long> writeLatestUnwritten(StoryReference reference,
                                               List<ParsedSchedule> schedules,
                                               List<ParsedGame> sets) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(schedules, "schedules");
        Objects.requireNonNull(sets, "sets");

        Map<ParsedSchedule.MatchKey, List<ParsedGame>> setsByMatch = groupSets(sets);   // 1. 세트를 매치별로 묶는다 (시즌·일·무순 팀쌍이 열쇠)
        Set<ArticleKey> alreadyWritten = articles.writtenKeys(reference.slotId());      // 2. 이미 쓴 매치 신원을 한 번에 읽어 둔다

        Optional<ParsedSchedule> target = schedules.stream()                            // 3. 후보를 걸러 하나만 남긴다 (아래 네 단계)
                .filter(ParsedSchedule::isPlayed)                                       // 3-1. 끝난 매치만. 진행 중이면 결과를 지어내게 된다
                .filter(match -> setsByMatch.containsKey(match.matchKey()))             // 3-2. 세트 기록이 있는 매치만. 옛 시즌은 세트가 버려져 있다 (D6)
                .filter(match -> !alreadyWritten.contains(keyOf(reference, match)))     // 3-3. 아직 안 쓴 것만. keyOf 가 세이브 번호를 DB 번호로 바꾼다
                .max(Comparator.comparingInt((ParsedSchedule m) -> orZero(m.season()))  // 3-4. 그중 가장 최근. max 는 정렬 없이 한 번 훑는다
                        .thenComparingInt(m -> orZero(m.day())));                       //      시즌이 1순위, 같은 시즌이면 일(day)이 2순위

        if (target.isEmpty()) {
            log.info("슬롯 {}: 새로 쓸 매치가 없다 (끝난 매치 {}건, 이미 쓴 기사 {}편)",
                    reference.slotId(), setsByMatch.size(), alreadyWritten.size());
            return Optional.empty();
        }

        ParsedSchedule match = target.get();

        SeasonBook book = new SeasonBook(schedules);                            // 4. 순위·업셋·라이벌의 재료. 전체 일정을 넘겨도 미래는 안 본다
        NotabilityContext context = book.contextFor(match, reference.playerGameTeamId());  // 5. 그 매치 시점의 맥락. 이름표가 들고 온 is_player 팀을 넘긴다 (없으면 null = "모른다")
        MatchBrief brief = MatchBrief.of(match, setsByMatch.get(match.matchKey()));  // 6. 사실만 모은다. 두 등식이 안 맞으면 여기서 던진다
        List<String> tags = book.tagsFor(match, reference);                     // 7. 맥락 태그 (순위·연패·라이벌). 최대 2개

        long articleId = writer.write(reference, brief, context, tags);         // 8. 여기부터는 ArticleWriter 의 일이다 (호출 → 대조 → 저장)
        log.info("슬롯 {}: 시즌 {} {}일 매치로 기사 {} 를 썼다",
                reference.slotId(), match.season(), match.day(), articleId);
        return Optional.of(articleId);
    }

    /**
     * 아직 총평이 없는 날 중 <b>가장 최근 날</b>의 총평을 쓴다.
     *
     * <h2>왜 버튼이 따로인가</h2>
     *
     * 매치 기사와 한 버튼으로 묶으면 한 번 누를 때 모델 호출이 넷이 된다. 무료 티어의
     * 분당 토큰 한도(8,000)에서 그건 거의 확실히 걸린다 — 걸리면 기다렸다 다시 부르므로
     * 실패는 아니지만, 한 번 누르고 20초를 보는 것보다 두 번 나눠 누르는 편이 낫다.
     * <b>비용의 단위를 사람이 고르게 한다</b>는 수동 트리거의 취지와도 맞는다.
     *
     * @return 저장된 {@code article_id}. 쓸 날이 없으면 {@link Optional#empty()}
     */
    public Optional<Long> writeLatestRoundSummary(int slotId) {
        Path saveFile = locateSaveFile(slotId);
        List<ParsedSchedule> schedules;
        try {
            schedules = MatchScheduleParser.read(saveFile);                      // 총평은 세트를 안 본다 — 매치만 읽으면 된다
        } catch (IOException e) {
            throw new UncheckedIOException("세이브를 읽지 못했다: " + saveFile, e);
        }
        return writeLatestRoundSummary(references.load(slotId), schedules);
    }

    /** 파일을 이미 읽었을 때. 테스트가 쓰는 입구다. */
    public Optional<Long> writeLatestRoundSummary(StoryReference reference,
                                                  List<ParsedSchedule> schedules) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(schedules, "schedules");

        Set<ArticleKey> written = articles.writtenRoundKeys(reference.slotId()); // 1. 이미 쓴 날

        Optional<int[]> target = schedules.stream()                             // 2. 끝난 매치가 있는 날들
                .filter(ParsedSchedule::isPlayed)
                .filter(m -> m.season() != null && m.day() != null)
                .map(m -> new int[] {m.season(), m.day()})
                .filter(d -> !written.contains(roundKey(d[0], d[1])))           // 3. 총평이 아직 없는 날만
                .max(Comparator.<int[]>comparingInt(d -> d[0])                  // 4. 그중 가장 최근
                        .thenComparingInt(d -> d[1]));

        if (target.isEmpty()) {
            log.info("슬롯 {}: 총평을 쓸 날이 없다 (이미 쓴 날 {}개)",
                    reference.slotId(), written.size());
            return Optional.empty();
        }

        RoundBrief brief = RoundBrief.of(target.get()[0], target.get()[1], schedules);
        if (!brief.isWorthSummarising()) {                                      // 5. 한 경기뿐이면 매치 기사와 같은 말이 된다
            log.info("슬롯 {}: 시즌 {} {}일은 경기가 하나뿐이라 총평을 쓰지 않는다",
                    reference.slotId(), brief.season(), brief.day());
            return Optional.empty();
        }

        long articleId = writer.writeRoundSummary(reference, brief);
        log.info("슬롯 {}: 시즌 {} {}일 총평 {} 를 썼다 (경기 {}건)",
                reference.slotId(), brief.season(), brief.day(), articleId, brief.results().size());
        return Optional.of(articleId);
    }

    /**
     * 총평의 신원. 팀이 없으므로 0 으로 채운다.
     *
     * <p>DB 에서는 그 자리가 NULL 이고 {@code NULLS NOT DISTINCT} 로 유일성이 선다(V10).
     * 자바 쪽 {@link ArticleKey} 는 {@code int} 라 NULL 을 담을 수 없어 0 으로 대신한다 —
     * <b>팀 번호 0 은 DB 시퀀스가 만들지 않는 값</b>이라 매치 기사와 부딪히지 않는다.
     */
    private static ArticleKey roundKey(int season, int day) {
        return new ArticleKey(season, day, 0, 0);
    }

    /**
     * 세트를 매치별로 묶는다.
     *
     * <p>{@link LinkedHashMap} 인 이유는 순서 때문이 아니라 <b>디버깅 때문</b>이다 —
     * 로그로 찍었을 때 세이브에 든 순서 그대로 보이면 어긋난 세트를 눈으로 찾을 수 있다.
     *
     * <p>키를 못 만드는 세트(시즌·일·팀이 비어 있는 것)는 버린다. 그런 세트는 어느 매치에도
     * 붙일 수 없고, 억지로 붙이면 남의 매치의 킬 합이 어긋나서 {@code MatchBrief} 가
     * 던진다 — 원인에서 먼 곳에서 터지는 실패다.
     */
    private static Map<ParsedSchedule.MatchKey, List<ParsedGame>> groupSets(List<ParsedGame> sets) {
        Map<ParsedSchedule.MatchKey, List<ParsedGame>> grouped = new LinkedHashMap<>();
        for (ParsedGame set : sets) {
            if (set.season() == null || set.day() == null
                    || set.blueTeamId() == null || set.redTeamId() == null) {
                continue;
            }
            ParsedSchedule.MatchKey key = ParsedSchedule.MatchKey.of(
                    set.season(), set.day(), set.blueTeamId(), set.redTeamId());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(set);
        }
        return grouped;
    }

    /** 세이브 번호 → DB 번호. {@link ArticleKey} 는 DB 번호로만 만들어야 한다. */
    private static ArticleKey keyOf(StoryReference reference, ParsedSchedule match) {
        return new ArticleKey(
                orZero(match.season()), orZero(match.day()),
                reference.teamId(match.blueTeamId()),
                reference.teamId(match.redTeamId()));
    }

    /**
     * 슬롯 → 세이브 파일.
     *
     * <p>{@code save_slot.slot_key} 가 곧 파일명이다({@code slot_0.tfm}) — 적재가 그렇게
     * 넣는다(D28). 그래서 세이브 폴더에 이어 붙이면 경로가 된다. 폴더는
     * {@code tfm.save-dir} 설정이고, 그 값은 워처가 쓰는 것과 <b>같은 값</b>이다.
     * 여기에 별도 설정을 두면 둘이 다른 폴더를 가리킬 수 있고, 그때 증상은
     * "기사가 옛날 경기만 쓴다" 로 나타난다 — 원인을 찾기 아주 나쁜 종류다.
     */
    private Path locateSaveFile(int slotId) {
        Path saveDir = properties.getSaveDir();
        if (saveDir == null) {
            throw new IllegalStateException(
                    "tfm.save-dir 이 없다. 기사 생성은 세이브 파일을 다시 읽어야 한다 — "
                            + "DB 에는 매치 일정(MatchSchedule)이 없기 때문이다");
        }
        String slotKey = references.slotKey(slotId);
        Path file = saveDir.resolve(slotKey);
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("세이브 파일을 읽을 수 없다: " + file);
        }
        return file;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
