package com.teamfighter.tfm.story.gallery;

import com.teamfighter.tfm.ingest.watcher.TfmProperties;
import com.teamfighter.tfm.parser.common.MatchScheduleParser;
import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.save.SaveParser;
import com.teamfighter.tfm.story.MatchBrief;
import com.teamfighter.tfm.story.SeasonBook;
import com.teamfighter.tfm.story.dao.ArticleDao;
import com.teamfighter.tfm.story.dao.ArticleKey;
import com.teamfighter.tfm.story.dao.GalleryBatch;
import com.teamfighter.tfm.story.dao.GalleryDao;
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
 * "다음 갤러리를 뽑는다" 의 진입점. <b>기사를 거치지 않는다</b> (D73).
 *
 * <h2>{@code StoryGenerator} 와 무엇이 다른가</h2>
 *
 * 고르는 규칙은 거의 같다 — 끝난 매치 중 세트 기록이 있고 아직 안 뽑은 것 중 가장 최근.
 * 다른 것은 <b>"안 뽑은" 의 기준</b>이다. 저쪽은 {@code article} 을 보고 이쪽은
 * {@code gallery_batch} 를 본다. 두 표가 독립이라 기사 없이도 갤러리가 나오고,
 * 갤러리 없이도 기사가 나온다.
 *
 * <p>세이브를 다시 읽는 이유도 같다: <b>DB 에 매치 일정이 없다.</b> 적재는 경기(세트)만
 * 넣는다 — 매치({@code MatchSchedule})는 갤러리가 생기기 전에 필요가 없어서 스키마에
 * 자리가 없다. 그래서 갤을 뽑으려면 세이브를 다시 파싱해야 한다.
 *
 * <h2>기사가 있으면 링크만 걸어 둔다</h2>
 *
 * 같은 매치에 기사가 이미 있으면 {@code article_id} 를 채운다. 생성에는 <b>안 쓴다</b> —
 * 갤 글의 근거는 선수별 표이지 기사 본문이 아니다. 화면이 "기사 보기" 를 그릴지
 * 정하는 데만 쓰인다.
 */
public class GalleryGenerator {

    private static final Logger log = LoggerFactory.getLogger(GalleryGenerator.class);

    private final GalleryWriter writer;
    private final GalleryDao galleries;
    private final ArticleDao articles;
    private final StoryReferenceDao references;
    private final TfmProperties properties;

    public GalleryGenerator(GalleryWriter writer, GalleryDao galleries, ArticleDao articles,
                            StoryReferenceDao references, TfmProperties properties) {
        this.writer = writer;
        this.galleries = galleries;
        this.articles = articles;
        this.references = references;
        this.properties = properties;
    }

    /**
     * 아직 갤러리가 없는 매치 중 <b>가장 최근 것</b> 하나를 뽑는다.
     *
     * @return 저장된 {@code batch_id}. 뽑을 매치가 없으면 {@link Optional#empty()} —
     *         <b>예외가 아니다.</b> "다 뽑았다" 는 정상 상태다
     */
    public Optional<Long> writeNext(int slotId, GalleryWriter.Progress progress) {
        Path saveFile = locateSaveFile(slotId);                                 // 1. 슬롯 → 파일 경로 (slot_key 가 곧 파일명이다)

        List<ParsedSchedule> schedules;
        List<ParsedGame> sets;
        try {
            schedules = MatchScheduleParser.read(saveFile);                     // 2. 매치 목록 (MatchSchedule 구역)
            sets = SaveParser.read(saveFile).gameStats();                       // 3. 세트 목록 (GameStat 구역). 파서가 따로라 파일을 두 번 읽는다
        } catch (IOException e) {
            throw new UncheckedIOException("세이브를 읽지 못했다: " + saveFile, e);
        }

        return writeNext(references.load(slotId), schedules, sets, progress);
    }

    /**
     * 파일을 이미 읽었을 때. <b>테스트가 쓰는 입구이기도 하다</b> —
     * 이 메서드는 디스크를 모르므로 세이브 파일 없이 매치 고르는 규칙만 검증할 수 있다.
     */
    public Optional<Long> writeNext(StoryReference reference, List<ParsedSchedule> schedules,
                                    List<ParsedGame> sets, GalleryWriter.Progress progress) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(schedules, "schedules");
        Objects.requireNonNull(sets, "sets");

        Map<ParsedSchedule.MatchKey, List<ParsedGame>> setsByMatch = groupSets(sets);   // 1. 세트를 매치별로 묶는다 (시즌·일·무순 팀쌍이 열쇠)
        Set<ArticleKey> already = galleries.writtenKeys(reference.slotId());            // 2. 갤러리가 이미 있는 매치를 한 번에 읽어 둔다

        Optional<ParsedSchedule> target = schedules.stream()                            // 3. 후보를 걸러 하나만 남긴다
                .filter(ParsedSchedule::isPlayed)                                       // 3-1. 끝난 매치만. 진행 중이면 결과를 지어내게 된다
                .filter(match -> setsByMatch.containsKey(match.matchKey()))             // 3-2. 세트 기록이 있는 매치만 — 선수별 표가 갤 글의 근거다
                .filter(match -> !already.contains(keyOf(reference, match)))            // 3-3. 아직 안 뽑은 것만
                .max(Comparator.comparingInt((ParsedSchedule m) -> orZero(m.season()))  // 3-4. 그중 가장 최근. max 는 정렬 없이 한 번 훑는다
                        .thenComparingInt(m -> orZero(m.day())));

        if (target.isEmpty()) {
            log.info("슬롯 {}: 갤러리를 뽑을 매치가 없다 (세트가 있는 매치 {}건, 이미 뽑은 갤 {}개)",
                    reference.slotId(), setsByMatch.size(), already.size());
            return Optional.empty();
        }

        ParsedSchedule match = target.get();
        MatchBrief brief = MatchBrief.of(match, setsByMatch.get(match.matchKey()));      // 4. 사실만 모은다. 두 등식이 안 맞으면 여기서 던진다
        List<String> tags = new SeasonBook(schedules).tagsFor(match, reference);         // 5. 맥락 태그 (순위·연패·라이벌). 최대 2개

        ArticleKey key = keyOf(reference, match);
        GalleryBatch batch = new GalleryBatch(
                reference.slotId(),
                articles.findIdByKey(reference.slotId(), key).orElse(null),              // 6. 기사가 있으면 링크만 건다. 생성에는 안 쓴다
                key.season(), key.day(), key.blueTeamId(), key.redTeamId(),
                match.blueScore(), match.redScore(),
                writer.model(),
                GalleryChunk.page().size());

        return writer.write(batch, brief, reference, tags, progress);                    // 7. 여기부터는 GalleryWriter 의 일이다
    }

    /**
     * 세트를 매치별로 묶는다. {@code StoryGenerator} 의 것과 같은 규칙이다 —
     * 키를 못 만드는 세트(시즌·일·팀이 비어 있는 것)는 버린다. 억지로 붙이면 남의 매치의
     * 킬 합이 어긋나서 {@code MatchBrief} 가 <b>원인에서 먼 곳에서</b> 던진다.
     */
    private static Map<ParsedSchedule.MatchKey, List<ParsedGame>> groupSets(List<ParsedGame> sets) {
        Map<ParsedSchedule.MatchKey, List<ParsedGame>> grouped = new LinkedHashMap<>();
        for (ParsedGame set : sets) {
            if (set.season() == null || set.day() == null
                    || set.blueTeamId() == null || set.redTeamId() == null) {
                continue;
            }
            grouped.computeIfAbsent(ParsedSchedule.MatchKey.of(
                    set.season(), set.day(), set.blueTeamId(), set.redTeamId()),
                    k -> new ArrayList<>()).add(set);
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
     * 슬롯 → 세이브 파일. {@code StoryGenerator} 와 <b>같은 설정</b>({@code tfm.save-dir})을
     * 쓴다 — 별도 설정을 두면 둘이 다른 폴더를 가리킬 수 있고, 그때 증상은
     * "갤러리가 옛날 경기만 나온다" 로 나타난다. 원인을 찾기 아주 나쁜 종류다.
     */
    private Path locateSaveFile(int slotId) {
        Path saveDir = properties.getSaveDir();
        if (saveDir == null) {
            throw new IllegalStateException(
                    "tfm.save-dir 이 없다. 갤러리는 세이브 파일을 다시 읽어야 한다 — "
                            + "DB 에는 매치 일정(MatchSchedule)이 없기 때문이다");
        }
        Path file = saveDir.resolve(references.slotKey(slotId));
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("세이브 파일을 읽을 수 없다: " + file);
        }
        return file;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
