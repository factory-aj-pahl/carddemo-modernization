# COTRN01C: View the details of one transaction from TRANSACT by transaction ID

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COTRN01C.cbl` (330 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CT01 (`app/csd/CARDDEMO.CSD:429-430`) |
| BMS map | mapset `COTRN01` / map `COTRN1A` (`app/bms/COTRN01.bms`, symbolic map `app/cpy-bms/COTRN01.CPY`) |
| Copybooks | COCOM01Y (plus the program-local extension `CDEMO-CT01-INFO`), COTRN01, COTTL01Y, CSDAT01Y, CSMSG01Y, CVTRA05Y, DFHAID, DFHBMSCA |
| Calls / XCTL targets | XCTL `COTRN00C` (PF5); XCTL to `CDEMO-FROM-PROGRAM` on PF3, which is `COTRN00C` or `COMEN01C` in practice, or `COMEN01C` when it is blank; XCTL `COSGN00C` (no COMMAREA). No CALLs. |
| Data stores | `TRANSACT` (ENT-TRANSACTION): READ with UPDATE by key `TRAN-ID`. Nothing is written. |

## Purpose
Shows every field of one transaction record. The user types a 16-digit transaction ID and presses
ENTER, or arrives from the transaction list (COTRN00C) with a row already selected, in which case the
record is shown at once. The screen is read-only apart from the ID field. From here the user can
return (PF3), clear the screen (PF4), or open the transaction list (PF5).

## Invocation and state
- Entered by XCTL from:
  - `COTRN00C`, when the user marks a row with `S`. The 218-byte COMMAREA carries the selected ID in
    `CDEMO-CT00-TRN-SELECTED`, at the same offset as `CDEMO-CT01-TRN-SELECTED` here. FROM-PROGRAM is
    `COTRN00C` and the context is 0.
  - `COMEN01C`, menu option 7 "Transaction View" (user type `U`, so regular users and admins see it).
    The menu sends the 160-byte COMMAREA. The group move pads the extension with spaces, so no ID is
    preselected and the form is empty.
- If `EIBCALEN = 0` (for example, CT01 typed on a clear screen), the program transfers to `COSGN00C`.
- The COMMAREA is 218 bytes: COCOM01Y (160 bytes) followed by `CDEMO-CT01-INFO`, which has the same
  layout as `CDEMO-CT00-INFO` in COTRN00C:

  | Field | PIC | Bytes | Use in this program |
  |:--|:--|:--|:--|
  | `CDEMO-CT01-TRNID-FIRST` | X(16) | 161-176 | Not used; passed through unchanged |
  | `CDEMO-CT01-TRNID-LAST` | X(16) | 177-192 | Not used; passed through unchanged |
  | `CDEMO-CT01-PAGE-NUM` | 9(8) | 193-200 | Not used; passed through unchanged |
  | `CDEMO-CT01-NEXT-PAGE-FLG` | X(1) | 201 | Not used; passed through unchanged |
  | `CDEMO-CT01-TRN-SEL-FLG` | X(1) | 202 | Not used; passed through unchanged |
  | `CDEMO-CT01-TRN-SELECTED` | X(16) | 203-218 | ID to show automatically on first entry |

- `CDEMO-FROM-PROGRAM` is set by the calling program and is not changed by RETURN, so it still names
  the caller on later turns. PF3 uses it.
- The record is re-read on every ENTER. Nothing about the transaction is kept between turns.

## Screen
After selecting the second transaction of the sample file in COTRN00C. The real screen shows the
card number in full; it is masked here.

```text
         1         2         3         4         5         6         7         8
12345678901234567890123456789012345678901234567890123456789012345678901234567890
 Tran: CT01                AWS Mainframe Modernization           Date: 06/15/22
 Prog: COTRN01C                    CardDemo                      Time: 10:30:00

                              View Transaction

      Enter Tran ID: 0000000001774260

      ----------------------------------------------------------------------

      Transaction ID: 0000000001774260       Card Number: ************6232

      Type CD: 03      Category CD: 0001      Source: OPERATOR

      Description: Return item at Nitzsche, Nicolas and Lowe

      Amount: -00000919.00     Orig Date: 2022-06-10     Proc Date:

      Merchant ID: 800000000     Merchant Name: Nitzsche, Nicolas and Lowe

      Merchant City: Fidelshire                      Merchant Zip: 53378



 ENTER=Fetch  F3=Back  F4=Clear  F5=Browse Tran.
```

Positions are the (row, column) of the first data character.

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Header | TRNNAME, TITLE01, CURDATE, PGMNAME, TITLE02, CURTIME | 4/40/8/8/40/8 | Out | rows 1-2, as in all CardDemo screens | `CT01`, titles, `MM/DD/YY`, `COTRN01C`, `HH:MM:SS` |
| Enter Tran ID | TRNIDIN | 16 | In | (6,22) UNPROT FSET IC, green, underline | `TRAN-ID` to look up; used exactly as typed |
| Transaction ID | TRNID | 16 | Out | (10,23) ASKIP, blue | `TRAN-ID` |
| Card Number | CARDNUM | 16 | Out | (10,59) ASKIP, blue | `TRAN-CARD-NUM`, unmasked |
| Type CD | TTYPCD | 2 | Out | (12,16) ASKIP, blue | `TRAN-TYPE-CD` |
| Category CD | TCATCD | 4 | Out | (12,37) ASKIP, blue | `TRAN-CAT-CD` |
| Source | TRNSRC | 10 | Out | (12,55) ASKIP, blue | `TRAN-SOURCE` |
| Description | TDESC | 60 | Out | (14,20) ASKIP, blue | `TRAN-DESC`, characters 1-60 of 100 |
| Amount | TRNAMT | 12 | Out | (16,15) ASKIP, blue | `TRAN-AMT` edited as `+99999999.99` |
| Orig Date | TORIGDT | 10 | Out | (16,43) ASKIP, blue | `TRAN-ORIG-TS`, characters 1-10 (the date) |
| Proc Date | TPROCDT | 10 | Out | (16,69) ASKIP, blue | `TRAN-PROC-TS`, characters 1-10 (the date) |
| Merchant ID | MID | 9 | Out | (18,20) ASKIP, blue | `TRAN-MERCHANT-ID` |
| Merchant Name | MNAME | 30 | Out | (18,49) ASKIP, blue | `TRAN-MERCHANT-NAME`, characters 1-30 of 50 |
| Merchant City | MCITY | 25 | Out | (20,22) ASKIP, blue | `TRAN-MERCHANT-CITY`, characters 1-25 of 50 |
| Merchant Zip | MZIP | 10 | Out | (20,68) ASKIP, blue | `TRAN-MERCHANT-ZIP` |
| Message | ERRMSG | 78 | Out | (23,2) ASKIP BRT FSET, red | Messages |

The output fields are ASKIP without FSET, so they are not returned by RECEIVE. Constant text:
"View Transaction" (4,31); "Enter Tran ID:" (6,7); a dashed rule on row 8; the field labels; and
"ENTER=Fetch  F3=Back  F4=Clear  F5=Browse Tran." (24,2).

## Processing flow
1. **MAIN-PARA**: clear the error flag and the message. IF `EIBCALEN = 0`, XCTL to `COSGN00C`.
2. Copy the COMMAREA. IF the context is not 1 (first entry): set it to 1, clear the map, and put the
   cursor on TRNIDIN. IF `CDEMO-CT01-TRN-SELECTED` is neither spaces nor LOW-VALUES, copy it into
   TRNIDIN and run PROCESS-ENTER-KEY (which sends the screen). Then send the screen (a second send
   when an ID was preselected; Q-COTRN01C-02).
3. OTHERWISE, RECEIVE the map and evaluate the AID:
   - ENTER runs PROCESS-ENTER-KEY.
   - PF3 sets TO-PROGRAM to `CDEMO-FROM-PROGRAM`, or to `COMEN01C` when that is blank, and transfers.
   - PF4 runs CLEAR-CURRENT-SCREEN.
   - PF5 sets TO-PROGRAM to `COTRN00C` and transfers.
   - Any other key sets the error flag and sends the invalid-key message.
4. **PROCESS-ENTER-KEY**:
   1. IF TRNIDIN is spaces or LOW-VALUES, set the error flag and send "Tran ID can NOT be empty...".
      In every case put the cursor on TRNIDIN.
   2. IF there is no error: blank all output fields, move TRNIDIN to `TRAN-ID`, and run
      READ-TRANSACT-FILE.
   3. IF there is still no error: edit `TRAN-AMT` into `+99999999.99`, move the record fields to the
      screen (BR-COTRN01C-02), and send the screen.
5. **READ-TRANSACT-FILE**: READ TRANSACT with UPDATE, key `TRAN-ID` (16 bytes). NORMAL continues.
   NOTFND and other responses set the error flag and send a message (VR-COTRN01C-02, ERR-COTRN01C-01).
6. **CLEAR-CURRENT-SCREEN**: blank TRNIDIN, every output field, and the message, put the cursor on
   TRNIDIN, and send.
7. **RETURN-TO-PREV-SCREEN**: IF TO-PROGRAM is blank, use `COSGN00C`. Set FROM-TRANID `CT01`,
   FROM-PROGRAM `COTRN01C`, and context 0, then XCTL with the COMMAREA.
8. Every turn that does not XCTL ends with RETURN TRANSID `CT01` and the 218-byte COMMAREA. Every
   send uses ERASE and CURSOR.

## Rules
### Business rules
- **BR-COTRN01C-01**: The transaction is found by an exact match of the 16-character key, using the
  content of TRNIDIN as typed. There is no numeric check, no padding, and no trimming.
  Source: `app/cbl/COTRN01C.cbl:172-173 (PROCESS-ENTER-KEY)`, `:267-278 (READ-TRANSACT-FILE)`.
- **BR-COTRN01C-02**: Display mapping (all from the TRANSACT record, layout CVTRA05Y):
  - TRNID = `TRAN-ID`; CARDNUM = `TRAN-CARD-NUM` (full 16 digits).
  - TTYPCD = `TRAN-TYPE-CD`; TCATCD = `TRAN-CAT-CD`; TRNSRC = `TRAN-SOURCE`. Codes only, without
    descriptions.
  - TDESC = `TRAN-DESC` characters 1-60.
  - TRNAMT = `TRAN-AMT` with picture `+99999999.99`: the sign is always shown and leading zeros are
    kept, so -919.00 shows as `-00000919.00`.
  - TORIGDT and TPROCDT = characters 1-10 of the 26-character timestamps, which is the `YYYY-MM-DD`
    part. A blank timestamp shows as blank.
  - MID = `TRAN-MERCHANT-ID`; MNAME = name characters 1-30; MCITY = city characters 1-25;
    MZIP = `TRAN-MERCHANT-ZIP`.

  Source: `app/cbl/COTRN01C.cbl:176-191 (PROCESS-ENTER-KEY)`.
- **BR-COTRN01C-03**: On first entry with a preselected ID (`CDEMO-CT01-TRN-SELECTED` not blank), the
  ID is placed in TRNIDIN and looked up at once, as if the user had pressed ENTER.
  Source: `app/cbl/COTRN01C.cbl:103-108 (MAIN-PARA)`.
- **BR-COTRN01C-04**: Before each lookup every output field is blanked, so a failed lookup shows no
  details from an earlier record. Source: `app/cbl/COTRN01C.cbl:158-171 (PROCESS-ENTER-KEY)`.
- **BR-COTRN01C-05**: After a lookup TRNIDIN keeps the ID that was entered.
  Source: `app/cbl/COTRN01C.cbl:144-192 (PROCESS-ENTER-KEY)` (TRNIDIN is never cleared there).
- **BR-COTRN01C-06**: Any signed-on user can view any transaction. There is no check that the
  transaction belongs to the user's account or card. Source: `app/cbl/COTRN01C.cbl:144-192`.
- **BR-COTRN01C-07**: PF4 clears TRNIDIN, every detail field, and the message, and puts the cursor on
  TRNIDIN. Source: `app/cbl/COTRN01C.cbl:301-326 (CLEAR-CURRENT-SCREEN, INITIALIZE-ALL-FIELDS)`.
- **BR-COTRN01C-08**: Every send fills the header (titles, `CT01`, `COTRN01C`, `MM/DD/YY`, `HH:MM:SS`).
  Source: `app/cbl/COTRN01C.cbl:243-262 (POPULATE-HEADER-INFO)`.

### Validation rules
- **VR-COTRN01C-01**: TRNIDIN must not be spaces or LOW-VALUES. Otherwise the message is
  "Tran ID can NOT be empty..." with the cursor on TRNIDIN, and no read is done.
  Source: `app/cbl/COTRN01C.cbl:146-152 (PROCESS-ENTER-KEY)`.
- **VR-COTRN01C-02**: The ID must exist in TRANSACT. On NOTFND the message is
  "Transaction ID NOT found..." with the cursor on TRNIDIN, and the detail fields stay blank.
  Source: `app/cbl/COTRN01C.cbl:283-288 (READ-TRANSACT-FILE)`.

### Navigation and keys
- **NAV-COTRN01C-01**: IF `EIBCALEN = 0`, XCTL to `COSGN00C`.
  Source: `app/cbl/COTRN01C.cbl:94-96 (MAIN-PARA)`, `:197-208 (RETURN-TO-PREV-SCREEN)`.
- **NAV-COTRN01C-02**: First entry (context 0): set the context to 1, show the empty form with the
  cursor on TRNIDIN, or show the preselected transaction (BR-COTRN01C-03).
  Source: `app/cbl/COTRN01C.cbl:99-109 (MAIN-PARA)`.
- **NAV-COTRN01C-03**: ENTER looks up the ID in TRNIDIN. Source: `app/cbl/COTRN01C.cbl:113-114 (MAIN-PARA)`.
- **NAV-COTRN01C-04**: PF3 transfers by XCTL to `CDEMO-FROM-PROGRAM`, or to `COMEN01C` when it is spaces
  or LOW-VALUES. Opened from the list, PF3 returns to COTRN00C; opened from the menu, it returns to
  COMEN01C. Source: `app/cbl/COTRN01C.cbl:115-122 (MAIN-PARA)`.
- **NAV-COTRN01C-05**: PF4 clears the screen (BR-COTRN01C-07). Source: `app/cbl/COTRN01C.cbl:123-124 (MAIN-PARA)`.
- **NAV-COTRN01C-06**: PF5 always transfers by XCTL to `COTRN00C`, which shows its first page
  (with the record-skip defect Q-COTRN00C-01). Source: `app/cbl/COTRN01C.cbl:125-127 (MAIN-PARA)`.
- **NAV-COTRN01C-07**: Any other AID sets the error flag and shows
  "Invalid key pressed. Please see below..." (the 50-character constant from CSMSG01Y). The screen is
  sent with erase; the output fields were not returned by RECEIVE, so the details are blank. For CLEAR
  and PA1-PA3 the terminal sends no field data: RECEIVE gets MAPFAIL (not tested), the map keeps its
  initial storage, and the Tran ID field is blank as well. Target: C-SHORTREAD (ledger-online.md).
  Source: `app/cbl/COTRN01C.cbl:128-131 (MAIN-PARA)`.
- **NAV-COTRN01C-08**: Before every XCTL the program sets FROM-TRANID `CT01`, FROM-PROGRAM `COTRN01C`,
  and context 0, and passes the 218-byte COMMAREA.
  Source: `app/cbl/COTRN01C.cbl:197-208 (RETURN-TO-PREV-SCREEN)`.
- **NAV-COTRN01C-09**: Every turn that does not XCTL ends with RETURN TRANSID `CT01` and the COMMAREA.
  Source: `app/cbl/COTRN01C.cbl:136-139 (MAIN-PARA)`.

### Error handling
- **ERR-COTRN01C-01**: READ with any response other than NORMAL or NOTFND (for example, file closed or
  disabled, or a record lock held by another task that times out) sets the error flag and shows
  "Unable to lookup Transaction..." with the cursor on TRNIDIN. RESP and RESP2 are written to the log
  with DISPLAY. Source: `app/cbl/COTRN01C.cbl:289-295 (READ-TRANSACT-FILE)`.
- **ERR-COTRN01C-02**: The RESP of RECEIVE MAP is captured but never tested.
  Source: `app/cbl/COTRN01C.cbl:230-238 (RECEIVE-TRNVIEW-SCREEN)`.

## Data effects
None on data. The READ uses the UPDATE option, so CICS holds an exclusive lock on the record until the
task ends at the RETURN at the end of the same turn (Q-COTRN01C-01). The record is never rewritten.

## Quirks
- **Q-COTRN01C-01**: The record is read with UPDATE although the program never rewrites it. Another
  task that wants the same record for update waits until this task ends, and two users viewing the
  same transaction at the same moment are serialized. Recommendation: fix (plain read).
  Source: `app/cbl/COTRN01C.cbl:269-278`.
- **Q-COTRN01C-02**: On first entry with a preselected ID, the map is sent twice in one task: once
  inside PROCESS-ENTER-KEY and once more by MAIN-PARA. Both sends carry the same content, and only the
  last one is visible. Recommendation: in the target system, return one response.
  Source: `app/cbl/COTRN01C.cbl:107-109, 152, 191`.
- **Q-COTRN01C-03**: PF5 opens COTRN00C with `EIBAID` = PF5, which makes the list skip the first
  record of the file (Q-COTRN00C-01). Recommendation: fix (in the list).
  Source: `app/cbl/COTRN01C.cbl:125-127`; `app/cbl/COTRN00C.cbl:285-287`.
- **Q-COTRN01C-04**: The ID must be typed as all 16 digits with leading zeros. `683580` is looked up as
  `683580` followed by 10 spaces and gives "Transaction ID NOT found...". Non-numeric input is not
  rejected and simply gives "not found". Recommendation: decide (accept a shorter number and left-pad
  it, and reject non-digits). Source: `app/cbl/COTRN01C.cbl:146-173`.
- **Q-COTRN01C-05**: The full card number is shown on the screen. Recommendation: decide (mask all but
  the last four digits unless the user role needs the full number). Source: `app/cbl/COTRN01C.cbl:179`.
- **Q-COTRN01C-06**: The display is lossy: the time part of both timestamps is dropped, the
  description is cut to 60 of 100 characters, the merchant name to 30 of 50, and the city to 25 of 50.
  Recommendation: decide the target display (most likely show full values).
  Source: `app/cbl/COTRN01C.cbl:184-189`; `app/bms/COTRN01.bms`.
- **Q-COTRN01C-07**: There is no scoping by user, account, or card (BR-COTRN01C-06). Recommendation:
  decide, consistently with Q-COTRN00C-08. Source: `app/cbl/COTRN01C.cbl:144-192`.
- **Q-COTRN01C-08**: Type and category are shown as codes; the TRANTYPE and TRANCATG reference files
  are not read for descriptions. Recommendation: decide (showing descriptions is a likely improvement).
  Source: `app/cbl/COTRN01C.cbl:180-181`.

## Test scenarios
Assumption: TRANSACT holds the 300 records of `app/data/ASCII/dailytran.txt`, in which every
`TRAN-PROC-TS` is blank. Card numbers are masked here; tests use the full values from the file.
- **TS-COTRN01C-01** (covers BR-COTRN01C-02, -03, NAV-COTRN01C-02): Given COTRN00C shows page 1, when
  the user marks row 2 with `S` and presses ENTER, then this screen shows TRNIDIN and TRNID
  `0000000001774260`, card `************6232`, type `03`, category `0001`, source `OPERATOR`,
  description `Return item at Nitzsche, Nicolas and Lowe`, amount `-00000919.00`, orig date
  `2022-06-10`, proc date blank, merchant `800000000`, `Nitzsche, Nicolas and Lowe`, `Fidelshire`,
  `53378`, and no message.
- **TS-COTRN01C-02** (covers NAV-COTRN01C-02): When the user selects menu option 7, then the form is
  empty, the cursor is on TRNIDIN, and there is no message.
- **TS-COTRN01C-03** (covers BR-COTRN01C-01, -02, -05, NAV-COTRN01C-03): Given the empty form, when the
  user types `0000000000683580` and presses ENTER, then the screen shows card `************7065`, type
  `01`, category `0001`, source `POS TERM`, description `Purchase at Abshire-Lowe`, amount
  `+00000504.77`, orig date `2022-06-10`, proc date blank, merchant `800000000`, `Abshire-Lowe`,
  `North Enoshaven`, `72112`. TRNIDIN still holds `0000000000683580`.
- **TS-COTRN01C-04** (covers VR-COTRN01C-01): When the user presses ENTER with TRNIDIN blank, then the
  message is "Tran ID can NOT be empty..." and the detail fields are blank.
- **TS-COTRN01C-05** (covers VR-COTRN01C-02, BR-COTRN01C-04): Given TS-COTRN01C-03 is displayed, when the
  user types `9999999999999999` and presses ENTER, then the message is "Transaction ID NOT found..." and
  every detail field is blank.
- **TS-COTRN01C-06** (covers Q-COTRN01C-04): When the user types `683580` and presses ENTER, then the
  message is "Transaction ID NOT found...". Expected in the target system: decide.
- **TS-COTRN01C-07** (covers NAV-COTRN01C-04): Given the screen was opened from COTRN00C, when the user
  presses PF3, then COTRN00C shows its page 1. Given the screen was opened from the menu, PF3 shows
  the main menu (COMEN01C).
- **TS-COTRN01C-08** (covers BR-COTRN01C-07, NAV-COTRN01C-05): Given TS-COTRN01C-03 is displayed, when
  the user presses PF4, then TRNIDIN, all details, and the message are blank.
- **TS-COTRN01C-09** (covers NAV-COTRN01C-06, Q-COTRN01C-03): When the user presses PF5, then COTRN00C
  shows page 1 starting with `0000000001774260` (record 2), because of Q-COTRN00C-01. Expected in the
  target system: page 1 starts with `0000000000683580`.
- **TS-COTRN01C-10** (covers NAV-COTRN01C-07): When the user presses PF7, then the message is
  "Invalid key pressed. Please see below..." and the details are blank.
- **TS-COTRN01C-11** (covers NAV-COTRN01C-01): When CT01 is started with no COMMAREA, then control
  passes to COSGN00C (sign-on screen).

## Open questions
- Should regular users be limited to transactions of their own cards (Q-COTRN01C-07)?
- Is the full card number needed on this screen for any role (Q-COTRN01C-05)?
