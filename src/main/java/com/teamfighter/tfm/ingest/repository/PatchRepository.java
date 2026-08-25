package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.Patch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PatchRepository extends JpaRepository<Patch, Integer> {

    Optional<Patch> findBySlotIdAndSeasonAndDay(Integer slotId, Integer season, Integer day);

    /** 패치 배정에 쓴다. 경기 시점 이전의 마지막 패치가 그 경기의 패치다. */
    List<Patch> findBySlotIdOrderBySeqAsc(Integer slotId);
}
