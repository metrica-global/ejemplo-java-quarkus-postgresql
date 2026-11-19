#!/usr/bin/env bash
# Why promote a module. Measures the API of lotes while a large lote is being generated, with
# giftcards in the nucleo and with giftcards promoted to its own service. Every number printed
# is measured on this machine, in this run.
#
#   scripts/demo-promocion.sh            a lote of 6000 gift cards
#   scripts/demo-promocion.sh 12000
#
# Generating gift cards is CPU bound (one Argon2id hash per CVV). While the module lives in the
# nucleo, that work shares the vCPU of every replica with the HTTP requests of the other
# modules. Adding replicas of the whole nucleo adds capacity, but every new replica also takes
# generation work, so the API keeps sharing its CPU. Promoting the module moves the work to
# replicas that serve nothing else.
#
# Four measurements, each with the response times of GET /api/v1/lotes seen by one client:
#
#   1. nucleo x2, idle                    the reference
#   2. nucleo x2, generating              everything in the nucleo (2 vCPU in total)
#   3. nucleo x4, generating              the whole nucleo scaled out (4 vCPU in total)
#   4. nucleo x2 + giftcards x2           the module promoted (4 vCPU in total)
#
# It starts and ends with every module in the nucleo.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$ROOT"
requisitos

CANTIDAD="${1:-6000}"
en_nucleo giftcards && [ "$(replicas giftcards)" = 0 ] ||
  morir "esta demo empieza con todos los módulos en el núcleo (make topologia); ejecute: make reintegrar MODULO=giftcards"
NUCLEO="$(replicas nucleo)"
FRAGMENTO="$(en_replica nucleo printenv ZC_GIFTCARDS_FRAGMENT_SIZE)"
MUESTRAS="$(mktemp)"
CLIENTE=""

limpiar() {
  [ -z "$CLIENTE" ] || kill "$CLIENTE" 2>/dev/null || true
  rm -f "$MUESTRAS"
  if promovido giftcards; then scripts/topologia.sh reintegrar giftcards >/dev/null || true; fi
  [ "$(replicas nucleo)" = "$NUCLEO" ] || escalar nucleo "$NUCLEO" || true
}
trap limpiar EXIT

# One client calling the API of lotes, one request after another, until it is stopped.
cliente_iniciar() { # token
  : >"$MUESTRAS"
  (
    while :; do
      curl -s -o /dev/null -m 30 -w '%{http_code} %{time_total}\n' "$API/lotes?pageSize=20" -H "Authorization: Bearer $1" >>"$MUESTRAS"
      sleep 0.05
    done
  ) &
  CLIENTE=$!
}

cliente_detener() {
  kill "$CLIENTE" 2>/dev/null || true
  wait "$CLIENTE" 2>/dev/null || true
  CLIENTE=""
}

# requests, failed, p50, p95, p99 and maximum in milliseconds (nearest rank).
resumen() {
  sort -k2 -n "$MUESTRAS" | awk '
    { t[NR] = $2 * 1000; if ($1 != 200) malas++ }
    function p(q,   i) { i = int(NR * q + 0.999999); if (i < 1) i = 1; if (i > NR) i = NR; return t[i] }
    END { printf "%d | %d | %.0f | %.0f | %.0f | %.0f", NR, malas, p(0.50), p(0.95), p(0.99), t[NR] }'
}

# Every JVM compiles the code it is about to run before the measurement, so the numbers are
# the steady state and not the first seconds of a replica that has just started.
calentar() { # token replicas-that-generate
  local i
  for i in $(seq 1 300); do
    curl -s -o /dev/null -m 30 "$API/lotes?pageSize=20" -H "Authorization: Bearer $1" || true
  done
  [ "$2" -eq 0 ] || cronometrar_lote "$1" "$(solicitar_lote "$1" $((FRAGMENTO * 2 * $2)) "Calentamiento")" 900 >/dev/null
}

filas=()
medir() { # label vcpu generating-replicas
  local etiqueta="$1" vcpu="$2" generan="$3" token id segundos="-"
  token="$(token_de ana)"
  calentar "$token" "$generan"
  if [ "$generan" -gt 0 ]; then
    cliente_iniciar "$token"
    id="$(solicitar_lote "$token" "$CANTIDAD" "Medición: $etiqueta")"
    segundos="$(cronometrar_lote "$token" "$id" 1800)"
  else
    cliente_iniciar "$token"
    sleep 30
  fi
  cliente_detener
  filas+=("| $etiqueta | $vcpu | $segundos | $(resumen) |")
  echo "  $etiqueta  ->  lote: ${segundos}s; peticiones | fallidas | p50 | p95 | p99 | máx (ms): $(resumen)"
}

echo "API de lotes mientras se genera un lote de $CANTIDAD gift cards ($((CANTIDAD / FRAGMENTO)) fragmentos de $FRAGMENTO)."
echo

medir "núcleo ×$NUCLEO, sin generar" "$NUCLEO" 0
medir "núcleo ×$NUCLEO, generando" "$NUCLEO" "$NUCLEO"

escalar nucleo $((NUCLEO * 2))
medir "núcleo ×$((NUCLEO * 2)), generando" $((NUCLEO * 2)) $((NUCLEO * 2))
escalar nucleo "$NUCLEO"

echo "  promoviendo giftcards (make promover MODULO=giftcards)..."
ZC_REPLICAS_PROPIAS="$NUCLEO" scripts/topologia.sh promover giftcards >/dev/null
medir "núcleo ×$NUCLEO + giftcards ×$NUCLEO, generando" $((NUCLEO * 2)) "$NUCLEO"

echo
echo "| Topología | vCPU en total | Lote (s) | Peticiones a lotes | Fallidas | p50 (ms) | p95 (ms) | p99 (ms) | Máx (ms) |"
echo "|---|---:|---:|---:|---:|---:|---:|---:|---:|"
printf '%s\n' "${filas[@]}"
echo
echo "Medido el $(date -u +%Y-%m-%dT%H:%MZ) en $(nproc) núcleos; cada réplica limitada a 1 vCPU; un solo cliente, una petición tras otra."
