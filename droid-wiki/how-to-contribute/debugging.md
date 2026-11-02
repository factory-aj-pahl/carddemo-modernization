# Debugging

This page lists where each process writes its logs, how to read the stack check, and the failures people actually hit, with the fix for each. The last sections cover debugging COBOL locally and comparing a Java batch job with its GnuCOBOL reference.

## Where the logs are

| Source | Location | Written by |
|:--|:--|:--|
| Online API | `~/.carddemo/run/api.log` | `modernization/ops/up.sh` redirects the jar's output there |
| Web app (vite preview) | `~/.carddemo/run/web.log` | `modernization/ops/up.sh` |
| Bill-payment service | `~/.carddemo/run/billpay.log` | `modernization/ops/billpay.sh` |
| Airflow api-server, scheduler, dag-processor | `~/.carddemo/airflow/run/<service>.log` | `modernization/airflow/start.sh` |
| Airflow metadata migration and DAG reserialize | `~/.carddemo/airflow/run/db-migrate.log`, `reserialize.log` | `modernization/airflow/start.sh` |
| Airflow task logs | Airflow's own log folder under `AIRFLOW_HOME` (`~/.carddemo/airflow`), also shown in the Airflow UI per task try | Airflow default; the repository does not override it |
| Batch SYSOUT in a DAG task | The task log, one `SYSOUT ...` line per output line and `STDERR ...` lines for the error stream | `_run_jar` in `modernization/airflow/dags/carddemo_batch.py` |
| Batch jar application log | `carddemo-batch.log` in `CARDDEMO_LOG_DIR`, else `java.io.tmpdir` (usually `/tmp`) | `logging.file.name` in `modernization/backend/batch/src/main/resources/application.properties` |
| ... when run by a DAG | `~/.carddemo/outbox/logs/carddemo-batch.log` | `carddemo_batch.py` sets `CARDDEMO_LOG_DIR` to `<outbox>/logs` |
| ... when run by `db-reset` or `load` | `~/.carddemo/run/carddemo-batch.log` | `batch()` in `modernization/ops/stack-env.sh` |
| Backend test output | `modernization/backend/<module>/target/surefire-reports/` (`TEST-*.xml`, `<class>.txt`, `<class>-output.txt`) | surefire with `redirectTestOutputToFile` (`modernization/backend/pom.xml`) |
| billpay-service HTTP tests | `modernization/backend/billpay-service/target/failsafe-reports/` | failsafe |
| Security matrix details | `modernization/backend/app/target/security-matrix.txt`, `modernization/backend/billpay-service/target/billpay-security-matrix.txt` | `SecurityMatrixTest`, `StandaloneSecurityMatrixIT` |
| Playwright | `modernization/e2e/test-results/` (`junit.xml`, plus a trace for each failed test) | `modernization/e2e/playwright.config.ts` (`trace: 'retain-on-failure'`) |
| Root pytest | Terminal, or `reports/junit.xml` and `reports/coverage.xml` after `make coverage` | root `Makefile` |

The batch jar sends only SYSOUT to standard output and turns console logging off (`logging.threshold.console=OFF`), so a stack trace from a batch run is in `carddemo-batch.log`, not on the terminal. Open a Playwright trace with `npx playwright show-trace <path>` from `modernization/e2e`.

## Reading the stack check

`make -C modernization status` runs `modernization/ops/check_stack.py` with the environment from `modernization/ops/stack-env.sh`. It prints one line per check, `OK  ` or `FAIL`, then `stack check: N/8 passed`, and exits 1 on any failure. `make up` runs the same script with `--wait 120`, retrying every 5 seconds because the scheduler heartbeat and first DAG parse lag the start.

| Check | Passes when | If it fails |
|:--|:--|:--|
| `api health` | `GET :8081/actuator/health` returns 200 with `"UP"` | Read `~/.carddemo/run/api.log`: database login, RabbitMQ connection, or port in use |
| `web app` | `GET :5173/` returns the page with `<div id="root"` | `~/.carddemo/run/web.log`; `web/dist` missing means `make -C modernization setup` was not run |
| `web proxy to api` | `GET :5173/actuator/health` is `"UP"` through the vite proxy | Web is up but the API is not, or `CARDDEMO_API_URL` points elsewhere |
| `airflow health` | `/api/v2/monitor/health` reports metadatabase and scheduler `healthy` | `~/.carddemo/airflow/run/scheduler.log` |
| `airflow owed DAGs` | `carddemo_daily_cycle`, `carddemo_transaction_report`, `carddemo_auth_purge`, `carddemo_trantype_maintenance` are listed | `dag-processor.log`; also see the import errors check |
| `report DAG unpaused` | `carddemo_transaction_report` has `is_paused=False` | `~/.carddemo/airflow/run/reserialize.log`; `start.sh` unpauses it on every start |
| `airflow import errors` | `/api/v2/importErrors` total is 0 | A DAG file under `modernization/airflow/dags` does not import; the Airflow UI shows the traceback |
| `rabbitmq queues` | All 8 queues exist in vhost `carddemo` (passive declare over AMQP as user `carddemo`) | The API declares them when it connects, so a fresh broker shows them missing until the API is up; "RabbitMQ refused the sign-on" or "refused vhost" means user, password, or vhost setup |

If the Airflow REST API itself cannot be reached or the token request fails, the three Airflow REST checks collapse into one failing `airflow REST API` line. The script reads the Airflow password from `~/.carddemo/airflow/simple_auth_manager_passwords.json.generated` and prints no secret.

## Common failures

| Symptom | Cause | Fix |
|:--|:--|:--|
| `CARDDEMO_DB_PASSWORD is not set: create ~/.carddemo/secrets.env ...`, exit 2 | `require_secrets` in `modernization/ops/stack-env.sh` found the variable empty and the secrets file unreadable | Create `~/.carddemo/secrets.env` (mode 600) with the three keys from `modernization/README.md` |
| `missing .../carddemo-api.jar: run 'make -C modernization setup' first`, exit 2 | `require_file` in `stack-env.sh` | `make -C modernization setup` |
| A `verify-*` target fails with `CARDDEMO_DB_PASSWORD is not set` | Verification scripts do not read the secrets file themselves (`modernization/verification/parity/javarun.py`, `modernization/verification/data/verify_data.py`) | `set -a; . ~/.carddemo/secrets.env; set +a` in the same shell |
| `no answer from http://127.0.0.1:8081/actuator/health within 180s`, followed by the last 40 lines of `api.log` | The API did not come up; the tail usually shows why | Common causes: database `carddemo` missing (run `db-reset`), wrong password, port 8081 already used by another process |
| Port already in use when starting `test-e2e` | `modernization/e2e/playwright.config.ts` starts its own servers with `reuseExistingServer: false` on 8081 and 5173 | `make -C modernization down` first, or set `CARDDEMO_API_PORT` and `CARDDEMO_WEB_PORT` to free ports |
| The stack's data disappears after a test run | A suite was pointed at the stack database: every test context runs Flyway `clean()` (`TestDatabaseConfig`), and e2e reseeds its database | Keep `CARDDEMO_TEST_DB` and `CARDDEMO_E2E_DB` unset or on a scratch name; rebuild the stack with `down`, `db-reset`, `load`, `up` |
| `load` fails on duplicate keys | `modernization/ops/load.sh` expects a freshly reset database | `make -C modernization db-reset` then `load` |
| `refusing to reset database '<name>'` | `modernization/ops/db-reset.sh` refuses system databases and invalid names | Use a plain name in `CARDDEMO_DB` |
| A manually triggered `carddemo_daily_cycle` run stays queued | The DAG is paused, and a paused DAG leaves manual runs queued (`modernization/README.md`). `start.sh` pauses it on every start | `. modernization/airflow/env.sh; airflow dags unpause carddemo_daily_cycle`, or start with `CARDDEMO_CYCLE_SCHEDULE=on` |
| BACKUP task fails with exit 8 and `BACKUP REFUSED: POSTING ALREADY STARTED FOR <date>` | A POSTTRAN run for that date already exists; a backup of a partly posted state would make restore by date useless | Either clear the failed POSTTRAN task (and downstream) in the same DAG run, which resumes after the last committed record, or run `carddemo-batch.jar RESTORE businessDate=<date>` and trigger again |
| Exit 12 with `INVALID OR MISSING BUSINESS DATE` | `businessDate` missing or not a strict `uuuu-MM-dd` calendar date (`BatchCommandLine.BUSINESS_DATE`, B-DATE) | Pass `businessDate=YYYY-MM-DD`; from Airflow, conf `{"business_date": "YYYY-MM-DD"}` |
| Exit 99, `ABEND CODE U999` on stderr, SYSOUT ending in `ABENDING PROGRAM` | The COBOL would have abended (B-ABEND); the error message and a `FILE STATUS IS:` line come first (`modernization/backend/common/src/main/java/com/carddemo/common/batch/BatchAbend.java`) | Committed records stay posted (per-record unit of work). Fix the cause, then resume or restore as for exit 8 |
| Exit 16 | Unknown command or missing parameter; the jar prints `carddemo-batch: <problem>` and the usage line. For CBPAUP0C, PAUDBUNL, PAUDBLOD, and DBUNLDGS, 16 is the program's own return code | Check the command line; the Airflow task message says which (`failure_label` in `carddemo_batch.py`) |
| A code change does not show up in the running API or in a DAG run | The stack runs the built jars; Airflow tasks use `CARDDEMO_BATCH_JAR` or `modernization/backend/batch/target/carddemo-batch.jar` | `make -C modernization backend`, then `down` and `up` for the API. `forceCreation` in `modernization/backend/pom.xml` stops Spring Boot from repackaging stale module jars into the executable jar |
| Root `cobc` tests are reported as skipped | `cobc` not on the PATH; `tests/conftest.py` skips them | `make setup-cobol`, or set `CARDDEMO_REQUIRE_COBC=1` to turn the skip into a failure |
| `make run-batch` prints `GnuCOBOL (cobc) not found` and exits 2 | `find_cobc` in `tools/carddemo/batch.py` | Install GnuCOBOL or set `COBC` to its path (`.env.example`) |
| `SecurityMatrixTest` fails after adding an endpoint | Each OpenAPI operation needs one line in its `CLASSIFICATION` | Classify the new operation; per-operation results are in `target/security-matrix.txt` |

## Debugging COBOL locally

`make run-batch PROGRAM=<name>` runs `python3 -m tools.carddemo run-batch <name> --workdir .batch-runs/<name>`. Supported programs are CBACT02C, CBACT03C, CBCUS01C, and CBTRN02C. The runner (`tools/carddemo/batch.py`) compiles small loader programs, turns the ASCII sample files into GnuCOBOL indexed files, compiles the program, and runs it with one `DD_<name>` environment variable per dataset. After a CBTRN02C run the work directory holds:

```
.batch-runs/CBTRN02C/
  .build/          compiled program, loader programs, staged .seq inputs
  ACCTFILE  DALYTRAN  TCATBALF  TRANFILE  XREFFILE   indexed and sequential datasets
  DALYREJS         rejected transactions (430-byte records: 350-byte DALYTRAN record + 80-byte trailer)
```

The command prints SYSOUT, then `--- <PROGRAM> (...) ended with RC=<n>` and each output file with its size. It returns 0 for RC 0 or 4. A good CBTRN02C run ends with RC=4, 300 transactions processed, and 43 rejects (`AGENTS.md`). The directory is git-ignored, so you can add `DISPLAY` statements to a scratch copy, rerun, and inspect the files with a hex viewer. Never commit a `DISPLAY` of card numbers or other PII (CD070).

The reject count differs under the parity harness (38) because it compiles with `-fsign=EBCDIC`; see [Getting started](../overview/getting-started.md#root-tooling-cobol-without-a-mainframe).

## Comparing a Java job with the COBOL

When `verify-batch` reports a mismatch, rerun the pair by hand with the scripts in `modernization/verification/parity/`:

```sh
set -a; . ~/.carddemo/secrets.env; set +a
python3 modernization/verification/parity/refrun.py CBTRN02C --out /tmp/ref     # patched GnuCOBOL reference
python3 modernization/verification/parity/javarun.py CBTRN02C --out /tmp/java   # Java job on a fresh database
python3 modernization/verification/parity/compare.py /tmp/ref /tmp/java         # record-by-record diff
```

- `refrun.py` options: `--dataset DIR` (default the sample data), `--workdir DIR` to keep the GnuCOBOL scratch files, `--sign ''` for the cobc default sign handling, `--unpatched` to run the original program without the `RP-*` reference patches. It never writes to `app/`. Its output directory has `sysout.txt`, `rc.txt`, and one `<name>.txt` per output dataset.
- `javarun.py` takes `--dataset` and `--business-date` (default 2022-06-10). It resets and reloads the database named by `CARDDEMO_DB` (default `carddemo_test`), so do not run it with `CARDDEMO_DB=carddemo` exported while you need the stack data.
- `compare.py` reports a file present on one side only, a different record count, or any differing byte. Only the run-time timestamp fields listed in `NORMALIZE` and `SYSOUT_NORMALIZE` are masked.
- `verify_batch.py --only <PROGRAM> --out DIR` (or `make -C modernization verify-batch ONLY=<PROGRAM> OUT=/abs/dir`) runs every owed dataset for one program and writes `results.json`.

[Batch parity](../verification/batch-parity.md) explains the patches and datasets.

## Related pages

- [How to monitor](../how-to-monitor.md)
- [Deployment](../deployment.md)
- [Batch jar](../applications/batch-jar.md)
- [Pitfalls](../background/pitfalls.md)
