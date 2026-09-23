#!/usr/bin/env bash
# dsh-launch.sh - start the dsh web server from a source checkout and print the
# launch-token URLs, so a client holding one authenticates by loading it once -
# the server then mints a 30-day cookie for whatever authority it saw.
#
# The token is printed, never published. Every file in the served dist is public,
# and a launch URL in it is a working login - for a harness that can run code on
# this host - readable by anything that can reach the port (any tailnet device,
# and any process on this box). Clients that accept the ?token= URL directly
# (enderslicercura builds after 1.3.5, WebView DP after 1.4) need nothing there.
#
# Debian 13 target. Needs Node ^22.19 or >=24 (Debian ships 20.x - see README).
#
# Usage:
#   ./dsh-launch.sh                                  # background, prints token URLs
#   DSH_TAIL_HOST=box.tailXXXX.ts.net ./dsh-launch.sh
#   DSH_FOREGROUND=1 ./dsh-launch.sh                 # stay attached (systemd)
#   DSH_PUBLISH_AUTH_JSON=1 ./dsh-launch.sh          # publish for an old client
set -eu
# pipefail is a bash/ksh feature - enable it only where the shell supports it
(set -o pipefail) 2>/dev/null && set -o pipefail || true

REPO="${DSH_REPO:-$HOME/dsh-upstream}"
PORT="${DSH_PORT:-3080}"
DIST="${DSH_DIST_ROOT:-$REPO/apps/web/dist}"
BIN="$REPO/apps/cli/lib/bin.js"
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/dsh"
LOG="$STATE/web.$PORT.out.log"
ERR="$STATE/web.$PORT.err.log"

# The log carries the printed launch token, so keep it owner-only.
umask 077
mkdir -p "$STATE"

command -v node >/dev/null 2>&1 || { echo "node not found - install Node 22: see README dsh-setup.sh" >&2; exit 1; }
[ -f "$BIN" ] || { echo "no built dsh at $BIN - run dsh-update.sh (or dsh-setup.sh) first" >&2; exit 1; }

major=$(node -p 'process.versions.node.split(".")[0]')
minor=$(node -p 'process.versions.node.split(".")[1]')
if [ "$major" -lt 22 ] || { [ "$major" -eq 22 ] && [ "$minor" -lt 19 ]; }; then
  echo "node $(node -v) is below dsh's floor (^22.19 or >=24)" >&2
  exit 1
fi

# tailnet name: explicit env var wins, otherwise ask tailscale (needs jq)
TAIL_HOST="${DSH_TAIL_HOST:-}"
if [ -z "$TAIL_HOST" ] && command -v tailscale >/dev/null 2>&1 && command -v jq >/dev/null 2>&1; then
  TAIL_HOST=$(tailscale status --json 2>/dev/null | jq -r '.Self.DNSName // empty' | sed 's/\.$//')
fi

if ss -ltnH "sport = :$PORT" 2>/dev/null | grep -q .; then
  echo "port $PORT is already serving; nothing to start"
  exit 0
fi

rm -f "$LOG" "$ERR"
# no arrays: the old `args=(...)` here was bash-only and dash rejected it
if [ -n "$TAIL_HOST" ]; then
  setsid node "$BIN" web --host 127.0.0.1 --port "$PORT" --no-open --trusted-host "$TAIL_HOST" >"$LOG" 2>"$ERR" </dev/null &
else
  setsid node "$BIN" web --host 127.0.0.1 --port "$PORT" --no-open >"$LOG" 2>"$ERR" </dev/null &
fi
pid=$!

# wait for the printed auth URL (up to 10 minutes)
token=""
for _ in $(seq 1 1200); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "dsh web exited during startup:" >&2
    tail -n 30 "$ERR" >&2 || true
    exit 1
  fi
  token=$(sed -n 's/.*dsh web:.*?token=\([A-Za-z0-9_-]*\).*/\1/p' "$LOG" 2>/dev/null | head -n 1)
  if [ -n "$token" ]; then break; fi
  sleep 0.5
done
if [ -z "$token" ]; then
  echo "no auth URL after 10 minutes; see $LOG and $ERR" >&2
  exit 1
fi

loop="http://127.0.0.1:$PORT/?token=$token"
issued=$(date -u +%Y-%m-%dT%H:%M:%SZ)

echo "dsh web is up. Paste one of these into the client:"
if [ -n "$TAIL_HOST" ]; then
  echo "serve  : https://$TAIL_HOST/?token=$token"
  echo "direct : http://$TAIL_HOST:$PORT/?token=$token"
fi
echo "loop   : $loop"

# Publishing is off by default. go.html carries the same token behind a shorter
# name, so the switch covers both, and a stale pair from an earlier run is
# removed rather than left readable.
if [ "${DSH_PUBLISH_AUTH_JSON:-0}" = "1" ]; then
  if [ -n "$TAIL_HOST" ]; then
    cat > "$DIST/auth.json" <<JSON
{
  "version": 1,
  "issuedAt": "$issued",
  "urls": {
    "https://$TAIL_HOST": "https://$TAIL_HOST/?token=$token",
    "http://$TAIL_HOST:$PORT": "http://$TAIL_HOST:$PORT/?token=$token",
    "http://127.0.0.1:$PORT": "$loop"
  }
}
JSON
  else
    cat > "$DIST/auth.json" <<JSON
{
  "version": 1,
  "issuedAt": "$issued",
  "urls": {
    "http://127.0.0.1:$PORT": "$loop"
  }
}
JSON
  fi
  # Stable auto-login page: /go.html carries the current launch token and
  # redirects to a RELATIVE /?token=..., so one bookmark works on loopback and
  # over the tailnet and keeps working after every restart (the token rotates).
  cat > "$DIST/go.html" <<HTML
<!doctype html>
<meta charset="utf-8">
<title>DeepSeek Harness</title>
<meta http-equiv="refresh" content="0; url=/?token=$token">
<p>Signing in to DeepSeek Harness&hellip;</p>
HTML
  echo "auth.json -> $DIST/auth.json"
  echo "go.html   -> $DIST/go.html"
  echo "  (public: anyone who can reach port $PORT can sign in with either)"
else
  rm -f "$DIST/auth.json" "$DIST/go.html"
  echo "auth.json: not written (DSH_PUBLISH_AUTH_JSON=1 publishes it for an old client)"
fi

# Client defaults: the one thing a freshly installed client cannot know is which
# directory to root its first session in on this host. Published in the same
# public dist, so it discloses a path (never a credential) to anything that can
# reach the port; set DSH_DEFAULT_WORKSPACE in the unit to the tree clients
# should work in.
default_workspace="${DSH_DEFAULT_WORKSPACE:-$REPO}"
cat > "$DIST/defaults.json" <<JSON
{
  "version": 1,
  "issuedAt": "$issued",
  "workspace": "$default_workspace"
}
JSON
echo "defaults: workspace $default_workspace -> $DIST/defaults.json"

if [ "${DSH_FOREGROUND:-0}" = "1" ] || [ -n "${INVOCATION_ID:-}" ]; then
  echo "dsh web running as pid $pid (foreground; log $LOG)"
  wait "$pid"
else
  echo "dsh web pid $pid (log $LOG)"
fi
