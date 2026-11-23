#!/bin/sh
# Keeps a Docker AgentMesh stack's public address current while it uses a free
# Cloudflare quick tunnel, whose trycloudflare.com address changes on every
# restart (macOS/Linux counterpart of scripts/start-agentmesh.ps1):
#   - points AM_PUBLIC_GATEWAY_URL at the live address and recreates the API/gateway;
#   - publishes it to the rendezvous gist (AM_RENDEZVOUS_URL in .env), which the
#     phone apps and the admin app read, so one QR code keeps working through
#     any number of address changes;
#   - recreates the tunnel when Cloudflare has dropped it.
# The GitHub token (gist scope) comes from AM_RENDEZVOUS_GITHUB_TOKEN or `gh auth token`.
#   sh scripts/docker-publicurl.sh &
# (The native Mac install does the same as a launchd service: com.agentmesh.publicurl.)
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENVF="$ROOT/infrastructure/docker/.env"
dc() { docker compose -f "$ROOT/infrastructure/docker/docker-compose.yml" --profile public --profile exit "$@"; }
get_env() { grep "^$1=" "$ENVF" 2>/dev/null | tail -n1 | cut -d= -f2-; }
set_env() { sed -i.bak "s|^$1=.*|$1=$2|" "$ENVF" && rm -f "$ENVF.bak"; }
log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*"; }
reachable() { curl -fsS -o /dev/null -m 10 "$1/agentmesh-ca.pem"; }

GIST=$(get_env AM_RENDEZVOUS_URL | sed 's|.*/gists/||')
TOKEN=${AM_RENDEZVOUS_GITHUB_TOKEN:-$(gh auth token 2>/dev/null)}
[ -n "$GIST" ] && [ -n "$TOKEN" ] || { echo "need AM_RENDEZVOUS_URL in .env and a GitHub token (gh auth login)" >&2; exit 1; }

published=""
misses=0
while :; do
  url=$(dc logs --no-log-prefix tunnel 2>&1 | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' | tail -n1)
  if [ -n "$url" ] && reachable "$url"; then
    misses=0
    if [ "$(get_env AM_PUBLIC_GATEWAY_URL)" != "$url" ]; then
      set_env AM_PUBLIC_GATEWAY_URL "$url"
      log "public address is now $url; recreating control-api and agent-gateway"
      dc up -d control-api agent-gateway >/dev/null 2>&1
    fi
    if [ "$published" != "$url" ]; then
      body=$(printf '{"files":{"agentmesh-endpoint.json":{"content":"{\\"url\\":\\"%s\\",\\"updated\\":\\"%s\\"}"}}}' "$url" "$(date -u +%Y-%m-%dT%H:%M:%SZ)")
      if curl -fsS -o /dev/null -m 20 -X PATCH -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
           -d "$body" "https://api.github.com/gists/$GIST"; then
        published=$url; log "rendezvous gist updated: $url"
      else
        log "could not update the rendezvous gist"
      fi
    fi
  elif [ -n "$url" ]; then
    misses=$((misses + 1))
    if [ "$misses" -ge 9 ]; then # ~3 minutes unreachable: Cloudflare dropped the quick tunnel
      log "$url is unreachable; recreating the tunnel"
      dc up -d --force-recreate tunnel >/dev/null 2>&1; misses=0
    fi
  fi
  sleep 20
done
