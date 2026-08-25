package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/** {@code (patch_id, champion_id)} 자연키. */
@Embeddable
public class PatchEventId implements Serializable {

    @Column(name = "patch_id", nullable = false)
    private Integer patchId;

    @Column(name = "champion_id", nullable = false)
    private Integer championId;

    protected PatchEventId() {
    }

    public PatchEventId(Integer patchId, Integer championId) {
        this.patchId = patchId;
        this.championId = championId;
    }

    public Integer getPatchId() {
        return patchId;
    }

    public Integer getChampionId() {
        return championId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof PatchEventId other
                && Objects.equals(patchId, other.patchId)
                && Objects.equals(championId, other.championId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(patchId, championId);
    }
}
