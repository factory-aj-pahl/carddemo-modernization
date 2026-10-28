# Batch parity datasets

Inputs for `verify_batch.py` (`make -C modernization verify-batch`). Each dataset is a directory of
ASCII sample-format files named like `app/data/ASCII` (export files are binary CVEXPORT records). The
reference (patched GnuCOBOL, B-PARITY) and the Java job both run on the same directory; outputs are
compared by `compare.py`.

Datasets under `modernization/verification/parity/generated/` are built by `make_datasets.py`
(deterministic: `python3 make_datasets.py --check` builds twice and compares the bytes); `verify_batch.py`
rebuilds them before every run. The directory is not committed.

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

## Owed pairs

The owed set for the batch-parity check. Rows are fixed before a run; a row that cannot run is a failure.

| Program | Dataset | Directory | Rules exercised |
|:--|:--|:--|:--|
| CBTRN02C | sample | `app/data/ASCII` | TS-CBTRN02C-01, TS-CBTRN02C-02, BR-CBTRN02C-01..10, Q-CBTRN02C-08, Q-CBTRN02C-12 |
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

## Not reproducible by a dataset

These rules are I/O failures of a file the target keeps in the database, or failures GnuCOBOL cannot
be made to produce from a dataset directory. Each is covered by a spec-derived Java test (class in
`modernization/backend/dataexchange/src/test/java/com/carddemo/dataexchange/internal/`).

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
