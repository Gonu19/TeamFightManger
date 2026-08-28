package com.teamfighter.tfm.ingest;

import com.teamfighter.tfm.ingest.entity.SaveSlot;
import com.teamfighter.tfm.ingest.watcher.SlotPathResolver;
import com.teamfighter.tfm.ingest.watcher.TfmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.nio.file.Path;
import java.util.List;

/**
 * 기동 시 슬롯을 <b>해시 검사 없이</b> 한 번 다시 적재한다. <b>기본은 꺼져 있다.</b>
 *
 * <pre>gradlew.bat bootRun --args="--tfm.reingest-on-start=true"</pre>
 *
 * <p><b>왜 필요한가.</b> 적재 코드가 늘어나면 이미 들어간 행에는 새 컬럼이 비어 있다.
 * 그런데 평소 경로로는 그 행을 다시 지나갈 방법이 없다 — {@link IngestService#ingest}
 * 가 {@code ingest_run} 의 {@code UNIQUE(slot_id, file_hash)} 로 같은 내용을 먼저 걸러낸다.
 * 게임을 계속 하는 슬롯은 해시가 바뀌어 저절로 따라잡히지만, <b>손대지 않는 커리어는
 * 영원히 비어 있는 채로 남는다.</b> 팀 백필(D54)이 실제로 그 상황이었다.
 *
 * <p><b>{@link SaveLoader#load} 를 직접 부른다.</b> 우회하는 것은 해시 검사 하나뿐이고,
 * 적재 규칙은 한 벌 그대로 쓴다. 별도 백필 SQL 을 두면 규칙이 두 곳에서 따로 늙는다 —
 * 그쪽이 훨씬 비싸다.
 *
 * <p><b>{@code ingest_run} 을 남기지 않는다.</b> 새로 적재한 것이 없기 때문이다. 억지로
 * 남기면 같은 해시에 걸려 유니크 위반으로 죽거나, 해시를 비틀어 넣어 "이 내용은 적재됐다"
 * 는 기록을 두 벌로 만든다. 그래서 흔적은 로그뿐이다 — 이건 적재가 아니라 수리다.
 *
 * <p><b>다시 돌려도 안전하다.</b> 이미 있는 경기는 건너뛰고, 이미 팀이 붙은 경기는
 * 건드리지 않는다. 두 번째 실행은 백필 0건을 찍는다 — 그게 다 됐다는 신호다.
 *
 * <p><b>{@code tfm.aggregate-on-start} 와 같이 켜지 마라.</b> {@code ApplicationRunner} 두 개의
 * 실행 순서는 정의되어 있지 않고, 실측상 집계가 먼저 끝난다 — 팀이 아직 비어 있는 채로
 * 집계된다. 수리 한 번, 집계 한 번, 두 번 띄운다 (D54).
 *
 * <p>예외를 삼키지 않는다. 수리가 조용히 실패하고 "정상 기동" 으로 보이면, 화면에는
 * 그냥 팀 없는 경기가 계속 보인다 — 성공과 구별되지 않는다.
 */
public class ReingestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReingestRunner.class);

    private final TfmProperties properties;
    private final SlotRegistry slotRegistry;
    private final SaveLoader loader;

    public ReingestRunner(TfmProperties properties, SlotRegistry slotRegistry, SaveLoader loader) {
        this.properties = properties;
        this.slotRegistry = slotRegistry;
        this.loader = loader;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Path> slotFiles = SlotPathResolver.resolve(properties.getSaveDir());
        log.info("기동 시 재적재 — 해시 검사를 건너뛰고 슬롯 {}개를 다시 읽는다 "
                + "(tfm.reingest-on-start=true)", slotFiles.size());

        for (Path slotFile : slotFiles) {
            SaveSlot slot = slotRegistry.ensure(slotFile);
            loader.load(slot, slotFile);            // 건수는 load() 가 로그에 찍는다
        }
    }
}
