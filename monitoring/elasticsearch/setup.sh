#!/bin/bash
# One-off Elasticsearch setup, run by two services in docker-compose.yml.
#
# setup.sh certs (the es-certs service, before Elasticsearch starts)
#   Creates a certificate authority and a TLS certificate for the "elasticsearch" host, once,
#   into the certs volume (which only Elasticsearch mounts). Elasticsearch serves HTTPS with it.
#   The app gets a separate volume with just the CA certificate, the only file it needs.
#
# setup.sh user (the es-setup service, once Elasticsearch is healthy)
#   Creates a least-privilege user for the app: it may manage and use the "players" index and
#   read cluster health, nothing else.
#
# Both are safe to run again.
set -euo pipefail
cd /usr/share/elasticsearch

create_certs() {
  if [ ! -f config/certs/ca/ca.crt ]; then
    echo "Creating CA"
    bin/elasticsearch-certutil ca --silent --pem -out config/certs/ca.zip
    unzip -q -o config/certs/ca.zip -d config/certs
  fi
  if [ ! -f config/certs/elasticsearch/elasticsearch.crt ]; then
    echo "Creating the Elasticsearch certificate"
    cat > config/certs/instances.yml <<'YAML'
instances:
  - name: elasticsearch
    dns: [elasticsearch, localhost]
    ip: [127.0.0.1]
YAML
    bin/elasticsearch-certutil cert --silent --pem -out config/certs/certs.zip \
      --in config/certs/instances.yml --ca-cert config/certs/ca/ca.crt --ca-key config/certs/ca/ca.key
    unzip -q -o config/certs/certs.zip -d config/certs
  fi
  # The archives hold private keys and are no longer needed once extracted.
  rm -f config/certs/ca.zip config/certs/certs.zip config/certs/instances.yml

  # Elasticsearch runs as uid 1000 and needs its own key, readable by it alone (certutil leaves it
  # world-readable). It never needs the CA's key, which stays with root. Certificates are public.
  chown -R 1000:0 config/certs
  chmod 600 config/certs/elasticsearch/elasticsearch.key
  chown 0:0 config/certs/ca/ca.key
  chmod 600 config/certs/ca/ca.key
  chmod 644 config/certs/ca/ca.crt config/certs/elasticsearch/elasticsearch.crt

  # The app only ever needs the CA certificate, so it gets a volume holding nothing else.
  cp config/certs/ca/ca.crt /ca-public/ca.crt
  chmod 644 /ca-public/ca.crt
  echo "Certificates ready"
}

# A healthy Elasticsearch can still refuse a request for a moment: right after it creates its
# security index, signing in as elastic fails until the index's primary shard is up
# (UnavailableShardsException on .security-7). Every call here is idempotent, so each one is
# retried until it succeeds, for up to two minutes.
es() {
  local attempt out
  for attempt in $(seq 1 60); do
    if out=$(curl -sS --fail-with-body --cacert /ca-public/ca.crt -u "elastic:${ELASTIC_PASSWORD}" \
        -H "Content-Type: application/json" "$@" 2>&1); then
      return 0
    fi
    out=${out//$'\n'/ }
    echo "Attempt $attempt failed, retrying: ${out:0:300}"
    sleep 2
  done
  echo "Giving up: ${out:0:1000}" >&2
  return 1
}

create_user() {
  echo "Creating role and user tournament_app"
  es -X PUT https://elasticsearch:9200/_security/role/tournament_app -d '{
    "cluster": ["monitor"],
    "indices": [{"names": ["players"], "privileges": ["create_index", "manage", "read", "write", "view_index_metadata"]}]
  }'
  es -X PUT https://elasticsearch:9200/_security/user/tournament_app -d "{
    \"password\": \"${ES_APP_PASSWORD}\",
    \"roles\": [\"tournament_app\"]
  }"
  echo "Elasticsearch setup done"
}

case "${1:-}" in
  certs) create_certs ;;
  user) create_user ;;
  *) echo "usage: setup.sh certs|user" >&2; exit 2 ;;
esac
