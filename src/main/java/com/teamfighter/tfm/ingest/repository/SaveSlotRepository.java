package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.SaveSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SaveSlotRepository extends JpaRepository<SaveSlot, Integer> {

    Optional<SaveSlot> findBySlotKey(String slotKey);
}
