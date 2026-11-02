# Patterns and conventions

This page collects the conventions that hold across CardDemo: how COBOL is written and checked at the root, and how the modernized Java, TypeScript, and Python code restates that COBOL. Most modern conventions follow from one rule: the target behaves exactly like the COBOL unless the fidelity ledger says otherwise, and every deviation is traceable to an ID.

## Traceability IDs are part of the code

The specification (`modernization/spec/`) gives every rule, quirk, and test scenario a stable ID, and the decision ledgers (`modernization/decisions/`) give every quirk a decision. Code and tests cite these IDs.

- **Comments and Javadoc** name the rule they implement, for example `/** Q-COBIL00C-07, NEW text. */` on a constant in `modernization/backend/billpay/src/main/java/com/carddemo/billpay/web/BillPaymentController.java`, or `First entry: blank form, focus on account ID (NAV-COBIL00C-02)` in an OpenAPI `@Operation` summary.
- **Tests carry `@Covers`** (`modernization/backend/test-support/src/main/java/com/carddemo/test/Covers.java`) with the IDs whose behavior their assertions check:

  ```java
  @Test
  @Covers({"Q-COBIL00C-01", "TS-COBIL00C-11"})
  void concurrent_payments_get_distinct_ids_and_all_commit() throws Exception {
  ```

  `modernization/verification/trace.py` joins these tags with the JUnit, Playwright, and Airflow results to build `modernization/verification/traceability.md`. Tag only what the assertions verify; a tag on a test that does not check the rule hides a gap.
- **Commit messages** cite IDs too ("Correct the batch half of Q-ENT-32 and clarify Q-COPAUS0C-14").

IDs are never renumbered; new ones are appended. The ID prefixes are listed in the [Glossary](../overview/glossary.md#modernization-identifiers).

## Fidelity rules

| Rule | Where it shows up |
|:--|:--|
| User-visible text is the COBOL text, verbatim, including typos and trailing spaces (D-COSMETIC) | `modernization/backend/common/src/main/java/com/carddemo/common/msg/CommonMessages.java` keeps `"Invalid key pressed. Please see below...         "` at its 50-byte width |
| Fixed-width files keep their byte layout (B-PARITY) | `modernization/backend/common/src/main/java/com/carddemo/common/codec/FixedWidth.java`, `Overpunch.java`, `Packed.java` |
| Numbers display as their COBOL PICTURE would | `modernization/backend/common/src/main/java/com/carddemo/common/format/Pic.java` and `ReportPictures.java` |
| Numeric keys are not left-padded; an account ID must be 11 digits (D-ACCTID, C-KEYLEN, DF-9) | Screen controllers cut input to the BMS length and reject short keys with the COBOL message |
| New messages exist only where a ledger FIX needs one, and are marked "NEW text" | `TOO_LARGE` in `BillPaymentController` |
| When the COBOL and a decision disagree, the decision wins; when the decision is silent, the spec wins | `modernization/decisions/README.md` |

## Backend: modules and packages

Each bounded context is a Maven module under `modernization/backend/` with package root `com.carddemo.<context>` and three packages:

| Package | Holds | Rules |
|:--|:--|:--|
| `web` | `@RestController` classes, request and field records | One controller per CICS program, mapped at `/api/screens/<PROGRAM>` |
| `internal` | Services, JDBC repositories, job steps | Never imported by another context |
| `api` | Interfaces and records other contexts use | Only interfaces and records; enforced by ArchUnit |

`modernization/backend/app/src/test/java/com/carddemo/app/ModuleBoundariesTest.java` fails the build if a context imports another context's `web` or `internal` package, if an `api` package contains a class, or if `common` depends on any context. When a context needs something from another, add an interface to the provider's `api` package and implement it in its `internal` package (for example `card/api/CardDirectory` implemented by `card/internal/JdbcCardDirectory`).

Optional cross-context collaborators are resolved per call through `ObjectProvider` so that a deployment without the provider still starts. Card does this for `AccountScope` and `AuditLog`, which IAM implements (`modernization/architecture.md` section 3).

## Backend: screen controllers

Every screen endpoint follows the same shape (see `BillPaymentController`):

1. `GET` returns the first-entry screen, usually `ScreenResponse.show(Fields.EMPTY).withFocus(...)`.
2. `POST` takes a `Request` record with `Key key` and the input fields, cuts each field to its BMS length, and switches on the key.
3. Every branch returns a `ScreenResponse` built with the fluent helpers: `show`, `navigate`, `error` (red message), `withMessage`, `withFocus`, `withFields`.
4. Unknown keys answer `CommonMessages.INVALID_KEY`, as the COBOL does.

Fields are Java records nested in the controller (`Fields`, `Request`, `Receipt`). Records annotated `@JsonInclude(NON_NULL)` leave out absent panels. The signed-on user comes from `CurrentUser.require()` (`modernization/backend/common/src/main/java/com/carddemo/common/security/CurrentUser.java`), never from request data (C-ROLE).

Services report outcomes as small result records or nested exception types rather than status codes, for example `BillPaymentService.Paid`, `NothingToPay`, `NotFound`, `PaymentFailed`, and the controller maps each to the COBOL message.

## Backend: data access and transactions

- Plain JDBC through Spring's `JdbcTemplate` and `JdbcClient`. There is no JPA or ORM anywhere in the backend; SQL is written by hand so locking and ordering match the COBOL access pattern.
- Transactions use `@Transactional` or `TransactionTemplate`. Read-for-update in COBOL becomes a row lock (`UPDLOCK`) inside one transaction.
- Optimistic concurrency uses a `rowversion` column on rows edited online. User update uses a SHA-256 fingerprint of names, type, hash, and account links instead, because the links live in another table.
- "Highest ID + 1" transaction IDs are taken under a row lock on a shared counter (Q-COTRN02C-01, Q-COBIL00C-01).
- Batch writes commit once per daily record (B-UOW), so a failed run can resume after the last committed record.

## Account scope and masking

Any read or write of account-owned data checks `AccountScope.canAccess(user, accountId)` (`modernization/backend/common/src/main/java/com/carddemo/common/security/AccountScope.java`). An out-of-scope key must be answered exactly like a missing key, with the same message, so a regular user cannot probe for other accounts. Card numbers, SSNs, and CVVs never leave the server unmasked (C-MASK); use `modernization/backend/common/src/main/java/com/carddemo/common/mask/Masking.java`. Batch SYSOUT follows the same rule (B-MASK). See [Account scoping](../features/account-scoping.md) and [Security](../security.md).

## Batch conventions

- Jobs are commands of one jar: `java -jar carddemo-batch.jar <COMMAND> key=value ...`. Arguments are `key=value` pairs, not flags.
- Exit codes are fixed (`modernization/backend/batch/src/main/java/com/carddemo/batch/ExitCodes.java`): 0 ok, 4 warnings, 8 refused, 12 invalid business date, 16 usage, 99 abend.
- Standard output carries only the job's SYSOUT text; application logs go to a file (`logging.threshold.console=OFF` in `modernization/backend/batch/src/main/resources/application.properties`). That keeps SYSOUT comparable with GnuCOBOL output.
- File outputs are written in the COBOL byte layout so the parity comparator can diff them.

## Web app conventions

- One component per CICS program in `modernization/web/src/screens/`, registered in `modernization/web/src/App.tsx` and described (transaction ID, title, F-key legend) in `modernization/web/src/screens.ts`.
- Screens use the `useScreen` hook (`modernization/web/src/useScreen.ts`), which sends first entry, posts each key as a turn, follows `navigate`, and moves focus to the field the server names. It holds keys until the first-entry screen has arrived, like a 3270 keyboard lock.
- The client never decides what a key means. It only maps legend keys to attention identifiers (`aid()` in `screens.ts`).
- `modernization/web/src/api.ts` sends the `XSRF-TOKEN` cookie value as `X-XSRF-TOKEN` on every POST and treats 401 as "show sign-on".
- A field is marked in error only when the server puts focus on it with a red message about its value (`fieldInError` in `useScreen.ts`).

## COBOL conventions (root)

The root `AGENTS.md` is the authority. In short:

- Fixed format: sequence area columns 1-6, indicator column 7, code 8-72. No tab characters (CD001) and nothing past column 80 (CD002, per `tools/carddemo/rules.py`; the root `AGENTS.md` lists the two IDs the other way round). Keep the file's existing line endings.
- Member names are 1-8 upper-case characters and match `PROGRAM-ID` (CD010, CD011). Online programs are `CO*C`, batch `CB*C`, record copybooks usually end in `Y`.
- Base programs never COPY from an optional module, and modules never COPY from each other (CD040).
- Debt markers link an issue: `TODO(#123): ...` (CD060).
- Never DISPLAY card numbers, CVVs, SSNs, or other PII (CD070).
- Changing a record copybook means updating `app/data` and the README dataset table.

Rule details are in `docs/static-analysis.md`; the rules are implemented in `tools/carddemo/rules.py`. See [Local tooling](../mainframe/local-tooling.md).

## Test conventions

- Java test methods are snake_case sentences that state the behavior, for example `failed_balance_update_also_removes_the_inserted_payment`. Root Python tests follow `test_<behaviour>`.
- Suites reset and reload their own database (`CARDDEMO_TEST_DB`, `CARDDEMO_E2E_DB`), never the stack's `carddemo`.
- Expected values come from the spec or from a GnuCOBOL reference run, not from what the Java currently outputs.

See [Testing](testing.md).

## Comments

Comments explain why, cite the rule ID, or record a deviation. They do not narrate the code. Look at `modernization/backend/pom.xml` (the `forceCreation` note on `maven-jar-plugin`) or the `inFlight` comment in `useScreen.ts` for the house style.

## Related pages

- [Architecture](../overview/architecture.md)
- [Modules](../modules/index.md)
- [Fidelity ledger](../verification/fidelity-ledger.md)
- [Tooling](tooling.md)
