# dsh-url.ps1 - print the launch URLs for the harness this machine is serving.
#
# The launcher prints them once, at server start, and publishes nothing: a
# ?token= URL in the served dist is a public sign-in for a harness that can run
# code on this host (see dsh-launch.ps1). This reads the token back out of the
# launcher's log, so a URL can be copied to a phone, a browser or another
# machine without touching the running server.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File dsh-url.ps1
#   powershell -ExecutionPolicy Bypass -File dsh-url.ps1 -Loop
#   powershell -ExecutionPolicy Bypass -File dsh-url.ps1 -Port 3082 -TailHost machine.tailnet-name.ts.net
param(
    # Authority to print a URL for. Defaults to this machine's tailnet name when
    # tailscale is installed, and to loopback only when it is not.
    [string]$TailHost,

    [int]$Port = 3080,

    # Print only the loopback URL.
    [switch]$Loop
)

$ErrorActionPreference = "Stop"

$listening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if (-not $listening) {
    Write-Host "nothing is serving port $Port - start dsh-launch.ps1 first" -ForegroundColor Red
    exit 1
}

if (-not $TailHost) {
    $tailscale = Get-Command tailscale -ErrorAction SilentlyContinue
    if ($tailscale) {
        $status = & tailscale status --json 2>$null | ConvertFrom-Json
        $TailHost = ($status.Self.DNSName -replace '\.$', '')
    }
}

$log = Join-Path (Join-Path $env:LOCALAPPDATA "dsh") "web.$Port.out.log"
$token = $null
if (Test-Path $log) {
    $text = Get-Content $log -Raw
    if ($text -match '\?token=([A-Za-z0-9_-]+)') { $token = $Matches[1] }
}
if (-not $token) {
    Write-Host "no launch token in $log" -ForegroundColor Red
    Write-Host "The launcher writes it there at startup. If the server was started by hand,"
    Write-Host "use the ?token= URL its own console printed, or restart it through dsh-launch.ps1."
    exit 1
}

$loopUrl = "http://127.0.0.1:$Port/?token=$token"
if ($Loop -or -not $TailHost) {
    Write-Host $loopUrl
} else {
    Write-Host "serve  : https://${TailHost}/?token=$token"
    Write-Host "direct : http://${TailHost}:$Port/?token=$token"
    Write-Host "loop   : $loopUrl"
}

# Say whether the token is still the live one: the server mints a new token on
# every start, so a log left over from an earlier run names a dead URL.
$code = 0
try {
    $request = [System.Net.HttpWebRequest]::Create($loopUrl)
    $request.AllowAutoRedirect = $false
    $request.Method = "GET"
    $response = $request.GetResponse()
    $code = [int]$response.StatusCode
    $response.Close()
} catch [System.Net.WebException] {
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
}
if ($code -ne 303) {
    Write-Host "(warning: that token answered HTTP $code - it is stale; restart through dsh-launch.ps1 for a fresh one)" -ForegroundColor Yellow
}
