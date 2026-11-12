#!/bin/bash
# One-off Elasticsearch setup, run by the es-setup service in docker-compose.yml.
#
# 1. Creates a certificate authority and a TLS certificate for the "elasticsearch" host, once,
#    into the certs volume (which only Elasticsearch mounts). Elasticsearch serves HTTPS with it.
#    The app gets a separate volume with just the CA certificate, the only file it needs.
# 2. Waits for Elasticsearch, then creates a least-privilege user for the app: it may manage and
#    use the "players" index and read cluster health, nothing else. Re-running is harmless.
set -euo pipefail
cd /usr/share/elasticsearch

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

echo "Waiting for Elasticsearch"
until curl -s --cacert config/certs/ca/ca.crt https://elasticsearch:9200 | grep -q "missing authentication credentials"; do
  sleep 2
done

es() {
  curl -sf --cacert config/certs/ca/ca.crt -u "elastic:${ELASTIC_PASSWORD}" \
    -H "Content-Type: application/json" "$@"
}
echo "Creating role and user tournament_app"
es -X PUT https://elasticsearch:9200/_security/role/tournament_app -d '{
  "cluster": ["monitor"],
  "indices": [{"names": ["players"], "privileges": ["create_index", "manage", "read", "write", "view_index_metadata"]}]
}' > /dev/null
es -X PUT https://elasticsearch:9200/_security/user/tournament_app -d "{
  \"password\": \"${ES_APP_PASSWORD}\",
  \"roles\": [\"tournament_app\"]
}" > /dev/null
echo "Elasticsearch setup done"
