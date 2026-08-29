---
title: "D1. 수집 방식: OCR → 세이브 파일 파싱"
---

## D1. 수집 방식: OCR → 세이브 파일 파싱

**상황.** 최초 기획은 경기 결과 스크린샷 OCR이었다.

**확인한 것.** `%USERPROFILE%\AppData\LocalLow\samoyed\Teamfight Manager\slot_*.tfm` 가
.NET BinaryFormatter 평문이었다. 암호화도 압축도 없고 필드명이 파일에 그대로 있었다.
`GameStat` 안에 `BlueBan / BluePick / RedBan / RedPick / WinTeam` 이 전부 들어 있었다.

**근거.**

| | OCR | 세이브 파싱 |
|---|---|---|
| 경기 1건 확보 비용 | 스크린샷 1장 + 사람 확인 1회 | 0 (이미 저장돼 있음) |
| 과거 데이터 | 없음. 0건에서 시작 | 슬롯 3개에 1,207경기가 이미 존재 |
| 정확도 | 100% 불가, 확인 단계 영구 필요 | 원본 그대로 |
| 챔피언 인식 | 초상화 아이콘이라 OCR이 아니라 이미지 매칭 필요 | 문자열로 저장됨 |

**결정.** 세이브 파일 파싱. OCR 관련 테이블(`ingest_image`, `ingest_field_review`,
`champion_alias`, `is_confirmed`)은 전부 삭제.

**뒤집힐 조건.** 게임 업데이트로 세이브가 암호화되면 OCR로 되돌아가야 한다.

---

