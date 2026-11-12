#!/bin/bash
# One-off Elasticsearch setup, run by the es-setup service in docker-compose.yml.
#
# 1. Creates a certificate authority and a TLS certificate for the "elasticsearch" host, once,
#    into the shared certs volume. Elasticsearch serves HTTPS with it; the app trusts only this CA.
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
# Elasticsearch runs as uid 1000; the CA's private key stays readable by it only.
chown -R 1000:0 config/certs
chmod 644 config/certs/ca/ca.crt
chmod 600 config/certs/ca/ca.key

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
