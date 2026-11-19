-- Creates the schema and the two roles of ONE service. Idempotent: safe to re-run.
--
-- Each service owns a schema in the shared PostgreSQL instance and gets two roles:
--   <svc>_owner  owns the schema; used only by the migration task (Flyway).
--   <svc>_app    DML only, with a connection limit; used by the running service.
-- No role can see another service's schema: the only way to another domain's data is an event.
--
-- Run as the database administrator (the RDS master user, or the local superuser):
--   psql -v ON_ERROR_STOP=1 -v svc=lotes -v owner_password=... -v app_password=... \
--        -v app_conn_limit=40 -f bootstrap-service.sql
--
-- The same file is used by the tests, by docker compose and by the bootstrap task in AWS.

\set owner :svc '_owner'
\set app :svc '_app'

SELECT format('CREATE ROLE %I LOGIN', :'owner')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'owner') \gexec
SELECT format('CREATE ROLE %I LOGIN', :'app')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app') \gexec

ALTER ROLE :"owner" WITH PASSWORD :'owner_password';
ALTER ROLE :"app" WITH PASSWORD :'app_password' CONNECTION LIMIT :app_conn_limit;

-- The RDS master user is not a superuser: it must be a member of the owner role
-- to create a schema on its behalf and to set its default privileges.
GRANT :"owner" TO CURRENT_USER;

CREATE SCHEMA IF NOT EXISTS :"svc" AUTHORIZATION :"owner";

-- Nothing is granted by default.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', current_database(), :'owner', :'app') \gexec

-- The runtime role can use the schema but cannot change it.
GRANT USAGE ON SCHEMA :"svc" TO :"app";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA :"svc"
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA :"svc"
  GRANT USAGE, SELECT ON SEQUENCES TO :"app";
-- Covers tables that already exist when the script is re-run.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA :"svc" TO :"app";

-- Unqualified table names resolve to the service's own schema.
ALTER ROLE :"owner" SET search_path = :"svc";
ALTER ROLE :"app" SET search_path = :"svc";
