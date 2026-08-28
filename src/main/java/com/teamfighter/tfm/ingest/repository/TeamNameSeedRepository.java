package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.TeamNameSeed;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamNameSeedRepository extends JpaRepository<TeamNameSeed, String> {
}
