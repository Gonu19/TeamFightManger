package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.MatchParticipant;
import com.teamfighter.tfm.ingest.entity.ParticipantId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchParticipantRepository extends JpaRepository<MatchParticipant, ParticipantId> {
}
