package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.ChampionPatchEvent;
import com.teamfighter.tfm.ingest.entity.PatchEventId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChampionPatchEventRepository extends JpaRepository<ChampionPatchEvent, PatchEventId> {

    List<ChampionPatchEvent> findByIdPatchId(Integer patchId);

    /** 여러 패치의 변경 내역을 한 번에. 패치마다 따로 조회하면 패치 수만큼 왕복한다. */
    List<ChampionPatchEvent> findByIdPatchIdIn(List<Integer> patchIds);
}
