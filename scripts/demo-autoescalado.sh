#!/usr/bin/env bash
# SIMULATION of the autoscaling policy, on the local system.
#
# In AWS, Application Auto Scaling changes the number of giftcards tasks by itself. That service
# does not exist on a laptop, so this script plays its part: it reads the same signal the
# Terraform policy tracks (messages waiting in giftcards-generacion divided by running tasks),
# applies the target tracking rule and changes the number of replicas with docker compose.
#
# What is real: the queue, its backlog, the replicas doing the work and the time it takes.
# What is simulated: the controller, and its timing. CloudWatch evaluates once a minute and the
# policy waits 60 s before adding tasks and 300 s before removing them; here the periods are
# short so the whole cycle fits in a few minutes.
#
#   scripts/demo-autoescalado.sh              a lote of 12000 gift cards
#   scripts/demo-autoescalado.sh 20000
#
# Tunables (same meaning as in infra/terraform/envs/*/terraform.tfvars):
#   ZC_SIM_MIN=2 ZC_SIM_MAX=6 ZC_SIM_TARGET=4   minimum, maximum, backlog per task target
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
cd "$ROOT"
requisitos

CANTIDAD="${1:-12000}"
MIN="${ZC_SIM_MIN:-2}"
MAX="${ZC_SIM_MAX:-6}"
TARGET="${ZC_SIM_TARGET:-4}"
PERIODO=5           # seconds between evaluations (CloudWatch: 60)
ENFRIAR_SALIDA=10   # seconds after a scale-out before another one (policy: 60)
ENFRIAR_ENTRADA=30  # seconds the metric must stay low before a scale-in (policy: 300)
COLA=giftcards-generacion

LOTES_ANTES="$(replicas lotes)"
trap 'escalar giftcards "$MIN"' EXIT
escalar giftcards "$MIN"

token="$(token_de ana)"
id="$(solicitar_lote "$token" "$CANTIDAD" "Demo de autoescalado")"
echo "SIMULACIÓN del controlador de autoescalado (ver cabecera de este archivo)."
echo "Lote $id de $CANTIDAD gift cards. Política: backlog por tarea objetivo $TARGET, entre $MIN y $MAX réplicas."
echo
printf '%6s  %9s  %8s  %9s  %13s  %-9s  %s\n' "t (s)" "en espera" "en vuelo" "giftcards" "backlog/tarea" "lotes" "decisión"

inicio=$SECONDS
ultima_salida=-1000
bajo_desde=-1
terminado=""
while :; do
  visibles="$(cola_visibles "$COLA")"
  vuelo="$(cola_en_vuelo "$COLA")"
  actuales="$(replicas giftcards)"
  t=$((SECONDS - inicio))
  metrica="$(awk -v v="$visibles" -v n="$actuales" 'BEGIN { printf "%.1f", v / n }')"
  # Target tracking keeps metric = target: the capacity that would do it is current * metric / target.
  deseadas="$(awk -v n="$actuales" -v m="$metrica" -v g="$TARGET" -v lo="$MIN" -v hi="$MAX" \
    'BEGIN { d = n * m / g; d = (d == int(d)) ? d : int(d) + 1; if (d < lo) d = lo; if (d > hi) d = hi; print d }')"
  decision="-"
  if [ "$deseadas" -gt "$actuales" ]; then
    bajo_desde=-1
    if [ $((t - ultima_salida)) -ge "$ENFRIAR_SALIDA" ]; then
      decision="escalar a $deseadas"
      ultima_salida=$t
    else
      decision="(enfriamiento)"
    fi
  elif [ "$deseadas" -lt "$actuales" ]; then
    [ "$bajo_desde" -ge 0 ] || bajo_desde=$t
    if [ $((t - bajo_desde)) -ge "$ENFRIAR_ENTRADA" ]; then
      decision="reducir a $deseadas"
      bajo_desde=-1
    else
      decision="(esperando para reducir)"
    fi
  else
    bajo_desde=-1
  fi
  printf '%6s  %9s  %8s  %9s  %13s  %-9s  %s\n' "$t" "$visibles" "$vuelo" "$actuales" "$metrica" "$(replicas lotes)" "$decision"
  case "$decision" in
    escalar*) escalar giftcards "$deseadas" ;;
    reducir*) escalar giftcards "$deseadas" ;;
  esac

  if [ -z "$terminado" ] && [ "$(lote "$token" "$id" | jq -r .estado)" = GENERADO ]; then
    terminado=$((SECONDS - inicio))
    echo "        -> lote GENERADO a los ${terminado}s; ahora la cola está vacía y la política retira réplicas"
  fi
  [ -n "$terminado" ] && [ "$(replicas giftcards)" = "$MIN" ] && break
  [ "$t" -lt 1800 ] || morir "la demo no terminó en 30 minutos"
  sleep "$PERIODO"
done

echo
echo "Lote generado en ${terminado}s. giftcards volvió a $MIN réplicas; lotes tuvo $LOTES_ANTES en todo momento."
[ "$(replicas lotes)" = "$LOTES_ANTES" ] || morir "lotes cambió de tamaño"
