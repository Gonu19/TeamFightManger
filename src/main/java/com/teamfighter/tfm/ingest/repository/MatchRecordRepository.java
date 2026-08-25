package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.MatchRecord;
import com.teamfighter.tfm.ingest.entity.MatchType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MatchRecordRepository extends JpaRepository<MatchRecord, Long> {

    Optional<MatchRecord> findBySlotIdAndMatchTypeAndSourceGameId(
            Integer slotId, MatchType matchType, Integer sourceGameId);

    /** 이미 적재된 경기를 한 번에 확인한다. 건별 조회는 경기 수만큼 왕복한다. */
    List<MatchRecord> findBySlotIdAndMatchType(Integer slotId, MatchType matchType);

    long countBySlotIdAndMatchType(Integer slotId, MatchType matchType);
}
