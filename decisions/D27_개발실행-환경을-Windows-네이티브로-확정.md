---
title: "D27. 개발·실행 환경을 Windows 네이티브로 확정 (2026-08-25)"
---

## D27. 개발·실행 환경을 Windows 네이티브로 확정 (2026-08-25)

**상황.** D23 이 Docker 대신 WSL 로컬 Postgres 를 골랐다. 사용자가 WSL 을 쓰지 않기로 했다.

**확인한 것.** Windows 에서 필요한 것이 전부 된다.

| 항목 | 상태 |
|---|---|
| Java | 21.0.10 LTS 설치됨 |
| 세이브 파일 | `%USERPROFILE%\AppData\LocalLow\samoyed\Teamfight Manager\` 3개 (2.6~2.75MB) |
| 게임 설치 | `E:\SteamLibrary\steamapps\common\Teamfight Manager\` |
| PostgreSQL | **없음. 설치 필요** (winget 사용 가능) |

**결정.** 개발·빌드·실행·테스트 전부 Windows 네이티브. WSL 을 쓰지 않는다.

**이 결정이 오히려 원래 설계와 잘 맞는다.** D22 에서 확인했듯 워처는 세이브 파일이 있는
곳에서 돌아야 하고, 그 파일은 Windows 에 있다. WSL 에서 `/mnt/c` 로 읽는 것은
경유일 뿐이고 파일 변경 알림이 9p 계층을 건너오면서 지연·유실될 수 있다.
**워처를 Windows JVM 에서 돌리면 `WatchService` 가 네이티브 알림을 그대로 받는다.**

**대가와 대응.**

| 대가 | 대응 |
|---|---|
| `tools/` 의 Python 레퍼런스 실행 | Windows Python 으로 돌린다. 스크립트가 OS 의존이 없다 |
| 경로에 공백 (`Teamfight Manager`) | 설정·인자에서 항상 인용. 테스트 픽스처 경로도 공백 포함으로 둔다 |
| 파일 잠김 | Windows 는 Linux 보다 공유 위반이 잘 난다. 세이브를 열 때 공유 읽기로 연다 |
| 줄바꿈 | 골든 JSON 비교가 CRLF 에 깨지지 않게 파일을 바이트로 읽거나 LF 로 정규화 |
| 인코딩 | psql 의 `client_encoding` 이 콘솔 코드페이지(한글 Windows = UHC)를 따라간다. UTF-8 SQL 파일의 한글 주석에서 적재가 멈춘다 — `V1__init.sql` 첫머리에 `SET client_encoding = 'UTF8'` 을 박아뒀다 |
| 콘솔 출력 | 위를 UTF8 로 고정하면 이번엔 CP949 콘솔에서 결과의 한글이 깨진다. 실행 전 `chcp 65001` 로 콘솔을 UTF-8 로 맞춘다. 표시 문제일 뿐 데이터는 정상이다 |

**뒤집힐 조건.** 없다. 워처가 파일 옆에 있어야 한다는 제약이 이 방향을 강제한다.

---

