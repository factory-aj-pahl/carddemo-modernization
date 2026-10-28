# Batch parity datasets

Inputs for `verify_batch.py` (`make -C modernization verify-batch`). Each dataset is a directory of
ASCII sample-format files named like `app/data/ASCII`. The reference (patched GnuCOBOL, B-PARITY) and
the Java job both run on the same directory; outputs are compared by `compare.py`.

Datasets under `modernization/verification/parity/generated-cycle/` are built by `make_cycle_datasets.py`
(deterministic: `python3 make_cycle_datasets.py --check` builds twice and compares the bytes);
`verify_batch.py` rebuilds them before every run. The directory is not committed. Two extra files steer
the jobs: `parm.txt` is the INTCALC PARM (`2022071800`, business date 2022-07-18 for the Java job) and
`dateparm.txt` is the CBTRN03C DATEPARM record (the Java job gets the same range as `startDate`/`endDate`).
Transaction files carry fixed processing timestamps `YYYY-MM-DD-12.00.00.000000`.

## Datasets

| Dataset | Purpose |
|:--|:--|
| sample | The shipped sample data, unchanged |
| cycle-edge | Sample masters and the 300 daily records plus: account 1 with no group in either slot (DEFAULT fallback); account 2 group `ZEROAPR` in the ZIP slot (rate 0, moved by RP-DATA-1); account 3 with category balances -1,234.56 (01/0001), 12,345.67 (01/0002), -1,234.57 (02/0001), 0.00 (03/0001); account 4 with a second, lower card `0000000000000004`; account 5 cycle credit 100.00 and debit 50.25; account 6 cycle credit 7.77 and no category balance; an xref card `4444333322221111` for missing account 99999999999; four extra daily records: unknown card (between records 150 and 151), card of the missing account, account 1 card with original date 2026-01-01 (after expiry 2025-05-20), and a -25.00 credit (type 03, category 0001) on card `0000000000000004` |
| statement-edge | Sample masters, transactions = the 300 daily records processed 2022-06-10 plus 14 extra transactions (some negative) on the busiest card (20 in all); customer 1 without middle name, customer 2 without address line 3 and state, customer 3 `Mary Ann` `Van Dyke`, customer 4 last name only, customer 5 without country and ZIP; an extra xref card `0000000000000001` (account 1) with no transactions |
| report-sample | Sample masters, transactions = the 300 daily records processed 2022-06-10, DATEPARM `2022-06-10 2022-06-10` |
| report-edge | Sample masters, the 300 daily records processed on 2022-06-09, -10, -11, -12 in turn, DATEPARM `2022-06-10 2022-06-11` (half the records outside the range, at both ends) |
| report-none | Sample masters, every transaction processed 2022-06-12, DATEPARM `2022-06-10 2022-06-11` (nothing in range) |
| empty-input | Sample masters with zero-length `dailytran.txt`, `tcatbal.txt`, and `transact.txt` |

## Owed pairs

The owed set for the batch-parity check. Rows are fixed before a run; a row that cannot run is a failure.

| Program | Dataset | Directory | Rules exercised |
|:--|:--|:--|:--|
| CBTRN02C | sample | `app/data/ASCII` | TS-CBTRN02C-01, TS-CBTRN02C-02, BR-CBTRN02C-01..10, Q-CBTRN02C-08, Q-CBTRN02C-12 |
| CBTRN02C | cycle-edge | `modernization/verification/parity/generated-cycle/cycle-edge` | TS-CBTRN02C-04, TS-CBTRN02C-05, VR-CBTRN02C-01, VR-CBTRN02C-02, BR-CBTRN02C-03, BR-CBTRN02C-06, BR-CBTRN02C-07, BR-CBTRN02C-09, ERR-CBTRN02C-07 (INVALID KEY branches) |
| CBTRN02C | empty-input | `modernization/verification/parity/generated-cycle/empty-input` | BR-CBTRN02C-10 (zero counts, RC 0) |
| CBTRN01C | sample | `app/data/ASCII` | TS-CBTRN01C-01, BR-CBTRN01C-01, BR-CBTRN01C-02, BR-CBTRN01C-04, BR-CBTRN01C-05, Q-CBTRN01C-01, Q-CBTRN01C-05 |
| CBTRN01C | cycle-edge | `modernization/verification/parity/generated-cycle/cycle-edge` | TS-CBTRN01C-02, TS-CBTRN01C-03, BR-CBTRN01C-03, BR-CBTRN01C-04, ERR-CBTRN01C-04 (INVALID KEY branches), Q-CBTRN01C-05 |
| CBTRN01C | empty-input | `modernization/verification/parity/generated-cycle/empty-input` | TS-CBTRN01C-04, Q-CBTRN01C-01 |
| CBACT04C | sample | `app/data/ASCII` | TS-CBACT04C-01, TS-CBACT04C-02, TS-CBACT04C-03, BR-CBACT04C-01, BR-CBACT04C-04..09, Q-CBACT04C-01, Q-CBACT04C-05, Q-CBACT04C-06, Q-CBACT04C-09, Q-INTCALC-01 |
| CBACT04C | cycle-edge | `modernization/verification/parity/generated-cycle/cycle-edge` | TS-CBACT04C-04, TS-CBACT04C-05, TS-CBACT04C-06, BR-CBACT04C-01..08, Q-CBACT04C-03, Q-CBACT04C-05, Q-CBACT04C-07, Q-CBACT04C-11 |
| CBACT04C | empty-input | `modernization/verification/parity/generated-cycle/empty-input` | TS-CBACT04C-09, BR-CBACT04C-01, Q-CBACT04C-01 |
| CBTRN03C | report-sample | `modernization/verification/parity/generated-cycle/report-sample` | TS-CBTRN03C-01, TS-CBTRN03C-02, TS-CBTRN03C-03, TS-CBTRN03C-04, BR-CBTRN03C-01..10, Q-CBTRN03C-01, Q-CBTRN03C-02, Q-CBTRN03C-03, Q-CBTRN03C-04, Q-CBTRN03C-05, Q-CBTRN03C-08, Q-CBTRN03C-09 |
| CBTRN03C | report-edge | `modernization/verification/parity/generated-cycle/report-edge` | TS-CBTRN03C-05, BR-CBTRN03C-01, BR-CBTRN03C-03, BR-CBTRN03C-07, BR-CBTRN03C-08, BR-CBTRN03C-09, Q-CBTRN03C-01, Q-CBTRN03C-06, Q-TRANREPT-03 |
| CBTRN03C | report-none | `modernization/verification/parity/generated-cycle/report-none` | TS-CBTRN03C-08, BR-CBTRN03C-01 (nothing in range) |
| CBTRN03C | empty-input | `modernization/verification/parity/generated-cycle/empty-input` | TS-CBTRN03C-08, BR-CBTRN03C-01 (empty transaction table) |
| CBSTM03A+CBSTM03B | report-sample | `modernization/verification/parity/generated-cycle/report-sample` | TS-CBSTM03A-01, TS-CBSTM03A-02, TS-CBSTM03A-03, TS-CBSTM03A-04, TS-CBSTM03B-01, TS-CBSTM03B-02, TS-CBSTM03B-03, BR-CBSTM03A-02..12, BR-CBSTM03B-02..05, Q-CBSTM03A-01, Q-CBSTM03A-07 |
| CBSTM03A+CBSTM03B | statement-edge | `modernization/verification/parity/generated-cycle/statement-edge` | TS-CBSTM03A-05, TS-CBSTM03A-10, TS-CBSTM03A-11, BR-CBSTM03A-03, BR-CBSTM03A-04, BR-CBSTM03A-06, BR-CBSTM03A-08, BR-CBSTM03A-09, BR-CBSTM03A-10, BR-CBSTM03A-12, Q-CBSTM03A-04, Q-CBSTM03A-06, Q-CBSTM03A-07 |
| CBSTM03A+CBSTM03B | empty-input | `modernization/verification/parity/generated-cycle/empty-input` | TS-CBSTM03A-06, Q-CBSTM03A-03, BR-CBSTM03A-10 (statements without transaction lines) |

## Not reproducible by a dataset

These rules are file I/O failures of files the target keeps in the database, or paths that end in an
abend (CEE3ABD, B-ABEND), where the reference has no Language Environment and the target's exit
code (99) and partial-output rules (B-UOW) differ by design. Each is covered by a spec-derived Java
test in `modernization/backend/batch/src/test/java/com/carddemo/batch/`.

| Rule | Why no dataset | Covering test |
|:--|:--|:--|
| ERR-CBTRN01C-01, ERR-CBTRN01C-02, ERR-CBTRN01C-03, ERR-CBTRN01C-05, TS-CBTRN01C-05, Q-CBTRN01C-02, Q-CBTRN01C-03 | Open, read, close failures and non-23 lookup failures of tables; abend | `TranverifyJobTest` |
| ERR-CBTRN02C-01..09 except ERR-CBTRN02C-07, TS-CBTRN02C-09, TS-CBTRN02C-10 | File failures and the duplicate TRAN-ID abend | `PosttranJobTest`, `PostingServiceTest` |
| ERR-CBACT04C-01..09, TS-CBACT04C-07, TS-CBACT04C-08, Q-CBACT04C-08 | Missing account or card, missing DEFAULT rate, write and file failures: abend; month refusal and restart are target behaviour | `IntcalcJobTest` |
| ERR-CBTRN03C-01..07, TS-CBTRN03C-06, TS-CBTRN03C-07, Q-CBTRN03C-07, Q-CBTRN03C-10 | Lookup failures and file failures abend; a missing date range is refused before the job (exit 12) | `TranreptJobTest` |
| ERR-CBSTM03A-01..09, TS-CBSTM03A-07, TS-CBSTM03A-08, TS-CBSTM03A-09, Q-CBSTM03A-09, Q-CBSTM03A-10 | Missing customer or account, file failures, picture overflow abend; HTML escaping is a FIX with no reference patch | `StatementJobTest` |
| BR-CBSTM03A-01, TS-CBSTM03A-12 | z/OS control-block walk, removed by RP-CBSTM03A-1 (Q-CBSTM03A-01: run location goes to the job log) | `StatementJobTest` |
| BR-CBSTM03B-01, ERR-CBSTM03B-01, TS-CBSTM03B-04, TS-CBSTM03B-05, TS-CBSTM03B-06 | DD-name dispatch and unsupported operations do not exist in the target (Q-CBSTM03B-01..04, PLATFORM) | `StatementJobTest` |
