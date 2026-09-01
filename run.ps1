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

.PARAMETER Story
    기사·갤러리 생성을 켠다 (tfm.story.enabled). 키가 있어야 실제로 나간다.

.PARAMETER Aggregate
    기동 때 집계를 한 번 돌린다. Reingest 와 <b>같이 켜지 않는다</b> —
    집계가 따라잡기 적재보다 먼저 끝난다 (decisions/OPEN.md).

.PARAMETER Reingest
    세이브를 처음부터 다시 적재한다. 수리용이다.

.PARAMETER NoBrowser
    다 뜬 뒤 브라우저를 열지 않는다.

.EXAMPLE
    .\run.ps1
    .\run.ps1 -Story
    .\run.ps1 -Port 8099 -Story
    .\run.ps1 -Aggregate
#>
[CmdletBinding()]
param(
    [int]$Port = 8088,
    [switch]$Story,
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
$keyNote = '없음 (.env 를 읽는다)'
if ($env:TFM_GROQ_API_KEY) {
    $shown = $env:TFM_GROQ_API_KEY
    if ($shown.Length -gt 4) { $shown = $shown.Substring(0, 4) }
    $keyNote = "환경변수 '$shown…' — .env 보다 우선한다"
}

# --- 인자 --------------------------------------------------------------------
$appArgs = @("--server.port=$Port")
if ($Story)     { $appArgs += '--tfm.story.enabled=true' }
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
Write-Host "  생성     $(if ($Story) { '켬' } else { '끔 (-Story 로 켠다)' })"
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
