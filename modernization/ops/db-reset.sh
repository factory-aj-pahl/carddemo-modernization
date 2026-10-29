#!/usr/bin/env bash
# Drops and re-creates the stack database ($CARDDEMO_DB, default carddemo), maps the carddemo_app login
# to it as db_owner, and runs the Flyway migrations. Stop the stack first (make down): open connections
# are rolled back.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/stack-env.sh"
require_secrets MSSQL_SA_PASSWORD CARDDEMO_DB_PASSWORD
db="$CARDDEMO_DB"
if ! [[ "$db" =~ ^[A-Za-z][A-Za-z0-9_]{0,63}$ ]] || [[ "$db" =~ ^(master|model|msdb|tempdb)$ ]]; then
    echo "refusing to reset database '$db'" >&2
    exit 2
fi
sa() { SQLCMDPASSWORD="$MSSQL_SA_PASSWORD" "$SQLCMD" -S 127.0.0.1 -U sa -C -b -h -1 "$@"; }
sa -Q "SET NOCOUNT ON;
IF DB_ID('$db') IS NOT NULL BEGIN
    ALTER DATABASE [$db] SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE [$db];
    PRINT 'dropped database $db';
END;
CREATE DATABASE [$db];
PRINT 'created database $db';"
sa -d "$db" -Q "SET NOCOUNT ON;
CREATE USER carddemo_app FOR LOGIN carddemo_app;
ALTER ROLE db_owner ADD MEMBER carddemo_app;
PRINT 'carddemo_app is db_owner of $db';"
batch reset --spring.flyway.clean-disabled=false
