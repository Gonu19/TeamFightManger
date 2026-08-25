package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.IngestRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestRunRepository extends JpaRepository<IngestRun, Long> {

    /**
     * 같은 내용이 <b>성공적으로</b> 적재된 적이 있는지.
     *
     * <p>{@code error_message} 가 있는 행은 실패한 시도다. 그것까지 세면
     * 한 번 실패한 파일을 영영 다시 시도할 수 없게 된다.
     *
     * <p>슬롯 안에서만 도는 검사라는 한계는 그대로다 — 백업 파일이 다른 슬롯으로
     * 등록되면 내용이 같아도 걸러지지 않는다. 그래서 글롭 단계에서 막는다 (D28).
     */
    boolean existsBySlotIdAndFileHashAndErrorMessageIsNull(Integer slotId, String fileHash);
}
