#!/usr/bin/env bash
# Measures how long the same lote takes with 1, 2 and 4 replicas of giftcards, while the nucleo
# keeps the replicas it had. Every number printed is measured on this machine, in this run.
#
#   scripts/demo-escalado.sh                 4000 gift cards, with 1, 2 and 4 replicas
#   scripts/demo-escalado.sh 8000 1 2 4 8    another size and other replica counts
#
# What is being shown: the work queue is shared, so capacity is added by starting more copies of
# ONE module. For that the module has to be its own service: if giftcards lives in the nucleo,
# this script promotes it first and reintegrates it at the end. Each replica is limited to one
# vCPU (docker-compose.yml), like a Fargate task.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$ROOT"
requisitos

CANTIDAD="${1:-4000}"
shift || true
REPLICAS=("$@")
[ ${#REPLICAS[@]} -gt 0 ] || REPLICAS=(1 2 4)

asegurar_promovido giftcards
INICIALES="$(replicas giftcards)"
NUCLEO_ANTES="$(replicas nucleo)"
FRAGMENTO="$(en_replica giftcards printenv ZC_GIFTCARDS_FRAGMENT_SIZE)"
ARGON2="m=$(en_replica giftcards printenv ZC_GIFTCARDS_ARGON2_MEMORY_KIB) KiB, t=$(en_replica giftcards printenv ZC_GIFTCARDS_ARGON2_ITERATIONS)"
trap 'escalar giftcards "$INICIALES"; restaurar_topologia' EXIT

echo "Lote de $CANTIDAD gift cards ($((CANTIDAD / FRAGMENTO)) fragmentos de $FRAGMENTO). Núcleo (módulo lotes): $NUCLEO_ANTES réplicas, sin cambios."
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
  # Which replica stored each fragment, from the application's own logs: how the work was shared.
  reparto="$(fragmentos_generados "$id" "$desde" | awk '{ print $2 }' | sort | uniq -c | awk '{ printf "%s%s", (NR > 1 ? " + " : ""), $1 }')"
  fila="$(awk -v n="$n" -v l="$(replicas nucleo)" -v s="$segundos" -v c="$CANTIDAD" -v b="$base" -v r="$reparto" \
    'BEGIN { printf "| %d | %d | %.1f | %.0f | %.2fx | %s |", n, l, s, c / s, b / s, r }')"
  filas+=("$fila")
  echo "  giftcards=$n  ->  ${segundos}s   (fragmentos por réplica: $reparto)"
  [ "$(replicas nucleo)" = "$NUCLEO_ANTES" ] || morir "el núcleo cambió de tamaño durante la medición"
done

echo
echo "| Réplicas de giftcards | Réplicas del núcleo | Tiempo (s) | Gift cards/s | Aceleración | Fragmentos por réplica |"
echo "|---:|---:|---:|---:|---:|---|"
printf '%s\n' "${filas[@]}"
echo
echo "Medido el $(date -u +%Y-%m-%dT%H:%MZ) en $(nproc) núcleos; cada réplica de giftcards limitada a 1 vCPU; Argon2id $ARGON2."
