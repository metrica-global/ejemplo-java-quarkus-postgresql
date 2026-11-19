#!/usr/bin/env bash
# Rehearses the promotion of a module, and the way back, on the Terraform of the dev
# environment. Every stage is planned by the REAL AWS provider and applied to a throwaway
# emulator. No AWS account is involved: the provider gets fake credentials and every endpoint
# it uses points at the emulator, which this script starts and removes.
#
#   scripts/tf-ensayo-promocion.sh                    the whole rehearsal (about 3 minutes)
#   ZC_ENSAYO_CONSERVAR=1 scripts/tf-ensayo-promocion.sh    keep the emulator and the copy
#
# What it shows, that the native tests cannot (they use a mocked provider, which plans every
# change as an update and accepts every call):
#   - what the real provider plans at each stage: what is created, changed IN PLACE, replaced
#     or destroyed, and that the listener rule of the module is never replaced;
#   - that every stage applies, in the order Terraform chooses, against an API that enforces
#     the two rules that matter here: a service needs its target group attached to a load
#     balancer, and a target group that a rule still uses cannot be deleted;
#   - that a service created later starts with the release the pipeline recorded, and that a
#     release does not show up as a change of anything else.
#
# What it does NOT show. The emulator runs no task, so nothing here is about traffic, health
# checks, deployments or autoscaling; and an emulator is not AWS, so a call it accepts is not
# proof that AWS accepts it. See infra/terraform/README.md for what was and was not executed.
#
# Three adaptations, all because of the emulator. None touches the repository: the first is
# made in the copy this script works on, the other two are in how this script plans.
#   - the port of the database: the emulator publishes its databases on a port of its own, and
#     with 5432 in the configuration every plan would want to change it;
#   - plans do not re-read the resources (-refresh=false): the emulator returns the security
#     group references in another format, which would show as four changes in every plan;
#   - the emulator forgets the description of an SSM parameter when the parameter is
#     overwritten, so a later plan wants to write it again. A planned change of a parameter
#     whose VALUE does not change is therefore not counted.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENTORNO=dev
MODULO=giftcards
CONTENEDOR=zonacards-ensayo-floci
PUERTO="${ZC_PORT_ENSAYO:-14577}"
EMULADOR="http://127.0.0.1:$PUERTO"
PUERTO_BD_EMULADOR=7001

morir() {
  printf '\033[31m%s\033[0m\n' "$*" >&2
  exit 1
}
bien() { printf '  \033[32m✔\033[0m %s\n' "$*"; }

for herramienta in docker terraform jq curl; do
  command -v "$herramienta" >/dev/null || morir "falta $herramienta"
done

WORK="${ZC_ENSAYO_WORKDIR:-$(mktemp -d "${TMPDIR:-/tmp}/zonacards-ensayo.XXXXXX")}"
ENV_DIR="$WORK/infra/terraform/envs/$ENTORNO"
EJEMPLO="$ROOT/infra/terraform/envs/$ENTORNO/promocion-$MODULO.tfvars"
[ -f "$EJEMPLO" ] || morir "no existe $EJEMPLO"

limpiar() {
  if [ -n "${ZC_ENSAYO_CONSERVAR:-}" ]; then
    echo
    echo "Se conservan el emulador ($CONTENEDOR, $EMULADOR) y la copia de trabajo ($WORK)."
    return
  fi
  docker rm -f "$CONTENEDOR" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap limpiar EXIT

# Never the credentials of the person who runs this: only the fake ones of the provider block.
tf() {
  (cd "$ENV_DIR" && env -u AWS_PROFILE -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
    -u AWS_DEFAULT_PROFILE TF_IN_AUTOMATION=1 terraform "$@")
}

# --- the emulator and the working copy ----------------------------------------------------------

IMAGEN="$(awk '$1 == "image:" && $2 ~ /^floci\/floci:/ { print $2; exit }' "$ROOT/docker-compose.yml")"
[ -n "$IMAGEN" ] || morir "no se encontró la imagen del emulador en docker-compose.yml"

echo "Ensayo de la promoción de $MODULO en el Terraform de $ENTORNO, contra un emulador ($IMAGEN)."
docker rm -f "$CONTENEDOR" >/dev/null 2>&1 || true
docker run -d --name "$CONTENEDOR" -p "127.0.0.1:$PUERTO:4566" "$IMAGEN" >/dev/null
limite=$((SECONDS + 60))
until curl -fsS -m 2 "$EMULADOR/_floci/health" 2>/dev/null | grep -q '"version"'; do
  [ "$SECONDS" -lt "$limite" ] || morir "el emulador no respondió en 60 s"
  sleep 1
done

mkdir -p "$WORK/infra" "$WORK/deploy"
# The database module reads deploy/db/bootstrap-service.sql by a relative path.
cp -R "$ROOT/deploy/db" "$WORK/deploy/db"
(cd "$ROOT/infra" && tar --exclude='.terraform' --exclude='*.tfstate*' -cf - terraform) | (cd "$WORK/infra" && tar -xf -)

sed -i "s/^  port     = 5432\$/  port     = $PUERTO_BD_EMULADOR/" "$WORK/infra/terraform/modules/database/main.tf"
grep -q "^  port     = $PUERTO_BD_EMULADOR\$" "$WORK/infra/terraform/modules/database/main.tf" ||
  morir "no se pudo adaptar el puerto de la base en la copia de trabajo"

cat >"$ENV_DIR/backend_override.tf" <<EOF
terraform {
  backend "local" {
    path = "$WORK/$ENTORNO.tfstate"
  }
}
EOF

# Every service the configuration uses, so that no call can leave this machine. With these
# credentials a call that did would be rejected by AWS anyway.
cat >"$ENV_DIR/providers_override.tf" <<EOF
provider "aws" {
  region                      = "us-east-1"
  access_key                  = "test"
  secret_key                  = "test"
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    appautoscaling = "$EMULADOR"
    cloudwatch     = "$EMULADOR"
    ec2            = "$EMULADOR"
    ecr            = "$EMULADOR"
    ecs            = "$EMULADOR"
    elbv2          = "$EMULADOR"
    iam            = "$EMULADOR"
    kms            = "$EMULADOR"
    logs           = "$EMULADOR"
    rds            = "$EMULADOR"
    s3             = "$EMULADOR"
    secretsmanager = "$EMULADOR"
    sns            = "$EMULADOR"
    sqs            = "$EMULADOR"
    ssm            = "$EMULADOR"
    sts            = "$EMULADOR"
  }
}
EOF

tf init -input=false -reconfigure >/dev/null

# The stages are the example file with its `transition` line changed, as an operator would.
grep -q '^ *transition = "traffic-in-nucleo"$' "$EJEMPLO" || morir "$EJEMPLO no trae transition = \"traffic-in-nucleo\""
cp "$EJEMPLO" "$WORK/trafico-en-nucleo.tfvars"
sed '/^ *transition = /d' "$EJEMPLO" >"$WORK/promovido.tfvars"
sed 's/^\( *transition = \)"traffic-in-nucleo"$/\1"traffic-in-service"/' "$EJEMPLO" >"$WORK/trafico-en-servicio.tfvars"

# --- one stage: plan with the real provider, check what it would do, apply ----------------------

REGLA="module.zonacards.aws_lb_listener_rule.module[\"$MODULO\"]"
TAREA_NUCLEO="module.zonacards.module.nucleo.aws_ecs_task_definition.service"
filas=()

accion_de() { # stage address -> the action of the plan on that resource, or "ninguna"
  awk -F'\t' -v a="$2" '$2 == a { print $1; f = 1 } END { if (!f) print "ninguna" }' "$WORK/$1.acciones"
}

cuenta() { # stage action -> how many resources
  awk -F'\t' -v a="$2" '$1 == a { n++ } END { print n + 0 }' "$WORK/$1.acciones"
}

esperar() { # stage what expected actual
  [ "$3" = "$4" ] || {
    echo "    plan completo: $WORK/$1.plan.txt" >&2
    ZC_ENSAYO_CONSERVAR=1
    morir "  ✘ $2: se esperaba «$3» y el plan dice «$4»"
  }
  bien "$2: $4"
}

etapa() { # name title [terraform plan arguments...]
  local nombre="$1" titulo="$2"
  shift 2
  echo
  echo "$titulo"
  tf plan -input=false -lock=false -no-color -refresh=false -out="$WORK/$nombre.tfplan" "$@" >"$WORK/$nombre.plan.txt" 2>&1 || {
    tail -30 "$WORK/$nombre.plan.txt" >&2
    ZC_ENSAYO_CONSERVAR=1
    morir "el plan de la etapa falló"
  }
  # Every resource the plan would touch, as "action<TAB>address"; the parameters the emulator
  # lost the description of (third adaptation at the top of this file) go to a list of their own.
  tf show -json "$WORK/$nombre.tfplan" >"$WORK/$nombre.plan.json"
  jq -r '.resource_changes[]? | select(.change.actions != ["no-op"] and .change.actions != ["read"])
         | (.type == "aws_ssm_parameter" and .change.actions == ["update"]
            and .change.before.insecure_value == .change.after.insecure_value) as $ruido
         | "\(if $ruido then "ruido" else (.change.actions | join("+")) end)\t\(.address)"' "$WORK/$nombre.plan.json" |
    sort >"$WORK/$nombre.todo"
  grep -v '^ruido' "$WORK/$nombre.todo" >"$WORK/$nombre.acciones" || true
  ruido="$(grep -c '^ruido' "$WORK/$nombre.todo" || true)"
  echo "  $(grep -E '^(Plan:|No changes)' "$WORK/$nombre.plan.txt" | head -1)"
  [ "$ruido" -eq 0 ] ||
    echo "  (no se cuentan $ruido: solo reescriben la descripción de un parámetro, que el emulador pierde)"
}

aplicar() { # name
  tf apply -input=false -lock=false -no-color "$WORK/$1.tfplan" >"$WORK/$1.apply.txt" 2>&1 || {
    grep -n 'Error' -A6 "$WORK/$1.apply.txt" | head -40 >&2
    ZC_ENSAYO_CONSERVAR=1
    morir "el apply de la etapa falló"
  }
  bien "aplicado: $(grep -E '^Apply complete' "$WORK/$1.apply.txt" | sed 's/^Apply complete! //')"
}

fila() { # stage label
  filas+=("| $2 | $(cuenta "$1" create) | $(cuenta "$1" update) | $(cuenta "$1" delete+create) | $(cuenta "$1" delete) | $(accion_de "$1" "$REGLA") | $(accion_de "$1" "$TAREA_NUCLEO") |")
}

sin_cambios() { # name title
  etapa "$1" "$2"
  [ ! -s "$WORK/$1.acciones" ] || {
    cat "$WORK/$1.acciones" >&2
    ZC_ENSAYO_CONSERVAR=1
    morir "  ✘ se esperaba un plan sin cambios"
  }
  bien "ningún recurso cambia"
}

# --- 0. the environment as it is committed: every module in the nucleo --------------------------

etapa 0-nucleo "0. El ambiente tal como está en el repositorio: todos los módulos en el núcleo"
aplicar 0-nucleo
RECURSOS_BASE="$(cuenta 0-nucleo create)"
puerto="$(tf show -json | jq -r '[.. | objects | select(.type? == "aws_db_instance") | .values.port][0]')"
[ "$puerto" = "$PUERTO_BD_EMULADOR" ] ||
  morir "el emulador publicó la base en el puerto $puerto y este guion espera $PUERTO_BD_EMULADOR (PUERTO_BD_EMULADOR)"
sin_cambios 0-estable "   Un segundo plan, sin tocar nada"

# --- promote ------------------------------------------------------------------------------------

etapa 1-expandir "1. Promover, primer apply: transition = \"traffic-in-nucleo\"" -var-file="$WORK/trafico-en-nucleo.tfvars"
esperar 1-expandir "recursos que se destruyen o reemplazan" 0 "$(($(cuenta 1-expandir delete) + $(cuenta 1-expandir delete+create)))"
esperar 1-expandir "la regla del módulo" update "$(accion_de 1-expandir "$REGLA")"
esperar 1-expandir "la definición de tarea del núcleo" ninguna "$(accion_de 1-expandir "$TAREA_NUCLEO")"
esperar 1-expandir "el servicio propio" create "$(accion_de 1-expandir "module.zonacards.module.promoted[\"$MODULO\"].aws_ecs_service.autoscaled[0]")"
aplicar 1-expandir
RECURSOS_PROMOCION="$(cuenta 1-expandir create)"
fila 1-expandir "Promover 1: \`traffic-in-nucleo\`"

etapa 2-contraer "2. Promover, segundo apply: sin transition" -var-file="$WORK/promovido.tfvars"
esperar 2-contraer "la regla del módulo" update "$(accion_de 2-contraer "$REGLA")"
esperar 2-contraer "la definición de tarea del núcleo (una revisión nueva)" delete+create "$(accion_de 2-contraer "$TAREA_NUCLEO")"
esperar 2-contraer "otros recursos que se crean, destruyen o reemplazan" 0 \
  "$(($(cuenta 2-contraer create) + $(cuenta 2-contraer delete) + $(cuenta 2-contraer delete+create) - 1))"
aplicar 2-contraer
fila 2-contraer "Promover 2: sin \`transition\`"

# Terraform registered a revision for the nucleo and deregistered none; the service still runs
# the one it had, which is why the procedure ends with a redeployment.
revisiones="$(tf show -json | jq -r --arg a "$TAREA_NUCLEO" '[.. | objects | select(.address? == $a) | .values.revision][0]')"
esperar 2-contraer "revisión de la definición de tarea del núcleo que conoce Terraform" 2 "$revisiones"

# --- demote -------------------------------------------------------------------------------------

etapa 3-volver "3. Reintegrar, primer apply: transition = \"traffic-in-service\"" -var-file="$WORK/trafico-en-servicio.tfvars"
esperar 3-volver "la regla del módulo" ninguna "$(accion_de 3-volver "$REGLA")"
esperar 3-volver "la definición de tarea del núcleo (una revisión nueva)" delete+create "$(accion_de 3-volver "$TAREA_NUCLEO")"
esperar 3-volver "otros recursos que se crean, destruyen o reemplazan" 0 \
  "$(($(cuenta 3-volver create) + $(cuenta 3-volver delete) + $(cuenta 3-volver delete+create) - 1))"
aplicar 3-volver
fila 3-volver "Reintegrar 1: \`traffic-in-service\`"

etapa 4-trafico "4. Reintegrar, segundo apply: transition = \"traffic-in-nucleo\"" -var-file="$WORK/trafico-en-nucleo.tfvars"
esperar 4-trafico "la regla del módulo" update "$(accion_de 4-trafico "$REGLA")"
esperar 4-trafico "la definición de tarea del núcleo" ninguna "$(accion_de 4-trafico "$TAREA_NUCLEO")"
esperar 4-trafico "recursos que se crean, destruyen o reemplazan" 0 \
  "$(($(cuenta 4-trafico create) + $(cuenta 4-trafico delete) + $(cuenta 4-trafico delete+create)))"
aplicar 4-trafico
fila 4-trafico "Reintegrar 2: \`traffic-in-nucleo\`"

etapa 5-retirar "5. Reintegrar, tercer apply: el módulo sale de promoted_modules"
esperar 5-retirar "la regla del módulo" update "$(accion_de 5-retirar "$REGLA")"
esperar 5-retirar "la definición de tarea del núcleo" ninguna "$(accion_de 5-retirar "$TAREA_NUCLEO")"
esperar 5-retirar "recursos que se destruyen (los que creó la promoción)" "$RECURSOS_PROMOCION" "$(cuenta 5-retirar delete)"
esperar 5-retirar "recursos que se crean o reemplazan" 0 "$(($(cuenta 5-retirar create) + $(cuenta 5-retirar delete+create)))"
# The apply is the check: the target group is deleted in the same apply that takes it out of
# the rule, and the emulator, like AWS, refuses to delete one a rule still uses.
aplicar 5-retirar
fila 5-retirar "Reintegrar 3: fuera de \`promoted_modules\`"

sin_cambios 5-estable "   Un plan más, sin tocar nada"

# --- a release in between -------------------------------------------------------------------------

echo
echo "6. El pipeline despliega la versión 1.1.0 y la anota en /zonacards/$ENTORNO/release"
curl -fsS -o /dev/null -X POST "$EMULADOR/" --aws-sigv4 "aws:amz:us-east-1:ssm" --user test:test \
  -H 'Content-Type: application/x-amz-json-1.1' -H 'X-Amz-Target: AmazonSSM.PutParameter' \
  -d "{\"Name\":\"/zonacards/$ENTORNO/release\",\"Value\":\"1.1.0\",\"Type\":\"String\",\"Overwrite\":true}" ||
  morir "no se pudo escribir el parámetro en el emulador"
# Terraform learns the new value the way it does in AWS: by reading the parameter.
tf apply -refresh-only -auto-approve -input=false -lock=false -no-color \
  -target=module.zonacards.aws_ssm_parameter.release >"$WORK/6-leer.txt" 2>&1 || {
  tail -20 "$WORK/6-leer.txt" >&2
  morir "no se pudo releer el parámetro"
}
leido="$(tf show -json | jq -r '[.. | objects | select(.address? == "module.zonacards.aws_ssm_parameter.release") | .values.insecure_value][0]')"
esperar 6-leer "versión que Terraform lee del parámetro" 1.1.0 "$leido"

sin_cambios 6-estable "   Un plan con la versión nueva anotada: una versión no es un cambio de infraestructura"

etapa 7-promover "7. Promover otra vez, ahora con la 1.1.0 desplegada" -var-file="$WORK/trafico-en-nucleo.tfvars"
imagen="$(jq -r --arg m "$MODULO" '
  .resource_changes[] | select(.address == "module.zonacards.module.promoted[\"\($m)\"].aws_ecs_task_definition.service")
  | .change.after.container_definitions | fromjson | .[0].image | sub("^.*/"; "")' "$WORK/7-promover.plan.json")"
esperar 7-promover "imagen con la que nace el servicio nuevo" "app:1.1.0" "$imagen"
esperar 7-promover "la definición de tarea del núcleo" ninguna "$(accion_de 7-promover "$TAREA_NUCLEO")"
esperar 7-promover "recursos que se crean" "$RECURSOS_PROMOCION" "$(cuenta 7-promover create)"

# --- summary --------------------------------------------------------------------------------------

echo
echo "Ambiente base: $RECURSOS_BASE recursos. Una promoción agrega $RECURSOS_PROMOCION."
echo
echo "| Apply | Crea | Cambia en el sitio | Reemplaza | Destruye | Regla de \`$MODULO\` | Definición de tarea del núcleo |"
echo "|---|---:|---:|---:|---:|---|---|"
printf '%s\n' "${filas[@]}" | sed 's/delete+create/revisión nueva/g; s/| update |/| cambia en el sitio |/g; s/| ninguna |/| sin cambios |/g'
echo
echo "Ensayado el $(date -u +%Y-%m-%dT%H:%MZ) con $(terraform version -json | jq -r '"Terraform \(.terraform_version)"') y el proveedor aws $(tf version -json | jq -r '.provider_selections["registry.terraform.io/hashicorp/aws"]'), contra $IMAGEN. Ninguna cuenta de AWS intervino."
