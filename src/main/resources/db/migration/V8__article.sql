-- 기사와 댓글 (2026-08-30, decisions/D61_*.md)
--
-- story/ 가 만든 것을 담는다. 3층 경계(사실·해석·창작)의 맨 위이고,
-- 여기 있는 어떤 값도 집계로 올라가지 않는다 — 집계 표는 이 표들을 조회하지 않는다.
--
-- 설계의 핵심은 brief_text 다. D61 결정 2 가 「이 기사가 쓴 숫자」 블록을
-- "생성 시점의 값으로 고정" 하라고 정했다. 나중에 집계가 갱신돼도 바뀌면 안 된다 —
-- 기사가 *그때* 무엇을 보고 썼는지가 남아야 검증이 되기 때문이다.
-- 그래서 참조가 아니라 텍스트를 통째로 박아 넣는다. 정규화의 예외이고, 의도한 것이다.

SET client_encoding = 'UTF8';


-- 대조 결과. 코드가 기사를 brief 와 맞춰본 결론이다 (FactCheck).
--   CLEAN        모순 없음. 그대로 실어도 된다
--   CONTRADICTED 모순이 있다. 화면이 경고를 띄운다
CREATE TYPE article_fact_status AS ENUM ('CLEAN', 'CONTRADICTED');

-- 지적의 심각도. 둘을 나누지 않으면 목록이 잡음으로 가득 차 아무도 안 본다.
--   CONTRADICTION brief 와 어긋난다
--   UNVERIFIED    brief 가 모른다. 틀렸다는 뜻이 아니다
CREATE TYPE article_finding_severity AS ENUM ('CONTRADICTION', 'UNVERIFIED');


CREATE TABLE article (
  article_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slot_id         int  NOT NULL REFERENCES save_slot(slot_id) ON DELETE CASCADE,

  -- 매치 신원. MatchSchedule.ID 는 대회마다 ID 공간이 따로라 단독으로는 유일하지 않다
  -- (실측 190건이 114개 값에 겹친다). 그래서 유일 키는 아래 UNIQUE 쪽이다.
  schedule_id     int,
  competition_id  int,
  competition_key text,
  season          int  NOT NULL,
  day             int  NOT NULL,
  round           int,

  -- 매치 기준 진영. 세트의 진영과 다를 수 있다 (실측 294세트 중 122세트가 반대다).
  blue_team_id    int  NOT NULL REFERENCES team(team_id),
  red_team_id     int  NOT NULL REFERENCES team(team_id),
  blue_score      smallint NOT NULL,
  red_score       smallint NOT NULL,
  blue_kill       int  NOT NULL,
  red_kill        int  NOT NULL,

  -- 해석층이 정한 분량. 왜 이 길이인지 사람이 볼 수 있게 이유도 남긴다
  notability      numeric(4,3) NOT NULL CHECK (notability BETWEEN 0 AND 1),
  notability_reasons text[] NOT NULL DEFAULT '{}',

  headline        text NOT NULL,
  body            text NOT NULL,

  -- BriefRenderer 가 만든 사실 블록 그대로. 모델이 본 것과 독자가 보는 것이
  -- 같은 문자열이어야 한다 — 갈리면 그 블록은 검증 장치가 아니라 장식이 된다.
  brief_text      text NOT NULL,

  -- 무엇이 이걸 썼나. 모델을 바꾸면 품질이 달라지므로 기사마다 남긴다
  model           text NOT NULL,
  generated_at    timestamptz NOT NULL DEFAULT now(),
  fact_status     article_fact_status NOT NULL,

  -- 매치 하나에 기사 하나. schedule_id 가 아니라 매치 신원으로 조인다 —
  -- 그래야 재생성이 갱신이 되고, 대회가 다른 같은 번호끼리 안 부딪힌다
  UNIQUE (slot_id, season, day, blue_team_id, red_team_id)
);

CREATE INDEX article_slot_time_idx ON article (slot_id, season DESC, day DESC);
CREATE INDEX article_team_idx      ON article (blue_team_id, red_team_id);


-- 댓글. 창작층 안에서만 산다 — 집계로 올라가지 않고 통계 화면과 링크로 잇지도 않는다.
-- 닉네임을 두지 않는다. 프롬프트가 본문만 쓰게 하고, 화면도 본문만 보여준다.
CREATE TABLE article_comment (
  article_id bigint   NOT NULL REFERENCES article(article_id) ON DELETE CASCADE,
  ordinal    smallint NOT NULL,
  body       text     NOT NULL,
  PRIMARY KEY (article_id, ordinal)
);


-- 대조에서 나온 지적. 화면의 「이 기사가 쓴 숫자」 블록 아래에 함께 붙는다.
CREATE TABLE article_finding (
  article_id bigint   NOT NULL REFERENCES article(article_id) ON DELETE CASCADE,
  ordinal    smallint NOT NULL,
  severity   article_finding_severity NOT NULL,
  what       text     NOT NULL,
  evidence   text     NOT NULL,
  PRIMARY KEY (article_id, ordinal)
);

CREATE INDEX article_finding_severity_idx ON article_finding (article_id, severity);


-- fact_status 와 article_finding 의 정합은 여기서 강제하지 않는다.
--
-- 행 개수에 대한 제약이라 CHECK 로는 못 쓰고 트리거가 필요한데, D35 가 같은 상황
-- (밴이 정확히 3개)에서 이미 정했다 — **트리거를 두는 대신 적재에서 막고 테스트로
-- 고정한다.** 트리거는 마이그레이션 안에 로직을 숨기고, 그 로직은 애플리케이션
-- 테스트가 못 본다.
--
-- 그러므로 "모순이 하나라도 있으면 fact_status = CONTRADICTED" 는 저장하는 쪽의
-- 책임이고, 그 계약은 ArticleDao 테스트가 지킨다.
