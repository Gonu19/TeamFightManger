-- 갤러리를 기사에서 떼어내고 이슈를 들인다 (2026-08-31, decisions/D73_*.md)
--
-- V11 은 갤러리를 기사에 매달았다. 우리 매치 기사를 레퍼런스 모드의 [이슈] 자리에
-- 놓는다는 설계였고(D72 결정 1), 사실층을 갤 아래 깔 수 있어서 좋았다.
--
-- 실물이 그 설계를 뒤집었다. 기사를 먼저 써야 갤러리를 만들 수 있으니 버튼을 두 번
-- 눌러야 했고, 그 둘을 합치면 한 번에 모델 호출이 여섯이다. 무엇보다 사용자가 원한 것은
-- 기사가 아니라 <b>게시판</b>이었다 — 기사는 그 앞을 막는 관문이 되어 있었다.
--
-- 그래서 갤러리가 매치에 직접 붙는다. 기사가 있으면 링크로 잇되(article_id), 없어도 된다.
-- 모드의 [이슈] 자리는 원래대로 <b>지어낸 뉴스</b>가 채운다 (gallery_issue).

SET client_encoding = 'UTF8';


-- --- 1. 갤러리가 매치에 직접 붙는다 -------------------------------------------

-- 기사는 이제 선택이다. 있으면 화면이 "기사 보기" 를 그리고, 없으면 안 그린다.
ALTER TABLE gallery_batch ALTER COLUMN article_id DROP NOT NULL;

-- 매치 신원. article 의 것과 같은 네 값이다 — 같은 매치를 두 번 뽑았는지 판정하는
-- 열쇠가 여기서도 (시즌 · 일 · 두 팀) 인 이유는 D_ArticleKey 가 적어 둔 그대로다:
-- MatchSchedule.ID 는 대회마다 ID 공간이 따로라 단독으로 유일하지 않다.
ALTER TABLE gallery_batch ADD COLUMN slot_id      int;
ALTER TABLE gallery_batch ADD COLUMN season       int;
ALTER TABLE gallery_batch ADD COLUMN day          int;
ALTER TABLE gallery_batch ADD COLUMN blue_team_id int REFERENCES team(team_id);
ALTER TABLE gallery_batch ADD COLUMN red_team_id  int REFERENCES team(team_id);
ALTER TABLE gallery_batch ADD COLUMN blue_score   smallint;
ALTER TABLE gallery_batch ADD COLUMN red_score    smallint;

-- 이미 있는 배치는 기사에서 값을 옮겨 온다. V11 이 article_id 를 NOT NULL 로 뒀으므로
-- 기존 행에는 반드시 기사가 있다 — 그래서 이 UPDATE 로 빠짐없이 채워진다.
UPDATE gallery_batch g
   SET slot_id      = a.slot_id,
       season       = a.season,
       day          = a.day,
       blue_team_id = a.blue_team_id,
       red_team_id  = a.red_team_id,
       blue_score   = a.blue_score,
       red_score    = a.red_score
  FROM article a
 WHERE a.article_id = g.article_id;

-- 채운 뒤에 못을 박는다. NOT NULL 을 처음부터 걸면 위 UPDATE 를 할 수 없다.
ALTER TABLE gallery_batch ALTER COLUMN slot_id      SET NOT NULL;
ALTER TABLE gallery_batch ALTER COLUMN season       SET NOT NULL;
ALTER TABLE gallery_batch ALTER COLUMN day          SET NOT NULL;
ALTER TABLE gallery_batch ALTER COLUMN blue_team_id SET NOT NULL;
ALTER TABLE gallery_batch ALTER COLUMN red_team_id  SET NOT NULL;

ALTER TABLE gallery_batch
  ADD CONSTRAINT gallery_batch_slot_fk
  FOREIGN KEY (slot_id) REFERENCES save_slot(slot_id) ON DELETE CASCADE;

-- 조회는 언제나 "이 커리어의 최근 갤러리" 다. 경기 시점 순으로 훑는다 —
-- generated_at 으로 정렬하면 옛 시즌을 나중에 뽑았을 때 그게 맨 위로 올라온다.
DROP INDEX IF EXISTS gallery_batch_article_idx;
CREATE INDEX gallery_batch_slot_time_idx
  ON gallery_batch (slot_id, season DESC, day DESC, generated_at DESC);
CREATE INDEX gallery_batch_article_idx
  ON gallery_batch (article_id) WHERE article_id IS NOT NULL;

-- UNIQUE 를 걸지 않는다. 같은 매치를 다시 뽑으면 <b>다른 갤</b>이 나오고, 그것이
-- 쌓이는 것이 맞다 (D72 결정 5). 모드의 '반응 불러오기' 도 누를 때마다 페이지가 는다.

COMMENT ON COLUMN gallery_batch.article_id IS
  '이 매치의 기사. 있으면 화면이 링크를 그린다. 없어도 된다 (D73)';


-- --- 2. 이슈 (모드의 [🔥 팀파 이슈] 사이드바) --------------------------------

-- 이슈 분류. 모드의 여섯 가지를 그대로 옮겼다.
--
-- ENUM 인 이유는 <b>화면의 배지 색과 짝</b>이기 때문이다. text 로 두면 모델이 지어낸
-- 분류가 새 값으로 들어오고, 화면은 그것을 색 없는 빈 배지로 조용히 그린다.
CREATE TYPE gallery_issue_category AS ENUM (
  'LEAGUE', 'TRANSFER', 'SCANDAL', 'BROADCAST', 'ANALYSIS', 'RUMOR');

-- 이슈는 배치에 매달린다. 페이지마다 그때의 이슈가 남아야 하기 때문이다 —
-- 갤 글이 그 이슈를 스크랩해 반응글을 쓰므로, 이슈가 바뀌면 그 글의 근거가 사라진다.
CREATE TABLE gallery_issue (
  batch_id   bigint   NOT NULL REFERENCES gallery_batch(batch_id) ON DELETE CASCADE,
  ordinal    smallint NOT NULL,
  category   gallery_issue_category NOT NULL,
  headline   text     NOT NULL,
  body       text     NOT NULL,

  -- 모델이 준 "MM.DD" 문자열 그대로다. 날짜로 파싱하지 않는다 —
  -- 게임 안의 날짜라 우리 달력의 연도가 없고, 없는 연도를 붙이면 그게 사실이 된다.
  issue_date text,

  PRIMARY KEY (batch_id, ordinal)
);

COMMENT ON TABLE gallery_issue IS
  '지어낸 뉴스 6개. 갤러가 이걸 스크랩해 반응글을 쓴다 (D73)';
COMMENT ON COLUMN gallery_issue.issue_date IS
  '모델이 준 MM.DD 문자열 그대로. 게임 안의 날짜라 연도가 없다';


-- --- 3. 글에 작성 시각 문자열 -------------------------------------------------

-- 모드의 게시판은 글마다 "2025. 08. 31. 16:40" 을 그린다. 그 값은 <b>경기 날짜</b>에서
-- 나오지 우리 시계에서 나오지 않는다 — 시즌 3의 경기를 오늘 뽑았다고 오늘 날짜가 붙으면
-- 게시판이 게임 세계 밖으로 나간다.
--
-- 그래서 문자열로 받아 그대로 둔다. timestamptz 로 두면 연도·시간대를 우리가 정해야 하고,
-- 그 값은 게임 안에 대응하는 것이 없다.
ALTER TABLE gallery_post ADD COLUMN posted_at text;

COMMENT ON COLUMN gallery_post.posted_at IS
  '게시판에 그리는 작성 시각 문자열. 게임 안의 날짜라 우리 시계가 아니다 (D73)';

-- 댓글도 같다. 모드는 댓글에 "16:41" 만 그린다.
ALTER TABLE gallery_comment ADD COLUMN posted_at text;
