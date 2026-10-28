# CBACT02C: List every record of the card master file to SYSOUT

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBACT02C.cbl` (178 lines) |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | JOB-READCARD, step STEP05 (`app/jcl/READCARD.jcl:22-28`) |
| BMS map | none |
| Copybooks | `CVACT02Y` (CARD-RECORD, 150 bytes) |
| Calls / XCTL targets | `CEE3ABD` (Language Environment abend service, only on error) |
| Data stores | DD `CARDFILE` = `AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS` (KSDS, 150-byte records, key CARD-NUM offset 0 length 16): Browse (OPEN INPUT, sequential READ NEXT from the first key) |

## Purpose
CBACT02C is a diagnostic utility that reads the card master file from beginning to end in key order
and writes each 150-byte card record, unformatted, to SYSOUT. It changes no data. It is used to
verify the contents of the card file after a load.

## Invocation and state
- Run as STEP05 of JOB-READCARD with DD `CARDFILE` (DISP=SHR), `SYSOUT`, and `SYSPRINT`. No PARM and no
  SYSIN are read.
- Working-storage state: END-OF-FILE flag (`N` until status `10`), APPL-RESULT (0 = OK, 16 = end of
  file, 12 = error, 8 = initial value before an open or close).
- Return code: the program never sets RETURN-CODE, so a normal run ends with RC 0. Any I/O error ends
  in a user abend U999 through CEE3ABD (ERR-CBACT02C-01..03).

## Processing flow
1. DISPLAY `START OF EXECUTION OF PROGRAM CBACT02C`.
2. 0000-CARDFILE-OPEN: OPEN INPUT CARDFILE. IF the status is not `00`, report and abend (ERR-CBACT02C-01).
3. FOR EACH record until END-OF-FILE = `Y`: PERFORM 1000-CARDFILE-GET-NEXT, which reads the next record
   into CARD-RECORD.
   - Status `00`: APPL-RESULT 0; the main loop DISPLAYs CARD-RECORD (all 150 bytes as one line).
   - Status `10`: set END-OF-FILE to `Y`; nothing is displayed.
   - Any other status: report and abend (ERR-CBACT02C-02).
4. 9000-CARDFILE-CLOSE: CLOSE CARDFILE. IF the status is not `00`, report and abend (ERR-CBACT02C-03).
5. DISPLAY `END OF EXECUTION OF PROGRAM CBACT02C` and GOBACK.

Shared error routines:
- 9910-DISPLAY-IO-STATUS formats the two-byte file status as four characters after the literal
  `FILE STATUS IS: NNNN`. IF the status is not numeric or its first byte is `9` (a VSAM/implementor
  status such as `93`), the output is the first status byte followed by the 3-digit decimal value of the
  second status byte read as an unsigned binary number. OTHERWISE the output is `00` followed by the two
  status digits. Examples: status `35` displays `FILE STATUS IS: NNNN0035`; status `93` displays
  `FILE STATUS IS: NNNN9243` on z/OS (EBCDIC `3` = X'F3' = 243) and `FILE STATUS IS: NNNN9051` under an
  ASCII runtime (`3` = X'33' = 51).
- 9999-ABEND-PROGRAM displays `ABENDING PROGRAM` and calls CEE3ABD with ABCODE = 999 and TIMING = 0,
  which ends the step immediately with user abend code U999 (no cleanup, files not closed by the program).

## Rules
### Business rules
- **BR-CBACT02C-01**: Records are listed in ascending card-number (primary key) order, one DISPLAY line
  per record, containing the whole 150-byte CARD-RECORD with no formatting or separators: CARD-NUM (16),
  CARD-ACCT-ID (11 digits), CARD-CVV-CD (3 digits), CARD-EMBOSSED-NAME (50), CARD-EXPIRAION-DATE (10),
  CARD-ACTIVE-STATUS (1), FILLER (59). Source: `app/cbl/CBACT02C.cbl:74-81 (main procedure)`,
  `app/cbl/CBACT02C.cbl:92-116 (1000-CARDFILE-GET-NEXT)`.
- **BR-CBACT02C-02**: The run is framed by the messages `START OF EXECUTION OF PROGRAM CBACT02C` and
  `END OF EXECUTION OF PROGRAM CBACT02C`. Source: `app/cbl/CBACT02C.cbl:71`, `app/cbl/CBACT02C.cbl:85`.
- **BR-CBACT02C-03**: Read status `10` (end of file) ends the loop without displaying anything; an empty
  file produces only the start and end messages. Source: `app/cbl/CBACT02C.cbl:98-99`,
  `app/cbl/CBACT02C.cbl:107-108 (1000-CARDFILE-GET-NEXT)`.

### Validation rules
None. Record content is not checked.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBACT02C-01**: OPEN INPUT status other than `00` -> DISPLAY `ERROR OPENING CARDFILE`, then the
  formatted status (`FILE STATUS IS: NNNN` + 4 characters), then `ABENDING PROGRAM`, then abend U999.
  Source: `app/cbl/CBACT02C.cbl:118-134 (0000-CARDFILE-OPEN)`, `app/cbl/CBACT02C.cbl:154-174`.
- **ERR-CBACT02C-02**: READ status other than `00` and `10` -> DISPLAY `ERROR READING CARDFILE`, the
  formatted status, `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBACT02C.cbl:100-114 (1000-CARDFILE-GET-NEXT)`.
- **ERR-CBACT02C-03**: CLOSE status other than `00` -> DISPLAY `ERROR CLOSING CARDFILE`, the formatted
  status, `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBACT02C.cbl:136-152 (9000-CARDFILE-CLOSE)`.
- **ERR-CBACT02C-04**: Status formatting rule used by ERR-CBACT02C-01..03, as described under Processing
  flow (non-numeric or `9x` status -> first byte + 3-digit binary value of the second byte; otherwise
  `00` + status). Source: `app/cbl/CBACT02C.cbl:161-174 (9910-DISPLAY-IO-STATUS)`.

## Data effects
None. The card file is opened INPUT only. Output is SYSOUT text only.

## Quirks
- **Q-CBACT02C-01**: Every card record is printed in full, including the 16-digit card number and the
  3-digit CVV code (CARD-CVV-CD), to SYSOUT. This violates repository rule CD070 and PCI DSS (CVV must
  never be stored or printed after authorization). Recommendation: fix (mask the card number, for
  example `0500****5740`, and never output the CVV). Source: `app/cbl/CBACT02C.cbl:78`.
- **Q-CBACT02C-02**: The status display prefix is the literal `FILE STATUS IS: NNNN` followed by the
  value, so a status `35` appears as `FILE STATUS IS: NNNN0035` (the `NNNN` is part of the text, not a
  placeholder). The binary decoding of `9x` statuses depends on the code page (EBCDIC vs ASCII).
  Recommendation: decide (keep the literal only if operators parse it). Source: `app/cbl/CBACT02C.cbl:168`, `app/cbl/CBACT02C.cbl:172`.
- **Q-CBACT02C-03**: APPL-RESULT is primed to 8 before OPEN and CLOSE, and the CLOSE paragraph uses
  `ADD 8 TO ZERO GIVING` / `SUBTRACT APPL-RESULT FROM APPL-RESULT` to produce 0 or 12. This is only
  obscure style; the outcome is the same as a direct assignment. Recommendation: preserve behavior,
  simplify code. Source: `app/cbl/CBACT02C.cbl:119`, `app/cbl/CBACT02C.cbl:137-143`.
- **Q-CBACT02C-04**: The abend path relies on CEE3ABD (Language Environment). Off z/OS (GnuCOBOL) the
  module does not exist; the run stops with `libcob: error: module 'CEE3ABD' not found` and exit code 1
  instead of U999. Recommendation: decide (the target should end with a distinct non-zero exit code).
  Source: `app/cbl/CBACT02C.cbl:154-158 (9999-ABEND-PROGRAM)`.
- **Q-CBACT02C-05**: The outer loop tests `END-OF-FILE = 'N'` inside `PERFORM UNTIL END-OF-FILE = 'Y'`,
  which is redundant. Recommendation: preserve (no effect). Source: `app/cbl/CBACT02C.cbl:74-81`.

## Test scenarios
Sample data: `app/data/ASCII/carddata.txt` (50 records, first key 0500****5740 for account 00000000050).

- **TS-CBACT02C-01** (covers BR-CBACT02C-01, BR-CBACT02C-02): Given the 50-record sample card file, when
  READCARD runs, then SYSOUT shows the start message, 50 record lines in ascending card-number order (the
  first beginning with card 0500****5740 followed by account `00000000050`), the end message, and RC 0.
- **TS-CBACT02C-02** (covers BR-CBACT02C-03): Given an empty card KSDS, when the program runs, then only
  the start and end messages are written and RC is 0.
- **TS-CBACT02C-03** (covers ERR-CBACT02C-01, ERR-CBACT02C-04): Given DD CARDFILE points to a missing
  dataset (open status `35`), when the program runs, then SYSOUT shows `ERROR OPENING CARDFILE`,
  `FILE STATUS IS: NNNN0035`, `ABENDING PROGRAM`, and the step ends with abend U999.
- **TS-CBACT02C-04** (covers ERR-CBACT02C-02, ERR-CBACT02C-04): Given a read fails with status `93`
  (resource unavailable), when the program runs, then SYSOUT shows `ERROR READING CARDFILE`,
  `FILE STATUS IS: NNNN9243` (EBCDIC host), `ABENDING PROGRAM`, abend U999.
- **TS-CBACT02C-05** (covers Q-CBACT02C-01): Given the sample data, when the program runs, then the
  output for the first record contains the CVV `747` in positions 28-30 (documenting the current
  exposure; the target must not reproduce it).

## Open questions
- Is this utility needed in the target at all, or is it replaced by a query or export tool?
- Which output format do operators rely on (raw record vs. formatted)? Any replacement must mask PAN and drop CVV.
