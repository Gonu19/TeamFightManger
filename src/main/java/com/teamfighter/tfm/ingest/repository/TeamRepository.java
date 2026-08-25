package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Integer> {

    Optional<Team> findBySlotIdAndGameTeamId(Integer slotId, Integer gameTeamId);

    List<Team> findBySlotId(Integer slotId);
}
