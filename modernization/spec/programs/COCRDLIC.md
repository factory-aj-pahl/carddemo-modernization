# COCRDLIC: Browse and filter the credit card file, 7 cards per page, and route a selected card to view or update

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COCRDLIC.cbl` (1459 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CCLI |
| BMS map | mapset `COCRDLI` / map `CCRDLIA` (`app/bms/COCRDLI.bms`, symbolic map `app/cpy-bms/COCRDLI.CPY`) |
| Copybooks | `CVCRD01Y` (work area, AID flags, filter fields), `COCOM01Y` (CARDDEMO-COMMAREA), `COTTL01Y` (titles), `COCRDLI` (symbolic map), `CSDAT01Y` (date/time), `CSMSG01Y` (common messages, unused), `CSUSR01Y` (user record, unused), `CVACT02Y` (CARD-RECORD), `CSSTRPFY` (procedure code: YYYY-STORE-PFKEY), IBM `DFHBMSCA`, `DFHAID`. Commented out: `COCRDSL`, `CSMSG02Y`. |
| Calls / XCTL targets | XCTL `COMEN01C` (PF3), XCTL `COCRDSLC` (select code `S`), XCTL `COCRDUPC` (select code `U`) |
| Data stores | `CARDDAT` (card KSDS, ENT-CARD, key CARD-NUM X(16)): Browse only (STARTBR GTEQ, READNEXT, READPREV, ENDBR). `CARDAIX` (account alternate-index path) is declared as a literal but never used. |

## Purpose
Shows the card file as a paged list of 7 cards per screen (account number, card number, active
status), in ascending card-number order. The user may filter by an 11-digit account number, a
16-digit card number, or both, page forward (PF8) and backward (PF7), and mark exactly one row with
`S` (view details, COCRDSLC) or `U` (update, COCRDUPC). The header comment says non-admin users
see only the cards of the account in the COMMAREA; the code does not implement this
(see BR-COCRDLIC-01, Q-COCRDLIC-01). The program never changes data.

## Invocation and state

**Entry paths**

| Entry | How detected | Effect |
|:--|:--|:--|
| Main menu option 3 "Credit Card List" (`COMEN01C`, `app/cpy/COMEN02Y.cpy:36-40`) | EIBCALEN > 0, `CDEMO-PGM-ENTER` (COMEN01C sets PGM-CONTEXT 0), `CDEMO-FROM-PROGRAM` = `COMEN01C`, `CDEMO-FROM-TRANID` = `CM00` | Program state reset; first page shown; map is not received |
| Return from COCRDSLC (PF3) or COCRDUPC (PF3, or any key after a save attempt) | `CDEMO-PGM-ENTER`, `CDEMO-FROM-PROGRAM` = `COCRDSLC` / `COCRDUPC` | Program state reset; first page shown (see NAV-COCRDLIC-03, Q-COCRDLIC-11) |
| Own pseudo-conversational turn | EIBCALEN > 0 and `CDEMO-FROM-PROGRAM` = `COCRDLIC` | Map received and edited, then the key is processed |
| Transaction CCLI typed with no COMMAREA | EIBCALEN = 0 | COMMAREA initialized, user type forced to `U`, first page shown |

**CARDDEMO-COMMAREA (`COCOM01Y`, 160 bytes) usage**

| Field | Read | Written |
|:--|:--|:--|
| CDEMO-FROM-PROGRAM | Yes: decides fresh entry vs. own turn, PF3 handling | `COCRDLIC` on every return and XCTL |
| CDEMO-FROM-TRANID | No | `CCLI` on every return and XCTL |
| CDEMO-TO-PROGRAM | No | `COMEN01C` on PF3 only |
| CDEMO-USER-TYPE | Never tested | Forced to `U` on fresh start, PF3, and both XCTLs (Q-COCRDLIC-11) |
| CDEMO-PGM-CONTEXT | Yes (`CDEMO-PGM-ENTER` = 0, `CDEMO-PGM-REENTER` = 1) | Set to 0 (ENTER) on fresh start and on every XCTL; never set to 1 by this program |
| CDEMO-ACCT-ID | Yes, for filter echo on screen | Account filter value (0 when blank/invalid); selected row's account on XCTL |
| CDEMO-CARD-NUM | Yes, for filter echo on screen | Card filter value (0 when blank/invalid); selected row's card on XCTL |
| CDEMO-LAST-MAP / CDEMO-LAST-MAPSET | No | `CCRDLIA` / `COCRDLI` |
| CDEMO-USER-ID, CDEMO-CUST-*, CDEMO-ACCT-STATUS | No | Cleared (INITIALIZE) on EIBCALEN = 0 and on the NAV-COCRDLIC-03 path |

**Program-specific COMMAREA extension (`WS-THIS-PROGCOMMAREA`, 254 bytes, appended after
CARDDEMO-COMMAREA; the whole RETURN COMMAREA is 2000 bytes).** Source:
`app/cbl/COCRDLIC.cbl:229-260`.

| Field | Picture | Meaning |
|:--|:--|:--|
| WS-CA-LAST-CARD-NUM | X(16) | Browse start key for the next page (PF8). After a full page it holds the key of the first raw record after the page (look-ahead record). |
| WS-CA-LAST-CARD-ACCT-ID | 9(11) | Account of that record (stored, never used as a key) |
| WS-CA-FIRST-CARD-NUM | X(16) | Card number of the first row on the current page; start key for refresh (ENTER) and PF7 |
| WS-CA-FIRST-CARD-ACCT-ID | 9(11) | Account of that row (stored, never used) |
| WS-CA-SCREEN-NUM | 9(1) | Page number shown in `PAGENO`; value 1 = `CA-FIRST-PAGE` |
| WS-CA-LAST-PAGE-DISPLAYED | 9(1) | 0 = `CA-LAST-PAGE-SHOWN`, 9 = `CA-LAST-PAGE-NOT-SHOWN` (drives the two-stage end-of-list message) |
| WS-CA-NEXT-PAGE-IND | X(1) | `Y` = `CA-NEXT-PAGE-EXISTS`, LOW-VALUES = `CA-NEXT-PAGE-NOT-EXISTS` |
| WS-RETURN-FLAG | X(1) | Never used |
| WS-SCREEN-ROWS (7 occurrences) | X(11) acct, X(16) card, X(1) status | The rows currently displayed; used on the next turn to resolve the selected row |

Pseudo-conversational state carried between turns: the paging keys, page number, end-of-list
flags, and the 7 displayed rows. Filter values are not stored in the program area; they persist
only because the filter fields are re-sent with MDT on (`FSET`) and are received again each turn,
plus the echo from `CDEMO-ACCT-ID` / `CDEMO-CARD-NUM`.

## Screen
BMS POS values are the attribute byte; data starts one column to the right. The mock-up shows
page 1 of the sample data (`app/data/ASCII/carddata.txt` lines 1-7), card numbers masked.

```
         1         2         3         4         5         6         7         8
12345678901234567890123456789012345678901234567890123456789012345678901234567890
 Tran: CCLI                AWS Mainframe Modernization           Date: mm/dd/yy
 Prog: COCRDLIC                    CardDemo                      Time: hh:mm:ss

                               List Credit Cards                      Page  1

                      Account Number    :   ___________
                      Credit Card Number:   ________________

          Select     Account Number           Card Number         Active
          ------    ---------------        ---------------       --------
            _         00000000050          0500********5740        Y
            _         00000000027          0683********1516        Y
            _         00000000002          0923********7330        Y
            _         00000000020          0927********6232        Y
            _         00000000012          0982********9795        Y
            _         00000000044          1014********4350        Y
            _         00000000037          1142********8931        Y


                   TYPE S FOR DETAIL, U TO UPDATE ANY RECORD

<row 23: ERRMSG, red, bright>
   F3=Exit F7=Backward  F8=Forward
```

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Transaction id (1,7) | TRNNAME | 4 | Out | ASKIP, FSET, blue | `CCLI` |
| Title line 1 (1,21) | TITLE01 | 40 | Out | ASKIP, yellow | `CCDA-TITLE01` = `'      AWS Mainframe Modernization       '` |
| Current date (1,71) | CURDATE | 8 | Out | ASKIP, blue | MM/DD/YY from FUNCTION CURRENT-DATE |
| Program name (2,7) | PGMNAME | 8 | Out | ASKIP, blue | `COCRDLIC` |
| Title line 2 (2,21) | TITLE02 | 40 | Out | ASKIP, yellow | `CCDA-TITLE02` = `'              CardDemo                  '` |
| Current time (2,71) | CURTIME | 8 | Out | ASKIP, blue | HH:MM:SS |
| Page number (4,76) | PAGENO | 3 | Out | default | WS-CA-SCREEN-NUM (single digit, left-justified) |
| Account filter (6,44) | ACCTSID | 11 | In/Out | UNPROT, FSET, IC, green, underline | Optional account-number filter |
| Card filter (7,44) | CARDSID | 16 | In/Out | UNPROT, FSET, green, underline | Optional card-number filter |
| Select row n (10+n,12), n = 1..7 | CRDSEL1..CRDSEL7 | 1 | In/Out | BMS: PROT, FSET, underline. Program sets unprotected + MDT (`DFHBMFSE`) when the row has data and there is no filter error; otherwise protected (row 1 `DFHBMPRF`, rows 2-7 `DFHBMPRO`). Red when in error. | Action code `S` or `U` |
| Stopper row n (10+n,14), n = 2..7 | CRDSTP2..CRDSTP7 | 1 | - | ASKIP, DRK, FSET | Not referenced by the program (no CRDSTP1) |
| Account number row n (10+n,22) | ACCTNO1..ACCTNO7 | 11 | Out | PROT | CARD-ACCT-ID |
| Card number row n (10+n,43) | CRDNUM1..CRDNUM7 | 16 | Out | PROT | CARD-NUM (displayed in full by the source) |
| Active status row n (10+n,67) | CRDSTS1..CRDSTS7 | 1 | Out | PROT | CARD-ACTIVE-STATUS |
| Info message (20,19) | INFOMSG | 45 | Out | PROT, neutral; program sends dark (`DFHBMDAR`) unless an info message is set, then neutral color | Informational prompt |
| Error message (23,1) | ERRMSG | 78 | Out | ASKIP, BRT, FSET, red | WS-ERROR-MSG (75 bytes) |
| Key legend (24,1) | (unnamed) | 78 | Out | ASKIP, turquoise | `'  F3=Exit F7=Backward  F8=Forward'` |

## Processing flow
1. `0000-MAIN`: clear `CC-WORK-AREA`, `WS-MISC-STORAGE` (including the browse key
   `WS-CARD-RID-CARDNUM`, which becomes spaces), and the output COMMAREA; clear the error message.
2. IF EIBCALEN = 0: INITIALIZE CARDDEMO-COMMAREA and the program area; set FROM-TRANID `CCLI`,
   FROM-PROGRAM `COCRDLIC`, user type `U`, PGM-ENTER, LAST-MAP `CCRDLIA`, LAST-MAPSET `COCRDLI`,
   page 1, last-page-not-shown. OTHERWISE copy the first 160 bytes of DFHCOMMAREA into
   CARDDEMO-COMMAREA and the next 254 bytes into the program area (even if EIBCALEN is only 160,
   see Q-COCRDLIC-14).
3. IF PGM-ENTER and FROM-PROGRAM is not `COCRDLIC` (arrival from the menu, COCRDSLC, or
   COCRDUPC): INITIALIZE the program area, set PGM-ENTER, LAST-MAP `CCRDLIA`, page 1,
   last-page-not-shown.
4. Map the AID (`YYYY-STORE-PFKEY`, PF13-PF24 fold onto PF1-PF12).
5. IF EIBCALEN > 0 and FROM-PROGRAM = `COCRDLIC`: `2000-RECEIVE-MAP` (receive the screen, then
   `2200-EDIT-INPUTS`: `2210-EDIT-ACCOUNT`, `2220-EDIT-CARD`, `2250-EDIT-ARRAY`).
6. Only ENTER, PF3, PF7, PF8 are valid; any other AID is treated as ENTER.
7. IF PF3 and FROM-PROGRAM = `COCRDLIC`: XCTL to `COMEN01C` (NAV-COCRDLIC-02). This happens
   before any input error is reported.
8. IF the key is not PF8: set last-page-not-shown.
9. EVALUATE, first match wins (`app/cbl/COCRDLIC.cbl:418-583`):
   1. Input error: if neither filter is in error, re-read forward from the browse key, which is
      spaces (start of file, Q-COCRDLIC-02); then send the map. If a filter is in error, do not
      read; the rows saved in the COMMAREA from the previous display are sent again.
   2. PF7 and page 1: re-read forward from WS-CA-FIRST-CARD-NUM; send.
   3. PF3 (only reachable when FROM-PROGRAM is not `COCRDLIC`), or PGM-REENTER with
      FROM-PROGRAM not `COCRDLIC`: INITIALIZE CARDDEMO-COMMAREA and the program area, set
      FROM-TRANID `CCLI`, FROM-PROGRAM `COCRDLIC`, user type `U`, PGM-ENTER, LAST-MAP/LAST-MAPSET,
      page 1; read forward from WS-CA-FIRST-CARD-NUM (now spaces, so from start of file); send.
   4. PF8 and next page exists: start key = WS-CA-LAST-CARD-NUM; page + 1; read forward; send.
   5. PF7 and not page 1: start key = WS-CA-FIRST-CARD-NUM; page - 1; read backward
      (`9100-READ-BACKWARDS`); send.
   6. ENTER and the selected row's code is `S` and FROM-PROGRAM = `COCRDLIC`: XCTL `COCRDSLC`.
   7. ENTER and the selected row's code is `U` and FROM-PROGRAM = `COCRDLIC`: XCTL `COCRDUPC`.
   8. OTHERWISE (ENTER without selection, PF8 with no next page, fresh entry): read forward from
      WS-CA-FIRST-CARD-NUM; send.
10. `COMMON-RETURN`: set FROM-TRANID `CCLI`, FROM-PROGRAM `COCRDLIC`, LAST-MAPSET `COCRDLI`,
    LAST-MAP `CCRDLIA`; build the 2000-byte COMMAREA (CARDDEMO-COMMAREA + program area); RETURN
    TRANSID(`CCLI`).

**Forward read (`9000-READ-FORWARD`, `app/cbl/COCRDLIC.cbl:1123-1260`)**
1. Clear the 7 rows. STARTBR `CARDDAT` with GTEQ on the 16-byte start key (response not checked).
2. Row counter = 0; set next-page-exists; FOR EACH READNEXT until the loop ends:
   - NORMAL or DUPREC: apply `9500-FILTER-RECORDS`. IF included: counter + 1; store account,
     card number, status in row[counter]; IF counter = 1: WS-CA-FIRST-CARD-* = this record and IF
     page number = 0 set it to 1.
     IF counter = 7: end the loop; WS-CA-LAST-CARD-* = this record; issue one more READNEXT
     (look-ahead): NORMAL/DUPREC sets next-page-exists and WS-CA-LAST-CARD-* = the look-ahead
     record (whether or not it matches the filter); ENDFILE sets next-page-not-exists and, if no
     message is set, message `NO MORE RECORDS TO SHOW`; any other response sets the file-error
     message.
   - ENDFILE: end the loop; next-page-not-exists; WS-CA-LAST-CARD-* = the contents of the record
     area (last record read); if no message is set, `NO MORE RECORDS TO SHOW`; IF page number = 1
     and counter = 0, message becomes `NO RECORDS FOUND FOR THIS SEARCH CONDITION.`.
   - Any other response: end the loop with the file-error message.
3. ENDBR.

**Backward read (`9100-READ-BACKWARDS`, `app/cbl/COCRDLIC.cbl:1264-1380`)**
1. Clear the 7 rows; WS-CA-LAST-CARDKEY = WS-CA-FIRST-CARDKEY (so a later PF8 returns to the page
   being left). STARTBR GTEQ on WS-CA-FIRST-CARD-NUM (response not checked).
2. Counter = 8; set next-page-exists. Issue one READPREV, which returns the record the browse is
   positioned on (the first row of the page being left); it is not filtered or displayed. IF
   NORMAL/DUPREC: counter = 7. OTHERWISE: file-error message, ENDBR, exit.
3. FOR EACH further READPREV until the loop ends: NORMAL/DUPREC: apply the filter; IF included:
   row[counter] = record, counter - 1; IF counter = 0: end the loop and WS-CA-FIRST-CARD-* = this
   record. Any other response, including ENDFILE at the start of the file: end the loop with the
   file-error message (Q-COCRDLIC-07).
4. ENDBR. Rows are filled bottom-up, so the page shows records in ascending order.

**Send map (`1000-SEND-MAP`)**: `1100-SCREEN-INIT` (titles, tran, program, date, time, page
number, info message dark), `1200-SCREEN-ARRAY-INIT` (for each non-empty row, show select code
echo, account, card, status), `1250-SETUP-ARRAY-ATTRIBS` (BR-COCRDLIC-12),
`1300-SETUP-SCREEN-ATTRS` (BR-COCRDLIC-13), `1400-SETUP-MESSAGE` (BR-COCRDLIC-14),
`1500-SEND-SCREEN` (SEND MAP ... CURSOR ERASE FREEKB).

## Rules
### Business rules
- **BR-COCRDLIC-01**: No user-type scoping. The list includes every card in CARDDAT, subject only
  to the on-screen filters, whatever `CDEMO-USER-TYPE` is. The user type is never tested; the
  header comment ("only the ones associated with ACCT in COMMAREA if user is not admin") is not
  implemented. Source: `app/cbl/COCRDLIC.cbl:4-7`, `app/cbl/COCRDLIC.cbl:1382-1407 (9500-FILTER-RECORDS)`.
- **BR-COCRDLIC-02**: Page size is 7 rows (`WS-MAX-SCREEN-LINES`). Rows appear in ascending
  CARD-NUM (primary key) order. Source: `app/cbl/COCRDLIC.cbl:177-178`, `app/cbl/COCRDLIC.cbl:1191-1192 (9000-READ-FORWARD)`.
- **BR-COCRDLIC-03**: Filtering. When the account filter is valid, a record is included only if
  CARD-ACCT-ID equals the filter. When the card filter is valid, a record is included only if
  CARD-NUM equals the filter. When both are valid, both must match. A blank or all-zero filter
  is not applied. Filtering is applied while browsing every record sequentially; no index is used.
  Source: `app/cbl/COCRDLIC.cbl:1382-1407 (9500-FILTER-RECORDS)`.
- **BR-COCRDLIC-04**: Forward paging (PF8) is allowed only when the next-page indicator is set.
  The page starts at the look-ahead key saved by the previous forward read, and the page number
  increases by 1. Source: `app/cbl/COCRDLIC.cbl:486-497 (0000-MAIN)`, `app/cbl/COCRDLIC.cbl:1191-1232 (9000-READ-FORWARD)`.
- **BR-COCRDLIC-05**: The next-page indicator is set when one more raw record exists after the
  7th displayed row, whether or not it matches the filter. It is cleared when end of file is
  reached. Source: `app/cbl/COCRDLIC.cbl:1197-1216`, `app/cbl/COCRDLIC.cbl:1233-1235 (9000-READ-FORWARD)`.
- **BR-COCRDLIC-06**: Backward paging (PF7) on any page except page 1 decreases the page number by
  1 and shows the 7 matching records immediately before the current first row (algorithm above).
  The next-page indicator is always set afterwards. Source: `app/cbl/COCRDLIC.cbl:501-513`, `app/cbl/COCRDLIC.cbl:1264-1380 (9100-READ-BACKWARDS)`.
- **BR-COCRDLIC-07**: Refresh. ENTER without a valid selection, PF7 on page 1, PF8 with no next
  page, and fresh entry all re-read forward from WS-CA-FIRST-CARD-NUM (the current page's first
  card) with the filters as currently entered. A fresh entry starts from key spaces, which is the
  start of the file. Source: `app/cbl/COCRDLIC.cbl:439-454`, `app/cbl/COCRDLIC.cbl:572-582 (0000-MAIN)`.
- **BR-COCRDLIC-08**: The page number becomes 1 when the first row of a forward read is placed and
  the page number is 0. Fresh entry sets it to 1. PF8 adds 1 and PF7 subtracts 1 (single digit,
  Q-COCRDLIC-06). Source: `app/cbl/COCRDLIC.cbl:324`, `app/cbl/COCRDLIC.cbl:341`, `app/cbl/COCRDLIC.cbl:492`, `app/cbl/COCRDLIC.cbl:508`, `app/cbl/COCRDLIC.cbl:1177-1179`.
- **BR-COCRDLIC-09**: Selection is acted on only with ENTER (or a key treated as ENTER). PF7 and
  PF8 page and ignore a single valid selection. Source: `app/cbl/COCRDLIC.cbl:517-569 (0000-MAIN)`.
- **BR-COCRDLIC-10**: On `S` or `U`, the program passes the selected row's account number in
  `CDEMO-ACCT-ID` and its card number in `CDEMO-CARD-NUM`. Both come from the rows saved in the
  COMMAREA when the screen was last sent, not from a re-read. It also sets FROM-TRANID `CCLI`,
  FROM-PROGRAM `COCRDLIC`, user type `U`, PGM-ENTER, LAST-MAPSET `COCRDLI`, and LAST-MAP `CCRDLIA`.
  Source: `app/cbl/COCRDLIC.cbl:517-569 (0000-MAIN)`.
- **BR-COCRDLIC-11**: The select codes the user typed are echoed back in the select fields of the
  rows now on screen. Source: `app/cbl/COCRDLIC.cbl:678-743 (1200-SCREEN-ARRAY-INIT)`.
- **BR-COCRDLIC-12**: Select-field protection. A row with no data, or any row when a filter is
  in error, gets a protected select field: row 1 uses `DFHBMPRF` (protected, MDT on) and rows 2-7
  use `DFHBMPRO`. Otherwise the select field is unprotected with MDT on (`DFHBMFSE`). A row flagged
  in error is shown red. Row 1 in error also shows `*` if its code is blank, but no cursor is
  placed on it. Rows 2-7 in error get the cursor but no `*`. Source:
  `app/cbl/COCRDLIC.cbl:748-832 (1250-SETUP-ARRAY-ATTRIBS)`.
- **BR-COCRDLIC-13**: Filter field echo and cursor. Echo is skipped when EIBCALEN = 0 or on
  arrival from `COMEN01C`. Otherwise:
  - The account filter shows the received value if it was edited (valid or invalid).
  - It is blank if `CDEMO-ACCT-ID` = 0.
  - Otherwise it shows `CDEMO-ACCT-ID`.

  The card filter follows the same rule with `CDEMO-CARD-NUM`. A filter in error is red and is flagged for the cursor. When several fields are flagged, BMS
  puts the cursor on the first flagged field in map order: account filter, card filter, then rows
  2-7. With no input error, the cursor goes on the account filter. Source:
  `app/cbl/COCRDLIC.cbl:837-889 (1300-SETUP-SCREEN-ATTRS)`.
- **BR-COCRDLIC-14**: Messages (first match wins). Source: `app/cbl/COCRDLIC.cbl:895-932 (1400-SETUP-MESSAGE)`.
  - A filter is in error: keep the filter error text; no info message.
  - PF7 on page 1: error `NO PREVIOUS PAGES TO DISPLAY`; no info message.
  - PF8, no next page, and last page already shown: error `NO MORE PAGES TO DISPLAY`; no info message.
  - PF8 and no next page (first time): info `TYPE S FOR DETAIL, U TO UPDATE ANY RECORD`; mark
    last page shown. The error line keeps `NO MORE RECORDS TO SHOW` set by the read.
  - OTHERWISE: info `TYPE S FOR DETAIL, U TO UPDATE ANY RECORD`. This branch is always taken,
    because the info message is cleared in `1100-SCREEN-INIT`.

  The error line shows WS-ERROR-MSG. The info line is shown in neutral color, unless no info
  message is set or the error is `NO RECORDS FOUND FOR THIS SEARCH CONDITION.`.
- **BR-COCRDLIC-15**: Last-page flag. Any key other than PF8 resets "last page shown" to "not
  shown", so the first PF8 at the end of the list gives the info message and the second gives
  `NO MORE PAGES TO DISPLAY`. Source: `app/cbl/COCRDLIC.cbl:410-414`, `app/cbl/COCRDLIC.cbl:905-916`.
- **BR-COCRDLIC-16**: The program is read-only. It never writes, rewrites, or deletes.

### Validation rules
- **VR-COCRDLIC-01**: Account filter (ACCTSID). Blank, spaces, LOW-VALUES, or all zeros means "no
  filter", and `CDEMO-ACCT-ID` is set to 0. Otherwise all 11 positions must be digits (so a
  shorter entry such as `50` fails). On failure: message
  `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER`, the field is red and gets the cursor,
  all select fields are protected, `CDEMO-ACCT-ID` is set to 0, and no browse is done. The rows
  from the previous screen are shown again. When valid, `CDEMO-ACCT-ID` = the filter. Source:
  `app/cbl/COCRDLIC.cbl:1003-1030 (2210-EDIT-ACCOUNT)`.
- **VR-COCRDLIC-02**: Card filter (CARDSID). Blank, spaces, LOW-VALUES, or all zeros means "no
  filter", and `CDEMO-CARD-NUM` is set to 0. Otherwise all 16 positions must be digits. On
  failure: message `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER` (only if no account
  message), the field is red and gets the cursor, select fields are protected, and there is no
  browse. Both filters are always edited, so both can be red. Source:
  `app/cbl/COCRDLIC.cbl:1036-1067 (2220-EDIT-CARD)`.
- **VR-COCRDLIC-03**: At most one row may carry `S` or `U`. If more than one does: message
  `PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE`, and every row holding `S` or `U` is flagged
  red. Skipped when a filter is in error. Source: `app/cbl/COCRDLIC.cbl:1073-1095 (2250-EDIT-ARRAY)`.
- **VR-COCRDLIC-04**: Each select code must be `S`, `U`, space, or LOW-VALUES. Lowercase `s` and
  `u` and any other character are rejected with `INVALID ACTION CODE` (only if no message is set
  yet). The row is flagged red. The last row with `S` or `U` becomes the selected row. Source:
  `app/cbl/COCRDLIC.cbl:1097-1115 (2250-EDIT-ARRAY)`.

### Navigation and keys
- **NAV-COCRDLIC-01**: AID handling. PF13-PF24 act as PF1-PF12. Only ENTER, PF3, PF7, and PF8 are
  valid. Every other AID (CLEAR, PA1, PA2, PF1, PF2, PF4-PF6, PF9-PF12) is processed as ENTER,
  with no "invalid key" message. Source: `app/cpy/CSSTRPFY.cpy:17-82`, `app/cbl/COCRDLIC.cbl:370-380`.
- **NAV-COCRDLIC-02**: PF3 on a turn of this program goes to the main menu: XCTL `COMEN01C` with
  CARDDEMO-COMMAREA, after setting FROM-TRANID `CCLI`, FROM-PROGRAM `COCRDLIC`, user type `U`,
  PGM-ENTER, LAST-MAPSET `COCRDLI`, LAST-MAP `CCRDLIA`, and TO-PROGRAM `COMEN01C`. This takes
  precedence over input errors. Source: `app/cbl/COCRDLIC.cbl:384-406 (0000-MAIN)`.
- **NAV-COCRDLIC-03**: The AID PF3 can arrive on entry from COCRDSLC or COCRDUPC, because XCTL
  keeps the same task and EIBAID. In that case, or with PGM-REENTER from another program, the
  whole CARDDEMO-COMMAREA is re-initialized (user id, customer, account, and card cleared; user
  type `U`) and page 1 is shown unfiltered. Source: `app/cbl/COCRDLIC.cbl:458-482 (0000-MAIN)`.
- **NAV-COCRDLIC-04**: PF8 shows the next page (BR-COCRDLIC-04), or refreshes the current page
  with the end-of-list messages (BR-COCRDLIC-14) when there is none. Source:
  `app/cbl/COCRDLIC.cbl:486-497`, `app/cbl/COCRDLIC.cbl:572-582`.
- **NAV-COCRDLIC-05**: PF7 shows the previous page. On page 1 it refreshes page 1 with
  `NO PREVIOUS PAGES TO DISPLAY`. Source: `app/cbl/COCRDLIC.cbl:439-454`, `app/cbl/COCRDLIC.cbl:501-513`.
- **NAV-COCRDLIC-06**: ENTER with exactly one `S`: XCTL `COCRDSLC` (transaction CCDL's program)
  with CARDDEMO-COMMAREA only (160 bytes). Source: `app/cbl/COCRDLIC.cbl:517-541`.
- **NAV-COCRDLIC-07**: ENTER with exactly one `U`: XCTL `COCRDUPC` with CARDDEMO-COMMAREA only.
  Source: `app/cbl/COCRDLIC.cbl:545-569`.
- **NAV-COCRDLIC-08**: Every turn that does not XCTL ends with RETURN TRANSID(`CCLI`) and a
  2000-byte COMMAREA. Source: `app/cbl/COCRDLIC.cbl:604-620 (COMMON-RETURN)`.
- **NAV-COCRDLIC-09**: On arrival from another program, the map is not received; the first page is
  built from scratch. Source: `app/cbl/COCRDLIC.cbl:336-343`, `app/cbl/COCRDLIC.cbl:357-362`.

### Error handling
- **ERR-COCRDLIC-01**: A READNEXT response other than NORMAL, DUPREC, or ENDFILE (including on the
  look-ahead read) stops the browse. The rows read so far are displayed, and the error line shows
  `File Error: READ     on CARDDAT   returned RESP nnnnnnnnn ,RESP2 nnnnnnnnn `. The literal parts
  are `'File Error:'` padded to 12, `'READ'` padded to 8, `' on '`, `'CARDDAT '` padded to 9,
  `' returned RESP '`, the EIBRESP value as 9 zero-padded digits plus a space, `',RESP2 '`, and the
  EIBRESP2 value the same way. The operation name is always `READ`, even for READNEXT, READPREV,
  and STARTBR failures. Source: `app/cbl/COCRDLIC.cbl:153-171`, `app/cbl/COCRDLIC.cbl:1222-1230`, `app/cbl/COCRDLIC.cbl:1246-1254`.
- **ERR-COCRDLIC-02**: The STARTBR response is not checked. A failed STARTBR shows up as the
  READNEXT/READPREV error in ERR-COCRDLIC-01 (typically INVREQ). ENDBR is issued with no RESP
  option, so it is issued even after a failed STARTBR. Source: `app/cbl/COCRDLIC.cbl:1129-1136`, `app/cbl/COCRDLIC.cbl:1258-1259`, `app/cbl/COCRDLIC.cbl:1273-1280`, `app/cbl/COCRDLIC.cbl:1375-1377`.
- **ERR-COCRDLIC-03**: In a backward read, a failure of the first READPREV (for example NOTFND
  when the first card of the page was deleted) shows the file-error message with all rows blank.
  A non-NORMAL READPREV inside the loop, including ENDFILE (RESP 20) at the start of the file,
  shows the file-error message with partially filled rows. Source:
  `app/cbl/COCRDLIC.cbl:1304-1318`, `app/cbl/COCRDLIC.cbl:1361-1370 (9100-READ-BACKWARDS)`.
- **ERR-COCRDLIC-04**: RECEIVE MAP and SEND MAP responses are captured in WS-RESP-CD and ignored.
  A MAPFAIL (for example after CLEAR) leaves the input fields as received, so the filters and
  select codes are treated as blank. Source: `app/cbl/COCRDLIC.cbl:962-967`, `app/cbl/COCRDLIC.cbl:938-946`.
- **ERR-COCRDLIC-05**: No HANDLE ABEND. Unhandled CICS conditions abend the task with the standard
  CICS abend code. Plain-text debug exits `SEND-PLAIN-TEXT` and `SEND-LONG-TEXT` exist but are
  never performed. Source: `app/cbl/COCRDLIC.cbl:1422-1454`.

## Data effects
None. CARDDAT is only browsed (STARTBR, READNEXT, READPREV, ENDBR within a single task; no record
locks). Every turn issues at most one browse.

## Quirks
- **Q-COCRDLIC-01**: The program's own header says non-admin users see only their account's
  cards, but there is no user-type check. Every user sees every card, and an account filter is
  optional and user-editable. Recommendation: decide (most likely implement scoping from the
  signed-on user's accounts in the target). Source: `app/cbl/COCRDLIC.cbl:4-7`, `app/cbl/COCRDLIC.cbl:1382-1407`.
- **Q-COCRDLIC-02**: After a selection error (more than one row, or an invalid code), the list is
  re-read from the start of the file, because the browse key is never set on that path and was
  cleared to spaces. The page number keeps its old value, the paging keys are reset to the first
  page, and the user's select codes are echoed onto rows that now hold different cards.
  Recommendation: fix (re-display the same page). Source: `app/cbl/COCRDLIC.cbl:300-302`, `app/cbl/COCRDLIC.cbl:419-438`.
- **Q-COCRDLIC-03**: Applying or changing a filter with ENTER re-reads from the current page's
  first card, not from the start of the file. Matches on earlier pages are skipped, and
  `NO RECORDS FOUND FOR THIS SEARCH CONDITION.` appears only on page 1 (on later pages an empty
  page shows `NO MORE RECORDS TO SHOW`). Recommendation: fix (a new filter starts at page 1).
  Source: `app/cbl/COCRDLIC.cbl:572-582`, `app/cbl/COCRDLIC.cbl:1241-1245`.
- **Q-COCRDLIC-04**: The next-page indicator is based on the next raw record, not the next
  matching record. With a filter that matches exactly 7, 14, ... cards, PF8 shows an empty page
  with `NO MORE RECORDS TO SHOW`. Recommendation: fix. Source: `app/cbl/COCRDLIC.cbl:1197-1214`.
- **Q-COCRDLIC-05**: Filtering scans the whole card file sequentially for every page, even for an
  exact card number (the primary key) or an account (alternate index `CARDAIX` exists but is not
  used). Recommendation: fix in the target (indexed query); results must be the same.
  Source: `app/cbl/COCRDLIC.cbl:213-217`, `app/cbl/COCRDLIC.cbl:1382-1407`.
- **Q-COCRDLIC-06**: The page number is PIC 9(1). Page 10 is shown as 0. The next forward read
  then resets it to 1 (because it is 0), and PF7 from "0" stores 1 (the sign is lost). The sample
  data (50 cards, 8 pages) does not reach this. Recommendation: fix. Source:
  `app/cbl/COCRDLIC.cbl:237-238`, `app/cbl/COCRDLIC.cbl:492`, `app/cbl/COCRDLIC.cbl:508`.
- **Q-COCRDLIC-07**: With a filter, PF7 can reach the start of the file before 7 matches are
  collected. ENDFILE is then reported as `File Error: READ ... RESP 000000020 ...`, the top rows
  are blank, and WS-CA-FIRST-CARD-NUM is not updated. Recommendation: fix (treat start of file as
  "show what was found"). Source: `app/cbl/COCRDLIC.cbl:1361-1370`.
- **Q-COCRDLIC-08**: With no row selected, the selected-row index is 0, and the test of
  `VIEW-REQUESTED-ON(0)` / `UPDATE-REQUESTED-ON(0)` reads the byte before the array (the last byte
  of a COMP-3 counter, X'0C'). It works by accident and would abend with subscript checking.
  Recommendation: fix (no behavior change). Source: `app/cbl/COCRDLIC.cbl:92-94`, `app/cbl/COCRDLIC.cbl:517-519`.
- **Q-COCRDLIC-09**: A stray `I` at line 790 makes the statement
  `MOVE DFHBMPRO TO CRDSEL4A OF CCRDLIAI I`, which also moves the attribute byte into the
  subscript `I`. `I` is not used again in the turn, so there is no visible effect.
  Recommendation: preserve behavior (drop in target). Source: `app/cbl/COCRDLIC.cbl:787-797`.
- **Q-COCRDLIC-10**: Row 1 is handled differently from rows 2-7: a different protect attribute
  (`DFHBMPRF`), `*` on a blank row in error but no cursor, and no `CRDSTP1` in the map.
  Recommendation: fix (uniform rows). Source: `app/cbl/COCRDLIC.cbl:751-773`, `app/bms/COCRDLI.bms:140-173`.
- **Q-COCRDLIC-11**: User type is forced to `U` on every XCTL and on fresh start. When control
  returns from COCRDSLC or COCRDUPC with the PF3 AID, CARDDEMO-COMMAREA is re-initialized,
  erasing CDEMO-USER-ID and the customer and account context for the rest of the session.
  Recommendation: fix (keep the session identity). Source: `app/cbl/COCRDLIC.cbl:320`, `app/cbl/COCRDLIC.cbl:388`, `app/cbl/COCRDLIC.cbl:462-467`, `app/cbl/COCRDLIC.cbl:522`, `app/cbl/COCRDLIC.cbl:550`.
- **Q-COCRDLIC-12**: Lowercase `s` and `u` are rejected as `INVALID ACTION CODE`. Recommendation:
  decide.
- **Q-COCRDLIC-13**: Filters and page position are lost when going to view or update and back. The
  list restarts at page 1 with blank filters. Recommendation: decide (target can keep list state).
- **Q-COCRDLIC-14**: When entered by XCTL, only the 160-byte CARDDEMO-COMMAREA is passed, but the
  program copies 254 bytes beyond it (past EIBCALEN) into its program area. The data is then
  discarded by the fresh-entry reset, so it is harmless here. Recommendation: fix (check
  EIBCALEN). Source: `app/cbl/COCRDLIC.cbl:326-343`.
- **Q-COCRDLIC-15**: Dead or unused code:
  - Lines 586-601 after the EVALUATE are unreachable.
  - `WS-RETURN-FLAG`, `WS-EXIT-MESSAGE` (set on PF3 but never shown), `CCARD-ERROR-MSG`, the
    CVV edit fields, `CSUSR01Y`, and `CSMSG01Y` are not used.
  - The `WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE` branch is written twice.

  Recommendation: drop in target. Source: `app/cbl/COCRDLIC.cbl:439-445`, `app/cbl/COCRDLIC.cbl:586-601`.
- **Q-COCRDLIC-16**: Full 16-digit card numbers are displayed on the list. Recommendation: decide
  (mask in the target per `docs/data-handling.md`; the select key must then be a surrogate or the
  full number kept server-side).
- **Q-COCRDLIC-17**: `NO MORE RECORDS TO SHOW` appears in the error line (red) on every page that
  reaches end of file, including a single filtered page, while the info line still invites
  selection. Recommendation: decide (informational, not an error).
- **Q-COCRDLIC-18**: The stopper fields `CRDSTP2`-`CRDSTP7` are defined at the same position
  (n,14) as the zero-length stopper after each select field. Recommendation: ignore in target.
  Source: `app/bms/COCRDLI.bms:162-173`.

## Test scenarios
Sample data: `app/data/ASCII/carddata.txt` holds 50 cards in key order, one card per account, all
active (`Y`). Pages are: page 1 = lines 1-7, page 2 = lines 8-14, ..., page 7 = lines 43-49,
page 8 = line 50. Card numbers are masked as first 4 + last 4; use the full value from the cited
line.

- **TS-COCRDLIC-01** (covers BR-COCRDLIC-02, BR-COCRDLIC-07, NAV-COCRDLIC-09): Given the sample
  card file, when the user picks main-menu option 3, then the page shows `Page 1` and rows
  (account / card / status):
  - 00000000050 / 0500********5740 (line 1) / Y
  - 00000000027 / 0683********1516 / Y
  - 00000000002 / 0923********7330 / Y
  - 00000000020 / 0927********6232 / Y
  - 00000000012 / 0982********9795 / Y
  - 00000000044 / 1014********4350 / Y
  - 00000000037 / 1142********8931 (line 7) / Y

  The info line is `TYPE S FOR DETAIL, U TO UPDATE ANY RECORD`, the error line is blank, and the
  cursor is on the account filter.
- **TS-COCRDLIC-02** (BR-COCRDLIC-04, BR-COCRDLIC-05): Given page 1, when PF8 is pressed, then
  `Page 2` shows lines 8-14 (first row 00000000035 / 1561********1600, last row 00000000010 /
  3260********7560).
- **TS-COCRDLIC-03** (BR-COCRDLIC-14, BR-COCRDLIC-15): Given page 7 (lines 43-49), when PF8 is
  pressed, then `Page 8` shows the single row 00000000040 / 9805********6588 (line 50), the error
  line is `NO MORE RECORDS TO SHOW`, and the info line is shown. When PF8 is pressed again, the
  same row is shown with error `NO MORE PAGES TO DISPLAY` and no info message. When ENTER and then
  PF8 are pressed, the first PF8 again gives the info message (the flag was reset).
- **TS-COCRDLIC-04** (BR-COCRDLIC-06): Given page 2, when PF7 is pressed, then `Page 1` shows
  lines 1-7 in ascending order.
- **TS-COCRDLIC-05** (NAV-COCRDLIC-05, BR-COCRDLIC-14): Given page 1, when PF7 is pressed, then
  page 1 is re-displayed with error `NO PREVIOUS PAGES TO DISPLAY` and no info message.
- **TS-COCRDLIC-06** (BR-COCRDLIC-03, VR-COCRDLIC-01): Given page 1, when account filter
  `00000000050` is entered with ENTER, then exactly one row (00000000050 / 0500********5740)
  is shown, the error line is `NO MORE RECORDS TO SHOW`, the filter stays displayed, and PF8
  refreshes the same row.
- **TS-COCRDLIC-07** (VR-COCRDLIC-01): Given page 1, when account filter `50` (2 characters) is
  entered, then the error is `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER`, the account
  filter is red with the cursor on it, the page 1 rows are shown again, and all select fields are
  protected.
- **TS-COCRDLIC-08** (VR-COCRDLIC-02, BR-COCRDLIC-03): Given page 1, when card filter
  4011********7367 (line 18, account 00000000013) is entered, then one row
  00000000013 / 4011********7367 / Y is shown. When card filter `40115008ABCD` is entered instead,
  the error is `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER`.
- **TS-COCRDLIC-09** (VR-COCRDLIC-01, VR-COCRDLIC-02): When account filter `ABC` and card filter
  `XYZ` are both entered, then the error is the account message only, both filter fields are red,
  and the cursor is on the account filter (the first flagged field).
- **TS-COCRDLIC-10** (BR-COCRDLIC-03, BR-COCRDLIC-14): Given page 1, when account filter
  `00000000099` (no such account) is entered, then no rows are shown, the error is
  `NO RECORDS FOUND FOR THIS SEARCH CONDITION.`, and the info line is not shown.
- **TS-COCRDLIC-11** (BR-COCRDLIC-03): When account filter `00000000050` and card filter
  0683********1516 (line 2, account 27) are entered together, then no rows match and the error is
  `NO RECORDS FOUND FOR THIS SEARCH CONDITION.`.
- **TS-COCRDLIC-12** (NAV-COCRDLIC-06, BR-COCRDLIC-10): Given page 1, when `S` is typed on row 1
  and ENTER is pressed, then XCTL to COCRDSLC with CDEMO-ACCT-ID = 00000000050, CDEMO-CARD-NUM =
  0500********5740, CDEMO-FROM-PROGRAM = `COCRDLIC`, CDEMO-FROM-TRANID = `CCLI`,
  CDEMO-LAST-MAPSET = `COCRDLI`, CDEMO-PGM-CONTEXT = 0, and CDEMO-USER-TYPE = `U`.
- **TS-COCRDLIC-13** (NAV-COCRDLIC-07): Given page 1, when `U` is typed on row 2 and ENTER is
  pressed, then XCTL to COCRDUPC with CDEMO-ACCT-ID = 00000000027 and CDEMO-CARD-NUM =
  0683********1516.
- **TS-COCRDLIC-14** (VR-COCRDLIC-03, Q-COCRDLIC-02): Given page 2, when `S` is typed on row 1 and
  `U` on row 3 and ENTER is pressed, then the error is
  `PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE`, the select fields on rows 1 and 3 are red,
  and the cursor is on row 3. As written, the rows shown are page 1's cards (lines 1-7) while
  `Page 2` is displayed, and a following PF8 shows lines 8-14 labelled `Page 3`.
- **TS-COCRDLIC-15** (VR-COCRDLIC-04): Given page 1, when `X` is typed on row 2, then the error is
  `INVALID ACTION CODE` and row 2's select field is red. Typing lowercase `s` gives the same error.
- **TS-COCRDLIC-16** (NAV-COCRDLIC-02): Given any page, when PF3 is pressed (even with an invalid
  filter on screen), then XCTL to COMEN01C with CDEMO-TO-PROGRAM = `COMEN01C`, CDEMO-FROM-PROGRAM =
  `COCRDLIC`, CDEMO-PGM-CONTEXT = 0, and CDEMO-USER-TYPE = `U`.
- **TS-COCRDLIC-17** (NAV-COCRDLIC-01): Given page 2, when PF5 (or PF10, CLEAR) is pressed with no
  selection, then page 2 is refreshed exactly as for ENTER.
- **TS-COCRDLIC-18** (Q-COCRDLIC-03): Given page 2 (first card 1561********1600), when account
  filter `00000000050` (its card is on page 1) is entered with ENTER, then no rows are shown, the
  error is `NO MORE RECORDS TO SHOW`, and `Page 2` remains.
- **TS-COCRDLIC-19** (NAV-COCRDLIC-03, Q-COCRDLIC-11): Given the user went from page 3 to COCRDSLC
  with `S` and presses PF3 there, then COCRDLIC shows `Page 1` unfiltered (lines 1-7), and
  CDEMO-USER-ID in the COMMAREA is spaces.
- **TS-COCRDLIC-20** (Q-COCRDLIC-04): Given a test card file in which account 00000000099 has
  exactly 7 cards and at least one card with a higher card number belongs to another account,
  when account filter `00000000099` is entered and then PF8 is pressed, then the first screen
  shows the 7 cards, and PF8 shows `Page 2` with no rows and error `NO MORE RECORDS TO SHOW`.
- **TS-COCRDLIC-21** (BR-COCRDLIC-01): Given a session signed on as `USER0001` (regular user),
  when the list is opened, then all 50 sample cards are reachable by paging. There is no
  restriction by account.

## Open questions
- Should the target restrict regular users to their own accounts' cards, as the header comment
  intends (Q-COCRDLIC-01)? There is no source data linking a signed-on user id to accounts or
  customers, so the target would need a new relationship.
- The source shows the full card number on the list and passes it as the selection key. Should the
  target mask card numbers, and what key should the UI use to select a row (Q-COCRDLIC-16)?
- The BMS pad character for partially filled input fields (spaces or nulls) affects only error
  text, not outcome: any shorter-than-11/16 entry fails the numeric test. Confirm whether the
  target should accept unpadded numbers such as `50` and pad them with zeros.
