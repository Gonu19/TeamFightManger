/*
 * 연대기(사이클) 화면의 진행 표시 (D81).
 *
 * 생성은 요청 밖에서 돈다. 그래서 이 화면이 몇 초마다 /story/status 를 물어
 * 단계를 그리고, 도는 동안 생성 버튼을 잠그고, 끝나면 결과로 데려간다.
 *
 * <h2>D74 가 가르친 것을 그대로 지킨다</h2>
 *
 * 끝난 상태는 서버 메모리에 남는다(그래야 화면을 늦게 열어도 결과를 볼 수 있다).
 * 그런데 DONE 을 볼 때마다 이동하면 — 이동 → 폴링 → DONE → 이동 이 <b>끝없이 돈다.</b>
 * 갤러리에서 실제로 그렇게 돌았다.
 *
 * 그래서 <b>이 브라우저가 실제로 버튼을 누른 경우에만</b> 결과로 넘어간다.
 * sessionStorage 를 쓰는 이유는 POST → 302 → GET 왕복을 건너야 하고, 탭을 닫으면
 * 함께 사라져야 하기 때문이다.
 *
 * sessionStorage 가 막히면(사생활 보호 모드) 표식이 비어서 <b>이동을 아예 안 한다</b> —
 * 결과를 자동으로 못 보는 것은 불편하지만 루프가 도는 것보다 낫다.
 * 실패 방향을 안전한 쪽으로 둔다.
 */
(function () {
    'use strict';

    var POLL_MS = 2000;
    var AWAIT_KEY = 'tfm_story_awaiting';

    var slot = null;
    var box = null;
    var stepText = null;
    var fill = null;
    var note = null;
    var dismiss = null;
    var leaving = false;

    window.startCycle = function (options) {
        slot = options && options.slot;
        if (slot === null || slot === undefined) {
            return;                            // 고른 커리어가 없으면 물어볼 것도 없다
        }

        box = document.getElementById('progressBox');
        stepText = document.getElementById('progressStep');
        fill = document.getElementById('progressFill');
        note = document.getElementById('progressNote');
        dismiss = document.getElementById('progressDismiss');

        // 생성 버튼을 누르면 "내가 시작한 작업" 으로 표시한다. 폼이 실제로 제출될 때만
        // 표시해야 한다 — 눌렀다가 브라우저가 막은 경우까지 표시하면 남의 결과로 튄다.
        document.querySelectorAll('form[action*="/story/generate"]').forEach(function (form) {
            form.addEventListener('submit', function () {
                markAwaiting(true);
                lockButtons();
            });
        });

        if (dismiss) {
            dismiss.addEventListener('click', function () {
                markAwaiting(false);
                hideProgress();
            });
        }

        // 이 화면에 막 돌아왔을 때 이미 돌고 있을 수 있다. 서버가 busy 를 알려줬거나
        // 내가 시작한 작업이 남아 있으면 곧바로 물어본다.
        if (options.busy || isAwaiting()) {
            pollStatus();
        }
    };

    /* ---------- 표시 ---------- */

    function showProgress(text, percent) {
        if (!box) {
            return;
        }
        box.classList.remove('hidden');
        stepText.textContent = text;
        fill.style.width = Math.max(0, Math.min(100, percent)) + '%';
    }

    function hideProgress() {
        if (box) {
            box.classList.add('hidden');
        }
    }

    /** 끝난 뒤에는 닫기 버튼을 준다 — 막대가 화면에 영원히 남지 않게. */
    function showDismiss(show) {
        if (dismiss) {
            dismiss.hidden = !show;
        }
    }

    function setNote(text, detail) {
        if (!note) {
            return;
        }
        note.textContent = '';
        var line = document.createElement('div');
        line.className = 'note-main';
        line.textContent = text;
        note.appendChild(line);

        // 원문은 접어 둔다 — 버리지 않되 첫 줄을 차지하지도 않는다.
        // textContent 로만 넣는다: 모델이 돌려준 문자열이라 태그가 섞일 수 있다.
        if (detail) {
            var box = document.createElement('details');
            var summary = document.createElement('summary');
            summary.textContent = '원문 보기';
            var body = document.createElement('div');
            body.className = 'note-detail';
            body.textContent = detail;
            box.appendChild(summary);
            box.appendChild(body);
            note.appendChild(box);
        }
    }

    /**
     * 도는 동안 생성 버튼을 전부 잠근다.
     *
     * <b>서버도 같은 것을 막는다</b>(StoryJobs 가 커리어당 하나만 받는다). 화면이 잠그는
     * 것은 그 거절을 <b>미리 보이게</b> 하려는 것뿐이다 — 눌러야 "이미 도는 중" 을 알게
     * 되는 버튼은 버튼이 아니다.
     */
    function lockButtons() {
        setButtonsEnabled(false);
    }

    function setButtonsEnabled(enabled) {
        document.querySelectorAll('[data-gen]').forEach(function (button) {
            button.disabled = !enabled;
        });
    }

    /* ---------- 표식 ---------- */

    function markAwaiting(value) {
        remember(AWAIT_KEY, value ? '1' : null);
    }

    function isAwaiting() {
        return recall(AWAIT_KEY) === '1';
    }

    function remember(key, value) {
        try {
            if (value === null) {
                sessionStorage.removeItem(key);
            } else {
                sessionStorage.setItem(key, value);
            }
        } catch (e) { /* 위 설명대로 넘어간다 */ }
    }

    function recall(key) {
        try {
            return sessionStorage.getItem(key);
        } catch (e) {
            return null;
        }
    }

    /*
     * 페이지를 떠나는 중인가.
     *
     * 이동이 시작되면 그때 날아가 있던 fetch 가 취소되면서 거부된다. 그것을 네트워크
     * 오류로 읽으면 떠나는 화면에 "진행 상황을 읽지 못했다" 가 뜬다 — 갤러리에서
     * 실제로 겪은 증상이다.
     */
    window.addEventListener('pagehide', function () { leaving = true; });

    /* ---------- 폴링 ---------- */

    function pollStatus() {
        fetch('/story/status?slot=' + slot)
            .then(function (response) { return response.json(); })
            .then(function (status) {
                if (status.state === 'RUNNING') {
                    showProgress(status.label + ' — ' + status.step + '…', status.percent);
                    showDismiss(false);
                    setNote('모델 호출 ' + status.done + '/' + status.total
                        + '. 끝날 때까지 다른 생성은 시작할 수 없다 — 분당 토큰이 하나다.');
                    setButtonsEnabled(false);
                    setTimeout(pollStatus, POLL_MS);
                    return;
                }

                if (status.state === 'DONE') {
                    // 내가 시작한 작업일 때만 결과로 넘어간다. 지난 결과를 보고 이동하면
                    // 이동 → 폴링 → DONE → 이동 이 끝없이 돈다 (D74).
                    if (isAwaiting() && status.next) {
                        showProgress(status.object.replace(/[을를]$/, '') + ' 이(가) 나왔다. 여는 중…', 100);
                        markAwaiting(false);
                        leaving = true;
                        window.location = status.next;
                        return;
                    }
                    markAwaiting(false);
                    hideProgress();
                    setButtonsEnabled(true);
                    return;
                }

                if (status.state === 'FAILED' || status.state === 'NOTHING_TO_DO') {
                    // 내가 시작한 작업일 때만 알린다. 지난 실패를 나중에 들어온 사람에게
                    // 보여주면 방금 실패한 것처럼 읽힌다.
                    if (isAwaiting()) {
                        var failed = status.state === 'FAILED';
                        showProgress((failed ? '실패 — ' : '') + status.object + ' 못 만들었다', 100);
                        // 할 일이 먼저, 원문은 아래 작게. 공급자 JSON 을 크게 띄우면
                        // "무엇을 하라" 가 그 안에 묻힌다 (D81).
                        setNote(status.message || '원인을 알 수 없다. 로그를 본다.', status.detail);
                        showDismiss(true);
                        markAwaiting(false);
                    } else {
                        hideProgress();
                    }
                    setButtonsEnabled(true);
                    return;
                }

                // IDLE — 아직 아무것도 안 눌렀다.
                hideProgress();
                setButtonsEnabled(true);
            })
            .catch(function () {
                if (leaving) {
                    return;                    // 이동하며 취소된 요청이다. 오류가 아니다
                }
                showProgress('진행 상황을 읽지 못했다', 0);
                setNote('앱이 떠 있는지 확인한다. 생성 자체는 계속되고 있을 수 있다.');
                showDismiss(true);
                setButtonsEnabled(true);
            });
    }
}());
