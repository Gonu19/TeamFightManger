/*
 * 갤러리 화면. 레퍼런스 모드(TFA2_gallery.html)의 동작을 옮긴 것이다.
 *
 * 여기서 하는 일은 셋뿐이다:
 *   1. 서버가 심어 준 JSON 으로 목록·글·이슈를 그린다
 *   2. 정렬(최신·조회·추천) — 같은 데이터의 다른 순서라 서버를 왕복하지 않는다
 *   3. 생성 진행 상황 폴링 — 생성이 요청 밖에서 돌기 때문이다
 *
 * 페이지 넘기기는 여기에 없다. 그건 다른 데이터라 서버가 링크로 준다.
 */

(function () {
    'use strict';

    /** 추천 몇 개부터 개념글인가. DB 의 생성 컬럼과 같은 값이다 (V11 · D71). */
    var CONCEPT_LIKES = 30;

    /** 진행 상황을 몇 초마다 물을까. 단계가 분 단위로 바뀌므로 자주 물을 이유가 없다. */
    var POLL_MS = 3000;

    var board = null;      // { batchId, issues[], posts[] }
    var sortKey = 'date';
    var slot = null;

    /* ---------- 시작 ---------- */

    window.startGallery = function (options) {
        slot = options.slot;
        board = readBoardData();

        renderIssues();
        renderBoard();

        if (options.canGenerate && slot !== null) {
            hookGenerateButton();
            pollStatus();                      // 이 화면에 막 돌아왔을 때 이미 돌고 있을 수 있다
        }
    };

    /**
     * 서버가 <script type="application/json"> 에 심어 준 데이터.
     *
     * 파싱이 실패하면 빈 게시판으로 둔다 — 던지면 이 파일의 나머지가 통째로 안 돌고,
     * 그러면 진행 막대까지 죽어서 "생성했는데 아무 일도 안 일어난다" 가 된다.
     */
    function readBoardData() {
        var tag = document.getElementById('boardData');
        if (!tag) { return null; }
        try {
            return JSON.parse(tag.textContent);
        } catch (e) {
            console.error('게시판 데이터를 읽지 못했다', e);
            return null;
        }
    }

    /* ---------- 목록 ---------- */

    function renderBoard() {
        var body = document.getElementById('boardBody');
        if (!board || !board.posts.length) {
            body.innerHTML = '<tr><td colspan="6" class="board-empty">'
                + '아직 게시글이 없습니다. 위 <b>반응 불러오기</b>를 누르면 만들어집니다.</td></tr>';
            return;
        }

        document.getElementById('sortBar').style.display = 'flex';

        var posts = sorted(board.posts);
        body.innerHTML = '';
        posts.forEach(function (post) {
            var tr = document.createElement('tr');
            if (post.concept) { tr.className = 'concept-row'; }

            // 개념글은 번호 대신 '개념' 이 뜬다. 모드가 그렇고, 그래야 목록에서
            // 어느 글이 갤을 뒤집었는지 한눈에 보인다.
            tr.appendChild(cell(post.concept ? '개념' : post.ordinal, post.concept ? 'num-concept' : ''));

            var title = document.createElement('td');
            title.className = 'title-td';
            title.appendChild(text(post.title));
            if (post.imageDesc) { title.appendChild(tag('span', 'pic', ' 📷')); }
            if (post.commentCount > 0) {
                title.appendChild(tag('b', 'cmt', ' [' + post.commentCount + ']'));
            }
            title.onclick = function () { openPost(post); };
            tr.appendChild(title);

            tr.appendChild(cell(post.author || 'ㅇㅇ', 'who'));
            tr.appendChild(cell(post.date || '-'));
            // 모델이 조회수를 안 줬으면 칸을 비운다. 0 을 그리면 "아무도 안 봤다" 로
            // 읽히는데, 그건 "안 줬다" 와 다른 뜻이다 (D71 결정 2).
            tr.appendChild(cell(post.views === null ? '-' : post.views));
            tr.appendChild(cell(post.likes === null ? '-' : post.likes,
                post.likes >= CONCEPT_LIKES ? 'hot' : ''));

            body.appendChild(tr);
        });
    }

    /** 정렬. 같은 데이터의 다른 순서라 서버를 왕복하지 않는다. */
    function sorted(posts) {
        var copy = posts.slice();
        if (sortKey === 'views') {
            copy.sort(function (a, b) { return num(b.views) - num(a.views); });
        } else if (sortKey === 'likes') {
            copy.sort(function (a, b) { return num(b.likes) - num(a.likes); });
        } else {
            // 최신순 = 올라온 순의 역순. ordinal 이 곧 올라온 순서다(조각 순서 = 갤의 시간).
            copy.sort(function (a, b) { return b.ordinal - a.ordinal; });
        }
        return copy;
    }

    /** 값을 모르는 글은 맨 뒤로 간다. 0 으로 읽는 것이 그 뜻이다. */
    function num(value) {
        return value === null || value === undefined ? 0 : value;
    }

    window.sortBoard = function (key) {
        sortKey = key;
        ['date', 'views', 'likes'].forEach(function (k) {
            document.getElementById('sort-' + k).className =
                'sort-link' + (k === key ? ' active' : '');
        });
        renderBoard();
    };

    /* ---------- 글 하나 ---------- */

    function openPost(post) {
        document.getElementById('viewTitle').textContent = post.title;
        document.getElementById('viewAuthor').textContent = post.author || 'ㅇㅇ';
        document.getElementById('viewKind').textContent = post.kind;
        document.getElementById('viewDate').textContent = post.date || '';
        document.getElementById('viewViews').textContent =
            post.views === null ? '-' : post.views;
        document.getElementById('viewLikes').textContent =
            post.likes === null ? '-' : post.likes;
        document.getElementById('viewContent').textContent = post.body;

        // 짤방은 파일명만 나온다. 실제 이미지가 없기 때문이고, 모드도 같다.
        var image = document.getElementById('viewImage');
        image.style.display = post.imageDesc ? 'inline-block' : 'none';
        image.textContent = post.imageDesc ? '📎 ' + post.imageDesc : '';

        renderComments(post);

        // 목록·정렬바·페이지 번호를 함께 감춘다. 글을 읽는 동안 페이지 번호가 남아 있으면
        // 그걸 눌렀을 때 어디로 가는지가 모호해진다 — 지금 글이 아니라 목록이 바뀐다.
        showList(false);
        document.getElementById('postView').style.display = 'block';
        window.scrollTo(0, 0);
    }

    window.closePost = function () {
        document.getElementById('postView').style.display = 'none';
        showList(true);
    };

    /** 목록 쪽 세 가지를 함께 켜고 끈다. 따로 다루면 하나를 빠뜨린다. */
    function showList(visible) {
        document.querySelector('.board-list').style.display = visible ? '' : 'none';
        document.getElementById('sortBar').style.display = visible ? 'flex' : 'none';
        var pager = document.querySelector('.pagination-container');
        if (pager) { pager.style.display = visible ? 'flex' : 'none'; }
    }

    function renderComments(post) {
        document.getElementById('viewCmtCount').textContent = post.commentCount;

        var box = document.getElementById('viewComments');
        box.innerHTML = '';
        post.comments.forEach(function (comment) {
            var container = tag('div', 'comment-item-container', '');
            container.appendChild(commentRow(comment, 'comment-item'));

            // 대댓글은 한 단계만 들여쓴다. 더 깊이 들어가면 화면이 계단이 된다.
            if (comment.replies.length) {
                var list = tag('div', 'sub-comment-list', '');
                comment.replies.forEach(function (reply) {
                    var row = commentRow(reply, 'sub-comment-item');
                    row.insertBefore(tag('span', 'sub-comment-prefix', '└'), row.firstChild);
                    list.appendChild(row);
                });
                container.appendChild(list);
            }
            box.appendChild(container);
        });
    }

    function commentRow(comment, className) {
        var row = tag('div', className, '');
        row.appendChild(tag('span', 'comment-author', comment.author || 'ㅇㅇ'));
        row.appendChild(tag('span', 'comment-body', comment.body));
        row.appendChild(tag('span', 'comment-date', comment.date || ''));
        return row;
    }

    /* ---------- 이슈 ---------- */

    function renderIssues() {
        var list = document.getElementById('issueList');
        if (!board || !board.issues.length) { return; }

        list.innerHTML = '';
        board.issues.forEach(function (issue) {
            var li = document.createElement('li');
            li.appendChild(tag('span', 'issue-cat-badge ' + (issue.badge || ''), issue.category));
            li.appendChild(text(issue.headline));
            li.onclick = function () { openIssue(issue); };
            list.appendChild(li);
        });
    }

    function openIssue(issue) {
        document.getElementById('issueModalCat').innerHTML = '';
        document.getElementById('issueModalCat').appendChild(
            tag('span', 'issue-cat-badge ' + (issue.badge || ''), issue.category));
        document.getElementById('issueModalTitle').textContent = issue.headline;
        document.getElementById('issueModalDate').textContent =
            '팀파이트 매니저 이슈' + (issue.date ? ' | ' + issue.date : '');
        document.getElementById('issueModalContent').textContent = issue.body;
        document.getElementById('issueModal').style.display = 'block';
    }

    window.closeIssue = function () {
        document.getElementById('issueModal').style.display = 'none';
    };

    /* ---------- 생성 진행 상황 ---------- */

    /**
     * 버튼을 누르면 곧바로 막대를 띄운다.
     *
     * 폼은 그대로 제출된다(POST → 302 → GET). 그 왕복 뒤 화면이 다시 그려지고,
     * 그때 pollStatus() 가 이어받아 진행 중인 작업을 찾아낸다.
     */
    function hookGenerateButton() {
        var form = document.getElementById('genForm');
        if (!form) { return; }
        form.addEventListener('submit', function () {
            showProgress('시작하는 중…', 0);
            document.getElementById('genBtn').disabled = true;
        });
    }

    function pollStatus() {
        fetch('/gallery/status?slot=' + slot)
            .then(function (response) { return response.json(); })
            .then(function (status) {
                if (status.state === 'RUNNING') {
                    showProgress(status.step + '…', status.percent);
                    document.getElementById('genBtn').disabled = true;
                    setTimeout(pollStatus, POLL_MS);
                    return;
                }
                if (status.state === 'DONE') {
                    // 새 페이지가 생겼으니 서버에서 다시 받아 온다. 여기서 직접 그리지
                    // 않는 이유는 페이지 목록(번호)도 함께 바뀌기 때문이다.
                    showProgress('다 됐다. 새로 불러온다…', 100);
                    window.location = '/gallery?slot=' + slot;
                    return;
                }
                if (status.state === 'FAILED' || status.state === 'NOTHING_TO_DO') {
                    showProgress(status.message || '끝났다', 100);
                    document.getElementById('genBtn').disabled = false;
                    return;
                }
                hideProgress();                // IDLE — 아직 한 번도 안 눌렀다
            })
            .catch(function (e) {
                // 서버가 죽었거나 네트워크가 끊겼다. 막대를 진실하지 않은 상태로
                // 두지 않는다 — 멈춘 막대는 "돌고 있다" 는 거짓말이 된다.
                console.error('진행 상황을 읽지 못했다', e);
                showProgress('진행 상황을 읽지 못했다. 새로고침해 본다.', 0);
                document.getElementById('genBtn').disabled = false;
            });
    }

    function showProgress(step, percent) {
        document.getElementById('progressBox').style.display = 'block';
        document.getElementById('progressStep').textContent = step;
        document.getElementById('progressFill').style.width = percent + '%';
    }

    function hideProgress() {
        document.getElementById('progressBox').style.display = 'none';
    }

    /* ---------- 테마 ---------- */

    /**
     * 다크 모드. 모드의 것을 그대로 옮겼다 — localStorage 에 남겨 다음에도 유지한다.
     * 서버에 저장할 값이 아니다. 이 앱은 1인용 로컬 앱이고(D59) 브라우저 취향은
     * 브라우저에 남는 것이 맞다.
     */
    window.toggleTheme = function () {
        var dark = document.body.classList.toggle('dark');
        try {
            localStorage.setItem('tfm_gallery_dark', dark ? '1' : '0');
        } catch (e) {
            // 사생활 보호 모드 등에서 던진다. 테마가 이번 세션에만 남을 뿐이라 넘어간다.
        }
    };

    try {
        if (localStorage.getItem('tfm_gallery_dark') === '1') {
            document.body.classList.add('dark');
        }
    } catch (e) { /* 위와 같다 */ }

    /* ---------- 자잘한 것 ---------- */

    /**
     * 전부 textContent 로 넣는다. innerHTML 로 넣으면 모델이 쓴 글에 든 태그가
     * 화면의 태그가 된다 — 갤 글은 우리가 안 쓴 문자열이라 그 가정이 성립하지 않는다.
     */
    function tag(name, className, content) {
        var node = document.createElement(name);
        if (className) { node.className = className; }
        if (content) { node.textContent = content; }
        return node;
    }

    function text(value) {
        return document.createTextNode(value);
    }

    function cell(value, className) {
        var td = document.createElement('td');
        td.textContent = value;
        if (className) { td.className = className; }
        return td;
    }
})();
