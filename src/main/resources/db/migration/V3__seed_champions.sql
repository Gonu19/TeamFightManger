-- 챔피언 40종 시드 (2026-08-25)
--
-- 세이브 파일에는 챔피언→역할군 매핑이 없다. 게임 에셋(ItemChampionCategoryConfig)에 있는데
-- Unity 직렬화 바이너리라 자동 추출이 안 된다. 40종뿐이고 거의 변하지 않는 정적 데이터라
-- 마이그레이션으로 넣는다.
--
-- 여기가 유일한 원본이다. 적재는 이 표에 없는 챔피언 이름을 만나면 던진다 —
-- 건너뛰면 경기가 3명이 되어 조용히 통계를 오염시킨다.
--
-- code 는 세이브 파일에 그대로 들어 있는 값이고, 진영 매칭이 이 값으로 이뤄진다 (D20).

SET client_encoding = 'UTF8';

INSERT INTO champion (code, name_ko, category) VALUES
  ('Fighter', '격투가', 'MELEE'),
  ('Knight', '기사', 'MELEE'),
  ('Swordman', '검사', 'MELEE'),
  ('Berserker', '광전사', 'MELEE'),
  ('MagicKnight', '마검사', 'MELEE'),
  ('ShieldBearer', '방패병', 'MELEE'),
  ('Lancer', '창술사', 'MELEE'),
  ('DuelBlader', '듀얼 블레이더', 'MELEE'),
  ('Ogre', '오우거', 'MELEE'),
  ('Jiangshi', '강시', 'MELEE'),
  ('Archer', '궁수', 'RANGER'),
  ('Sniper', '소총수', 'RANGER'),
  ('BoomerangHunter', '부메랑 헌터', 'RANGER'),
  ('PoisonDartHunter', '독침술사', 'RANGER'),
  ('Gambler', '도박사', 'RANGER'),
  ('Gunner', '총잡이', 'RANGER'),
  ('Dancer', '무희', 'RANGER'),
  ('Pyromancer', '화염술사', 'MAGICIAN'),
  ('IceMage', '얼음술사', 'MAGICIAN'),
  ('LightningMage', '번개술사', 'MAGICIAN'),
  ('Necromancer', '네크로맨서', 'MAGICIAN'),
  ('Illusionist', '환영술사', 'MAGICIAN'),
  ('Shadowmancer', '그림자술사', 'MAGICIAN'),
  ('DarkMage', '흑마술사', 'MAGICIAN'),
  ('Monk', '몽크', 'PRIEST'),
  ('Priest', '성직자', 'PRIEST'),
  ('Pythoness', '무녀', 'PRIEST'),
  ('PlagueDoctor', '역병의사', 'PRIEST'),
  ('BarrierMagician', '결계사', 'PRIEST'),
  ('Bard', '음유시인', 'PRIEST'),
  ('Chef', '요리사', 'PRIEST'),
  ('Exorcist', '엑소시스트', 'PRIEST'),
  ('Taoist', '도사', 'PRIEST'),
  ('Ninja', '닌자', 'ASSASSIN'),
  ('Ghost', '유령', 'ASSASSIN'),
  ('Demon', '악마', 'ASSASSIN'),
  ('Vampire', '흡혈귀', 'ASSASSIN'),
  ('Executioner', '처형인', 'ASSASSIN'),
  ('Clown', '광대', 'ASSASSIN'),
  ('Werewolf', '늑대인간', 'ASSASSIN')
ON CONFLICT (code) DO NOTHING;

-- 40종이 전부 들어갔는지 확인한다. 조용히 모자란 채로 넘어가면
-- 적재가 한참 뒤에 "시드에 없는 챔피언" 으로 넘어진다.
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM champion;
  IF n <> 40 THEN
    RAISE EXCEPTION '챔피언이 40종이어야 하는데 %종이다', n;
  END IF;
END $$;
