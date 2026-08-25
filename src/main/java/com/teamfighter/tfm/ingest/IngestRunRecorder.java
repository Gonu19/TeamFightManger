package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.IngestRun;
import com.teamfighter.tfm.ingest.repository.IngestRunRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 적재 시도를 기록한다.
 *
 * <p><b>적재 본체와 다른 트랜잭션에서 돈다.</b> 같은 트랜잭션에 두면
 * 적재가 실패할 때 실패 기록도 롤백과 함께 사라진다 — 남기려던 바로 그 정보가
 * 남지 않는다. 이 클래스가 따로 있는 이유가 그것이다.
 */
@Component
public class IngestRunRecorder {

    private final IngestRunRepository runs;

    public IngestRunRecorder(IngestRunRepository runs) {
        this.runs = runs;
    }

    /**
     * 같은 내용이 <b>성공적으로</b> 적재된 적이 있는지.
     *
     * <p>실패한 시도는 세지 않는다. 세면 한 번 실패한 파일을 영영 다시 시도할 수 없다.
     */
    @Transactional(readOnly = true)
    public boolean alreadySucceeded(Integer slotId, String fileHash) {
        return runs.existsBySlotIdAndFileHashAndErrorMessageIsNull(slotId, fileHash);
    }

    @Transactional
    public Long start(Integer slotId, String fileHash, long fileSize, String parserVersion) {
        return runs.save(new IngestRun(slotId, fileHash, fileSize, parserVersion)).getRunId();
    }

    @Transactional
    public void succeed(Long runId, int newMatches, int newScrims) {
        runs.findById(runId).ifPresent(r -> r.succeed(newMatches, newScrims, OffsetDateTime.now()));
    }

    @Transactional
    public void fail(Long runId, Throwable cause) {
        runs.findById(runId).ifPresent(r -> r.fail(describe(cause), OffsetDateTime.now()));
    }

    /** 원인을 알아볼 수 있게 남긴다. 메시지만 남기면 어느 계층에서 났는지 모른다. */
    private static String describe(Throwable cause) {
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
