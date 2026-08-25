package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.SaveSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link IngestService} 구현. 조율만 한다.
 *
 * <p><b>이 메서드에는 {@code @Transactional} 이 없다.</b> 일부러 그렇다.
 * 세 가지가 각자 다른 트랜잭션에서 돌아야 하기 때문이다:
 *
 * <ol>
 *   <li>{@link SlotRegistry} — 슬롯은 적재가 실패해도 남아야 한다.
 *       실패 기록이 슬롯을 참조하므로, 같이 롤백되면 외래키 위반으로 기록 자체가 불가능해진다</li>
 *   <li>{@link IngestRunRecorder} — <b>실패 기록이 실패와 함께 사라지면 안 된다.</b>
 *       적재와 같은 트랜잭션에 두면 정확히 그렇게 된다</li>
 *   <li>{@link SaveLoader} — 파일 하나가 통째로 들어가거나 통째로 안 들어간다</li>
 * </ol>
 *
 * <p>원래는 한 메서드를 {@code @Transactional} 로 감싸고 그 안에서 다 했다.
 * 그래서 실패 기록이 남지 않았다 — 남기려던 바로 그 정보가.
 */
@Service
public class IngestServiceImpl implements IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestServiceImpl.class);

    /** 파서 구현이 바뀌면 재적재가 필요할 수 있다. 그 판단의 근거로 남긴다. */
    private static final String PARSER_VERSION = "java-nrbf-1";

    private final SlotRegistry slotRegistry;
    private final IngestRunRecorder recorder;
    private final SaveLoader loader;

    public IngestServiceImpl(SlotRegistry slotRegistry, IngestRunRecorder recorder, SaveLoader loader) {
        this.slotRegistry = slotRegistry;
        this.recorder = recorder;
        this.loader = loader;
    }

    @Override
    public IngestResult ingest(Path saveFile) {
        SaveSlot slot = slotRegistry.ensure(saveFile);   // 형식이 아니면 여기서 던진다 (D28)

        String hash;
        long size;
        try {
            hash = SlotFile.sha256(saveFile);
            size = Files.size(saveFile);
        } catch (IOException e) {
            throw new UncheckedIOException("세이브 파일을 읽지 못했다: " + saveFile, e);
        }

        if (recorder.alreadySucceeded(slot.getSlotId(), hash)) {
            log.debug("이미 적재된 내용이다: {} ({})", slot.getSlotKey(), hash.substring(0, 12));
            return IngestResult.duplicate(slot.getSlotId());
        }

        Long runId = recorder.start(slot.getSlotId(), hash, size, PARSER_VERSION);
        try {
            IngestResult result = loader.load(slot, saveFile);
            recorder.succeed(runId, result.newMatches(), result.newScrims());
            return result;
        } catch (RuntimeException e) {
            // 적재는 롤백되지만 이 기록은 남는다 — 다른 트랜잭션이기 때문이다.
            recorder.fail(runId, e);
            log.error("적재 실패 {} — {}", slot.getSlotKey(), e.toString());
            throw e;
        }
    }
}
