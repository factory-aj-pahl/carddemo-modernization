# COBIL00C: Online bill payment that pays an account's full current balance

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COBIL00C.cbl` (572 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CB00 (`app/csd/CARDDEMO.CSD:337-340`) |
| BMS map | mapset `COBIL00` / map `COBIL0A` (`app/bms/COBIL00.bms`, symbolic map `app/cpy-bms/COBIL00.CPY`) |
| Copybooks | COCOM01Y (plus the program-local extension `CDEMO-CB00-INFO`), COBIL00, COTTL01Y, CSDAT01Y, CSMSG01Y, CVACT01Y, CVACT03Y, CVTRA05Y, DFHAID, DFHBMSCA |
| Calls / XCTL targets | XCTL `COSGN00C` when EIBCALEN = 0, passing the 218-byte working-storage COMMAREA (Q-COSGN00C-10); XCTL `CDEMO-FROM-PROGRAM`, or `COMEN01C` when that is blank (PF3). No CALLs. |
| Data stores | `ACCTDAT` (ENT-ACCOUNT): Read with UPDATE, then Rewrite, key `ACCT-ID`. `CXACAIX` (alternate-index path over the card cross-reference, ENT-CARD-XREF): Read, key `XREF-ACCT-ID`. `TRANSACT` (ENT-TRANSACTION): Browse (STARTBR, READPREV, ENDBR) and Write, key `TRAN-ID`. |

## Purpose
Lets a signed-on user pay off an account. The user enters an 11-digit account number. The
program shows the current balance and asks for confirmation. When the user confirms, the
program records a payment transaction for the **entire** current balance in the online
transaction file and sets the account balance to zero. Partial payments are not possible.
The payment does not go through the batch posting job (JOB-POSTTRAN). It is written directly
to TRANSACT and the account balance is changed online.

## Invocation and state
- Entered by XCTL from `COMEN01C`, main menu option 10 "Bill Payment" (`app/cpy/COMEN02Y.cpy:80-84`,
  user type `U`). The menu passes only the 160-byte `CARDDEMO-COMMAREA` from COCOM01Y, with
  `CDEMO-FROM-PROGRAM = 'COMEN01C'`, `CDEMO-FROM-TRANID = 'CM00'`, and `CDEMO-PGM-CONTEXT = 0`.
- If `EIBCALEN = 0` (transaction started directly without a COMMAREA), the program transfers to
  the sign-on program `COSGN00C`.
- The COMMAREA the program returns is 218 bytes: COCOM01Y (160 bytes) followed by `CDEMO-CB00-INFO`
  (bytes 161-218): `CDEMO-CB00-TRNID-FIRST` X(16), `CDEMO-CB00-TRNID-LAST` X(16),
  `CDEMO-CB00-PAGE-NUM` 9(8), `CDEMO-CB00-NEXT-PAGE-FLG` X(1), `CDEMO-CB00-TRN-SEL-FLG` X(1),
  `CDEMO-CB00-TRN-SELECTED` X(16). Incoming data is copied with `MOVE DFHCOMMAREA(1:EIBCALEN)`.
  When the 160-byte menu COMMAREA arrives, the group move pads the extension with spaces.
- COMMAREA fields read: `CDEMO-PGM-CONTEXT` (0 = first entry, 1 = re-entry), `CDEMO-FROM-PROGRAM`
  (PF3 target), and `CDEMO-CB00-TRN-SELECTED` (an optional account number to pre-fill; no program sets it).
- State carried between pseudo-conversational turns: only `CDEMO-PGM-CONTEXT = 1`. The account number,
  balance, and confirmation are read from the screen on every turn, so the balance is re-read on
  the confirming turn.
- On leaving (PF3 or no COMMAREA) the program sets `CDEMO-FROM-TRANID = 'CB00'`,
  `CDEMO-FROM-PROGRAM = 'COBIL00C'`, and `CDEMO-PGM-CONTEXT = 0`, then issues XCTL with the 218-byte COMMAREA.
- Every turn that does not XCTL ends with `EXEC CICS RETURN TRANSID('CB00') COMMAREA(CARDDEMO-COMMAREA)`.

## Screen
State after the first ENTER for account `00000000001` (balance 194.00):

```text
         1         2         3         4         5         6         7         8
12345678901234567890123456789012345678901234567890123456789012345678901234567890
 Tran: CB00                AWS Mainframe Modernization           Date: 06/15/22
 Prog: COBIL00C                    CardDemo                      Time: 10:30:00

                                   Bill Payment

      Enter Acct ID: 00000000001

      ----------------------------------------------------------------------


      Your current balance is:  +0000000194.00



      Do you want to pay your balance now. Please confirm:  _  (Y/N)







 Confirm to make a bill payment...
 ENTER=Continue  F3=Back  F4=Clear
```

Positions are the (row, column) of the first data character. The BMS `POS` value is one column
earlier, because it addresses the attribute byte.

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Transaction | TRNNAME | 4 | Out | (1,8) ASKIP FSET, blue | Always `CB00` |
| Title 1 | TITLE01 | 40 | Out | (1,22) ASKIP FSET, yellow | `CCDA-TITLE01` = `'      AWS Mainframe Modernization       '` |
| Date | CURDATE | 8 | Out | (1,72) ASKIP FSET, blue | Current date `MM/DD/YY` (FUNCTION CURRENT-DATE) |
| Program | PGMNAME | 8 | Out | (2,8) ASKIP FSET, blue | Always `COBIL00C` |
| Title 2 | TITLE02 | 40 | Out | (2,22) ASKIP FSET, yellow | `CCDA-TITLE02` = `'              CardDemo                  '` |
| Time | CURTIME | 8 | Out | (2,72) ASKIP FSET, blue | Current time `HH:MM:SS` |
| Account ID | ACTIDIN | 11 | In/Out | (6,22) UNPROT FSET IC, green, underline | Account to pay; must match an `ACCT-ID` key exactly (11 characters) |
| Current balance | CURBAL | 14 | Out | (11,33) ASKIP FSET, blue | `ACCT-CURR-BAL` edited as `+9999999999.99` (sign always shown, leading zeros) |
| Confirm | CONFIRM | 1 | In | (15,61) UNPROT FSET, green, underline | `Y`/`y` pay, `N`/`n` cancel, blank asks for confirmation |
| Message | ERRMSG | 78 | Out | (23,2) ASKIP BRT FSET, red; set to green on success | Error, prompt, or success text |

Constant text: "Bill Payment" (4,36); "Enter Acct ID:" (6,7); a 70-dash rule (8,7); "Your current
balance is: " (11,7); "Do you want to pay your balance now. Please confirm: " (15,7); "(Y/N)"
(15,64); "ENTER=Continue  F3=Back  F4=Clear" (24,2).

## Processing flow
1. **MAIN-PARA**: clear the error flag and the message. IF `EIBCALEN = 0`, XCTL to `COSGN00C` (NAV-COBIL00C-01).
2. Copy the COMMAREA. IF `CDEMO-PGM-CONTEXT` is not 1 (first entry):
   1. Set the context to 1, clear the map (LOW-VALUES), and put the cursor on ACTIDIN.
   2. IF `CDEMO-CB00-TRN-SELECTED` is neither spaces nor LOW-VALUES, move it into ACTIDIN and run
      PROCESS-ENTER-KEY. This path cannot occur in practice (Q-COBIL00C-10).
   3. Send the screen (SEND-BILLPAY-SCREEN).
3. OTHERWISE (re-entry), RECEIVE the map and evaluate the attention identifier (AID):
   ENTER runs PROCESS-ENTER-KEY. PF3 transfers to the previous program. PF4 runs CLEAR-CURRENT-SCREEN.
   Any other key shows the invalid-key message.
4. **PROCESS-ENTER-KEY**:
   1. Set the confirm-pay flag to No. IF ACTIDIN is blank, show "Acct ID can NOT be empty..." (VR-COBIL00C-01).
   2. IF there is no error, copy ACTIDIN to both `ACCT-ID` and `XREF-ACCT-ID`, then evaluate CONFIRM:
      - `Y` or `y`: set confirm-pay to Yes and READ-ACCTDAT-FILE (READ UPDATE).
      - `N` or `n`: CLEAR-CURRENT-SCREEN (blank form, no message) and set the error flag.
      - blank (spaces or LOW-VALUES): READ-ACCTDAT-FILE.
      - any other value: show "Invalid value. Valid values are (Y/N)..." with the cursor on CONFIRM (VR-COBIL00C-02).
      - In every branch above, `ACCT-CURR-BAL` is then moved into CURBAL (lines 193-194).
   3. IF there is no error and `ACCT-CURR-BAL <= 0`, show "You have nothing to pay..." (BR-COBIL00C-02).
   4. IF there is no error:
      - IF confirm-pay is Yes, run steps a-h in order. **The error flag is not checked between these steps** (Q-COBIL00C-02).
        a. READ-CXACAIX-FILE (gets the card number for the account).
        b. Set `TRAN-ID` to HIGH-VALUES, then STARTBR, READPREV, and ENDBR on TRANSACT. This returns the highest existing `TRAN-ID`.
        c. Add 1 to it as a 16-digit number (BR-COBIL00C-03).
        d. Build the payment record (BR-COBIL00C-04), taking timestamps from GET-CURRENT-TIMESTAMP (BR-COBIL00C-05).
        e. WRITE-TRANSACT-FILE. On success, clear the screen fields and set the green success message (BR-COBIL00C-09).
        f. Compute `ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT` (BR-COBIL00C-06).
        g. UPDATE-ACCTDAT-FILE (REWRITE).
        h. Send the screen.
      - OTHERWISE (confirm blank), set the message "Confirm to make a bill payment..." with the cursor on CONFIRM, and send the screen.
5. Every turn that does not XCTL ends with RETURN TRANSID `CB00`. Error paragraphs call
   SEND-BILLPAY-SCREEN themselves, and processing continues after the send, so a single turn can
   send the screen several times. The user sees only the last one (Q-COBIL00C-03).

## Rules
### Business rules
- **BR-COBIL00C-01**: The payment amount is always the full `ACCT-CURR-BAL`, as read with UPDATE on the
  confirming turn. The user cannot enter an amount. Source: `app/cbl/COBIL00C.cbl:224 (PROCESS-ENTER-KEY)`.
- **BR-COBIL00C-02**: When the account exists and `ACCT-CURR-BAL <= 0`, no payment is made. The message is
  "You have nothing to pay..." with the cursor on ACTIDIN, and CURBAL shows the balance (for example
  `+0000000000.00`). This applies even if CONFIRM is `Y`. Source: `app/cbl/COBIL00C.cbl:193-206 (PROCESS-ENTER-KEY)`.
- **BR-COBIL00C-03**: The new transaction ID is the highest `TRAN-ID` in TRANSACT plus 1. The program
  STARTBRs with a key of HIGH-VALUES and READPREVs once to get the highest key. That key is moved to
  `PIC 9(16)`, incremented, and moved back, so it is zero-padded to 16 digits. IF READPREV returns
  ENDFILE, the base is 0 and the ID is `0000000000000001`. Example: with sample data loaded, the
  highest ID is `0000000996722787`, so the next ID is `0000000996722788`.
  Source: `app/cbl/COBIL00C.cbl:212-219 (PROCESS-ENTER-KEY)`, `:472-496 (READPREV-TRANSACT-FILE)`.
- **BR-COBIL00C-04**: The payment transaction record (CVTRA05Y, 350 bytes) is first initialized
  (alphanumeric fields to spaces, numeric fields to zero), then set as follows: `TRAN-ID` = the new ID;
  `TRAN-TYPE-CD = '02'`; `TRAN-CAT-CD = 0002`; `TRAN-SOURCE = 'POS TERM'`;
  `TRAN-DESC = 'BILL PAYMENT - ONLINE'`; `TRAN-AMT` = `ACCT-CURR-BAL` (positive);
  `TRAN-CARD-NUM` = `XREF-CARD-NUM` from CXACAIX; `TRAN-MERCHANT-ID = 999999999`;
  `TRAN-MERCHANT-NAME = 'BILL PAYMENT'`; `TRAN-MERCHANT-CITY = 'N/A'`; `TRAN-MERCHANT-ZIP = 'N/A'`;
  `TRAN-ORIG-TS` = `TRAN-PROC-TS` = the current timestamp. The filler stays as spaces. Type 02 is
  "Payment" in `app/data/ASCII/trantype.txt`, and type 02 category 0002 is "Electronic payment" in
  `app/data/ASCII/trancatg.txt`. Source: `app/cbl/COBIL00C.cbl:218-232 (PROCESS-ENTER-KEY)`.
- **BR-COBIL00C-05**: The timestamp comes from CICS ASKTIME and FORMATTIME (the region's local date and
  time). Its format is `YYYY-MM-DD HH:MM:SS.000000` (26 characters). The date separator is `-`, a space
  separates date and time, the time separator is `:`, and a `.` is followed by six zeros, so there are
  no fractional seconds. Source: `app/cbl/COBIL00C.cbl:249-267 (GET-CURRENT-TIMESTAMP)`, `app/cpy/CSDAT01Y.cpy:42-55`.
- **BR-COBIL00C-06**: The new account balance is `ACCT-CURR-BAL - TRAN-AMT`, which is normally 0.00.
  No other account field changes: the current-cycle credit and debit, credit limits, and dates are
  untouched. Source: `app/cbl/COBIL00C.cbl:234-235 (PROCESS-ENTER-KEY)`.
- **BR-COBIL00C-07**: The payment is attributed to the card returned by a READ of CXACAIX on the account
  ID. That is the first cross-reference record for the account in the alternate index.
  Source: `app/cbl/COBIL00C.cbl:211, 225 (PROCESS-ENTER-KEY)`, `:408-436 (READ-CXACAIX-FILE)`.
- **BR-COBIL00C-08**: Two-step confirmation. ENTER with CONFIRM blank reads the account, shows the
  balance, and prompts "Confirm to make a bill payment..." with the cursor on CONFIRM. Nothing is
  written. ENTER with CONFIRM `Y` or `y` pays immediately, even on the first ENTER when the balance has
  not been shown yet. ENTER with `N` or `n` clears all fields and shows no message.
  Source: `app/cbl/COBIL00C.cbl:173-191, 236-240 (PROCESS-ENTER-KEY)`.
- **BR-COBIL00C-09**: When the TRANSACT WRITE succeeds, ACTIDIN, CURBAL, and CONFIRM are cleared, the
  message color is set to green, and the message is
  "Payment successful.  Your Transaction ID is <TRAN-ID>." Note the two spaces after "successful." and
  the 16-digit ID followed by a period. The cursor is on ACTIDIN.
  Source: `app/cbl/COBIL00C.cbl:522-532 (WRITE-TRANSACT-FILE)`, `:560-566 (INITIALIZE-ALL-FIELDS)`.
- **BR-COBIL00C-10**: Every send fills the header: TITLE01/TITLE02 from COTTL01Y, TRNNAME `CB00`,
  PGMNAME `COBIL00C`, CURDATE `MM/DD/YY`, and CURTIME `HH:MM:SS` from FUNCTION CURRENT-DATE.
  Source: `app/cbl/COBIL00C.cbl:319-338 (POPULATE-HEADER-INFO)`.

### Validation rules
- **VR-COBIL00C-01**: ACTIDIN must not be spaces or LOW-VALUES. Otherwise the message is
  "Acct ID can NOT be empty..." with the cursor on ACTIDIN, and no file is read.
  Source: `app/cbl/COBIL00C.cbl:158-167 (PROCESS-ENTER-KEY)`.
- **VR-COBIL00C-02**: CONFIRM must be `Y`, `y`, `N`, `n`, or blank. Otherwise the message is
  "Invalid value. Valid values are (Y/N)..." with the cursor on CONFIRM. The account is not read, and
  nothing is written. Source: `app/cbl/COBIL00C.cbl:185-190 (PROCESS-ENTER-KEY)`.
- **VR-COBIL00C-03**: The account must exist in ACCTDAT. Otherwise the message is
  "Account ID NOT found..." with the cursor on ACTIDIN. There is no numeric or length check: the
  11 characters on the screen are the key as typed, so `1` or `ABC` simply produce this message.
  Source: `app/cbl/COBIL00C.cbl:170, 343-364 (READ-ACCTDAT-FILE)`.

### Navigation and keys
- **NAV-COBIL00C-01**: IF `EIBCALEN = 0`, XCTL to `COSGN00C`. Source: `app/cbl/COBIL00C.cbl:107-109 (MAIN-PARA)`, `:273-284 (RETURN-TO-PREV-SCREEN)`.
- **NAV-COBIL00C-02**: First entry (`CDEMO-PGM-CONTEXT = 0`): set the context to 1, show a blank form with
  the cursor on ACTIDIN, and send. Source: `app/cbl/COBIL00C.cbl:112-122 (MAIN-PARA)`.
- **NAV-COBIL00C-03**: ENTER runs the payment flow (PROCESS-ENTER-KEY). Source: `app/cbl/COBIL00C.cbl:126-127 (MAIN-PARA)`.
- **NAV-COBIL00C-04**: PF3 transfers by XCTL to `CDEMO-FROM-PROGRAM`, or to `COMEN01C` when that field is
  spaces or LOW-VALUES. Coming from the menu, the target is `COMEN01C`. Before the XCTL the program sets
  FROM-TRANID `CB00`, FROM-PROGRAM `COBIL00C`, and context 0.
  Source: `app/cbl/COBIL00C.cbl:128-135 (MAIN-PARA)`, `:273-284 (RETURN-TO-PREV-SCREEN)`.
- **NAV-COBIL00C-05**: PF4 clears ACTIDIN, CURBAL, CONFIRM, and the message, puts the cursor on ACTIDIN,
  and resends the screen. Source: `app/cbl/COBIL00C.cbl:136-137 (MAIN-PARA)`, `:552-566 (CLEAR-CURRENT-SCREEN, INITIALIZE-ALL-FIELDS)`.
- **NAV-COBIL00C-06**: Any other AID (PF1, PF2, PF5-PF24, PA keys, CLEAR) shows the 50-character
  `CCDA-MSG-INVALID-KEY` text "Invalid key pressed. Please see below..." padded with trailing spaces. For
  PF keys the screen fields keep what was received (ACTIDIN, CURBAL, and CONFIRM are FSET). For PA keys
  and CLEAR the terminal sends no data, so RECEIVE gets MAPFAIL (ignored, ERR-COBIL00C-08), the map keeps
  its initial storage, and the account ID, current balance, and Confirm fields are blank on the re-sent
  screen. Target: C-SHORTREAD (ledger-online.md). Source: `app/cbl/COBIL00C.cbl:124`,
  `app/cbl/COBIL00C.cbl:138-141 (MAIN-PARA)`, `app/cpy/CSMSG01Y.cpy:20-21`.
- **NAV-COBIL00C-07**: Every turn that does not XCTL ends with RETURN TRANSID `CB00` and the 218-byte
  COMMAREA. Source: `app/cbl/COBIL00C.cbl:146-149 (MAIN-PARA)`.

### Error handling
All messages appear in ERRMSG in red, with the cursor on ACTIDIN, unless stated otherwise.
Responses other than the ones listed also write `DISPLAY 'RESP:' <resp> 'REAS:' <resp2>` to the CICS
job log.
- **ERR-COBIL00C-01**: ACCTDAT READ with a response other than NORMAL or NOTFND shows
  "Unable to lookup Account...". Source: `app/cbl/COBIL00C.cbl:365-371 (READ-ACCTDAT-FILE)`.
- **ERR-COBIL00C-02**: CXACAIX READ NOTFND shows "Account ID NOT found...". Any other non-NORMAL response,
  including DUPKEY for an account with several cards, shows "Unable to lookup XREF AIX file...".
  Processing still continues to the TRANSACT write (Q-COBIL00C-02).
  Source: `app/cbl/COBIL00C.cbl:420-436 (READ-CXACAIX-FILE)`.
- **ERR-COBIL00C-03**: TRANSACT STARTBR NOTFND shows "Transaction ID NOT found...". Any other non-NORMAL
  response shows "Unable to lookup Transaction...". Source: `app/cbl/COBIL00C.cbl:451-467 (STARTBR-TRANSACT-FILE)`.
- **ERR-COBIL00C-04**: TRANSACT READPREV ENDFILE sets `TRAN-ID` to zeros and is not treated as an error.
  Any other non-NORMAL response shows "Unable to lookup Transaction...".
  Source: `app/cbl/COBIL00C.cbl:484-496 (READPREV-TRANSACT-FILE)`.
- **ERR-COBIL00C-05**: TRANSACT WRITE DUPKEY or DUPREC shows "Tran ID already exist...". Any other
  non-NORMAL response shows "Unable to Add Bill pay Transaction...". In both cases the account is still
  rewritten afterwards (Q-COBIL00C-02). Source: `app/cbl/COBIL00C.cbl:522-547 (WRITE-TRANSACT-FILE)`.
- **ERR-COBIL00C-06**: ACCTDAT REWRITE NOTFND shows "Account ID NOT found...". Any other non-NORMAL
  response shows "Unable to Update Account...". This message replaces the success message, but the
  transaction has already been written. Source: `app/cbl/COBIL00C.cbl:387-403 (UPDATE-ACCTDAT-FILE)`.
- **ERR-COBIL00C-07**: ENDBR has no RESP option. Any exception raises the CICS default action, a task
  abend (AEIP). An example is INVREQ when STARTBR failed and no browse is active.
  Source: `app/cbl/COBIL00C.cbl:501-505 (ENDBR-TRANSACT-FILE)`.
- **ERR-COBIL00C-08**: The RESP of RECEIVE MAP is captured but never tested, so MAPFAIL is ignored
  (consequence for PA keys and CLEAR: see NAV-COBIL00C-06).
  Source: `app/cbl/COBIL00C.cbl:306-314 (RECEIVE-BILLPAY-SCREEN)`.

## Data effects
On a confirmed payment for an account with a positive balance, in this order:
1. **ACCTDAT** READ UPDATE on `ACCT-ID`. The record is locked from here until the REWRITE or the end of the task.
2. **CXACAIX** READ on `XREF-ACCT-ID` (no data change).
3. **TRANSACT** STARTBR with key HIGH-VALUES, READPREV, ENDBR (no data change).
4. **TRANSACT** WRITE of one new 350-byte record (BR-COBIL00C-04).
5. **ACCTDAT** REWRITE with `ACCT-CURR-BAL = old balance - TRAN-AMT`. All other bytes are unchanged.

There is no explicit SYNCPOINT. Work is committed at the end of the task (RETURN). All three files are
defined `RECOVERY(NONE)` (`app/csd/CARDDEMO.CSD:1-12, 63-87`), so CICS does not back out the WRITE if
the REWRITE fails or the task abends. The WRITE and REWRITE are therefore **not atomic**. The "highest
transaction ID" read is not locked, so two tasks can generate the same ID (Q-COBIL00C-01).
On a turn without confirmation, ACCTDAT is still read with UPDATE. The lock is released at the end of
the task and no data changes.
The payment is not written to the daily transaction file, so JOB-POSTTRAN never sees it. TCATBAL
(transaction category balances) and the account's cycle credit are not updated.

## Quirks
- **Q-COBIL00C-01**: Transaction IDs are generated as "highest key + 1" without any lock or sequence.
  Two concurrent payments, or a payment concurrent with COTRN02C (which uses the same method), can
  compute the same ID, and the second WRITE fails with DUPREC. Recommendation: fix (use a database
  sequence or identity). Source: `app/cbl/COBIL00C.cbl:212-219`.
- **Q-COBIL00C-02**: In the confirmed-payment sequence, the error flag is not tested after
  READ-CXACAIX-FILE, STARTBR, READPREV, or WRITE. Each failure sends an error screen, but execution
  continues: the WRITE is still attempted, possibly with a stale card number, and the account balance
  is reduced and rewritten **even when the transaction WRITE failed**. The user sees the last message
  set, for example "Tran ID already exist...", while the balance is already zero. Recommendation: fix
  (one atomic unit of work; abort on any failure). Source: `app/cbl/COBIL00C.cbl:208-244`.
- **Q-COBIL00C-03**: One task can send the map several times. The success path sends after the WRITE
  and again at line 242. Error paragraphs send and then fall through to further sends. First entry
  with a pre-selected account sends twice. Only the final screen is visible. Recommendation: in the
  target system, return a single response that matches the final visible state.
  Source: `app/cbl/COBIL00C.cbl:120-122, 242, 532`.
- **Q-COBIL00C-04**: The account number is not validated for being numeric or for its length. The raw
  11 screen characters (`PIC X(11)`) are moved into the `PIC 9(11)` key, so the user must type all
  11 digits including leading zeros (`00000000001`). `1` returns "Account ID NOT found...".
  Recommendation: decide (accept a numeric input and left-pad it with zeros).
  Source: `app/cbl/COBIL00C.cbl:170-171`.
- **Q-COBIL00C-05**: The payment is stored with a positive `TRAN-AMT`. In the sample data, credits and
  returns (type 03) carry negative amounts, and JOB-POSTTRAN adds amounts to the balance. Any consumer
  that sums `TRAN-AMT` per account, such as statements or reports, treats a payment as a charge.
  Recommendation: decide the sign convention. Source: `app/cbl/COBIL00C.cbl:224`.
- **Q-COBIL00C-06**: The payment bypasses batch posting. It does not update TCATBAL or
  `ACCT-CURR-CYC-CREDIT`, and it does not check the account's active status or expiration date.
  Recommendation: decide. Source: `app/cbl/COBIL00C.cbl:208-235`.
- **Q-COBIL00C-07**: `ACCT-CURR-BAL` is `S9(10)V99` but `TRAN-AMT` is `S9(9)V99`. For balances of
  1,000,000,000.00 or more, the high-order digit is truncated in the transaction amount, and the
  remaining balance is not zero. Recommendation: fix (use a wide enough type) or preserve; the case is
  very unlikely. Source: `app/cbl/COBIL00C.cbl:224, 234`; `app/cpy/CVACT01Y.cpy:7`; `app/cpy/CVTRA05Y.cpy:10`.
- **Q-COBIL00C-08**: The balance shown on the confirmation screen comes from the previous turn. The
  amount actually paid is the balance re-read when the user confirms, and the success message does not
  show it. Recommendation: decide (show the amount paid, or pass the expected amount and reject if it
  changed). Source: `app/cbl/COBIL00C.cbl:173-184, 224`.
- **Q-COBIL00C-09**: Lines 193-194 move `ACCT-CURR-BAL` into CURBAL even when the account was not read in
  this task (confirm `N` or invalid, or account not found). The screen has already been sent by then,
  so nothing visible changes. If the record area holds non-numeric bytes, the numeric-edited move could
  raise a data exception; this was not verified. Recommendation: fix (do not touch the balance unless
  the account was read). Source: `app/cbl/COBIL00C.cbl:193-194`.
- **Q-COBIL00C-10**: The `CDEMO-CB00-INFO` extension (first and last ID, page number, next-page flag,
  selection flag) was copied from the transaction-list template and is unused. The one field read,
  `CDEMO-CB00-TRN-SELECTED`, is never set by any program, and on entry from the menu it holds spaces.
  The pre-fill path is therefore dead. Recommendation: remove. Source: `app/cbl/COBIL00C.cbl:64-72, 116-121`.
- **Q-COBIL00C-11**: CXACAIX is a path over an alternate index defined `NONUNIQUEKEY`
  (`app/jcl/XREFFILE.jcl:72-75`). For an account with more than one card, the READ returns DUPKEY and
  shows "Unable to lookup XREF AIX file...", but because of Q-COBIL00C-02 the payment still goes ahead
  with the first card. In the sample data, every account has exactly one card. Recommendation: decide
  which card a payment is attributed to. Source: `app/cbl/COBIL00C.cbl:408-436`.
- **Q-COBIL00C-12**: If TRANSACT is empty, STARTBR at HIGH-VALUES returns NOTFND. READPREV then fails
  (no browse) and ENDBR without RESP abends the task. The "ENDFILE gives ID 1" branch is therefore
  unreachable in practice. This is inferred from CICS browse semantics. Recommendation: fix.
  Source: `app/cbl/COBIL00C.cbl:441-505`.
- **Q-COBIL00C-13**: ACCTDAT is read with UPDATE (an exclusive lock) even on the turn that only displays
  the balance. Recommendation: in the target system, read without a lock when displaying.
  Source: `app/cbl/COBIL00C.cbl:345-354`.

## Test scenarios
Assumptions: ACCTDAT is loaded from `app/data/ASCII/acctdata.txt`, CCXREF/CXACAIX from
`app/data/ASCII/cardxref.txt`, and TRANSACT from `app/data/ASCII/dailytran.txt` (300 records, highest
`TRAN-ID` `0000000996722787`). Card numbers are masked.
- **TS-COBIL00C-01** (covers BR-COBIL00C-01, -03, -04, -05, -06, -08, -09): Given account `00000000001`
  with balance 194.00 and card `************3697`. When the user presses ENTER with ACTIDIN
  `00000000001` and CONFIRM blank, then CURBAL shows `+0000000194.00`, the message is
  "Confirm to make a bill payment...", the cursor is on CONFIRM, and no data changes. When the user
  then presses ENTER with CONFIRM `Y`, then TRANSACT contains a new record `0000000996722788` with
  type `02`, category `0002`, source `POS TERM`, description `BILL PAYMENT - ONLINE`, amount `+194.00`,
  card `************3697`, merchant `999999999` / `BILL PAYMENT` / `N/A` / `N/A`, and original and
  processing timestamps both equal to the current time in the form `YYYY-MM-DD HH:MM:SS.000000`.
  Account `00000000001` has balance `0.00` and every other field unchanged. All input fields are blank,
  and the green message reads "Payment successful.  Your Transaction ID is 0000000996722788.".
- **TS-COBIL00C-02** (covers BR-COBIL00C-08): Given account `00000000002` with balance 158.00. When the
  user presses ENTER with ACTIDIN `00000000002` and CONFIRM `y` on the first ENTER, then the payment is
  made immediately: a 158.00 transaction is written, the balance becomes 0.00, and the success message
  is shown.
- **TS-COBIL00C-03** (covers BR-COBIL00C-02): Given that account `00000000001` has balance 0.00 (after
  TS-COBIL00C-01). When the user presses ENTER with ACTIDIN `00000000001` and CONFIRM blank or `Y`,
  then the message is "You have nothing to pay...", CURBAL shows `+0000000000.00`, and no record is
  written.
- **TS-COBIL00C-04** (covers BR-COBIL00C-02): Given an account whose balance is -25.00 (set up by
  editing test data). When the user presses ENTER with that account and CONFIRM `Y`, then the message
  is "You have nothing to pay..." and nothing changes.
- **TS-COBIL00C-05** (covers VR-COBIL00C-01): When the user presses ENTER with ACTIDIN blank, then the
  message is "Acct ID can NOT be empty..." and the cursor is on ACTIDIN.
- **TS-COBIL00C-06** (covers VR-COBIL00C-03, Q-COBIL00C-04): When the user presses ENTER with ACTIDIN
  `99999999999`, then the message is "Account ID NOT found...". When the user presses ENTER with
  ACTIDIN `1`, then the result is the same message.
- **TS-COBIL00C-07** (covers VR-COBIL00C-02): When the user presses ENTER with ACTIDIN `00000000001`
  and CONFIRM `X`, then the message is "Invalid value. Valid values are (Y/N)...", the cursor is on
  CONFIRM, CURBAL is not refreshed, and no data changes.
- **TS-COBIL00C-08** (covers BR-COBIL00C-08): When the user presses ENTER with ACTIDIN `00000000001`
  and CONFIRM `N`, then all fields are cleared, the message is blank, and the account is unchanged.
- **TS-COBIL00C-09** (covers NAV-COBIL00C-04, -05, -06): On the bill-payment screen reached from the
  menu, PF4 clears the form. PF5 shows "Invalid key pressed. Please see below...". PF3 returns to
  `COMEN01C`.
- **TS-COBIL00C-10** (covers NAV-COBIL00C-01): When transaction CB00 is started with no COMMAREA, then
  control transfers to `COSGN00C`.
- **TS-COBIL00C-11** (covers Q-COBIL00C-01, Q-COBIL00C-02, ERR-COBIL00C-05): Given two sessions that
  confirm payments for `00000000003` and `00000000004`, and the second session's READPREV returns the
  same highest ID as the first. When both WRITEs run, then the second gets DUPREC and shows
  "Tran ID already exist...". As written, account `00000000004` is still rewritten with balance 0.00
  and has no payment record. The target system must decide the intended behavior; recommended:
  reject with no balance change, or generate unique IDs.

## Open questions
- Should a partial payment amount be supported, or should only the full balance be paid, as in the source?
- Which sign should a payment amount carry in the transaction file (Q-COBIL00C-05)?
- Should online payments update the cycle credit and TCATBAL, or be posted through the batch cycle instead?
- For accounts with several cards, which card should the payment be attributed to (Q-COBIL00C-11)?
- Which time zone do ASKTIME/FORMATTIME use in production, and must the target system keep local time?
