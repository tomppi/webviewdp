# dsh-launch.ps1 - start the dsh web server (built repo) and publish the
# launch-token URLs into the served dist as auth.json, so webviewdp (the
# Android app) and browsers authenticate by loading the printed ?token= URL -
# exactly once the server mints a 30-day cookie for whatever authority it saw.
#
# The harness does not write auth.json itself. It mints a launch token, prints
# a ?token= URL, and serves its dist directory as static files; this script is
# the bridge between those two facts. Without it clients get a 401, and the app
# shows its setup screen instead of the UI.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File dsh-launch.ps1 `
#       -TailHost machine.tailnet-name.ts.net -Repo C:\src\deepseek-harness
#
#   powershell -ExecutionPolicy Bypass -File dsh-launch.ps1 `
#       -TailHost 127.0.0.1 -Port 3082 -DistRoot C:\tmp\dist   # test
param(
    # Authority clients reach the harness by, and the --trusted-host the server
    # is started with. A tailnet name is what makes TLS and MagicDNS line up;
    # a bare address works for a local test.
    [Parameter(Mandatory = $true)][string]$TailHost,

    # A built checkout of deepseek-harness: apps\cli\lib\bin.js must exist.
    [string]$Repo = ".",

    [int]$Port = 3080,

    # Served static directory. Defaults to <Repo>\apps\web\dist.
    [string]$DistRoot
)

$ErrorActionPreference = "Stop"
$Repo = (Resolve-Path $Repo).Path
if (-not $DistRoot) { $DistRoot = Join-Path $Repo "apps\web\dist" }
$node = (Get-Command node -ErrorAction Stop).Source
$bin  = Join-Path $Repo "apps\cli\lib\bin.js"
$logDir = Join-Path $env:LOCALAPPDATA "dsh"
$log    = Join-Path $logDir "web.$Port.out.log"
$errLog = Join-Path $logDir "web.$Port.err.log"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# never stack a second server
$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Port $Port is already serving (pid $($existing.OwningProcess)); nothing to start."
    exit 0
}

# start the built server, output to log
Remove-Item -Force $log, $errLog -ErrorAction SilentlyContinue
$proc = Start-Process -FilePath $node -ArgumentList @($bin,"web","--host","127.0.0.1","--port",$Port,"--trusted-host",$TailHost,"--no-open") -WorkingDirectory $Repo -RedirectStandardOutput $log -RedirectStandardError $errLog -PassThru -WindowStyle Hidden

# wait for the printed auth URL (process alive, up to 10 minutes)
$token = $null
$deadline = (Get-Date).AddMinutes(10)
while (-not $token -and (Get-Date) -lt $deadline) {
    if ($proc.HasExited) {
        $tail = Get-Content $errLog -Tail 30 -ErrorAction SilentlyContinue
        throw "dsh web exited during startup:`n$tail"
    }
    if (Test-Path $log) {
        $line = Get-Content $log -Raw
        if ($line -match "dsh web:\s*\S+\?token=([A-Za-z0-9_-]+)") { $token = $Matches[1] }
    }
    if (-not $token) { Start-Sleep -Milliseconds 500 }
}
if (-not $token) { throw "no auth URL after 10 minutes; see $log and $errLog" }

# same token, one authenticated URL per authority
$loopUrl   = "http://127.0.0.1:$Port/?token=$token"
$directUrl = "http://${TailHost}:$Port/?token=$token"
$serveUrl  = "https://$TailHost/?token=$token"

# publish into the served dist (non-index assets are public)
$auth = [ordered]@{
    version  = 1
    issuedAt = (Get-Date).ToUniversalTime().ToString("o")
    urls     = [ordered]@{
        ("https://" + $TailHost)              = $serveUrl
        ("http://" + $TailHost + ":" + $Port) = $directUrl
        ("http://127.0.0.1:" + $Port)         = $loopUrl
    }
} | ConvertTo-Json -Depth 5
$authPath = Join-Path $DistRoot "auth.json"
Set-Content -Path $authPath -Value $auth -Encoding ascii

Write-Host "dsh web is up; launch token published:"
Write-Host "  serve  :  $serveUrl"
Write-Host "  direct :  $directUrl"
Write-Host "  loop   :  $loopUrl"
Write-Host "auth.json -> $authPath"

# keep the action alive while the server runs (scheduled-task parity)
while (-not $proc.HasExited) { Start-Sleep -Seconds 10 }
