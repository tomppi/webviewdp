#!/usr/bin/env bash
# dsh-url.sh - print the launch URLs for the harness this machine is serving.
#
# The launcher prints them once, at server start, and publishes nothing: a
# ?token= URL in the served dist is a public sign-in for a harness that can run
# code on this host (see dsh-launch.sh). This reads the token back out of that
# launcher's log, so a URL can be copied into a phone, a browser or another
# machine without touching the running server.
#
# Usage:
#   dsh-url.sh                     # every authority this machine answers on
#   dsh-url.sh --loop              # loopback URL only
#   DSH_PORT=3082 dsh-url.sh       # a harness on another port
#   DSH_TAIL_HOST=x.ts.net dsh-url.sh
set -eu
(set -o pipefail) 2>/dev/null && set -o pipefail || true

PORT="${DSH_PORT:-3080}"
TAIL_HOST="${DSH_TAIL_HOST:-}"
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/dsh"
LOG="$STATE/web.$PORT.out.log"
ONLY_LOOP=0
[ "${1:-}" = "--loop" ] && ONLY_LOOP=1

if [ -z "$TAIL_HOST" ] && command -v tailscale >/dev/null 2>&1 && command -v jq >/dev/null 2>&1; then
  TAIL_HOST=$(tailscale status --json 2>/dev/null | jq -r '.Self.DNSName // empty' | sed 's/\.$//')
fi

if ! ss -ltnH "sport = :$PORT" 2>/dev/null | grep -q .; then
  echo "nothing is serving port $PORT - start dsh-launch.sh first" >&2
  exit 1
fi

# The launcher's log, wherever this account can reach it: the server often runs
# as root (the dsh.service unit does), and then its state dir is under /root and
# a desktop user needs sudo to read it. Nothing else is published - the token is
# a login for a machine that runs code.
token=""
log="$LOG"
for candidate in "$LOG" "/root/.local/state/dsh/web.$PORT.out.log"; do
  [ -f "$candidate" ] || continue
  if [ -r "$candidate" ]; then
    log="$candidate"
  else
    continue
  fi
  token=$(sed -n 's/.*?token=\([A-Za-z0-9_-]*\).*/\1/p' "$log" | head -n 1)
  [ -n "$token" ] && break
done
if [ -z "$token" ]; then
  if [ -r "$LOG" ] || [ -r "/root/.local/state/dsh/web.$PORT.out.log" ]; then
    echo "no launch token in $log - restart the server through dsh-launch.sh" >&2
  else
    cat >&2 <<MSG
no readable launch token log (tried $LOG and /root/.local/state/dsh/)
The launcher writes the token there at startup. If the server runs as another
user - the dsh.service unit runs it as root - run this again with sudo:

    sudo $0
MSG
  fi
  exit 1
fi

loop="http://127.0.0.1:$PORT/?token=$token"
if [ "$ONLY_LOOP" = "1" ] || [ -z "$TAIL_HOST" ]; then
  echo "$loop"
else
  echo "serve  : https://$TAIL_HOST/?token=$token"
  echo "direct : http://$TAIL_HOST:$PORT/?token=$token"
  echo "loop   : $loop"
fi

# Say whether the token is still the live one: the server mints a new token on
# every start, so a log left over from an earlier run names a dead URL. The
# exchange has no side effect worth worrying about - it mints one cookie.
if command -v curl >/dev/null 2>&1; then
  code=$(curl -s -o /dev/null -w '%{http_code}' "$loop" || true)
  if [ "$code" != "303" ]; then
    echo "(warning: that token answered HTTP ${code:-nothing} - it is stale; restart through dsh-launch.sh for a fresh one)" >&2
  fi
fi
