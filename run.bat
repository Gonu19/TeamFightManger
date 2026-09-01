@echo off
rem  더블클릭으로 앱을 띄운다. 알맹이는 run.ps1 이고 이 파일은 그것을 부르기만 한다.
rem
rem  왜 .bat 이 따로 있나
rem    - .ps1 은 탐색기에서 더블클릭하면 "실행" 이 아니라 "메모장으로 열기" 다
rem    - 기본 실행 정책(RemoteSigned)에서 내려받은 .ps1 은 차단된다.
rem      -ExecutionPolicy Bypass 는 이 한 번의 호출에만 적용되고 시스템 설정은 안 건드린다
rem
rem  기본이 "생성 켬" 이다. 그 기본값은 run.ps1 이 들고 있다 — 여기서 -Story 를
rem  덧붙이지 않는 이유가 그것이다. 이 파일이 인자를 붙이면 run.bat -Story 로 불렀을 때
rem  PowerShell 이 "parameter specified more than once" 로 죽는다.
rem
rem  인자는 그대로 넘긴다:  run.bat -Port 8099 -NoStory

setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*

rem  창이 곧바로 닫히면 오류를 읽을 수가 없다. 실패했을 때만 잡아 둔다.
if errorlevel 1 (
    echo.
    echo  실패했다. 위 메시지를 본다.
    pause
)
endlocal
