# Batch parity datasets

Inputs for `verify_batch.py` (`make -C modernization verify-batch`). Each dataset is a directory of
ASCII sample-format files named like `app/data/ASCII` (export files are binary CVEXPORT records). The
reference (patched GnuCOBOL, B-PARITY) and the Java job both run on the same directory; outputs are
compared by `compare.py`.

Datasets under `modernization/verification/parity/generated/` are built by `make_datasets.py`
(deterministic: `python3 make_datasets.py --check` builds twice and compares the bytes); `verify_batch.py`
rebuilds them before every run. The directory is not committed.

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
| empty | Zero-length customer, account, xref, card, transaction, and disclosure-group files |
| listing-edge | Sample customers, xrefs, and cards in reverse key order (file order differs from key order); customer 1 FICO `850`, customer 2 FICO `300` |
| export-sample | Sample masters plus `transact.txt` = the 300 daily transactions, and `discgrp.txt` for Q-ENT-02 (RP-DATA-1) |
| export-edge | `export-sample` with every file in reverse key order and limit values: account 1 balance -194.00 and cycle debit -1234.51; account 2 balance 999,999,999.99, cycle credit 0.07, cycle debit 9,999,999,999.99; account 3 credit limit -1.00 and cash limit -999,999,999.99; transaction 1 (lowest ID) amount -504.77, transaction 2 amount 999,999,999.99, transaction 3 amount 0, transaction 4 merchant ID 999999999, transaction 5 merchant ID 0; customer FICO 850 and 300 |
| export-notran | `export-sample` with an empty `transact.txt` |
| import-sample | `expfile.dat`: the 500-record export of `export-sample` (after RP-DATA-1), sequence 1-500, encoded from BR-CBEXPORT-03..08 by `make_datasets.py` (byte-identical to the GnuCOBOL CBEXPORT output except the timestamp) |
| import-edge | `expfile.dat`: the export of `export-edge`, plus customer 999999999 with FICO 000 (seq 600), xref account 99999999999 / customer 999999999 (seq 601), card account 99999999999 CVV 000 (seq 602), card CVV 999 (seq 603), an `X` record with sequence 0, and unknown types `Z` (seq 123456789), space (seq 700), `c` (seq 999999999); 508 records stored out of sequence order |
| import-empty | Zero-length `expfile.dat` |
| import-missing | No `expfile.dat` (the EXPFILE DD names a missing file) |
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
| CBTRN02C | sample | `app/data/ASCII` | TS-CBTRN02C-01, TS-CBTRN02C-02, BR-CBTRN02C-01..10, Q-CBTRN02C-08, Q-CBTRN02C-09, Q-CBTRN02C-12 |
| CBACT02C | sample | `app/data/ASCII` | TS-CBACT02C-01, TS-CBACT02C-05, BR-CBACT02C-01, BR-CBACT02C-02, BR-CBACT02C-03, Q-CBACT02C-01, Q-READCARD-01 |
| CBACT02C | empty | `modernization/verification/parity/generated/empty` | TS-CBACT02C-02, BR-CBACT02C-02, BR-CBACT02C-03 |
| CBACT02C | listing-edge | `modernization/verification/parity/generated/listing-edge` | BR-CBACT02C-01 (key order from a reverse-ordered file) |
| CBACT03C | sample | `app/data/ASCII` | TS-CBACT03C-01, BR-CBACT03C-01, BR-CBACT03C-02, BR-CBACT03C-03, Q-CBACT03C-01, Q-CBACT03C-02, Q-READCARD-01 |
| CBACT03C | empty | `modernization/verification/parity/generated/empty` | TS-CBACT03C-02, BR-CBACT03C-02, BR-CBACT03C-03 |
| CBACT03C | listing-edge | `modernization/verification/parity/generated/listing-edge` | BR-CBACT03C-01 (key order from a reverse-ordered file) |
| CBCUS01C | sample | `app/data/ASCII` | TS-CBCUS01C-01, BR-CBCUS01C-01, BR-CBCUS01C-02, BR-CBCUS01C-03, Q-CBCUS01C-01, Q-CBCUS01C-02, Q-READCARD-01 |
| CBCUS01C | empty | `modernization/verification/parity/generated/empty` | TS-CBCUS01C-02, BR-CBCUS01C-02, BR-CBCUS01C-03 |
| CBCUS01C | listing-edge | `modernization/verification/parity/generated/listing-edge` | BR-CBCUS01C-01 (key order from a reverse-ordered file, FICO 850 and 300) |
| CBEXPORT | export-sample | `modernization/verification/parity/generated/export-sample` | TS-CBEXPORT-01, TS-CBEXPORT-02, TS-CBEXPORT-03, TS-CBEXPORT-04, TS-CBEXPORT-05, BR-CBEXPORT-01..09, Q-CBEXPORT-02, Q-CBEXPORT-03, Q-CBEXPORT-04, Q-CBEXPORT-06, Q-CBEXPORT-07, Q-CBACT01C-07 |
| CBEXPORT | export-edge | `modernization/verification/parity/generated/export-edge` | BR-CBEXPORT-02 (key order from reverse-ordered files), BR-CBEXPORT-04 (FICO 850, 300), BR-CBEXPORT-05 (negative and maximum packed, zoned, and binary amounts), BR-CBEXPORT-07 (negative, maximum, and zero packed amounts; merchant ID 0 and 999999999) |
| CBEXPORT | export-notran | `modernization/verification/parity/generated/export-notran` | TS-CBEXPORT-06, BR-CBEXPORT-02, BR-CBEXPORT-09 |
| CBEXPORT | empty | `modernization/verification/parity/generated/empty` | BR-CBEXPORT-02, BR-CBEXPORT-09 (all counts zero, empty export file) |
| CBIMPORT | import-sample | `modernization/verification/parity/generated/import-sample` | TS-CBIMPORT-01, TS-CBIMPORT-02, TS-CBEXPORT-09, BR-CBIMPORT-01..06, BR-CBIMPORT-08, BR-CBIMPORT-09, BR-CBIMPORT-10, Q-CBIMPORT-01, Q-CBIMPORT-03 |
| CBIMPORT | import-edge | `modernization/verification/parity/generated/import-edge` | TS-CBIMPORT-03, TS-CBIMPORT-04, BR-CBIMPORT-01 (stored order differs from key order; sequence 0 first), BR-CBIMPORT-02, BR-CBIMPORT-03..06 (signed and limit values), BR-CBIMPORT-07 (types `Z`, space, `c`), BR-CBIMPORT-08, Q-CBIMPORT-02, Q-CBIMPORT-04, Q-CBIMPORT-06 |
| CBIMPORT | import-empty | `modernization/verification/parity/generated/import-empty` | TS-CBIMPORT-05, BR-CBIMPORT-01, BR-CBIMPORT-09 |
| CBIMPORT | import-missing | `modernization/verification/parity/generated/import-missing` | TS-CBIMPORT-06, ERR-CBIMPORT-01 (EXPORT-INPUT), ERR-CBIMPORT-05, B-ABEND |
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

These rules are I/O failures of files the target keeps in the database, failures GnuCOBOL cannot
be made to produce from a dataset directory, or paths that end in an abend (CEE3ABD, B-ABEND), where
the target's exit code (99) and partial-output rules (B-UOW) differ by design. Each is covered by a
spec-derived test: for the listing and data exchange jobs a Java test in
`modernization/backend/dataexchange/src/test/java/com/carddemo/dataexchange/internal/`; for the cycle
jobs failures are injected by renaming a table or refusing its writes in `CycleFailuresTest`, or, where
the target has no counterpart (no open or close of a table, no DD names), the rule is resolved in
`modernization/verification/trace-exclusions.md`.

| Rule | Why no dataset | Covering test |
|:--|:--|:--|
| ERR-CBACT02C-01, ERR-CBACT02C-04 | CARDFILE is the card table; it cannot be missing | `ListingJobTest.card_file_open_failure_abends_with_status_0035` |
| ERR-CBACT02C-02, ERR-CBACT02C-04 | Read I/O error | `ListingJobTest.card_read_failure_93_abends_with_the_ebcdic_status_value` |
| ERR-CBACT02C-03 | Close I/O error | `ListingJobTest.card_close_failure_abends_after_the_listing` |
| ERR-CBACT03C-01, ERR-CBACT03C-04 | XREFFILE is the xref table | `ListingJobTest.xref_open_failure_abends_with_status_0035` |
| ERR-CBACT03C-02 | Read I/O error | `ListingJobTest.xref_read_failure_30_abends` |
| ERR-CBACT03C-03 | Close I/O error | `ListingJobTest.xref_close_failure_abends` |
| ERR-CBCUS01C-01, ERR-CBCUS01C-04 | CUSTFILE is the customer table | `ListingJobTest.customer_open_failure_abends_with_status_0035` |
| ERR-CBCUS01C-02 | Read I/O error | `ListingJobTest.customer_read_failure_30_abends` |
| ERR-CBCUS01C-03 | Close I/O error | `ListingJobTest.customer_close_failure_abends` |
| ERR-CBEXPORT-01, ERR-CBEXPORT-04 | The five inputs are tables; EXPORT-OUTPUT allocation failure | `ExportImportJobTest.missing_customer_input_abends_with_status_35`, `ExportImportJobTest.open_failures_of_later_inputs_and_of_the_export_file_name_the_file` |
| ERR-CBEXPORT-02 | Read I/O error | `ExportImportJobTest.read_failure_abends_naming_the_input` |
| ERR-CBEXPORT-03 | Write failure (duplicate key needs a pre-existing KSDS, out of space) | `ExportImportJobTest.export_write_failure_abends_and_counts_only_written_records` |
| ERR-CBEXPORT-05 | Close failure | `ExportImportJobTest.input_close_failures_are_ignored` |
| ERR-CBIMPORT-01 (outputs) | Output allocation failure | `ExportImportJobTest.output_open_failure_names_the_output_and_deletes_the_ones_created` |
| ERR-CBIMPORT-02 | A damaged export file cannot be loaded into the reference KSDS | `ExportImportJobTest.trailing_partial_record_abends_as_a_read_error_after_the_complete_records` |
| ERR-CBIMPORT-03 | Output out of space | `ExportImportJobTest.target_write_failure_abends_naming_the_record_type` |
| ERR-CBIMPORT-04, Q-CBIMPORT-05 | ERROUT write failure (FIX: now abends) | `ExportImportJobTest.error_file_write_failure_abends` |
| Q-CBIMPORT-07 | Undecodable numerics: the FIX has no reference patch (GnuCOBOL output is undefined) | `ExportImportJobTest.undecodable_numeric_field_goes_to_the_error_file_and_the_import_continues` |
| ERR-CBTRN01C-01, ERR-CBTRN01C-02, ERR-CBTRN01C-03, ERR-CBTRN01C-05, TS-CBTRN01C-05, Q-CBTRN01C-02, Q-CBTRN01C-03 | Open, read, close failures and non-23 lookup failures of tables; abend | `TranverifyJobTest`, `CycleFailuresTest`, `TransactionVerifierCloseTest`; TS-CBTRN01C-05 in trace-exclusions.md |
| ERR-CBTRN02C-01..09 except ERR-CBTRN02C-07, TS-CBTRN02C-09, TS-CBTRN02C-10 | File failures and the duplicate TRAN-ID abend | `PosttranJobTest`, `PostingServiceTest`, `CycleFailuresTest`, `RejectsFileTest`; TS-CBTRN02C-10 in trace-exclusions.md |
| ERR-CBACT04C-01..09, TS-CBACT04C-07, TS-CBACT04C-08, Q-CBACT04C-08 | Missing account or card, missing DEFAULT rate, write and file failures: abend; month refusal and restart are target behaviour | `IntcalcJobTest`, `CycleFailuresTest`; ERR-CBACT04C-01 and -08 in trace-exclusions.md |
| ERR-CBTRN03C-01..07, TS-CBTRN03C-06, TS-CBTRN03C-07, Q-CBTRN03C-07, Q-CBTRN03C-10 | Lookup failures and file failures abend; a missing date range is refused before the job (exit 12) | `TranreptJobTest`, `CycleFailuresTest` |
| ERR-CBSTM03A-01..09, TS-CBSTM03A-07, TS-CBSTM03A-08, TS-CBSTM03A-09, Q-CBSTM03A-09, Q-CBSTM03A-10 | Missing customer or account, file failures, picture overflow abend; HTML escaping is a FIX with no reference patch | `StatementJobTest`, `CycleFailuresTest`; ERR-CBSTM03A-01, -07 and TS-CBSTM03A-09 in trace-exclusions.md |
| BR-CBSTM03A-01, TS-CBSTM03A-12 | z/OS control-block walk, removed by RP-CBSTM03A-1 (Q-CBSTM03A-01: run location goes to the job log) | `CycleFailuresTest`; trace-exclusions.md |
| BR-CBSTM03B-01, ERR-CBSTM03B-01, TS-CBSTM03B-04, TS-CBSTM03B-05, TS-CBSTM03B-06 | DD-name dispatch and unsupported operations do not exist in the target (Q-CBSTM03B-01..04, PLATFORM) | `CycleFailuresTest` (ERR-CBSTM03B-01); trace-exclusions.md |
