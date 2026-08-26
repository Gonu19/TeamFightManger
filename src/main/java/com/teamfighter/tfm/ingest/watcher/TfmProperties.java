package com.teamfighter.tfm.ingest.watcher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * {@code application.yml} 의 {@code tfm:} 블록.
 *
 * <p>필드와 getter/setter 뿐이다 — 로직은 여기 없다. 값의 <b>해석</b>(플레이스홀더 치환,
 * 공백 포함 경로 바인딩)은 Spring 의 {@code Binder} 가 한다. 그 바인딩 계약은
 * {@code TfmPropertiesTest} 가 못 박는다.
 *
 * <p><b>{@code Path} 바인딩은 별도 컨버터 없이 된다.</b> 처음엔 Spring 코어에
 * {@code String}→{@code Path} 변환기가 없다고 봤는데, 실제로 돌려보니 5개 바인딩 테스트가
 * 전부 통과한다. 추측이 아니라 {@code TfmPropertiesTest} 가 근거다.
 */
@Component
@ConfigurationProperties(prefix = "tfm")
public class TfmProperties {

    private Path saveDir;
    private long watchDebounceMs = 1500L;
    /**
     * 워처를 띄울지. 운영에서는 항상 켠다 — 끄면 이 앱이 하는 일이 없다.
     * 끄는 곳은 통합 테스트뿐이다: 테스트가 세이브 폴더 존재 여부에 묶이면
     * 이 PC 밖에서는 DB 테스트가 이유 없이 죽는다.
     */
    private boolean watchEnabled = true;
    /**
     * <b>선언만 있고 아무 데서도 강제되지 않는다.</b> 세이브 파일을 쓰는 코드가 애초에 없어서
     * 가드할 대상이 없다. 값을 바꿔도 동작은 하나도 안 바뀐다 — 이 플래그를 신뢰하지 마라.
     * 실제 보장은 {@code SaveWatcherWindowsShareTest} 의
     * {@code watcherAndResolver_neverWriteToSaveFile} 이 수정시각·해시로 확인한다.
     * 남길지 없앨지는 아직 결정되지 않았다.
     */
    private boolean readOnly = true;

    public Path getSaveDir() {
        return saveDir;
    }

    public void setSaveDir(Path saveDir) {
        this.saveDir = saveDir;
    }

    public long getWatchDebounceMs() {
        return watchDebounceMs;
    }

    public void setWatchDebounceMs(long watchDebounceMs) {
        this.watchDebounceMs = watchDebounceMs;
    }

    public boolean isWatchEnabled() {
        return watchEnabled;
    }

    public void setWatchEnabled(boolean watchEnabled) {
        this.watchEnabled = watchEnabled;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
}
