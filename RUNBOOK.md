> **답하는 질문:** 이걸 어떻게 돌리나
> **읽을 때:** 명령이 필요할 때. 외우거나 다시 만들지 않는다
> **크기:** 명령과 그 명령이 실패하는 방식만. 설명은 다른 문서로

# 실행

전부 **Windows PowerShell** 기준이다. WSL·Docker 를 쓰지 않는다 (D27).

## 테스트

```powershell
# 전체. DB 가 필요하다 — 따옴표 없으면 PowerShell 이 값을 명령으로 해석한다
$env:TFM_DB_PASSWORD = 'postgres'; .\gradlew.bat test

# DB 없이 도는 것만 (에이전트가 확인할 수 있는 범위)
.\gradlew.bat test --tests "com.teamfighter.tfm.parser.*" --tests "com.teamfighter.tfm.story.*"

# 하나만
.\gradlew.bat test --tests "*MatchBriefTest*" --console=plain
```

**결과 세는 법** — `build/reports/tests/test/index.html` 의 카운터를 본다.
`BUILD SUCCESSFUL` 만 보면 몇 개가 건너뛰어졌는지 모른다.

## 앱

```powershell
.\run.ps1                # 8088 · 생성 켜짐 · 브라우저까지 연다
.\run.ps1 -Port 8099     # 사용자 인스턴스를 둔 채 하나 더
.\run.ps1 -NoStory       # 생성 끄고 (회귀만 볼 때)
.\run.ps1 -Aggregate     # 기동 때 집계 한 번
.\run.ps1 -Reingest      # 수리용. Aggregate 와 같이 켜지 말 것
```

`run.bat` 은 탐색기에서 **더블클릭**용이다(같은 인자를 받는다). 스크립트가 뜨기 전에
<b>무엇을 쓰는지 먼저 찍는다</b> — DB 비번의 출처와, 키가 어디서 오는지(셸의 값이
`.env` 를 가리는지), 아예 없으면 그 사실까지.

**생성은 기본이 켬**이다. 앱 자체의 기본값은 여전히 꺼짐이고(D61 결정 4), 바꾼 것은
주인의 실행 스크립트다 — 이 파일을 쓰는 사람은 켜려고 띄운다.

화면: `/tier` · `/champion/{code}` · `/story`(사이클) · `/gallery`
생성은 요청 밖에서 돌고 진행 막대가 단계를 그린다. **커리어당 하나만 돈다** (D81).

<details><summary>스크립트 없이 (무엇을 하는지 봐야 할 때)</summary>

```powershell
$env:TFM_DB_PASSWORD = 'postgres'; .\gradlew.bat bootRun --console=plain --args="--server.port=8088"
```
</details>

**Aggregate 와 Reingest 를 같이 켜면 안 되는 이유**: 집계가 따라잡기 적재보다 항상
1초 먼저 끝난다 (`decisions/OPEN.md`). 스크립트가 경고만 하고 막지는 않는다.

## DB

```powershell
& 'C:\Program Files\PostgreSQL\16\bin\psql.exe' -U postgres -d tfm -c "SELECT game_team_id, name FROM team WHERE slot_id=1 ORDER BY game_team_id;"
```

DB 는 `tfm`(운영) / `tfm_test`(테스트). 스키마의 주인은 Flyway 다 — Hibernate 는 `validate` 만 한다.

## 측정 도구 (DB·키 불필요. 골든 파일만 읽는다)

```bash
python tools/perf_role_control.py --metric death --top 10   # 시너지·카운터 (D64)
python tools/synergy_holdout.py --seed 1                    # held-out 예측 (D63)
python tools/synergy_power.py --repeats 8                   # 검정력 (D62)
python tools/patch_weight_analysis.py                       # 패치 감쇠 (D52)
```

## 자주 밟는 지뢰

| 증상 | 진짜 원인 |
|---|---|
| Postgres `28P01` 인증 실패 | `TFM_DB_PASSWORD` 가 그 셸에 없다. 코드 문제가 아니다 |
| 한글 로그가 깨진다 | `chcp 65001` |
| `.env` 값이 안 먹는다 | 빈 값(`TFM_X=`)은 "없음" 이 아니라 "빈 문자열" 이라 `${TFM_X:기본값}` 을 누른다 |
| 픽스처 테스트가 조용히 건너뛰어진다 | 워크트리에 `fixtures/*.tfm` 이 없다. 메인에만 있다 |
| 모델 호출이 404 | 모델명이 낡았다. `python`으로 `/v1/models` 를 조회해 카탈로그를 확인한다 |
| 생성이 401 로 실패한다 | 셸의 `TFM_GROQ_API_KEY` 가 `.env` 를 가린다. `run.ps1` 이 시작할 때 어느 쪽을 쓰는지 찍는다 |
| 포트가 이미 쓰인다 | 사용자 인스턴스가 떠 있다. `-Port` 로 다른 값을 준다 |

## 절대 하지 않는 것

- 적용된 마이그레이션(`V1`~`V8`) 수정 — 주석 한 글자도 체크섬을 깬다
- `git push` — 원격은 사용자가 수동으로
- 라이브 세이브를 `fixtures/` 에 복사한 채로 두기 — `GoldenFileTest` 가 깨진다
