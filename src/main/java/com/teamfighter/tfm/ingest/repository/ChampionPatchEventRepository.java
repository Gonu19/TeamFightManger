package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.ChampionPatchEvent;
import com.teamfighter.tfm.ingest.entity.PatchEventId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChampionPatchEventRepository extends JpaRepository<ChampionPatchEvent, PatchEventId> {

    List<ChampionPatchEvent> findByIdPatchId(Integer patchId);
}
