# CBEXPORT: Export customers, accounts, cross-references, transactions, and cards to one multi-record migration file

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBEXPORT.cbl` (586 lines) |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | CBEXPORT, step STEP02 (`app/jcl/CBEXPORT.jcl:43-68`); STEP01 deletes and redefines the export KSDS (`app/jcl/CBEXPORT.jcl:24-38`) |
| BMS map | none |
| Copybooks | `CVCUS01Y` (CUSTOMER-RECORD, 500), `CVACT01Y` (ACCOUNT-RECORD, 300), `CVACT03Y` (CARD-XREF-RECORD, 50), `CVTRA05Y` (TRAN-RECORD, 350), `CVACT02Y` (CARD-RECORD, 150), `CVEXPORT` (EXPORT-RECORD, 500, with REDEFINES per record type) |
| Calls / XCTL targets | `CEE3ABD` (Language Environment abend, called with no arguments, only on error) |
| Data stores | DD `CUSTFILE` = `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS`: Browse; DD `ACCTFILE` = `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS`: Browse; DD `XREFFILE` = `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS`: Browse; DD `TRANSACT` = `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS`: Browse; DD `CARDFILE` = `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS`: Browse; DD `EXPFILE` = `AWS.M2.CARDDEMO.EXPORT.DATA` (KSDS, 500 bytes, key = 4-byte binary sequence number at offset 27): Write (OPEN OUTPUT, sequential load) |

## Purpose
CBEXPORT copies the five CardDemo master files into a single 500-byte multi-record file for a branch
migration. Each output record carries a one-letter type, a run timestamp, a running sequence number,
a fixed branch and region code, and the source record re-encoded into a type-specific layout that
mixes binary, packed-decimal, and zoned numeric fields. CBIMPORT reads the file back. The program
validates nothing and changes no source data.

## Invocation and state
- JCL: STEP01 IDCAMS `DELETE AWS.M2.CARDDEMO.EXPORT.DATA CLUSTER PURGE`, `SET MAXCC = 0`, then
  `DEFINE CLUSTER ... INDEXED KEYS(4 27) RECORDSIZE(500 500)`; STEP02 runs CBEXPORT with the five input
  DDs (DISP=SHR) and EXPFILE (DISP=SHR, the cluster just defined). No PARM, no SYSIN.
- Working-storage state: WS-FORMATTED-TIMESTAMP (set once per run), WS-SEQUENCE-COUNTER (9(9),
  continuous across all record types), one exported-record counter per type plus a total (9(9) each),
  file status fields with `00`/`10` 88-levels.
- Return code: never set; normal end RC 0. Open, read, and write errors call CEE3ABD.

## Processing flow
1. DISPLAY `CBEXPORT: Starting Customer Data Export`; build the timestamp (BR-CBEXPORT-01); open the
   five inputs and the output (ERR-CBEXPORT-01); DISPLAY `CBEXPORT: Export Date: ` + date and
   `CBEXPORT: Export Time: ` + time.
2. For each section in the fixed order customers, accounts, cross-references, transactions, cards
   (BR-CBEXPORT-02):
   1. DISPLAY `CBEXPORT: Processing customer records` (or `account`, `cross-reference`, `transaction`,
      `card`).
   2. Read the first record; WHILE status is not `10`: build and write one export record, read the next.
   3. DISPLAY `CBEXPORT: Customers exported: ` + count (section-specific text, BR-CBEXPORT-09).
3. Close all six files, DISPLAY the completion summary (BR-CBEXPORT-09), GOBACK.

## Rules
### Business rules
- **BR-CBEXPORT-01**: The run timestamp is taken once: ACCEPT DATE YYYYMMDD and ACCEPT TIME (HHMMSShh).
  Export date = `YYYY-MM-DD`, export time = `HH:MM:SS` (hundredths dropped), record timestamp =
  date + space + time + `.00`, left-justified in 26 bytes (`2026-10-28 13:48:03.00` + 4 spaces). Every
  record of the run carries the same timestamp. Source: `app/cbl/CBEXPORT.cbl:175-198 (1050-GENERATE-TIMESTAMP)`.
- **BR-CBEXPORT-02**: Sections are exported in the order customers (`C`, CUSTFILE in customer-ID
  order), accounts (`A`, account-ID order), cross-references (`X`, card-number order), transactions
  (`T`, transaction-ID order), cards (`D`, card-number order). Each section reads its whole file
  sequentially; there is no filtering and no linking between sections. Source:
  `app/cbl/CBEXPORT.cbl:152-161 (0000-MAIN-PROCESSING)`, `app/cbl/CBEXPORT.cbl:246-258`, `app/cbl/CBEXPORT.cbl:315-327`,
  `app/cbl/CBEXPORT.cbl:379-391`, `app/cbl/CBEXPORT.cbl:434-446`, `app/cbl/CBEXPORT.cbl:499-511`.
- **BR-CBEXPORT-03**: Common header of every export record (offsets 0-based): 0 record type X(1);
  1-26 timestamp X(26); 27-30 sequence number 9(9) COMP (4-byte unsigned big-endian binary, the KSDS
  key); 31-34 branch ID, always `0001`; 35-39 region code, always `NORTH`; 40-499 type-specific data
  (460 bytes). Before mapping, INITIALIZE EXPORT-RECORD sets the data area to spaces, so every unused
  or FILLER byte is a space. The sequence number is WS-SEQUENCE-COUNTER + 1 for each record, starting
  at 1 and continuing across sections. Source: `app/cpy/CVEXPORT.cpy:9-19`,
  `app/cbl/CBEXPORT.cbl:274-282 (2200-CREATE-CUSTOMER-EXP-REC)` and the same block in 3200, 4200, 5200, 5700.
- **BR-CBEXPORT-04**: Customer record `C` (data offsets from record start): 40-43 customer ID 9(9) COMP;
  44-68 first name X(25); 69-93 middle name; 94-118 last name; 119-268 three address lines X(50);
  269-270 state; 271-273 country; 274-283 ZIP; 284-313 two phone numbers X(15); 314-322 SSN 9(9) zoned;
  323-342 government ID X(20); 343-352 date of birth X(10); 353-362 EFT account ID X(10); 363
  primary-cardholder indicator; 364-365 FICO score 9(3) COMP-3 (unsigned, sign nibble `F`, for example
  x`274F`); 366-499 spaces. All fields are copied unchanged from CUSTOMER-RECORD. Source:
  `app/cpy/CVEXPORT.cpy:24-42`, `app/cbl/CBEXPORT.cbl:284-302 (2200-CREATE-CUSTOMER-EXP-REC)`.
- **BR-CBEXPORT-05**: Account record `A`: 40-50 account ID 9(11) zoned; 51 active status; 52-58 current
  balance S9(10)V99 COMP-3 (7 bytes, sign `C`/`D`); 59-70 credit limit S9(10)V99 zoned with trailing
  overpunch sign; 71-77 cash credit limit S9(10)V99 COMP-3; 78-87 open date; 88-97 expiration date;
  98-107 reissue date; 108-119 current-cycle credit S9(10)V99 zoned; 120-127 current-cycle debit
  S9(10)V99 COMP (8-byte signed big-endian binary holding the value x 100); 128-137 ZIP; 138-147 group
  ID; 148-499 spaces. Source: `app/cpy/CVEXPORT.cpy:47-60`, `app/cbl/CBEXPORT.cbl:353-365 (3200-CREATE-ACCOUNT-EXP-REC)`.
- **BR-CBEXPORT-06**: Cross-reference record `X`: 40-55 card number X(16); 56-64 customer ID 9(9) zoned;
  65-72 account ID 9(11) COMP (8-byte binary); 73-499 spaces. Source: `app/cpy/CVEXPORT.cpy:84-88`,
  `app/cbl/CBEXPORT.cbl:417-420 (4200-CREATE-XREF-EXPORT-RECORD)`.
- **BR-CBEXPORT-07**: Transaction record `T`: 40-55 transaction ID; 56-57 type code; 58-61 category
  9(4) zoned; 62-71 source; 72-171 description X(100); 172-177 amount S9(9)V99 COMP-3 (6 bytes);
  178-181 merchant ID 9(9) COMP; 182-231 merchant name; 232-281 merchant city; 282-291 merchant ZIP;
  292-307 card number; 308-333 origination timestamp X(26); 334-359 processing timestamp X(26);
  360-499 spaces. Source: `app/cpy/CVEXPORT.cpy:65-79`, `app/cbl/CBEXPORT.cbl:472-485 (5200-CREATE-TRAN-EXP-REC)`.
- **BR-CBEXPORT-08**: Card record `D`: 40-55 card number; 56-63 account ID 9(11) COMP; 64-65 CVV 9(3)
  COMP (2-byte binary); 66-115 embossed name X(50); 116-125 expiration date; 126 active status;
  127-499 spaces. Source: `app/cpy/CVEXPORT.cpy:93-100`, `app/cbl/CBEXPORT.cbl:537-543 (5700-CREATE-CARD-EXPORT-RECORD)`.
- **BR-CBEXPORT-09**: Counts: after each section, DISPLAY `CBEXPORT: Customers exported: `,
  `CBEXPORT: Accounts exported: `, `CBEXPORT: Cross-references exported: `,
  `CBEXPORT: Transactions exported: `, `CBEXPORT: Cards exported: ` + 9-digit zero-padded count. At the
  end: `CBEXPORT: Export completed`, `CBEXPORT: Customers Exported: `, `CBEXPORT: Accounts Exported: `,
  `CBEXPORT: XRefs Exported: `, `CBEXPORT: Transactions Exported: `, `CBEXPORT: Cards Exported: `,
  `CBEXPORT: Total Records Exported: ` + count. A count is incremented only after a successful write.
  Source: `app/cbl/CBEXPORT.cbl:257-258`, `app/cbl/CBEXPORT.cbl:312-313`, `app/cbl/CBEXPORT.cbl:557-576 (6000-FINALIZE)`.

### Validation rules
None. Source records are re-encoded without any check.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBEXPORT-01**: OPEN status not `00` -> `ERROR: Cannot open CUSTOMER-INPUT, Status: ` (or
  `ACCOUNT-INPUT`, `XREF-INPUT`, `TRANSACTION-INPUT`, `CARD-INPUT`, `EXPORT-OUTPUT`) + 2-character status,
  then abend (ERR-CBEXPORT-04). Files are opened in that order. Source:
  `app/cbl/CBEXPORT.cbl:201-243 (1100-OPEN-FILES)`.
- **ERR-CBEXPORT-02**: READ status neither `00` nor `10` -> `ERROR: Reading CUSTOMER-INPUT, Status: ` (or
  the other input names) + status, abend. Source: `app/cbl/CBEXPORT.cbl:261-269`, `app/cbl/CBEXPORT.cbl:330-338`,
  `app/cbl/CBEXPORT.cbl:394-402`, `app/cbl/CBEXPORT.cbl:449-457`, `app/cbl/CBEXPORT.cbl:514-522`.
- **ERR-CBEXPORT-03**: WRITE status not `00` -> `ERROR: Writing export record, Status: ` + status, abend.
  Source: `app/cbl/CBEXPORT.cbl:304-310`, `app/cbl/CBEXPORT.cbl:367-373`, `app/cbl/CBEXPORT.cbl:422-428`,
  `app/cbl/CBEXPORT.cbl:487-493`, `app/cbl/CBEXPORT.cbl:545-551`.
- **ERR-CBEXPORT-04**: Abend: DISPLAY `CBEXPORT: ABENDING PROGRAM`, `CALL 'CEE3ABD'` with no arguments.
  Source: `app/cbl/CBEXPORT.cbl:578-582 (9999-ABEND-PROGRAM)`.
- **ERR-CBEXPORT-05**: CLOSE statuses are not checked; the completion messages are displayed regardless.
  Source: `app/cbl/CBEXPORT.cbl:559-566 (6000-FINALIZE)`.

## Data effects
| Dataset | Access | Effect |
|:--|:--|:--|
| CUSTFILE, ACCTFILE, XREFFILE, TRANSACT, CARDFILE | OPEN INPUT, sequential read | Read entirely, unchanged |
| EXPFILE (EXPORT.DATA KSDS) | OPEN OUTPUT, sequential write in ascending key | One 500-byte record per source record; the cluster is deleted and redefined by STEP01 every run |

## Quirks
- **Q-CBEXPORT-01**: The export carries full card numbers, CVV codes, SSNs, dates of birth, and government
  IDs in clear text. Storing CVV after authorisation is not allowed under PCI DSS. Recommendation: decide
  (fix expected: drop CVV, encrypt or tokenise PAN and SSN). Source: `app/cbl/CBEXPORT.cbl:297-299`,
  `app/cbl/CBEXPORT.cbl:538-540`.
- **Q-CBEXPORT-02**: Branch `0001` and region `NORTH` are hard-coded, so the fields carry no information.
  Recommendation: decide (parameterise or drop). Source: `app/cbl/CBEXPORT.cbl:281-282`.
- **Q-CBEXPORT-03**: The timestamp always ends in `.00` and is padded with 4 spaces; hundredths from
  ACCEPT TIME are discarded and the time is local. Recommendation: preserve for file compatibility, or
  decide on a full ISO timestamp. Source: `app/cbl/CBEXPORT.cbl:188-197`.
- **Q-CBEXPORT-04**: Numeric fields deliberately mix encodings (binary COMP, packed COMP-3, zoned with
  overpunch sign) and text is EBCDIC on z/OS; one account record holds all three numeric encodings. A
  consumer must decode per field. The local GnuCOBOL build needs `-fsign=EBCDIC` for the sample data's
  overpunch signs. Recommendation: preserve if the binary file format is an external contract;
  otherwise decide (a self-describing format). Source: `app/cpy/CVEXPORT.cpy:24-100`.
- **Q-CBEXPORT-05**: On z/OS, moving a non-numeric zoned field (for example spaces in an ID or amount) to a
  COMP or COMP-3 target can raise a data exception (S0C7) and end the step without a CBEXPORT message.
  Sample data is clean. Recommendation: decide (validate and reject with a message in the target).
  Source: `app/cbl/CBEXPORT.cbl:285`, `app/cbl/CBEXPORT.cbl:356-363`, `app/cbl/CBEXPORT.cbl:420`, `app/cbl/CBEXPORT.cbl:539-540`.
- **Q-CBEXPORT-06**: The file is described as a "sequential export file" in CVEXPORT but is a KSDS keyed on
  the binary sequence number; CBIMPORT reads it in key order. Recommendation: preserve (ordering by
  sequence is what matters). Source: `app/cpy/CVEXPORT.cpy:3-4`, `app/jcl/CBEXPORT.jcl:30-38`.
- **Q-CBEXPORT-07**: Per-section messages say `exported` and final ones `Exported`, with different labels
  (`Cross-references exported:` versus `XRefs Exported:`). Recommendation: preserve if log scraping depends
  on it. Source: `app/cbl/CBEXPORT.cbl:390`, `app/cbl/CBEXPORT.cbl:571`.
- **Q-CBEXPORT-08**: CEE3ABD is called without arguments (abend code undefined on z/OS); off z/OS the call
  fails with `libcob: error: module 'CEE3ABD' not found` and exit code 1. Recommendation: fix. Source:
  `app/cbl/CBEXPORT.cbl:579-582`.
- **Q-CBEXPORT-09**: The comment line at source line 314 has asterisks in the sequence area (columns 3-6) as
  well as column 7; it is still a valid comment. Recommendation: preserve (no behaviour). Source:
  `app/cbl/CBEXPORT.cbl:314`.

## Test scenarios
Local run (GnuCOBOL, `-fsign=EBCDIC`): the 50 sample customers, accounts, xref records, and cards, plus the
262 transactions posted by POSTTRAN.

- **TS-CBEXPORT-01** (covers BR-CBEXPORT-02, BR-CBEXPORT-09): Given the sample inputs, when STEP02 runs, then RC
  is 0 and SYSOUT shows, in order: `CBEXPORT: Starting Customer Data Export`, `CBEXPORT: Export Date: `
  date, `CBEXPORT: Export Time: ` time, `CBEXPORT: Processing customer records`,
  `CBEXPORT: Customers exported: 000000050`, ... `CBEXPORT: Transactions exported: 000000262`, ...
  `CBEXPORT: Cards exported: 000000050`, `CBEXPORT: Export completed`, the five `Exported:` totals, and
  `CBEXPORT: Total Records Exported: 000000462`.
- **TS-CBEXPORT-02** (covers BR-CBEXPORT-03): Given the same run, then EXPFILE has 462 records with
  sequence numbers 1 to 462: `C` 1-50, `A` 51-100, `X` 101-150, `T` 151-412, `D` 413-462; every record has
  the same timestamp `YYYY-MM-DD HH:MM:SS.00` + 4 spaces, `0001` at offset 31, and `NORTH` at offset 35.
- **TS-CBEXPORT-03** (covers BR-CBEXPORT-05): Given account `00000000001` (status `Y`, balance 194.00, credit
  limit 2020.00, cash limit 1020.00, opened 2014-11-20), then its `A` record has offsets 40-50
  `00000000001`, 51 `Y`, 52-58 x`0000000019400C`, 59-70 `00000020200{` (EBCDIC overpunch for +0 in the last
  digit), 71-77 x`0000000102000C`, 78-87 `2014-11-20`.
- **TS-CBEXPORT-04** (covers BR-CBEXPORT-06, BR-CBEXPORT-08): Given xref card 0500****5740 (customer
  `000000050`, account 50), then its `X` record has the card at 40-55, `000000050` at 56-64, and
  x`0000000000000032` at 65-72; its `D` record has x`0000000000000032` at 56-63 and the CVV as a 2-byte
  big-endian binary at 64-65.
- **TS-CBEXPORT-05** (covers BR-CBEXPORT-04, BR-CBEXPORT-07): Given customer `000000001` with FICO 274, then
  the `C` record has x`00000001` at 40-43 and x`274F` at 364-365, spaces from 366; a transaction of
  amount 504.77 with merchant ID 800000000 has x`00000050477C` at 172-177 and x`2FAF0800` at 178-181.
- **TS-CBEXPORT-06** (covers BR-CBEXPORT-02): Given an empty TRANSACT KSDS, when the program runs, then
  `CBEXPORT: Transactions exported: 000000000` is displayed and the card records follow the xref records
  directly (sequence numbers 151-200).
- **TS-CBEXPORT-07** (covers ERR-CBEXPORT-01, ERR-CBEXPORT-04): Given the CUSTFILE DD points to a missing
  dataset, then SYSOUT shows `ERROR: Cannot open CUSTOMER-INPUT, Status: 35` and
  `CBEXPORT: ABENDING PROGRAM` (verified locally; GnuCOBOL then exits 1 because CEE3ABD is absent).
- **TS-CBEXPORT-08** (covers ERR-CBEXPORT-03): Given EXPFILE already holds a record with sequence number 1
  and is opened without the STEP01 redefine on a platform that keeps it, then the first write fails with a
  duplicate-key status, SYSOUT shows `ERROR: Writing export record, Status: 22` (or the platform's open
  failure via ERR-CBEXPORT-01), and the step abends.
- **TS-CBEXPORT-09** (covers BR-CBEXPORT-04 to BR-CBEXPORT-08 with CBIMPORT): Given the export from
  TS-CBEXPORT-01, when CBIMPORT splits it, then every output record matches its source record in every named
  field (verified locally; only FILLER bytes differ, see Q-CBIMPORT-03).

## Open questions
- Is the binary layout an external contract with a receiving system, or can the target use a different
  format (Q-CBEXPORT-04)?
- Must CVV and full PAN be part of the migration file (Q-CBEXPORT-01)?
- Should branch and region come from configuration (Q-CBEXPORT-02)?
- Should the export be restricted to a set of customers (the comments mention "complete customer profiles"
  but the program exports whole files)?
