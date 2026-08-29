package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.Athlete;
import com.teamfighter.tfm.ingest.entity.AthleteId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AthleteRepository extends JpaRepository<Athlete, AthleteId> {

    List<Athlete> findByIdSlotId(Integer slotId);
}
