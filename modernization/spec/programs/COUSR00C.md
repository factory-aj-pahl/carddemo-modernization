# COUSR00C: Paged list of security users (10 per page) with selection to update or delete a user

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COUSR00C.cbl` (695 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CU00 (`app/csd/CARDDEMO.CSD:449-458`, program definition `app/csd/CARDDEMO.CSD:278-284`) |
| BMS map | mapset `COUSR00` / map `COUSR0A` (`app/bms/COUSR00.bms`, symbolic map `app/cpy-bms/COUSR00.CPY`) |
| Copybooks | `COCOM01Y` (CARDDEMO-COMMAREA, extended in-line with CDEMO-CU00-INFO), `COUSR00` (symbolic map), `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y` (SEC-USER-DATA), `DFHAID`, `DFHBMSCA` |
| Calls / XCTL targets | XCTL `COUSR02C` (selection `U`), XCTL `COUSR03C` (selection `D`), XCTL `COADM01C` (PF3), XCTL `COSGN00C` (no COMMAREA) |
| Data stores | CICS file `USRSEC` (KSDS, 80-byte SEC-USER-DATA, key SEC-USR-ID 8 bytes): Browse (STARTBR / READNEXT / READPREV / ENDBR), no updates |

## Purpose
COUSR00C lists the users defined in the user security file USRSEC, ten per page, in ascending user ID
order. The administrator can start the list at a given user ID, page forward (PF8) and backward (PF7),
and type `U` or `D` next to a user to open the update (COUSR02C) or delete (COUSR03C) screen for that
user. PF3 returns to the admin menu.

## Invocation and state
- Entered by XCTL from COADM01C option 1 (COMMAREA length 160; the program-specific extension is then
  padded with spaces), and from COUSR02C / COUSR03C when they exit with PF3 and were called from this
  list (COMMAREA length 194). In both cases CDEMO-PGM-CONTEXT = 0.
- If started with no COMMAREA (EIBCALEN = 0), it XCTLs to COSGN00C *with* a COMMAREA (see
  Q-COSGN00C-10).
- The program appends a 34-byte extension to CARDDEMO-COMMAREA (the 05-level items follow `COPY COCOM01Y`
  inside the same 01 group), so its COMMAREA is 194 bytes. COUSR02C (CDEMO-CU02-INFO) and COUSR03C
  (CDEMO-CU03-INFO) declare the same layout, so the selection reaches them at the same offsets.

| Offset (0-based) | Field | Picture | Meaning |
|--:|:--|:--|:--|
| 0-159 | CDEMO-GENERAL-INFO ... CDEMO-MORE-INFO | `COCOM01Y` | Standard COMMAREA (user ID, user type, from/to program, context, ...) |
| 160 | CDEMO-CU00-USRID-FIRST | X(08) | User ID shown in row 1 of the current page (start key for PF7) |
| 168 | CDEMO-CU00-USRID-LAST | X(08) | User ID shown in row 10 of the current page (start key for PF8); only updated when row 10 is filled |
| 176 | CDEMO-CU00-PAGE-NUM | 9(08) | Current page number (relative to the start key of the last ENTER) |
| 184 | CDEMO-CU00-NEXT-PAGE-FLG | X(01) | `Y` = more records exist after this page (PF8 allowed); `N` = no more |
| 185 | CDEMO-CU00-USR-SEL-FLG | X(01) | Selection code typed by the user (`U`, `u`, `D`, `d`, or other) |
| 186 | CDEMO-CU00-USR-SELECTED | X(08) | User ID of the selected row (read by COUSR02C / COUSR03C as CDEMO-CU02/CU03-USR-SELECTED) |

- Each turn ends with RETURN TRANSID(`CU00`) COMMAREA(CARDDEMO-COMMAREA) (194 bytes).
- Before any XCTL the program sets CDEMO-FROM-TRANID = `CU00`, CDEMO-FROM-PROGRAM = `COUSR00C`,
  CDEMO-PGM-CONTEXT = 0, and CDEMO-TO-PROGRAM to the target.

## Screen
BMS convention: `POS=(r,c)` is the attribute byte, so data starts in column c+1. All list fields are
FSET, so every displayed value is sent back to the program on the next RECEIVE.

```text
             1         2         3         4         5         6         7         8
    12345678901234567890123456789012345678901234567890123456789012345678901234567890
01   Tran: CU00                AWS Mainframe Modernization           Date: mm/dd/yy
02   Prog: COUSR00C                    CardDemo                      Time: hh:mm:ss
03
04                                     List Users                    Page: nnnnnnnn
05
06       Search User ID: ________
07
08       Sel    User ID          First Name              Last Name          Type
09       ---    --------    --------------------    --------------------    ----
10        _     xxxxxxxx    xxxxxxxxxxxxxxxxxxxx    xxxxxxxxxxxxxxxxxxxx     x
11        _     xxxxxxxx    xxxxxxxxxxxxxxxxxxxx    xxxxxxxxxxxxxxxxxxxx     x
12        _     xxxxxxxx    xxxxxxxxxxxxxxxxxxxx    xxxxxxxxxxxxxxxxxxxx     x
13        _     xxxxxxxx    xxxxxxxxxxxxxxxxxxxx    xxxxxxxxxxxxxxxxxxxx     x
14        _     xxxxxxxx    xxxxxxxxxxxxxxxxxxxx    xxxxxxxxxxxxxxxxxxxx     x
15        _     xxxxxxxx    xxxxxxxxxxxxxxxxxxxx    xxxxxxxxxxxxxxxxxxxx     x
16        _     xxxxxxxx    xxxxxxxxxxxxxxxxxxxx    xxxxxxxxxxxxxxxxxxxx     x
17        _     xxxxxxxx    xxxxxxxxxxxxxxxxxxxx    xxxxxxxxxxxxxxxxxxxx     x
18        _     xxxxxxxx    xxxxxxxxxxxxxxxxxxxx    xxxxxxxxxxxxxxxxxxxx     x
19        _     xxxxxxxx    xxxxxxxxxxxxxxxxxxxx    xxxxxxxxxxxxxxxxxxxx     x
20
21              Type 'U' to Update or 'D' to Delete a User from the list
22
23   <message line, 78 bytes>
24   ENTER=Continue  F3=Back  F7=Backward  F8=Forward
```

| Field (row,col of first data byte) | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|--:|:--|:--|:--|
| Tran (1,8) | TRNNAME | 4 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Transaction ID of the program (WS-TRANID) |
| Title 1 (1,22) | TITLE01 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE01 from COTTL01Y |
| Date (1,72) | CURDATE | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='mm/dd/yy' | Current date MM/DD/YY (POPULATE-HEADER-INFO) |
| Prog (2,8) | PGMNAME | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Program name (WS-PGMNAME) |
| Title 2 (2,22) | TITLE02 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE02 from COTTL01Y |
| Time (2,72) | CURTIME | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='hh:mm:ss' | Current time HH:MM:SS |
| Page (4,72) | PAGENUM | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Page number: CDEMO-CU00-PAGE-NUM 9(8) moved to X(8), so shown zero-padded, e.g. 00000001 |
| Search User ID (6,22) | USRIDIN | 8 | In/Out | (FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | Search User ID: start key for ENTER; cleared after each successful list display |
| Sel row 1 (10,7) | SEL0001 | 1 | In/Out | (FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE, INITIAL=' ' | Row 1 selection code: U/u = update, D/d = delete |
| User ID row 1 (10,13) | USRID01 | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Row 1 User ID (SEC-USR-ID); FSET so it is returned on RECEIVE |
| First Name row 1 (10,25) | FNAME01 | 20 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Row 1 first name (SEC-USR-FNAME) |
| Last Name row 1 (10,49) | LNAME01 | 20 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Row 1 last name (SEC-USR-LNAME) |
| Type row 1 (10,74) | UTYPE01 | 1 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Row 1 user type (SEC-USR-TYPE, raw code A/U) |
| Message (23,2) | ERRMSG | 78 | Out | (ASKIP,BRT,FSET), COLOR=RED | Message line (WS-MESSAGE); default color red, overridden by ERRMSGC where the program sets it |
| Rows 2-10 (rows 11-19, same columns) | SEL0002-SEL0010, USRID02-USRID10, FNAME02-FNAME10, LNAME02-LNAME10, UTYPE02-UTYPE10 | 1/8/20/20/1 | SEL In/Out, others Out | Same as row 1 | Same as row 1 for list rows 2-10 |

Unnamed literals: header labels (blue), `List Users` at row 4 (neutral, bright), `Page:` at row 4
column 66 (turquoise, bright), `Search User ID:` at row 6 (turquoise), column headings
`Sel`, `User ID `, `     First Name     `, `     Last Name      `, `Type` at row 8 and dashes at row 9
(neutral), the instruction `Type 'U' to Update or 'D' to Delete a User from the list` at row 21 (neutral,
bright), and the key legend `ENTER=Continue  F3=Back  F7=Backward  F8=Forward` at row 24 (yellow).

## Processing flow
1. MAIN-PARA (`app/cbl/COUSR00C.cbl:98-144`): set error flag off, end-of-file flag off, next-page flag `N`
   (overwritten by the COMMAREA copy below), send-with-erase on; clear WS-MESSAGE and ERRMSGO; set the
   cursor to Search User ID.
2. IF EIBCALEN = 0: CDEMO-TO-PROGRAM = `COSGN00C`, PERFORM RETURN-TO-PREV-SCREEN.
3. OTHERWISE copy DFHCOMMAREA(1:EIBCALEN) into the 194-byte COMMAREA.
   - IF context is not 1 (first entry): set context 1, set the output map to LOW-VALUES, PERFORM
     PROCESS-ENTER-KEY (which lists from the first user), then PERFORM SEND-USRLST-SCREEN again.
   - OTHERWISE RECEIVE the map and evaluate EIBAID: ENTER -> PROCESS-ENTER-KEY; PF3 -> CDEMO-TO-PROGRAM =
     `COADM01C`, RETURN-TO-PREV-SCREEN; PF7 -> PROCESS-PF7-KEY; PF8 -> PROCESS-PF8-KEY; other -> error flag,
     cursor on search field, CCDA-MSG-INVALID-KEY, send the screen.
4. RETURN TRANSID(CU00) with the COMMAREA.
5. PROCESS-ENTER-KEY (`app/cbl/COUSR00C.cbl:149-232`):
   1. FOR EACH row 1 to 10 in order, find the first row whose Sel field is neither spaces nor LOW-VALUES.
      Copy that Sel character to CDEMO-CU00-USR-SEL-FLG and that row's User ID to CDEMO-CU00-USR-SELECTED.
      If no row has a selection, set both to spaces.
   2. IF both the selection flag and the selected user ID are non-blank, evaluate the flag:
      `U` or `u` -> XCTL COUSR02C; `D` or `d` -> XCTL COUSR03C; anything else -> message
      "Invalid selection. Valid values are U and D" (the error flag is not set) and continue.
   3. Start key SEC-USR-ID = Search User ID if it is non-blank, else LOW-VALUES.
   4. Set the page number to 0 and PERFORM PROCESS-PAGE-FORWARD.
   5. IF no error, clear the Search User ID field.
6. PROCESS-PF7-KEY (`app/cbl/COUSR00C.cbl:237-255`): start key = CDEMO-CU00-USRID-FIRST (LOW-VALUES if
   blank); set the next-page flag to `Y`; IF page number > 1, PERFORM PROCESS-PAGE-BACKWARD; OTHERWISE
   message "You are already at the top of the page...", send without ERASE (the displayed rows stay).
7. PROCESS-PF8-KEY (`app/cbl/COUSR00C.cbl:260-277`): start key = CDEMO-CU00-USRID-LAST (HIGH-VALUES if
   blank); IF next-page flag is `Y`, PERFORM PROCESS-PAGE-FORWARD; OTHERWISE message
   "You are already at the bottom of the page...", send without ERASE.
8. PROCESS-PAGE-FORWARD (`app/cbl/COUSR00C.cbl:282-331`):
   1. STARTBR USRSEC at the start key. The GTEQ option is commented out, but GTEQ is the CICS default, so
      the browse is positioned at the first key greater than or equal to the start key.
   2. IF no error:
      1. IF EIBAID is not ENTER, PF7, or PF3 (in practice: PF8), READNEXT once and discard the record.
         This skips the last record of the current page, which the STARTBR positioned on.
      2. IF not at end of file, clear the user ID, first name, last name, and type of all 10 rows
         (the Sel fields are not cleared).
      3. FOR row = 1 while row <= 10 and not end of file and no error: READNEXT; if a record was read,
         fill the row (row 1 also sets CDEMO-CU00-USRID-FIRST, row 10 also sets CDEMO-CU00-USRID-LAST)
         and advance the row.
      4. IF 10 rows were read without reaching end of file: add 1 to the page number, READNEXT once more
         as a look-ahead; next-page flag = `Y` if a record was read, otherwise `N` (the end-of-file branch
         sets the bottom message). OTHERWISE (end of file during the loop): next-page flag = `N`, and add 1 to
         the page number only if at least one row was filled.
      5. ENDBR, move the page number to the screen, clear the Search User ID field, send the screen.
9. PROCESS-PAGE-BACKWARD (`app/cbl/COUSR00C.cbl:336-379`):
   1. STARTBR at the start key (CDEMO-CU00-USRID-FIRST).
   2. IF no error:
      1. IF EIBAID is not ENTER or PF8 (in practice: PF7), READPREV once and discard it. The first READPREV
         after STARTBR returns the record at the start key, which is the first row of the current page.
      2. IF not at end of file, clear all 10 rows (except Sel).
      3. FOR row = 10 down to 1 while not end of file and no error: READPREV; fill the row (row 1 sets
         CDEMO-CU00-USRID-FIRST, row 10 sets CDEMO-CU00-USRID-LAST). The previous ten users therefore
         appear in ascending order.
      4. IF 10 rows were read without reaching the beginning of the file: READPREV once more as a
         look-ahead; IF the next-page flag is `Y` (always true here, set by PF7): IF a record was read and the
         page number > 1, subtract 1 from the page number, OTHERWISE set the page number to 1. If the
         beginning of the file was reached during the fill loop, the page number is not changed.
      5. ENDBR, move the page number to the screen, send the screen (the search field is not cleared here).
10. STARTBR / READNEXT / READPREV / ENDBR paragraphs set the messages listed under Error handling. Each
    end-of-file or error branch also sends the screen immediately, so a single turn can send the map
    several times; the user sees the last one, which carries the last message set.
11. SEND-USRLST-SCREEN (`app/cbl/COUSR00C.cbl:522-544`): header, message, SEND MAP with CURSOR and with
    ERASE unless the "already at top/bottom" path cleared the send-erase flag.

## Rules
### Business rules
- **BR-COUSR00C-01**: When entered with no COMMAREA, control is transferred to COSGN00C (with the
  COMMAREA, FROM-TRANID `CU00`, FROM-PROGRAM `COUSR00C`, context 0).
  Source: `app/cbl/COUSR00C.cbl:110-112 (MAIN-PARA)`, `app/cbl/COUSR00C.cbl:506-517 (RETURN-TO-PREV-SCREEN)`.
- **BR-COUSR00C-02**: On first entry (context not 1), the list is shown from the first user ID in the
  file, page 1, with no search key and no selection. The screen is sent twice in this turn (once by the
  browse, once by MAIN-PARA). Source: `app/cbl/COUSR00C.cbl:115-119 (MAIN-PARA)`.
- **BR-COUSR00C-03**: Page size is fixed at 10 users. Users are listed in ascending key (USRSEC user ID)
  order. Source: `app/cbl/COUSR00C.cbl:293-306 (PROCESS-PAGE-FORWARD)`, `app/bms/COUSR00.bms:153-442`.
- **BR-COUSR00C-04**: Each list row shows the user ID, first name (20), last name (20), and the raw
  one-character user type (`A` or `U`; no translation). Passwords are never shown.
  Source: `app/cbl/COUSR00C.cbl:384-441 (POPULATE-USER-DATA)`.
- **BR-COUSR00C-05**: Only one selection is processed per ENTER: the first row from the top whose Sel
  field is not blank. Selections in lower rows are ignored.
  Source: `app/cbl/COUSR00C.cbl:151-185 (PROCESS-ENTER-KEY)`.
- **BR-COUSR00C-06**: Selection `U` or `u` on a row with a user ID transfers control to COUSR02C (update)
  with CDEMO-CU00-USR-SEL-FLG = the typed code and CDEMO-CU00-USR-SELECTED = that row's user ID.
  Source: `app/cbl/COUSR00C.cbl:187-199 (PROCESS-ENTER-KEY)`.
- **BR-COUSR00C-07**: Selection `D` or `d` on a row with a user ID transfers control to COUSR03C (delete)
  in the same way. Source: `app/cbl/COUSR00C.cbl:200-209 (PROCESS-ENTER-KEY)`.
- **BR-COUSR00C-08**: A selection code typed on an empty row (no user ID) is ignored without a message.
  Source: `app/cbl/COUSR00C.cbl:187-188 (PROCESS-ENTER-KEY)`.
- **BR-COUSR00C-09**: ENTER always rebuilds the list from the beginning: from the Search User ID if one is
  entered (first user ID greater than or equal to it), otherwise from the first user in the file. The page
  number restarts at 1. After the list is displayed the Search User ID field is cleared, so a second ENTER
  lists from the first user again. Source: `app/cbl/COUSR00C.cbl:218-232 (PROCESS-ENTER-KEY)`, `app/cbl/COUSR00C.cbl:328`.
- **BR-COUSR00C-10**: Forward paging (PF8, when the next-page flag is `Y`) shows the 10 users that follow
  CDEMO-CU00-USRID-LAST and adds 1 to the page number. Source: `app/cbl/COUSR00C.cbl:260-271 (PROCESS-PF8-KEY)`,
  `app/cbl/COUSR00C.cbl:282-331 (PROCESS-PAGE-FORWARD)`.
- **BR-COUSR00C-11**: After filling a full page, the program reads one more record to decide whether a
  next page exists (next-page flag `Y` or `N`). A page with fewer than 10 users always sets the flag to
  `N`. Source: `app/cbl/COUSR00C.cbl:308-323 (PROCESS-PAGE-FORWARD)`.
- **BR-COUSR00C-12**: The page number is incremented on a forward page only if at least one row was filled.
  Source: `app/cbl/COUSR00C.cbl:317-322 (PROCESS-PAGE-FORWARD)`.
- **BR-COUSR00C-13**: Backward paging (PF7, when page number > 1) shows the 10 users that precede
  CDEMO-CU00-USRID-FIRST, in ascending order, and sets the next-page flag to `Y`. The page number is
  decreased by 1 if another user exists before the new first row; if the new page starts at the first
  user of the file, the page number is set to 1. Source: `app/cbl/COUSR00C.cbl:237-249 (PROCESS-PF7-KEY)`,
  `app/cbl/COUSR00C.cbl:336-379 (PROCESS-PAGE-BACKWARD)`.
- **BR-COUSR00C-14**: The page number is displayed as the 8-digit zero-padded value of
  CDEMO-CU00-PAGE-NUM (for example `00000001`). Source: `app/cbl/COUSR00C.cbl:327`, `app/cbl/COUSR00C.cbl:376`.
- **BR-COUSR00C-15**: Header as in the other user screens: titles, `CU00`, `COUSR00C`, date MM/DD/YY,
  time HH:MM:SS. Source: `app/cbl/COUSR00C.cbl:562-581 (POPULATE-HEADER-INFO)`.
- **BR-COUSR00C-16**: Rows that are not filled on a page are blank (user ID, names, and type set to
  spaces before the page is filled). Source: `app/cbl/COUSR00C.cbl:292-296`, `app/cbl/COUSR00C.cbl:446-501 (INITIALIZE-USER-DATA)`.

### Validation rules
- **VR-COUSR00C-01**: A selection code other than `U`, `u`, `D`, `d` on a row with a user ID produces
  the message "Invalid selection. Valid values are U and D" with the cursor on the search field. The
  list is still rebuilt as for ENTER (BR-COUSR00C-09), and a later browse message (for example the
  bottom-of-file message) replaces this text. Source: `app/cbl/COUSR00C.cbl:210-215 (PROCESS-ENTER-KEY)`.

### Navigation and keys
- **NAV-COUSR00C-01**: ENTER -> process selection, else rebuild the list (PROCESS-ENTER-KEY).
  Source: `app/cbl/COUSR00C.cbl:123-124 (MAIN-PARA)`.
- **NAV-COUSR00C-02**: PF3 -> XCTL to COADM01C with the COMMAREA (always to the admin menu).
  Source: `app/cbl/COUSR00C.cbl:125-127 (MAIN-PARA)`, `app/cbl/COUSR00C.cbl:506-517`.
- **NAV-COUSR00C-03**: PF7 -> previous page; on page 1 (or less), message
  "You are already at the top of the page..." and the current rows stay on the screen (sent without ERASE).
  In both cases the next-page flag is set to `Y` first (line 245), so after PF7 on page 1 a following PF8
  pages forward from CDEMO-CU00-USRID-LAST even when no next page exists; with no further users this clears
  all rows and shows "You have reached the bottom of the page..." with the page number unchanged
  (Q-COUSR00C-15).
  Source: `app/cbl/COUSR00C.cbl:128-129`, `app/cbl/COUSR00C.cbl:237-255 (PROCESS-PF7-KEY)`, `app/cbl/COUSR00C.cbl:245`.
- **NAV-COUSR00C-04**: PF8 -> next page; when the next-page flag is not `Y`, message
  "You are already at the bottom of the page..." and the current rows stay (sent without ERASE).
  The flag can be `Y` without a next page after PF7 on page 1 (NAV-COUSR00C-03).
  Source: `app/cbl/COUSR00C.cbl:130-131`, `app/cbl/COUSR00C.cbl:260-277 (PROCESS-PF8-KEY)`, `app/cbl/COUSR00C.cbl:245`.
- **NAV-COUSR00C-05**: Any other AID -> message "Invalid key pressed. Please see below...         ", cursor on
  the search field, screen re-sent with ERASE (the received rows and page number are sent back, so the
  list stays visible). Source: `app/cbl/COUSR00C.cbl:132-136 (MAIN-PARA)`.
- **NAV-COUSR00C-06**: Selection `U`/`u` -> XCTL COUSR02C; `D`/`d` -> XCTL COUSR03C; both with the 194-byte
  COMMAREA, FROM-TRANID `CU00`, FROM-PROGRAM `COUSR00C`, context 0.
  Source: `app/cbl/COUSR00C.cbl:189-209 (PROCESS-ENTER-KEY)`.
- **NAV-COUSR00C-07**: Every redisplay ends with RETURN TRANSID(`CU00`) COMMAREA(CARDDEMO-COMMAREA).
  Source: `app/cbl/COUSR00C.cbl:141-144 (MAIN-PARA)`.

### Error handling
- **ERR-COUSR00C-01**: STARTBR NOTFND (no user ID greater than or equal to the start key, or an empty file)
  -> end-of-file flag set, message "You are at the top of the page...", cursor on search field, screen sent.
  The error flag is not set, so processing continues and ENDBR is issued for a browse that never started
  (see Q-COUSR00C-03). Source: `app/cbl/COUSR00C.cbl:600-606 (STARTBR-USER-SEC-FILE)`.
- **ERR-COUSR00C-02**: STARTBR any other error -> `DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD` to the CICS
  log, error flag set, message "Unable to lookup User...", cursor on search field, screen sent; no rows
  are read and no ENDBR is issued. Source: `app/cbl/COUSR00C.cbl:607-613 (STARTBR-USER-SEC-FILE)`.
- **ERR-COUSR00C-03**: READNEXT ENDFILE -> end-of-file flag set, message
  "You have reached the bottom of the page...", screen sent. Source: `app/cbl/COUSR00C.cbl:634-640 (READNEXT-USER-SEC-FILE)`.
- **ERR-COUSR00C-04**: READNEXT any other error -> RESP/RESP2 DISPLAYed, error flag set, message
  "Unable to lookup User...", screen sent. Source: `app/cbl/COUSR00C.cbl:641-647 (READNEXT-USER-SEC-FILE)`.
- **ERR-COUSR00C-05**: READPREV ENDFILE -> end-of-file flag set, message
  "You have reached the top of the page...", screen sent. Source: `app/cbl/COUSR00C.cbl:668-674 (READPREV-USER-SEC-FILE)`.
- **ERR-COUSR00C-06**: READPREV any other error (including NOTFND when the start key no longer exists,
  see Q-COUSR00C-09) -> RESP/RESP2 DISPLAYed, error flag set, message "Unable to lookup User...", screen
  sent. Source: `app/cbl/COUSR00C.cbl:675-681 (READPREV-USER-SEC-FILE)`.
- **ERR-COUSR00C-07**: ENDBR has no RESP option; any exception abends the task.
  Source: `app/cbl/COUSR00C.cbl:687-691 (ENDBR-USER-SEC-FILE)`.
- **ERR-COUSR00C-08**: RECEIVE MAP RESP is captured but not tested.
  Source: `app/cbl/COUSR00C.cbl:549-557 (RECEIVE-USRLST-SCREEN)`.

## Data effects
Read-only. The program browses USRSEC and changes no records. The browse is opened and closed within one
task (STARTBR ... ENDBR), so no browse or lock is held between screens. State between screens is kept only
in the COMMAREA extension (first/last key, page number, next-page flag, selection).

## Quirks
- **Q-COUSR00C-01**: ENTER (with or without a selection error) always restarts the list at the search
  key or at the first user and resets the page number to 1, so the user loses the current page.
  Recommendation: decide (a modern list would keep the page and filter).
  Source: `app/cbl/COUSR00C.cbl:218-228`.
- **Q-COUSR00C-02**: End-of-file messages appear on normal displays. When the last page holds exactly
  10 users, or fewer, the look-ahead or fill loop hits end of file and the message
  "You have reached the bottom of the page..." is shown. With the 10 sample users this message appears
  on the very first display. Returning to the first page with PF7 shows
  "You have reached the top of the page...". These messages also replace "Invalid selection. Valid values
  are U and D". Recommendation: decide (the target can show "no more records" as a state, not a message).
  Source: `app/cbl/COUSR00C.cbl:308-316`, `app/cbl/COUSR00C.cbl:634-640`, `app/cbl/COUSR00C.cbl:668-674`.
- **Q-COUSR00C-03**: When STARTBR returns NOTFND (search key greater than every user ID, or empty file), the
  code sets end of file but not the error flag, then issues ENDBR at line 325 for a browse that never
  started. CICS raises INVREQ, and with no RESP or HANDLE the task abends (expected abend code AEIP). The
  message set just before, "You are at the top of the page...", is also misleading. Recommendation: fix
  (show "No users found" and an empty list). Source: `app/cbl/COUSR00C.cbl:597-606`, `app/cbl/COUSR00C.cbl:325`.
- **Q-COUSR00C-04**: CDEMO-CU00-USRID-LAST is updated only when row 10 is filled, so after a short last page
  it still holds the previous page's last key (FIRST is updated). Because the next-page flag is then `N`, PF8
  does not use it. Recommendation: fix in the target (track the last displayed key).
  Source: `app/cbl/COUSR00C.cbl:433-435`.
- **Q-COUSR00C-05**: The page number is relative to the start key. After ENTER with a search key in the
  middle of the file the counter restarts at 1, and PF7 then stops with "You are already at the top of the
  page..." even though earlier users exist. Recommendation: decide.
  Source: `app/cbl/COUSR00C.cbl:227`, `app/cbl/COUSR00C.cbl:248-255`.
- **Q-COUSR00C-06**: The Sel fields are not cleared when the list is rebuilt (INITIALIZE-USER-DATA clears only
  ID, names, and type). After an invalid selection, the typed code remains on the same row, which may now
  show a different user. Recommendation: fix (clear selections on every list refresh).
  Source: `app/cbl/COUSR00C.cbl:446-501`.
- **Q-COUSR00C-07**: The skip-one-record logic depends on EIBAID instead of on explicit direction:
  forward skips unless the AID is ENTER, PF7, or PF3; backward skips unless the AID is ENTER or PF8. PF3 is
  in the list because COUSR02C/COUSR03C return here with PF3 (EIBAID is still PF3 in the XCTL target) and
  must not skip the first user. Recommendation: fix (keyset paging with "key > last" / "key < first").
  Source: `app/cbl/COUSR00C.cbl:288-290`, `app/cbl/COUSR00C.cbl:342-344`.
- **Q-COUSR00C-08**: A single turn may SEND the map several times (each end-of-file or error branch sends
  immediately, and first entry sends twice). Only the last send is visible. Recommendation: do not port; the
  target renders once with the final message. Source: `app/cbl/COUSR00C.cbl:118-119`, `app/cbl/COUSR00C.cbl:606`,
  `app/cbl/COUSR00C.cbl:640`, `app/cbl/COUSR00C.cbl:674`.
- **Q-COUSR00C-09**: Paging keys are kept between screens without any lock. If another administrator deletes
  the user shown in row 1, the next PF7 issues READPREV right after a STARTBR on a key that no longer exists,
  which CICS answers with NOTFND, giving "Unable to lookup User...". If the user shown in row 10 is
  deleted, the next PF8 positions on the following user and the skip-read hides it. Recommendation: fix
  (keyset pagination on exclusive bounds).
  Source: `app/cbl/COUSR00C.cbl:239-243`, `app/cbl/COUSR00C.cbl:262-266`, `app/cbl/COUSR00C.cbl:342-344`.
- **Q-COUSR00C-10**: The program does not check CDEMO-USER-TYPE; any caller with a COMMAREA can list all users
  (see Q-COADM01C-01). Recommendation: fix (ADMIN role required). Source: `app/cbl/COUSR00C.cbl:98-144`.
- **Q-COUSR00C-11**: PF3 always goes to COADM01C, ignoring CDEMO-FROM-PROGRAM. Recommendation: preserve.
  Source: `app/cbl/COUSR00C.cbl:125-127`.
- **Q-COUSR00C-12**: The search is a start position, not a filter: users after the search key that do not
  match it are still listed. Recommendation: decide (keep "start at" semantics or add a filter).
  Source: `app/cbl/COUSR00C.cbl:218-222`, `app/cbl/COUSR00C.cbl:588-595`.
- **Q-COUSR00C-13**: Dead data: WS-USER-DATA (USER-REC OCCURS 10), WS-REC-COUNT, and WS-PAGE-NUM are never
  used, and the browse error branches DISPLAY RESP codes only (no PII). Recommendation: ignore.
  Source: `app/cbl/COUSR00C.cbl:52-64`, `app/cbl/COUSR00C.cbl:608`.
- **Q-COUSR00C-14**: The selection is taken from the user ID fields sent back by the terminal (FSET output
  fields), not from server-side state, so the selected ID is whatever the terminal returns for that row.
  Recommendation: fix (the target selects by row key held on the server or by an ID validated against the
  current page). Source: `app/cbl/COUSR00C.cbl:152-181`, `app/bms/COUSR00.bms:162-166`.
- **Q-COUSR00C-15**: PF7 sets the next-page flag to `Y` before testing the page number, so PF7 on page 1
  marks a next page that may not exist. With the 10 sample users, PF7 then PF8 starts at `USER0005`, skips
  it, clears all ten rows, hits end of file, and shows an empty list with "You have reached the bottom of the
  page..." and page `00000001`, instead of "You are already at the bottom of the page..." with the rows kept.
  Recommendation: fix. Source: `app/cbl/COUSR00C.cbl:245-255 (PROCESS-PF7-KEY)`,
  `app/cbl/COUSR00C.cbl:270-271 (PROCESS-PF8-KEY)`, `app/cbl/COUSR00C.cbl:282-323 (PROCESS-PAGE-FORWARD)`.

## Test scenarios
Sample data: the ten users of `app/jcl/DUSRSECJ.jcl:35-44` sort as `ADMIN001`-`ADMIN005`, then
`USER0001`-`USER0005`. Scenarios TS-COUSR00C-08 to -10 use a larger synthetic file of 25 users
`T0000001`-`T0000025`.

- **TS-COUSR00C-01** (covers BR-COUSR00C-02, BR-COUSR00C-03, BR-COUSR00C-11, BR-COUSR00C-14, Q-COUSR00C-02): Given the
  sample USRSEC, when COADM01C option 1 transfers control here, then rows 10-19 show `ADMIN001` ... `ADMIN005`,
  `USER0001` ... `USER0005` with types `A` and `U`, Page shows `00000001`, the message is
  "You have reached the bottom of the page...", and the COMMAREA has CU00-USRID-FIRST = `ADMIN001`,
  CU00-USRID-LAST = `USER0005`, CU00-NEXT-PAGE-FLG = `N`, CU00-PAGE-NUM = 1.
- **TS-COUSR00C-02** (covers NAV-COUSR00C-04): Given TS-COUSR00C-01, when PF8 is pressed, then the rows are unchanged and
  the message is "You are already at the bottom of the page...".
- **TS-COUSR00C-03** (covers NAV-COUSR00C-03): Given TS-COUSR00C-01, when PF7 is pressed, then the rows are unchanged and
  the message is "You are already at the top of the page...".
- **TS-COUSR00C-04** (covers BR-COUSR00C-09, BR-COUSR00C-16): Given the sample USRSEC, when `USER0003` is typed in Search User
  ID and ENTER is pressed, then rows 1-3 show `USER0003`, `USER0004`, `USER0005`, rows 4-10 are blank, Page is
  `00000001`, the message is "You have reached the bottom of the page...", and the search field is cleared.
- **TS-COUSR00C-05** (covers BR-COUSR00C-06, NAV-COUSR00C-06): Given TS-COUSR00C-01, when `U` is typed in the Sel field of
  row 2 and ENTER is pressed, then control passes to COUSR02C with CDEMO-CU00-USR-SEL-FLG = `U`,
  CDEMO-CU00-USR-SELECTED = `ADMIN002`, CDEMO-FROM-PROGRAM = `COUSR00C`, CDEMO-FROM-TRANID = `CU00`, context 0,
  COMMAREA length 194.
- **TS-COUSR00C-06** (covers BR-COUSR00C-07, BR-COUSR00C-05): Given TS-COUSR00C-01, when `d` is typed on row 6 and `U` on row 8 and
  ENTER is pressed, then control passes to COUSR03C with CDEMO-CU00-USR-SELECTED = `USER0001` (row 8 ignored).
- **TS-COUSR00C-07** (covers BR-COUSR00C-08): Given TS-COUSR00C-04 (rows 4-10 blank), when `U` is typed on row 5 and ENTER is
  pressed, then no XCTL happens and the list is rebuilt from `ADMIN001` (the search field had been cleared).
- **TS-COUSR00C-08** (covers BR-COUSR00C-10, BR-COUSR00C-11, BR-COUSR00C-12, BR-COUSR00C-13): Given 25 users, when the list is entered, then page 1
  shows `T0000001`-`T0000010` with no message and next-page `Y`; PF8 shows `T0000011`-`T0000020`, page 2, no
  message; PF8 shows `T0000021`-`T0000025` in rows 1-5, rows 6-10 blank, page 3, message
  "You have reached the bottom of the page..."; PF8 again gives "You are already at the bottom of the page...";
  PF7 shows `T0000011`-`T0000020`, page 2; PF7 shows `T0000001`-`T0000010`, page 1, message
  "You have reached the top of the page..."; PF7 again gives "You are already at the top of the page...".
- **TS-COUSR00C-09** (covers VR-COUSR00C-01, Q-COUSR00C-01, Q-COUSR00C-06): Given 25 users and page 2 displayed, when `X` is typed on row
  1 and ENTER is pressed, then the message is "Invalid selection. Valid values are U and D", the list shows
  page 1 (`T0000001`-`T0000010`), and `X` is still in row 1's Sel field.
- **TS-COUSR00C-10** (covers BR-COUSR00C-09, Q-COUSR00C-05): Given 25 users, when `T0000005` is searched, then rows show
  `T0000005`-`T0000014`, page 1; and PF7 then gives "You are already at the top of the page...".
- **TS-COUSR00C-11** (covers ERR-COUSR00C-01, Q-COUSR00C-03): Given the sample USRSEC, when `ZZZZZZZZ` is searched, then (as coded)
  STARTBR returns NOTFND, the message "You are at the top of the page..." is sent, and the subsequent ENDBR
  abends the task with INVREQ (AEIP). The target should instead show an empty list with a "no users found"
  message.
- **TS-COUSR00C-12** (covers NAV-COUSR00C-02): Given any list page, when PF3 is pressed, then control passes to COADM01C with
  FROM-PROGRAM `COUSR00C`.
- **TS-COUSR00C-13** (covers NAV-COUSR00C-05): Given any list page, when PF5 is pressed, then the same rows are shown with
  "Invalid key pressed. Please see below...".
- **TS-COUSR00C-14** (covers BR-COUSR00C-01): Given no COMMAREA, when CU00 is started, then control passes to COSGN00C.
- **TS-COUSR00C-15** (covers ERR-COUSR00C-02): Given USRSEC is closed, when the list is entered, then the message is
  "Unable to lookup User...".
- **TS-COUSR00C-16** (covers BR-COUSR00C-02, Q-COUSR00C-07): Given COUSR02C was opened from this list, when PF3 is pressed in
  COUSR02C, then this program is re-entered with context 0 and EIBAID PF3, and the list starts again at the
  first user, page 1 (the first user is not skipped).
- **TS-COUSR00C-17** (covers NAV-COUSR00C-03, NAV-COUSR00C-04, Q-COUSR00C-15): Given TS-COUSR00C-03, when PF8 is
  pressed, then (as coded) all ten rows are blank, Page shows `00000001`, and the message is
  "You have reached the bottom of the page...".

## Open questions
- What abend code does the ENDBR-without-browse path (Q-COUSR00C-03) produce on the production CICS
  release? The source makes the INVREQ certain only if no browse is active; this should be confirmed on a
  test region.
- Should the modernized list keep the "start at user ID" semantics or offer a real filter (Q-COUSR00C-12)?
