#!/usr/bin/env bash
# Measures how long the same lote takes with 1, 2 and 4 replicas of giftcards, while lotes keeps
# the replicas it had. Every number printed is measured on this machine, in this run.
#
#   scripts/demo-escalado.sh                 4000 gift cards, with 1, 2 and 4 replicas
#   scripts/demo-escalado.sh 8000 1 2 4 8    another size and other replica counts
#
# What is being shown: the work queue is shared, so capacity is added by starting more copies of
# ONE service. Each replica is limited to one vCPU (docker-compose.yml), like a Fargate task.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$ROOT"
requisitos

CANTIDAD="${1:-4000}"
shift || true
REPLICAS=("$@")
[ ${#REPLICAS[@]} -gt 0 ] || REPLICAS=(1 2 4)

INICIALES="$(replicas giftcards)"
LOTES_ANTES="$(replicas lotes)"
FRAGMENTO="$(docker compose exec -T giftcards printenv ZC_GIFTCARDS_FRAGMENT_SIZE)"
ARGON2="m=$(docker compose exec -T giftcards printenv ZC_GIFTCARDS_ARGON2_MEMORY_KIB) KiB, t=$(docker compose exec -T giftcards printenv ZC_GIFTCARDS_ARGON2_ITERATIONS)"
trap 'escalar giftcards "$INICIALES"' EXIT

echo "Lote de $CANTIDAD gift cards ($((CANTIDAD / FRAGMENTO)) fragmentos de $FRAGMENTO). lotes: $LOTES_ANTES réplicas, sin cambios."
echo

filas=()
base=""
for n in "${REPLICAS[@]}"; do
  escalar giftcards "$n"
  token="$(token_de ana)"
  # Warm-up: two fragments per replica, so every JVM has compiled the hashing code before the
  # measurement. Without it the first run mostly measures the JIT compiler.
  cronometrar_lote "$token" "$(solicitar_lote "$token" $((FRAGMENTO * 2 * n)) "Calentamiento ($n réplicas)")" 600 >/dev/null

  desde="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  id="$(solicitar_lote "$token" "$CANTIDAD" "Medición con $n réplicas")"
  segundos="$(cronometrar_lote "$token" "$id" 1800)"
  [ -n "$base" ] || base="$segundos"
  # Which replica stored each fragment, from the services' own logs: how the work was shared.
  reparto="$(docker compose logs giftcards --no-log-prefix --since "$desde" 2>/dev/null |
    jq -Rr --arg id "$id" 'fromjson? | select(.message == "fragmento generado" and .mdc.lote_id == $id) | .hostName' |
    sort | uniq -c | awk '{ printf "%s%s", (NR > 1 ? " + " : ""), $1 }')"
  fila="$(awk -v n="$n" -v l="$(replicas lotes)" -v s="$segundos" -v c="$CANTIDAD" -v b="$base" -v r="$reparto" \
    'BEGIN { printf "| %d | %d | %.1f | %.0f | %.2fx | %s |", n, l, s, c / s, b / s, r }')"
  filas+=("$fila")
  echo "  giftcards=$n  ->  ${segundos}s   (fragmentos por réplica: $reparto)"
  [ "$(replicas lotes)" = "$LOTES_ANTES" ] || morir "lotes cambió de tamaño durante la medición"
done

echo
echo "| Réplicas de giftcards | Réplicas de lotes | Tiempo (s) | Gift cards/s | Aceleración | Fragmentos por réplica |"
echo "|---:|---:|---:|---:|---:|---|"
printf '%s\n' "${filas[@]}"
echo
echo "Medido el $(date -u +%Y-%m-%dT%H:%MZ) en $(nproc) núcleos; cada réplica de giftcards limitada a 1 vCPU; Argon2id $ARGON2."
