#!/usr/bin/env bash
# Sets up or updates BeanDesk's database on this server: makes the database, applies
# database/platform.sql and the migrations it does not have yet, in order, and makes the API's
# database user. Run it as the postgres superuser, from a clone of the repository that the
# postgres user can read (such as /opt/beandesk):
#
#   sudo -u postgres env API_PASSWORD='...' bash deploy/migrate.sh
#
# API_PASSWORD is the password in DATABASE_URL in deploy/.env; it is only needed the first time.
# DB (default beandesk) and PGPORT choose another database or port.
set -euo pipefail

DB=${DB:-beandesk}
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }
# Only warnings and errors, not notices such as "already granted".
export PGOPTIONS="-c client_min_messages=warning"

if ! psql -X -tAc "select 1 from pg_database where datname = '$DB'" -d postgres | grep -q 1; then
  echo "Making database $DB"
  createdb "$DB"
fi

run -d "$DB" -c "create schema if not exists migrations;
  create table if not exists migrations.applied (version text primary key, applied_at timestamptz not null default now())"

for file in "$ROOT/database/platform.sql" "$ROOT"/database/migrations/*.sql; do
  version=$(basename "$file" .sql)
  if psql -X -d "$DB" -tAc "select 1 from migrations.applied where version = '$version'" | grep -q 1; then
    continue
  fi
  echo "Applying $version"
  run -d "$DB" --single-transaction -f "$file" -c "insert into migrations.applied (version) values ('$version')"
done

# The API's own user: it may only switch to the roles the migrations grant things to.
run -d "$DB" -v password="${API_PASSWORD:-}" <<'SQL'
select not exists (select 1 from pg_roles where rolname = 'beandesk_api') as missing \gset
\if :missing
    create role beandesk_api login noinherit;
\endif
select length(:'password') > 0 as has_password \gset
\if :has_password
    alter role beandesk_api password :'password';
\endif
grant anon, authenticated, service_role to beandesk_api;
SQL
run -d "$DB" -c "grant connect on database \"$DB\" to beandesk_api"
echo "Database $DB is up to date."
