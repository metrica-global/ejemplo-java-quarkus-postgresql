#!/usr/bin/env bash
# Runs once, when the local database is created. Gives every service its schema and its two
# roles with the SAME script the tests and the AWS bootstrap task use.
#
# The passwords below are for the local environment only. In AWS they are generated into
# Secrets Manager and never written to a file.
set -euo pipefail

bootstrap() {
  local svc="$1" limit="$2"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v svc="$svc" \
    -v owner_password="${svc}-owner-local" \
    -v app_password="${svc}-app-local" \
    -v app_conn_limit="$limit" \
    -f /zonacards/bootstrap-service.sql
}

bootstrap lotes 40
bootstrap giftcards 120
