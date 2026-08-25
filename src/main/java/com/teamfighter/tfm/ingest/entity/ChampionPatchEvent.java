package com.teamfighter.tfm.ingest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/**
 * 패치 하나가 건드린 챔피언과 스탯 변화량.
 *
 * <p>{@code change_count} 의 원천이다 — "이 챔피언이 그 시점까지 몇 번 바뀌었나".
 * 자기 변경 감쇠 {@code 0.5^(변경횟수/2)} 의 지수가 여기서 나온다(D15).
 */
@Entity
@Table(name = "champion_patch_event")
public class ChampionPatchEvent implements Persistable<PatchEventId> {

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

    @Override
    public PatchEventId getId() {
        return id;
    }

    /**
     * 항상 새 행이다.
     *
     * <p>{@code @EmbeddedId} 는 ID 가 미리 채워져 있어서, Spring Data 의 기본 판정은
     * "ID 가 있으니 기존 행" 이 된다. 그러면 {@code save()} 가 {@code persist()} 가 아니라
     * {@code merge()} 를 호출하고, <b>INSERT 전에 SELECT 를 한 번 더 낸다.</b>
     *
     * <p>실측했다 — 경기 475건 적재에 statement 11,901개 중 5,720개가 그 SELECT 였다.
     * 있을 리 없는 행을 매번 확인한 셈이다.
     *
     * <p>이 테이블들은 <b>적재에서만 쓰고 수정하지 않는다.</b> 그래서 항상 새 행으로 본다.
     * 혹시 같은 키를 두 번 넣으면 유니크 제약에서 크게 실패한다 — 조용히 덮어쓰지 않는다.
     */
    @Override
    public boolean isNew() {
        return true;
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
