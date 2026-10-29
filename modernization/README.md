# CardDemo modernized stack

The CardDemo mainframe application rebuilt as a React + TypeScript web app (`web/`), a Spring Boot API
(`backend/app`), Spring Batch jobs (`backend/batch`) orchestrated by Apache Airflow (`airflow/`), SQL Server,
and RabbitMQ. `architecture.md` describes the design; `spec/` and `decisions/` the behavior it implements.
Everything runs on this machine and binds to 127.0.0.1 only.

| Service | Address | Started by |
|:--|:--|:--|
| Web app (vite preview of `web/dist`, proxies `/api` to the API) | http://127.0.0.1:5173 | `make up` |
| Online API (OpenAPI at `/v3/api-docs`) | http://127.0.0.1:8081 | `make up` |
| Airflow 3 (UI and REST API; scheduler and DAG processor) | http://127.0.0.1:8080 | `make up` |
| Standalone bill-payment service | http://127.0.0.1:8091 | `make billpay` |
| SQL Server 2025 Developer, database `carddemo` | 127.0.0.1:1433 | systemd |
| RabbitMQ, vhost `carddemo` (management 15672) | 127.0.0.1:5672 | systemd |

Sign on with `ADMIN001` (admin) or `USER0001` (regular user); the demo password is in the repository
`README.md`.

## Prerequisites

Installed on this machine (Ubuntu 24.04) with `sudo apt` unless noted:

| Component | Version | Configuration |
|:--|:--|:--|
| OpenJDK | 21 | `java` on the PATH |
| Maven | 3.8.7 | `mvn` on the PATH |
| Node.js / npm | 24 | Playwright 1.64 with its Chromium in `~/.cache/ms-playwright` (`npx playwright install chromium`) |
| SQL Server 2025 Developer | 17.0 | systemd service `mssql-server`, port 1433 on 127.0.0.1, memory capped at 2 GB (`mssql-conf set memory.memorylimitmb 2048`); `sqlcmd` from `mssql-tools18` in `/opt/mssql-tools18/bin`. Login `carddemo_app` (SQL authentication) created once as `sa`; `make db-reset` maps it into the database as `db_owner` |
| RabbitMQ | 3.12.1 | systemd service `rabbitmq-server`; `/etc/rabbitmq/rabbitmq.conf`: `listeners.tcp.default = 127.0.0.1:5672`, `management.tcp.ip = 127.0.0.1`; management plugin enabled; vhost `carddemo` and user `carddemo` with all permissions on it. The API declares its queues when it connects |
| Apache Airflow | 3.3.2 | Python 3.12 venv in `~/.carddemo/airflow-venv` (not apt); `AIRFLOW_HOME` `~/.carddemo/airflow` (SQLite metadata, simple auth manager user `carddemo`, password generated into `simple_auth_manager_passwords.json.generated` on first start). Settings in `airflow/env.sh` |
| GnuCOBOL | 3.1.2 | `cobc`, for the verification harness only (`make -C .. setup-cobol`) |

Secrets live only in `~/.carddemo/secrets.env` (mode 600, never in the repository), one `KEY=value` per line:

| Key | Used for |
|:--|:--|
| `MSSQL_SA_PASSWORD` | `sa` login; only `make db-reset` uses it (drop and create the database) |
| `CARDDEMO_DB_PASSWORD` | `carddemo_app` login: API, batch jar, Airflow tasks, billpay-service, tests |
| `RABBITMQ_PASSWORD` | RabbitMQ user `carddemo` |

The `make` stack targets read this file themselves (`ops/stack-env.sh`) when the variables are not already
set. Other settings can be overridden from the environment: `CARDDEMO_DB` (default `carddemo`),
`CARDDEMO_API_PORT`, `CARDDEMO_WEB_PORT`, `CARDDEMO_AIRFLOW_PORT`, `CARDDEMO_BILLPAY_PORT`, `CARDDEMO_RUN`
(pid files and logs, default `~/.carddemo/run`), `AIRFLOW_HOME`, `CARDDEMO_INBOX` / `CARDDEMO_OUTBOX`
(batch inputs and outputs, default `~/.carddemo/inbox` and `~/.carddemo/outbox`).

## Build, load, and start from scratch

From the repository root:

```sh
make -C modernization setup      # backend jars, npm ci + build of web/, npm ci of e2e/
make -C modernization db-reset   # drop and re-create database carddemo, run the Flyway migrations
make -C modernization load       # sample data: app/data/ASCII, USRSEC users and account links, IMS sample
make -C modernization up         # Airflow 8080, API 8081, web 5173; then the stack check
make -C modernization smoke      # browser smoke flow against the running stack
```

What each step does:

- `setup`: `mvn package -DskipTests` builds `backend/app/target/carddemo-api.jar`,
  `backend/batch/target/carddemo-batch.jar`, and `backend/billpay-service/target/carddemo-billpay-service.jar`;
  `web/dist` is the production build of the web app.
- `db-reset` (`ops/db-reset.sh`): as `sa`, drops `carddemo` if it exists, creates it, adds `carddemo_app` as
  `db_owner`, then `carddemo-batch.jar reset` runs every migration (`SCHEMA RESET`).
- `load` (`ops/load.sh`): `carddemo-batch.jar load` of every base file (accounts, cards, customers, card
  cross-references, transaction types and categories, disclosure groups, category balances, daily
  transactions file is the POSTTRAN input and is not a table), the 10 USRSEC users with BCrypt hashes and the
  sample accounts linked round-robin to the regular users (D-SEED), and the IMS pending-authorization unload.
  Transaction types and categories come from the ASCII `trantype.txt` and `trancatg.txt` (DF-1), so the Db2
  module seed (`DB2LTTYP`, `DB2LTCAT`) is not loaded on top; `carddemo-batch.jar loaddb2 types=... categories=...`
  would replace them with it. The transaction table starts empty, as on the mainframe; the daily cycle fills it.
- `up` (`ops/up.sh`): starts Airflow with `airflow/start.sh` (migrates its metadata DB, reserializes and
  unpauses the report DAG), waits for its health, starts the API with the Airflow credentials it needs for
  report runs, waits for `/actuator/health` UP, starts the web app, and runs `ops/check_stack.py`, which must
  print `stack check: 8/8 passed`: API health UP, web app and its proxy, Airflow healthy, the four owed DAGs
  (`carddemo_daily_cycle`, `carddemo_transaction_report`, `carddemo_auth_purge`,
  `carddemo_trantype_maintenance`) listed, the report DAG unpaused, no DAG import errors, and the eight RabbitMQ
  queues declared (checked by a passive declare over AMQP as user `carddemo`). A service that is already running is left alone, so `make up` can be repeated. Logs:
  `~/.carddemo/run/api.log`, `~/.carddemo/run/web.log`, `~/.carddemo/airflow/run/*.log`.
- `smoke` (`e2e/smoke/smoke.spec.ts`): signs on as USER0001, opens Account View for a linked account, pays its
  balance on Bill Payment (checked in the database), then signs on as ADMIN001 and lists the users. Each run
  pays one more linked account; after `db-reset load` it can run 5 times (USER0001 has that many accounts with a
  balance).

`make -C modernization status` repeats the stack check. Stop everything with:

```sh
make -C modernization down       # web, API, billpay-service, Airflow (pid files in ~/.carddemo/run)
```

To start over, run `down`, then `db-reset`, `load`, `up`.

## Batch and scheduling

The Airflow DAGs run the batch jar (`airflow/dags/carddemo_batch.py`). The daily cycle
(`carddemo_daily_cycle`) is scheduled at 22:00 UTC Monday to Friday; each scheduled run posts its own date (the
run of Friday 2022-07-01 posts business date 2022-07-01), and a run on a date in `CARDDEMO_HOLIDAYS`
(comma-separated YYYY-MM-DD) skips every task. It is created paused, and `airflow/start.sh` pauses it again on
every start, so the stack never posts on its own. To schedule it, run
`airflow dags unpause carddemo_daily_cycle` (after `. modernization/airflow/env.sh`; it lasts until the next
start), or start the stack with `CARDDEMO_CYCLE_SCHEDULE=on`. A paused DAG also leaves manual runs queued, so
unpause it before triggering one. Trigger a date by hand (for example one missed while the DAG was paused; there
is no catchup) from the Airflow UI or REST API with conf `{"business_date": "YYYY-MM-DD"}` after placing the daily
file in `~/.carddemo/inbox/<date>/dailytran.txt`; outputs (rejects, statements) go to `~/.carddemo/outbox/<date>/`.
Interest (INTCALC) runs on the first business day of each month; if that day's cycle is missed, the next date run
in the month charges it (the jar's `INTSTATUS` tells the DAG whether the month is already calculated), once some
month has been calculated on the database.
If POSTTRAN fails (for example an abend, exit 99, on a bad daily record), the records it committed stay posted. Fix
the cause, then either clear the failed POSTTRAN task (with its downstream tasks) in the same DAG run, which resumes
posting after the last committed record, or run `carddemo-batch.jar RESTORE businessDate=<date>` and trigger the
cycle for the date again. A new trigger without the restore stops at BACKUP with exit 8
(`BACKUP REFUSED: POSTING ALREADY STARTED FOR <date>`), because a backup of a partly posted state would make the
restore by date useless (`architecture.md` section 4).
The transaction report DAG is started by the web app's report screen (CORPT00C); the file is downloadable
there. The jar also runs on its own:
`java -jar backend/batch/target/carddemo-batch.jar <COMMAND> key=value ...` (commands and exit codes in
`architecture.md` section 5).

## Standalone bill-payment service

Bill payment (BC-5) runs alone as `billpay-service`: sign-on, the main menu (other options answer
"not installed"), user maintenance from the IAM module, and Bill Payment, on the same database.

```sh
make -C modernization billpay        # start it alone on 8091 against carddemo; stop with make down
make -C modernization test-billpay   # mvn -pl billpay-service -am verify (see below)
```

Authentication: the service carries the IAM module, so it signs users on itself: `POST /api/screens/COSGN00C`
with `{"key":"ENTER","userId":...,"password":...}` checks the BCrypt hash in the shared `app_user` table and
creates a server-side session in this service (cookie `JSESSIONID`, HttpOnly, SameSite=Strict, 15-minute idle
timeout); state-changing requests must echo the `XSRF-TOKEN` cookie in the `X-XSRF-TOKEN` header. Roles come
from the session (ADMIN screens answer 403 to a regular user), and a regular user reaches only linked accounts.
Sessions are not shared with the API on 8081: sign on to each service separately.

`test-billpay` builds the service with only its dependencies, runs the unit tests, then (failsafe) starts the
packaged jar alone on port 8092 against `CARDDEMO_TEST_DB` (default `carddemo_test`, reloaded with the sample
data) and runs its HTTP tests against that process: `StandaloneBillPaymentIT` (pay a positive balance, reject a
zero balance, account not found, out-of-scope account) and `StandaloneSecurityMatrixIT` (every operation in the
service's own `/v3/api-docs` as anonymous, USER0001, and ADMIN001, with a leak scan;
report in `backend/billpay-service/target/billpay-security-matrix.txt`).

## Tests and verification

The test suites clean and reload their own database (`CARDDEMO_TEST_DB` for the backend, `CARDDEMO_E2E_DB`
for e2e; both default to `carddemo_test`). Do not point them at `carddemo` while the stack runs.

| Command | What it runs |
|:--|:--|
| `make -C modernization test-backend` | `mvn verify` of every module, including the module-boundaries and security-matrix tests and the billpay-service HTTP tests |
| `make -C modernization test-billpay` | billpay-service alone, as above |
| `make -C modernization test-e2e` | the Playwright suite (`e2e/tests`); it seeds `CARDDEMO_E2E_DB` and starts its own API, web app, and an Airflow stand-in on `CARDDEMO_API_PORT` / `CARDDEMO_WEB_PORT` (default 8081 / 5173), so stop the stack first or set other ports |
| `make -C modernization test-web` | TypeScript checks of `web/` and `e2e/` |
| `make -C modernization test-airflow` | DAG and helper unit tests |
| `make -C modernization verify-batch OUT=/abs/dir` | batch parity: every Java job against the patched GnuCOBOL reference |
| `make -C modernization verify-data OUT=/abs/dir` | load/export round trip of all sample data |
| `make -C modernization verify-cycle OUT=/abs/dir` | Airflow daily and month-start cycles against the reference chain (scratch `CARDDEMO_DB`) |
| `make -C modernization verify-report OUT=/abs/dir` | with the stack up: report requests in the browser as ADMIN001 and USER0001 on the real Airflow, each download compared byte for byte with GnuCOBOL CBTRN03C on the same tables and range (USER0001: linked accounts only, D-REPORT). If the sample daily file has not been posted yet, it first runs `carddemo_daily_cycle` for 2022-06-10 on it through the Airflow REST API; the range defaults to the first to last processing date in the database |
| `make -C modernization verify-harness`, `verify-ledger`, `verify-spec`, `trace` | harness qualification, ledger and spec checks, rule traceability |

The verification targets need the secrets in the environment (`set -a; . ~/.carddemo/secrets.env; set +a`).
The repository's original checks still run from the root with `make check` (see the root `AGENTS.md`).
