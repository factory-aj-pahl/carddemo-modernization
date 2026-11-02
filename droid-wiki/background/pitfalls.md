# Pitfalls

These are the places where a new contributor is most likely to break something or misread a result. Each entry names the behavior, why it is that way, and the file that proves it.

## Overview

The traps fall into three groups: the local environment (databases, ports, Airflow), batch behavior (backups, partial posting, reject counts), and fidelity rules (messages, scope, keys, data encoding). Most of them come from a deliberate decision, described on the [design decisions](design-decisions.md) page. For failure symptoms and how to diagnose them, see [Debugging](../how-to-contribute/debugging.md).

| Pitfall | Area | Source |
|:--|:--|:--|
| Tests wipe their database | Environment | `modernization/backend/test-support/src/main/java/com/carddemo/test/TestDatabaseConfig.java` |
| e2e suite takes ports 8081 and 5173 | Environment | `modernization/e2e/playwright.config.ts:7-8` |
| Daily cycle is paused; manual runs queue | Environment | `modernization/README.md:100-104` |
| Sessions differ between 8081 and 8091 | Environment | `modernization/README.md:136` |
| Maven can package stale jars | Build | `modernization/backend/pom.xml:71-79` |
| BACKUP is refused after any POSTTRAN | Batch | `modernization/architecture.md:172-179` |
| Partial posting stays committed after an abend | Batch | `modernization/decisions/ledger-batch-data.md:20-22` |
| 43 versus 38 rejects | Batch | `modernization/decisions/ledger-batch-data.md:222` |
| Overpunch signs in ASCII data | Data | `modernization/backend/common/src/main/java/com/carddemo/common/codec/Overpunch.java` |
| Reference patches never touch `app/` | Verification | `modernization/decisions/ledger-batch-data.md:25` |
| Messages keep trailing spaces | Fidelity | `modernization/backend/common/src/main/java/com/carddemo/common/msg/CommonMessages.java:13` |
| Out-of-scope looks like not found | Fidelity | `modernization/decisions/ledger-online.md:21` |
| Account IDs are 11 digits, never padded | Fidelity | `modernization/decisions/ledger-online.md:25` |
| PF13-PF24 fold to PF1-PF12 in some programs | Fidelity | `modernization/backend/common/src/main/java/com/carddemo/common/screen/Key.java:12-16` |
| max+1 IDs need the counter lock | Data | `modernization/backend/transaction/src/main/java/com/carddemo/transaction/internal/JdbcTransactionLedger.java:24-32` |
| Active flags are not enforced | Business | `modernization/decisions/README.md:34` |
| CVVs are stored in the database | Security | `modernization/decisions/README.md:32` |

## Environment

### Tests reset their database: never point them at `carddemo`

Every backend test application context runs `flyway.clean()` and migrates again (`modernization/backend/test-support/src/main/java/com/carddemo/test/TestDatabaseConfig.java:7-14`), then reloads the sample data. The database comes from `CARDDEMO_TEST_DB`, defaulting to `carddemo_test` (`modernization/backend/test-support/src/main/resources/config/application.properties:2-3`). The e2e seed uses `CARDDEMO_E2E_DB` with the same default (`modernization/e2e/scripts/seed.mjs:11`). If you set either variable to `carddemo`, the tests erase the stack's database. The warning is written down in `modernization/README.md:147-148` and `modernization/Makefile:57-58`.

### The e2e suite starts its own servers on 8081 and 5173

`make -C modernization test-e2e` starts its own API, web app, and Airflow stand-in on `CARDDEMO_API_PORT` and `CARDDEMO_WEB_PORT`, which default to 8081 and 5173 (`modernization/e2e/playwright.config.ts:7-8`). Those are the same ports the running stack uses. Stop the stack first (`make -C modernization down`) or set other ports (`modernization/Makefile:66-69`, `modernization/README.md:154`). See [End-to-end suite](../verification/e2e-suite.md).

### The daily cycle is paused, and manual runs queue while it is

`carddemo_daily_cycle` is created paused (`is_paused_upon_creation=True`, `modernization/airflow/dags/carddemo_daily_cycle.py:71`) and `modernization/airflow/start.sh:15-22` pauses it again on every start unless `CARDDEMO_CYCLE_SCHEDULE=on`. A paused DAG also leaves manual runs queued, so a triggered run that never starts usually means the DAG is still paused (`modernization/README.md:100-104`). Catchup is off: dates missed while paused are not re-run automatically (`modernization/airflow/dags/carddemo_daily_cycle.py:8-10`). The report DAG, by contrast, must stay unpaused because the web report screen triggers it (`modernization/airflow/start.sh:12`).

### Sessions are not shared between 8081 and 8091

The standalone bill-payment service carries the IAM module and creates its own server-side sessions. Signing on to the API on 8081 does not sign you on to the service on 8091 (`modernization/README.md:131-136`). See [Bill-payment service](../applications/billpay-service.md).

### Maven stale jars and `forceCreation`

Without `forceCreation`, an unchanged module jar is not rebuilt and Spring Boot keeps stale dependency jars inside the executable jar. The parent POM sets `maven-jar-plugin` `forceCreation` to `true` for that reason (`modernization/backend/pom.xml:71-79`). Do not remove it, and if a runnable jar behaves like an old build, check that this setting is still in effect.

## Batch

### BACKUP is refused after any POSTTRAN run

BACKUP for a business date with any POSTTRAN run (started, failed, stopped, or completed, and not undone by a restore) exits 8 with `BACKUP REFUSED: POSTING ALREADY STARTED FOR <date>` (`modernization/architecture.md:172-179`). Re-triggering the daily cycle for a date that already posted therefore fails at the first task with nothing changed. To post the date again, run `RESTORE businessDate=<date>` first (`modernization/airflow/dags/carddemo_daily_cycle.py:25-31`). See [Daily batch cycle](../features/daily-batch-cycle.md).

### Partial posting stays committed after an abend

POSTTRAN commits one database transaction per daily record (B-UOW, `modernization/decisions/ledger-batch-data.md:22`). An abend (exit 99, B-ABEND, `modernization/decisions/ledger-batch-data.md:20`) rolls back only the record in progress; every record posted before it stays. After an abend, either clear the failed POSTTRAN task so it resumes from its last commit, or restore the date and trigger the whole cycle again. Re-triggering without a restore stops at BACKUP (`modernization/architecture.md:180-185`).

### 43 versus 38 rejects

The root documentation says the sample posting run rejects 43 transactions (`AGENTS.md:114`, `docs/runbooks/batch-operations.md:40`), and the root integration test asserts 43 (`tests/integration/test_batch_jobs.py:64-67`). That count comes from GnuCOBOL's default ASCII sign handling. The ledger row Q-CBTRN02C-08 says the mainframe answer, with EBCDIC overpunch semantics (`cobc -fsign=EBCDIC`), is 300 processed and 38 rejected with RC 4, and that the 43 "should read 38" (`modernization/decisions/ledger-batch-data.md:222`, reference patch RP-BUILD-1 at line 685). Both numbers are correct for their build flags: the root `make run-batch` reproduces 43, and the modernization parity harness and Java job expect 38. Do not "fix" one side to match the other without changing the build flags as well.

### Overpunch signs in the ASCII sample data

The files under `app/data/ASCII` keep signed zoned fields with the EBCDIC overpunch graphics in the last byte (Q-CBTRN02C-08, `modernization/decisions/ledger-batch-data.md:222`): `{` and `A`-`I` mean +0 to +9, `}` and `J`-`R` mean -0 to -9. The loader decodes them with `modernization/backend/common/src/main/java/com/carddemo/common/codec/Overpunch.java`. Reading such a field as plain text or as an ASCII sign gives wrong amounts, which is the root of the 43/38 difference above.

### Reference patches are never applied to `app/`

When a FIX changes a batch program's output, the ledger names a reference patch (`RP-*`) that makes the COBOL produce the expected result. Patches are applied to scratch copies under `/tmp` only, never to `app/` (B-PARITY, `modernization/decisions/ledger-batch-data.md:25`; `modernization/decisions/README.md:80-82`). If you find a patched COBOL file under `app/`, that is a bug. See [Batch parity](../verification/batch-parity.md).

## Fidelity rules

### Messages with trailing spaces must stay verbatim

D-COSMETIC keeps user-visible text exactly as the COBOL writes it (`modernization/decisions/README.md:16`). Some messages end in a space, for example `No access - Admin Only option... ` in `modernization/backend/common/src/main/java/com/carddemo/common/msg/CommonMessages.java:13`, and tests compare the exact string (`modernization/backend/iam/src/test/java/com/carddemo/iam/MenuRulesTest.java:67`). Editors and formatters that trim trailing whitespace inside string literals, or a well-meant typo fix, will break those tests and the fidelity contract.

### Out-of-scope must look like not found

C-SCOPE requires that a regular user asking for an account outside their linked accounts gets the program's own not-found message, so existence is not revealed (`modernization/decisions/ledger-online.md:21`). For example, `modernization/backend/account/src/main/java/com/carddemo/account/web/AccountLookup.java:17` answers an out-of-scope account like one missing from the cross-reference. Lists, page numbers, and boundary messages are computed over in-scope rows only. Returning 403 or a distinct message for out-of-scope keys is a defect. See [Account scoping](../features/account-scoping.md).

### Account IDs are 11 digits with no padding

C-KEYLEN keeps the COBOL full-length rule: account numbers are typed as 11 digits, card and transaction IDs as 16 digits, and input is never left-padded (`modernization/decisions/ledger-online.md:25`, D-ACCTID and DF-9 in `modernization/decisions/README.md:27,48`). `modernization/backend/billpay/src/main/java/com/carddemo/billpay/web/BillPaymentController.java:145` is one implementation. The REST account inquiry applies the same rule (`modernization/architecture.md:155-159`).

### PF13-PF24 folding

Seven COBOL programs copy CSSTRPFY, which makes PF13-PF24 behave as PF1-PF12: COACTVWC, COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC, COTRTLIC, and COTRTUPC (for example `app/cbl/COCRDLIC.cbl`). The `Key.folded()` method in `modernization/backend/common/src/main/java/com/carddemo/common/screen/Key.java:12-16` reproduces this, and only the matching controllers call it (for example `modernization/backend/card/src/main/java/com/carddemo/card/web/CardListController.java` and `modernization/backend/refdata/src/main/java/com/carddemo/refdata/web/ListTurn.java`). The other programs do not fold, so do not add folding to a controller whose COBOL program does not copy CSSTRPFY.

## Data and security

### max+1 IDs need the counter lock

Online transaction IDs for COTRN02C and COBIL00C still follow "highest ID + 1", but are taken under a row lock on the shared `id_counter` row held until the writing transaction ends (`modernization/architecture.md:169-171`, table at `modernization/backend/schema/src/main/resources/db/migration/V1__core_schema.sql:124-129`). `modernization/backend/transaction/src/main/java/com/carddemo/transaction/internal/JdbcTransactionLedger.java:24-32` takes the counter's lock first and only then looks up the maximum. Any new writer of online transactions must go through the `TransactionLedger` API rather than computing its own maximum.

### Active-status flags are not enforced

D-INACTIVE keeps the COBOL behavior: account and card active-status flags are shown and editable but block nothing in posting, interest, bill payment, authorization, or any screen (`modernization/decisions/README.md:34`, Q-ENT-29 at `modernization/decisions/ledger-batch-data.md:59`). Adding an "inactive" check would be a behavior change that needs a new decision.

### CVVs are kept in the database

D-PII-FILES keeps CVVs in the `card` table as the COBOL does (`cvv_cd` in `modernization/backend/schema/src/main/resources/db/migration/V1__core_schema.sql:83`), and exports and unloads keep full card numbers, CVVs, and SSNs in files written mode 600 (`modernization/decisions/README.md:32`). These values must never reach a client (C-MASK) or a log (B-MASK). Treat the database and those output files as sensitive. See [Security](../security.md).

## Related pages

- [Design decisions](design-decisions.md)
- [Debugging](../how-to-contribute/debugging.md)
- [Fidelity ledger](../verification/fidelity-ledger.md)
