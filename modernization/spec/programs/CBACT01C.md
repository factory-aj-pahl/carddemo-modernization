# CBACT01C: List the account master and write three demonstration extract files (fixed, array, and variable-length)

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBACT01C.cbl` (430 lines); assembler date routine `app/asm/COBDATFT.asm` (84 lines) with DSECT `app/maclib/COCDATFT.mac` |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | JOB-READACCT, step STEP05 after IEFBR14 pre-delete step PREDEL (`app/jcl/READACCT.jcl:22-50`) |
| BMS map | none |
| Copybooks | `CVACT01Y` (ACCOUNT-RECORD, 300 bytes), `CODATECN` (CODATECN-REC, date-conversion parameter block) |
| Calls / XCTL targets | static CALL `COBDATFT` (assembler date reformatter), `CEE3ABD` (abend, only on error) |
| Data stores | DD `ACCTFILE` = `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` (KSDS, 300 bytes, key ACCT-ID 9(11) offset 0): Browse (OPEN INPUT, sequential READ NEXT). DD `OUTFILE` = `AWS.M2.CARDDEMO.ACCTDATA.PSCOMP` (sequential, FB 107): Write. DD `ARRYFILE` = `AWS.M2.CARDDEMO.ACCTDATA.ARRYPS` (sequential, FB 110): Write. DD `VBRCFILE` = `AWS.M2.CARDDEMO.ACCTDATA.VBPS` (sequential, RECFM=VB, LRECL 84, records 10-80 bytes): Write |

## Purpose
CBACT01C reads every account in the account master file, prints each account field by field to
SYSOUT, and writes three extract files that demonstrate COBOL data formats: a fixed-length copy of the
account with one packed-decimal field and a reformatted reissue date, a record with an OCCURS array of
balances partly filled with hard-coded test amounts, and two variable-length records per account. The
extracts contain synthetic values (constants such as 2525.00, 1005.00, -2500.00), so they are test or
demonstration data rather than business output.

## Invocation and state
- JOB-READACCT: PREDEL deletes the three output datasets (DISP=(MOD,DELETE,DELETE)), then STEP05 runs
  CBACT01C with ACCTFILE (DISP=SHR) and the three outputs as NEW,CATLG,DELETE (`app/jcl/READACCT.jcl:22-48`).
- No PARM, no SYSIN.
- Working-storage state carried across records: END-OF-FILE, APPL-RESULT (0 OK, 16 EOF, 12 error,
  8 initial), WS-RECD-LEN (current variable record length), and the FD record areas of OUTFILE (see
  Q-CBACT01C-01) and the CODATECN-REC block (see Q-CBACT01C-02), which are never reset between records.
- Return code: never set; RC 0 on success; any I/O error ends with user abend U999 via CEE3ABD.

## Processing flow
1. DISPLAY `START OF EXECUTION OF PROGRAM CBACT01C`.
2. Open files in this order, abending on the first failure: 0000-ACCTFILE-OPEN (INPUT), 2000-OUTFILE-OPEN
   (OUTPUT), 3000-ARRFILE-OPEN (OUTPUT), 4000-VBRFILE-OPEN (OUTPUT).
3. FOR EACH account record until END-OF-FILE = `Y`: PERFORM 1000-ACCTFILE-GET-NEXT, which reads the next
   record INTO ACCOUNT-RECORD and:
   - IF status `00`:
     1. INITIALIZE ARR-ARRAY-REC (ACCT-ID and all balance occurrences to zero, ARR-FILLER to spaces).
     2. 1100-DISPLAY-ACCT-RECORD: display 11 labelled lines and a dashed line (BR-CBACT01C-01).
     3. 1300-POPUL-ACCT-RECORD: build OUT-ACCT-REC (BR-CBACT01C-02..04), including the COBDATFT call.
     4. 1350-WRITE-ACCT-RECORD: WRITE OUT-ACCT-REC.
     5. 1400-POPUL-ARRAY-RECORD then 1450-WRITE-ARRY-RECORD (BR-CBACT01C-05).
     6. INITIALIZE VBRC-REC1; 1500-POPUL-VBRC-RECORD (fills VBRC-REC1 and VBRC-REC2 and displays both);
        1550-WRITE-VB1-RECORD (12-byte record) and 1575-WRITE-VB2-RECORD (39-byte record) (BR-CBACT01C-06).
     7. Back in the main loop: DISPLAY ACCOUNT-RECORD (the whole 300-byte record on one line).
   - IF status `10`: END-OF-FILE = `Y`.
   - OTHERWISE: error and abend (ERR-CBACT01C-02).
4. 9000-ACCTFILE-CLOSE: CLOSE ACCTFILE only; abend if status not `00`. The three output files are not
   closed explicitly; the runtime closes them at GOBACK.
5. DISPLAY `END OF EXECUTION OF PROGRAM CBACT01C`, GOBACK.

## Rules
### Business rules
- **BR-CBACT01C-01**: For each account, SYSOUT receives, in order: `ACCT-ID                 :` + ACCT-ID,
  `ACCT-ACTIVE-STATUS      :`, `ACCT-CURR-BAL           :`, `ACCT-CREDIT-LIMIT       :`,
  `ACCT-CASH-CREDIT-LIMIT  :`, `ACCT-OPEN-DATE          :`, `ACCT-EXPIRAION-DATE     :`,
  `ACCT-REISSUE-DATE       :`, `ACCT-CURR-CYC-CREDIT    :`, `ACCT-CURR-CYC-DEBIT     :`,
  `ACCT-GROUP-ID           :` (each label is 25 characters ending in `:` with the raw field value
  appended), then a line of 49 dashes `-------------------------------------------------`, then
  `VBRC-REC1:` + the 12-byte record 1, `VBRC-REC2:` + the 39-byte record 2, then the raw 300-byte
  ACCOUNT-RECORD. Signed amounts are displayed in their zoned form (on z/OS the last digit carries the
  overpunched sign, for example `00000001940{` for +194.00). Source: `app/cbl/CBACT01C.cbl:200-213 (1100-DISPLAY-ACCT-RECORD)`,
  `app/cbl/CBACT01C.cbl:283-284 (1500-POPUL-VBRC-RECORD)`, `app/cbl/CBACT01C.cbl:150-152 (main procedure)`.
- **BR-CBACT01C-02**: OUTFILE record (107 bytes) is a copy of the account with these fields in order:
  ACCT-ID 9(11), ACTIVE-STATUS X(1), CURR-BAL S9(10)V99 zoned (12), CREDIT-LIMIT (12), CASH-CREDIT-LIMIT (12),
  OPEN-DATE X(10), EXPIRAION-DATE X(10), REISSUE-DATE X(10) (reformatted, BR-CBACT01C-03), CURR-CYC-CREDIT
  zoned (12), CURR-CYC-DEBIT S9(10)V99 COMP-3 (7 bytes, BR-CBACT01C-04), GROUP-ID X(10). ACCT-ADDR-ZIP and the
  178-byte filler are not copied. Source: `app/cbl/CBACT01C.cbl:56-69 (FD OUT-FILE)`,
  `app/cbl/CBACT01C.cbl:215-240 (1300-POPUL-ACCT-RECORD)`.
- **BR-CBACT01C-03**: The reissue date `YYYY-MM-DD` is converted by COBDATFT with input type `2` and
  output type `2` to `YYYYMMDD`: output bytes 1-4 = input 1-4, bytes 5-6 = input 6-7, bytes 7-8 = input
  9-10. The first 10 bytes of the 20-byte output area are moved to OUT-ACCT-REISSUE-DATE, so bytes 9-10
  are whatever the output area held (never set; see Q-CBACT01C-02). COBDATFT does not validate the input
  (the dash check is commented out) and its error message is not examined.
  Source: `app/cbl/CBACT01C.cbl:223-233 (1300-POPUL-ACCT-RECORD)`, `app/asm/COBDATFT.asm:46-54 (VALIDIN2)`.
- **BR-CBACT01C-04**: IF ACCT-CURR-CYC-DEBIT = 0, OUT-ACCT-CURR-CYC-DEBIT = +2525.00 (packed
  X'0000000252500C'). OTHERWISE the field is not assigned and keeps the value from the previous record
  (or the initial content of the record area for the first record). The real cycle debit is never copied.
  Source: `app/cbl/CBACT01C.cbl:236-238 (1300-POPUL-ACCT-RECORD)`.
- **BR-CBACT01C-05**: ARRYFILE record (110 bytes) = ARR-ACCT-ID 9(11) + 5 occurrences of
  (ARR-ACCT-CURR-BAL S9(10)V99 zoned 12 bytes, ARR-ACCT-CURR-CYC-DEBIT S9(10)V99 COMP-3 7 bytes) + ARR-FILLER
  X(4). Values: occurrence 1 = (ACCT-CURR-BAL, +1005.00); occurrence 2 = (ACCT-CURR-BAL, +1525.00);
  occurrence 3 = (-1025.00, -2500.00); occurrences 4 and 5 = (0, 0) from INITIALIZE; filler = 4 spaces.
  Source: `app/cbl/CBACT01C.cbl:71-78 (FD ARRY-FILE)`, `app/cbl/CBACT01C.cbl:169`,
  `app/cbl/CBACT01C.cbl:253-261 (1400-POPUL-ARRAY-RECORD)`.
- **BR-CBACT01C-06**: Each account produces two VBRCFILE records: record 1 (length 12) = ACCT-ID 9(11) +
  ACCT-ACTIVE-STATUS X(1); record 2 (length 39) = ACCT-ID 9(11) + ACCT-CURR-BAL zoned (12) +
  ACCT-CREDIT-LIMIT zoned (12) + reissue year X(4) (first 4 characters of ACCT-REISSUE-DATE). Lengths are set
  in WS-RECD-LEN, which the FD uses as the DEPENDING ON item (RDW length = data length + 4).
  Source: `app/cbl/CBACT01C.cbl:80-85 (FD VBRC-FILE)`, `app/cbl/CBACT01C.cbl:276-315 (1500-POPUL-VBRC-RECORD,
  1550-WRITE-VB1-RECORD, 1575-WRITE-VB2-RECORD)`.
- **BR-CBACT01C-07**: Output order per account: OUTFILE record, ARRYFILE record, VBRC record 1, VBRC
  record 2. Accounts are processed in ascending ACCT-ID order. Source: `app/cbl/CBACT01C.cbl:165-178 (1000-ACCTFILE-GET-NEXT)`.

### Validation rules
None. Account content is not validated.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBACT01C-01**: Open failures (status not `00`), checked in order, each followed by the status line,
  `ABENDING PROGRAM`, and abend U999: ACCTFILE -> `ERROR OPENING ACCTFILE`; OUTFILE ->
  `ERROR OPENING OUTFILE` + the 2-byte status on the same line; ARRYFILE -> `ERROR OPENING ARRAYFILE` +
  status; VBRCFILE -> `ERROR OPENING VBRC FILE` + status.
  Source: `app/cbl/CBACT01C.cbl:317-386 (0000-ACCTFILE-OPEN, 2000-OUTFILE-OPEN, 3000-ARRFILE-OPEN, 4000-VBRFILE-OPEN)`.
- **ERR-CBACT01C-02**: ACCTFILE READ status other than `00`/`10` -> `ERROR READING ACCOUNT FILE`, status
  line, `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBACT01C.cbl:179-197 (1000-ACCTFILE-GET-NEXT)`.
- **ERR-CBACT01C-03**: WRITE to any of the three outputs with status other than `00` or `10` ->
  `ACCOUNT FILE WRITE STATUS IS:` + status (the same text for OUTFILE, ARRYFILE, and VBRCFILE), status line,
  `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBACT01C.cbl:242-251 (1350-WRITE-ACCT-RECORD)`,
  `app/cbl/CBACT01C.cbl:263-274 (1450-WRITE-ARRY-RECORD)`, `app/cbl/CBACT01C.cbl:287-315 (1550-WRITE-VB1-RECORD, 1575-WRITE-VB2-RECORD)`.
- **ERR-CBACT01C-04**: ACCTFILE CLOSE status not `00` -> `ERROR CLOSING ACCOUNT FILE`, status line,
  `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBACT01C.cbl:388-404 (9000-ACCTFILE-CLOSE)`.
- **ERR-CBACT01C-05**: The status line is `FILE STATUS IS: NNNN` + 4 characters: for non-numeric or `9x`
  statuses the first byte plus the 3-digit binary value of the second byte, otherwise `00` + status; the
  abend is CEE3ABD with code 999, timing 0. Source: `app/cbl/CBACT01C.cbl:406-426 (9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS)`.

## Data effects
- No master data is changed (ACCTFILE is INPUT).
- Creates `AWS.M2.CARDDEMO.ACCTDATA.PSCOMP` (one 107-byte record per account), `AWS.M2.CARDDEMO.ACCTDATA.ARRYPS`
  (one 110-byte record per account), and `AWS.M2.CARDDEMO.ACCTDATA.VBPS` (two variable records per account).
- On abend the outputs are deleted by DISP=(NEW,CATLG,DELETE).

## Quirks
- **Q-CBACT01C-01**: OUT-ACCT-CURR-CYC-DEBIT is set (to the constant 2525.00) only when the account's cycle
  debit is zero; for a non-zero debit the field silently keeps the previous record's value, and for the
  first record it holds uninitialized FD buffer content (possibly invalid packed data). Recommendation:
  decide (the constant looks like test scaffolding; a real extract would copy ACCT-CURR-CYC-DEBIT).
  Source: `app/cbl/CBACT01C.cbl:236-238`.
- **Q-CBACT01C-02**: OUT-ACCT-REISSUE-DATE receives 10 bytes from a 20-byte output area of which COBDATFT
  fills only 8, so bytes 9-10 contain uninitialized working storage (typically X'00' on z/OS). Recommendation:
  fix (emit `YYYYMMDD` plus 2 spaces, or an 8-byte field). Source: `app/cbl/CBACT01C.cbl:233`, `app/cpy/CODATECN.cpy:35-39`.
- **Q-CBACT01C-03**: The array record contains hard-coded amounts (1005.00, 1525.00, -1025.00, -2500.00),
  and VBRC record 2 is labelled with credit limit but no business meaning is documented. The program is a
  data-format demonstration. Recommendation: decide (likely drop in the target or keep only as a test
  fixture generator). Source: `app/cbl/CBACT01C.cbl:253-261`.
- **Q-CBACT01C-04**: A WRITE returning status `10` is treated as success, although `10` (end of file) is
  not a valid outcome of WRITE. Recommendation: fix (treat only `00` as success). Source: `app/cbl/CBACT01C.cbl:245`, `app/cbl/CBACT01C.cbl:266-267`, `app/cbl/CBACT01C.cbl:292-293`, `app/cbl/CBACT01C.cbl:307-308`.
- **Q-CBACT01C-05**: The write error text `ACCOUNT FILE WRITE STATUS IS:` is used for all three output files,
  so the failing file cannot be identified from the message. Recommendation: fix.
  Source: `app/cbl/CBACT01C.cbl:246`, `app/cbl/CBACT01C.cbl:268`, `app/cbl/CBACT01C.cbl:294`, `app/cbl/CBACT01C.cbl:309`.
- **Q-CBACT01C-06**: OUTFILE, ARRYFILE, and VBRCFILE are never closed by the program (only ACCTFILE is);
  an implicit close at run-unit end flushes them. Under GnuCOBOL this produces implicit-close warnings.
  Recommendation: fix (close explicitly). Source: `app/cbl/CBACT01C.cbl:156`, `app/cbl/CBACT01C.cbl:388-404`.
- **Q-CBACT01C-07**: The sample account data carries the value `A000000000` in ACCT-ADDR-ZIP and spaces in
  ACCT-GROUP-ID (a layout shift in the sample file), so OUT-ACCT-GROUP-ID is blank for every sample account.
  Recommendation: decide (data fix in `app/data`, see `entities.md`). Source: `app/cpy/CVACT01Y.cpy:15-16`, `app/data/ASCII/acctdata.txt`.
- **Q-CBACT01C-08**: COBDATFT is assembler; the program cannot run under GnuCOBOL without a stub
  implementing BR-CBACT01C-03. COBDATFT also always returns R15 = 0 and reports problems only through
  CODATECN-ERROR-MSG (`INVALID INPUT`), which CBACT01C ignores. Recommendation: replace with a library date
  formatter in the target. Source: `app/cbl/CBACT01C.cbl:231`, `app/asm/COBDATFT.asm:30-62`.
- **Q-CBACT01C-09**: The account record is displayed twice per account (labelled fields and the raw record),
  and the display labels keep the copybook misspelling `EXPIRAION`. Recommendation: preserve labels only if
  a consumer parses them. Source: `app/cbl/CBACT01C.cbl:151`, `app/cbl/CBACT01C.cbl:207`.

## Test scenarios
Sample data: `app/data/ASCII/acctdata.txt` (50 accounts). Account `00000000001`: status `Y`, balance
194.00 (`00000001940{`), credit limit 2020.00, cash limit 1020.00, open 2014-11-20, expiry 2025-05-20,
reissue 2025-05-20, cycle credit 0, cycle debit 0, group ID blank.

- **TS-CBACT01C-01** (covers BR-CBACT01C-01, BR-CBACT01C-07): Given the sample account file, when READACCT
  runs (with a COBDATFT stub off z/OS), then SYSOUT shows the start message, for each of the 50 accounts 11
  labelled lines, a dashed line, `VBRC-REC1:`, `VBRC-REC2:`, and the raw record, then the end message; RC 0.
- **TS-CBACT01C-02** (covers BR-CBACT01C-02, BR-CBACT01C-03, BR-CBACT01C-04): Given account 1, then its
  OUTFILE record is 107 bytes: `00000000001Y00000001940{00000020200{00000010200{2014-11-202025-05-20`,
  then `20250520` + 2 undefined bytes, then `00000000000{`, then packed X'0000000252500C', then 10 spaces.
- **TS-CBACT01C-03** (covers BR-CBACT01C-05): Given account 1, then its ARRYFILE record is
  `00000000001` + (`00000001940{`, X'0000000100500C') + (`00000001940{`, X'0000000152500C') +
  (`00000010250}`, X'0000000250000D') + 2 x (`00000000000{`, X'0000000000000C') + 4 spaces (110 bytes).
- **TS-CBACT01C-04** (covers BR-CBACT01C-06): Given account 1, then VBRCFILE receives a 12-byte record
  `00000000001Y` and a 39-byte record `00000000001` + `00000001940{` + `00000020200{` + `2025`.
- **TS-CBACT01C-05** (covers BR-CBACT01C-04, Q-CBACT01C-01): Given two accounts where the first has cycle
  debit 0 and the second has cycle debit 50.00, then both OUTFILE records carry 2525.00 in the packed debit field.
- **TS-CBACT01C-06** (covers ERR-CBACT01C-01): Given OUTFILE cannot be allocated (open status `37`), then the
  output is `ERROR OPENING OUTFILE37`, `FILE STATUS IS: NNNN0037`, `ABENDING PROGRAM`, abend U999.
- **TS-CBACT01C-07** (covers ERR-CBACT01C-02): Given the account file returns read status `30`, then
  `ERROR READING ACCOUNT FILE`, `FILE STATUS IS: NNNN0030`, `ABENDING PROGRAM`, abend U999.
- **TS-CBACT01C-08** (covers ERR-CBACT01C-03): Given ARRYFILE runs out of space (write status `34`), then
  `ACCOUNT FILE WRITE STATUS IS:34`, `FILE STATUS IS: NNNN0034`, `ABENDING PROGRAM`, abend U999.

## Open questions
- Who consumes PSCOMP, ARRYPS, and VBPS? If nobody, the target can drop this job; if a consumer exists,
  which of the constant values (2525.00, 1005.00, 1525.00, -1025.00, -2500.00) does it expect?
- What do bytes 9-10 of OUT-ACCT-REISSUE-DATE contain on the real system (Q-CBACT01C-02)?
- Is the account sample file's ZIP/group misalignment a data defect or an intended layout (Q-CBACT01C-07)?
