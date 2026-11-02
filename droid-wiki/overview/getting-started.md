# Getting started

This page gets you from a fresh clone to a working setup for either half of the repository: the COBOL tooling at the root, or the modernized stack under `modernization/`. The two are independent. You can work on the COBOL with nothing but Python and GnuCOBOL, and you can run the modern stack without touching the root tooling.

## Choose your path

| Goal | Path | Needs |
|:--|:--|:--|
| Lint, test, or change COBOL programs | [Root tooling](#root-tooling-cobol-without-a-mainframe) | Python 3.10+, GnuCOBOL 3.x |
| Run the web app, API, and batch cycle | [Modernized stack](#modernized-stack) | JDK 21, Maven, Node 24, SQL Server, RabbitMQ, Airflow 3 |
| Install on a real mainframe | Root `README.md`, "Installation" | z/OS with CICS, VSAM, JCL (Db2, IMS, MQ optional) |

## Root tooling: COBOL without a mainframe

The root `Makefile` and `AGENTS.md` describe these commands. They need no JDK, Node, or database.

```sh
make setup          # .venv with pinned tools from requirements-dev.txt, plus git hooks
make setup-cobol    # GnuCOBOL 3.x via apt (syntax checks, batch runs, cobc tests)
cp .env.example .env   # optional; documents the variables the tooling reads
```

The devcontainer in `.devcontainer/devcontainer.json` installs the same things.

Common commands:

| Task | Command |
|:--|:--|
| Everything CI runs | `make check` |
| COBOL/JCL static analysis (new findings only) | `make lint` |
| GnuCOBOL syntax check of batch programs | `make syntax` |
| Tests (all / no GnuCOBOL needed) | `make test` / `make test-unit` |
| Coverage gate (90 percent) | `make coverage` |
| Regenerate `docs/INVENTORY.md` | `make docs` |
| Run one batch program locally | `make run-batch PROGRAM=CBTRN02C` |

A quick way to confirm the setup works is the posting job:

```sh
make run-batch PROGRAM=CBTRN02C
```

It compiles `app/cbl/CBTRN02C.cbl`, loads the sample files into indexed files under `.batch-runs/CBTRN02C/`, and prints the job output. A good run ends with RC=4, 300 transactions processed, and 43 rejected with reason `0102 OVERLIMIT TRANSACTION` (written to `DALYREJS`). The [Local tooling](../mainframe/local-tooling.md) page explains the runner.

The 43 rejects come from the cobc default sign handling. The modernization's parity harness compiles with `-fsign=EBCDIC`, which decodes the overpunched signs in the ASCII sample correctly and gives 38 rejects (`modernization/verification/parity/README.md`). Both numbers are expected in their own context.

## Modernized stack

### Prerequisites

`modernization/README.md` lists the exact versions the stack was built and tested with on Ubuntu 24.04:

| Component | Version | Notes |
|:--|:--|:--|
| OpenJDK | 21 | `java` on the PATH |
| Maven | 3.8.7 | `mvn` on the PATH |
| Node.js / npm | 24 | Playwright Chromium via `npx playwright install chromium` |
| SQL Server Developer | 2025 (17.0) | systemd `mssql-server`, 127.0.0.1:1433; login `carddemo_app` created once as `sa`; `sqlcmd` in `/opt/mssql-tools18/bin` |
| RabbitMQ | 3.12 | systemd `rabbitmq-server`, listening on 127.0.0.1; management plugin; vhost `carddemo` and user `carddemo` |
| Apache Airflow | 3.3.2 | Python 3.12 venv in `~/.carddemo/airflow-venv`; `AIRFLOW_HOME` `~/.carddemo/airflow` |
| GnuCOBOL | 3.1.2 | Only for the verification harness |

### Secrets

Secrets live only in `~/.carddemo/secrets.env` (mode 600), never in the repository:

| Key | Used for |
|:--|:--|
| `MSSQL_SA_PASSWORD` | `sa` login, used only by `make db-reset` |
| `CARDDEMO_DB_PASSWORD` | `carddemo_app` login for the API, batch jar, Airflow tasks, billpay-service, and tests |
| `RABBITMQ_PASSWORD` | RabbitMQ user `carddemo` |

The stack targets read this file through `modernization/ops/stack-env.sh`. The verification targets expect the variables in the environment: `set -a; . ~/.carddemo/secrets.env; set +a`.

### Build, load, and start

From the repository root:

```sh
make -C modernization setup      # backend jars, npm ci + build of web/, npm ci of e2e/
make -C modernization db-reset   # drop and re-create database carddemo, run the Flyway migrations
make -C modernization load       # sample data, USRSEC users with BCrypt hashes, IMS sample
make -C modernization up         # Airflow 8080, API 8081, web 5173, then the stack check
make -C modernization smoke      # browser smoke flow against the running stack
```

`make up` ends by running `modernization/ops/check_stack.py`, which must print `stack check: 8/8 passed`. Then open http://127.0.0.1:5173 and sign on as `ADMIN001` (admin) or `USER0001` (regular user). The demo password is in the root `README.md`.

| Service | Address |
|:--|:--|
| Web app | http://127.0.0.1:5173 |
| Online API (OpenAPI at `/v3/api-docs`) | http://127.0.0.1:8081 |
| Airflow UI and REST API | http://127.0.0.1:8080 |
| Bill-payment service (`make -C modernization billpay`) | http://127.0.0.1:8091 |

Stop everything with `make -C modernization down`. To start over, run `down`, `db-reset`, `load`, `up`. The [Deployment](../deployment.md) page covers the ops scripts in detail.

### Running the batch cycle by hand

The daily cycle DAG is created paused and `modernization/airflow/start.sh` pauses it again on every start, so the stack never posts on its own. To post a business date, place the daily file in `~/.carddemo/inbox/<date>/dailytran.txt` and trigger `carddemo_daily_cycle` from the Airflow UI with conf `{"business_date": "YYYY-MM-DD"}`. Outputs go to `~/.carddemo/outbox/<date>/`. The jar also runs on its own:

```sh
java -jar modernization/backend/batch/target/carddemo-batch.jar <COMMAND> key=value ...
```

See [Batch jar](../applications/batch-jar.md) and [Daily batch cycle](../features/daily-batch-cycle.md).

### Tests

The backend and e2e suites clean and reload their own database (`CARDDEMO_TEST_DB` and `CARDDEMO_E2E_DB`, both defaulting to `carddemo_test`). Never point them at `carddemo` while the stack runs.

| Command | What it runs |
|:--|:--|
| `make -C modernization test-backend` | `mvn verify` of every module |
| `make -C modernization test-e2e` | Playwright suite; starts its own API and web app on 8081/5173, so stop the stack first |
| `make -C modernization test-web` | TypeScript checks of `web/` and `e2e/` |
| `make -C modernization test-airflow` | DAG and helper unit tests |
| `make -C modernization verify-batch` | Batch parity against GnuCOBOL |

The [Testing](../how-to-contribute/testing.md) page covers each suite.

## Next steps

- [Architecture](architecture.md) for the big picture
- [Development workflow](../how-to-contribute/development-workflow.md) before your first change
- [Debugging](../how-to-contribute/debugging.md) when something does not start
