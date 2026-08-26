package com.teamfighter.tfm.ingest.watcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * 기동 시 딱 한 번, 워처가 아직 건드리지 않은 슬롯을 따라잡는다.
 *
 * <p><b>왜 필요한가.</b> {@link SaveWatcher} 는 이벤트 기반이다. 게임이 건드리지 않은 슬롯은
 * 영원히 이벤트가 안 온다 — 앱이 꺼져 있던 동안 저장된 것도, 이번 기동에서 아직 아무도
 * 저장하지 않은 슬롯도 같은 이유로 샌다. 커리어 3개 중 게임이 건드린 1개만 적재되고
 * 나머지 2개가 통째로 누적에서 빠지는 사고가 실측으로 확인됐다.
 *
 * <p><b>{@link SaveWatcher#rescan()} 을 그대로 위임한다.</b> "폴더를 다시 훑어 놓친 변경을
 * 따라잡는다" 는 동작이 {@code OVERFLOW} 처리와 완전히 같다. 별도 구현을 두면 두 경로가
 * 따로 늙는다.
 *
 * <p><b>따로 스레드를 만들지 않는다.</b> {@code rescan()} 이 워처의 단일 디바운스 스레드에
 * 실려 직렬화되므로, 기동 직후 게임이 저장할 때 따라잡기와 워처 이벤트가 같은 파일을
 * 동시에 {@code ingest()} 하는 일이 없다.
 *
 * <p><b>예외를 삼키지 않는다.</b> {@code rescan()} 이 던지면 그대로 던져 기동을 막는다.
 * 이 앱은 누적이 존재 이유라, 따라잡기가 조용히 실패하고 "정상 기동"으로 보이는 상태가
 * 이 프로젝트에서 가장 경계하는 실패 방식이다.
 *
 * <p>여기로 올라오는 예외는 셋뿐이다. 슬롯 하나의 {@code ingest()} 실패는 아니다 —
 * 그건 {@link SaveWatcher} 가 슬롯 단위로 격리해 로그만 남긴다.
 * <ul>
 *   <li>{@code IllegalStateException} — 정지된 워처 (기동 시엔 일어나지 않는다)</li>
 *   <li>{@code IllegalStateException} — 세이브 폴더가 없거나 경로가 비었다</li>
 *   <li>{@code UncheckedIOException} — 폴더를 읽다 I/O 오류. <b>구조적 문제가 아닐 수도 있다.</b>
 *       네트워크·외장 드라이브 마운트가 늦으면 일시적으로 이게 난다</li>
 * </ul>
 *
 * <p>마지막 것 때문에 일시적 오류로도 앱이 안 뜰 수 있다. 그래도 던지는 쪽을 고른 이유는,
 * {@link SaveWatcher#start()} 가 빈 초기화 시점에 이미 같은 검사를 해서 폴더가 잘못됐으면
 * 여기까지 오지도 못하기 때문이다 — 기동 정책은 이미 "폴더가 성해야 뜬다" 다. 여기서만
 * 관대하게 굴면 정책이 두 갈래가 된다. 실행 중 오류를 감시가 살아남는 쪽으로 처리하는 것
 * ({@code OVERFLOW} 가지)과는 다른 문제다. 그때는 이미 돌던 워처를 잃는 쪽이 더 나쁘다.
 */
public class StartupCatchUp implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupCatchUp.class);

    private final SaveWatcher saveWatcher;

    public StartupCatchUp(SaveWatcher saveWatcher) {
        this.saveWatcher = saveWatcher;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("기동 시 따라잡기 — 세이브 폴더의 슬롯을 전부 다시 훑는다");
        saveWatcher.rescan();
    }
}
