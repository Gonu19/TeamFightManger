package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.AthleteNameSeed;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AthleteNameSeedRepository extends JpaRepository<AthleteNameSeed, Integer> {
}
