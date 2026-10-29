# CBACT04C: Monthly interest calculation from transaction-category balances, writing interest transactions and resetting account cycle totals

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBACT04C.cbl` (652 lines) |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | JOB-INTCALC, step STEP15, `PARM='2022071800'` (`app/jcl/INTCALC.jcl:22-41`) |
| BMS map | none |
| Copybooks | `CVTRA01Y` (TRAN-CAT-BAL-RECORD, 50), `CVACT03Y` (CARD-XREF-RECORD, 50), `CVTRA02Y` (DIS-GROUP-RECORD, 50), `CVACT01Y` (ACCOUNT-RECORD, 300), `CVTRA05Y` (TRAN-RECORD, 350) |
| Calls / XCTL targets | `CEE3ABD` (abend, only on error) |
| Data stores | DD `TCATBALF` = `AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS` (KSDS, key account 9(11) + type X(2) + category 9(4)): Browse (sequential from first key). DD `XREFFILE` = `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` (random; Read by alternate key FD-XREF-ACCT-ID, offset 25 length 11, through the AIX path on DD `XREFFIL1` = `AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH`). DD `ACCTFILE` = `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` (random, OPEN I-O): Read by key, Rewrite. DD `DISCGRP` = `AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS` (random, key group X(10) + type X(2) + category 9(4)): Read by key. DD `TRANSACT` = `AWS.M2.CARDDEMO.SYSTRAN(+1)` (new GDG generation, RECFM=F, LRECL 350): Write |

## Purpose
CBACT04C runs the monthly interest cycle. It reads every transaction-category balance, grouped by account,
looks up the annual interest rate for the account's disclosure group, transaction type, and category (falling
back to the `DEFAULT` group), and computes one month's interest for each balance with a non-zero rate. Each
interest amount is written as a system-generated transaction (type `01`, category `0005`) to a new SYSTRAN
generation, and the sum of the account's interest is added to the account's current balance while the cycle
credit and debit totals are reset to zero. Fee computation is a placeholder that does nothing.

## Invocation and state
- JOB-INTCALC STEP15 with `PARM='2022071800'`. The PARM arrives in the LINKAGE SECTION as PARM-LENGTH (S9(4) COMP)
  followed by PARM-DATE (X(10)); only PARM-DATE is used, as the first 10 characters of every generated transaction ID.
  PARM-LENGTH is never checked.
- DDs: TCATBALF, XREFFILE, XREFFIL1 (AIX path used by the alternate-key reads of XREFFILE), ACCTFILE, DISCGRP (all
  DISP=SHR), TRANSACT DISP=(NEW,CATLG,DELETE) as SYSTRAN(+1).
- Working-storage state across records: WS-LAST-ACCT-NUM (X(11), initially spaces), WS-FIRST-TIME (`Y` until the
  first account), WS-TOTAL-INT (S9(9)V99, interest accumulated for the current account), WS-MONTHLY-INT
  (S9(9)V99), WS-RECORD-COUNT (9(9), counted but never displayed), WS-TRANID-SUFFIX (9(6), starts 0, never reset),
  ACCOUNT-RECORD and CARD-XREF-RECORD of the current account.
- Return code: never set (RC 0). Any file error ends in abend U999 via CEE3ABD.

## Processing flow
1. DISPLAY `START OF EXECUTION OF PROGRAM CBACT04C`.
2. Open, in order, abending on the first failure (ERR-CBACT04C-01): TCATBALF INPUT, XREFFILE INPUT, DISCGRP INPUT,
   ACCTFILE I-O, TRANSACT OUTPUT.
3. FOR EACH category-balance record in key order (1000-TCATBALF-GET-NEXT; `10` = end; other non-`00` abends):
   1. Add 1 to WS-RECORD-COUNT; DISPLAY TRAN-CAT-BAL-RECORD (raw 50 bytes).
   2. IF TRANCAT-ACCT-ID differs from WS-LAST-ACCT-NUM (a new account starts):
      1. IF this is not the first account, PERFORM 1050-UPDATE-ACCOUNT for the previous account.
         OTHERWISE set WS-FIRST-TIME = `N`.
      2. WS-TOTAL-INT = 0; WS-LAST-ACCT-NUM = TRANCAT-ACCT-ID.
      3. 1100-GET-ACCT-DATA: read ACCTFILE by TRANCAT-ACCT-ID (abend if not found).
      4. 1110-GET-XREF-DATA: read XREFFILE by alternate key account ID (abend if not found).
   3. Build the disclosure key: ACCT-GROUP-ID of the current account + TRANCAT-TYPE-CD + TRANCAT-CD, and PERFORM
      1200-GET-INTEREST-RATE (falls back to group `DEFAULT` when the key is missing).
   4. IF DIS-INT-RATE is not 0: PERFORM 1300-COMPUTE-INTEREST (compute, accumulate, and write the interest
      transaction) and 1400-COMPUTE-FEES (empty).
4. At end of file the loop ends. The ELSE branch meant to update the last account is never reached (Q-CBACT04C-01),
   so the last account is not updated.
5. Close, in order: TCATBALF, XREFFILE, DISCGRP, ACCTFILE, TRANSACT (abend on failure).
6. DISPLAY `END OF EXECUTION OF PROGRAM CBACT04C`, GOBACK.

## Rules
### Business rules
- **BR-CBACT04C-01**: Category balances are processed in ascending key order (account, type, category). An
  account's group of records is contiguous; an account change is detected by comparing the 11-digit account of the
  record with the previous one. Source: `app/cbl/CBACT04C.cbl:188-206 (main procedure)`.
- **BR-CBACT04C-02**: Rate lookup key = ACCT-GROUP-ID (X(10)) + TRANCAT-TYPE-CD + TRANCAT-CD. IF the key is not
  found (status `23`): DISPLAY `DISCLOSURE GROUP RECORD MISSING` and `TRY WITH DEFAULT GROUP CODE`, then read again
  with group `DEFAULT` (padded to 10 characters) and the same type and category. The rate is DIS-INT-RATE, an annual
  percentage S9(4)V99 (for example `00150{` = 15.00). Source: `app/cbl/CBACT04C.cbl:210-213 (main procedure)`,
  `app/cbl/CBACT04C.cbl:415-440 (1200-GET-INTEREST-RATE)`, `app/cbl/CBACT04C.cbl:443-460 (1200-A-GET-DEFAULT-INT-RATE)`.
- **BR-CBACT04C-03**: IF the rate is 0, no interest transaction is written and nothing is accumulated for that
  category. Source: `app/cbl/CBACT04C.cbl:214-217 (main procedure)`.
- **BR-CBACT04C-04**: Monthly interest = (TRAN-CAT-BAL x DIS-INT-RATE) / 1200, stored in WS-MONTHLY-INT S9(9)V99
  with truncation toward zero after the second decimal (no ROUNDED) and silent loss of high-order digits beyond 9
  integer digits (no ON SIZE ERROR). Negative balances give negative interest. Examples: 1164.87 x 15.00 / 1200 =
  14.560875 -> 14.56; -1234.56 x 15.00 / 1200 = -15.432 -> -15.43; 0.50 x 15.00 / 1200 = 0.00625 -> 0.00 (a zero-amount
  transaction is still written). Source: `app/cbl/CBACT04C.cbl:462-470 (1300-COMPUTE-INTEREST)`.
- **BR-CBACT04C-05**: WS-TOTAL-INT accumulates the monthly interest of all non-zero-rate categories of the current
  account. Source: `app/cbl/CBACT04C.cbl:467 (1300-COMPUTE-INTEREST)`, `app/cbl/CBACT04C.cbl:200`.
- **BR-CBACT04C-06**: Interest transaction (350 bytes), one per category with a non-zero rate:
  TRAN-ID = PARM-DATE (10) + WS-TRANID-SUFFIX (6 digits, incremented before use, continuous over the whole run; for
  example `2022071800000001`); TRAN-TYPE-CD `01`; TRAN-CAT-CD `0005`; TRAN-SOURCE `System` (padded to 10);
  TRAN-DESC = `Int. for a/c ` + ACCT-ID (11 digits), the remaining 76 bytes not set (Q-CBACT04C-06); TRAN-AMT =
  monthly interest; TRAN-MERCHANT-ID 0; merchant name, city, ZIP spaces; TRAN-CARD-NUM = XREF-CARD-NUM of the card
  found by the account alternate key; TRAN-ORIG-TS = TRAN-PROC-TS = current timestamp `YYYY-MM-DD-HH.MM.SS.hh0000`
  (taken once per transaction). Source: `app/cbl/CBACT04C.cbl:473-515 (1300-B-WRITE-TX)`,
  `app/cbl/CBACT04C.cbl:613-626 (Z-GET-DB2-FORMAT-TIMESTAMP)`.
- **BR-CBACT04C-07**: Account update when the next account starts: ACCT-CURR-BAL = ACCT-CURR-BAL + WS-TOTAL-INT;
  ACCT-CURR-CYC-CREDIT = 0; ACCT-CURR-CYC-DEBIT = 0; REWRITE. This happens for every account that has at least one
  category-balance record, even when all its rates are 0 (interest 0, cycle totals still reset), except the last
  account in the file (Q-CBACT04C-01). Accounts without category-balance records are not touched.
  Source: `app/cbl/CBACT04C.cbl:194-199 (main procedure)`, `app/cbl/CBACT04C.cbl:350-370 (1050-UPDATE-ACCOUNT)`.
- **BR-CBACT04C-08**: Category balances themselves are not changed (TCATBALF is INPUT); they are not reset after
  interest. Source: `app/cbl/CBACT04C.cbl:234-236 (0000-TCATBALF-OPEN)`.
- **BR-CBACT04C-09**: Fees are not computed: 1400-COMPUTE-FEES contains only the comment `To be implemented`.
  Source: `app/cbl/CBACT04C.cbl:518-520 (1400-COMPUTE-FEES)`.

### Validation rules
None beyond the existence checks under Error handling.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBACT04C-01**: Open failure (status not `00`) -> message, status line, `ABENDING PROGRAM`, abend U999.
  Messages: TCATBALF `ERROR OPENING TRANSACTION CATEGORY BALANCE`; XREFFILE `ERROR OPENING CROSS REF FILE` + the
  2-byte status on the same line; DISCGRP `ERROR OPENING DALY REJECTS FILE` (wrong text, Q-CBACT04C-04); ACCTFILE
  `ERROR OPENING ACCOUNT MASTER FILE`; TRANSACT `ERROR OPENING TRANSACTION FILE`.
  Source: `app/cbl/CBACT04C.cbl:234-323 (0000-TCATBALF-OPEN .. 0400-TRANFILE-OPEN)`.
- **ERR-CBACT04C-02**: TCATBALF read status not `00`/`10` -> `ERROR READING TRANSACTION CATEGORY FILE`, status line,
  abend U999. Source: `app/cbl/CBACT04C.cbl:325-348 (1000-TCATBALF-GET-NEXT)`.
- **ERR-CBACT04C-03**: Account not found -> `ACCOUNT NOT FOUND: ` + the 11-digit key, then (because the status is
  `23`, not `00`) `ERROR READING ACCOUNT FILE`, status line `FILE STATUS IS: NNNN0023`, abend U999. Any other non-`00`
  status -> `ERROR READING ACCOUNT FILE`, status line, abend. Source: `app/cbl/CBACT04C.cbl:372-391 (1100-GET-ACCT-DATA)`.
- **ERR-CBACT04C-04**: No card for the account in the cross-reference (alternate key) -> `ACCOUNT NOT FOUND: ` + key,
  then `ERROR READING XREF FILE`, status line, abend U999. Any status other than `00` (including `02`, "more cards
  with this account exist") also abends with `ERROR READING XREF FILE` (Q-CBACT04C-03).
  Source: `app/cbl/CBACT04C.cbl:393-413 (1110-GET-XREF-DATA)`.
- **ERR-CBACT04C-05**: Disclosure read status other than `00`/`23` -> `ERROR READING DISCLOSURE GROUP FILE`, status line,
  abend. DEFAULT-group read status not `00` -> `ERROR READING DEFAULT DISCLOSURE GROUP`, status line, abend.
  Source: `app/cbl/CBACT04C.cbl:422-435 (1200-GET-INTEREST-RATE)`, `app/cbl/CBACT04C.cbl:446-459 (1200-A-GET-DEFAULT-INT-RATE)`.
- **ERR-CBACT04C-06**: Account REWRITE status not `00` -> `ERROR RE-WRITING ACCOUNT FILE`, status line, abend U999.
  Source: `app/cbl/CBACT04C.cbl:356-369 (1050-UPDATE-ACCOUNT)`.
- **ERR-CBACT04C-07**: Interest transaction WRITE status not `00` -> `ERROR WRITING TRANSACTION RECORD`, status line,
  abend U999. Source: `app/cbl/CBACT04C.cbl:500-514 (1300-B-WRITE-TX)`.
- **ERR-CBACT04C-08**: Close failure -> message, status line, abend: `ERROR CLOSING TRANSACTION BALANCE FILE`,
  `ERROR CLOSING CROSS REF FILE`, `ERROR CLOSING DISCLOSURE GROUP FILE`, `ERROR CLOSING ACCOUNT FILE`,
  `ERROR CLOSING TRANSACTION FILE`. Source: `app/cbl/CBACT04C.cbl:522-611 (9000-TCATBALF-CLOSE .. 9400-TRANFILE-CLOSE)`.
- **ERR-CBACT04C-09**: Status line `FILE STATUS IS: NNNN` + 4 characters (first byte + 3-digit binary of the second byte
  for non-numeric or `9x`; otherwise `00` + status); CEE3ABD code 999 timing 0.
  Source: `app/cbl/CBACT04C.cbl:628-648 (9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS)`.

## Data effects
- SYSTRAN(+1): one interest transaction per category balance with a non-zero rate, in category-balance key order.
- ACCTFILE: for each account with category balances except the last one: ACCT-CURR-BAL += total interest,
  ACCT-CURR-CYC-CREDIT = 0, ACCT-CURR-CYC-DEBIT = 0, rewritten when the next account's first record is read (after
  that account's interest transactions were written).
- TCATBALF, XREFFILE, DISCGRP unchanged. No commit boundary: an abend leaves earlier account rewrites in place, while
  SYSTRAN(+1) is deleted (DISP=(NEW,CATLG,DELETE)), so interest already added to balances has no transaction record.
- The interest transactions are not posted to TCATBALF or to the transaction master by this program; downstream jobs
  (see `jobs.md`, COMBTRAN) merge SYSTRAN with the posted transactions.

## Quirks
- **Q-CBACT04C-01**: The last account in the category-balance file is never updated: its interest transactions are
  written, but its balance does not receive the interest and its cycle totals are not reset. The `ELSE PERFORM
  1050-UPDATE-ACCOUNT` at lines 219-220 belongs to `IF END-OF-FILE = 'N'`, which is always true at that point inside
  `PERFORM UNTIL END-OF-FILE = 'Y'`. Verified under GnuCOBOL: account `00000000050` keeps balance 1945.87 and cycle
  totals although interest transaction `2022071800000050` was written for it. Recommendation: fix (update the last
  account after the loop); keep a parity switch if reconciling against legacy output. Source: `app/cbl/CBACT04C.cbl:188-222`.
- **Q-CBACT04C-02**: Interest is truncated, not rounded, and is charged on negative category balances as negative
  interest (a credit to the customer). Interest uses the category balance, which TCATBALF accumulates indefinitely
  (never reset by this job). Recommendation: decide (confirm rounding and negative-balance policy with the business).
  Source: `app/cbl/CBACT04C.cbl:464-465`.
- **Q-CBACT04C-03**: The cross-reference is read by the alternate key (account ID) declared without `WITH DUPLICATES`,
  but the AIX is defined `NONUNIQUEKEY` (`app/jcl/XREFFILE.jcl:72-75`). If an account has more than one card, the read
  returns status `02` (or the open/read fails, depending on the runtime), which the program treats as an error and
  abends, so no interest is calculated for that account or any account after it. Recommendation: fix (an account
  with several cards must be supported; decide which card number to stamp). Source: `app/cbl/CBACT04C.cbl:34-39`, `app/cbl/CBACT04C.cbl:393-413`.
- **Q-CBACT04C-04**: The DISCGRP open-failure message says `ERROR OPENING DALY REJECTS FILE` (copy-paste from CBTRN02C).
  Recommendation: fix. Source: `app/cbl/CBACT04C.cbl:281`.
- **Q-CBACT04C-05**: All sample accounts have ACCT-GROUP-ID blank (the value `A000000000` sits in ACCT-ADDR-ZIP due to a
  layout shift in the sample data), so every lookup misses and falls back to `DEFAULT`; the sample run prints the
  "missing" message pair 100 times. Recommendation: decide (fix the sample data; in production a blank group should
  probably map to DEFAULT without a warning). Source: `app/cbl/CBACT04C.cbl:210`, `app/data/ASCII/acctdata.txt`.
- **Q-CBACT04C-06**: TRAN-RECORD is never initialized and the STRINGs do not pad: TRAN-DESC bytes 25-100 and the 20-byte
  filler keep their initial working-storage content (spaces under GnuCOBOL, undefined on z/OS). Recommendation: fix
  (write spaces). Source: `app/cbl/CBACT04C.cbl:476-489`.
- **Q-CBACT04C-07**: The cycle totals are reset even for accounts that earn no interest, and the reset only happens for
  accounts that have category balances, which couples the statement cycle reset to the interest job. Recommendation:
  decide. Source: `app/cbl/CBACT04C.cbl:350-354`.
- **Q-CBACT04C-08**: PARM-LENGTH is not checked; a missing or short PARM makes PARM-DATE contain undefined bytes, and the
  generated transaction IDs collide with earlier runs if the same PARM is reused (SYSTRAN is a new generation each time,
  but the IDs are later merged into the transaction master). The suffix allows at most 999,999 transactions per run
  (wraps to 000000 silently). Recommendation: fix (validate the run date, derive it from the business date).
  Source: `app/cbl/CBACT04C.cbl:175-180`, `app/cbl/CBACT04C.cbl:473-480`.
- **Q-CBACT04C-09**: The interest transaction's original timestamp is the processing time, not the cycle date given in
  the PARM. Recommendation: decide. Source: `app/cbl/CBACT04C.cbl:496-498`.
- **Q-CBACT04C-10**: WS-RECORD-COUNT is maintained but never reported; fees are a stub. Recommendation: decide.
  Source: `app/cbl/CBACT04C.cbl:192`, `app/cbl/CBACT04C.cbl:518-520`.
- **Q-CBACT04C-11**: Every category-balance record (account number and balance) is displayed to SYSOUT. Recommendation:
  decide (operational noise; not PCI-sensitive since no card number). Source: `app/cbl/CBACT04C.cbl:193`.

## Test scenarios
Sample data: category balances and accounts as left by POSTTRAN on the sample data (100 category-balance records for
50 accounts), `app/data/ASCII/discgrp.txt` (groups `A000000000`, `DEFAULT`, `ZEROAPR`; DEFAULT rates: type 01
category 0001 15.00, 0002-0004 25.00; types 02, 03, 07 0.00; types 04, 05, 06 15.00), `cardxref.txt`. Results
below were verified under GnuCOBOL built with `-fsign=EBCDIC` using a driver that passes PARM `2022071800`.

- **TS-CBACT04C-01** (covers BR-CBACT04C-02, BR-CBACT04C-04, BR-CBACT04C-06, BR-CBACT04C-07): Given account
  `00000000001` with balance 1288.10, cycle credit 1164.87, cycle debit -70.77, category balances (01, 0001) = 1164.87
  and (03, 0001) = -70.77, and blank group ID, when INTCALC runs, then SYSTRAN's first record is
  `2022071800000001`, type `01`, category `0005`, source `System`, description `Int. for a/c 00000000001`, amount
  +14.56, card 9680****3697, both timestamps equal; no transaction for (03, 0001) (rate 0); and after the run the
  account shows balance 1302.66, cycle credit 0, cycle debit 0.
- **TS-CBACT04C-02** (covers Q-CBACT04C-01): Given the same run, then account `00000000050` (last in the file) is
  unchanged (balance 1945.87, cycle credit 1501.75, cycle debit -47.88) although SYSTRAN contains its interest
  transaction `2022071800000050`.
- **TS-CBACT04C-03** (covers BR-CBACT04C-02, Q-CBACT04C-05): Given the sample run, then SYSOUT contains 100 category
  record lines, each followed by `DISCLOSURE GROUP RECORD MISSING` and `TRY WITH DEFAULT GROUP CODE`, then
  `END OF EXECUTION OF PROGRAM CBACT04C`; SYSTRAN has 50 records; RC 0.
- **TS-CBACT04C-04** (covers BR-CBACT04C-02): Given an account with ACCT-GROUP-ID `A000000000` and a (01, 0002) balance
  of 1000.00, then the group rate 25.00 is used without the missing-group messages and interest = 20.83.
- **TS-CBACT04C-05** (covers BR-CBACT04C-03, BR-CBACT04C-07): Given an account with group `ZEROAPR` and balances in
  several categories, followed by another account, then no interest transaction is written for it, its balance is
  unchanged, and its cycle credit and debit are set to 0.
- **TS-CBACT04C-06** (covers BR-CBACT04C-04): Given a (01, 0001) balance of -1234.56 at 15.00 percent, then the interest
  transaction amount is -15.43; given a balance of 0.50, the amount is 0.00 and the transaction is still written.
- **TS-CBACT04C-07** (covers ERR-CBACT04C-03): Given a category balance for account `00000099999` that is not in the
  account file, then SYSOUT shows `ACCOUNT NOT FOUND: 00000099999`, `ERROR READING ACCOUNT FILE`,
  `FILE STATUS IS: NNNN0023`, `ABENDING PROGRAM`, abend U999.
- **TS-CBACT04C-08** (covers ERR-CBACT04C-05): Given a DISCGRP file without the DEFAULT record for (01, 0001) and an
  account with blank group, then `DISCLOSURE GROUP RECORD MISSING`, `TRY WITH DEFAULT GROUP CODE`,
  `ERROR READING DEFAULT DISCLOSURE GROUP`, `FILE STATUS IS: NNNN0023`, `ABENDING PROGRAM`, abend U999.
- **TS-CBACT04C-09** (covers BR-CBACT04C-01): Given an empty TCATBALF, then only the start and end messages are written,
  SYSTRAN is empty, and no account changes.

## Open questions
- Is Q-CBACT04C-01 (last account not updated) known in production, and do downstream balances depend on it?
- Rounding policy for interest (truncate vs. half-up) and treatment of negative balances (Q-CBACT04C-02).
- Which card number should an interest transaction carry when an account has several cards (Q-CBACT04C-03)?
- Should the cycle credit/debit reset belong to the interest job or to the statement cycle (Q-CBACT04C-07)?
- Fee rules for 1400-COMPUTE-FEES are not specified anywhere in the source.
