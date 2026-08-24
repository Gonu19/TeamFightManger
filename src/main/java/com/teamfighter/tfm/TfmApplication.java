package com.teamfighter.tfm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 팀파이트 매니저 티어 분석.
 *
 * <p>워처 · 집계 · 웹이 한 프로세스에서 뜬다. 세이브 파일이 사용자 PC 에 있어서
 * 워처가 파일 옆에 있어야 하고, 그래서 클라우드 배포가 아니라 로컬 {@code java -jar} 다 (D22/D27).
 */
@SpringBootApplication
@EnableScheduling
public class TfmApplication {

    public static void main(String[] args) {
        SpringApplication.run(TfmApplication.class, args);
    }
}
