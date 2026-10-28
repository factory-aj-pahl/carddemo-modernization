# CBIMPORT: Split the multi-record migration file back into customer, account, xref, transaction, and card files

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBIMPORT.cbl` (491 lines) |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | CBIMPORT, step STEP01 (`app/jcl/CBIMPORT.jcl:22-65`) |
| BMS map | none |
| Copybooks | `CVEXPORT` (EXPORT-RECORD, 500, input layout), `CVCUS01Y` (CUSTOMER-RECORD, 500), `CVACT01Y` (ACCOUNT-RECORD, 300), `CVACT03Y` (CARD-XREF-RECORD, 50), `CVTRA05Y` (TRAN-RECORD, 350), `CVACT02Y` (CARD-RECORD, 150) |
| Calls / XCTL targets | `CEE3ABD` (Language Environment abend, called with no arguments, only on error) |
| Data stores | DD `EXPFILE` = `AWS.M2.CARDDEMO.EXPORT.DATA` (KSDS, 500 bytes, key 4-byte binary sequence at offset 27): Browse; DD `CUSTOUT` = `AWS.M2.CARDDEMO.CUSTDATA.IMPORT` (sequential FB 500): Write; DD `ACCTOUT` = `AWS.M2.CARDDEMO.ACCTDATA.IMPORT` (FB 300): Write; DD `XREFOUT` = `AWS.M2.CARDDEMO.CARDXREF.IMPORT` (FB 50): Write; DD `TRNXOUT` = `AWS.M2.CARDDEMO.TRANSACT.IMPORT` (FB 350): Write; DD `CARDOUT` (FB 150): Write, but no DD statement exists in the JCL; DD `ERROUT` = `AWS.M2.CARDDEMO.IMPORT.ERRORS` (FB 132): Write |

## Purpose
CBIMPORT is the receiving side of the branch-migration pair. It reads the export file written by
CBEXPORT in sequence-number order and, according to each record's type letter, converts the record back
into the normal CardDemo layout (customer, account, card cross-reference, transaction, or card) and
writes it to a separate sequential file. Records with an unknown type go to an error file. The
"validation" step only prints fixed messages.

## Invocation and state
- JCL STEP01: EXPFILE DISP=SHR; CUSTOUT, ACCTOUT, XREFOUT, TRNXOUT, ERROUT are new cataloged datasets
  (DISP=(NEW,CATLG,DELETE)), so the job cannot be rerun until they are deleted. There is no `CARDOUT` DD
  (Q-CBIMPORT-01). No PARM, no SYSIN.
- Working-storage state: WS-IMPORT-DATE / WS-IMPORT-TIME (display only), counters 9(9) for records read,
  each imported type, error records written, and unknown types; WS-ERROR-RECORD (error line layout).
- Return code: never set; normal end RC 0. Open, read, and target-write errors call CEE3ABD.

## Processing flow
1. DISPLAY `CBIMPORT: Starting Customer Data Import`; derive date `YYYY-MM-DD` and time `HH:MM:SS` from
   FUNCTION CURRENT-DATE; open EXPFILE INPUT and the six outputs OUTPUT (ERR-CBIMPORT-01); DISPLAY
   `CBIMPORT: Import Date: ` + date and `CBIMPORT: Import Time: ` + time.
2. Read the first export record INTO EXPORT-RECORD; WHILE status is not `10`: add 1 to records read,
   dispatch on the record type (BR-CBIMPORT-02), read the next record.
3. 3000-VALIDATE-IMPORT: DISPLAY two fixed messages (BR-CBIMPORT-08).
4. Close all seven files; DISPLAY the summary (BR-CBIMPORT-09); GOBACK.

## Rules
### Business rules
- **BR-CBIMPORT-01**: Records are processed one at a time in ascending sequence-number order (KSDS key
  order), regardless of type. The header timestamp, branch ID, and region code are ignored. Source:
  `app/cbl/CBIMPORT.cbl:37-41`, `app/cbl/CBIMPORT.cbl:251-270 (2000-PROCESS-EXPORT-FILE, 2100-READ-EXPORT-RECORD)`.
- **BR-CBIMPORT-02**: EVALUATE EXPORT-REC-TYPE: `C` -> customer, `A` -> account, `X` -> cross-reference,
  `T` -> transaction, `D` -> card, any other value -> unknown-record handling (BR-CBIMPORT-07). Source:
  `app/cbl/CBIMPORT.cbl:273-288 (2200-PROCESS-RECORD-BY-TYPE)`.
- **BR-CBIMPORT-03**: Customer `C`: INITIALIZE CUSTOMER-RECORD, then move every CVEXPORT customer field to
  its CVCUS01Y field (binary customer ID to 9(9) zoned, packed FICO score to 9(3) zoned, text fields
  unchanged), WRITE to CUSTOUT, count. Source: `app/cbl/CBIMPORT.cbl:291-323 (2300-PROCESS-CUSTOMER-RECORD)`.
- **BR-CBIMPORT-04**: Account `A`: INITIALIZE ACCOUNT-RECORD, move all twelve fields (packed balance and
  cash limit, zoned credit limit and cycle credit, binary cycle debit, all to S9(10)V99 zoned with
  trailing overpunch sign), WRITE to ACCTOUT, count. Source: `app/cbl/CBIMPORT.cbl:326-352 (2400-PROCESS-ACCOUNT-RECORD)`.
- **BR-CBIMPORT-05**: Cross-reference `X`: card number, customer ID, binary account ID to 9(11) zoned;
  WRITE to XREFOUT, count. Transaction `T`: all thirteen fields (packed amount to S9(9)V99 zoned, binary
  merchant ID to 9(9) zoned); WRITE to TRNXOUT, count. Source: `app/cbl/CBIMPORT.cbl:355-372 (2500-PROCESS-XREF-RECORD)`,
  `app/cbl/CBIMPORT.cbl:375-402 (2600-PROCESS-TRAN-RECORD)`.
- **BR-CBIMPORT-06**: Card `D`: card number, binary account ID and binary CVV to zoned 9(11) and 9(3),
  embossed name, expiration date, active status; WRITE to CARDOUT, count. Source:
  `app/cbl/CBIMPORT.cbl:405-425 (2650-PROCESS-CARD-RECORD)`.
- **BR-CBIMPORT-07**: Unknown type: add 1 to the unknown count and build a 130-byte error line, written as
  a 132-byte record padded with 2 spaces: FUNCTION CURRENT-DATE (21 characters `YYYYMMDDhhmmsshh+hhmm`)
  left-justified in 26, `|`, the type byte, `|`, the sequence number as 9(7) (high-order digits dropped:
  123456789 becomes `3456789`), `|`, `Unknown record type encountered` padded to 50, 43 spaces. Write it to
  ERROUT and add 1 to errors written. Source: `app/cbl/CBIMPORT.cbl:155-163`,
  `app/cbl/CBIMPORT.cbl:428-449 (2700-PROCESS-UNKNOWN-RECORD, 2750-WRITE-ERROR)`.
- **BR-CBIMPORT-08**: Validation is a stub: it always displays `CBIMPORT: Import validation completed` and
  `CBIMPORT: No validation errors detected`, even when unknown records were found. Source:
  `app/cbl/CBIMPORT.cbl:452-455 (3000-VALIDATE-IMPORT)`.
- **BR-CBIMPORT-09**: Summary after closing: `CBIMPORT: Import completed`, `CBIMPORT: Total Records Read: `,
  `CBIMPORT: Customers Imported: `, `CBIMPORT: Accounts Imported: `, `CBIMPORT: XRefs Imported: `,
  `CBIMPORT: Transactions Imported: `, `CBIMPORT: Cards Imported: `, `CBIMPORT: Errors Written: `,
  `CBIMPORT: Unknown Record Types: `, each followed by a 9-digit zero-padded count. Source:
  `app/cbl/CBIMPORT.cbl:458-481 (4000-FINALIZE)`.
- **BR-CBIMPORT-10**: Import date and time shown at start are FUNCTION CURRENT-DATE positions 1-4, 5-6, 7-8
  (date) and 9-10, 11-12, 13-14 (time), separated by `-` and `:`. Source: `app/cbl/CBIMPORT.cbl:181-196 (1000-INITIALIZE)`.

### Validation rules
None beyond the record-type dispatch. No field, duplicate-key, or cross-record checks exist, although the
program header promises checksum validation.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBIMPORT-01**: OPEN status not `00` -> `ERROR: Cannot open EXPORT-INPUT, Status: ` (or
  `CUSTOMER-OUTPUT`, `ACCOUNT-OUTPUT`, `XREF-OUTPUT`, `TRANSACTION-OUTPUT`, `CARD-OUTPUT`, `ERROR-OUTPUT`) +
  status, abend. Opened in that order. Source: `app/cbl/CBIMPORT.cbl:199-248 (1100-OPEN-FILES)`.
- **ERR-CBIMPORT-02**: EXPFILE READ status neither `00` nor `10` -> `ERROR: Reading EXPORT-INPUT, Status: ` +
  status, abend. Source: `app/cbl/CBIMPORT.cbl:262-270 (2100-READ-EXPORT-RECORD)`.
- **ERR-CBIMPORT-03**: Target WRITE status not `00` -> `ERROR: Writing customer record, Status: ` (or
  `account record`, `xref record`, `transaction record`, `card record`) + status, abend. Source:
  `app/cbl/CBIMPORT.cbl:317-321`, `app/cbl/CBIMPORT.cbl:346-350`, `app/cbl/CBIMPORT.cbl:366-370`,
  `app/cbl/CBIMPORT.cbl:396-400`, `app/cbl/CBIMPORT.cbl:419-423`.
- **ERR-CBIMPORT-04**: ERROUT WRITE status not `00` -> `ERROR: Writing error record, Status: ` + status;
  processing continues and the error is still counted as written. Source:
  `app/cbl/CBIMPORT.cbl:440-449 (2750-WRITE-ERROR)`.
- **ERR-CBIMPORT-05**: Abend: DISPLAY `CBIMPORT: ABENDING PROGRAM`, `CALL 'CEE3ABD'` with no arguments. CLOSE
  statuses are not checked. Source: `app/cbl/CBIMPORT.cbl:484-487 (9999-ABEND-PROGRAM)`, `app/cbl/CBIMPORT.cbl:460-466`.

## Data effects
| Dataset | Access | Effect |
|:--|:--|:--|
| EXPFILE | OPEN INPUT, sequential read | Read entirely, unchanged |
| CUSTOUT, ACCTOUT, XREFOUT, TRNXOUT, CARDOUT | OPEN OUTPUT, write | One record per export record of the matching type, in export sequence order |
| ERROUT | OPEN OUTPUT, write | One 132-byte line per unknown-type record |

The outputs are plain sequential files; nothing in the job loads them into the VSAM masters.

## Quirks
- **Q-CBIMPORT-01**: CBIMPORT.jcl has no `CARDOUT` DD, so on z/OS the OPEN of CARD-OUTPUT fails (unless
  the runtime allocates the file dynamically), the step abends with `ERROR: Cannot open CARD-OUTPUT,
  Status: ` + status before any record is processed, and the new output datasets are deleted by their
  abnormal-end disposition. Under GnuCOBOL the file is simply created as `CARDOUT` in the working directory.
  Recommendation: fix (add the DD). Source: `app/cbl/CBIMPORT.cbl:63-66`, `app/cbl/CBIMPORT.cbl:236-241`,
  `app/jcl/CBIMPORT.jcl:33-60`.
- **Q-CBIMPORT-02**: The validation step is a stub that reports success unconditionally; the "checksums"
  in the header do not exist. Recommendation: decide (implement real reconciliation, for example counts
  per type against the export summary). Source: `app/cbl/CBIMPORT.cbl:28-32`, `app/cbl/CBIMPORT.cbl:452-455`.
- **Q-CBIMPORT-03**: INITIALIZE does not touch FILLER, and the FD record areas are never cleared, so the
  filler bytes of every output record are whatever is in the I/O buffer: binary zeros under GnuCOBOL
  (customer bytes 332-499, account 122-299, xref 36-49, transaction 330-349, card 91-149), undefined on z/OS.
  The source files have spaces there, so a byte comparison with the originals fails even though every
  named field matches. Recommendation: fix (write spaces). Source: `app/cbl/CBIMPORT.cbl:293`,
  `app/cbl/CBIMPORT.cbl:328`, `app/cbl/CBIMPORT.cbl:357`, `app/cbl/CBIMPORT.cbl:377`, `app/cbl/CBIMPORT.cbl:407`.
- **Q-CBIMPORT-04**: The error line truncates the 9-digit sequence number to 7 digits and pads the 21-byte
  CURRENT-DATE value to 26 bytes, so it is not in the same format as the export timestamp.
  Recommendation: decide (fix expected: full sequence number and ISO timestamp). Source:
  `app/cbl/CBIMPORT.cbl:156-160`, `app/cbl/CBIMPORT.cbl:432-434`.
- **Q-CBIMPORT-05**: An ERROUT write failure is displayed but not fatal, and still counted in
  `Errors Written`. Recommendation: fix. Source: `app/cbl/CBIMPORT.cbl:442-449`.
- **Q-CBIMPORT-06**: No duplicate or referential checks: duplicate customer IDs, accounts without
  customers, or cards without accounts are written as they come. Outputs are not sorted beyond the export
  order (which is key order per type for a CBEXPORT file). Recommendation: decide. Source:
  `app/cbl/CBIMPORT.cbl:273-425`.
- **Q-CBIMPORT-07**: On z/OS an invalid packed or binary value in the export (for example a corrupted file)
  can raise a data exception (S0C7) during the numeric moves, ending the step without a CBIMPORT message.
  Recommendation: decide (validate and route to ERROUT in the target). Source: `app/cbl/CBIMPORT.cbl:296`,
  `app/cbl/CBIMPORT.cbl:333-340`, `app/cbl/CBIMPORT.cbl:385-386`.
- **Q-CBIMPORT-08**: The output datasets use DISP=(NEW,CATLG,DELETE) with no preceding delete step, so a
  rerun fails at allocation until they are removed. Recommendation: fix in the target job definition.
  Source: `app/jcl/CBIMPORT.jcl:33-60`.
- **Q-CBIMPORT-09**: CEE3ABD is called without arguments; off z/OS the call fails with
  `libcob: error: module 'CEE3ABD' not found` (exit code 1). Recommendation: fix. Source: `app/cbl/CBIMPORT.cbl:484-487`.
- **Q-CBIMPORT-10**: The imported data includes full PANs, CVVs, and SSNs in clear (same as CBEXPORT,
  Q-CBEXPORT-01). Recommendation: decide. Source: `app/cbl/CBIMPORT.cbl:308`, `app/cbl/CBIMPORT.cbl:410-412`.

## Test scenarios
Local runs (GnuCOBOL, `-fsign=EBCDIC`, CARDOUT supplied by the harness) on the 462-record export from
TS-CBEXPORT-01.

- **TS-CBIMPORT-01** (covers BR-CBIMPORT-01 to BR-CBIMPORT-06, BR-CBIMPORT-09): Given the 462-record export,
  when CBIMPORT runs, then RC is 0, SYSOUT ends with `CBIMPORT: Total Records Read: 000000462`,
  `Customers Imported: 000000050`, `Accounts Imported: 000000050`, `XRefs Imported: 000000050`,
  `Transactions Imported: 000000262`, `Cards Imported: 000000050`, `Errors Written: 000000000`,
  `Unknown Record Types: 000000000` (each with the `CBIMPORT: ` prefix), and ERROUT is empty.
- **TS-CBIMPORT-02** (covers BR-CBIMPORT-03 to BR-CBIMPORT-06, Q-CBIMPORT-03): Given the same run, then every
  named field of every output record equals the corresponding source record (customers, accounts, xref,
  the 262 transactions, cards, including signed amounts such as account credit limit 2020.00); the only
  differing bytes are FILLER (binary zeros instead of spaces).
- **TS-CBIMPORT-03** (covers BR-CBIMPORT-07, ERR-CBIMPORT-04): Given an export with one `C` record (sequence 1)
  and one record of type `Z` with sequence 123456789, then counts are read 2, customers 1, errors written 1,
  unknown 1, and ERROUT holds one 132-byte line `<21-char CURRENT-DATE>     |Z|3456789|Unknown record type encountered`
  followed by spaces (verified locally).
- **TS-CBIMPORT-04** (covers BR-CBIMPORT-08, Q-CBIMPORT-02): Given the export of TS-CBIMPORT-03, then SYSOUT
  still shows `CBIMPORT: Import validation completed` and `CBIMPORT: No validation errors detected`.
- **TS-CBIMPORT-05** (covers BR-CBIMPORT-01): Given an empty export KSDS, then all counts are `000000000`,
  all outputs are empty, and RC is 0 (verified locally).
- **TS-CBIMPORT-06** (covers ERR-CBIMPORT-01, ERR-CBIMPORT-05): Given the EXPFILE DD points to a missing
  dataset, then SYSOUT shows `ERROR: Cannot open EXPORT-INPUT, Status: 35` and `CBIMPORT: ABENDING PROGRAM`
  (verified locally; GnuCOBOL exits 1 because CEE3ABD is absent).
- **TS-CBIMPORT-07** (covers Q-CBIMPORT-01): Given the delivered CBIMPORT.jcl on z/OS (no CARDOUT DD), when the
  job runs, then it ends with `ERROR: Cannot open CARD-OUTPUT, Status: ` + a non-zero status and
  `CBIMPORT: ABENDING PROGRAM`, and no import datasets remain cataloged.
- **TS-CBIMPORT-08** (covers ERR-CBIMPORT-03): Given CUSTOUT is allocated with too little space, when the
  customer writes exceed it, then `ERROR: Writing customer record, Status: ` + status is displayed and the
  step abends (on z/OS, an out-of-space condition may instead end the step with a system abend such as SB37).

## Open questions
- Which file status does z/OS return for the missing CARDOUT DD with the installation's runtime options
  (Q-CBIMPORT-01), and is the intended fix to add the DD?
- What should the validation step check (Q-CBIMPORT-02)?
- Should the import load the VSAM masters directly, or is a separate REPRO step expected?
