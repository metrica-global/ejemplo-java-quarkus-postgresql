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

  id="$(solicitar_lote "$token" "$CANTIDAD" "Medición con $n réplicas")"
  segundos="$(cronometrar_lote "$token" "$id" 1800)"
  [ -n "$base" ] || base="$segundos"
  fila="$(awk -v n="$n" -v l="$(replicas lotes)" -v s="$segundos" -v c="$CANTIDAD" -v b="$base" \
    'BEGIN { printf "| %d | %d | %.1f | %.0f | %.2fx |", n, l, s, c / s, b / s }')"
  filas+=("$fila")
  echo "  giftcards=$n  ->  ${segundos}s"
  [ "$(replicas lotes)" = "$LOTES_ANTES" ] || morir "lotes cambió de tamaño durante la medición"
done

echo
echo "| Réplicas de giftcards | Réplicas de lotes | Tiempo (s) | Gift cards/s | Aceleración |"
echo "|---:|---:|---:|---:|---:|"
printf '%s\n' "${filas[@]}"
echo
echo "Medido el $(date -u +%Y-%m-%dT%H:%MZ) en $(nproc) núcleos; cada réplica de giftcards limitada a 1 vCPU."
