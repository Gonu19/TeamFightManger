> **답하는 질문:** 지금 무엇이 돌고, 무엇을 하는 중이고, 무엇이 막혀 있나
> **읽을 때:** 세션 시작 직후. 이 파일과 `decisions/README.md` 라우팅 표면 방향이 잡힌다
> **크기:** 3KB 이하로 유지한다. `RUNBOOK.md` 와 같은 한도이고, 둘을 합친 6KB 가
> 세션 시작의 정액 예산이다. 넘으면 내용이 `decisions/` 로 갈 때가 된 것이다

# 지금 상태 (2026-08-30)

## 도는 것

| 계층 | 상태 |
|---|---|
| `parser/` | 세이브 파싱 완료. 경기·팀·선수·**매치 일정** |
| `ingest/` | 적재 완료. 운영 DB 팀 56 · 공식 698건 · 선수 실명 |
| `analysis/` | 카운터·티어 집계 완료. **시너지는 미착수** |
| `story/` | 사실→해석→창작→대조→저장까지 이어짐(`ArticleWriter`). 팀 실명 붙음 |
| `web/` | **비어 있음** — 파일 0개 |
| `draft/` | 비어 있음 (`banpick.md` 가 설계) |

테스트 **347개** (DB 없이 280 · DB 필요 67). 마이그레이션 `V1`~`V8` **전부 적용됨**.
결정 **D1~D65**.

## 진행 중

**화면.** 자바는 `ArticleWriter` 까지 왔다. 남은 것은 화면뿐이다:
`web/StoryController` → `templates/story/list.html`·`detail.html` → `static/tfm.css`.
설계는 `architecture.md` 5·6절과 `decisions/D61_*.md`.

기사를 뽑으려면 `tfm.story.enabled=true` 여야 한다 — 꺼지면 빈이 아예 없다.

## 막힌 것

- **`.env` → Spring 배선이 미검증이다.** 앱을 띄워야 확인된다 (키는 실호출로 확인됨)
- **`tools/` 실호출 스크립트가 임시 파일에만 있다.** 저장소에 넣으려면 `gradlew test` 가
  네트워크를 안 타도록 opt-in 으로 만들어야 한다
- **`bootRun`·psql 은 사용자가 돌린다.** 테스트는 `$env:TFM_DB_PASSWORD` 로 에이전트도 된다

## 최근에 뒤집힌 것 (오래된 문서를 믿기 전에 본다)

- **시너지·카운터는 승패가 아니라 경기력에서 본다** (D63). 805경기로도 t<2 였던 것이
  `champ_stat` 으로 보면 150경기에서 잡힌다
- **챔피언 쌍은 역할군의 다른 이름이 아니다** (D64). 다만 지표마다 부호의 뜻이 다르다 —
  카운터는 `death` 로 본다. `dealing` 의 상대 효과는 "흡수 성향" 이다
- **없는 게 아니라 안 보이는 것이다** (D62). D60 의 "2인만 실재한다" 를 그렇게 읽으면 안 된다
- **Groq 카탈로그에 llama 계열이 없다.** 기본 모델은 `openai/gpt-oss-120b`.
  `architecture.md` 의 옛 모델명을 믿고 호출하면 404 다
- **`@Repository` 의 인자 검증은 `IllegalArgumentException` 으로 안 나간다.** 예외 변환이
  `InvalidDataAccessApiUsageException` 으로 바꾼다(원인은 남는다). DAO 를 부르는 쪽에서 걸린다

## 이 파일의 규칙

- **커밋할 때 같이 고친다.** 낡으면 없느니만 못하다
- 여기에는 **상태만** 쓴다. 이유는 `decisions/`, 구조는 `architecture.md` 다
