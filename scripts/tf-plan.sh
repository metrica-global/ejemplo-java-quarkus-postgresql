#!/usr/bin/env bash
# terraform plan of one environment against a real AWS account, WITHOUT a remote backend and
# WITHOUT ever applying.
#
#   AWS_PROFILE=tf scripts/tf-plan.sh dev
#
# The environments keep their state in S3. To see what Terraform would create before that bucket
# exists, this script plans a copy of infra/terraform that uses a throwaway local state. It only
# reads from AWS (caller identity, availability zones and the like); it creates nothing, and the
# copy is deleted by removing the directory it prints.
set -euo pipefail

ENVIRONMENT="${1:-dev}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[ -d "$ROOT/infra/terraform/envs/$ENVIRONMENT" ] || { echo "ambiente desconocido: $ENVIRONMENT" >&2; exit 1; }
[ -n "${AWS_PROFILE:-}${AWS_ACCESS_KEY_ID:-}" ] || { echo "defina AWS_PROFILE (por ejemplo AWS_PROFILE=tf)" >&2; exit 1; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/zonacards-tf-plan.XXXXXX")"
mkdir -p "$WORK/infra" "$WORK/deploy"
# The database module reads deploy/db/bootstrap-service.sql by a relative path.
cp -R "$ROOT/deploy/db" "$WORK/deploy/db"
(cd "$ROOT/infra" && tar --exclude='.terraform' --exclude='*.tfstate*' -cf - terraform) | (cd "$WORK/infra" && tar -xf -)

cd "$WORK/infra/terraform/envs/$ENVIRONMENT"
cat > backend_override.tf <<EOF
terraform {
  backend "local" {
    path = "$WORK/$ENVIRONMENT.tfstate"
  }
}
EOF

terraform init -input=false -reconfigure >/dev/null
terraform plan -input=false -lock=false -out="$WORK/$ENVIRONMENT.tfplan" "${@:2}"

echo
echo "Plan guardado en $WORK/$ENVIRONMENT.tfplan (no se aplicó nada)."
echo "Para ver solo el autoescalado:"
echo "  terraform -chdir=$WORK/infra/terraform/envs/$ENVIRONMENT show $WORK/$ENVIRONMENT.tfplan | grep -n appautoscaling"
