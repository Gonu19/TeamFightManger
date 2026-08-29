---
title: "D18. 현재 날짜는 `TodayData.Time` 에서 읽는다"
---

## D18. 현재 날짜는 `TodayData.Time` 에서 읽는다

**발견 경위.** 이벤트전을 여러 번 치렀는데 워처가 찍는 최대 Day 가 41에서 안 움직였다.

**원인.** 최대 Day 를 `max(GameStat.Day)` 로 구했기 때문이다. 이벤트전은 GameStat 을
남기지 않고(D16), 스크림에는 Day 가 없다. 그래서 **공식 경기가 없는 날은 이 값이 멈춘다.**

```
TodayData.Time     : S2025 Day 46 18:00   ← 실제 현재 날짜
max(GameStat.Day)  : 41                   ← 5일 뒤처짐
진행완료 스케줄 최대 : Day 45
```

**결정.** 현재 게임 내 날짜는 `TodayData.<Time>k__BackingField` (SeasonTime) 에서 읽는다.
`TodayData` 는 세이브에 1개뿐이고 `Hour`/`Minute` 까지 있다.

**중요한 이유.** D8 의 스크림 타임스탬프 보정이 이 값에 의존한다. GameStat 기반으로
날짜를 추정했다면 스크림에 최대 5일 틀린 날짜가 붙었을 것이다.

---

