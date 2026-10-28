# CBTRN02C: Post the daily transaction file: validate each transaction, update category balances and accounts, write the transaction master, and reject failures

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBTRN02C.cbl` (731 lines) |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | JOB-POSTTRAN, step STEP15 (`app/jcl/POSTTRAN.jcl:23-42`) |
| BMS map | none |
| Copybooks | `CVTRA06Y` (DALYTRAN-RECORD, 350), `CVTRA05Y` (TRAN-RECORD, 350), `CVACT03Y` (CARD-XREF-RECORD, 50), `CVACT01Y` (ACCOUNT-RECORD, 300), `CVTRA01Y` (TRAN-CAT-BAL-RECORD, 50) |
| Calls / XCTL targets | `CEE3ABD` (abend, only on error) |
| Data stores | DD `DALYTRAN` = `AWS.M2.CARDDEMO.DALYTRAN.PS` (sequential 350): Read. DD `XREFFILE` = `AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS` (random, key XREF-CARD-NUM X(16)): Read by key. DD `ACCTFILE` = `AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS` (random, OPEN I-O, key ACCT-ID 9(11)): Read by key, Rewrite. DD `TCATBALF` = `AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS` (random, OPEN I-O, key account 9(11) + type X(2) + category 9(4) = 17 bytes): Read by key, Write, Rewrite. DD `TRANFILE` = `AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS` (random, OPEN OUTPUT, key TRAN-ID X(16)): Write. DD `DALYREJS` = `AWS.M2.CARDDEMO.DALYREJS(+1)` (new GDG generation, RECFM=F, LRECL 430): Write |

## Purpose
CBTRN02C is the nightly posting program. For every transaction in the daily transaction file it checks
that the card is known, that the card's account exists, that the account stays within its credit limit,
and that the account has not expired. Valid transactions are added to the transaction-category balance
of the account, to the account's current balance and cycle credit or debit, and written to the
transaction master file with a processing timestamp. Invalid transactions are written, with a reason
code and description, to a new generation of the daily rejects file. The job ends with return code 4
when anything was rejected.

## Invocation and state
- JOB-POSTTRAN STEP15 (`app/jcl/POSTTRAN.jcl:23-42`). DDs: `TRANFILE`, `DALYTRAN`, `XREFFILE`, `ACCTFILE`,
  `TCATBALF` all DISP=SHR; `DALYREJS` DISP=(NEW,CATLG,DELETE) as a new GDG generation (RECFM=F, LRECL=430).
- No PARM, no SYSIN.
- Working-storage state across records: END-OF-FILE; WS-TRANSACTION-COUNT and WS-REJECT-COUNT
  (PIC 9(9), start 0); WS-VALIDATION-TRAILER (reason 9(4) + description X(76)), reset per record;
  CARD-XREF-RECORD and ACCOUNT-RECORD hold the records read during validation of the current transaction
  and are reused by the posting step.
- Return code: RETURN-CODE = 4 IF WS-REJECT-COUNT > 0, otherwise 0. Any file error ends in user abend U999
  via CEE3ABD (no RC, step abends).

## Processing flow
1. DISPLAY `START OF EXECUTION OF PROGRAM CBTRN02C`.
2. Open, in order, abending on the first failure (ERR-CBTRN02C-01): DALYTRAN INPUT (0000-DALYTRAN-OPEN),
   TRANFILE OUTPUT (0100-TRANFILE-OPEN), XREFFILE INPUT (0200-XREFFILE-OPEN), DALYREJS OUTPUT
   (0300-DALYREJS-OPEN), ACCTFILE I-O (0400-ACCTFILE-OPEN), TCATBALF I-O (0500-TCATBALF-OPEN).
3. FOR EACH daily transaction (1000-DALYTRAN-GET-NEXT; status `10` ends the loop; any other non-`00`
   status abends, ERR-CBTRN02C-02):
   1. Add 1 to WS-TRANSACTION-COUNT; set reason = 0 and description = spaces.
   2. PERFORM 1500-VALIDATE-TRAN:
      1. 1500-A-LOOKUP-XREF: read XREFFILE by DALYTRAN-CARD-NUM. IF not found: reason 100.
      2. IF reason is still 0, 1500-B-LOOKUP-ACCT: read ACCTFILE by XREF-ACCT-ID. IF not found: reason 101.
         IF found: compute WS-TEMP-BAL and apply the credit-limit check (reason 102), then, independently,
         the expiry check (reason 103).
   3. IF reason = 0, PERFORM 2000-POST-TRANSACTION:
      1. Build TRAN-RECORD from the daily record (all fields copied one-to-one, except TRAN-PROC-TS),
         TRAN-PROC-TS = current timestamp from Z-GET-DB2-FORMAT-TIMESTAMP.
      2. 2700-UPDATE-TCATBAL: read TCATBALF by (XREF-ACCT-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD). IF not found,
         display the creation message and 2700-A-CREATE-TCATBAL-REC writes a new record with balance = amount.
         OTHERWISE 2700-B-UPDATE-TCATBAL-REC adds the amount and rewrites.
      3. 2800-UPDATE-ACCOUNT-REC: update balances in ACCOUNT-RECORD and REWRITE it.
      4. 2900-WRITE-TRANSACTION-FILE: WRITE TRAN-RECORD to TRANFILE.
   4. OTHERWISE add 1 to WS-REJECT-COUNT and PERFORM 2500-WRITE-REJECT-REC (430-byte record = the 350-byte
      daily record + 80-byte trailer).
4. Close, in order (abend on the first failure, ERR-CBTRN02C-08): DALYTRAN, TRANFILE, XREFFILE, DALYREJS,
   ACCTFILE, TCATBALF.
5. DISPLAY `TRANSACTIONS PROCESSED :` + count (9 digits), `TRANSACTIONS REJECTED  :` + count (9 digits).
6. IF rejects > 0, RETURN-CODE = 4.
7. DISPLAY `END OF EXECUTION OF PROGRAM CBTRN02C`, GOBACK.

## Rules
### Business rules
- **BR-CBTRN02C-01**: Every record read from DALYTRAN (status `00`) increments WS-TRANSACTION-COUNT, and is
  either posted (reason 0) or rejected (reason non-zero, WS-REJECT-COUNT + 1). Processed = posted + rejected.
  Source: `app/cbl/CBTRN02C.cbl:202-219 (main procedure)`.
- **BR-CBTRN02C-02**: Credit-limit check. WS-TEMP-BAL (S9(9)V99) = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT
  + DALYTRAN-AMT, computed without ROUNDED or ON SIZE ERROR (digits above 9 integer places are lost). IF
  ACCT-CREDIT-LIMIT >= WS-TEMP-BAL the check passes; OTHERWISE reason 102, description `OVERLIMIT TRANSACTION`.
  The account's current balance (ACCT-CURR-BAL) is not used. Source: `app/cbl/CBTRN02C.cbl:403-413 (1500-B-LOOKUP-ACCT)`.
- **BR-CBTRN02C-03**: Expiry check, applied whenever the account was found, after and independent of the
  limit check. IF ACCT-EXPIRAION-DATE (X(10), `YYYY-MM-DD`) >= the first 10 characters of DALYTRAN-ORIG-TS
  (alphanumeric comparison), the check passes; OTHERWISE reason 103, description
  `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`. When both checks fail, 103 overwrites 102 (only one reason is kept).
  Source: `app/cbl/CBTRN02C.cbl:414-420 (1500-B-LOOKUP-ACCT)`.
- **BR-CBTRN02C-04**: Posted transaction record: TRAN-ID, TYPE-CD, CAT-CD, SOURCE, DESC, AMT, MERCHANT-ID,
  MERCHANT-NAME, MERCHANT-CITY, MERCHANT-ZIP, CARD-NUM, ORIG-TS copied unchanged from the daily record;
  TRAN-PROC-TS = processing timestamp (BR-CBTRN02C-05); the 20-byte filler is whatever TRAN-RECORD holds
  (never initialized, see Q-CBTRN02C-09). Source: `app/cbl/CBTRN02C.cbl:424-438 (2000-POST-TRANSACTION)`.
- **BR-CBTRN02C-05**: Processing timestamp format `YYYY-MM-DD-HH.MM.SS.hh0000` (26 characters, Db2 timestamp
  style): date and time from FUNCTION CURRENT-DATE, `hh` = hundredths of a second, followed by the literal
  `0000`. Example: `2022-07-18-23.05.41.370000`. A new timestamp is taken for every posted transaction.
  Source: `app/cbl/CBTRN02C.cbl:692-705 (Z-GET-DB2-FORMAT-TIMESTAMP)`, `app/cbl/CBTRN02C.cbl:149-174`.
- **BR-CBTRN02C-06**: Category balance. Key = XREF-ACCT-ID (account from the cross-reference, not from the
  daily record) + DALYTRAN-TYPE-CD + DALYTRAN-CAT-CD. IF the key does not exist (status `23`), DISPLAY
  `TCATBAL record not found for key : ` + the 17-byte key + `.. Creating.` and WRITE a new record: key as
  above, TRAN-CAT-BAL = 0 + DALYTRAN-AMT, filler spaces (INITIALIZE). OTHERWISE TRAN-CAT-BAL = TRAN-CAT-BAL +
  DALYTRAN-AMT and REWRITE. Source: `app/cbl/CBTRN02C.cbl:467-501 (2700-UPDATE-TCATBAL)`,
  `app/cbl/CBTRN02C.cbl:503-524 (2700-A-CREATE-TCATBAL-REC)`, `app/cbl/CBTRN02C.cbl:526-542 (2700-B-UPDATE-TCATBAL-REC)`.
- **BR-CBTRN02C-07**: Account update. ACCT-CURR-BAL = ACCT-CURR-BAL + DALYTRAN-AMT. IF DALYTRAN-AMT >= 0,
  ACCT-CURR-CYC-CREDIT = ACCT-CURR-CYC-CREDIT + amount; OTHERWISE ACCT-CURR-CYC-DEBIT = ACCT-CURR-CYC-DEBIT +
  amount (the debit total therefore becomes more negative). All other account fields are rewritten unchanged.
  Source: `app/cbl/CBTRN02C.cbl:545-560 (2800-UPDATE-ACCOUNT-REC)`.
- **BR-CBTRN02C-08**: Posting order for one transaction: category balance write/rewrite, then account
  rewrite, then transaction master write. There is no commit or rollback; an abend in a later step leaves
  the earlier updates in place. Source: `app/cbl/CBTRN02C.cbl:440-442 (2000-POST-TRANSACTION)`.
- **BR-CBTRN02C-09**: Reject record (430 bytes) = the 350-byte daily record unchanged + reason code as 4 digits
  + 76-byte description padded with spaces, for example `0102OVERLIMIT TRANSACTION` followed by 55 spaces.
  Source: `app/cbl/CBTRN02C.cbl:446-465 (2500-WRITE-REJECT-REC)`, `app/cbl/CBTRN02C.cbl:176-182`.
- **BR-CBTRN02C-10**: End-of-job summary: `TRANSACTIONS PROCESSED :` + 9-digit count and
  `TRANSACTIONS REJECTED  :` + 9-digit count (two spaces before the colon on the second line), displayed after
  all files are closed; RETURN-CODE = 4 if at least one transaction was rejected.
  Source: `app/cbl/CBTRN02C.cbl:227-232 (main procedure)`.

### Validation rules
- **VR-CBTRN02C-01**: The card number must exist in the card cross-reference file; otherwise reason 100
  `INVALID CARD NUMBER FOUND`, and the account checks are skipped. Source: `app/cbl/CBTRN02C.cbl:380-392 (1500-A-LOOKUP-XREF)`, `app/cbl/CBTRN02C.cbl:370-378 (1500-VALIDATE-TRAN)`.
- **VR-CBTRN02C-02**: The account from the cross-reference must exist in the account file; otherwise reason
  101 `ACCOUNT RECORD NOT FOUND`. Source: `app/cbl/CBTRN02C.cbl:393-399 (1500-B-LOOKUP-ACCT)`.
- **VR-CBTRN02C-03**: Credit limit, reason 102 `OVERLIMIT TRANSACTION` (BR-CBTRN02C-02).
- **VR-CBTRN02C-04**: Account expiry, reason 103 `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` (BR-CBTRN02C-03).
- **VR-CBTRN02C-05**: Not validated: transaction type and category codes (any value is posted and creates a
  category balance), amount sign or zero, card active status, card expiry, account active status, duplicate
  transaction IDs (detected only by the write, ERR-CBTRN02C-06). Source: `app/cbl/CBTRN02C.cbl:370-422`
  (comment `ADD MORE VALIDATIONS HERE` at line 377).

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBTRN02C-01**: Open failure (status not `00`) -> message, status line, `ABENDING PROGRAM`, abend U999.
  Messages: DALYTRAN `ERROR OPENING DALYTRAN`; TRANFILE `ERROR OPENING TRANSACTION FILE`; XREFFILE
  `ERROR OPENING CROSS REF FILE`; DALYREJS `ERROR OPENING DALY REJECTS FILE`; ACCTFILE
  `ERROR OPENING ACCOUNT MASTER FILE`; TCATBALF `ERROR OPENING TRANSACTION BALANCE FILE`.
  Source: `app/cbl/CBTRN02C.cbl:236-343 (0000-DALYTRAN-OPEN .. 0500-TCATBALF-OPEN)`.
- **ERR-CBTRN02C-02**: DALYTRAN read status not `00`/`10` -> `ERROR READING DALYTRAN FILE`, status line, abend U999.
  Source: `app/cbl/CBTRN02C.cbl:345-369 (1000-DALYTRAN-GET-NEXT)`.
- **ERR-CBTRN02C-03**: Reject write status not `00` -> `ERROR WRITING TO REJECTS FILE`, status line, abend U999.
  Source: `app/cbl/CBTRN02C.cbl:451-464 (2500-WRITE-REJECT-REC)`.
- **ERR-CBTRN02C-04**: TCATBALF read status other than `00` or `23` -> `ERROR READING TRANSACTION BALANCE FILE`,
  status line, abend U999. New-record WRITE status not `00` -> `ERROR WRITING TRANSACTION BALANCE FILE`;
  REWRITE status not `00` -> `ERROR REWRITING TRANSACTION BALANCE FILE`; both abend U999.
  Source: `app/cbl/CBTRN02C.cbl:481-493 (2700-UPDATE-TCATBAL)`, `app/cbl/CBTRN02C.cbl:510-524`, `app/cbl/CBTRN02C.cbl:528-542`.
- **ERR-CBTRN02C-05**: Account REWRITE: INVALID KEY moves reason 109 and `ACCOUNT RECORD NOT FOUND` to the
  trailer, but the trailer is not used afterwards (the transaction is still written and counted as posted).
  Other REWRITE failures are not checked at all. Source: `app/cbl/CBTRN02C.cbl:554-559 (2800-UPDATE-ACCOUNT-REC)`.
- **ERR-CBTRN02C-06**: Transaction WRITE status not `00` (including `22` duplicate TRAN-ID) ->
  `ERROR WRITING TO TRANSACTION FILE`, status line, abend U999 (after the category balance and account were
  already updated for that transaction). Source: `app/cbl/CBTRN02C.cbl:562-579 (2900-WRITE-TRANSACTION-FILE)`.
- **ERR-CBTRN02C-07**: XREFFILE and ACCTFILE validation reads handle only INVALID KEY; a read failing with any
  other status (for example `30`, `92`) is neither rejected nor stopped and the transaction is validated and
  posted with whatever CARD-XREF-RECORD / ACCOUNT-RECORD contain from the previous transaction.
  Source: `app/cbl/CBTRN02C.cbl:383-391`, `app/cbl/CBTRN02C.cbl:395-421`.
- **ERR-CBTRN02C-08**: Close failure -> message, status line, abend U999. Messages: `ERROR CLOSING DALYTRAN FILE`,
  `ERROR CLOSING TRANSACTION FILE`, `ERROR CLOSING CROSS REF FILE`, `ERROR CLOSING DAILY REJECTS FILE` (displays
  the XREFFILE status, Q-CBTRN02C-05), `ERROR CLOSING ACCOUNT FILE`, `ERROR CLOSING TRANSACTION BALANCE FILE`.
  Source: `app/cbl/CBTRN02C.cbl:582-690 (9000-DALYTRAN-CLOSE .. 9500-TCATBALF-CLOSE)`.
- **ERR-CBTRN02C-09**: Status line format `FILE STATUS IS: NNNN` + 4 characters (first byte + 3-digit binary value
  of the second byte for non-numeric or `9x` statuses; otherwise `00` + status); abend via CEE3ABD code 999,
  timing 0. Source: `app/cbl/CBTRN02C.cbl:707-727 (9999-ABEND-PROGRAM, 9910-DISPLAY-IO-STATUS)`.

## Data effects
Per posted transaction, in this order:
1. TCATBALF: new record (key account/type/category, balance = amount) or existing balance + amount (REWRITE).
2. ACCTFILE: ACCT-CURR-BAL += amount; ACCT-CURR-CYC-CREDIT += amount when amount >= 0, else
   ACCT-CURR-CYC-DEBIT += amount (REWRITE of the record read during validation).
3. TRANFILE: new 350-byte record keyed by TRAN-ID with TRAN-PROC-TS set.
Per rejected transaction: one 430-byte record appended to the new DALYREJS generation. No master is changed.
TRANFILE is opened OUTPUT (Q-CBTRN02C-01). There is no checkpoint/restart: rerunning the job after a partial
run reposts already-posted transactions to TCATBALF and ACCTFILE.

## Quirks
- **Q-CBTRN02C-01**: TRANFILE (the transaction master KSDS, DISP=SHR) is opened OUTPUT, not I-O or EXTEND.
  OUTPUT on an indexed file means "create/load": under GnuCOBOL it empties the file every run, and on z/OS
  it is only valid for an empty cluster (or one defined with REUSE, which is then reset). Transactions from
  earlier days cannot accumulate in the master through this job. Recommendation: decide (the target should
  append to a persistent transaction store; confirm the intended z/OS behavior). Source: `app/cbl/CBTRN02C.cbl:254-270 (0100-TRANFILE-OPEN)`.
- **Q-CBTRN02C-02**: The credit-limit test uses cycle credit minus cycle debit plus the amount, not the current
  balance. Because cycle debits are stored as negative numbers (BR-CBTRN02C-07), subtracting them adds the
  absolute value of returns/payments: a customer who has made returns gets closer to the limit, not further.
  Example: cycle credit 100.00, cycle debit -50.00, new purchase 10.00 -> WS-TEMP-BAL = 160.00.
  Recommendation: decide (business must state the intended available-credit formula; preserve for parity runs).
  Source: `app/cbl/CBTRN02C.cbl:403-405`, `app/cbl/CBTRN02C.cbl:548-552`.
- **Q-CBTRN02C-03**: Reason 103 overwrites 102; a transaction failing both checks is reported only as expired.
  The expiry check is a string comparison; a blank or malformed expiry date (sorts low) rejects everything.
  Recommendation: preserve for parity, decide whether multiple reasons should be recorded. Source: `app/cbl/CBTRN02C.cbl:407-420`.
- **Q-CBTRN02C-04**: Reason 109 set on an account REWRITE failure is dead: nothing reads it after posting
  started, and the transaction is still written. Other REWRITE errors are ignored. Recommendation: fix (abend or
  reject consistently). Source: `app/cbl/CBTRN02C.cbl:554-559`.
- **Q-CBTRN02C-05**: 9300-DALYREJS-CLOSE displays XREFFILE-STATUS instead of DALYREJS-STATUS when the rejects file
  fails to close. Recommendation: fix. Source: `app/cbl/CBTRN02C.cbl:648-649`.
- **Q-CBTRN02C-06**: Non-`23` read errors on the cross-reference or account file are silently treated as
  success, reusing the previous transaction's records (ERR-CBTRN02C-07). This can post a transaction to the
  wrong account. Recommendation: fix. Source: `app/cbl/CBTRN02C.cbl:383-421`.
- **Q-CBTRN02C-07**: No unit of work: a failure in 2900 (for example duplicate TRAN-ID, status `22`) abends after the
  category balance and account were already updated; DALYREJS is deleted by DISP=(NEW,CATLG,DELETE) but the VSAM
  updates remain. Recommendation: fix (one transaction per unit of work in the target; define restart).
  Source: `app/cbl/CBTRN02C.cbl:440-442`, `app/jcl/POSTTRAN.jcl:34`.
- **Q-CBTRN02C-08**: Sample data uses EBCDIC-style zoned-decimal sign overpunch in an ASCII file (`{`, `A`-`I` = +0..+9,
  `}`, `J`-`R` = -0..-9 in the last digit, for example `0000005047G` = +504.77 and `0000009190}` = -919.00). Under
  GnuCOBOL with the default ASCII sign convention these characters decode as the last digit 0 and a positive sign,
  which changes amounts and the reject count. Verified: default GnuCOBOL build: 300 processed, 43 rejected (all 102),
  RC 4; build with `-fsign=EBCDIC` (mainframe semantics): 300 processed, 38 rejected (all 102), RC 4, 50
  category-balance records created (100 in total). The `43` documented in `AGENTS.md` and the runbook is an artifact
  of the ASCII sign decoding. Recommendation: decide (the target and the parity harness must decode signed zoned
  fields with EBCDIC overpunch semantics; correct the documented expectation to 38).
  Source: `app/data/ASCII/dailytran.txt`, `app/cpy/CVTRA06Y.cpy:10`.
- **Q-CBTRN02C-09**: TRAN-RECORD is never initialized, so its 20-byte FILLER is written with whatever working storage
  holds (spaces under GnuCOBOL; unpredictable on z/OS without WSCLEAR). Recommendation: fix (write spaces).
  Source: `app/cbl/CBTRN02C.cbl:424-438`, `app/cpy/CVTRA05Y.cpy:18`.
- **Q-CBTRN02C-10**: The category-balance key uses the account from the cross-reference while the account update uses
  the record read by the same key; they agree today, but the daily record's own account is never used (it has none).
  The reject file contains full card numbers (in the copied daily record). Recommendation: preserve the key
  derivation; protect the reject dataset as PCI data. Source: `app/cbl/CBTRN02C.cbl:469-477`, `app/cbl/CBTRN02C.cbl:447`.
- **Q-CBTRN02C-11**: WS-TEMP-BAL is S9(9)V99 while account amounts are S9(10)V99; a sum of 1,000,000,000.00 or more
  loses its high-order digit silently (no ON SIZE ERROR), which can let an overlimit transaction pass.
  Recommendation: fix (use the full precision). Source: `app/cbl/CBTRN02C.cbl:187`, `app/cbl/CBTRN02C.cbl:403-405`.

## Test scenarios
Sample data (`app/data/ASCII`): 300 daily transactions, 50 cards/xrefs/accounts, 50 category-balance records
(type `01`, category `0001`, balance 0 for each account). All sample account expiry dates are later than the
2022 transaction dates, so reason 103 does not occur in the sample.

- **TS-CBTRN02C-01** (covers BR-CBTRN02C-01, BR-CBTRN02C-10, Q-CBTRN02C-08): Given the sample files decoded with
  EBCDIC overpunch semantics, when POSTTRAN runs, then SYSOUT ends with `TRANSACTIONS PROCESSED :000000300` and
  `TRANSACTIONS REJECTED  :000000038`, RC = 4, DALYREJS has 38 records all with trailer `0102OVERLIMIT TRANSACTION`,
  TRANFILE has 262 records, and TCATBALF has 100 records (50 `.. Creating.` messages). (Default-ASCII GnuCOBOL gives 43
  rejects instead; see Q-CBTRN02C-08.)
- **TS-CBTRN02C-02** (covers BR-CBTRN02C-04, BR-CBTRN02C-05, BR-CBTRN02C-06, BR-CBTRN02C-07): Given account
  `00000000007` (balance 193.00, credit limit 2065.00, cycle credit and debit 0) and the sample transactions for card
  4859****7065, when POSTTRAN runs, then the first transaction `0000000000683580` (type `01`, category `0001`,
  amount +504.77) is written to TRANFILE with TRAN-PROC-TS in the form `YYYY-MM-DD-HH.MM.SS.hh0000`; after the run the
  account shows balance 900.21, cycle credit 1287.09, cycle debit -579.88, and category balances
  `00000000007 01 0001` = 1287.09 and `00000000007 03 0001` = -579.88 (the latter created with
  `TCATBAL record not found for key : 00000000007030001.. Creating.`).
- **TS-CBTRN02C-03** (covers BR-CBTRN02C-02, BR-CBTRN02C-09): Given account `00000000037` with credit limit 446.00
  and zero cycle totals, when transaction `0000000040455859` (card 1142****8931, amount +715.44) is processed, then
  it is rejected: the DALYREJS record is the daily record followed by `0102OVERLIMIT TRANSACTION` and 55 spaces;
  the account and category balances are unchanged by it.
- **TS-CBTRN02C-04** (covers VR-CBTRN02C-01): Given a daily transaction with card `9999999999999999` not in the
  xref, then it is rejected with trailer `0100INVALID CARD NUMBER FOUND` and no account read happens.
- **TS-CBTRN02C-05** (covers VR-CBTRN02C-02): Given an xref linking card 1111****1111 to non-existent account
  `00000099999`, then the transaction is rejected with `0101ACCOUNT RECORD NOT FOUND`.
- **TS-CBTRN02C-06** (covers BR-CBTRN02C-03, Q-CBTRN02C-03): Given account expiry `2022-06-01` and a transaction
  with DALYTRAN-ORIG-TS `2022-06-10 19:27:53.000000` whose amount also exceeds the limit, then it is rejected with
  `0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION` (not 102). With ORIG-TS `2022-06-01 ...` (same day) it passes the
  expiry check.
- **TS-CBTRN02C-07** (covers Q-CBTRN02C-02): Given an account with credit limit 150.00, cycle credit 100.00, cycle
  debit -50.00, when a purchase of +10.00 arrives, then WS-TEMP-BAL = 160.00 and it is rejected with 102, although
  the net cycle spend is 50.00.
- **TS-CBTRN02C-08** (covers BR-CBTRN02C-10): Given a daily file where every transaction is valid, then
  `TRANSACTIONS REJECTED  :000000000` and RC = 0. Given an empty daily file, then both counts are 0, RC = 0, and
  TRANFILE is empty after the run (Q-CBTRN02C-01 under GnuCOBOL).
- **TS-CBTRN02C-09** (covers ERR-CBTRN02C-06, Q-CBTRN02C-07): Given two daily records with the same TRAN-ID, both
  valid, then the second causes `ERROR WRITING TO TRANSACTION FILE`, `FILE STATUS IS: NNNN0022`,
  `ABENDING PROGRAM`, abend U999, after its amount was already added to the category balance and the account.
- **TS-CBTRN02C-10** (covers ERR-CBTRN02C-01): Given DD TCATBALF missing, then `ERROR OPENING TRANSACTION BALANCE FILE`,
  `FILE STATUS IS: NNNN0035`, `ABENDING PROGRAM`, abend U999, before any record is read.

## Open questions
- What is the intended credit check (current balance vs. cycle credit minus cycle debit) and the sign convention for
  cycle debits (Q-CBTRN02C-02)?
- On the real z/OS system, is TRANSACT.VSAM.KSDS defined with REUSE or emptied before POSTTRAN, and how should the
  target keep the transaction history (Q-CBTRN02C-01)? TRANREPT later reads a backup of this file.
- Confirm that the authoritative sign encoding for the sample data is EBCDIC overpunch, so the expected reject count is
  38, not 43 (Q-CBTRN02C-08).
- Should transaction type and category codes be validated against TRANTYPE/TRANCATG before posting?
- Restart policy after an abend mid-file (Q-CBTRN02C-07).
