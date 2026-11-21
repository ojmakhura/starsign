#!/usr/bin/env bash
# Runs once, on an empty data directory: creates Keycloak's own database and owner.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v kc_user="$KEYCLOAK_DB_USERNAME" -v kc_pass="$KEYCLOAK_DB_PASSWORD" -v kc_db="$KEYCLOAK_DB_NAME" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'kc_user', :'kc_pass')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'kc_user')\gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'kc_db', :'kc_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'kc_db')\gexec
SQL
