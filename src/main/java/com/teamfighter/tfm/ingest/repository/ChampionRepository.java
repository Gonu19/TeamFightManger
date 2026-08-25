package com.teamfighter.tfm.ingest.repository;

import com.teamfighter.tfm.ingest.entity.Champion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChampionRepository extends JpaRepository<Champion, Integer> {

    /** 진영 매칭이 이 조회로 이뤄진다. 인덱스 순서를 쓰지 않는다 (D20). */
    Optional<Champion> findByCode(String code);
}
