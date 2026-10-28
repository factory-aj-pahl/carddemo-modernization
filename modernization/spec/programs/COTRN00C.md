# COTRN00C: Paged list of all transactions in TRANSACT, with selection for detail view

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COTRN00C.cbl` (699 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CT00 (`app/csd/CARDDEMO.CSD:419-422`) |
| BMS map | mapset `COTRN00` / map `COTRN0A` (`app/bms/COTRN00.bms`, symbolic map `app/cpy-bms/COTRN00.CPY`) |
| Copybooks | COCOM01Y (plus the program-local extension `CDEMO-CT00-INFO`), COTRN00, COTTL01Y, CSDAT01Y, CSMSG01Y, CVTRA05Y, DFHAID, DFHBMSCA |
| Calls / XCTL targets | XCTL `COTRN01C` (row selected with `S`); XCTL `COMEN01C` (PF3); XCTL `COSGN00C` (no COMMAREA). No CALLs. |
| Data stores | `TRANSACT` (ENT-TRANSACTION): Browse only (STARTBR, READNEXT, READPREV, ENDBR), key `TRAN-ID`. Read-only. |

## Purpose
Shows transactions from the online transaction file, 10 per page, in ascending transaction-ID order.
Transactions of every account and card appear; nothing restricts the list to the signed-on user. The
user can type a transaction ID to start the list at that ID, page forward (PF8) and backward (PF7),
and type `S` next to a row to open it in the transaction view (COTRN01C).

## Invocation and state
- Entered by XCTL from:
  - `COMEN01C`, menu option 6 "Transaction List", with the 160-byte COMMAREA and context 0.
  - `COTRN01C`, by PF3 when the view was opened from this list, or by PF5 at any time, with the
    218-byte COMMAREA and context 0.
  On these entries `EIBAID` is the key the user pressed in the previous program (ENTER, PF3, or PF5).
  That value affects the first page (Q-COTRN00C-01).
- If `EIBCALEN = 0`, the program transfers to `COSGN00C`.
- The COMMAREA is 218 bytes: COCOM01Y (160 bytes) followed by `CDEMO-CT00-INFO`:

  | Field | PIC | Bytes | Meaning |
  |:--|:--|:--|:--|
  | `CDEMO-CT00-TRNID-FIRST` | X(16) | 161-176 | `TRAN-ID` shown in row 1 of the current page |
  | `CDEMO-CT00-TRNID-LAST` | X(16) | 177-192 | `TRAN-ID` shown in row 10 of the current page |
  | `CDEMO-CT00-PAGE-NUM` | 9(8) | 193-200 | Page number shown in PAGENUM |
  | `CDEMO-CT00-NEXT-PAGE-FLG` | X(1) | 201 | `Y` when PF8 may page forward |
  | `CDEMO-CT00-TRN-SEL-FLG` | X(1) | 202 | Selection character found on the last ENTER |
  | `CDEMO-CT00-TRN-SELECTED` | X(16) | 203-218 | `TRAN-ID` of the selected row, passed to COTRN01C |

  On entry from the menu the group move pads these bytes with spaces. The 88-level
  `NEXT-PAGE-NO` is set at the start of MAIN-PARA, but the COMMAREA move that follows overwrites it.
- On every turn the page rows are rebuilt from the file. Between turns only the first and last IDs,
  the page number, and the next-page flag are kept.

## Screen
First page, with TRANSACT loaded from `app/data/ASCII/dailytran.txt`:

```text
         1         2         3         4         5         6         7         8
12345678901234567890123456789012345678901234567890123456789012345678901234567890
 Tran: CT00                AWS Mainframe Modernization           Date: 06/15/22
 Prog: COTRN00C                    CardDemo                      Time: 10:30:00

                              List Transactions                  Page: 00000001

     Search Tran ID: ________________

  Sel    Transaction ID      Date          Description                Amount
  ---   ----------------   --------   --------------------------   ------------
   _    0000000000683580   06/10/22   Purchase at Abshire-Lowe     +00000504.77
   _    0000000001774260   06/10/22   Return item at Nitzsche, N   -00000919.00
   _    0000000006292564   06/10/22   Purchase at Ernser, Roob a   +00000067.88
   _    0000000009101861   06/10/22   Purchase at Guann LLC        +00000281.77
   _    0000000010142252   06/10/22   Purchase at Kertzmann-Scho   +00000454.66
   _    0000000010229018   06/10/22   Purchase at Gislason-Medhu   +00000849.99
   _    0000000016259484   06/10/22   Return item at Sipes Inc     -00000056.77
   _    0000000017874199   06/10/22   Purchase at Legros Group     +00000373.66
   _    0000000019065428   06/10/22   Return item at Turcotte Gr   -00000535.88
   _    0000000021711604   06/10/22   Purchase at Gleason, Shana   +00000416.11

            Type 'S' to View Transaction details from the list


 ENTER=Continue  F3=Back  F7=Backward  F8=Forward
```

Positions are the (row, column) of the first data character. The variable `nn` stands for rows 01-10,
which are screen rows 10-19.

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Header | TRNNAME, TITLE01, CURDATE, PGMNAME, TITLE02, CURTIME | 4/40/8/8/40/8 | Out | rows 1-2, as in all CardDemo screens | `CT00`, titles, `MM/DD/YY`, `COTRN00C`, `HH:MM:SS` |
| Page | PAGENUM | 8 | Out | (4,72) ASKIP FSET, blue | `CDEMO-CT00-PAGE-NUM` as 8 digits, for example `00000001` |
| Search Tran ID | TRNIDIN | 16 | In | (6,22) UNPROT FSET, green, underline (no IC; the cursor is placed by the program) | Start the list at the first `TRAN-ID` greater than or equal to this value; must be 16 digits |
| Select | SEL00nn | 1 | In | (row,4) UNPROT FSET, green, underline | `S` or `s` opens the row in COTRN01C |
| Transaction ID | TRNIDnn | 16 | Out (returned, FSET) | (row,9) ASKIP FSET, blue | `TRAN-ID` |
| Date | TDATEnn | 8 | Out | (row,28) ASKIP FSET, blue | `TRAN-ORIG-TS` as `MM/DD/YY` |
| Description | TDESCnn | 26 | Out | (row,39) ASKIP FSET, blue | First 26 characters of `TRAN-DESC` |
| Amount | TAMT0nn | 12 | Out | (row,68) ASKIP FSET, blue | `TRAN-AMT` edited as `+99999999.99` |
| Message | ERRMSG | 78 | Out | (23,2) ASKIP BRT FSET, red | Messages |

Constant text: "List Transactions" (4,31); "Page:" (4,66); "Search Tran ID:" (6,6); column headings
on rows 8-9; "Type 'S' to View Transaction details from the list" (21,13);
"ENTER=Continue  F3=Back  F7=Backward  F8=Forward" (24,2).

## Processing flow
1. **MAIN-PARA**: clear the error flag, set end-of-file off, set next-page to No (overwritten by the
   COMMAREA move), set "send with erase" on, clear the message, and put the cursor on TRNIDIN.
   IF `EIBCALEN = 0`, XCTL to `COSGN00C`.
2. Copy the COMMAREA. IF the context is not 1 (first entry): set it to 1, clear the map, run
   PROCESS-ENTER-KEY, and send the screen again (a second send; Q-COTRN00C-09).
3. OTHERWISE, RECEIVE the map and evaluate the AID: ENTER runs PROCESS-ENTER-KEY. PF3 transfers to
   `COMEN01C`. PF7 runs PROCESS-PF7-KEY. PF8 runs PROCESS-PF8-KEY. Any other key shows the invalid-key
   message.
4. **PROCESS-ENTER-KEY**:
   1. Find the first row (01 to 10) whose SEL field is neither spaces nor LOW-VALUES. Copy that SEL
      value and that row's TRNID into `CDEMO-CT00-TRN-SEL-FLG` and `CDEMO-CT00-TRN-SELECTED`. If no
      row is marked, set both to spaces.
   2. IF both are non-blank: if the flag is `S` or `s`, set TO-PROGRAM `COTRN01C`, FROM-TRANID `CT00`,
      FROM-PROGRAM `COTRN00C`, and context 0, and XCTL to COTRN01C (the task ends here). Any other
      value sets the message "Invalid selection. Valid value is S" and processing continues.
   3. IF TRNIDIN is blank, set `TRAN-ID` to LOW-VALUES. Otherwise, if TRNIDIN is NUMERIC, use it as
      `TRAN-ID`. If it is not, set the error flag, show "Tran ID must be Numeric ...", and continue.
   4. Set the page number to 0 and run PROCESS-PAGE-FORWARD. IF there is no error, blank TRNIDIN.
5. **PROCESS-PF7-KEY**: set `TRAN-ID` to `TRNID-FIRST` (or LOW-VALUES if that is blank) and set the
   next-page flag to Y. IF the page number is greater than 1, run PROCESS-PAGE-BACKWARD. Otherwise show
   "You are already at the top of the page..." and send without erase, so the current rows stay.
6. **PROCESS-PF8-KEY**: set `TRAN-ID` to `TRNID-LAST` (or HIGH-VALUES if that is blank). IF the
   next-page flag is Y, run PROCESS-PAGE-FORWARD. Otherwise show
   "You are already at the bottom of the page..." and send without erase.
7. **PROCESS-PAGE-FORWARD**:
   1. STARTBR at `TRAN-ID`. The default positioning is GTEQ: the first record with a key greater than
      or equal to `TRAN-ID`. If STARTBR fails with an error, stop.
   2. IF the AID is not ENTER, PF7, or PF3, READNEXT once and discard the record. On PF8 this skips the
      record that was the last row of the previous page.
   3. IF not at end of file and no error, blank all 10 rows.
   4. FOR rows 1 to 10, while not at end of file and no error: READNEXT, and on success fill the row
      (POPULATE-TRAN-DATA).
   5. IF 10 rows were filled without reaching end of file: add 1 to the page number, then READNEXT once
      more as a look-ahead. Set the next-page flag to Y if a record was found, otherwise N.
      OTHERWISE: set the next-page flag to N, and add 1 to the page number only if at least one row was
      filled.
   6. ENDBR, move the page number to PAGENUM, blank TRNIDIN, and send the screen.
8. **PROCESS-PAGE-BACKWARD**:
   1. STARTBR at `TRAN-ID` (the first row of the current page). If it fails with an error, stop.
   2. IF the AID is not ENTER or PF8, READPREV once and discard the record. This is always true here,
      because backward paging runs only on PF7. The record returned is the current first row itself.
   3. IF not at end of file and no error, blank all 10 rows.
   4. FOR rows 10 down to 1, while not at end of file and no error: READPREV and fill the row. Rows are
      filled bottom-up, so the screen still shows ascending order.
   5. IF not at end of file and no error: READPREV once more as a look-ahead. Then, because the
      next-page flag is always Y here: IF a record was found and the page number is greater than 1,
      subtract 1 from the page number. OTHERWISE set the page number to 1.
   6. ENDBR, move the page number to PAGENUM, and send the screen.
9. **POPULATE-TRAN-DATA** fills row `WS-IDX` (BR-COTRN00C-04). Row 1 also sets `TRNID-FIRST`, and row 10
   also sets `TRNID-LAST`.
10. Every turn that does not XCTL ends with RETURN TRANSID `CT00`. The file paragraphs can send the
    screen in the middle of paging (ERR rules), before the final send.

## Rules
### Business rules
- **BR-COTRN00C-01**: The list covers the whole TRANSACT file in ascending `TRAN-ID` order (key order).
  There is no filter by user, account, or card. Source: `app/cbl/COTRN00C.cbl:279-328 (PROCESS-PAGE-FORWARD)`.
- **BR-COTRN00C-02**: A page holds 10 transactions. Source: `app/cbl/COTRN00C.cbl:290-303 (PROCESS-PAGE-FORWARD)`.
- **BR-COTRN00C-03**: First entry and ENTER rebuild page 1, starting at the first record whose
  `TRAN-ID` is greater than or equal to the search ID. With no search ID, the list starts at the
  beginning of the file. The page number is reset to 0 and then becomes 1.
  Source: `app/cbl/COTRN00C.cbl:206-225 (PROCESS-ENTER-KEY)`.
- **BR-COTRN00C-04**: Row content:
  - TRNID = `TRAN-ID`.
  - TDATE = `MM/DD/YY` from `TRAN-ORIG-TS`: MM is characters 6-7, DD is characters 9-10, and YY is
    characters 3-4 of the timestamp.
  - TDESC = `TRAN-DESC`, characters 1-26.
  - TAMT = `TRAN-AMT` edited with picture `+99999999.99`. The sign is always shown and leading zeros
    are kept, so -919.00 shows as `-00000919.00`.

  Source: `app/cbl/COTRN00C.cbl:381-445 (POPULATE-TRAN-DATA)`.
- **BR-COTRN00C-05**: The next-page flag is Y only when a full page of 10 rows was read and the
  look-ahead READNEXT found an 11th record. Otherwise it is N.
  Source: `app/cbl/COTRN00C.cbl:305-320 (PROCESS-PAGE-FORWARD)`.
- **BR-COTRN00C-06**: Forward page numbering adds 1 when 10 rows are filled, and also when a partial page
  has at least one row. The number is unchanged when no row could be read.
  Source: `app/cbl/COTRN00C.cbl:305-320 (PROCESS-PAGE-FORWARD)`.
- **BR-COTRN00C-07**: PF8 restarts the browse at `TRNID-LAST` and skips that one record, so the next page
  begins with the first record after the previous last row.
  Source: `app/cbl/COTRN00C.cbl:257-268 (PROCESS-PF8-KEY)`, `:285-287 (PROCESS-PAGE-FORWARD)`.
- **BR-COTRN00C-08**: PF7 restarts at `TRNID-FIRST`, skips that record, and fills rows 10 down to 1 with
  the 10 preceding records. A look-ahead READPREV then sets the page number: if an earlier record
  exists and the page is greater than 1, the page number goes down by 1. If no earlier record exists,
  the page number becomes 1. After PF7 the next-page flag is always Y, also when PF7 is rejected on
  page 1 (Q-COTRN00C-13).
  Source: `app/cbl/COTRN00C.cbl:234-252 (PROCESS-PF7-KEY)`, `:333-376 (PROCESS-PAGE-BACKWARD)`.
- **BR-COTRN00C-09**: Selection uses only the first row, from top to bottom, whose Sel field is not
  blank. When its value is `S` or `s` and its TRNID is not blank, the program sets
  `CDEMO-CT00-TRN-SELECTED` to that row's `TRAN-ID`, sets FROM-PROGRAM `COTRN00C`, FROM-TRANID `CT00`,
  and context 0, and transfers to COTRN01C. Selection is checked before the search ID. A mark on an
  empty row (TRNID blank) is ignored. Source: `app/cbl/COTRN00C.cbl:148-195 (PROCESS-ENTER-KEY)`.
- **BR-COTRN00C-10**: `TRNID-FIRST` changes only when row 1 is filled, and `TRNID-LAST` only when row 10
  is filled. A partial page therefore leaves `TRNID-LAST` at its previous value, which is harmless
  because the next-page flag is N. Source: `app/cbl/COTRN00C.cbl:391-393, 437-439 (POPULATE-TRAN-DATA)`.
- **BR-COTRN00C-11**: Every send fills the header (titles, `CT00`, `COTRN00C`, `MM/DD/YY`, `HH:MM:SS`).
  Source: `app/cbl/COTRN00C.cbl:567-586 (POPULATE-HEADER-INFO)`.

### Validation rules
- **VR-COTRN00C-01**: A non-blank Sel value must be `S` or `s`. Otherwise the message is
  "Invalid selection. Valid value is S" with the cursor on TRNIDIN. This does not set the error flag:
  the list is rebuilt from the search ID or from the start, and a later paging message can replace
  this one. Source: `app/cbl/COTRN00C.cbl:196-203 (PROCESS-ENTER-KEY)`.
- **VR-COTRN00C-02**: A non-blank search ID must pass the COBOL NUMERIC test on all 16 positions.
  Trailing blanks fail the test, so 16 digits must be typed. Otherwise the message is
  "Tran ID must be Numeric ..." with the cursor on TRNIDIN. The rows on the screen keep the values
  that were received (they are FSET fields). Source: `app/cbl/COTRN00C.cbl:206-219 (PROCESS-ENTER-KEY)`.

### Navigation and keys
- **NAV-COTRN00C-01**: IF `EIBCALEN = 0`, XCTL to `COSGN00C`. Source: `app/cbl/COTRN00C.cbl:107-109 (MAIN-PARA)`, `:510-521 (RETURN-TO-PREV-SCREEN)`.
- **NAV-COTRN00C-02**: First entry (context 0): set the context to 1, clear the map, and show page 1
  (BR-COTRN00C-03). Source: `app/cbl/COTRN00C.cbl:112-116 (MAIN-PARA)`.
- **NAV-COTRN00C-03**: ENTER processes the selection, then the search, then rebuilds page 1.
  Source: `app/cbl/COTRN00C.cbl:120-121 (MAIN-PARA)`.
- **NAV-COTRN00C-04**: PF3 always transfers by XCTL to `COMEN01C`, regardless of `CDEMO-FROM-PROGRAM`.
  Before the XCTL the program sets FROM-TRANID `CT00`, FROM-PROGRAM `COTRN00C`, and context 0.
  Source: `app/cbl/COTRN00C.cbl:122-124 (MAIN-PARA)`.
- **NAV-COTRN00C-05**: PF7 pages backward. When the page number is 1 or less, the message is
  "You are already at the top of the page..." and the screen is sent without erase, so the rows stay.
  The next-page flag is still set to Y (Q-COTRN00C-13).
  Source: `app/cbl/COTRN00C.cbl:125-126, 234-252 (PROCESS-PF7-KEY)`.
- **NAV-COTRN00C-06**: PF8 pages forward. When the next-page flag is not Y, the message is
  "You are already at the bottom of the page..." and the screen is sent without erase.
  The flag can be Y without a next page after PF7 on page 1 (NAV-COTRN00C-05, Q-COTRN00C-13).
  Source: `app/cbl/COTRN00C.cbl:127-128, 257-274 (PROCESS-PF8-KEY)`.
- **NAV-COTRN00C-07**: Any other AID shows "Invalid key pressed. Please see below..." (the 50-character
  constant, padded with trailing spaces) with the cursor on TRNIDIN. The screen is sent with erase from
  the received data. Source: `app/cbl/COTRN00C.cbl:129-133 (MAIN-PARA)`.
- **NAV-COTRN00C-08**: `S` or `s` on a row transfers by XCTL to `COTRN01C` (BR-COTRN00C-09).
  Source: `app/cbl/COTRN00C.cbl:186-195 (PROCESS-ENTER-KEY)`.
- **NAV-COTRN00C-09**: Every turn that does not XCTL ends with RETURN TRANSID `CT00` and the 218-byte
  COMMAREA. Source: `app/cbl/COTRN00C.cbl:138-141 (MAIN-PARA)`.

### Error handling
- **ERR-COTRN00C-01**: STARTBR NOTFND (no key greater than or equal to the start key) sets end of file
  and sends the message "You are at the top of the page...". The wording does not match the situation.
  The error flag is not set, so paging continues and reaches ENDBR without an open browse
  (Q-COTRN00C-02). Source: `app/cbl/COTRN00C.cbl:605-611 (STARTBR-TRANSACT-FILE)`.
- **ERR-COTRN00C-02**: STARTBR with any other non-NORMAL response sets the error flag and sends
  "Unable to lookup transaction...". RESP and RESP2 are written to the log with DISPLAY. No rows are
  built. Source: `app/cbl/COTRN00C.cbl:612-618 (STARTBR-TRANSACT-FILE)`.
- **ERR-COTRN00C-03**: READNEXT ENDFILE sets end of file and sends
  "You have reached the bottom of the page...". This message stays on the final screen, including when
  the page is full and only the look-ahead read hit the end.
  Source: `app/cbl/COTRN00C.cbl:639-645 (READNEXT-TRANSACT-FILE)`.
- **ERR-COTRN00C-04**: READNEXT with any other non-NORMAL response sets the error flag and sends
  "Unable to lookup transaction...". Source: `app/cbl/COTRN00C.cbl:646-652 (READNEXT-TRANSACT-FILE)`.
- **ERR-COTRN00C-05**: READPREV ENDFILE sets end of file and sends "You have reached the top of the page...".
  Source: `app/cbl/COTRN00C.cbl:673-679 (READPREV-TRANSACT-FILE)`.
- **ERR-COTRN00C-06**: READPREV with any other non-NORMAL response sets the error flag and sends
  "Unable to lookup transaction...". Source: `app/cbl/COTRN00C.cbl:680-686 (READPREV-TRANSACT-FILE)`.
- **ERR-COTRN00C-07**: ENDBR has no RESP option. Any exception, such as INVREQ when no browse is active,
  abends the task (AEIP). Source: `app/cbl/COTRN00C.cbl:692-696 (ENDBR-TRANSACT-FILE)`.
- **ERR-COTRN00C-08**: The RESP of RECEIVE MAP is ignored. Source: `app/cbl/COTRN00C.cbl:554-562 (RECEIVE-TRNLST-SCREEN)`.

## Data effects
None. The program only reads TRANSACT. Browses are always ended with ENDBR within the same task, except
after a STARTBR error or after a non-numeric search ID, where the browse is left to end with the task.

## Quirks
- **Q-COTRN00C-01**: The skip-one READNEXT in PROCESS-PAGE-FORWARD runs for every AID except ENTER, PF7,
  and PF3. When the list is entered from COTRN01C with **PF5**, `EIBAID` is PF5, so page 1 skips the
  first record of the file: it shows records 2-11, and `TRNID-FIRST` is record 2. PF7 then says
  "already at the top", so record 1 can be reached only by pressing ENTER. Recommendation: fix.
  Source: `app/cbl/COTRN00C.cbl:285-287`; `app/cbl/COTRN01C.cbl:125-127`.
- **Q-COTRN00C-02**: A search ID greater than every key, such as `9999999999999999`, makes STARTBR
  return NOTFND. The program shows "You are at the top of the page..." and does not set the error flag.
  PROCESS-PAGE-FORWARD then issues ENDBR with no browse active, which CICS rejects with INVREQ. Because
  ENDBR has no RESP option, the task abends with AEIP. This is inferred from CICS semantics.
  Recommendation: fix (show an empty list or a "not found" message).
  Source: `app/cbl/COTRN00C.cbl:281-322, 605-611, 692-696`.
- **Q-COTRN00C-03**: Page numbers count from the starting point of the last ENTER, not from the start
  of the file. After a search, page 1 is the first page at the search ID, and PF7 on that page says
  "You are already at the top of the page..." even though earlier records exist.
  Recommendation: decide. Source: `app/cbl/COTRN00C.cbl:224, 245-252`.
- **Q-COTRN00C-04**: The search ID must be typed as all 16 digits with leading zeros, because the
  NUMERIC test includes trailing blanks: `683580` fails with "Tran ID must be Numeric ...".
  Recommendation: decide (accept a shorter number and left-pad it). Source: `app/cbl/COTRN00C.cbl:209`.
- **Q-COTRN00C-05**: The end-of-file messages appear even when a full page is shown. Paging back to the
  first page shows "You have reached the top of the page...", and the last full page shows
  "You have reached the bottom of the page...". Recommendation: preserve the information (a "first
  page" and "last page" indicator) but not necessarily the wording. Source: `app/cbl/COTRN00C.cbl:308, 360`.
- **Q-COTRN00C-06**: An invalid selection character is not an error. Only the first marked row is
  considered, and marks on other rows are silently ignored. Recommendation: decide.
  Source: `app/cbl/COTRN00C.cbl:148-203`.
- **Q-COTRN00C-07**: For a non-numeric search ID, STARTBR still runs with whatever `TRAN-ID` holds. In a
  new task that is the initial working-storage content. The browse is never ended. If that STARTBR
  returns NOTFND, the message changes to "You are at the top of the page...".
  Recommendation: fix (do not browse after a validation error). Source: `app/cbl/COTRN00C.cbl:212-225, 281-283`.
- **Q-COTRN00C-08**: Every signed-on user, regular or admin, sees all customers' transactions.
  Recommendation: decide (scope by account or card for regular users).
  Source: `app/cbl/COTRN00C.cbl:279-328`.
- **Q-COTRN00C-09**: One task can send the map several times: first entry sends twice, and each
  end-of-file branch sends before the final send. Only the last send is visible.
  Recommendation: in the target system, return one response. Source: `app/cbl/COTRN00C.cbl:115-116, 326, 611, 645, 679`.
- **Q-COTRN00C-10**: Paging assumes the boundary record still exists. If the record at `TRNID-FIRST` or
  `TRNID-LAST` was deleted between turns, GTEQ positioning lands on the next record and the skip-one
  read discards a record that was never shown. Records added between turns can appear on the next
  page. Recommendation: in the target system, use keyset pagination (`id > last` / `id < first`).
  Source: `app/cbl/COTRN00C.cbl:285-287, 339-341`.
- **Q-COTRN00C-11**: If a backward page reaches the start of the file before 10 rows are filled (only
  possible when records were deleted), the top rows stay blank, `TRNID-FIRST` is not updated, and the
  page number is unchanged. Recommendation: fix. Source: `app/cbl/COTRN00C.cbl:351-369`.
- **Q-COTRN00C-12**: The date column is the origination date with a 2-digit year. The description is
  cut to 26 of 100 characters. Recommendation: decide the target display.
  Source: `app/cbl/COTRN00C.cbl:383-396`.
- **Q-COTRN00C-13**: PF7 sets the next-page flag to Y before testing the page number, so PF7 on page 1
  marks a next page that may not exist. When page 1 is a full page that is also the last page, PF8 then
  restarts at `TRNID-LAST`, skips that record, clears all ten rows, hits end of file, and shows an empty
  list with "You have reached the bottom of the page..." and the page number unchanged, instead of
  "You are already at the bottom of the page..." with the rows kept. When page 1 is a partial page,
  `TRNID-LAST` still holds the previous value (BR-COTRN00C-10), so PF8 continues from that older
  position. Recommendation: fix. Source: `app/cbl/COTRN00C.cbl:242, 245-252 (PROCESS-PF7-KEY)`,
  `:267-268 (PROCESS-PF8-KEY)`, `:285-293, 316-319 (PROCESS-PAGE-FORWARD)`, `:639-645 (READNEXT-TRANSACT-FILE)`.

## Test scenarios
Assumption: TRANSACT holds the 300 records of `app/data/ASCII/dailytran.txt`. Sorted by ID, record 1 is
`0000000000683580`, record 10 is `0000000021711604`, record 11 is `0000000025430891`, record 290 is
`0000000957695517`, record 291 is `0000000957864065`, and record 300 is `0000000996722787`.
- **TS-COTRN00C-01** (covers BR-COTRN00C-01, -02, -03, -04, -05, NAV-COTRN00C-02): When CT00 is entered
  from the menu, then PAGENUM is `00000001` and rows 1-10 hold records 1-10. Row 1 is
  `0000000000683580`, `06/10/22`, `Purchase at Abshire-Lowe`, `+00000504.77`. Row 2 is
  `0000000001774260`, `06/10/22`, `Return item at Nitzsche, N`, `-00000919.00`. The message is blank
  and the next-page flag is Y.
- **TS-COTRN00C-02** (covers BR-COTRN00C-07): Given page 1, when the user presses PF8, then PAGENUM is
  `00000002` and row 1 is `0000000025430891`, `Purchase at Beatty-Hessel`, `+00000094.33`.
- **TS-COTRN00C-03** (covers BR-COTRN00C-08, ERR-COTRN00C-05): Given page 2, when the user presses PF7,
  then PAGENUM is `00000001`, the rows equal TS-COTRN00C-01, and the message is
  "You have reached the top of the page...".
- **TS-COTRN00C-04** (covers NAV-COTRN00C-05): Given page 1, when the user presses PF7, then the message
  is "You are already at the top of the page..." and the rows are unchanged.
- **TS-COTRN00C-05** (covers BR-COTRN00C-05, ERR-COTRN00C-03, NAV-COTRN00C-06): When the user presses
  PF8 29 times from page 1, then PAGENUM is `00000030`, rows hold records 291-300 (row 10 is
  `0000000996722787`), and the message is "You have reached the bottom of the page...". When the user
  presses PF8 again, the message is "You are already at the bottom of the page..." and the rows are
  unchanged.
- **TS-COTRN00C-06** (covers BR-COTRN00C-03, -06, Q-COTRN00C-03): When the user presses ENTER with
  search ID `0000000957695517`, then PAGENUM is `00000001`, rows hold records 290-299, and TRNIDIN is
  blanked. When the user presses PF8, then PAGENUM is `00000002`, only row 1 is filled
  (`0000000996722787`), the message is "You have reached the bottom of the page...", and the next-page
  flag is N. When the user presses PF7 twice, the second PF7 shows
  "You are already at the top of the page...".
- **TS-COTRN00C-07** (covers VR-COTRN00C-02, Q-COTRN00C-04): When the user presses ENTER with search ID
  `683580`, then the message is "Tran ID must be Numeric ...". The same happens for `ABC`.
- **TS-COTRN00C-08** (covers BR-COTRN00C-09, NAV-COTRN00C-08): Given page 1, when the user types `S` in
  row 2 and presses ENTER, then control transfers to COTRN01C, which shows transaction
  `0000000001774260`.
- **TS-COTRN00C-09** (covers VR-COTRN00C-01): Given page 1, when the user types `X` in row 1 and presses
  ENTER, then the message is "Invalid selection. Valid value is S" and page 1 is rebuilt.
- **TS-COTRN00C-10** (covers Q-COTRN00C-02): When the user presses ENTER with search ID
  `9999999999999999`, then the source shows "You are at the top of the page..." and the task abends
  with AEIP. Expected in the target system: decide (recommended: an empty list with a "no transactions
  found" message).
- **TS-COTRN00C-11** (covers Q-COTRN00C-01): Given COTRN01C was reached from page 1, when the user
  presses PF5 there, then page 1 here starts with record 2 (`0000000001774260`), and record 1 is not
  shown.
- **TS-COTRN00C-12** (covers NAV-COTRN00C-04, -07): PF3 transfers to COMEN01C. PF10 shows
  "Invalid key pressed. Please see below...".
- **TS-COTRN00C-13** (covers BR-COTRN00C-08, NAV-COTRN00C-05, -06, Q-COTRN00C-13): Given the user pressed
  ENTER with search ID `0000000957864065`, so page 1 holds records 291-300 with
  "You have reached the bottom of the page...", when the user presses PF7, then the message is
  "You are already at the top of the page...". When the user then presses PF8, then (as coded) all ten
  rows are blank, PAGENUM is `00000001`, and the message is "You have reached the bottom of the page...".
  Expected in the target system (ledger FIX): the message is "You are already at the bottom of the
  page...", the rows still hold records 291-300, and PAGENUM is `00000001`.

## Open questions
- Should regular users be restricted to their own account's transactions (Q-COTRN00C-08)?
- Should page numbers be absolute positions in the file, or stay relative to the search start?
