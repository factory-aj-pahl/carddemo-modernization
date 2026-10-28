# Batch parity datasets

Inputs for `verify_batch.py` (`make -C modernization verify-batch`). Each dataset is a directory of
ASCII sample-format files named like `app/data/ASCII`. The reference (patched GnuCOBOL, B-PARITY) and
the Java job both run on the same directory; outputs are compared by `compare.py`.

## Datasets

| Dataset | Purpose |
|:--|:--|
| sample | The shipped sample data, unchanged |

## Owed pairs

The owed set for the batch-parity check. Rows are fixed before a run; a row that cannot run is a failure.

| Program | Dataset | Directory | Rules exercised |
|:--|:--|:--|:--|
| CBTRN02C | sample | `app/data/ASCII` | TS-CBTRN02C-01, TS-CBTRN02C-02, BR-CBTRN02C-01..10, Q-CBTRN02C-08, Q-CBTRN02C-12 |
