-- 팀 신원을 세이브에서 가져온다 (2026-08-29, decision.md D56)
--
-- D55 는 팀 이름을 common.data 에서 읽었다. 그건 "지금 프로필" 의 로스터라
-- 사용자가 게임에서 커스터마이즈하면 갈린다 — 지나간 커리어의 이름이 아니다.
--
-- 세이브 안에 TeamInfo 가 있고 커리어 시점의 신원을 그대로 담고 있다:
--   ID=0  NameKey='Ember scale'            UseKey=true   → 이름 그 자체
--   ID=35 NameKey='team.name.pro.team8'    UseKey=false  → 로컬라이제이션 키
--
-- 그래서 키를 저장하고, 표시 이름은 시드에서 찾는다. 시드가 틀려도 UPDATE 한 줄로
-- 고쳐진다 — 이름은 표시용인데 적재를 오염시킬 이유가 없다.

SET client_encoding = 'UTF8';


-- 1. team 에 로컬라이제이션 키를 둔다
--
-- name 은 "표시할 이름"(해석 결과), name_key 는 "세이브가 말한 것"(원본 사실)이다.
-- 둘을 나눠야 시드를 고쳤을 때 무엇을 다시 계산해야 하는지가 분명해진다.
ALTER TABLE team ADD COLUMN name_key text;

COMMENT ON COLUMN team.name_key IS
  '세이브 TeamInfo.NameKey. UseKey=false 일 때의 로컬라이제이션 키다. 커스텀 이름이면 NULL (D56)';
COMMENT ON COLUMN team.name IS
  '표시할 팀 이름. 커스텀이면 세이브에서 그대로, 아니면 team_name_seed 에서 해석한 값 (D56)';


-- 2. 로컬라이제이션 키 → 이름 시드
--
-- 게임 에셋(sharedassets0.assets)에는 키만 있고 표시 문자열이 없어 뽑아내지 못했다.
-- 사용자가 게임 화면에서 읽어 준 목록을 시드로 쓴다. 검증: pro.team8 = KT Rolster Bullets
-- (그 팀이 실측 커리어에서 66세트를 뛴 35번이다 — 사용자 확인).
--
-- tier 는 리그 단계다. 화면에서 팀을 묶는 축이고, 실측상 표본 크기와 직결된다:
-- 플레이어가 속한 리그는 팀당 41~66세트, worlds 는 3~18세트다.
CREATE TABLE team_name_seed (
  name_key text PRIMARY KEY,
  name     text     NOT NULL,
  league   text     NOT NULL,          -- amateur · semi_pro · pro2 · pro · worlds
  tier     smallint NOT NULL,          -- 1=아마추어 … 5=월드. 정렬용
  seq      smallint NOT NULL,          -- 리그 안 순번 (teamN 의 N)
  UNIQUE (league, seq)
);

COMMENT ON TABLE team_name_seed IS
  '로컬라이제이션 키 → 표시 이름. 게임 에셋에서 못 뽑아 손으로 넣었다. 틀리면 여기만 고친다 (D56)';

INSERT INTO team_name_seed (name_key, name, league, tier, seq) VALUES
  ('team.name.amateur.team1',  'Seorabal Gaming',      'amateur',  1,  1),
  ('team.name.amateur.team2',  'OZ Gaming',            'amateur',  1,  2),
  ('team.name.amateur.team3',  'Runaway',              'amateur',  1,  3),
  ('team.name.amateur.team4',  'hyFresh Blade',        'amateur',  1,  4),
  ('team.name.amateur.team5',  'ESC Ever',             'amateur',  1,  5),
  ('team.name.amateur.team6',  'Element Mystic',       'amateur',  1,  6),
  ('team.name.amateur.team7',  'Spear Gaming',         'amateur',  1,  7),

  ('team.name.semi_pro.team1',  'Anarchy',             'semi_pro', 2,  1),
  ('team.name.semi_pro.team2',  'Nongshim Redforce',   'semi_pro', 2,  2),
  ('team.name.semi_pro.team3',  'Team Dynamics',       'semi_pro', 2,  3),
  ('team.name.semi_pro.team4',  'Sandbox Gaming',      'semi_pro', 2,  4),
  ('team.name.semi_pro.team5',  'BBQ Olivers',         'semi_pro', 2,  5),
  ('team.name.semi_pro.team6',  'Jin Air Greenwings',  'semi_pro', 2,  6),
  ('team.name.semi_pro.team7',  'Ever8 Winners',       'semi_pro', 2,  7),
  ('team.name.semi_pro.team8',  'Awesome Spear',       'semi_pro', 2,  8),
  ('team.name.semi_pro.team9',  'Kongdoo Monster',     'semi_pro', 2,  9),
  ('team.name.semi_pro.team10', 'SBENU Sonicboom',     'semi_pro', 2, 10),

  ('team.name.pro2.team1',  'APK Prince',              'pro2',     3,  1),
  ('team.name.pro2.team2',  'Liiv Sandbox',            'pro2',     3,  2),
  ('team.name.pro2.team3',  'Incredible Miracle',      'pro2',     3,  3),
  ('team.name.pro2.team4',  'KSV Esports',             'pro2',     3,  4),
  ('team.name.pro2.team5',  'KT Rolster',              'pro2',     3,  5),
  ('team.name.pro2.team6',  'Late Lifebuoys',          'pro2',     3,  6),
  ('team.name.pro2.team7',  'Kwangdong Freecs',        'pro2',     3,  7),
  ('team.name.pro2.team8',  'Dplus KIA',               'pro2',     3,  8),
  ('team.name.pro2.team9',  'BRION',                   'pro2',     3,  9),
  ('team.name.pro2.team10', 'Hanwha Life Esports',     'pro2',     3, 10),

  ('team.name.pro.team1',  'T1',                       'pro',      4,  1),
  ('team.name.pro.team2',  'Damwon Gaming',            'pro',      4,  2),
  ('team.name.pro.team3',  'Griffin',                  'pro',      4,  3),
  ('team.name.pro.team4',  'OGN Entus',                'pro',      4,  4),
  ('team.name.pro.team5',  'Tigers',                   'pro',      4,  5),
  ('team.name.pro.team6',  'DRX',                      'pro',      4,  6),
  ('team.name.pro.team7',  'Longzhu IM',               'pro',      4,  7),
  ('team.name.pro.team8',  'KT Rolster Bullets',       'pro',      4,  8),
  ('team.name.pro.team9',  'Afreeca Freecs',           'pro',      4,  9),
  ('team.name.pro.team10', 'Kingzone DragonX',         'pro',      4, 10),

  ('team.name.worlds.team1',  'SK Telecom T1',         'worlds',   5,  1),
  ('team.name.worlds.team2',  'Gen.G',                 'worlds',   5,  2),
  ('team.name.worlds.team3',  'DWG KIA',               'worlds',   5,  3),
  ('team.name.worlds.team4',  'Longzhu Gaming',        'worlds',   5,  4),
  ('team.name.worlds.team5',  'ROX Tigers',            'worlds',   5,  5),
  ('team.name.worlds.team6',  'MVP Ozone',             'worlds',   5,  6),
  ('team.name.worlds.team7',  'Azubu Frost',           'worlds',   5,  7),
  ('team.name.worlds.team8',  'Azubu Blaze',           'worlds',   5,  8),
  ('team.name.worlds.team9',  'MiG',                   'worlds',   5,  9),
  ('team.name.worlds.team10', 'Najin Black Sword',     'worlds',   5, 10),
  ('team.name.worlds.team11', 'Najin White Shield',    'worlds',   5, 11),
  ('team.name.worlds.team12', 'Samsung Galaxy',        'worlds',   5, 12),
  ('team.name.worlds.team13', 'CJ Entus Frost',        'worlds',   5, 13),
  ('team.name.worlds.team14', 'KT Rolster Arrows',     'worlds',   5, 14),
  -- worlds 는 15팀인데 받은 목록이 14개였다. 빠진 하나를 사용자가 Afreeca Freecs 로
  -- 지목했다. 다만 목록 안에서의 위치는 확인되지 않았으므로 끝에 둔다 —
  -- 어긋나 있으면 이 행 하나만 고치면 된다. worlds 는 표본이 3~18세트라
  -- 화면 영향도 가장 작다.
  ('team.name.worlds.team15', 'Afreeca Freecs',        'worlds',   5, 15)
ON CONFLICT (name_key) DO NOTHING;


-- 시드가 52개인지 확인한다. 조용히 모자라면 팀 절반이 이름 없이 남는다.
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM team_name_seed;
  IF n <> 52 THEN
    RAISE EXCEPTION '팀 이름 시드가 52개여야 하는데 %개다', n;
  END IF;
END $$;
