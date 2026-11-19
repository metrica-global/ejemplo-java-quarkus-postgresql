#!/usr/bin/env bash
# End-to-end scenarios against the local system (make up). Nothing is mocked: real Keycloak
# tokens, the gateway, the application with its replicas, PostgreSQL and the SNS/SQS emulator.
#
#   scripts/e2e.sh              all scenarios
#   scripts/e2e.sh saga dlq     only the named ones (see the list at the bottom)
#
# The full run exercises the application in both topologies. It starts with every module in
# the nucleo, PROMOTES giftcards to its own service in the middle (same image, configuration
# only, while a client keeps calling the API), runs the scenarios that need the module on its
# own, and REINTEGRATES it at the end, leaving the system as it found it. The scenarios do not
# name a service: they ask the topology who serves the module, so each of them can also be run
# by itself in either topology.
#
# Requires: docker compose, curl and jq.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$ROOT"

GRAFANA="http://localhost:${ZC_PORT_GRAFANA:-3000}"
RUN_ID="e2e-$(date +%s)-$$"

# --- output ------------------------------------------------------------------------------------

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok() { printf '  \033[32m✔\033[0m %s\n' "$*"; }
info() { printf '    %s\n' "$*"; }
fail() {
  printf '  \033[31m✘ %s\033[0m\n' "$*" >&2
  exit 1
}

# --- helpers -----------------------------------------------------------------------------------

token_de_integracion() { # service account: client credentials
  curl -fsS -d grant_type=client_credentials -d client_id=zonacards-integracion -d client_secret=integracion-local "$KEYCLOAK" | jq -r .access_token
}

# api METHOD PATH TOKEN [BODY] [extra curl args...]  ->  sets STATUS and BODY
api() {
  local method="$1" path="$2" token="$3" body="${4:-}"
  shift $(($# < 4 ? $# : 4))
  local args=(-sS -X "$method" -w $'\n%{http_code}' "$API$path" "$@")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local out
  out="$(curl "${args[@]}")"
  STATUS="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}

espera_status() { # expected description
  [ "$STATUS" = "$1" ] || fail "$2: se esperaba HTTP $1 y llegó $STATUS: $BODY"
}

campo() { jq -r "$1" <<<"$BODY"; }

tokens() {
  ANA="$(token_de ana)"   # rol operador, tenant pe
  LUIS="$(token_de luis)" # rol consulta, tenant pe
  EVA="$(token_de eva)"   # rol operador, tenant ec
}

sql() { docker compose exec -T postgres psql -U postgres -d zonacards -v ON_ERROR_STOP=1 -Atc "$1"; }

sql_como() { # role password sql  ->  output and errors, never fails
  { docker compose exec -T -e PGPASSWORD="$2" postgres psql -h 127.0.0.1 -U "$1" -d zonacards -Atc "$3" 2>&1 || true; } | head -1
}

mensajes() { echo $(($(cola_visibles "$1") + $(cola_en_vuelo "$1"))); } # waiting + in flight

# espera SECONDS DESCRIPTION COMMAND...   (the command must succeed before the deadline)
espera() {
  local timeout="$1" what="$2"
  shift 2
  local deadline=$((SECONDS + timeout))
  until "$@" >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$deadline" ] || fail "no ocurrió en ${timeout}s: $what"
    sleep 1
  done
}

crear_lote() { # token cantidad descripcion  ->  sets LOTE
  local key="$RUN_ID-$RANDOM$RANDOM"
  api POST /lotes "$1" "{\"descripcion\":\"$3\",\"pais\":\"604\",\"moneda\":\"PEN\",\"monto\":50.00,\"cantidad\":$2,\"vigenciaHasta\":\"2027-12-31\"}" \
    -H "Idempotency-Key: $key"
  espera_status 201 "crear lote"
  LOTE="$(campo .id)"
}

lote_cumple() { # token id jq-condition
  api GET "/lotes/$2" "$1"
  [ "$STATUS" = 200 ] && jq -e "$3" <<<"$BODY" >/dev/null
}

total_giftcards() { # token query
  api GET "/giftcards?$2&pageSize=1" "$1"
  espera_status 200 "listar gift cards"
  campo .page.total
}

# Conditions for `espera`: functions, so they are evaluated again on every attempt.
cola_de_trabajo_vacia() { [ "$(mensajes giftcards-generacion)" = 0 ]; }
cola_con_al_menos() { [ "$(mensajes "$1")" -ge "$2" ]; }
cola_con_en_vuelo() { [ "$(cola_en_vuelo "$1")" -ge 1 ]; }

replicas_son() { [ "$(replicas "$1")" = "$2" ]; }
replicas_sanas_son() { [ "$(replicas_sanas "$1")" = "$2" ]; }

# Which compose service answered a request that came in through the gateway. nginx reports the
# address of the container it used (the last one, if it had to retry).
atiende() { # path
  local ip servicio contenedor
  ip="$(curl -sS -o /dev/null -D - "$API$1" -H "Authorization: Bearer $ANA" | tr -d '\r' |
    awk -F': ' 'tolower($1) == "x-zonacards-upstream" { n = split($2, a, ", "); split(a[n], b, ":"); print b[1] }')"
  for servicio in "${SERVICIOS_APP[@]}"; do
    for contenedor in $(contenedores "$servicio"); do
      if [ "$(docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$contenedor")" = "$ip" ]; then
        echo "$servicio"
        return
      fi
    done
  done
  echo "desconocido ($ip)"
}

# A client that keeps calling both APIs through the gateway while the topology changes.
sonda_iniciar() {
  SONDA_LOG="$(mktemp)"
  (
    while :; do
      for ruta in "/lotes?pageSize=1" "/giftcards?pageSize=1"; do
        printf '%s %s\n' "$ruta" "$(curl -s -o /dev/null -m 15 -w '%{http_code}' "$API$ruta" -H "Authorization: Bearer $ANA")" >>"$SONDA_LOG"
      done
      sleep 0.1
    done
  ) &
  SONDA_PID=$!
}

sonda_detener() { # sets SONDA_TOTAL and SONDA_FALLIDAS
  kill "$SONDA_PID" 2>/dev/null || true
  wait "$SONDA_PID" 2>/dev/null || true
  SONDA_TOTAL="$(wc -l <"$SONDA_LOG" | tr -d ' ')"
  SONDA_FALLIDAS="$(grep -vc ' 200$' "$SONDA_LOG" || true)"
  SONDA_DETALLE="$(grep -v ' 200$' "$SONDA_LOG" | sort | uniq -c | tr '\n' ';' || true)"
  rm -f "$SONDA_LOG"
}

# The loops of a module, as the liveness check of ONE replica reports them.
bucles_de() { # service module
  en_replica "$1" curl -fsS http://localhost:8080/q/health/live |
    jq -r --arg m "$2/" '[.checks[] | select(.name == "eventx-loops") | .data // {} | keys[] | select(startswith($m))] | length'
}

# Fragments of a lote generated by each compose service: "nucleo=7 giftcards=9".
reparto_por_servicio() { # lote-id since
  fragmentos_generados "$1" "$2" | awk '{ n[$1]++ } END { for (s in n) printf "%s=%d ", s, n[s] }'
}

traza_entre() { # service-of-lotes service-of-giftcards  ->  sets TRAZA
  local buscar="{ resource.service.name = \"$1\" && name = \"POST /api/v1/lotes\" } && { resource.service.name = \"$2\" && span.zonacards.module = \"giftcards\" && kind = consumer }"
  TRAZA="$(curl -fsS -G "$GRAFANA/api/datasources/proxy/uid/tempo/api/search" --data-urlencode "q=$buscar" --data-urlencode start="$3" --data-urlencode end="$(($(date +%s) + 60))" --data-urlencode limit=1 | jq -re '.traces[0].traceID')"
}

# --- scenarios ---------------------------------------------------------------------------------

escenario_seguridad() {
  bold "Seguridad: tokens reales de Keycloak, permisos por rol y aislamiento por tenant"
  api GET /lotes ""
  espera_status 401 "sin token"
  ok "sin token: 401"

  api GET /lotes "not-a-token"
  espera_status 401 "token inválido"
  ok "token inválido: 401"

  api GET /lotes "$LUIS"
  espera_status 200 "luis lista lotes"
  api POST /lotes "$LUIS" '{"descripcion":"x","pais":"604","moneda":"PEN","monto":1,"cantidad":1,"vigenciaHasta":"2027-12-31"}' -H "Idempotency-Key: $RUN_ID-luis"
  espera_status 403 "luis crea un lote"
  [ "$(campo .code)" = AUTH_ACCESS_DENIED ] || fail "código inesperado: $BODY"
  ok "luis (rol consulta) puede leer pero no crear: 403 AUTH_ACCESS_DENIED"

  api GET "/lotes?pageSize=1" "$(token_de_integracion)"
  espera_status 200 "cuenta de servicio"
  ok "la cuenta de servicio (client credentials) entra con sus permisos"

  crear_lote "$ANA" 1 "Lote de Perú"
  api GET "/lotes/$LOTE" "$EVA"
  espera_status 404 "eva lee un lote de otro tenant"
  api DELETE "/lotes/$LOTE" "$EVA"
  espera_status 404 "eva anula un lote de otro tenant"
  ok "eva (tenant ec) no ve ni puede anular un lote del tenant pe: 404"

  local interno
  interno="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${ZC_PORT_API:-8080}/q/health/live")"
  [ "$interno" = 404 ] || fail "el gateway expone rutas internas: HTTP $interno"
  ok "el gateway solo publica las API de los módulos (/api/v1/lotes y /api/v1/giftcards)"
}

escenario_saga() {
  bold "Saga de emisión: POST /lotes -> eventos -> generación por fragmentos -> GENERADO"
  local key="$RUN_ID-saga" body='{"descripcion":"Campaña navideña","pais":"604","moneda":"PEN","monto":50.00,"cantidad":1000,"vigenciaHasta":"2027-12-31"}'
  local started=$SECONDS
  api POST /lotes "$ANA" "$body" -H "Idempotency-Key: $key"
  espera_status 201 "crear lote"
  LOTE="$(campo .id)"
  [ "$(campo .estado)" = SOLICITADO ] || fail "estado inicial inesperado: $BODY"
  ok "201 inmediato: lote $LOTE en SOLICITADO"

  api POST /lotes "$ANA" "$body" -H "Idempotency-Key: $key"
  espera_status 200 "reintento con la misma llave"
  [ "$(campo .id)" = "$LOTE" ] || fail "el reintento creó otro lote"
  api POST /lotes "$ANA" "${body/1000/999}" -H "Idempotency-Key: $key"
  espera_status 409 "misma llave, otro contenido"
  ok "Idempotency-Key: el reintento devuelve el mismo lote (200) y otro contenido con la misma llave es 409"

  espera 180 "lote GENERADO" lote_cumple "$ANA" "$LOTE" '.estado == "GENERADO"'
  ok "GENERADO en $((SECONDS - started))s: $(campo '.progreso | "\(.fragmentosCompletados)/\(.fragmentosTotales) fragmentos, \(.generadas) gift cards"')"

  [ "$(total_giftcards "$ANA" "loteId=$LOTE")" = 1000 ] || fail "la API no lista 1000 gift cards"
  local fila
  fila="$(sql "SELECT count(*), count(DISTINCT secuencia), count(DISTINCT codigo_indice), min(secuencia), max(secuencia), count(*) FILTER (WHERE estado <> 'GENERADA') FROM giftcards.giftcard WHERE lote_id = '$LOTE'")"
  [ "$fila" = "1000|1000|1000|1|1000|0" ] || fail "invariante rota en la base: $fila"
  ok "en la base: exactamente 1000 gift cards, secuencias 1..1000 sin huecos, 1000 códigos distintos"

  api GET "/giftcards?loteId=$LOTE&pageSize=1" "$ANA"
  [ "$(campo '.items[0].codigo // "oculto"')" = oculto ] || fail "la API devolvió un código en claro"
  local columnas
  columnas="$(sql "SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY column_name) FROM information_schema.columns WHERE table_schema = 'giftcards' AND table_name = 'giftcard' AND column_name IN ('codigo_cifrado', 'codigo_indice', 'cvv_hash')")"
  ok "ni la API ni la base guardan el código o el CVV en claro ($columnas; máscara $(campo '.items[0].codigoEnmascarado'))"
}

escenario_unitaria() {
  bold "CRUD de gift cards: emitir, consultar, extender vigencia, activar y anular"
  api POST /giftcards "$ANA" '{"pais":"604","moneda":"PEN","monto":75.50,"vigenciaHasta":"2027-12-31"}'
  espera_status 201 "emitir"
  local id codigo
  id="$(campo .id)"
  codigo="$(campo .codigo)"
  [[ "$codigo" =~ ^604[0-9]{13}$ ]] || fail "código inesperado: $codigo"
  ok "emitida: el código y el CVV se muestran una sola vez, en la respuesta de emisión"

  api GET "/giftcards/$id" "$ANA"
  espera_status 200 "consultar"
  [ "$(campo '.codigo // "oculto"')" = oculto ] && [ "$(campo '.cvv // "oculto"')" = oculto ] || fail "la consulta devolvió secretos"
  ok "consulta: solo la máscara $(campo .codigoEnmascarado)"

  api PUT "/giftcards/$id" "$ANA" "{\"vigenciaHasta\":\"2028-06-30\",\"version\":$(campo .version)}"
  espera_status 200 "extender vigencia"
  api PUT "/giftcards/$id" "$ANA" '{"vigenciaHasta":"2029-01-01","version":0}'
  espera_status 409 "versión obsoleta"
  ok "vigencia extendida; una versión obsoleta es 409 (bloqueo optimista)"

  api POST "/giftcards/$id/activar" "$ANA"
  espera_status 200 "activar"
  [ "$(campo .estado)" = ACTIVA ] || fail "no quedó activa: $BODY"
  api DELETE "/giftcards/$id?motivo=Reportada%20como%20extraviada" "$ANA"
  espera_status 200 "anular"
  api POST "/giftcards/$id/activar" "$ANA"
  espera_status 422 "activar una anulada"
  ok "activada y luego anulada; activar una anulada es 422 $(campo .code)"
}

escenario_anulacion() {
  bold "Anulación de un lote: después, durante y antes de la generación"

  # After: every card exists and every card must end up annulled.
  crear_lote "$ANA" 500 "Anular después"
  local despues="$LOTE"
  espera 120 "lote GENERADO" lote_cumple "$ANA" "$despues" '.estado == "GENERADO"'
  api DELETE "/lotes/$despues?motivo=Campa%C3%B1a%20cancelada" "$ANA"
  espera_status 200 "anular lote"
  [ "$(campo .estado)" = ANULADO ] || fail "el lote no quedó ANULADO: $BODY"
  espera 60 "anulación aplicada en giftcards" lote_cumple "$ANA" "$despues" '.giftcardsAnuladas == 500'
  [ "$(total_giftcards "$ANA" "loteId=$despues&estado=ANULADA")" = 500 ] || fail "no se anularon las 500 gift cards"
  ok "después de generar: las 500 gift cards quedaron ANULADA y lotes recibió la confirmación"

  # During: the annulment races with the workers that are storing fragments.
  crear_lote "$ANA" 4000 "Anular durante"
  local durante="$LOTE"
  espera 120 "primeros fragmentos generados" lote_cumple "$ANA" "$durante" '.progreso.fragmentosCompletados >= 2'
  api DELETE "/lotes/$durante" "$ANA"
  espera_status 200 "anular lote en generación"
  espera 120 "anulación aplicada en giftcards" lote_cumple "$ANA" "$durante" '.anulacionAplicadaEn != null'
  local anuladas
  anuladas="$(campo .giftcardsAnuladas)"
  espera 180 "cola de trabajo vacía" cola_de_trabajo_vacia
  local fila
  fila="$(sql "SELECT count(*), count(*) FILTER (WHERE estado <> 'ANULADA') FROM giftcards.giftcard WHERE lote_id = '$durante'")"
  [ "$fila" = "$anuladas|0" ] || fail "quedaron gift cards vivas o se generaron después de anular: $fila (anuladas: $anuladas)"
  [ "$anuladas" -lt 4000 ] || fail "la anulación llegó cuando el lote ya estaba completo; no se probó la carrera"
  ok "durante la generación: se detuvo en $anuladas de 4000; ninguna quedó viva y no se generó ninguna más"

  # Before: giftcards is down, so the request and the annulment wait together in its inbox and
  # arrive in whatever order the broker decides.
  if ! promovido giftcards; then
    info "caso 'antes' omitido: necesita detener solo giftcards, y con el módulo en el núcleo detenerlo detiene también la API de lotes (make promover MODULO=giftcards)"
    return
  fi
  local antes_replicas
  antes_replicas="$(replicas giftcards)"
  docker compose stop giftcards >/dev/null 2>&1
  crear_lote "$ANA" 500 "Anular antes"
  local antes="$LOTE"
  api DELETE "/lotes/$antes" "$ANA"
  espera_status 200 "anular lote recién solicitado"
  ok "con giftcards detenido por completo, la API de lotes sigue aceptando y anulando lotes"
  espera 30 "los dos eventos en la cola de giftcards" cola_con_al_menos giftcards-inbox 2
  docker compose up -d --no-deps --no-recreate --scale "giftcards=$antes_replicas" giftcards >/dev/null 2>&1
  espera 120 "anulación aplicada en giftcards" lote_cumple "$ANA" "$antes" '.anulacionAplicadaEn != null'
  espera 120 "cola de trabajo vacía" cola_de_trabajo_vacia
  fila="$(sql "SELECT count(*) FILTER (WHERE estado <> 'ANULADA') FROM giftcards.giftcard WHERE lote_id = '$antes'")"
  [ "$fila" = 0 ] || fail "quedaron $fila gift cards vivas en un lote anulado"
  ok "antes de que giftcards se enterara (módulo detenido): el lote terminó ANULADO sin gift cards vivas"
}

escenario_caida() {
  bold "Caída de una tarea a mitad de un fragmento (docker kill -9)"
  local servicio antes
  servicio="$(servicio_de giftcards)"
  antes="$(replicas "$servicio")"
  crear_lote "$ANA" 2000 "Sobrevive a una caída"
  espera 60 "fragmentos en proceso" cola_con_en_vuelo giftcards-generacion
  sleep 3
  local victima
  victima="$(contenedores "$servicio" | head -1)"
  docker kill --signal KILL "$victima" >/dev/null
  ok "tarea $(docker inspect --format '{{.Name}}' "$victima" | tr -d /) eliminada con fragmentos en proceso"
  # What the ECS scheduler does by itself: replace the task that died.
  docker compose up -d --no-deps --no-recreate --scale "$servicio=$antes" "$servicio" >/dev/null 2>&1
  local started=$SECONDS
  espera 240 "lote GENERADO pese a la caída" lote_cumple "$ANA" "$LOTE" '.estado == "GENERADO"'
  local fila
  fila="$(sql "SELECT count(*), count(DISTINCT secuencia) FROM giftcards.giftcard WHERE lote_id = '$LOTE'")"
  [ "$fila" = "2000|2000" ] || fail "el lote no quedó exacto tras la caída: $fila"
  ok "el fragmento interrumpido volvió a la cola y otra tarea lo completó: exactamente 2000 gift cards, sin duplicados (${SECONDS}s de prueba, $((SECONDS - started))s tras la caída)"
  espera 120 "$servicio de vuelta a $antes réplicas sanas" replicas_sanas_son "$servicio" "$antes"
}

escenario_dlq() {
  bold "Cola de mensajes fallidos (DLQ) y redrive"
  # A defect that makes one handler fail: giftcards loses the permission to store plans.
  sql "REVOKE INSERT ON giftcards.plan_generacion FROM giftcards_app" >/dev/null
  trap 'sql "GRANT INSERT ON giftcards.plan_generacion TO giftcards_app" >/dev/null 2>&1 || true' EXIT
  crear_lote "$ANA" 250 "Atascado y recuperado"
  info "defecto introducido; el evento lote-solicitado falla y se reintenta con espera creciente..."
  espera 240 "mensaje en la DLQ" cola_con_al_menos giftcards-inbox-dlq 1
  lote_cumple "$ANA" "$LOTE" '.estado == "SOLICITADO"' || fail "el lote avanzó pese al fallo: $BODY"
  ok "tras agotar los reintentos el mensaje quedó en giftcards-inbox-dlq; el lote sigue SOLICITADO y nada se perdió"

  sql "GRANT INSERT ON giftcards.plan_generacion TO giftcards_app" >/dev/null
  trap - EXIT
  sqs StartMessageMoveTask "{\"SourceArn\":\"$(cola_arn giftcards-inbox-dlq)\"}" >/dev/null
  ok "defecto corregido y redrive iniciado (aws sqs start-message-move-task)"
  espera 120 "lote GENERADO tras el redrive" lote_cumple "$ANA" "$LOTE" '.estado == "GENERADO"'
  [ "$(mensajes giftcards-inbox-dlq)" = 0 ] || fail "la DLQ no quedó vacía"
  ok "el mismo mensaje se procesó y el lote llegó a GENERADO; la DLQ quedó vacía"
}

escenario_aislamiento() {
  bold "Base de datos: un esquema por módulo y roles de mínimo privilegio, también dentro de un mismo proceso"
  local salida
  salida="$(sql_como lotes_app lotes-app-local 'SELECT count(*) FROM giftcards.giftcard')"
  [[ "$salida" == *"permission denied"* ]] || fail "lotes_app pudo leer el esquema giftcards: $salida"
  ok "lotes_app no puede leer giftcards: ${salida##*ERROR:  }"

  salida="$(sql_como giftcards_app giftcards-app-local 'SELECT count(*) FROM lotes.lote')"
  [[ "$salida" == *"permission denied"* ]] || fail "giftcards_app pudo leer el esquema lotes: $salida"
  ok "giftcards_app no puede leer lotes: ${salida##*ERROR:  }"

  salida="$(sql_como giftcards_app giftcards-app-local 'CREATE TABLE giftcards.intrusa (id int)')"
  [[ "$salida" == *"permission denied"* ]] || fail "giftcards_app pudo crear una tabla: $salida"
  salida="$(sql_como giftcards_app giftcards-app-local 'ALTER TABLE giftcards.giftcard ADD COLUMN x int')"
  [[ "$salida" == *"must be owner"* ]] || fail "giftcards_app pudo alterar una tabla: $salida"
  ok "el rol de ejecución no puede cambiar el esquema; solo la tarea de migración (rol dueño)"

  salida="$(sql_como giftcards_app giftcards-app-local 'SELECT count(*) FROM giftcards.giftcard')"
  [[ "$salida" =~ ^[0-9]+$ ]] || fail "giftcards_app no puede leer su propio esquema: $salida"
  ok "y sí opera sobre su propio esquema ($salida gift cards)"
}

escenario_observabilidad() {
  bold "Observabilidad: salud, métricas y una traza que cruza los dos módulos"
  local servicio salud
  servicio="$(servicio_de giftcards)"
  salud="$(en_replica "$servicio" curl -fsS http://localhost:8080/q/health/live)"
  jq -e '.status == "UP" and (.checks[] | select(.name == "eventx-loops") | .data["giftcards/consumer-giftcards-generacion"] == "alive" and .data["giftcards/outbox-relay"] == "alive")' <<<"$salud" >/dev/null ||
    fail "liveness inesperado: $salud"
  ok "liveness (en '$servicio') incluye el latido del relay y de los consumidores de cada módulo que atiende: $(jq -r '[.checks[] | select(.name == "eventx-loops") | .data | keys[]] | join(", ")' <<<"$salud")"

  local metricas
  metricas="$(en_replica "$servicio" curl -fsS http://localhost:8080/q/metrics | grep -E '^zonacards_(outbox_pending|messages_consumed_seconds_count)\{module="giftcards"' | head -3)"
  [ -n "$metricas" ] || fail "no hay métricas zonacards_* con la etiqueta module"
  ok "métricas Prometheus en /q/metrics, etiquetadas por módulo, por ejemplo: $(head -1 <<<"$metricas")"

  local desde
  desde="$(date +%s)"
  crear_lote "$ANA" 250 "Traza de extremo a extremo"
  espera 120 "lote GENERADO" lote_cumple "$ANA" "$LOTE" '.estado == "GENERADO"'
  espera 60 "traza en Tempo" traza_entre "$(servicio_de lotes)" "$servicio" "$desde"
  traza_entre "$(servicio_de lotes)" "$servicio" "$desde"
  ok "una sola traza une la petición HTTP del módulo lotes (en '$(servicio_de lotes)') con el consumo del módulo giftcards (en '$servicio'): $GRAFANA/explore (Tempo, $TRAZA)"
}

escenario_escalado() {
  bold "Escalado horizontal de un solo módulo"
  promovido giftcards || fail "giftcards vive en el núcleo: para escalarlo por separado hay que promoverlo antes (make promover MODULO=giftcards)"
  local nucleo_antes giftcards_antes
  nucleo_antes="$(replicas nucleo)"
  giftcards_antes="$(replicas giftcards)"
  docker compose up -d --no-deps --no-recreate --scale "giftcards=$((giftcards_antes + 2))" --wait giftcards >/dev/null 2>&1
  [ "$(replicas giftcards)" = "$((giftcards_antes + 2))" ] || fail "giftcards no escaló"
  [ "$(replicas nucleo)" = "$nucleo_antes" ] || fail "el núcleo cambió de tamaño"
  ok "giftcards pasó de $giftcards_antes a $((giftcards_antes + 2)) réplicas; el núcleo sigue en $nucleo_antes"
  crear_lote "$ANA" 1000 "Con más réplicas"
  local started=$SECONDS
  espera 120 "lote GENERADO" lote_cumple "$ANA" "$LOTE" '.estado == "GENERADO"'
  ok "con las réplicas nuevas el sistema sigue generando: 1000 gift cards en $((SECONDS - started))s"
  docker compose up -d --no-deps --no-recreate --scale "giftcards=$giftcards_antes" giftcards >/dev/null 2>&1
  espera 120 "giftcards de vuelta a $giftcards_antes réplicas" replicas_son giftcards "$giftcards_antes"
  ok "de vuelta a $giftcards_antes réplicas (las tareas retiradas terminaron su trabajo antes de salir)"
  info "tabla medida de tiempos por número de réplicas: make demo-escalado"
}

# --- topology ----------------------------------------------------------------------------------

escenario_promocion() {
  bold "Promoción: giftcards pasa del núcleo a un servicio propio, solo con configuración"
  en_nucleo giftcards && [ "$(replicas giftcards)" = 0 ] ||
    fail "giftcards no está solo en el núcleo (make topologia); ejecute antes: make reintegrar MODULO=giftcards"
  [ "$(atiende "/giftcards?pageSize=1")" = nucleo ] || fail "el gateway no envía giftcards al núcleo"

  # Something to recognise afterwards: a card issued before, and how many there are.
  api POST /giftcards "$ANA" '{"pais":"604","moneda":"PEN","monto":20.00,"vigenciaHasta":"2027-12-31"}'
  espera_status 201 "emitir la gift card testigo"
  local testigo mascara antes imagen
  testigo="$(campo .id)"
  mascara="$(campo .codigoEnmascarado)"
  antes="$(sql "SELECT count(*) FROM giftcards.giftcard")"
  imagen="$(imagenes "${SERVICIOS_APP[@]}")"
  ok "punto de partida: el núcleo ($(replicas nucleo) réplicas) atiende $(modulos_nucleo); $antes gift cards en la base; imagen ${imagen:7:12}"

  # --- expand ---
  scripts/topologia.sh promover giftcards expandir >/dev/null
  [ "$(replicas_sanas giftcards)" -ge 1 ] || fail "el servicio propio no arrancó"
  [ "$(imagenes "${SERVICIOS_APP[@]}")" = "$imagen" ] || fail "el servicio propio no corre la misma imagen que el núcleo"
  [ "$(en_replica giftcards printenv ZC_MODULES)" = giftcards ] || fail "el servicio propio no tiene ZC_MODULES=giftcards"
  [ "$(bucles_de giftcards lotes)" = 0 ] || fail "el servicio propio arrancó bucles de lotes"
  ok "expandir: servicio 'giftcards' con $(replicas giftcards) réplicas sanas, la MISMA imagen (${imagen:7:12}) y ZC_MODULES=giftcards"
  [ "$(atiende "/giftcards?pageSize=1")" = nucleo ] || fail "el tráfico se movió antes de tiempo"
  ok "el núcleo sigue atendiendo el módulo y recibiendo su tráfico"

  # The overlap: two deployments consume the same queues and run the same relay.
  local desde solape reparto fila
  desde="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  crear_lote "$ANA" 4000 "Durante el solape"
  solape="$LOTE"
  espera 420 "lote GENERADO durante el solape" lote_cumple "$ANA" "$solape" '.estado == "GENERADO"'
  fila="$(sql "SELECT count(*), count(DISTINCT secuencia), count(DISTINCT codigo_indice) FROM giftcards.giftcard WHERE lote_id = '$solape'")"
  [ "$fila" = "4000|4000|4000" ] || fail "el lote generado durante el solape no quedó exacto: $fila"
  reparto="$(reparto_por_servicio "$solape" "$desde")"
  [[ "$reparto" == *nucleo=* && "$reparto" == *giftcards=* ]] || fail "solo un despliegue generó fragmentos durante el solape: $reparto"
  ok "durante el solape los dos despliegues compiten por la misma cola (fragmentos: $reparto) y el lote queda exacto: 4000 gift cards, sin pérdidas ni duplicados"

  # --- contract, while a client keeps calling ---
  sonda_iniciar
  scripts/topologia.sh promover giftcards contraer >/dev/null
  sleep 2
  sonda_detener
  [ "$SONDA_FALLIDAS" = 0 ] || fail "hubo $SONDA_FALLIDAS respuestas fallidas de $SONDA_TOTAL durante el cambio: $SONDA_DETALLE"
  ok "contraer: $SONDA_TOTAL peticiones a las dos API mientras cambiaba la topología, 0 fallidas"

  promovido giftcards || fail "la topología no quedó promovida"
  [ "$(atiende "/giftcards?pageSize=1")" = giftcards ] || fail "el gateway no envía giftcards a su servicio"
  [ "$(atiende "/lotes?pageSize=1")" = nucleo ] || fail "el gateway no envía lotes al núcleo"
  ok "el tráfico de /api/v1/giftcards llega al servicio propio; el de /api/v1/lotes, al núcleo"

  local contenedor
  for contenedor in $(contenedores nucleo); do
    [ "$(docker exec "$contenedor" printenv ZC_MODULES)" = lotes ] || fail "una réplica del núcleo sigue con el módulo"
    # Asked directly, without the gateway. With a valid token: without one the answer is 401
    # whatever the module, so an anonymous caller learns nothing about the topology.
    [ "$(docker exec "$contenedor" curl -s -H "Authorization: Bearer $ANA" http://localhost:8080/api/v1/giftcards | jq -r '"\(.status) \(.code)"')" = "404 MODULE_NOT_SERVED" ] ||
      fail "una réplica del núcleo sigue exponiendo la API de giftcards"
    [ "$(docker exec "$contenedor" curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/v1/giftcards)" = 401 ] ||
      fail "una réplica del núcleo responde algo distinto de 401 a una petición sin token"
  done
  [ "$(bucles_de nucleo giftcards)" = 0 ] || fail "el núcleo sigue con bucles de giftcards"
  [ "$(imagenes "${SERVICIOS_APP[@]}")" = "$imagen" ] || fail "cambió la imagen"
  ok "el núcleo se redesplegó con ZC_MODULES=lotes: ya no consume las colas de giftcards y su API responde 404 MODULE_NOT_SERVED; la imagen no cambió"

  api GET "/giftcards/$testigo" "$ANA"
  espera_status 200 "leer la gift card testigo"
  [ "$(campo .codigoEnmascarado)" = "$mascara" ] || fail "la gift card testigo cambió"
  [ "$(sql "SELECT count(*) FROM giftcards.giftcard")" = "$((antes + 4000))" ] || fail "el número de gift cards no cuadra"
  ok "los datos siguen ahí: la gift card emitida antes se lee por el servicio nuevo y la base tiene $((antes + 4000)) gift cards"

  local desde_s
  desde_s="$(date +%s)"
  desde="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  crear_lote "$ANA" 1000 "Después de promover"
  espera 240 "lote GENERADO con el módulo promovido" lote_cumple "$ANA" "$LOTE" '.estado == "GENERADO"'
  reparto="$(reparto_por_servicio "$LOTE" "$desde")"
  [[ "$reparto" == giftcards=* ]] || fail "el núcleo siguió generando fragmentos: $reparto"
  ok "la saga funciona igual con los módulos en dos despliegues: 1000 gift cards generadas solo por el servicio propio (fragmentos: $reparto)"
  espera 60 "traza entre los dos despliegues" traza_entre nucleo giftcards "$desde_s"
  ok "y una sola traza une la petición en 'nucleo' con el consumo en 'giftcards' ($TRAZA)"
}

escenario_reintegracion() {
  bold "Reintegración: giftcards vuelve al núcleo"
  promovido giftcards || fail "giftcards no está promovido (make topologia)"
  local antes imagen
  antes="$(sql "SELECT count(*) FROM giftcards.giftcard")"
  imagen="$(imagenes "${SERVICIOS_APP[@]}")"

  sonda_iniciar
  scripts/topologia.sh reintegrar giftcards >/dev/null
  sleep 2
  sonda_detener
  [ "$SONDA_FALLIDAS" = 0 ] || fail "hubo $SONDA_FALLIDAS respuestas fallidas de $SONDA_TOTAL durante el cambio: $SONDA_DETALLE"
  ok "$SONDA_TOTAL peticiones a las dos API mientras cambiaba la topología, 0 fallidas"

  en_nucleo giftcards && [ "$(replicas giftcards)" = 0 ] || fail "la topología no volvió al punto de partida"
  [ "$(atiende "/giftcards?pageSize=1")" = nucleo ] || fail "el gateway no envía giftcards al núcleo"
  [ "$(bucles_de nucleo giftcards)" = 3 ] || fail "el núcleo no arrancó los bucles de giftcards"
  [ "$(imagenes "${SERVICIOS_APP[@]}")" = "$imagen" ] || fail "cambió la imagen"
  ok "el núcleo atiende otra vez $(modulos_nucleo) con la misma imagen; el servicio propio quedó sin réplicas"

  [ "$(sql "SELECT count(*) FROM giftcards.giftcard")" = "$antes" ] || fail "el número de gift cards cambió"
  crear_lote "$ANA" 500 "Después de reintegrar"
  espera 240 "lote GENERADO en el núcleo" lote_cumple "$ANA" "$LOTE" '.estado == "GENERADO"'
  ok "los datos siguen ahí ($antes gift cards) y la saga vuelve a completarse dentro del núcleo"
}

# --- main --------------------------------------------------------------------------------------

# Order matters in the full run: the scenarios before `promocion` run with every module in the
# nucleo; the ones between `promocion` and `reintegracion`, with giftcards as its own service.
TODOS=(seguridad saga unitaria caida dlq aislamiento observabilidad promocion anulacion escalado reintegracion)
ELEGIDOS=("$@")
[ ${#ELEGIDOS[@]} -gt 0 ] || ELEGIDOS=("${TODOS[@]}")

requisitos
if [ $# -eq 0 ]; then
  en_nucleo giftcards && [ "$(replicas giftcards)" = 0 ] ||
    fail "la corrida completa empieza con todos los módulos en el núcleo (make topologia); ejecute: make reintegrar MODULO=giftcards"
fi

for nombre in "${ELEGIDOS[@]}"; do
  declare -F "escenario_$nombre" >/dev/null || fail "escenario desconocido: $nombre (disponibles: ${TODOS[*]})"
  tokens # an access token lasts 30 minutes; the full run can take longer
  "escenario_$nombre"
done

bold "Todo en orden: ${#ELEGIDOS[@]} escenario(s) en ${SECONDS}s."
