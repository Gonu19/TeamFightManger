package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.BanId;
import com.teamfighter.tfm.ingest.entity.MatchBan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchBanRepository extends JpaRepository<MatchBan, BanId> {
}
