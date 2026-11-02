# Cleanup opportunities

This page lists the cleanup work that the repository itself shows evidence for: accepted lint findings, large files, deviations the architecture says to revisit, small build and test gaps, and documentation that has drifted. Each item points at the file that shows it. Nothing here is a known bug in behavior.

## Overview

| Area | Finding | Size |
|:--|:--|:--|
| Debt markers | No untracked `TODO`, `FIXME`, or `HACK` in code | 0 |
| Root lint baseline | Accepted findings in the COBOL under `app/` | 51 findings, 6 rules |
| Architecture deviations | Items `modernization/architecture.md` marks as provisional or unbuilt | 3 |
| Module build and tests | Dependency, boundary-test, and test-source gaps | 4 |
| Documentation drift | Stale or conflicting statements | 2 |

## Debt markers

A search for `TODO`, `FIXME`, and `HACK` across the tracked tree (excluding `.git`) finds 12 lines, and all of them are the CD060 rule itself, its tests, and its documentation: `tools/carddemo/rules.py:26-27,173,183`, `tests/unit/test_rules.py:112-115`, `docs/static-analysis.md:38`, `AGENTS.md:81-82`, and `.factory/skills/carddemo-cobol-change/SKILL.md:26`. There are no debt markers in the COBOL, Java, TypeScript, or Python sources. Rule CD060 keeps it that way for COBOL (`docs/static-analysis.md:38`).

## Root lint baseline

`tools/carddemo/lint-baseline.json` holds 51 findings accepted for the original COBOL. `make lint` fails only on findings not in this file, and metric findings cannot grow past their recorded value (`AGENTS.md`, "Making changes"). Rules are described in `docs/static-analysis.md:26-39`.

| Rule | Meaning | Accepted | Files |
|:--|:--|--:|:--|
| CD050 | Duplicated code, 30 or more normalized lines | 17 | COACCT01/CODATE01, COACTUPC/COACTVWC, COACTVWC/COCRDSLC, DBUNLDGS/PAUDBUNL, CBACT04C, COTRTUPC, `app/cpy/CSLKPCDY.cpy` |
| CD020 | Paragraph cyclomatic complexity over 25 | 16 | COACTUPC (4), COTRTLIC (2), COTRTUPC (2), COCRDUPC (2), COCRDLIC, CORPT00C, COTRN00C, COTRN02C, COUSR00C, `app/cpy/CSSTRPFY.cpy` |
| CD070 | DISPLAY of a PII field or a record containing one | 10 | CBTRN01C (3), CBACT03C (2), CBCUS01C (2), CBTRN03C (2), CBACT02C (1) |
| CD030 | Unreachable paragraph | 6 | `*-EXIT` paragraphs in CBPAUP0C, COPAUA0C (2), DBUNLDGS, PAUDBLOD, PAUDBUNL |
| CD010 | Invalid member name | 1 | `app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl` (9 characters) |
| CD031 | Unused copybook | 1 | `app/cpy/UNUSED1Y.cpy` |

### Complexity hotspots

The CD020 values recorded in the baseline give the complexity ranking without running `make complexity`:

| Paragraph | File | Complexity |
|:--|:--|--:|
| 3300-SETUP-SCREEN-ATTRS | `app/cbl/COACTUPC.cbl` | 248 |
| 1100-RECEIVE-MAP | `app/cbl/COACTUPC.cbl` | 93 |
| 0000-MAIN | `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` | 69 |
| VALIDATE-INPUT-DATA-FIELDS | `app/cbl/COTRN02C.cbl` | 46 |
| 3300-SETUP-SCREEN-ATTRS | `app/cbl/COCRDUPC.cbl` | 45 |
| 3300-SETUP-SCREEN-ATTRS | `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` | 43 |
| 0000-MAIN | `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` | 41 |
| 9700-CHECK-CHANGE-IN-REC | `app/cbl/COACTUPC.cbl` | 36 |

The threshold is 25 (`docs/static-analysis.md:30`). COACTUPC dominates, which matches it being the largest program.

### How the modernization relates to these findings

Several accepted findings were already handled in the rebuild without changing `app/`:

- The 10 CD070 findings are in the batch listing and posting programs. The target masks card numbers, CVVs, and SSNs in every job log (B-MASK, `modernization/decisions/ledger-batch-data.md:23`), and the parity harness applies masking reference patches such as RP-CBACT02C-1 and RP-CBTRN01C-2 to scratch copies only.
- `app/cpy/UNUSED1Y.cpy` (CD031) is ledger row Q-ENT-18, NOT-PORTED.
- The unreachable `*-EXIT` paragraphs (CD030) have no observable behavior and fall under the NOT-PORTED policy for dead code (`modernization/decisions/README.md:77`).

Fixing them in `app/` itself would change the reference the parity harness compiles, so any such change has to be checked against [Batch parity](verification/batch-parity.md) first.

## Largest files

Line counts from `git ls-files | xargs wc -l`:

| File | Lines | Note |
|:--|--:|:--|
| `app/cbl/COACTUPC.cbl` | 4236 | Largest COBOL program; CD021 limit is 5000 lines (`docs/static-analysis.md:31`) |
| `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` | 2098 | |
| `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` | 1702 | |
| `app/cbl/COCRDUPC.cbl` | 1560 | |
| `modernization/e2e/tests/screen-walks.ts` | 1171 | Largest TypeScript file |
| `modernization/backend/refdata/src/main/java/com/carddemo/refdata/web/ListTurn.java` | 709 | Largest main Java file (COTRTLIC turn logic) |
| `modernization/backend/batch/src/main/java/com/carddemo/batch/BatchCommandLine.java` | 593 | Every batch command in one class |
| `modernization/backend/account/src/main/java/com/carddemo/account/web/UpdateEdits.java` | 450 | COACTUPC field edits |
| `modernization/verification/parity/refrun.py` | 545 | Largest Python file in `modernization/` |
| `modernization/web/src/screens/AccountUpdate.tsx` | 252 | Largest web source file |

`BatchCommandLine.java` is the most obvious split candidate: it dispatches every batch command, including WAITSTEP (the COBSWAIT port), in one class. `ListTurn.java` follows the structure of COTRTLIC's main paragraph, which is the third most complex paragraph in the baseline.

## Documented deviations to revisit

`modernization/architecture.md` section 3 names three items as provisional:

- **Account existence check.** "The user screens check that linked accounts exist by reading the `account` table directly, since the Account context publishes no existence check yet. Move this to `account/api` when it does." (`modernization/architecture.md:96-97`). The query is in `modernization/backend/iam/src/main/java/com/carddemo/iam/internal/UserAdminRepository.java:121`. Adding an existence check to the account API would let IAM drop this direct read of another context's table.
- **`/api/users` and `/api/cards`.** "Only the screen endpoints exist for these contexts; the `/api/users` and `/api/cards` sketches are not built." (`modernization/architecture.md:101-102`). The context table at `modernization/architecture.md:73-75` still lists them as API sketches.
- **`ObjectProvider` lookups in Card.** Card components resolve `AccountScope` and `AuditLog` per call so that contexts depending on Card without IAM still start (`modernization/architecture.md:98-100`). This is a workaround for a dependency shape, not a design goal.

## Module build and test gaps

These were reported by other page authors and checked against the code:

- **`iam` declared at compile scope where only runtime is needed.** `modernization/backend/account/pom.xml:25-30` and `modernization/backend/transaction/pom.xml:30-35` depend on `iam` at the default (compile) scope, while no class under their `src/main` imports `com.carddemo.iam`. The POM comments say this is deliberate ("Runtime provider of AccountScope and AuditLog; code uses only the common interfaces"). Declaring it `<scope>runtime</scope>` would keep the provider and stop code from importing IAM by accident. By comparison, `modernization/backend/authorization/pom.xml:39-44` declares `iam` at test scope.
- **The boundary test does not cover the runnables.** `modernization/backend/app/src/test/java/com/carddemo/app/ModuleBoundariesTest.java` checks only the 11 context packages listed in `CONTEXTS`. Classes in `com.carddemo.batch`, `com.carddemo.app`, and the bill-payment service are not checked, and `modernization/backend/batch/src/main/java/com/carddemo/batch/BatchApplication.java:3-4` imports `com.carddemo.iam.internal.JdbcAccountScope` and `JdbcUserRoles` directly. This is wiring rather than business use, but it is outside the rule the test claims to enforce.
- **Shared modules without tests of their own.** `common` (20 main Java files), `migration` (9), and `test-support` (7) have no `src/test` sources, and `schema` holds only SQL. Their code is exercised through other modules' tests, but codecs such as `modernization/backend/common/src/main/java/com/carddemo/common/codec/Overpunch.java` and `Packed.java` have no direct unit tests.
- **Cross-module coupling through `migration`.** `modernization/backend/dataexchange/pom.xml` and `modernization/backend/authorization/pom.xml` depend on `migration` and use its record layouts, so the sample-data loader module is also a runtime dependency of two contexts.

`billpay` and `inquiry` publish no `api` package. That is consistent with nothing depending on them and is not a gap.

## Documentation drift

- **Stale `combtran` job in the context table.** `modernization/architecture.md:79` lists COMBTRAN among the batch cycle programs and a Spring Batch job `combtran`. B-CYCLE says COMBTRAN has no target step (`modernization/decisions/ledger-batch-data.md:24`) and the DAG docstring says "there is no COMBTRAN step" (`modernization/airflow/dags/carddemo_daily_cycle.py:30-31`). The same row also lists `tranrept`, which runs on request and is not part of the cycle.
- **43 versus 38 rejects.** `AGENTS.md:114`, `docs/runbooks/batch-operations.md:40`, and `tests/integration/test_batch_jobs.py:64-67` state 43 rejects for the sample POSTTRAN run. Ledger row Q-CBTRN02C-08 says this is an ASCII-sign artifact of the default GnuCOBOL build and "should read 38" (`modernization/decisions/ledger-batch-data.md:222`). The root tooling still compiles without `-fsign=EBCDIC`, so updating the text alone would make it disagree with `make run-batch`; the build flag and the documents have to change together. See [Pitfalls](background/pitfalls.md).

## Dependency pins

Versions as pinned in the repository. Nothing in the repository says any of them is outdated, so this is a record for future upgrades, not a list of problems.

| Component | Pin | Source |
|:--|:--|:--|
| Spring Boot parent | 3.5.16 | `modernization/backend/pom.xml:8` |
| Java | 21 | `modernization/backend/pom.xml:39` |
| springdoc-openapi | 2.8.17 | `modernization/backend/pom.xml:41` |
| ArchUnit | 1.4.1 | `modernization/backend/pom.xml:42` |
| React, react-dom | ^19.3.1 | `modernization/web/package.json` |
| react-router | ^8.5.1 | `modernization/web/package.json` |
| Vite | ^8.3.5 | `modernization/web/package.json` |
| TypeScript | ^7.0.2 | `modernization/web/package.json` |
| Playwright | ^1.64.1 | `modernization/e2e/package.json` |
| Root Python dev tools | 26 exact pins | `requirements-dev.txt` |

The web and e2e dependencies use caret ranges, so the exact installed versions come from the lock files (`modernization/web/package-lock.json`, `modernization/e2e/package-lock.json`). The Airflow version is not pinned in the repository; it is installed into `~/.carddemo/airflow-venv` (`modernization/Makefile:11`). See [Dependencies](reference/dependencies.md).

## Related pages

- [Design decisions](background/design-decisions.md)
- [Modules](modules/index.md)
- [Fidelity ledger](verification/fidelity-ledger.md)
- [Local tooling](mainframe/local-tooling.md)
