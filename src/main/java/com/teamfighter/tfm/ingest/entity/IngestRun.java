package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;

/**
 * 적재 시도 한 번.
 *
 * <p>{@code (slot_id, file_hash)} 로 같은 내용의 재파싱을 막는다.
 * <b>슬롯 안에서만</b> 도는 검사라, 다른 슬롯으로 등록된 백업 파일은 걸러지지 않는다 —
 * 그래서 글롭 단계에서 {@code *.tfm_backup} 을 배제해야 한다(D28).
 */
@Entity
@Table(name = "ingest_run", uniqueConstraints = @UniqueConstraint(columnNames = {"slot_id", "file_hash"}))
public class IngestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "run_id")
    private Long runId;

    @Column(name = "slot_id")
    private Integer slotId;

    @Column(name = "file_hash", nullable = false)
    private String fileHash;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "parser_version", nullable = false)
    private String parserVersion;

    @Column(name = "started_at", insertable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "new_matches", nullable = false)
    private int newMatches;

    @Column(name = "new_scrims", nullable = false)
    private int newScrims;

    /** 실패를 조용히 넘기지 않는다. 이 값이 있으면 그 적재는 실패한 것이다. */
    @Column(name = "error_message")
    private String errorMessage;

    protected IngestRun() {
    }

    public IngestRun(Integer slotId, String fileHash, long fileSize, String parserVersion) {
        this.slotId = slotId;
        this.fileHash = fileHash;
        this.fileSize = fileSize;
        this.parserVersion = parserVersion;
    }

    public Long getRunId() {
        return runId;
    }

    public String getFileHash() {
        return fileHash;
    }

    public int getNewMatches() {
        return newMatches;
    }

    public int getNewScrims() {
        return newScrims;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void succeed(int newMatches, int newScrims, OffsetDateTime at) {
        this.newMatches = newMatches;
        this.newScrims = newScrims;
        this.finishedAt = at;
    }

    public void fail(String message, OffsetDateTime at) {
        this.errorMessage = message;
        this.finishedAt = at;
    }
}
