#!/usr/bin/env bash
# Smoke test of a running Docker Compose stack: the things unit tests can't see. HTTPS with a
# verified certificate, the authentication rules, which ports answer, Elasticsearch's TLS and
# least-privilege user, and background search indexing. CI runs it on every pull request.
#
#   docker compose up -d --build && scripts/smoke-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."

HTTPS_PORT="${HTTPS_PORT:-8443}"
API="https://localhost:${HTTPS_PORT}"
ES_APP_PASSWORD="${ES_APP_PASSWORD:-app-local-only}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass() { echo "ok    $*"; }
fail() { echo "FAIL  $*" >&2; exit 1; }
expect() { # expect <description> <wanted> <got>
  if [ "$2" = "$3" ]; then pass "$1 ($3)"; else fail "$1: wanted $2, got $3"; fi
}
json() { python3 -c "import sys, json; print(json.load(sys.stdin)$1)"; }

echo "Waiting for the app"
for _ in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/actuator/health)" = 200 ] && break
  sleep 2
done
expect "health on the internal management port" 200 "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/actuator/health)"

# Caddy issues its local certificate at startup; take its root CA, and Elasticsearch's.
for _ in $(seq 1 30); do
  docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt "$WORK/caddy.crt" >/dev/null 2>&1 && break
  curl -sk -o /dev/null "$API/" || true
  sleep 1
done
[ -s "$WORK/caddy.crt" ] || fail "could not read Caddy's root certificate"
docker compose cp elasticsearch:/usr/share/elasticsearch/config/certs/ca/ca.crt "$WORK/es.crt" >/dev/null 2>&1
https() { curl -s --cacert "$WORK/caddy.crt" "$@"; }

echo "HTTPS entry point and authentication"
name="smoke_$(date +%s)"
signup=$(https -XPOST "$API/users" -H 'Content-Type: application/json' -d "{\"username\":\"$name\"}")
id=$(echo "$signup" | json "['id']") || fail "sign up over HTTPS: $signup"
token=$(echo "$signup" | json "['token']")
pass "sign up over HTTPS with a verified certificate (user $id)"
other=$(https -XPOST "$API/users" -H 'Content-Type: application/json' -d "{\"username\":\"${name}_b\"}" | json "['id']")

expect "level up with own token" 200 "$(https -o /dev/null -w '%{http_code}' -XPOST -H "Authorization: Bearer $token" "$API/users/$id/levels")"
expect "level up without a token" 401 "$(https -o /dev/null -w '%{http_code}' -XPOST "$API/users/$id/levels")"
expect "level up as another player" 403 "$(https -o /dev/null -w '%{http_code}' -XPOST -H "Authorization: Bearer $token" "$API/users/$other/levels")"
expect "actuator on the public port, with a token" 403 "$(https -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" "$API/actuator/prometheus")"
expect "plain HTTP to the HTTPS port" 400 "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${HTTPS_PORT}/")"

echo "Search (indexed in the background after sign up)"
found=""
for _ in $(seq 1 20); do
  found=$(https -H "Authorization: Bearer $token" "$API/users/search?q=$name" \
    | python3 -c "import sys, json; r = json.load(sys.stdin); print(r[0]['id'] if r else '')" 2>/dev/null || true)
  [ "$found" = "$id" ] && break
  sleep 1
done
expect "new player is searchable" "$id" "$found"

echo "Elasticsearch security"
expect "Elasticsearch over plain HTTP" 000 "$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://localhost:9200 || true)"
expect "Elasticsearch over HTTPS without credentials" 401 "$(curl -s --cacert "$WORK/es.crt" -o /dev/null -w '%{http_code}' https://localhost:9200)"
expect "app user reads its index" 200 "$(curl -s --cacert "$WORK/es.crt" -u "tournament_app:$ES_APP_PASSWORD" -o /dev/null -w '%{http_code}' https://localhost:9200/players/_count)"
expect "app user creates another index" 403 "$(curl -s --cacert "$WORK/es.crt" -u "tournament_app:$ES_APP_PASSWORD" -o /dev/null -w '%{http_code}' -X PUT https://localhost:9200/not-yours)"
expect "app container sees only the CA certificate" "ca.crt" "$(docker compose exec -T app ls /certs | tr -d '\r' | xargs)"

echo "Ports"
for service_port in postgres:5432 redis:6379 app:8080 app:8081 elasticsearch:9200; do
  bound=$(docker compose port "${service_port%%:*}" "${service_port##*:}" 2>/dev/null | head -1)
  case "$bound" in
    127.0.0.1:*) pass "$service_port published to localhost only ($bound)" ;;
    "") pass "$service_port not published" ;;
    *) fail "$service_port published on $bound" ;;
  esac
done

echo "Smoke test passed"
