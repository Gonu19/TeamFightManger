package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 패치 하나가 건드린 챔피언과 스탯 변화량.
 *
 * <p>{@code change_count} 의 원천이다 — "이 챔피언이 그 시점까지 몇 번 바뀌었나".
 * 자기 변경 감쇠 {@code 0.5^(변경횟수/2)} 의 지수가 여기서 나온다(D15).
 */
@Entity
@Table(name = "champion_patch_event")
public class ChampionPatchEvent {

    @EmbeddedId
    private PatchEventId id;

    @Column(nullable = false)
    private int attack;

    @Column(nullable = false)
    private int magic;

    @Column(nullable = false)
    private int defence;

    @Column(name = "max_hp", nullable = false)
    private int maxHp;

    @Column(name = "attack_speed", nullable = false)
    private int attackSpeed;

    @Column(name = "skill_cool", nullable = false)
    private int skillCool;

    @Column(name = "move_speed", nullable = false)
    private int moveSpeed;

    /** 이 패치에서 새로 추가된 챔피언인지. {@code PatchNews.NewChamps} 에서 온다. */
    @Column(name = "is_new", nullable = false)
    private boolean isNew;

    protected ChampionPatchEvent() {
    }

    public ChampionPatchEvent(PatchEventId id) {
        this.id = id;
    }

    public PatchEventId getId() {
        return id;
    }

    public void setChanges(int attack, int magic, int defence, int maxHp,
                           int attackSpeed, int skillCool, int moveSpeed) {
        this.attack = attack;
        this.magic = magic;
        this.defence = defence;
        this.maxHp = maxHp;
        this.attackSpeed = attackSpeed;
        this.skillCool = skillCool;
        this.moveSpeed = moveSpeed;
    }

    public void markNew() {
        this.isNew = true;
    }
}
