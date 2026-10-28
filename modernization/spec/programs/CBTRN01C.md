# CBTRN01C: Verify each daily transaction's card and account against the master files (read-only dry run)

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBTRN01C.cbl` (494 lines) |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | none: no JCL member, CA7 or Control-M entry runs it (see Open questions) |
| BMS map | none |
| Copybooks | `CVTRA06Y` (DALYTRAN-RECORD, 350), `CVCUS01Y` (CUSTOMER-RECORD, 500), `CVACT03Y` (CARD-XREF-RECORD, 50), `CVACT02Y` (CARD-RECORD, 150), `CVACT01Y` (ACCOUNT-RECORD, 300), `CVTRA05Y` (TRAN-RECORD, 350) |
| Calls / XCTL targets | `CEE3ABD` (abend, only on error) |
| Data stores | DD `DALYTRAN` (sequential, 350 bytes): Read sequentially. DD `XREFFILE` (KSDS card xref, key card number X(16)): Read by key. DD `ACCTFILE` (KSDS account, key ACCT-ID 9(11)): Read by key. DD `CUSTFILE` (KSDS customer, key 9(9)), DD `CARDFILE` (KSDS card, key X(16)), DD `TRANFILE` (KSDS transaction, key X(16)): opened INPUT and closed, never read |

## Purpose
CBTRN01C reads the daily transaction file and, for each transaction, checks that the card number exists
in the card cross-reference file and that the linked account exists in the account master. It only
reports the outcome of each lookup on SYSOUT; it writes no file and changes no data. It looks like an
early version or a dry-run of the posting program CBTRN02C (which applies the same lookups and then posts).

## Invocation and state
- No JCL member executes CBTRN01C. A job would need DDs `DALYTRAN`, `CUSTFILE`, `XREFFILE`, `CARDFILE`,
  `ACCTFILE`, `TRANFILE` (the same datasets as JOB-POSTTRAN plus customer and card).
- No PARM, no SYSIN.
- Working-storage state: END-OF-DAILY-TRANS-FILE flag; WS-XREF-READ-STATUS and WS-ACCT-READ-STATUS
  (0 = found, 4 = not found), reset to 0 before each lookup; DALYTRAN-RECORD keeps the last record read.
- Return code: never set; RC 0 on success; any open, read, or close error ends with user abend U999 via CEE3ABD.

## Processing flow
1. MAIN-PARA: DISPLAY `START OF EXECUTION OF PROGRAM CBTRN01C`.
2. Open, in order, each INPUT, abending on the first failure: 0000-DALYTRAN-OPEN, 0100-CUSTFILE-OPEN,
   0200-XREFFILE-OPEN, 0300-CARDFILE-OPEN, 0400-ACCTFILE-OPEN, 0500-TRANFILE-OPEN.
3. Repeat until END-OF-DAILY-TRANS-FILE = `Y`:
   1. 1000-DALYTRAN-GET-NEXT: READ the next record INTO DALYTRAN-RECORD. Status `00` continue; `10` set
      END-OF-DAILY-TRANS-FILE = `Y`; other: error and abend (ERR-CBTRN01C-02).
   2. IF not end of file: DISPLAY DALYTRAN-RECORD (350 bytes).
   3. Regardless of end of file (Q-CBTRN01C-01): set WS-XREF-READ-STATUS = 0, move DALYTRAN-CARD-NUM to
      XREF-CARD-NUM, PERFORM 2000-LOOKUP-XREF.
   4. IF WS-XREF-READ-STATUS = 0: set WS-ACCT-READ-STATUS = 0, move XREF-ACCT-ID to ACCT-ID, PERFORM
      3000-READ-ACCOUNT; IF WS-ACCT-READ-STATUS not 0, DISPLAY `ACCOUNT ` + ACCT-ID + ` NOT FOUND`.
      OTHERWISE DISPLAY `CARD NUMBER ` + DALYTRAN-CARD-NUM + ` COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-`
      + DALYTRAN-ID.
4. Close, in order: 9000-DALYTRAN-CLOSE, 9100-CUSTFILE-CLOSE, 9200-XREFFILE-CLOSE, 9300-CARDFILE-CLOSE,
   9400-ACCTFILE-CLOSE, 9500-TRANFILE-CLOSE (abend on the first failure).
5. DISPLAY `END OF EXECUTION OF PROGRAM CBTRN01C`, GOBACK.

## Rules
### Business rules
- **BR-CBTRN01C-01**: Every daily transaction read is echoed to SYSOUT as its raw 350-byte record before
  its lookups. Source: `app/cbl/CBTRN01C.cbl:164-169 (MAIN-PARA)`.
- **BR-CBTRN01C-02**: The card number of the transaction (DALYTRAN-CARD-NUM) is looked up by key in the
  cross-reference file. IF found, the program displays `SUCCESSFUL READ OF XREF`, `CARD NUMBER: ` + card
  number, `ACCOUNT ID : ` + XREF-ACCT-ID, `CUSTOMER ID: ` + XREF-CUST-ID.
  Source: `app/cbl/CBTRN01C.cbl:170-172 (MAIN-PARA)`, `app/cbl/CBTRN01C.cbl:227-239 (2000-LOOKUP-XREF)`.
- **BR-CBTRN01C-03**: IF the card is not in the cross-reference (INVALID KEY), the program displays
  `INVALID CARD NUMBER FOR XREF`, sets WS-XREF-READ-STATUS = 4, skips the account lookup, and displays
  `CARD NUMBER ` + card + ` COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-` + transaction ID.
  Source: `app/cbl/CBTRN01C.cbl:231-233 (2000-LOOKUP-XREF)`, `app/cbl/CBTRN01C.cbl:180-184 (MAIN-PARA)`.
- **BR-CBTRN01C-04**: IF the card is found, the account XREF-ACCT-ID is read by key. Found -> display
  `SUCCESSFUL READ OF ACCOUNT FILE`. Not found (INVALID KEY) -> display `INVALID ACCOUNT NUMBER FOUND`,
  set WS-ACCT-READ-STATUS = 4, then display `ACCOUNT ` + ACCT-ID (11 digits) + ` NOT FOUND`.
  Source: `app/cbl/CBTRN01C.cbl:173-179 (MAIN-PARA)`, `app/cbl/CBTRN01C.cbl:241-250 (3000-READ-ACCOUNT)`.
- **BR-CBTRN01C-05**: "Skipping" has no data effect: nothing is written for verified or unverified
  transactions. Source: `app/cbl/CBTRN01C.cbl:154-197 (MAIN-PARA)`.

### Validation rules
- **VR-CBTRN01C-01**: A transaction is "verified" only when its card number exists in the xref file and the
  xref's account exists in the account file. No other field (amount, type, dates, card active status,
  account status, expiry) is checked. Source: `app/cbl/CBTRN01C.cbl:170-184 (MAIN-PARA)`.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBTRN01C-01**: Open status not `00` -> message, status line, `ABENDING PROGRAM`, abend U999.
  Messages in open order: `ERROR OPENING DAILY TRANSACTION FILE`, `ERROR OPENING CUSTOMER FILE`,
  `ERROR OPENING CROSS REF FILE`, `ERROR OPENING CARD FILE`, `ERROR OPENING ACCOUNT FILE`,
  `ERROR OPENING TRANSACTION FILE`. Source: `app/cbl/CBTRN01C.cbl:252-359 (0000-DALYTRAN-OPEN .. 0500-TRANFILE-OPEN)`.
- **ERR-CBTRN01C-02**: DALYTRAN read status other than `00`/`10` -> `ERROR READING DAILY TRANSACTION FILE`,
  status line, `ABENDING PROGRAM`, abend U999. Source: `app/cbl/CBTRN01C.cbl:202-225 (1000-DALYTRAN-GET-NEXT)`.
- **ERR-CBTRN01C-03**: Close status not `00` -> message, status line, abend U999. Messages in close order:
  DALYTRAN -> `ERROR CLOSING CUSTOMER FILE` with the CUSTFILE status (Q-CBTRN01C-02); CUSTFILE ->
  `ERROR CLOSING CUSTOMER FILE`; XREFFILE -> `ERROR CLOSING CROSS REF FILE`; CARDFILE -> `ERROR CLOSING CARD FILE`;
  ACCTFILE -> `ERROR CLOSING ACCOUNT FILE`; TRANFILE -> `ERROR CLOSING TRANSACTION FILE`.
  Source: `app/cbl/CBTRN01C.cbl:361-467 (9000-DALYTRAN-CLOSE .. 9500-TRANFILE-CLOSE)`.
- **ERR-CBTRN01C-04**: Keyed reads handle only INVALID KEY (status `23`). Any other read failure (for
  example `30`, `92`) runs neither the INVALID KEY nor the NOT INVALID KEY branch, so no message is shown,
  the status flag stays 0, and processing continues as if the record were found (Q-CBTRN01C-03).
  Source: `app/cbl/CBTRN01C.cbl:229-239 (2000-LOOKUP-XREF)`, `app/cbl/CBTRN01C.cbl:243-250 (3000-READ-ACCOUNT)`.
- **ERR-CBTRN01C-05**: Status line format and abend: `FILE STATUS IS: NNNN` + (first byte + 3-digit binary
  value of the second byte for non-numeric or `9x` statuses; otherwise `00` + status); CEE3ABD code 999,
  timing 0. Source: `app/cbl/CBTRN01C.cbl:469-489 (Z-ABEND-PROGRAM, Z-DISPLAY-IO-STATUS)`.

## Data effects
None. All six files are opened INPUT. CUSTFILE, CARDFILE, and TRANFILE are opened and closed but never read.

## Quirks
- **Q-CBTRN01C-01**: The lookups run after the end-of-file read too. Because READ INTO does not change
  DALYTRAN-RECORD at end of file, the last transaction's lookups and messages are repeated once (with the
  sample data: 300 records, 301 `SUCCESSFUL READ OF XREF` messages). With an empty input file, the lookup
  runs once with a blank card number and prints `INVALID CARD NUMBER FOR XREF` and
  `CARD NUMBER                  COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-` followed by blanks.
  Recommendation: fix. Source: `app/cbl/CBTRN01C.cbl:164-185 (MAIN-PARA)`.
- **Q-CBTRN01C-02**: 9000-DALYTRAN-CLOSE reports a failure with the text `ERROR CLOSING CUSTOMER FILE` and
  displays CUSTFILE-STATUS instead of DALYTRAN-STATUS (copy-paste error). Recommendation: fix.
  Source: `app/cbl/CBTRN01C.cbl:372-373 (9000-DALYTRAN-CLOSE)`.
- **Q-CBTRN01C-03**: Non-`23` read errors on XREFFILE or ACCTFILE are ignored (ERR-CBTRN01C-04); the
  program then uses stale XREF-ACCT-ID from the previous transaction and reports success.
  Recommendation: fix (check the status after every read). Source: `app/cbl/CBTRN01C.cbl:229-250`.
- **Q-CBTRN01C-04**: CUSTFILE, CARDFILE, and TRANFILE are opened but unused, so the job needs datasets it
  does not use and fails if any is missing. Recommendation: fix (drop them). Source: `app/cbl/CBTRN01C.cbl:158-162`.
- **Q-CBTRN01C-05**: Full card numbers are displayed (`CARD NUMBER: `, the raw transaction record, and the
  "could not be verified" message), against rule CD070. Recommendation: fix (mask, for example 4859****7065).
  Source: `app/cbl/CBTRN01C.cbl:168`, `app/cbl/CBTRN01C.cbl:181`, `app/cbl/CBTRN01C.cbl:236`.
- **Q-CBTRN01C-06**: The program is not scheduled or referenced by any JCL; it appears to be dead code or a
  development predecessor of CBTRN02C. Recommendation: decide (drop, or keep as a pre-posting validation report).
  Source: whole program.

## Test scenarios
Sample data: `app/data/ASCII/dailytran.txt` (300 records), `cardxref.txt`, `acctdata.txt` (50 each).
First transaction `0000000000683580`, card 4859****7065, account `00000000007`, customer `000000007`.
All 300 sample card numbers exist in the xref and all their accounts exist.

- **TS-CBTRN01C-01** (covers BR-CBTRN01C-01, BR-CBTRN01C-02, BR-CBTRN01C-04, Q-CBTRN01C-01): Given the sample
  files, when the program runs, then for transaction `0000000000683580` SYSOUT shows the raw record,
  `SUCCESSFUL READ OF XREF`, `CARD NUMBER: ` + card, `ACCOUNT ID : 00000000007`, `CUSTOMER ID: 000000007`,
  `SUCCESSFUL READ OF ACCOUNT FILE`; overall 301 xref successes (the last record's lookups appear twice) and RC 0.
  (Verified under GnuCOBOL with `-fsign=EBCDIC`.)
- **TS-CBTRN01C-02** (covers BR-CBTRN01C-03): Given a daily transaction `T000000000000001` with card
  `9999999999999999` not in the xref, then SYSOUT shows `INVALID CARD NUMBER FOR XREF` and
  `CARD NUMBER 9999999999999999 COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-T000000000000001`, and no
  account lookup message for it.
- **TS-CBTRN01C-03** (covers BR-CBTRN01C-04): Given an xref record linking card 1111****1111 to account
  `00000099999` that has no account record, then SYSOUT shows `SUCCESSFUL READ OF XREF`, its 3 detail lines,
  `INVALID ACCOUNT NUMBER FOUND`, and `ACCOUNT 00000099999 NOT FOUND`.
- **TS-CBTRN01C-04** (covers Q-CBTRN01C-01): Given an empty DALYTRAN, then SYSOUT shows the start message,
  `INVALID CARD NUMBER FOR XREF`, the "could not be verified" line with blank card and ID, and the end message; RC 0.
- **TS-CBTRN01C-05** (covers ERR-CBTRN01C-01, Q-CBTRN01C-04): Given DD CARDFILE is missing, then SYSOUT shows
  `ERROR OPENING CARD FILE`, `FILE STATUS IS: NNNN0035`, `ABENDING PROGRAM`, abend U999, although the card
  file is never read.

## Open questions
- Is CBTRN01C still used anywhere (operator-run validation, or a predecessor of POSTTRAN)? If not, should the
  target drop it?
- If kept, should it also check the conditions CBTRN02C applies (credit limit, account expiry)?
