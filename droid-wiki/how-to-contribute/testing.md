# Testing

CardDemo has five kinds of automated tests: the root pytest suite for the COBOL tooling, JUnit tests for every backend module, TypeScript type checks of the web app, a Playwright end-to-end suite, and unit tests for the Airflow DAGs. On top of these, the verification harnesses compare the Java batch jobs with the original COBOL. Only the root suite runs in CI.

## Overview

| Suite | Location | Command | Files | Tests | Needs |
|:--|:--|:--|--:|--:|:--|
| Root pytest | `tests/unit`, `tests/integration` | `make test`, `make test-unit`, `make coverage` | 9 | 45 | Python; GnuCOBOL for `cobc` tests |
| Backend JUnit | `modernization/backend/*/src/test/java` | `make -C modernization test-backend` | 97 Java files (78 test classes) | 817 `@Test` methods | JDK 21, Maven, SQL Server, RabbitMQ |
| Web type check | `modernization/web`, `modernization/e2e` | `make -C modernization test-web` | n/a | n/a | Node |
| End-to-end | `modernization/e2e/tests` | `make -C modernization test-e2e` | 11 spec files | about 118 `test(` calls | Built jars, SQL Server, Chromium |
| Airflow | `modernization/airflow/tests` | `make -C modernization test-airflow` | 3 | 23 | Airflow venv |
| Verification harnesses | `modernization/verification` | `make -C modernization verify-*` | see [Verification](../verification/index.md) | | GnuCOBOL, jars, database |

Counts were taken with `ls`, `grep -c "@Test"`, and `grep "def test_"` on commit 467e0f0; the e2e count is a line count of `test(` calls and may differ slightly from what Playwright reports.

## Root pytest suite

Configured in `pyproject.toml` under `[tool.pytest.ini_options]`: test paths `tests`, files `test_*.py`, functions `test_*`, options `-ra --strict-markers --durations=10`. The Makefile runs it in parallel with `pytest -n auto` (pytest-xdist).

| Directory | Files | Scope |
|:--|:--|:--|
| `tests/unit` | `test_agentsmd.py`, `test_copybook.py`, `test_duplicates.py`, `test_findings.py`, `test_procedure.py`, `test_rules.py` | `tools/carddemo` modules on small synthetic COBOL; must not read the real `app/` tree |
| `tests/integration` | `test_batch_jobs.py`, `test_data_layouts.py`, `test_repository_checks.py` | The real sources: GnuCOBOL syntax and batch runs, record lengths vs copybooks and sample data, repository-wide checks |

### Helpers in `tests/conftest.py`

- `cobol(*lines)` builds fixed-format source: a line starting with `*` becomes a column-7 comment, any other line starts in column 8.
- `make_repo` is a fixture that writes a `{relative path: text}` dict under `tmp_path` and returns a `tools.carddemo.source.Repository` for it.

A unit test in `tests/unit/test_rules.py` uses both:

```python
repo = make_repo(
    {
        "app/cbl/GOODPGM.cbl": _program("GOODPGM"),
        "app/cbl/BADPGM.cbl": _program("BADPGM", program_id="OTHERPGM"),
        "app/cpy/TOOLONGNAME.cpy": cobol("01 A PIC X."),
        "app/jcl/lower.jcl": "//LOWER JOB\n",
    }
)
found = {(f.rule, f.path) for f in _rules(repo, rules.check_naming)}
```

### The `cobc` marker

Tests that compile or run COBOL are marked `@pytest.mark.cobc` (the whole of `tests/integration/test_batch_jobs.py` sets `pytestmark = pytest.mark.cobc`). `pytest_collection_modifyitems` in `tests/conftest.py` skips them when `cobc` (or `$COBC`) is not on the PATH, unless `CARDDEMO_REQUIRE_COBC=1`, in which case they run and fail. CI and the devcontainer set `CARDDEMO_REQUIRE_COBC=1`.

### Coverage gate

`make coverage` runs the suite with `--cov`, writes `reports/coverage.xml` and `reports/junit.xml`, and fails under 90 percent branch coverage of `tools/carddemo` (`[tool.coverage.report] fail_under = 90`). Every test writes only to its own `tmp_path`, which keeps `-n auto` safe (`AGENTS.md`).

## Backend JUnit tests

`make -C modernization test-backend` runs `mvn -B -q -f backend verify` over all 18 modules. Unit and Spring Boot tests run under surefire; only `modernization/backend/billpay-service` has failsafe integration tests.

### Test files per module

| Module | Test files | Notable tests |
|:--|--:|:--|
| `batch` | 16 | `PosttranJobTest`, `IntcalcJobTest`, `StatementJobTest`, `TranreptJobTest`, `CycleBackupTest`, `CycleFailuresTest`, `DataLoadRoundTripTest`, command-line tests per job family |
| `iam` | 10 | `SignonRulesTest`, `SignonAndMenusTest`, `MenuRulesTest`, `UserAddTest`, `UserUpdateTest`, `UserDeleteTest`, `UserListTest`, `DisabledOptionsTest` |
| `authorization` | 10 | `AuthorizationMessagingTest`, `AuthorizationFailureTest`, `PendingAuthListTest`, `PendingAuthDetailTest` |
| `transaction` | 8 | `TransactionAddScreenTest`, `TransactionTurnRulesTest` |
| `refdata` | 7 | `TranTypeMaintScreenTest`, `TranTypeMaintAbendTest` |
| `account` | 6 | `AccountViewScreenTest`, `AccountUpdateScreenTest`, `AccountUpdateTurnTest`, `AccountUpdateStateTest`, `AccountFailureTest` |
| `app` | 6 | `ModuleBoundariesTest`, `SecurityMatrixTest`, `SecurityExtrasTest`, `MenuNavigationTest`, `ApplicationSmokeTest` |
| `card` | 6 | `CardListTest`, `CardViewTest`, `CardUpdateTest`, `CardUpdateStateTest` |
| `billpay-service` | 5 | `StandaloneBillPaymentIT`, `StandaloneSecurityMatrixIT` |
| `dataexchange` | 5 | `ExportImportJobTest`, `ListingJobTest`, `AccountExtractJobTest`, `FileStatusesTest` |
| `reporting` | 4 | `ReportRequestScreenTest`, `ReportRunApiTest`, `AirflowReportLauncherTest` |
| `billpay` | 3 | `BillPaymentScreenTest`, `BillPaymentLookupTest`, `BillPaymentAtomicityTest` |
| `inquiry` | 3 | `AccountInquiryMessagingTest`, `DateInquiryMessagingTest` |
| `cycle` | 2 | `PostingServiceTest`, `TransactionVerifierCloseTest` |
| `common`, `schema`, `migration`, `test-support` | 0 | Covered through the modules that use them and through the parity harness |

### Shared fixtures (`modernization/backend/test-support`)

The module is a test-scoped dependency of every context module. Its classes are under `modernization/backend/test-support/src/main/java/com/carddemo/test/`:

| Class | Purpose |
|:--|:--|
| `TestApplication.java` | `@SpringBootApplication(scanBasePackages = "com.carddemo")`: boots whatever modules are on the test classpath |
| `TestDatabaseConfig.java` | A `FlywayMigrationStrategy` that runs `clean()` then `migrate()`, so each test context starts from an empty, migrated database |
| `SampleData.java` | `clear()` empties the base tables; `load()` loads the ASCII sample files, the 10 USRSEC users (BCrypt), and the round-robin account links (D-SEED) |
| `ScreenClient.java` | Drives `/api/screens/<PROGRAM>` through `MockMvc` like the web app: one session, CSRF token attached, JSON in and out. `signOn`, `show` (GET), `post` (POST with key and fields, expects 200), `turn` and `showRaw` (no status check) |
| `Covers.java` | The `@Covers({...})` annotation that `trace.py` reads |
| `DailyRecords.java`, `RepoPaths.java` | Daily transaction records and paths into `app/data` |

`modernization/backend/test-support/src/main/resources/config/application.properties` points tests at `CARDDEMO_TEST_DB` (default `carddemo_test`), disables the RabbitMQ listeners' auto-start, and lowers the BCrypt cost to 4 so sample users hash quickly.

### Writing a test for a screen rule

A screen test is a `@SpringBootTest(classes = TestApplication.class)` with `@AutoConfigureMockMvc`. It reloads the sample data before each test, signs on through `ScreenClient`, and asserts on the JSON envelope (`fields`, `message`, `messageColor`, `focus`, `navigate`). From `modernization/backend/billpay/src/test/java/com/carddemo/billpay/BillPaymentScreenTest.java`:

```java
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class BillPaymentScreenTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;

    ScreenClient client;

    @BeforeEach
    void load() throws Exception {
        sample.load();
        new SampleDataLoader(jdbc).loadFile(Layouts.TRANSACT, RepoPaths.asciiData().resolve("dailytran.txt"));
        client = new ScreenClient(mvc);
    }

    @Test
    @Covers({"NAV-COBIL00C-02"})
    void first_entry_is_a_blank_form() {
        client.signOn(ownerOf(1));
        JsonNode r = client.show("COBIL00C");
        assertThat(r.at("/fields/accountId").asText()).isEmpty();
        assertThat(r.at("/fields/currentBalance").asText()).isEmpty();
        assertThat(r.at("/fields/confirm").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }
}
```

(`ownerOf` is a helper in the same class that looks up the user linked to an account in `user_account`.)

Steps for a new rule:

1. Find the rule ID in `modernization/spec/programs/<PROGRAM>.md` or the ledger row for the quirk.
2. Put the test in the module that owns the program, named as a snake_case sentence of the behavior.
3. Drive the screen with `client.post("<PROGRAM>", "<KEY>", Map.of(...))` and assert the exact COBOL message text and color from the spec.
4. Check the database with `JdbcTemplate` when the rule is about what is written.
5. Tag the test with `@Covers` for the IDs the assertions verify, and nothing more.

`ScreenClient.signOn(userId)` uses the demo password from the root `README.md`, so tests never need to spell it out.

### Cross-cutting tests in the `app` module

- `modernization/backend/app/src/test/java/com/carddemo/app/ModuleBoundariesTest.java` (ArchUnit) fails if a context imports another context's `web` or `internal` package, if an `api` package holds a class, or if `common` depends on a context.
- `modernization/backend/app/src/test/java/com/carddemo/app/SecurityMatrixTest.java` reads the live OpenAPI document, requires exactly one classification (`PUBLIC`, `AUTHENTICATED`, `ADMIN_ONLY`, `ACCOUNT_SCOPED`) per operation, calls each one as anonymous, USER0001, and ADMIN001, and scans every response for sample passwords, BCrypt hashes, full card numbers, and SSNs. Results go to `modernization/backend/app/target/security-matrix.txt`. Adding an endpoint without a classification fails this test.

### Surefire and failsafe

The parent `modernization/backend/pom.xml` configures surefire with `-Xmx512m` and `redirectTestOutputToFile`, so a test's console output lands in `target/surefire-reports/<class>-output.txt` rather than the build log.

`modernization/backend/billpay-service/pom.xml` adds failsafe: in `pre-integration-test` the `spring-boot-maven-plugin` starts the packaged service alone on port 8092 against `-Dbillpay.it.db`, else `CARDDEMO_TEST_DB`, else `carddemo_test`; `StandaloneBillPaymentIT` and `StandaloneSecurityMatrixIT` call it over HTTP; `post-integration-test` stops it. The matrix report goes to `modernization/backend/billpay-service/target/billpay-security-matrix.txt`. Run only this module with `make -C modernization test-billpay`.

To run one test class, use standard Maven selection, for example `mvn -f modernization/backend -pl billpay -am test -Dtest=BillPaymentScreenTest -Dsurefire.failIfNoSpecifiedTests=false`. This is plain Maven usage, not a repository target.

## Web type check

`make -C modernization test-web` runs `npm --prefix web run typecheck` and `npm --prefix e2e run typecheck` (both `tsc -p .`). The web app has no unit test runner; its behavior is tested through the e2e suite.

## End-to-end suite

`make -C modernization test-e2e` runs `npm --prefix e2e test`. The `pretest` script seeds `CARDDEMO_E2E_DB` with `modernization/e2e/scripts/seed.mjs`, then `modernization/e2e/playwright.config.ts` starts three servers: the Airflow stand-in (`modernization/e2e/scripts/airflow-stub.mjs`), the API jar, and a fresh web build in preview mode. It runs one worker with no retries and writes JUnit to `modernization/e2e/test-results/junit.xml`, which `trace.py` reads. Rule IDs go in the test title.

Spec files in `modernization/e2e/tests/`: `account`, `cards`, `transaction`, `users`, `trantype`, `authorizations`, `reports`, `accessibility` (axe-core), `headers`, `slice`, `capture`. The smoke flow (`modernization/e2e/smoke/smoke.spec.ts`, `make -C modernization smoke`) runs against the live stack instead.

Details are on the [End-to-end suite](../verification/e2e-suite.md) page.

## Airflow tests

`make -C modernization test-airflow` runs `modernization/airflow/tests/run_tests.py` with the Airflow venv's Python. It discovers `test_carddemo_batch.py`, `test_carddemo_dags.py`, and `test_carddemo_schedules.py` with `unittest` and writes `modernization/airflow/tests/junit.xml`. Each case is named `<method> › <first docstring line>`, so the IDs at the start of the docstring are its traceability tags:

```python
def test_interest_runs_only_on_the_first_business_day_between_posting_and_statements(self):
    """Q-INTCALC-02 B-CYCLE: INTCALC only on the first business day of a month, after POSTTRAN, before CREASTMT"""
```

Pass that file to `trace.py --junit`.

## Verification harnesses

These are differential checks rather than unit tests: batch parity against GnuCOBOL (`verify-batch`), load/export round trip (`verify-data`), the Airflow cycle against the reference chain (`verify-cycle`), browser reports against CBTRN03C (`verify-report`), harness qualification (`verify-harness`), spec and ledger consistency (`verify-spec`, `verify-ledger`), and the traceability matrix (`trace`). See [Verification](../verification/index.md), [Batch parity](../verification/batch-parity.md), and [Traceability](../verification/traceability.md).

## Related pages

- [Development workflow](development-workflow.md) for which suites to run per change
- [Debugging](debugging.md) for reading test reports
- [Patterns and conventions](patterns-and-conventions.md#test-conventions)
