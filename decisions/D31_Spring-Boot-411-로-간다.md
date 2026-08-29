---
title: "D31. Spring Boot 4.1.1 로 간다 — 3.x 는 선택지가 아니다 (2026-08-25)"
---

## D31. Spring Boot 4.1.1 로 간다 — 3.x 는 선택지가 아니다 (2026-08-25)

**상황.** `project.md` 제약이 "Spring Boot 3.x" 였다. Initializr 가 제공하는 목록을 확인했다.

```
4.2.0 (SNAPSHOT) · 4.2.0 (M1) · 4.1.2 (SNAPSHOT) · 4.1.1 · 4.0.9 (SNAPSHOT) · 4.0.8
```

**3.x 가 없다.** 제약이 이미 만족 불가능한 상태였다.

**결정.** **4.1.1.** 목록에서 최신 GA(정식 릴리스)다.

**SNAPSHOT·마일스톤을 쓰지 않는 이유.** SNAPSHOT 은 같은 버전 번호인데 내용이 매일 바뀐다.
어제 되던 빌드가 오늘 깨져도 내 코드는 그대로라 원인을 못 찾는다.
**이 프로젝트는 골든 파일로 "결과가 언제나 같아야 한다"를 강제하는 구조다**(D22).
토대가 매일 바뀌면 그 검증이 무의미해진다. M1 도 API 가 아직 움직인다.

**실제로 빌드해서 확인한 것.**

| | |
|---|---|
| Spring Boot | 4.1.1 |
| Spring Framework | 7.0.9 |
| Thymeleaf | 3.1.5 |
| Gradle | 9.5.1 |
| Java | 21.0.10 |
| 결과 | `compileJava` · `bootJar` · `build` 전부 성공 |

**Gradle 은 받지 않았다.** `winget` 에 Gradle 패키지가 없었는데,
`~/.gradle/wrapper/dists` 에 9.4.1 과 9.5.1 이 이미 있었다(다른 프로젝트가 받아둔 것).
그걸로 wrapper 를 생성했다. 이제 `gradlew.bat` 하나로 돌아간다.

**남는 위험.** Boot 4 는 Framework 7 · Hibernate 7 · Jackson 3 을 끌고 온다.
학습 자료 대부분이 아직 Boot 3 기준이라 **문서와 실물이 어긋나는 순간이 온다.**
특히 Postgres ENUM 의 JPA 매핑(D22 가 이미 번거롭다고 지적한 곳)은 Hibernate 버전에 민감하다.
막히면 자료를 의심하지 말고 실제 API 를 먼저 확인한다.

**위험이 즉시 현실이 됐다 — Flyway 가 조용히 안 돌았다.**

Boot 3 에서는 `flyway-core` 만 넣으면 마이그레이션이 자동으로 실행된다.
Boot 4 는 **자동설정을 기술별 모듈로 쪼갰다**(`spring-boot-hibernate`, `spring-boot-jdbc`,
`spring-boot-flyway` …). `flyway-core` 만 있으면 Flyway 라이브러리는 클래스패스에 있지만
**자동설정이 없어서 아무 일도 일어나지 않는다.**

**오류도 경고도 없다.** 로그에 Flyway 가 한 줄도 안 나오는 것이 유일한 증상이었다.
눈치채지 못했으면 나중에 "왜 테이블이 없지"부터 거꾸로 파야 했을 것이다.
`org.springframework.boot:spring-boot-starter-flyway` 를 추가해서 해결했다.

**교훈.** Boot 4 에서 어떤 기능이 "그냥 안 도는" 증상을 보이면 라이브러리가 아니라
**자동설정 모듈이 빠졌는지를 먼저 본다.** 로그의 침묵이 단서다.

**부팅 확인 (2026-08-25).**

| | |
|---|---|
| Flyway | `Successfully applied 1 migration to schema "public", now at version v1` |
| Hibernate | 7.4.5.Final · PostgreSQLDialect · `tfm/public` |
| Tomcat | 11.0.24, 포트 **8088** |
| 기동 시간 | 7.2초 |

**포트를 8088 로 옮겼다.** 8080 은 로컬 LLM 서버(`llama-server`)가 쓰고 있다.
`${TFM_PORT:8088}` 이라 환경변수로 바꿀 수 있다.

**뒤집힐 조건.** 없다. 3.x 로 돌아갈 방법이 없다.

---

---

