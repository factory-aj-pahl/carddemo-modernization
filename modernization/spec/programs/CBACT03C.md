# CBACT03C: List every record of the card cross-reference file to SYSOUT

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBACT03C.cbl` (178 lines) |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | JOB-READXREF, step STEP05 (`app/jcl/READXREF.jcl:22-28`) |
| BMS map | none |
| Copybooks | `CVACT03Y` (CARD-XREF-RECORD, 50 bytes) |
| Calls / XCTL targets | `CEE3ABD` (Language Environment abend service, only on error) |
| Data stores | DD `XREFFILE` = `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` (KSDS, 50-byte records, key XREF-CARD-NUM offset 0 length 16): Browse (OPEN INPUT, sequential READ NEXT) |

## Purpose
CBACT03C is a diagnostic utility that reads the card-to-account cross-reference file from beginning to
end in card-number order and writes each 50-byte record, unformatted, to SYSOUT. It changes no data.
Because of a leftover debug statement, every record is printed twice.

## Invocation and state
- Run as STEP05 of JOB-READXREF with DD `XREFFILE` (DISP=SHR), `SYSOUT`, `SYSPRINT`. No PARM, no SYSIN.
- Working-storage state: END-OF-FILE flag, APPL-RESULT (0 OK, 16 end of file, 12 error, 8 initial).
- Return code: never set; normal end RC 0. I/O errors end with user abend U999 via CEE3ABD.

## Processing flow
1. DISPLAY `START OF EXECUTION OF PROGRAM CBACT03C`.
2. 0000-XREFFILE-OPEN: OPEN INPUT XREFFILE; IF status is not `00`, report and abend (ERR-CBACT03C-01).
3. FOR EACH record until END-OF-FILE = `Y`: PERFORM 1000-XREFFILE-GET-NEXT (READ next INTO CARD-XREF-RECORD).
   - Status `00`: DISPLAY CARD-XREF-RECORD inside the read paragraph, then DISPLAY it again in the main loop.
   - Status `10`: END-OF-FILE = `Y`; nothing displayed.
   - Other status: report and abend (ERR-CBACT03C-02).
4. 9000-XREFFILE-CLOSE: CLOSE; IF status is not `00`, report and abend (ERR-CBACT03C-03).
5. DISPLAY `END OF EXECUTION OF PROGRAM CBACT03C`, GOBACK.

The status formatting (9910-DISPLAY-IO-STATUS) and abend (9999-ABEND-PROGRAM: `ABENDING PROGRAM`,
CEE3ABD with code 999, timing 0) are identical to CBACT02C (see `CBACT02C.md`, ERR-CBACT02C-04).

## Rules
### Business rules
- **BR-CBACT03C-01**: Records are listed in ascending card-number order. Each record produces two
  identical SYSOUT lines of 50 bytes: XREF-CARD-NUM (16), XREF-CUST-ID (9 digits), XREF-ACCT-ID
  (11 digits), FILLER (14). Source: `app/cbl/CBACT03C.cbl:96 (1000-XREFFILE-GET-NEXT)`,
  `app/cbl/CBACT03C.cbl:74-81 (main procedure)`.
- **BR-CBACT03C-02**: The run is framed by `START OF EXECUTION OF PROGRAM CBACT03C` and
  `END OF EXECUTION OF PROGRAM CBACT03C`. Source: `app/cbl/CBACT03C.cbl:71`, `app/cbl/CBACT03C.cbl:85`.
- **BR-CBACT03C-03**: Read status `10` ends the loop with no output for that read. Source:
  `app/cbl/CBACT03C.cbl:98-99`, `app/cbl/CBACT03C.cbl:107-108 (1000-XREFFILE-GET-NEXT)`.

### Validation rules
None.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBACT03C-01**: OPEN status not `00` -> `ERROR OPENING XREFFILE`, `FILE STATUS IS: NNNN` + status
  (4 characters, formatted as in ERR-CBACT03C-04), `ABENDING PROGRAM`, abend U999.
  Source: `app/cbl/CBACT03C.cbl:118-134 (0000-XREFFILE-OPEN)`.
- **ERR-CBACT03C-02**: READ status not `00`/`10` -> `ERROR READING XREFFILE`, status line,
  `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBACT03C.cbl:100-114 (1000-XREFFILE-GET-NEXT)`.
- **ERR-CBACT03C-03**: CLOSE status not `00` -> `ERROR CLOSING XREFFILE`, status line,
  `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBACT03C.cbl:136-152 (9000-XREFFILE-CLOSE)`.
- **ERR-CBACT03C-04**: Status formatting: IF the status is non-numeric or begins with `9`, display the
  first byte plus the 3-digit unsigned binary value of the second byte (EBCDIC `93` -> `9243`, ASCII
  `93` -> `9051`); OTHERWISE `00` + the two digits (for example `0035`). Abend: CALL CEE3ABD with 999
  and timing 0. Source: `app/cbl/CBACT03C.cbl:154-174 (9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS)`.

## Data effects
None. Input-only access; output is SYSOUT text.

## Quirks
- **Q-CBACT03C-01**: Each record is displayed twice: once by the uncommented `DISPLAY CARD-XREF-RECORD`
  in the read paragraph (commented out in the sibling CBACT02C) and once in the main loop. Output line
  count is 2 x records + 2. Recommendation: decide (fix if nobody parses the output; preserve if a
  downstream comparison depends on it). Source: `app/cbl/CBACT03C.cbl:96`, `app/cbl/CBACT03C.cbl:78`.
- **Q-CBACT03C-02**: Full 16-digit card numbers are written to SYSOUT, against rule CD070.
  Recommendation: fix (mask, for example `0500****5740`). Source: `app/cbl/CBACT03C.cbl:78`, `app/cbl/CBACT03C.cbl:96`.
- **Q-CBACT03C-03**: Off z/OS the abend path fails with `libcob: error: module 'CEE3ABD' not found`
  (exit code 1) instead of U999. Recommendation: decide. Source: `app/cbl/CBACT03C.cbl:154-158`.

## Test scenarios
Sample data: `app/data/ASCII/cardxref.txt` (50 records; first record card 0500****5740, customer
`000000050`, account `00000000050`).

- **TS-CBACT03C-01** (covers BR-CBACT03C-01, BR-CBACT03C-02, Q-CBACT03C-01): Given the 50-record sample
  xref file, when READXREF runs, then SYSOUT has the start message, 100 record lines (each record twice in
  a row, ascending card order), the end message, and RC 0.
- **TS-CBACT03C-02** (covers BR-CBACT03C-03): Given an empty xref KSDS, when the program runs, then only
  the start and end messages appear and RC is 0.
- **TS-CBACT03C-03** (covers ERR-CBACT03C-01, ERR-CBACT03C-04): Given the XREFFILE dataset is missing
  (status `35`), when the program runs, then the output is `ERROR OPENING XREFFILE`,
  `FILE STATUS IS: NNNN0035`, `ABENDING PROGRAM`, and abend U999.
- **TS-CBACT03C-04** (covers ERR-CBACT03C-02): Given a read returns status `30` (permanent I/O error),
  when the program runs, then the output is `ERROR READING XREFFILE`, `FILE STATUS IS: NNNN0030`,
  `ABENDING PROGRAM`, abend U999.

## Open questions
- Is the double listing relied upon by anyone (Q-CBACT03C-01)?
