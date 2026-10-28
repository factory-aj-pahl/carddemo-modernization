# Batch parity harness

Differential testing of the Java batch jobs against the original COBOL compiled with GnuCOBOL
(B-PARITY in `modernization/decisions/ledger-batch-data.md`). All commands run from the repository root;
database steps need the secrets (`set -a; . ~/.carddemo/secrets.env; set +a`) and `cobc` 3.x.

| File | Role |
|:--|:--|
| `refrun.py` | Reference runner: compiles `app/cbl/<PROGRAM>.cbl` (or a patched copy under a temporary directory) with `cobc -x -std=ibm -fsign=EBCDIC -I app/cpy`, loads a dataset into GnuCOBOL sequential and indexed files, runs the program, and writes `sysout.txt`, `rc.txt`, and one LF-terminated file per output or updated dataset (binary CVEXPORT files stay unterminated 500-byte records) |
| `javarun.py` | Java runner: resets the database named by `CARDDEMO_DB_PARITY`, loads the same dataset with the batch jar's `load`, runs the job, writes the same layout, and exports the updated tables |
| `compare.py` | Comparator: every file of both directories, record by record; reports missing and unexpected files, record counts, the first differing byte per record, and malformed run-time timestamps |
| `verify_batch.py` | Driver for `make -C modernization verify-batch`: rebuilds the generated datasets, then runs every owed pair in `datasets.md` and writes `results.json` |
| `make_datasets.py` | Builds the generated edge datasets under `generated/` (deterministic; `--check` builds twice and compares) |
| `qualify_harness.py` | Qualification of the harness itself (`make -C modernization verify-harness`) |
| `patches/RP-*.patch` | Reference patches from the ledger's RP- table, applied with `patch -p1` to a temporary copy, never to `app/` |
| `datasets.md` | Datasets, the owed (program, dataset) pairs with the rules each exercises, and the rules no dataset can reach with their covering Java tests |

## Running

```sh
make -C modernization verify-batch                 # all owed pairs; ONLY=CBEXPORT for one program, OUT=DIR to keep outputs
make -C modernization verify-harness               # qualification below
python3 modernization/verification/parity/refrun.py CBTRN02C --unpatched --out /tmp/ref   # one reference run
python3 modernization/verification/parity/compare.py /tmp/ref /tmp/java                   # one comparison
```

## Reference build details

- `-fsign=EBCDIC` (RP-BUILD-1): the ASCII sample data carries EBCDIC overpunch signs (`{`, `A`-`I`,
  `}`, `J`-`R`). `--sign ""` compiles with the cobc default for the qualification run.
- RP-DATA-1 (Q-ENT-02) is applied by `refrun.py` to the staged `acctdata.txt` of programs that list it
  in `data_patches`; the Java loader applies the same migration rule.
- `CEE3ABD` stub: off z/OS the Language Environment abend service does not exist and a `CALL 'CEE3ABD'`
  fails with exit code 1. `refrun.py` builds a one-line COBOL module (`MOVE 99 TO RETURN-CODE`,
  `STOP RUN`) on `COB_LIBRARY_PATH`, so a reference abend ends with exit code 99 like B-ABEND. The stub
  displays nothing; the program's own DISPLAY lines before the call are the compared evidence.
- An input marked `missing_if_absent` (the CBIMPORT export file) that is absent from the dataset leaves
  its DD pointing to no file, so OPEN returns status 35.
- Indexed outputs are unloaded in key order, which is byte order and the order the Java table export
  and `JdbcTables` use.

## Comparator normalizations

Only run-time clock values are normalized; both sides must hold a well-formed value, otherwise the
comparator reports it.

| File | Bytes or line | Pattern |
|:--|:--|:--|
| `transact.txt` | 305-330 (TRAN-PROC-TS, BR-CBTRN02C-05) | `YYYY-MM-DD-hh.mm.ss.hh0000` |
| `expfile.dat` | 2-27 of each 500-byte record (BR-CBEXPORT-01) | `YYYY-MM-DD hh:mm:ss.00` + 4 spaces |
| `errout.txt` | 1-21 (FUNCTION CURRENT-DATE, BR-CBIMPORT-07) | `YYYYMMDDhhmmsshh+hhmm` |
| `sysout.txt` | lines `CBEXPORT: Export Date:`/`Time:`, `CBIMPORT: Import Date:`/`Time:` | `YYYY-MM-DD`, `hh:mm:ss` |

Card numbers and SSNs in printed findings are masked (B-MASK applies to evidence too).

## Qualification

`qualify_harness.py` prints PASS or FAIL with evidence for each item and exits non-zero if any fails.

1. Unpatched CBTRN02C on the sample data: with `-fsign=EBCDIC`, RC 4, `TRANSACTIONS PROCESSED :000000300`,
   38 rejects all with reason `0102`; with the cobc default sign, 43 rejects (the sign handling is in effect).
2. Unpatched CBACT02C, CBACT03C, and CBCUS01C show every sample record (50 each): CBACT02C once, CBACT03C
   and CBCUS01C twice (Q-CBACT03C-01, Q-CBCUS01C-01), framed by the start and end messages.
3. Every RP- ID in `modernization/decisions/*.md`: the patch files of a program apply cleanly (no fuzz,
   no offset) and cumulatively to a temporary copy, which then passes `cobc -fsyntax-only`; RP-BUILD-1 and
   RP-DATA-1 are implemented by `refrun.py`; `git status --porcelain -- app/` is empty afterwards. An RP-
   ID without a patch file fails.
4. Comparator mutations on two reference output pairs (patched CBTRN02C on the sample, CBEXPORT on
   `export-sample`): the unmodified pair matches; a flipped byte, a dropped record, an added trailing
   record, a renamed output file, a removed table row (`acctdata.txt`), a partial binary record, a changed
   return code, and a malformed normalized timestamp are each reported, naming the file and the record.
