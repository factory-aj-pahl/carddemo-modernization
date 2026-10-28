# CBCUS01C: List every record of the customer master file to SYSOUT

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBCUS01C.cbl` (178 lines) |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | JOB-READCUST, step STEP05 (`app/jcl/READCUST.jcl:21-27`) |
| BMS map | none |
| Copybooks | `CVCUS01Y` (CUSTOMER-RECORD, 500 bytes) |
| Calls / XCTL targets | `CEE3ABD` (Language Environment abend service, only on error) |
| Data stores | DD `CUSTFILE` = `AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS` (KSDS, 500-byte records, key FD-CUST-ID PIC 9(09) offset 0 length 9): Browse (OPEN INPUT, sequential READ NEXT) |

## Purpose
CBCUS01C is a diagnostic utility that reads the customer master file in customer-ID order and writes
each 500-byte customer record, unformatted, to SYSOUT. It changes no data. Because of a leftover debug
statement, every record is printed twice.

## Invocation and state
- Run as STEP05 of JOB-READCUST with DD `CUSTFILE` (DISP=SHR), `SYSOUT`, `SYSPRINT`. No PARM, no SYSIN.
- Working-storage state: END-OF-FILE flag, APPL-RESULT (0 OK, 16 end of file, 12 error, 8 initial).
- Return code: never set; normal end RC 0. I/O errors end with user abend U999 via CEE3ABD.

## Processing flow
1. DISPLAY `START OF EXECUTION OF PROGRAM CBCUS01C`.
2. 0000-CUSTFILE-OPEN: OPEN INPUT CUSTFILE; IF status is not `00`, report and abend (ERR-CBCUS01C-01).
3. FOR EACH record until END-OF-FILE = `Y`: PERFORM 1000-CUSTFILE-GET-NEXT (READ next INTO CUSTOMER-RECORD).
   - Status `00`: DISPLAY CUSTOMER-RECORD in the read paragraph and again in the main loop.
   - Status `10`: END-OF-FILE = `Y`.
   - Other status: report and abend (ERR-CBCUS01C-02).
4. 9000-CUSTFILE-CLOSE: CLOSE; IF status is not `00`, report and abend (ERR-CBCUS01C-03).
5. DISPLAY `END OF EXECUTION OF PROGRAM CBCUS01C`, GOBACK.

The error routines are named Z-DISPLAY-IO-STATUS and Z-ABEND-PROGRAM here but behave exactly like
9910-DISPLAY-IO-STATUS and 9999-ABEND-PROGRAM in CBACT02C (see `CBACT02C.md`).

## Rules
### Business rules
- **BR-CBCUS01C-01**: Customer records are listed in ascending CUST-ID order, each as two identical
  500-byte SYSOUT lines in the CVCUS01Y layout: CUST-ID 9(9), first/middle/last name X(25) each, address
  lines 1-3 X(50) each, state X(2), country X(3), ZIP X(10), phone 1 and 2 X(15) each, SSN 9(9),
  government ID X(20), date of birth X(10) `YYYY-MM-DD`, EFT account X(10), primary-card-holder indicator
  X(1), FICO score 9(3), FILLER X(168). Source: `app/cbl/CBCUS01C.cbl:96 (1000-CUSTFILE-GET-NEXT)`,
  `app/cbl/CBCUS01C.cbl:74-81 (main procedure)`, `app/cpy/CVCUS01Y.cpy:4-23`.
- **BR-CBCUS01C-02**: The run is framed by `START OF EXECUTION OF PROGRAM CBCUS01C` and
  `END OF EXECUTION OF PROGRAM CBCUS01C`. Source: `app/cbl/CBCUS01C.cbl:71`, `app/cbl/CBCUS01C.cbl:85`.
- **BR-CBCUS01C-03**: Read status `10` ends the loop with no output for that read.
  Source: `app/cbl/CBCUS01C.cbl:98-99`, `app/cbl/CBCUS01C.cbl:107-108 (1000-CUSTFILE-GET-NEXT)`.

### Validation rules
None.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBCUS01C-01**: OPEN status not `00` -> `ERROR OPENING CUSTFILE`, `FILE STATUS IS: NNNN` + status,
  `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBCUS01C.cbl:118-134 (0000-CUSTFILE-OPEN)`.
- **ERR-CBCUS01C-02**: READ status not `00`/`10` -> `ERROR READING CUSTOMER FILE`, status line,
  `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBCUS01C.cbl:100-114 (1000-CUSTFILE-GET-NEXT)`.
- **ERR-CBCUS01C-03**: CLOSE status not `00` -> `ERROR CLOSING CUSTOMER FILE`, status line,
  `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBCUS01C.cbl:136-152 (9000-CUSTFILE-CLOSE)`.
- **ERR-CBCUS01C-04**: Status formatting and abend: IF status is non-numeric or begins with `9`, the
  first byte plus the 3-digit unsigned binary value of the second byte; OTHERWISE `00` + status. CALL
  CEE3ABD with 999 and timing 0. Source: `app/cbl/CBCUS01C.cbl:154-174 (Z-ABEND-PROGRAM, Z-DISPLAY-IO-STATUS)`.

## Data effects
None. Input-only access; output is SYSOUT text.

## Quirks
- **Q-CBCUS01C-01**: Each record is displayed twice (debug DISPLAY left active in the read paragraph plus
  the main-loop DISPLAY). Recommendation: decide. Source: `app/cbl/CBCUS01C.cbl:96`, `app/cbl/CBCUS01C.cbl:78`.
- **Q-CBCUS01C-02**: The full customer record, including SSN, government ID, date of birth, phone numbers,
  and EFT account ID, is written to SYSOUT twice per customer, against rule CD070 and
  `docs/data-handling.md`. Recommendation: fix (never print SSN/govt ID; mask or omit PII).
  Source: `app/cbl/CBCUS01C.cbl:78`, `app/cbl/CBCUS01C.cbl:96`.
- **Q-CBCUS01C-03**: The READ error message says `CUSTOMER FILE` while the open message says `CUSTFILE`;
  inconsistent wording only. Recommendation: preserve or normalize (decide). Source: `app/cbl/CBCUS01C.cbl:110`, `app/cbl/CBCUS01C.cbl:129`.
- **Q-CBCUS01C-04**: JOB-READCUST has `NOTIFY=&SYUID` (typo for `&SYSUID`), so the TSO notification
  symbol is not resolved. Recommendation: fix in the job (not in the program). Source: `app/jcl/READCUST.jcl:2`.
- **Q-CBCUS01C-05**: Off z/OS the abend path fails with `libcob: error: module 'CEE3ABD' not found`
  (exit code 1). Recommendation: decide. Source: `app/cbl/CBCUS01C.cbl:154-158`.

## Test scenarios
Sample data: `app/data/ASCII/custdata.txt` (50 records, IDs `000000001`-`000000050`; customer 1 is
Immanuel Madeline Kessler, Altenwerthshire NC USA 12546).

- **TS-CBCUS01C-01** (covers BR-CBCUS01C-01, BR-CBCUS01C-02, Q-CBCUS01C-01): Given the sample customer
  file, when READCUST runs, then SYSOUT shows the start message, 100 record lines (each customer twice,
  ascending ID, the first two starting `000000001Immanuel`), the end message, and RC 0.
- **TS-CBCUS01C-02** (covers BR-CBCUS01C-03): Given an empty customer KSDS, when the program runs, then only
  the start and end messages appear.
- **TS-CBCUS01C-03** (covers ERR-CBCUS01C-01, ERR-CBCUS01C-04): Given DD CUSTFILE is missing (status `35`),
  then the output is `ERROR OPENING CUSTFILE`, `FILE STATUS IS: NNNN0035`, `ABENDING PROGRAM`, abend U999.
- **TS-CBCUS01C-04** (covers ERR-CBCUS01C-02): Given a read returns status `30` (permanent I/O error),
  then the output is `ERROR READING CUSTOMER FILE`, `FILE STATUS IS: NNNN0030`,
  `ABENDING PROGRAM`, abend U999.

## Open questions
- Is a PII-free customer listing still required in the target, and who consumes it?
