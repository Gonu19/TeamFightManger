package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.IngestRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestRunRepository extends JpaRepository<IngestRun, Long> {

    /** 같은 내용의 재적재를 막는다. 슬롯 안에서만 도는 검사다 (D28). */
    boolean existsBySlotIdAndFileHash(Integer slotId, String fileHash);
}
