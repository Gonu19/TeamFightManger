package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.SaveSlot;
import com.teamfighter.tfm.ingest.watcher.TfmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReingestRunner} 의 계약을 고정한다. DB 는 안 쓴다 — 저장소·적재기는 목이다.
 *
 * <p>{@code StartupCatchUpTest} 와 같은 방식으로 둘을 나눈다: <b>빈이 올바른 조건으로
 * 등록되는가</b> 는 {@link ApplicationContextRunner} 로, <b>등록되면 무슨 일을 하는가</b> 는
 * {@code run()} 을 직접 불러서 본다.
 */
class ReingestRunnerTest {

    private static TfmProperties propertiesFor(Path saveDir) {
        TfmProperties properties = new TfmProperties();
        properties.setSaveDir(saveDir);
        return properties;
    }

    private static Path slotFile(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, "x", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("폴더의 슬롯을 전부, 파일명 순으로 다시 적재한다")
    void run_reloadsEverySlot(@TempDir Path dir) throws IOException {
        Path first = slotFile(dir, "slot_1.tfm");
        Path second = slotFile(dir, "slot_2.tfm");
        slotFile(dir, "slot_2.tfm_backup");                     // 슬롯이 아니다 (D28)

        SlotRegistry slotRegistry = mock(SlotRegistry.class);
        SaveLoader loader = mock(SaveLoader.class);
        SaveSlot slotOne = new SaveSlot("slot_1", null);
        SaveSlot slotTwo = new SaveSlot("slot_2", null);
        when(slotRegistry.ensure(first)).thenReturn(slotOne);
        when(slotRegistry.ensure(second)).thenReturn(slotTwo);

        new ReingestRunner(propertiesFor(dir), slotRegistry, loader).run(new DefaultApplicationArguments());

        // 변조: 루프를 첫 슬롯에서 break 하면 손대지 않는 커리어가 영영 안 고쳐진다.
        var order = inOrder(loader);
        order.verify(loader).load(slotOne, first);
        order.verify(loader).load(slotTwo, second);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("해시 검사를 거치지 않는다 — 같은 내용이어도 적재기까지 내려간다")
    void run_bypassesTheHashCheck(@TempDir Path dir) throws IOException {
        Path only = slotFile(dir, "slot_1.tfm");

        SlotRegistry slotRegistry = mock(SlotRegistry.class);
        SaveLoader loader = mock(SaveLoader.class);
        IngestService ingestService = mock(IngestService.class);   // 이 경로는 쓰지 않는다
        SaveSlot slot = new SaveSlot("slot_1", null);
        when(slotRegistry.ensure(only)).thenReturn(slot);

        new ReingestRunner(propertiesFor(dir), slotRegistry, loader).run(new DefaultApplicationArguments());

        // 변조: IngestService.ingest() 로 바꾸면 ingest_run 의 해시 중복에 걸려
        // 내용이 그대로인 슬롯은 SaveLoader 까지 도달하지 못한다 — 백필이 통째로 안 돈다.
        verify(loader).load(slot, only);
        verify(ingestService, never()).ingest(any());
    }

    @Test
    @DisplayName("세이브 폴더가 없으면 던진다 — 조용히 아무것도 안 하지 않는다")
    void run_throws_whenSaveDirIsMissing(@TempDir Path dir) {
        SaveLoader loader = mock(SaveLoader.class);

        assertThatThrownBy(() -> new ReingestRunner(
                propertiesFor(dir.resolve("없는폴더")), mock(SlotRegistry.class), loader)
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);

        verify(loader, never()).load(any(), any());
    }

    @Test
    @DisplayName("플래그를 켜야 등록된다 — 기본은 꺼져 있다")
    void bean_isRegisteredOnlyWhenFlagIsTrue() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(IngestConfiguration.class)
                .withBean(TfmProperties.class, TfmProperties::new)
                .withBean(SlotRegistry.class, () -> mock(SlotRegistry.class))
                .withBean(SaveLoader.class, () -> mock(SaveLoader.class));

        runner.run(context -> assertThat(context).doesNotHaveBean(ReingestRunner.class));
        runner.withPropertyValues("tfm.reingest-on-start=false")
                .run(context -> assertThat(context).doesNotHaveBean(ReingestRunner.class));
        runner.withPropertyValues("tfm.reingest-on-start=true")
                .run(context -> assertThat(context).hasSingleBean(ReingestRunner.class));
    }
}
