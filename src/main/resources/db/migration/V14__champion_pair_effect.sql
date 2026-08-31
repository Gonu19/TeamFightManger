-- 방향 있는 챔피언 쌍 효과 (2026-09-01, decisions/D63 · D64 · D65)
--
-- 시너지와 카운터를 <b>승패가 아니라 출력</b>으로 잰 결과가 여기 들어온다.
--
-- 왜 새 표인가. 기존 `champion_matchup`·`synergy_combo` 는 승률 기반이고 무방향이다.
-- D63 이 그 둘을 한꺼번에 뒤집었다:
--
--   ① 관측 단위를 바꾼다 — 경기 하나가 승패로는 1비트지만 출력으로는 8명 × 6지표다.
--      같은 805경기에서 시너지 t 가 0.75~1.65 → 15.60 으로 올랐다. 자릿수가 다르다.
--   ② 효과에 <b>방향이 있다</b> — `A←B` 와 `B←A` 는 다른 값이다. 승패로는 `A+B` 가
--      한 덩어리라 누가 누구를 살렸는지 알 수 없었다.
--
-- 그래서 기존 표를 고치지 않고 옆에 세운다. 승률 기반은 표본이 쌓인 뒤 교차검증용으로
-- 남긴다(D63 결정 1) — 지우면 그 대조를 영영 못 한다.

SET client_encoding = 'UTF8';


-- 어느 지표의 효과인가.
--
-- <b>지표마다 부호의 뜻이 다르다</b>(D64 결정 3). `DEALING` 의 상대 효과가 양수인 것은
-- "그 상대가 내 딜을 흡수해 준다" 는 뜻이지 "내가 저 챔피언에게 강하다" 가 아니다.
-- 카운터의 뜻에 맞는 것은 `DEATH` 다 — 양수면 "그 상대를 만나면 더 죽는다".
--
-- 그래서 지표를 골라 저장하지 않고 <b>전부</b> 저장한다. 화면이 지표 하나가 아니라
-- 지표 묶음을 보여주기 때문이다(D65 결정 1) — 같은 죽음 증가라도 함께 오는 값이
-- 다르면 다른 현상이다.
CREATE TYPE perf_metric AS ENUM ('DEALING', 'TANKING', 'HEALING', 'KILL', 'DEATH', 'ASSIST');

-- 상대가 어느 편에 있었나.
--   ALLY  같은 팀 (시너지)
--   FOE   맞은편 (카운터)
CREATE TYPE pair_side AS ENUM ('ALLY', 'FOE');


CREATE TABLE champion_pair_effect (
  scope         agg_scope NOT NULL,
  slot_id       int REFERENCES save_slot(slot_id) ON DELETE CASCADE,
  patch_id      int REFERENCES patch(patch_id) ON DELETE CASCADE,
  include_scrim boolean NOT NULL DEFAULT false,

  side          pair_side NOT NULL,

  -- 누구의 출력이 달라지는가. `subject` 가 값을 받는 쪽이다.
  subject_champion_id int NOT NULL REFERENCES champion(champion_id),
  -- 옆에(또는 맞은편에) 있는 챔피언.
  other_champion_id   int NOT NULL REFERENCES champion(champion_id),

  metric        perf_metric NOT NULL,

  -- 효과 크기. <b>단위는 σ</b> 다 — 그 챔피언의 그 지표 표준편차 몇 배만큼 달라지는가.
  --
  -- 승률로 옮기지 않는다(D63 결정 5). "딜이 오른다" 는 "이긴다" 가 아니고, 그 변환에는
  -- 아직 없는 회귀식(D19 미결)이 필요하다. <b>출력은 출력으로만 말한다.</b>
  effect        numeric(6,3) NOT NULL,

  -- 이 쌍이 실제로 함께 나온 횟수.
  --
  -- <b>화면이 반드시 같이 보여줘야 하는 값이다.</b> 교차검증은 "효과가 있다" 까지만
  -- 말했고, 개별 쌍의 크기는 표본이 얇으면 그만큼 흔들린다. D13·D60 의 "판정 불가"
  -- 규칙이 여기에 그대로 걸린다.
  observations  int NOT NULL CHECK (observations > 0),

  -- 자기 자신과의 쌍은 없다. 같은 챔피언이 한 경기에 둘 나오지 않는다
  -- (`match_participant_unique_champ` 가 그것을 이미 막는다).
  CONSTRAINT champion_pair_effect_not_self CHECK (subject_champion_id <> other_champion_id),

  -- GLOBAL 행은 slot_id 도 patch_id 도 NULL 이다(D24). 보통의 UNIQUE 는 NULL 을 서로
  -- 다른 값으로 보므로 그대로 두면 같은 쌍이 무한히 쌓인다 — 다른 집계 표와 같은 절이다.
  CONSTRAINT champion_pair_effect_key UNIQUE NULLS NOT DISTINCT
      (scope, slot_id, patch_id, include_scrim, side, subject_champion_id,
       other_champion_id, metric)
);

-- 화면은 "이 챔피언의 동료 효과 / 상대 효과" 를 지표별로 한꺼번에 읽는다.
CREATE INDEX champion_pair_effect_subject_idx
  ON champion_pair_effect (scope, slot_id, subject_champion_id, side, metric);

-- 역시너지 경고와 상위 쌍 목록은 <b>효과 크기 순</b>으로 훑는다 (D65 결정 2).
CREATE INDEX champion_pair_effect_size_idx
  ON champion_pair_effect (scope, slot_id, metric, side, effect);


COMMENT ON TABLE champion_pair_effect IS
  '출력 기반 방향 있는 쌍 효과. 승률이 아니다 (D63)';
COMMENT ON COLUMN champion_pair_effect.effect IS
  'σ 단위. 승률로 옮기지 않는다 — 그 변환은 D19 미결이다 (D63 결정 5)';
COMMENT ON COLUMN champion_pair_effect.observations IS
  '이 쌍이 함께 나온 횟수. 화면이 반드시 같이 보여준다 (D13·D60 판정 불가 규칙)';
