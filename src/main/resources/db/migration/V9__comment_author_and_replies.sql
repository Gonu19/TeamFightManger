-- 댓글에 닉네임과 대댓글 (2026-08-31, decisions/D69_*.md)
--
-- V8 은 댓글을 본문만 담는 표로 만들었다. D61 결정 3 이 "닉네임을 두지 않는다" 고
-- 정했기 때문이다 — 프롬프트가 본문만 쓰게 하고 화면도 본문만 보여준다는 규칙이었다.
--
-- 실물 댓글을 읽고 그 결정을 고친다(D69). 커뮤니티의 몰입은 상당 부분이 "누가 말했나"
-- 와 "누가 누구에게 받아쳤나" 에서 나온다. 닉네임이 없으면 열다섯 줄이 한 사람의
-- 독백처럼 읽히고, 대댓글이 없으면 싸움이 성립하지 않는다.

SET client_encoding = 'UTF8';


-- 닉네임. 디시 유동닉처럼 `ㅇㅇ(123.45)` 꼴이 온다.
--
-- NOT NULL 이 아니다. V8 로 이미 저장된 기사의 댓글에는 닉네임이 없고, 그것들을
-- 지어내서 채우면 "그때 모델이 만든 것" 과 "우리가 나중에 만든 것" 이 구분되지 않는다.
-- 화면은 NULL 이면 익명으로 그린다.
ALTER TABLE article_comment ADD COLUMN author text;

-- 이 댓글이 받아친 대상. NULL 이면 원댓글이다.
--
-- 자기 표를 가리키는 외래키다 — 대댓글의 대댓글도 가능하지만 프롬프트는 한 단계만
-- 요구한다. 깊이를 DB 로 막지 않는 이유는 그 제약이 CHECK 로 표현되지 않기 때문이고,
-- 트리거를 두지 않는 것은 D35 이후로 이 저장소의 규칙이다. 적재에서 막고 테스트로 고정한다.
--
-- ON DELETE CASCADE 를 걸지 않는다. 원댓글이 지워지는 경로가 없기 때문이다 —
-- 댓글은 기사 단위로 통째로 지워지고 다시 들어온다(ArticleDao.replaceComments).
ALTER TABLE article_comment ADD COLUMN parent_ordinal smallint;

ALTER TABLE article_comment
  ADD CONSTRAINT article_comment_parent_fk
  FOREIGN KEY (article_id, parent_ordinal)
  REFERENCES article_comment (article_id, ordinal)
  DEFERRABLE INITIALLY DEFERRED;

-- 자기 자신을 가리키면 화면이 무한히 파고든다. 이건 행 하나 안의 값 비교라 CHECK 로 된다.
ALTER TABLE article_comment
  ADD CONSTRAINT article_comment_parent_not_self
  CHECK (parent_ordinal IS NULL OR parent_ordinal <> ordinal);

COMMENT ON COLUMN article_comment.author IS
  '유동닉. V8 시절 댓글은 NULL 이고 화면이 익명으로 그린다 (D69)';
COMMENT ON COLUMN article_comment.parent_ordinal IS
  '받아친 원댓글의 ordinal. NULL 이면 원댓글 (D69)';


-- 대댓글을 원댓글 아래 모아 그리려면 부모로 훑는다.
CREATE INDEX article_comment_parent_idx
  ON article_comment (article_id, parent_ordinal);
