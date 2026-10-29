# COTRN02C: Add a transaction directly to TRANSACT for a card or account

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COTRN02C.cbl` (783 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CT02 (`app/csd/CARDDEMO.CSD:439-440`) |
| BMS map | mapset `COTRN02` / map `COTRN2A` (`app/bms/COTRN02.bms`, symbolic map `app/cpy-bms/COTRN02.CPY`) |
| Copybooks | COCOM01Y (plus the program-local extension `CDEMO-CT02-INFO`), COTRN02, COTTL01Y, CSDAT01Y, CSMSG01Y, CVTRA05Y, CVACT01Y (unused), CVACT03Y, DFHAID, DFHBMSCA |
| Calls / XCTL targets | `CALL 'CSUTLDTC'` (date validation, twice per validation pass); XCTL to `CDEMO-FROM-PROGRAM` on PF3, or `COMEN01C` when it is blank; XCTL `COSGN00C` when EIBCALEN = 0, passing the 218-byte working-storage COMMAREA (Q-COSGN00C-10) |
| Data stores | `TRANSACT` (ENT-TRANSACTION): STARTBR, READPREV, ENDBR, WRITE, key `TRAN-ID`. `CXACAIX` (path over the account alternate index of CARDXREF): READ by `XREF-ACCT-ID`. `CCXREF` (CARDXREF): READ by `XREF-CARD-NUM`. `ACCTDAT` is named in working storage but never accessed. |

## Purpose
Lets a user key in a new card transaction (type, category, source, description, amount, dates, and
merchant) for an account or card, confirm it, and write it straight into the transaction master file
TRANSACT with the next free transaction ID. The program does not post the transaction: account
balances, category balances, credit limits, and card status are not touched or checked. PF5 pre-fills
the form from the last transaction in the file.

## Invocation and state
- Entered by XCTL from `COMEN01C`, menu option 8 "Transaction Add" (user type `U`, so regular users
  and admins see it; a commented-out label in `app/cpy/COMEN02Y.cpy:69` reads
  "Transaction Add (Admin Only)"). The menu sends the 160-byte COMMAREA with context 0.
- If `EIBCALEN = 0`, the program transfers to `COSGN00C`.
- The COMMAREA is 218 bytes: COCOM01Y (160 bytes) followed by `CDEMO-CT02-INFO` (first ID, last ID,
  page number, next-page flag, selection flag, selected ID; same layout as `CDEMO-CT00-INFO`). Only
  `CDEMO-CT02-TRN-SELECTED` (bytes 203-218) is read, on first entry. No program sets it
  (Q-COTRN02C-10). On entry from the menu the group move pads the extension with spaces.
- Nothing about the form is kept in the COMMAREA. Between turns the entered values survive only
  because every input field is FSET and is returned by each RECEIVE.

## Screen
After the first ENTER with every field valid and the confirmation blank. The account number was
typed; the card number was filled in by the program (masked here; the real screen shows all 16
digits).

```text
         1         2         3         4         5         6         7         8
12345678901234567890123456789012345678901234567890123456789012345678901234567890
 Tran: CT02                AWS Mainframe Modernization           Date: 06/15/22
 Prog: COTRN02C                    CardDemo                      Time: 10:30:00

                              Add Transaction

      Enter Acct #:  00000000001     (or)     Card #:  ************3697

      ----------------------------------------------------------------------

      Type CD: 01      Category CD: 0001      Source: POS TERM__

      Description: Purchase at Test Store______________________________________

      Amount: +00000025.50     Orig Date: 2022-06-15     Proc Date: 2022-06-15
             (-99999999.99)              (YYYY-MM-DD)              (YYYY-MM-DD)
      Merchant ID: 800000001     Merchant Name: Test Store____________________

      Merchant City: Seattle__________________       Merchant Zip: 98101_____


      You are about to add this transaction. Please confirm :  _  (Y/N)

 Confirm to add this transaction...
 ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last Tran.
```

Positions are the (row, column) of the first data character. Every input field is UNPROT FSET,
green, underline.

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Header | TRNNAME, TITLE01, CURDATE, PGMNAME, TITLE02, CURTIME | 4/40/8/8/40/8 | Out | rows 1-2, as in all CardDemo screens | `CT02`, titles, `MM/DD/YY`, `COTRN02C`, `HH:MM:SS` |
| Acct # | ACTIDIN | 11 | In/Out | (6,22) IC | Account ID; 11 digits. Overwritten from CARDXREF when only a card is entered |
| Card # | CARDNIN | 16 | In/Out | (6,56) | Card number; 16 digits. Overwritten from CXACAIX when an account is entered |
| Type CD | TTYPCD | 2 | In | (10,16) | `TRAN-TYPE-CD`, 2 digits |
| Category CD | TCATCD | 4 | In | (10,37) | `TRAN-CAT-CD`, 4 digits |
| Source | TRNSRC | 10 | In | (10,55) | `TRAN-SOURCE`, free text |
| Description | TDESC | 60 | In | (12,20) | `TRAN-DESC` (first 60 of 100 characters) |
| Amount | TRNAMT | 12 | In/Out | (14,15) | Signed amount, format `-99999999.99`; shown back normalized as `+99999999.99` |
| Orig Date | TORIGDT | 10 | In | (14,43) | Origination date `YYYY-MM-DD` |
| Proc Date | TPROCDT | 10 | In | (14,69) | Processing date `YYYY-MM-DD` |
| Merchant ID | MID | 9 | In | (16,20) | 9 digits |
| Merchant Name | MNAME | 30 | In | (16,49) | Free text (first 30 of 50) |
| Merchant City | MCITY | 25 | In | (18,22) | Free text (first 25 of 50) |
| Merchant Zip | MZIP | 10 | In | (18,68) | Free text |
| Confirm | CONFIRM | 1 | In | (21,64) | `Y`/`y` adds; `N`/`n`/blank asks for confirmation |
| Message | ERRMSG | 78 | Out | (23,2) ASKIP BRT FSET, red; green for the success message | Messages |

Constant text: "Add Transaction" (4,31); "Enter Acct #:" (6,7); "(or)" (6,38); "Card #:" (6,47);
dashed rule on row 8; field labels; format hints "(-99999999.99)" (15,14), "(YYYY-MM-DD)" (15,42) and
(15,68); "You are about to add this transaction. Please confirm :" (21,7); "(Y/N)" (21,67);
"ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last Tran." (24,2).

## Processing flow
Every send of the screen (SEND-TRNADD-SCREEN) is followed immediately by RETURN TRANSID `CT02`, so the
first validation failure ends the task and only that one message is shown (Q-COTRN02C-11).
1. **MAIN-PARA**: clear the error flag and the message. IF `EIBCALEN = 0`, XCTL to `COSGN00C`.
2. Copy the COMMAREA. IF the context is not 1 (first entry): set it to 1, clear the map, put the cursor
   on ACTIDIN. IF `CDEMO-CT02-TRN-SELECTED` is not blank, copy it into CARDNIN and run
   PROCESS-ENTER-KEY (dead code; Q-COTRN02C-10). Send the empty form.
3. OTHERWISE, RECEIVE the map and evaluate the AID: ENTER runs PROCESS-ENTER-KEY; PF3 transfers to
   `CDEMO-FROM-PROGRAM` (or `COMEN01C`); PF4 clears the form; PF5 runs COPY-LAST-TRAN-DATA; any other
   key shows the invalid-key message.
4. **PROCESS-ENTER-KEY**: run VALIDATE-INPUT-KEY-FIELDS, then VALIDATE-INPUT-DATA-FIELDS. Then evaluate
   CONFIRM: `Y`/`y` runs ADD-TRANSACTION; `N`, `n`, spaces, or LOW-VALUES show
   "Confirm to add this transaction..."; any other value shows
   "Invalid value. Valid values are (Y/N)...". Both messages put the cursor on CONFIRM.
5. **VALIDATE-INPUT-KEY-FIELDS** (VR-COTRN02C-01 to -05):
   1. IF ACTIDIN is not blank: it must be NUMERIC. Convert it with NUMVAL, write it back to ACTIDIN,
      READ CXACAIX by account, and move `XREF-CARD-NUM` to CARDNIN.
   2. ELSE IF CARDNIN is not blank: it must be NUMERIC. Convert it with NUMVAL, write it back, READ
      CCXREF by card, and move `XREF-ACCT-ID` to ACTIDIN.
   3. ELSE show "Account or Card Number must be entered...".
6. **VALIDATE-INPUT-DATA-FIELDS** (VR-COTRN02C-06 to -24), in this order:
   1. The block that blanks the data fields when the error flag is on (lines 237-249) is unreachable.
   2. Emptiness of type, category, source, description, amount, orig date, proc date, merchant ID,
      merchant name, merchant city, merchant zip.
   3. Type, then category, must be NUMERIC.
   4. Amount format; orig date format; proc date format.
   5. Normalize the amount: NUMVAL-C into `S9(9)V99`, edit as `+99999999.99`, write back to TRNAMT.
   6. CALL CSUTLDTC for the orig date, then for the proc date, with format `YYYY-MM-DD`.
   7. Merchant ID must be NUMERIC.
7. **ADD-TRANSACTION**: STARTBR TRANSACT at `TRAN-ID` = HIGH-VALUES, READPREV (the record with the
   highest key), ENDBR. Add 1 to that ID (0 if READPREV hit ENDFILE). INITIALIZE the record, fill it
   from the screen (BR-COTRN02C-06), and WRITE it.
8. **WRITE-TRANSACT-FILE**: on NORMAL, clear every input field (including CONFIRM), set the message
   colour to green, and show "Transaction added successfully.  Your Tran ID is <id>.". DUPKEY/DUPREC
   and other errors show a message (ERR rules).
9. **COPY-LAST-TRAN-DATA** (PF5): run VALIDATE-INPUT-KEY-FIELDS; STARTBR at HIGH-VALUES, READPREV,
   ENDBR; IF no error, copy type, category, source, amount (edited `+99999999.99`), description,
   characters 1-10 of both timestamps, and the merchant fields into the form; then run
   PROCESS-ENTER-KEY (full validation and the confirmation step) in the same task.
10. **RETURN-TO-PREV-SCREEN**: IF TO-PROGRAM is blank, use `COSGN00C`; set FROM-TRANID `CT02`,
    FROM-PROGRAM `COTRN02C`, context 0; XCTL with the COMMAREA.

## Rules
### Business rules
- **BR-COTRN02C-01**: The transaction is identified by account or by card through the card
  cross-reference. When ACTIDIN is filled, the account wins: the card is looked up through CXACAIX and
  CARDNIN is overwritten with the card found, whatever the user typed there. Only when ACTIDIN is
  blank is CARDNIN used, and ACTIDIN is then filled from CCXREF. Both fields are written back in their
  numeric form. Source: `app/cbl/COTRN02C.cbl:193-230 (VALIDATE-INPUT-KEY-FIELDS)`.
- **BR-COTRN02C-02**: Every validation runs on every ENTER, including the turn on which the user
  confirms with `Y`. The confirmation value is evaluated only after all validations pass.
  Source: `app/cbl/COTRN02C.cbl:164-188 (PROCESS-ENTER-KEY)`.
- **BR-COTRN02C-03**: A valid amount is written back to TRNAMT in the edited form `+99999999.99`
  before the confirmation prompt, so the user confirms the normalized value.
  Source: `app/cbl/COTRN02C.cbl:383-386 (VALIDATE-INPUT-DATA-FIELDS)`.
- **BR-COTRN02C-04**: The record is written only when CONFIRM is `Y` or `y` on a turn where all
  validations pass. Source: `app/cbl/COTRN02C.cbl:169-172 (PROCESS-ENTER-KEY)`.
- **BR-COTRN02C-05**: The new `TRAN-ID` is the highest existing `TRAN-ID` in TRANSACT plus 1, as a
  16-digit number with leading zeros. If the backward read finds no record, the ID becomes
  `0000000000000001`. Source: `app/cbl/COTRN02C.cbl:444-451 (ADD-TRANSACTION)`, `:688-689 (READPREV-TRANSACT-FILE)`.
- **BR-COTRN02C-06**: Record content (CVTRA05Y, 350 bytes), after INITIALIZE (spaces and zeros):

  | Record field | Value |
  |:--|:--|
  | `TRAN-ID` | BR-COTRN02C-05 |
  | `TRAN-TYPE-CD`, `TRAN-CAT-CD` | TTYPCD, TCATCD as entered |
  | `TRAN-SOURCE` | TRNSRC as entered |
  | `TRAN-DESC` | TDESC (60 characters, padded with spaces to 100) |
  | `TRAN-AMT` | NUMVAL-C of TRNAMT |
  | `TRAN-CARD-NUM` | CARDNIN (after BR-COTRN02C-01) |
  | `TRAN-MERCHANT-ID` | MID |
  | `TRAN-MERCHANT-NAME`, `-CITY`, `-ZIP` | MNAME, MCITY (padded to 50), MZIP |
  | `TRAN-ORIG-TS` | TORIGDT: the 10-character date followed by 16 spaces |
  | `TRAN-PROC-TS` | TPROCDT: the 10-character date followed by 16 spaces |

  Source: `app/cbl/COTRN02C.cbl:450-466 (ADD-TRANSACTION)`.
- **BR-COTRN02C-07**: After a successful WRITE all input fields, including CONFIRM, are cleared, the
  cursor goes to ACTIDIN, and the green message is "Transaction added successfully.  Your Tran ID is
  <16-digit id>." (two spaces after the first period). Source: `app/cbl/COTRN02C.cbl:724-734 (WRITE-TRANSACT-FILE)`.
- **BR-COTRN02C-08**: Adding a transaction changes only TRANSACT. The account balance, the category
  balance (TCATBAL), and the daily transaction file are not touched, and no credit-limit, card
  expiry, card status, or account status check is made. Source: `app/cbl/COTRN02C.cbl:442-466, 711-749`.
- **BR-COTRN02C-09**: PF5 fills the form from the record with the highest `TRAN-ID` in the whole file
  (not the selected account's last transaction) and then processes the form as if ENTER had been
  pressed. The card and account come from the key fields, not from the copied record. Timestamps are
  cut to their first 10 characters. Source: `app/cbl/COTRN02C.cbl:471-495 (COPY-LAST-TRAN-DATA)`.
- **BR-COTRN02C-10**: PF4 clears every input field, including CONFIRM, and the message, and puts the
  cursor on ACTIDIN. Source: `app/cbl/COTRN02C.cbl:754-779 (CLEAR-CURRENT-SCREEN, INITIALIZE-ALL-FIELDS)`.
- **BR-COTRN02C-11**: Every send fills the header (titles, `CT02`, `COTRN02C`, `MM/DD/YY`, `HH:MM:SS`).
  Source: `app/cbl/COTRN02C.cbl:552-571 (POPULATE-HEADER-INFO)`.

### Validation rules
The first failing rule sends its message, with the cursor on the named field, and ends the task.
- **VR-COTRN02C-01**: A non-blank ACTIDIN must pass the COBOL NUMERIC test on all 11 positions (so it
  must be 11 digits). Otherwise "Account ID must be Numeric..." (cursor ACTIDIN).
  Source: `app/cbl/COTRN02C.cbl:196-203 (VALIDATE-INPUT-KEY-FIELDS)`.
- **VR-COTRN02C-02**: The account must have a record in CXACAIX. On NOTFND: "Account ID NOT found..."
  (cursor ACTIDIN). Source: `app/cbl/COTRN02C.cbl:591-596 (READ-CXACAIX-FILE)`.
- **VR-COTRN02C-03**: When ACTIDIN is blank, a non-blank CARDNIN must be NUMERIC on all 16 positions.
  Otherwise "Card Number must be Numeric..." (cursor CARDNIN).
  Source: `app/cbl/COTRN02C.cbl:210-217 (VALIDATE-INPUT-KEY-FIELDS)`.
- **VR-COTRN02C-04**: The card must have a record in CCXREF. On NOTFND: "Card Number NOT found..."
  (cursor CARDNIN). Source: `app/cbl/COTRN02C.cbl:624-629 (READ-CCXREF-FILE)`.
- **VR-COTRN02C-05**: At least one of ACTIDIN and CARDNIN must be entered. Otherwise
  "Account or Card Number must be entered..." (cursor ACTIDIN).
  Source: `app/cbl/COTRN02C.cbl:224-229 (VALIDATE-INPUT-KEY-FIELDS)`.
- **VR-COTRN02C-06**: TTYPCD must not be spaces or LOW-VALUES; otherwise "Type CD can NOT be empty..." (cursor TTYPCD).
  Source: `app/cbl/COTRN02C.cbl:252-257 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-07**: TCATCD must not be spaces or LOW-VALUES; otherwise "Category CD can NOT be empty..." (cursor TCATCD).
  Source: `app/cbl/COTRN02C.cbl:258-263 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-08**: TRNSRC must not be spaces or LOW-VALUES; otherwise "Source can NOT be empty..." (cursor TRNSRC).
  Source: `app/cbl/COTRN02C.cbl:264-269 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-09**: TDESC must not be spaces or LOW-VALUES; otherwise "Description can NOT be empty..." (cursor TDESC).
  Source: `app/cbl/COTRN02C.cbl:270-275 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-10**: TRNAMT must not be spaces or LOW-VALUES; otherwise "Amount can NOT be empty..." (cursor TRNAMT).
  Source: `app/cbl/COTRN02C.cbl:276-281 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-11**: TORIGDT must not be spaces or LOW-VALUES; otherwise "Orig Date can NOT be empty..." (cursor TORIGDT).
  Source: `app/cbl/COTRN02C.cbl:282-287 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-12**: TPROCDT must not be spaces or LOW-VALUES; otherwise "Proc Date can NOT be empty..." (cursor TPROCDT).
  Source: `app/cbl/COTRN02C.cbl:288-293 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-13**: MID must not be spaces or LOW-VALUES; otherwise "Merchant ID can NOT be empty..." (cursor MID).
  Source: `app/cbl/COTRN02C.cbl:294-299 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-14**: MNAME must not be spaces or LOW-VALUES; otherwise "Merchant Name can NOT be empty..." (cursor MNAME).
  Source: `app/cbl/COTRN02C.cbl:300-305 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-15**: MCITY must not be spaces or LOW-VALUES; otherwise "Merchant City can NOT be empty..." (cursor MCITY).
  Source: `app/cbl/COTRN02C.cbl:306-311 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-16**: MZIP must not be spaces or LOW-VALUES; otherwise "Merchant Zip can NOT be empty..." (cursor MZIP).
  Source: `app/cbl/COTRN02C.cbl:312-317 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-17**: TTYPCD must be NUMERIC on both positions. Otherwise "Type CD must be Numeric..."
  (cursor TTYPCD). Source: `app/cbl/COTRN02C.cbl:323-328 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-18**: TCATCD must be NUMERIC on all 4 positions. Otherwise
  "Category CD must be Numeric..." (cursor TCATCD). Source: `app/cbl/COTRN02C.cbl:329-334 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-19**: TRNAMT must have `+` or `-` in position 1, digits in positions 2-9, `.` in
  position 10, and digits in positions 11-12 (for example `-00000050.00`). Otherwise
  "Amount should be in format -99999999.99" (cursor TRNAMT). Zero is allowed, and the sign is not
  related to the transaction type. Source: `app/cbl/COTRN02C.cbl:339-351 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-20**: TORIGDT must be 4 digits, `-`, 2 digits, `-`, 2 digits. Otherwise
  "Orig Date should be in format YYYY-MM-DD" (cursor TORIGDT).
  Source: `app/cbl/COTRN02C.cbl:353-366 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-21**: TPROCDT must have the same format. Otherwise
  "Proc Date should be in format YYYY-MM-DD" (cursor TPROCDT).
  Source: `app/cbl/COTRN02C.cbl:368-381 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-22**: TORIGDT must be a calendar date according to CSUTLDTC (format `YYYY-MM-DD`):
  severity `0000`, or message number `2513` (outside the supported range, accepted; Q-COTRN02C-12).
  Otherwise "Orig Date - Not a valid date..." (cursor TORIGDT).
  Source: `app/cbl/COTRN02C.cbl:389-407 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-23**: TPROCDT must pass the same check. Otherwise "Proc Date - Not a valid date..."
  (cursor TPROCDT). Source: `app/cbl/COTRN02C.cbl:409-427 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-24**: MID must be NUMERIC on all 9 positions. Otherwise
  "Merchant ID must be Numeric..." (cursor MID). Source: `app/cbl/COTRN02C.cbl:430-436 (VALIDATE-INPUT-DATA-FIELDS)`.
- **VR-COTRN02C-25**: The user must confirm. CONFIRM `N`, `n`, spaces, or LOW-VALUES gives
  "Confirm to add this transaction..." (cursor CONFIRM) and nothing is written.
  Source: `app/cbl/COTRN02C.cbl:173-181 (PROCESS-ENTER-KEY)`.
- **VR-COTRN02C-26**: Any CONFIRM value other than `Y`, `y`, `N`, `n`, or blank gives
  "Invalid value. Valid values are (Y/N)..." (cursor CONFIRM).
  Source: `app/cbl/COTRN02C.cbl:182-187 (PROCESS-ENTER-KEY)`.

No rule checks the type or category against the TRANTYPE or TRANCATG reference files, the source
value, the zip code format, or the relation between the two dates.

### Navigation and keys
- **NAV-COTRN02C-01**: IF `EIBCALEN = 0`, XCTL to `COSGN00C`.
  Source: `app/cbl/COTRN02C.cbl:115-117 (MAIN-PARA)`, `:500-511 (RETURN-TO-PREV-SCREEN)`.
- **NAV-COTRN02C-02**: First entry (context 0): set the context to 1 and show the empty form with the
  cursor on ACTIDIN. Source: `app/cbl/COTRN02C.cbl:120-130 (MAIN-PARA)`.
- **NAV-COTRN02C-03**: ENTER validates the form and asks for, or acts on, the confirmation.
  Source: `app/cbl/COTRN02C.cbl:134-135 (MAIN-PARA)`.
- **NAV-COTRN02C-04**: PF3 transfers by XCTL to `CDEMO-FROM-PROGRAM`, or `COMEN01C` when it is blank.
  From the menu this is COMEN01C. Source: `app/cbl/COTRN02C.cbl:136-143 (MAIN-PARA)`.
- **NAV-COTRN02C-05**: PF4 clears the form (BR-COTRN02C-10). Source: `app/cbl/COTRN02C.cbl:144-145 (MAIN-PARA)`.
- **NAV-COTRN02C-06**: PF5 copies the last transaction and processes the form (BR-COTRN02C-09).
  Source: `app/cbl/COTRN02C.cbl:146-147 (MAIN-PARA)`.
- **NAV-COTRN02C-07**: Any other AID shows "Invalid key pressed. Please see below..." (the 50-character
  constant). The entered values are sent back unchanged. For CLEAR and PA1-PA3 the terminal sends no
  field data: RECEIVE gets MAPFAIL (not tested), the map keeps its initial storage, and every field is
  blank on the re-sent screen. Target: C-SHORTREAD (ledger-online.md). Source:
  `app/cbl/COTRN02C.cbl:148-151 (MAIN-PARA)`.
- **NAV-COTRN02C-08**: Before every XCTL the program sets FROM-TRANID `CT02`, FROM-PROGRAM `COTRN02C`,
  and context 0. Source: `app/cbl/COTRN02C.cbl:500-511 (RETURN-TO-PREV-SCREEN)`.
- **NAV-COTRN02C-09**: Every turn that does not XCTL ends with RETURN TRANSID `CT02` and the COMMAREA,
  either right after the send or at the end of MAIN-PARA.
  Source: `app/cbl/COTRN02C.cbl:156-159 (MAIN-PARA)`, `:530-534 (SEND-TRNADD-SCREEN)`.

### Error handling
- **ERR-COTRN02C-01**: READ CXACAIX with a response other than NORMAL or NOTFND (including DUPKEY when
  several cards share the account; Q-COTRN02C-07) shows "Unable to lookup Acct in XREF AIX file..."
  (cursor ACTIDIN). RESP and RESP2 are written to the log with DISPLAY.
  Source: `app/cbl/COTRN02C.cbl:597-603 (READ-CXACAIX-FILE)`.
- **ERR-COTRN02C-02**: READ CCXREF with a response other than NORMAL or NOTFND shows
  "Unable to lookup Card # in XREF file..." (cursor CARDNIN), with a DISPLAY of RESP and RESP2.
  Source: `app/cbl/COTRN02C.cbl:630-636 (READ-CCXREF-FILE)`.
- **ERR-COTRN02C-03**: STARTBR NOTFND (TRANSACT is empty) shows "Transaction ID NOT found..." (cursor
  ACTIDIN). No transaction can be added to an empty file (Q-COTRN02C-13).
  Source: `app/cbl/COTRN02C.cbl:655-660 (STARTBR-TRANSACT-FILE)`.
- **ERR-COTRN02C-04**: STARTBR with any other error, or READPREV with an error other than ENDFILE,
  shows "Unable to lookup Transaction..." (cursor ACTIDIN), with a DISPLAY of RESP and RESP2. The
  browse is left open and ends with the task.
  Source: `app/cbl/COTRN02C.cbl:661-667, 690-696 (STARTBR-TRANSACT-FILE, READPREV-TRANSACT-FILE)`.
- **ERR-COTRN02C-05**: READPREV ENDFILE is not an error: `TRAN-ID` is set to zeros, so the new ID is 1.
  Source: `app/cbl/COTRN02C.cbl:688-689 (READPREV-TRANSACT-FILE)`.
- **ERR-COTRN02C-06**: ENDBR has no RESP option. Any exception abends the task (AEIP).
  Source: `app/cbl/COTRN02C.cbl:702-706 (ENDBR-TRANSACT-FILE)`.
- **ERR-COTRN02C-07**: WRITE DUPKEY or DUPREC (another task took the same ID) shows
  "Tran ID already exist..." (cursor ACTIDIN). The form keeps its values, so pressing ENTER again
  with `Y` retries with a new ID. Source: `app/cbl/COTRN02C.cbl:735-741 (WRITE-TRANSACT-FILE)`.
- **ERR-COTRN02C-08**: WRITE with any other error shows "Unable to Add Transaction..." (cursor ACTIDIN),
  with a DISPLAY of RESP and RESP2. Source: `app/cbl/COTRN02C.cbl:742-748 (WRITE-TRANSACT-FILE)`.
- **ERR-COTRN02C-09**: The RESP of RECEIVE MAP is captured but never tested.
  Source: `app/cbl/COTRN02C.cbl:539-547 (RECEIVE-TRNADD-SCREEN)`.

## Data effects
- **TRANSACT**: one new record per confirmed add (BR-COTRN02C-06). The file is defined with
  RECOVERY(NONE) in the CSD, so there is no backout. No record is updated or deleted.
- **CXACAIX, CCXREF**: read only.
- **ACCTDAT, TCATBAL, DALYTRAN**: not touched. The new transaction never reaches an account balance
  or a category balance, because those are changed only by the POSTTRAN batch (CBTRN02C), which reads
  the daily transaction file, not TRANSACT. It does appear in the transaction list and view (COTRN00C,
  COTRN01C), in the transaction report (CORPT00C / TRANREPT, which filters on `TRAN-PROC-TS`), and in
  the other batch jobs that read TRANSACT.

## Quirks
- **Q-COTRN02C-01**: The new ID is "highest key + 1" with no lock or sequence, the same scheme as
  COBIL00C (Q-COBIL00C-01). Two tasks adding at the same time can compute the same ID; the second
  WRITE then fails with "Tran ID already exist...". Recommendation: fix (database sequence or
  identity). Source: `app/cbl/COTRN02C.cbl:444-451, 735-741`.
- **Q-COTRN02C-02**: The transaction bypasses posting (BR-COTRN02C-08): no balance update, no
  credit-limit check, no card expiry or status check. The same applies to bill payments in COBIL00C,
  except that COBIL00C does update the account balance. Recommendation: decide (most likely route
  online adds through the posting rules). Source: `app/cbl/COTRN02C.cbl:442-466`.
- **Q-COTRN02C-03**: Both timestamps are stored as a 10-character date followed by spaces, while batch
  records hold 26-character timestamps (`YYYY-MM-DD HH:MM:SS.ffffff`). The processing date is typed
  by the user, so it can be in the past or the future, and the transaction report selects records by
  that date. Recommendation: decide (most likely set the processing timestamp by the system and store
  real timestamps). Source: `app/cbl/COTRN02C.cbl:464-465`.
- **Q-COTRN02C-04**: PF5 copies the last record of the whole file, not of the chosen account, and
  then runs the full ENTER processing. If CONFIRM already holds `Y`, the copied transaction is added
  in the same keystroke without the user reviewing it. Recommendation: fix (copy, then stop and show
  the form). Source: `app/cbl/COTRN02C.cbl:471-495`.
- **Q-COTRN02C-05**: With the sample TRANSACT (all `TRAN-PROC-TS` blank), PF5 always ends with
  "Proc Date can NOT be empty...". Recommendation: preserve the behaviour of not inventing a date;
  covered by Q-COTRN02C-04 otherwise. Source: `app/cbl/COTRN02C.cbl:488, 288-293`.
- **Q-COTRN02C-06**: When both account and card are typed, the card is silently replaced by the card
  found for the account; no check that they belong together. When only a card is typed, the first
  ENTER fills in the account, and on the confirming ENTER the account takes precedence and the card is
  derived again from the account. For an account with several cards, a different card from the one
  typed could then be stored. Recommendation: fix (validate that the pair matches, and keep the card
  that was typed). Source: `app/cbl/COTRN02C.cbl:195-223`.
- **Q-COTRN02C-07**: CXACAIX is a non-unique alternate index. For an account with more than one card,
  CICS READ returns DUPKEY, which falls into the "Unable to lookup Acct in XREF AIX file..." branch
  (see Q-COBIL00C-11). In the sample data each account has one card. Recommendation: fix.
  Source: `app/cbl/COTRN02C.cbl:578-603`.
- **Q-COTRN02C-08**: The NUMERIC tests include trailing blanks, so account (11), card (16), type (2),
  category (4), and merchant ID (9) must be typed at full length with leading zeros: `1` as an
  account gives "Account ID must be Numeric...", and type `1` gives "Type CD must be Numeric...".
  Recommendation: decide (accept shorter numbers and left-pad them).
  Source: `app/cbl/COTRN02C.cbl:197, 211, 323, 329, 430`.
- **Q-COTRN02C-09**: Type and category codes are not checked against TRANTYPE and TRANCATG, the source
  is free text, an amount of zero is accepted, and a purchase can carry a negative amount.
  Recommendation: decide (validate codes against the reference data). Source: `app/cbl/COTRN02C.cbl:251-351`.
- **Q-COTRN02C-10**: Dead code: the first-entry prefill from `CDEMO-CT02-TRN-SELECTED` (no program sets
  it; it would put a transaction ID into the card field); the block at lines 237-249 that blanks the
  data fields after a key error (unreachable, because every error ends the task); the unused ACCTDAT
  file name, CVACT01Y, and the paging fields of the extension. Recommendation: drop.
  Source: `app/cbl/COTRN02C.cbl:40, 72-80, 89, 124-129, 237-249`.
- **Q-COTRN02C-11**: Because each send is followed by RETURN, only the first failing rule is reported,
  and control never comes back to the calling paragraph. Recommendation: preserve the "one message at
  a time" behaviour if users expect it, but implement it as normal control flow; decide whether to
  report all errors at once. Source: `app/cbl/COTRN02C.cbl:516-534`.
- **Q-COTRN02C-12**: Dates outside the CEEDAYS range (message 2513), such as `1500-01-01`, are accepted
  (Q-CSUTLDTC-05). Recommendation: decide (most likely reject). Source: `app/cbl/COTRN02C.cbl:400, 420`.
- **Q-COTRN02C-13**: If TRANSACT is empty, STARTBR at HIGH-VALUES returns NOTFND and the user sees
  "Transaction ID NOT found...", so the first transaction can never be added online (as in
  Q-COBIL00C-12). Recommendation: fix. Source: `app/cbl/COTRN02C.cbl:655-660`.
- **Q-COTRN02C-14**: The option is open to regular users; a commented-out menu label says it was once
  "Admin Only". Recommendation: decide the authorization rule. Source: `app/cpy/COMEN02Y.cpy:67-72`.
- **Q-COTRN02C-15**: The full card number is shown in CARDNIN after it is derived from the account.
  Recommendation: decide (mask). Source: `app/cbl/COTRN02C.cbl:209`.

## Test scenarios
Assumptions: TRANSACT holds the 300 records of `app/data/ASCII/dailytran.txt` (highest ID
`0000000996722787`, every `TRAN-PROC-TS` blank). Account `00000000001` has one card, `************3697`,
and a current balance of 194.00. "The sample form" means: type `01`, category `0001`, source
`POS TERM`, description `Purchase at Test Store`, amount `+00000025.50`, orig date `2022-06-15`, proc
date `2022-06-15`, merchant ID `800000001`, name `Test Store`, city `Seattle`, zip `98101`.
- **TS-COTRN02C-01** (covers BR-COTRN02C-01, -02, -03, VR-COTRN02C-25): Given account `00000000001`
  and the sample form with CONFIRM blank, when the user presses ENTER, then CARDNIN shows the card
  `************3697`, the amount shows `+00000025.50`, the message is
  "Confirm to add this transaction...", and TRANSACT still has 300 records.
- **TS-COTRN02C-02** (covers BR-COTRN02C-04, -05, -06, -07, -08): Given TS-COTRN02C-01, when the user
  types `Y` and presses ENTER, then the green message is
  "Transaction added successfully.  Your Tran ID is 0000000996722788.", every input field is blank,
  and TRANSACT holds a new record `0000000996722788` with card `************3697`, type `01`, category
  `0001`, amount 25.50, merchant `800000001`, and `TRAN-ORIG-TS` = `TRAN-PROC-TS` = `2022-06-15`
  followed by 16 spaces. Account `00000000001` still has a balance of 194.00.
- **TS-COTRN02C-03** (covers BR-COTRN02C-01): Given ACTIDIN blank, CARDNIN = the card of account
  `00000000001`, and the sample form, when the user presses ENTER, then ACTIDIN shows `00000000001`.
- **TS-COTRN02C-04** (covers VR-COTRN02C-05): Given both key fields blank, when the user presses ENTER,
  then the message is "Account or Card Number must be entered...".
- **TS-COTRN02C-05** (covers VR-COTRN02C-01, Q-COTRN02C-08): Account `ABC` gives
  "Account ID must be Numeric...". Account `1` gives the same message.
- **TS-COTRN02C-06** (covers VR-COTRN02C-02, -04): Account `99999999999` gives
  "Account ID NOT found...". With ACTIDIN blank, card `0000000000000000` gives "Card Number NOT found...".
- **TS-COTRN02C-07** (covers VR-COTRN02C-06, BR-COTRN02C-02): Given account `00000000001` and every
  data field blank, when the user presses ENTER, then the message is "Type CD can NOT be empty..." and
  CARDNIN has been filled in.
- **TS-COTRN02C-08** (covers VR-COTRN02C-17, -19): With the sample form and type `1`, the message is
  "Type CD must be Numeric...". With the sample form and amount `25.50`, the message is
  "Amount should be in format -99999999.99".
- **TS-COTRN02C-09** (covers VR-COTRN02C-20, -22, -23, Q-COTRN02C-12): With orig date `06/15/2022`:
  "Orig Date should be in format YYYY-MM-DD". With orig date `2023-02-29`: "Orig Date - Not a valid
  date...". With proc date `2022-06-31`: "Proc Date - Not a valid date...". With proc date
  `1500-01-01`: the date is accepted and the confirmation prompt appears.
- **TS-COTRN02C-10** (covers VR-COTRN02C-24): With merchant ID `ABC`, the message is
  "Merchant ID must be Numeric...".
- **TS-COTRN02C-11** (covers VR-COTRN02C-25, -26): With the sample form and CONFIRM `X`, the message is
  "Invalid value. Valid values are (Y/N)...". With CONFIRM `N`, the message is
  "Confirm to add this transaction..." and nothing is written.
- **TS-COTRN02C-12** (covers BR-COTRN02C-09, Q-COTRN02C-05): Given account `00000000001` and the other
  fields blank, when the user presses PF5, then the form shows type `01`, category `0001`, source
  `POS TERM`, amount `+00000603.22`, description `Purchase at Kilback LLC`, orig date `2022-06-10`,
  proc date blank, merchant `800000000`, `Kilback LLC`, `Cummeratamouth`, `53200-7529`, and the message
  is "Proc Date can NOT be empty...".
- **TS-COTRN02C-13** (covers Q-COTRN02C-04): Given TS-COTRN02C-02 has run (the last record now has proc
  date `2022-06-15`), when the user types account `00000000001` and CONFIRM `Y` and presses PF5, then
  a copy is added at once with the message "Transaction added successfully.  Your Tran ID is
  0000000996722789.". Expected in the target system: the copy is shown for review and nothing is
  written.
- **TS-COTRN02C-14** (covers BR-COTRN02C-10, NAV-COTRN02C-04, -07): PF4 clears every field and the
  message. PF3 shows the main menu. PF7 shows "Invalid key pressed. Please see below..." and keeps
  the entered values.
- **TS-COTRN02C-15** (covers Q-COTRN02C-01, ERR-COTRN02C-07): Given two users confirm a valid form at
  the same moment, then one gets the success message and the other may get "Tran ID already exist...".
  This is inferred from the ID scheme and needs a concurrency test to observe. Expected in the target
  system: both succeed with different IDs.

## Open questions
- Should online-added transactions go through the posting rules (balance update, credit limit, card
  status), and should the processing date be set by the system (Q-COTRN02C-02, -03)?
- Who may add transactions: every user, or administrators only (Q-COTRN02C-14)?
- Should type and category be validated against TRANTYPE and TRANCATG, and should the sign of the
  amount follow the type (Q-COTRN02C-09)?
