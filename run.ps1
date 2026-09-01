<#
.SYNOPSIS
    앱을 띄운다. 환경변수를 매번 손으로 세팅하지 않으려고 있는 파일이다.

.DESCRIPTION
    지금까지는 이걸 외워야 했다:

        $env:TFM_DB_PASSWORD = 'postgres'; .\gradlew.bat bootRun --console=plain --args="--server.port=8088"

    문제는 길이가 아니라 <b>조용히 틀리는 방식</b>이다.

      - 따옴표를 빼면 PowerShell 이 값을 명령으로 해석한다
      - 환경변수는 그 셸에만 산다. 새 창을 열면 사라지고, 증상은 Postgres 28P01 이라
        "코드가 깨졌나" 로 읽힌다
      - 환경변수가 .env 보다 우선한다. 지난번에 넣어둔 잘못된 키가 파일을 조용히 가린다

    그래서 이 스크립트는 <b>무엇을 쓰는지 먼저 찍고</b> 띄운다. 키는 앞 네 글자만 보인다.

.PARAMETER Port
    기본 8088. 이미 쓰고 있으면 다른 값을 준다 — 사용자 인스턴스가 떠 있는 채로
    검증용을 하나 더 띄울 때 쓴다.

.PARAMETER NoStory
    기사·갤러리 생성을 끈다. <b>기본은 켬</b>이다.

    앱 자체의 기본값은 여전히 꺼짐이다(application.yml). D61 결정 4 가 "켜지 않은
    설치에서 생성기가 우연히 불릴 경로가 아예 없어야 한다" 로 정해 둔 것이고,
    그건 이 스크립트가 바꿀 것이 아니다.

    바꾼 것은 <b>주인의 실행 스크립트</b>다. 이 파일을 쓰는 사람은 이 앱을 만든
    사람이고, 켜려고 띄운다 — 매번 -Story 를 붙이는 것은 그 사실을 반복해 적는 일이다.
    끄고 띄울 이유가 있으면(회귀만 볼 때) -NoStory 로 끈다.

.PARAMETER Aggregate
    기동 때 집계를 한 번 돌린다. Reingest 와 <b>같이 켜지 않는다</b> —
    집계가 따라잡기 적재보다 먼저 끝난다 (decisions/OPEN.md).

.PARAMETER Reingest
    세이브를 처음부터 다시 적재한다. 수리용이다.

.PARAMETER NoBrowser
    다 뜬 뒤 브라우저를 열지 않는다.

.EXAMPLE
    .\run.ps1                 # 생성 켜짐 · 브라우저까지 연다
    .\run.ps1 -Port 8099
    .\run.ps1 -NoStory        # 생성 끄고 (회귀만 볼 때)
    .\run.ps1 -Aggregate
#>
[CmdletBinding()]
param(
    [int]$Port = 8088,
    [switch]$NoStory,
    [switch]$Aggregate,
    [switch]$Reingest,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

# 한글 로그가 깨지는 것을 막는다 (RUNBOOK 의 '자주 밟는 지뢰').
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# --- DB 비밀번호 -------------------------------------------------------------
# 이미 셸에 있으면 그것을 쓴다. 없을 때만 로컬 기본값을 넣는다 — 이 앱은 루프백에만
# 바인딩되는 1인용 로컬 앱이고(D59), 그 DB 는 개발용이다.
if (-not $env:TFM_DB_PASSWORD) {
    $env:TFM_DB_PASSWORD = 'postgres'
    $dbSource = '기본값'
} else {
    $dbSource = '셸에 있던 값'
}

# --- 생성 키 -----------------------------------------------------------------
# .env 는 앱이 직접 읽는다. 여기서는 <b>무엇이 이길지</b>만 알려준다 —
# 환경변수가 .env 보다 우선하므로, 셸에 남은 값이 파일을 조용히 가릴 수 있다.
$hasEnvKey = [bool]$env:TFM_GROQ_API_KEY
$hasDotEnv = (Test-Path '.env') -and
             (Select-String -Path '.env' -Pattern '^\s*TFM_GROQ_API_KEY\s*=\s*\S' -Quiet)

if ($hasEnvKey) {
    $shown = $env:TFM_GROQ_API_KEY
    if ($shown.Length -gt 4) { $shown = $shown.Substring(0, 4) }
    $keyNote = "환경변수 '$shown…' — .env 보다 우선한다"
} elseif ($hasDotEnv) {
    $keyNote = '.env 에 있다'
} else {
    # 생성을 켜고 띄우는데 키가 아무 데도 없으면, 버튼은 보이지만 누르는 순간 실패한다.
    # 그 사실을 <b>누르기 전에</b> 말한다 — 눌러야 아는 버튼은 버튼이 아니다.
    $keyNote = '없음 — 생성 버튼은 보이지만 누르면 실패한다'
}

# --- 인자 --------------------------------------------------------------------
$story = -not $NoStory

$appArgs = @("--server.port=$Port")
if ($story)     { $appArgs += '--tfm.story.enabled=true' }
if ($Aggregate) { $appArgs += '--tfm.aggregate-on-start=true' }
if ($Reingest)  { $appArgs += '--tfm.reingest-on-start=true' }

if ($Aggregate -and $Reingest) {
    # 막지 않고 경고만 한다. 수리 중에 일부러 그럴 수도 있고, 막아 버리면
    # 그 사정을 아는 사람이 스크립트를 우회하게 된다.
    Write-Warning '집계와 재적재를 같이 켰다. 집계가 따라잡기 적재보다 먼저 끝난다 — 최신 경기가 빠진 표가 나온다 (decisions/OPEN.md).'
}

$url = "http://127.0.0.1:$Port/"

Write-Host ''
Write-Host '  TeamFighter' -ForegroundColor Yellow
Write-Host "  주소     $url"
Write-Host "  DB 비번  $dbSource"
Write-Host "  생성     $(if ($story) { '켬' } else { '끔 (-NoStory)' })"
Write-Host "  키       $keyNote"
Write-Host ''
Write-Host '  Ctrl+C 로 끝낸다.' -ForegroundColor DarkGray
Write-Host ''

# --- 다 뜨면 브라우저를 연다 --------------------------------------------------
# bootRun 은 앞단을 잡고 있으므로 여는 일은 따로 돌린다. 포트가 열릴 때까지
# 기다렸다 여는 이유는, 바로 열면 "연결할 수 없음" 이 먼저 뜨기 때문이다.
if (-not $NoBrowser) {
    Start-Job -ScriptBlock {
        param($url, $port)
        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Milliseconds 500
            $up = Test-NetConnection -ComputerName '127.0.0.1' -Port $port `
                    -InformationLevel Quiet -WarningAction SilentlyContinue
            if ($up) { Start-Process $url; return }
        }
    } -ArgumentList $url, $Port | Out-Null
}

& .\gradlew.bat bootRun --console=plain "--args=$($appArgs -join ' ')"
