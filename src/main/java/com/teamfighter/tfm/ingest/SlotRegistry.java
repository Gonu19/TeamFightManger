package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.SaveSlot;
import com.teamfighter.tfm.ingest.repository.SaveSlotRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

/**
 * 슬롯을 찾거나 만든다.
 *
 * <p><b>적재 본체와 다른 트랜잭션에서 돈다.</b> 적재가 실패해도 슬롯은 남아야 한다 —
 * 실패 기록({@code ingest_run})이 슬롯을 참조하므로, 슬롯이 롤백되면
 * 그 기록은 외래키 위반으로 저장될 수 없다.
 */
@Component
public class SlotRegistry {

    private final SaveSlotRepository slots;

    public SlotRegistry(SaveSlotRepository slots) {
        this.slots = slots;
    }

    @Transactional
    public SaveSlot ensure(Path saveFile) {
        String slotKey = SlotFile.slotKeyOf(saveFile);      // 형식이 아니면 여기서 던진다 (D28)
        return slots.findBySlotKey(slotKey)
                .orElseGet(() -> slots.save(new SaveSlot(slotKey, null)));
    }
}
