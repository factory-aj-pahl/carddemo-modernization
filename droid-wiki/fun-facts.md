# Fun facts

A few things about CardDemo that are true, checkable, and a little surprising. Each one cites the file or commit it comes from (commit 467e0f0, data collected on 2026-10-29).

## The same job has two correct reject counts

Run the POSTTRAN program `CBTRN02C` on the sample data and you get 300 transactions processed and either 43 or 38 rejected, depending on how GnuCOBOL decodes signs.

- The root tooling compiles with GnuCOBOL defaults and expects 43. `AGENTS.md` (line 114) says a good run ends with 43 rejects, and `tests/integration/test_batch_jobs.py` (line 64) asserts `TRANSACTIONS REJECTED  :000000043`.
- The parity harness compiles with `-fsign=EBCDIC` (B-PARITY, line 25 of `modernization/decisions/ledger-batch-data.md`) and gets 38.

The ledger entry Q-CBTRN02C-08 (line 222 of the same file) explains why. The ASCII sample data stores signed amounts with EBCDIC overpunch characters, so a build that reads them with ASCII sign rules decodes some amounts differently from a mainframe. It concludes that 38 is the mainframe answer and that "The 43 in `AGENTS.md` and the runbook is an ASCII-decoding artifact and should read 38." `AGENTS.md` still says 43 at this commit. More on this in [Batch parity](verification/batch-parity.md).

## The typos are part of the contract

The fidelity ledger has a rule, D-COSMETIC, that user-visible text stays verbatim, typos included (`modernization/decisions/README.md`, line 16). So the Java card update screen still says:

- `Record changed by some one else. Please review` (from `app/cbl/COCRDUPC.cbl`, line 208, now in `modernization/backend/card/src/main/java/com/carddemo/card/web/CardUpdateController.java`, line 49)
- `Changes validated.Press F5 to save`, with no space after the period (`app/cbl/COCRDUPC.cbl`, line 167)
- `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER`, asserted in `modernization/backend/card/src/test/java/com/carddemo/card/CardListTest.java`, line 159

All of these are listed as PRESERVE under Q-COCRDUPC-18 in `modernization/decisions/ledger-online.md` (line 244). Field names got a different treatment: the copybooks spell `ACCT-EXPIRAION-DATE` and `CARD-EXPIRAION-DATE` (`app/cpy/CVACT01Y.cpy`, line 11, and `app/cpy/CVACT02Y.cpy`, line 9, both unchanged since Release 1.0 on 2022-09-01), but the SQL columns are spelled `expiration_date` (`modernization/backend/schema/src/main/resources/db/migration/V1__core_schema.sql`, line 70). Ledger entry Q-ENT-03 records that split: correct spelling in the schema, verbatim spelling on screens and in reports. One security rule overrides D-COSMETIC: the separate "User not found" and "Wrong Password" sign-on messages were merged into one, so user IDs cannot be guessed (Q-COSGN00C-04). See [Fidelity ledger](verification/fidelity-ledger.md).

## 611 quirks, all written down

The specification counts 611 quirks (Q- IDs): 506 in the program files, 42 in `modernization/spec/entities.md`, and 63 in `modernization/spec/jobs.md` (`modernization/spec/components.md`, line 32). The ledgers then give each one a decision: 621 rows in total start with a Q- ID across `modernization/decisions/ledger-online.md` and `modernization/decisions/ledger-batch-data.md`. The quirks range from security (plain-text passwords, Q-COSGN00C-01) to small things such as JCL typos like `NOTIFY=&SYUID` (Q-READCUST-01). The biggest single source is `app/cbl/COACTUPC.cbl`, the account update screen, which is also the longest file in the repository at 4,236 lines and has 31 quirks of its own (`modernization/spec/components.md`, line 56).

## Zero debt markers in 30,000 lines of COBOL

`git grep` finds no `TODO`, `FIXME`, or `HACK` markers anywhere under `app/` or `modernization/`. The only matches in the repository are in the lint rule that forbids unlinked markers (CD060, `tools/carddemo/rules.py`, lines 26-27 and 173) and in its unit test (`tests/unit/test_rules.py`, lines 112-115). The accepted lint debt lives in `tools/carddemo/lint-baseline.json` instead: 51 findings, the largest groups being CD050 (17) and CD020 (16). For what those rules mean, see [Local tooling](mainframe/local-tooling.md); for what is left to clean up, see [Cleanup opportunities](cleanup-opportunities.md).

## Names that tell you where you are

- Online programs are `CO*C` and batch programs are `CB*C` (`AGENTS.md`), and each BMS map is named after its program without the trailing `C`, so `COSGN00C` draws map `COSGN00`.
- Four-character CICS transaction IDs start the screens: `CC00` runs sign-on `COSGN00C`, and `CM00` and `CA00` are the main and admin menus (`app/csd/CARDDEMO.CSD`, lines 327-399). The web app keeps the mapping from program to transaction ID in `modernization/web/src/screens.ts`.
- The largest copybook, `app/cpy/CSLKPCDY.cpy` (1,318 lines), is not a record layout. It is a lookup table of North American phone area codes, US state codes, and state-plus-ZIP prefixes which `app/cbl/COACTUPC.cbl` copies in (line 602) to check phone numbers, states, and ZIP codes on account update.

For more on how the system grew, see [Lore](lore.md); for the numbers, see [By the numbers](by-the-numbers.md).
