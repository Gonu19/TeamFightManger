-- 갤러리 — 기사 아래 붙는 게시판 (2026-08-31, decisions/D71_*.md · D72_*.md)
--
-- 레퍼런스 모드(community_reaction_mod)의 구조를 그대로 들여온다. 그 모드의 단위는
-- "기사 한 편" 이 아니라 **경기 하나에 달린 갤러리 한 페이지**다 — 짧은 글 20개가
-- 서로 다른 각도에서 같은 경기를 물어뜯고, 그 아래에서 키배가 난다.
--
-- 우리 쪽 매핑이 이 스키마의 전부다:
--
--     모드의 [이슈] 뉴스  →  article  (이미 있는, 대조까지 끝난 매치 기사)
--     모드의 게시글 20개  →  gallery_post
--     모드의 페이지       →  gallery_batch
--
-- 모드에서 이슈는 모델이 지어낸 것이지만 우리는 그 자리에 **세이브에서 나온 사실로
-- 검증된 기사**를 놓는다. 그래서 사실층은 하나도 안 무너진 채로 게시판만 얹힌다 —
-- D66(대조는 어휘가 같을 때만) 과 D68(형용사는 창작층이 짓는다)을 뒤집지 않는다.

SET client_encoding = 'UTF8';


-- 게시글 유형. 모드의 [게시글 유형 다양성 규칙] 열 가지를 그대로 옮겼다.
--
-- ENUM 인 이유는 이 값이 **할당량과 짝**이기 때문이다. 한 배치에 유형별로 몇 개를
-- 쓸지 코드가 정하고 프롬프트에 박아 넣는다 — text 로 두면 모델이 지어낸 유형이
-- 새 값으로 들어오고, 그러면 할당이 조용히 무너져도 아무도 모른다.
--
--   SKIT      상황극/대화체 드립글
--   BAIT      "~.txt" / "~한 진짜 이유" 어그로 제목 글
--   ANALYSIS  데이터 분석글 (고정닉 분석노트 톤)
--   LIVE      실황/직관 후기
--   FLAME     키배 유발 떡밥글
--   SAGA      서사글 (라이벌·이적·대회)
--   PLAYER    선수 개인 저격/찬양
--   DAILY     갤 일상 + 경기 연계 잡담
--   TRANSFER  이적시장/방출/유망주 반응
--   SCRAP     기사 스크랩 반응글 (앵커 기사를 퍼와서 토론·반박·옹호)
CREATE TYPE gallery_post_kind AS ENUM (
  'SKIT', 'BAIT', 'ANALYSIS', 'LIVE', 'FLAME', 'SAGA', 'PLAYER', 'DAILY', 'TRANSFER', 'SCRAP');


-- 한 페이지. 버튼 한 번에 하나 생긴다.
--
-- 기사 하나에 여러 배치를 허용한다(UNIQUE 를 안 건다). 모드의 '반응 불러오기' 가
-- 누를 때마다 새 페이지를 쌓는 것과 같다 — 같은 경기라도 다시 뽑으면 다른 갤이 된다.
-- 덮어쓰지 않는 이유는 이 층에 정답이 없기 때문이다. 기사는 갱신하는 게 맞고
-- (그건 사실을 다시 맞춰보는 일이다) 갤러리는 쌓는 게 맞다.
CREATE TABLE gallery_batch (
  batch_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  article_id   bigint NOT NULL REFERENCES article(article_id) ON DELETE CASCADE,
  generated_at timestamptz NOT NULL DEFAULT now(),

  -- 무엇이 이걸 썼나. article.model 과 같은 이유다 — 모델을 바꾸면 갤 분위기가 달라진다
  model        text NOT NULL,

  -- 이 배치가 호출 몇 번으로 만들어졌나 (D72). 한 번에 20개를 뽑으면 분당 토큰
  -- 한도(8,000)에 걸리므로 유형 묶음으로 나눠 부른다. 몇 조각이었는지가 남아야
  -- "이 페이지는 왜 글이 12개뿐인가"(= 조각 하나가 실패했다)를 나중에 읽을 수 있다
  chunks       smallint NOT NULL CHECK (chunks > 0)
);

CREATE INDEX gallery_batch_article_idx ON gallery_batch (article_id, generated_at DESC);


-- 게시글 하나.
CREATE TABLE gallery_post (
  post_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  batch_id bigint   NOT NULL REFERENCES gallery_batch(batch_id) ON DELETE CASCADE,
  ordinal  smallint NOT NULL,
  kind     gallery_post_kind NOT NULL,

  title    text NOT NULL,
  body     text NOT NULL,

  -- 유동닉 `ㅇㅇ(124.50)`. NULL 이면 화면이 익명으로 그린다.
  -- 모델이 안 준 것을 지어내지 않는다는 규칙은 **이름에는 그대로 적용된다** (D69).
  author   text,

  -- 조회수·추천수. **여기가 이 저장소에서 처음으로 없는 숫자를 담는 자리다** (D71).
  --
  -- 모델이 지어낸 값이고, 게임에도 세이브에도 대응하는 값이 없다. 그래도 두는 이유는
  -- 게시판의 몰입 절반이 "이 글이 몇 명한테 읽혔나" 에서 나오기 때문이다.
  --
  -- 그 대신 경계를 스키마로 못박는다: 이 두 컬럼은 gallery_* 밖으로 나가지 않는다.
  -- 집계 표(champion_*·team_*)는 이 표들을 조회하지 않고, 통계 화면도 마찬가지다.
  -- NOT NULL 이 아닌 것도 같은 규칙이다 — 모델이 안 준 값을 0 으로 채우면
  -- "안 준 것" 과 "0 이라고 한 것" 이 구분되지 않는다.
  views    int CHECK (views >= 0),
  likes    int CHECK (likes >= 0),

  -- 모델이 스스로 개념글이라고 표시했는가.
  declared_concept boolean NOT NULL DEFAULT false,

  -- 화면에 개념글로 그릴 것인가. 모드가 `likes >= 30` 이면 개념글로 승격시키는 것을
  -- 그대로 옮겼다. **저장 시점에 계산해 굳힌다** — 화면마다 이 규칙을 다시 쓰면
  -- 목록과 상세가 서로 다른 답을 하게 되는 종류의 버그가 난다.
  is_concept boolean GENERATED ALWAYS AS
      (declared_concept OR COALESCE(likes, 0) >= 30) STORED,

  -- 짤방 파일명. 실제 이미지는 없고 파일명 텍스트만 노출된다(모드도 같다).
  -- NULL 이면 짤방 없는 글이다 — 별도의 has_image 플래그를 두지 않는 이유가 그것이다
  image_desc text,

  UNIQUE (batch_id, ordinal)
);

CREATE INDEX gallery_post_batch_idx ON gallery_post (batch_id, ordinal);


-- 댓글. article_comment 와 같은 모양이다 (D69) — 유동닉 + 한 단계 대댓글.
--
-- 표를 따로 두는 이유는 매다는 대상이 다르기 때문이다. article_comment 는 기사에,
-- 이쪽은 게시글에 붙는다. 한 표에 합치면 부모 컬럼이 둘 중 하나만 차는 행이 되고,
-- 그 제약은 CHECK 로 쓸 수는 있어도 조회할 때마다 어느 쪽인지 물어야 한다.
CREATE TABLE gallery_comment (
  post_id        bigint   NOT NULL REFERENCES gallery_post(post_id) ON DELETE CASCADE,
  ordinal        smallint NOT NULL,

  -- 받아친 원댓글의 ordinal. NULL 이면 원댓글이다.
  parent_ordinal smallint,

  author         text,
  body           text     NOT NULL,

  PRIMARY KEY (post_id, ordinal),

  CONSTRAINT gallery_comment_parent_fk
    FOREIGN KEY (post_id, parent_ordinal) REFERENCES gallery_comment (post_id, ordinal)
    DEFERRABLE INITIALLY DEFERRED,

  -- 자기 자신을 부모로 두면 화면이 무한히 파고든다
  CONSTRAINT gallery_comment_parent_not_self
    CHECK (parent_ordinal IS NULL OR parent_ordinal <> ordinal)
);

CREATE INDEX gallery_comment_parent_idx ON gallery_comment (post_id, parent_ordinal);


COMMENT ON TABLE  gallery_batch IS '기사 하나에 달린 갤러리 페이지. 누를 때마다 쌓인다 (D72)';
COMMENT ON COLUMN gallery_post.views IS
  '모델이 지어낸 조회수. gallery_* 밖으로 나가지 않는다 (D71)';
COMMENT ON COLUMN gallery_post.likes IS
  '모델이 지어낸 추천수. 30 이상이면 개념글로 승격된다 (D71)';
COMMENT ON COLUMN gallery_post.is_concept IS
  '화면에 개념글로 그릴 것인가. 저장 시점에 굳힌다 (D71)';
