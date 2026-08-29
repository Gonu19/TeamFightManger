---
title: "D22. 스택을 Spring Boot + Thymeleaf/HTMX 로 전환 (2026-08-11)"
---

## D22. 스택을 Spring Boot + Thymeleaf/HTMX 로 전환 (2026-08-11)

**상황.** 원래 목적이 Spring 학습이었다는 것이 뒤늦게 확인됐다. Next.js + Supabase 로
진행 중이었고 TS 파서 954줄(`lib/parser/*.ts`)과 골든 테스트가 나온 시점이었다.

**전환 시점이 저렴한 이유.**

- UI·DB·API 코드가 아직 없다. Next.js 의존성만 깔려 있고 `app/` 디렉토리가 없다
- 버려지는 것은 TS 파서 954줄뿐인데, 로직이 언어 무관이고 Python 원본(`tools/nrbf.py`)도 있어
  Java 는 3번째 구현이다
- **`tests/baseline/*.json` 골든 파일이 이미 있다.** Java 포팅의 정답지가 완성돼 있어 검증이 자동이다

**원래 계획에 있던 구멍도 드러났다.** 워처는 세이브 파일을 볼 수 있는 곳에서 돌아야 하는데
그 파일은 사용자 Windows PC 에 있다. Vercel 에 배포하면 워처가 파일을 못 본다.
**Next.js 계획도 결국 로컬 실행이 필요했다.** Spring 은 `java -jar` 하나로 워처·집계·웹이
전부 뜨므로 이 제약과 오히려 잘 맞는다.

**프론트엔드는 Thymeleaf + HTMX.** 선택 근거:

- 화면이 **데이터는 빽빽한데 상호작용은 가볍다.** 정렬·필터가 전부고 40행은 서버가 다시 그려도
  눈에 띄지 않는다. 드래그·복잡한 클라이언트 상태·폼이 없다
- 디자인 요구(표본에 따른 시각적 약화, 신뢰구간 막대)는 서버에서 클래스·폭을 계산해 내려보내면 된다
- SSE 실시간 갱신은 `hx-ext="sse"` 로 조각만 교체한다. 순수 Thymeleaf 여도 SSE 용 JS 는
  어차피 필요하므로 "JS 0" 은 선택지가 아니었다
- **학습 목적이 결정적이었다.** Thymeleaf 는 Spring MVC 를 통째로 쓰지만(뷰 리졸버, 모델 바인딩,
  Security 연동), REST + SPA 로 가면 Spring 은 `@RestController` 와 DTO 만 담당하게 되어
  Spring 학습량이 오히려 줄어든다
- 6명 에이전트가 붙어 있는 상황에서 프로젝트 1개 / 빌드 1개 / 배포 1개가 조율하기 쉽다

**DB 는 Postgres 를 유지한다.** `schema.sql` 이 Postgres 전용 기능을 네 개 쓴다 —
`GENERATED ALWAYS AS ... STORED`, `UNIQUE NULLS NOT DISTINCT`(PG15+), ENUM 타입 6개,
부분 유니크 인덱스. H2·SQLite 로 가면 넷 다 다시 짜야 한다. ~~Docker Compose~~ + Flyway.
→ **Docker Compose 부분은 D23 에서 폐기** (WSL 로컬 PG16), **그 WSL 도 D27 에서 폐기.**
최종은 Windows 네이티브 PG16 이다.

**JPA 로 집계하지 않는다.** 가중합·2단 축소·백분위 컷·`NULLS NOT DISTINCT` 업서트는
Hibernate 가 약한 영역이고 Postgres ENUM 매핑도 번거롭다. **적재는 JPA, 집계는 JdbcTemplate.**

**남는 위험.**

1. **분석 로직이 어려운 부분인데 언어와 무관하다.** Spring 보일러플레이트에 에너지를 다 쓰고
   정작 카운터·시너지 분석에 도달하지 못하는 것이 최대 위험이다. 그래서 `tools/` 의 Python
   스크립트를 레퍼런스 구현으로 유지하고 Java 결과를 계속 대조한다
2. **Java 의 `byte` 는 부호가 있다.** NRBF 는 unsigned 를 다루므로 `& 0xFF` 를 빼먹으면
   조용히 틀린다. ~~골든 JSON 이 잡아준다~~ → **틀린 기대였다. D34 참고**
3. 팀 6명이 이미 TS 궤도에 올라와 있어 재브리핑이 필요하다

**뒤집힐 조건.** 밴픽 시뮬레이터(2차)가 서버 왕복으로 감당이 안 될 만큼 무거워지면
그 화면만 클라이언트 렌더링으로 떼는 것을 검토한다. 지금은 픽 한 번에 서버 왕복 한 번이면
충분하다 — 추천값이 어차피 DB 에서 나온다.

---

