#!/usr/bin/env bash
# Shared helpers of the local demo scripts. Source it; do not run it.
#
# Everything here talks to the local system started with `make up`: the gateway, Keycloak and
# the SNS/SQS emulator, on the ports of docker-compose.yml.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API="http://localhost:${ZC_PORT_API:-8080}/api/v1"
KEYCLOAK="http://localhost:${ZC_PORT_KEYCLOAK:-8081}/realms/zonacards/protocol/openid-connect/token"
FLOCI="http://localhost:${ZC_PORT_FLOCI:-14566}"
QUEUE_PREFIX="zonacards-local"

morir() {
  printf '\033[31m%s\033[0m\n' "$*" >&2
  exit 1
}

requisitos() {
  command -v jq >/dev/null || morir "falta jq"
  curl -fsS -o /dev/null "http://localhost:${ZC_PORT_API:-8080}/healthz" || morir "el sistema local no responde; ejecute: make up"
}

# The local realm uses the user name as password. Users: ana (operador, pe), luis (consulta, pe),
# eva (operador, ec).
token_de() {
  curl -fsS -d grant_type=password -d client_id=zonacards-cli -d "username=$1" -d "password=$1" "$KEYCLOAK" | jq -r .access_token
}

sqs() { # action json
  curl -fsS -X POST "$FLOCI/" -H 'Content-Type: application/x-amz-json-1.0' -H "X-Amz-Target: AmazonSQS.$1" \
    -H 'Authorization: AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=0' -d "$2"
}

cola_url() { echo "http://localhost:4566/000000000000/$QUEUE_PREFIX-$1"; }
cola_arn() { echo "arn:aws:sqs:us-east-1:000000000000:$QUEUE_PREFIX-$1"; }

# The two numbers CloudWatch publishes for a queue: waiting, and received but not yet deleted.
cola_visibles() {
  sqs GetQueueAttributes "{\"QueueUrl\":\"$(cola_url "$1")\",\"AttributeNames\":[\"ApproximateNumberOfMessages\"]}" |
    jq -r '.Attributes.ApproximateNumberOfMessages | tonumber'
}

cola_en_vuelo() {
  sqs GetQueueAttributes "{\"QueueUrl\":\"$(cola_url "$1")\",\"AttributeNames\":[\"ApproximateNumberOfMessagesNotVisible\"]}" |
    jq -r '.Attributes.ApproximateNumberOfMessagesNotVisible | tonumber'
}

replicas() { (cd "$ROOT" && docker compose ps --status running -q "$1" | wc -l | tr -d ' '); }

replicas_sanas() {
  (cd "$ROOT" && docker compose ps --status running --format '{{.Health}}' "$1" | grep -c '^healthy$' || true)
}

# Changes the number of replicas of ONE service and waits until exactly that many are healthy.
# --no-deps: the database, the broker and the one-off tasks (migrations, topology) are left alone.
escalar() { # service replicas
  (cd "$ROOT" && docker compose up -d --no-deps --no-recreate --scale "$1=$2" "$1" >/dev/null 2>&1) || morir "no se pudo escalar $1 a $2"
  local limite=$((SECONDS + 180))
  until [ "$(replicas "$1")" = "$2" ] && [ "$(replicas_sanas "$1")" = "$2" ]; do
    [ "$SECONDS" -lt "$limite" ] || morir "$1 no llegó a $2 réplicas sanas en 180 s"
    sleep 1
  done
}

# solicitar_lote TOKEN CANTIDAD DESCRIPCION  ->  prints the id
solicitar_lote() {
  curl -fsS -X POST "$API/lotes" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: demo-$(date +%s%N)-$RANDOM" \
    -d "{\"descripcion\":\"$3\",\"pais\":\"604\",\"moneda\":\"PEN\",\"monto\":50.00,\"cantidad\":$2,\"vigenciaHasta\":\"2027-12-31\"}" | jq -r .id
}

lote() { curl -fsS "$API/lotes/$2" -H "Authorization: Bearer $1"; } # token id

# Waits until the lote is GENERADO and prints how long it took, in seconds with decimals.
cronometrar_lote() { # token id timeout
  local inicio fin estado
  inicio="$(date +%s.%N)"
  local limite=$((SECONDS + $3))
  while :; do
    estado="$(lote "$1" "$2" | jq -r .estado)"
    [ "$estado" = GENERADO ] && break
    [ "$estado" = FALLIDO ] || [ "$estado" = ANULADO ] && morir "el lote $2 terminó en $estado"
    [ "$SECONDS" -lt "$limite" ] || morir "el lote $2 no terminó en $3 s"
    sleep 0.5
  done
  fin="$(date +%s.%N)"
  awk -v a="$inicio" -v b="$fin" 'BEGIN { printf "%.1f", b - a }'
}
