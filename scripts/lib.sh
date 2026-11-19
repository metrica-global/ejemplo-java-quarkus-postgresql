#!/usr/bin/env bash
# Shared helpers of the local demo scripts. Source it; do not run it.
#
# Everything here talks to the local system started with `make up`: the gateway, Keycloak and
# the SNS/SQS emulator, on the ports of docker-compose.yml.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"
# The modules of the application, in the order of com.zonacards.app.AppContainer.
MODULOS=(lotes giftcards)
# The compose services that run the application image: the nucleo and the own service of every
# module that can be promoted locally.
SERVICIOS_APP=(nucleo giftcards)
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

# --- topology: which deployment serves each module ----------------------------------------------
# It lives in three variables of .env, which docker-compose.yml reads. scripts/topologia.sh is
# the only writer.

env_valor() { # key default
  local valor=""
  [ -f "$ENV_FILE" ] && valor="$(grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2-)"
  echo "${valor:-$2}"
}

# Changes ONE variable of .env and leaves every other line as it is: the same file holds the
# keys that protect the card codes already stored in the local database.
env_fijar() { # key value
  touch "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  if grep -qE "^$1=" "$ENV_FILE"; then
    local tmp
    tmp="$(mktemp "$ENV_FILE.XXXXXX")"
    awk -v k="$1" -v v="$2" 'index($0, k "=") == 1 { print k "=" v; next } { print }' "$ENV_FILE" >"$tmp"
    chmod 600 "$tmp"
    mv "$tmp" "$ENV_FILE"
  else
    printf '%s=%s\n' "$1" "$2" >>"$ENV_FILE"
  fi
}

modulos_nucleo() { env_valor ZC_NUCLEO_MODULES "$(IFS=,; echo "${MODULOS[*]}")"; }
en_nucleo() { [[ ",$(modulos_nucleo)," == *",$1,"* ]]; }
# The compose service that receives the module's API: `nucleo`, or the module's own service.
ruta_de() { env_valor "ZC_RUTA_${1^^}" nucleo; }
promovido() { ! en_nucleo "$1" && [ "$(ruta_de "$1")" = "$1" ]; }
# Where the module runs when the topology is not in the middle of a change.
servicio_de() { ruta_de "$1"; }

replicas() { (cd "$ROOT" && docker compose ps --status running -q "$1" | wc -l | tr -d ' '); }

contenedores() { (cd "$ROOT" && docker compose ps --status running -q "$@"); }

# Runs a command in one running replica of a service, whatever its number is.
en_replica() { # service command...
  local servicio="$1" contenedor
  shift
  contenedor="$(contenedores "$servicio" | head -1)"
  [ -n "$contenedor" ] || morir "$servicio no tiene réplicas en ejecución"
  docker exec "$contenedor" "$@"
}

# The image ids the running replicas of the given services were created from, one per line.
imagenes() {
  local ids
  ids="$(contenedores "$@")"
  [ -n "$ids" ] || return 0
  # shellcheck disable=SC2086
  docker inspect --format '{{.Image}}' $ids | sort -u
}

replicas_sanas() {
  (cd "$ROOT" && docker compose ps --status running --format '{{.Health}}' "$1" | grep -c '^healthy$' || true)
}

# Changes the number of replicas of ONE service and waits until exactly that many are healthy.
# --no-deps: the database, the broker and the one-off tasks (migrations, topology) are left alone.
# --no-recreate: replicas that are running are never touched, only added or removed.
escalar() { # service replicas
  (cd "$ROOT" && docker compose up -d --no-deps --no-recreate --scale "$1=$2" "$1" >/dev/null 2>&1) || morir "no se pudo escalar $1 a $2"
  local limite=$((SECONDS + 180))
  until [ "$(replicas "$1")" = "$2" ] && [ "$(replicas_sanas "$1")" = "$2" ]; do
    [ "$SECONDS" -lt "$limite" ] || morir "$1 no llegó a $2 réplicas sanas en 180 s"
    sleep 1
  done
}

# Which replica stored each fragment of a lote, from the application's own logs: one line per
# fragment with the compose service and the container that generated it.
fragmentos_generados() { # lote-id since(RFC 3339)
  local servicio contenedor
  for servicio in "${SERVICIOS_APP[@]}"; do
    for contenedor in $(cd "$ROOT" && docker compose ps -a -q "$servicio"); do
      docker logs --since "$2" "$contenedor" 2>/dev/null |
        jq -Rr --arg id "$1" --arg s "$servicio" \
          'fromjson? | select(.message == "fragmento generado" and .mdc.lote_id == $id) | "\($s) \(.hostName)"'
    done
  done
}

# For the demos that need a module on its own: promotes it if it lives in the nucleo, and
# remembers to put the topology back with restaurar_topologia.
asegurar_promovido() { # module
  REINTEGRAR_AL_SALIR=""
  promovido "$1" && return 0
  en_nucleo "$1" && [ "$(replicas "$1")" = 0 ] || morir "la topología está a medio cambiar; revísela con: make topologia"
  echo "'$1' vive en el núcleo: se promueve para esta demo y se reintegra al terminar."
  "$ROOT/scripts/topologia.sh" promover "$1" >/dev/null
  REINTEGRAR_AL_SALIR="$1"
}

restaurar_topologia() {
  [ -n "${REINTEGRAR_AL_SALIR:-}" ] || return 0
  "$ROOT/scripts/topologia.sh" reintegrar "$REINTEGRAR_AL_SALIR" >/dev/null
  echo "'$REINTEGRAR_AL_SALIR' volvió al núcleo."
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
