# COUSR03C: Fetch a user from USRSEC and delete it

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COUSR03C.cbl` (359 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CU03 (`app/csd/CARDDEMO.CSD:479-488`, program definition `app/csd/CARDDEMO.CSD:299-305`) |
| BMS map | mapset `COUSR03` / map `COUSR3A` (`app/bms/COUSR03.bms`, symbolic map `app/cpy-bms/COUSR03.CPY`) |
| Copybooks | `COCOM01Y` (CARDDEMO-COMMAREA, extended in-line with CDEMO-CU03-INFO), `COUSR03` (symbolic map), `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y` (SEC-USER-DATA), `DFHAID`, `DFHBMSCA` |
| Calls / XCTL targets | XCTL to CDEMO-FROM-PROGRAM (PF3: `COUSR00C` or `COADM01C`), XCTL `COADM01C` (PF12), XCTL `COSGN00C` (no COMMAREA) |
| Data stores | CICS file `USRSEC` (KSDS, 80-byte SEC-USER-DATA, key SEC-USR-ID 8 bytes): Read for update by key, Delete |

## Purpose
COUSR03C lets an administrator remove a user. The administrator enters (or receives from the user list)
a user ID, presses ENTER to display the user's names and type for review, and presses PF5 to delete
the record from USRSEC.

## Invocation and state
- Entered by XCTL from COUSR00C when the user types `D` next to a user (COMMAREA 194 bytes,
  CDEMO-CU03-USR-SELECTED = selected user ID, CDEMO-FROM-PROGRAM = `COUSR00C`), or from COADM01C option 4
  (COMMAREA 160 bytes; extension padded with spaces; CDEMO-FROM-PROGRAM = `COADM01C`). Context 0.
- If started with no COMMAREA (EIBCALEN = 0), it XCTLs to COSGN00C *with* a COMMAREA (see Q-COSGN00C-10).
- COMMAREA extension CDEMO-CU03-INFO (offsets 160-193) has the same layout as COUSR00C's CDEMO-CU00-INFO;
  only CDEMO-CU03-USR-SELECTED is read. The fetched user ID is not kept in state.
- Each turn ends with RETURN TRANSID(`CU03`) COMMAREA(CARDDEMO-COMMAREA) (194 bytes).
- Before XCTL it sets CDEMO-FROM-TRANID = `CU03`, CDEMO-FROM-PROGRAM = `COUSR03C`, CDEMO-PGM-CONTEXT = 0.
- WS-USR-MODIFIED is declared and reset but never used.

## Screen
BMS convention: `POS=(r,c)` is the attribute byte, so data starts in column c+1. Only User ID is
enterable; the names and type are display-only.

```text
             1         2         3         4         5         6         7         8
    12345678901234567890123456789012345678901234567890123456789012345678901234567890
01   Tran: CU03                AWS Mainframe Modernization           Date: mm/dd/yy
02   Prog: COUSR03C                    CardDemo                      Time: hh:mm:ss
03
04                                     Delete User
05
06        Enter User ID: ________
07
08        **********************************************************************
09
10
11        First Name: xxxxxxxxxxxxxxxxxxxx
12
13        Last Name:  xxxxxxxxxxxxxxxxxxxx
14
15        User Type: x (A=Admin, U=User)
16
17
18
19
20
21
22
23   <message line, 78 bytes>
24   ENTER=Fetch  F3=Back  F4=Clear  F5=Delete
```

| Field (row,col of first data byte) | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|--:|:--|:--|:--|
| Tran (1,8) | TRNNAME | 4 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Transaction ID of the program (WS-TRANID) |
| Title 1 (1,22) | TITLE01 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE01 from COTTL01Y |
| Date (1,72) | CURDATE | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='mm/dd/yy' | Current date MM/DD/YY (POPULATE-HEADER-INFO) |
| Prog (2,8) | PGMNAME | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Program name (WS-PGMNAME) |
| Title 2 (2,22) | TITLE02 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE02 from COTTL01Y |
| Time (2,72) | CURTIME | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='hh:mm:ss' | Current time HH:MM:SS |
| Enter User ID (6,22) | USRIDIN | 8 | In/Out | (FSET,IC,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | User ID to fetch/delete (key); initial cursor (IC) |
| First Name (11,19) | FNAME | 20 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, HILIGHT=UNDERLINE | First name (display only) |
| Last Name (13,19) | LNAME | 20 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, HILIGHT=UNDERLINE | Last name (display only) |
| User Type (15,18) | USRTYPE | 1 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, HILIGHT=UNDERLINE | User type (display only) |
| Message (23,2) | ERRMSG | 78 | Out | (ASKIP,BRT,FSET), COLOR=RED | Message line (WS-MESSAGE); default color red, overridden by ERRMSGC where the program sets it |

Unnamed literals: header labels (blue), `Delete User` at row 4 (neutral, bright), `Enter User ID:` at row
6 (green), 70 asterisks at row 8 (yellow), labels `First Name:`, `Last Name:`, `User Type: ` (turquoise),
hint `(A=Admin, U=User)` (blue), and the key legend `ENTER=Fetch  F3=Back  F4=Clear  F5=Delete` at row 24
(yellow). PF12 works but is not shown in the legend.

## Processing flow
1. MAIN-PARA (`app/cbl/COUSR03C.cbl:82-137`): error flag off, modified flag off; clear WS-MESSAGE and ERRMSGO.
2. IF EIBCALEN = 0: CDEMO-TO-PROGRAM = `COSGN00C`, PERFORM RETURN-TO-PREV-SCREEN.
3. OTHERWISE copy the COMMAREA in. IF context is not 1: set it to 1, clear the output map, cursor User ID;
   IF CDEMO-CU03-USR-SELECTED is not blank, move it to User ID and PERFORM PROCESS-ENTER-KEY; send the screen.
4. OTHERWISE RECEIVE the map and evaluate EIBAID: ENTER -> PROCESS-ENTER-KEY; PF3 -> CDEMO-TO-PROGRAM =
   CDEMO-FROM-PROGRAM (or `COADM01C` if blank), RETURN-TO-PREV-SCREEN (no delete); PF4 -> CLEAR-CURRENT-SCREEN;
   PF5 -> DELETE-USER-INFO; PF12 -> CDEMO-TO-PROGRAM = `COADM01C`, RETURN-TO-PREV-SCREEN; other -> error flag,
   CCDA-MSG-INVALID-KEY, send the screen.
5. RETURN TRANSID(CU03) with the COMMAREA.
6. PROCESS-ENTER-KEY (`app/cbl/COUSR03C.cbl:142-169`): IF User ID is blank: "User ID can NOT be empty...",
   cursor User ID, send. OTHERWISE blank First Name, Last Name, User Type; READ USRSEC UPDATE by User ID (NORMAL
   sets the neutral message "Press PF5 key to delete this user ..." and sends); IF no error, move first name,
   last name, and type into the screen and send again.
7. DELETE-USER-INFO (`app/cbl/COUSR03C.cbl:174-192`): IF User ID is blank: "User ID can NOT be empty...",
   cursor User ID, send. OTHERWISE READ USRSEC UPDATE by the User ID on the screen (sends the "Press PF5 ..."
   screen on NORMAL), then DELETE-USER-SEC-FILE regardless of the READ result.
8. DELETE-USER-SEC-FILE (`app/cbl/COUSR03C.cbl:305-336`): `EXEC CICS DELETE DATASET(USRSEC)` without RIDFLD,
   which deletes the record held by the preceding READ UPDATE; evaluate RESP: NORMAL -> clear the fields
   and message, green message `User ` + SEC-USR-ID up to the first space + ` has been deleted ...`, send;
   NOTFND -> "User ID NOT found...", cursor User ID; other -> DISPLAY RESP/RESP2, "Unable to Update User...",
   cursor First Name.
9. CLEAR-CURRENT-SCREEN / INITIALIZE-ALL-FIELDS (`app/cbl/COUSR03C.cbl:341-356`): cursor User ID; User ID,
   First Name, Last Name, User Type, and message set to spaces; send.

## Rules
### Business rules
- **BR-COUSR03C-01**: When entered with no COMMAREA, control is transferred to COSGN00C.
  Source: `app/cbl/COUSR03C.cbl:90-92 (MAIN-PARA)`, `app/cbl/COUSR03C.cbl:197-208 (RETURN-TO-PREV-SCREEN)`.
- **BR-COUSR03C-02**: On first entry with a preselected user (from COUSR00C) the record is fetched and shown;
  otherwise an empty screen with the cursor on User ID. Source: `app/cbl/COUSR03C.cbl:95-105 (MAIN-PARA)`.
- **BR-COUSR03C-03**: Fetch (ENTER) reads USRSEC for update by the entered user ID (exact key, as typed) and
  shows first name, last name, and type (not the password) with the neutral message
  "Press PF5 key to delete this user ...". The READ UPDATE lock is released at the end of the task.
  Source: `app/cbl/COUSR03C.cbl:156-169 (PROCESS-ENTER-KEY)`, `app/cbl/COUSR03C.cbl:267-286 (READ-USER-SEC-FILE)`.
- **BR-COUSR03C-04**: Delete (PF5) re-reads the record for update by the user ID currently on the screen and
  deletes it in the same task. A prior fetch is not required, and the displayed names are not compared with
  the record. Source: `app/cbl/COUSR03C.cbl:188-192 (DELETE-USER-INFO)`, `app/cbl/COUSR03C.cbl:307-311`.
- **BR-COUSR03C-05**: After a successful delete, User ID, names, and type are cleared, the cursor is on User
  ID, and the green message `User <id> has been deleted ...` is shown (id cut at the first space).
  Source: `app/cbl/COUSR03C.cbl:314-322 (DELETE-USER-SEC-FILE)`.
- **BR-COUSR03C-06**: Header with `CU03` / `COUSR03C`, date MM/DD/YY, time HH:MM:SS.
  Source: `app/cbl/COUSR03C.cbl:243-262 (POPULATE-HEADER-INFO)`.

### Validation rules
- **VR-COUSR03C-01**: On fetch (ENTER), User ID must not be spaces or LOW-VALUES; otherwise
  "User ID can NOT be empty..." and cursor on User ID. Source: `app/cbl/COUSR03C.cbl:144-150 (PROCESS-ENTER-KEY)`.
- **VR-COUSR03C-02**: On delete (PF5), User ID must not be blank; otherwise "User ID can NOT be empty..." and
  cursor on User ID. Source: `app/cbl/COUSR03C.cbl:176-182 (DELETE-USER-INFO)`.

### Navigation and keys
- **NAV-COUSR03C-01**: ENTER -> fetch and display the user. Source: `app/cbl/COUSR03C.cbl:109-110 (MAIN-PARA)`.
- **NAV-COUSR03C-02**: PF3 -> XCTL to CDEMO-FROM-PROGRAM (`COUSR00C` or `COADM01C`; `COADM01C` if blank)
  without deleting. Source: `app/cbl/COUSR03C.cbl:111-118 (MAIN-PARA)`.
- **NAV-COUSR03C-03**: PF4 -> clear all fields and the message; cursor User ID.
  Source: `app/cbl/COUSR03C.cbl:119-120`, `app/cbl/COUSR03C.cbl:341-356`.
- **NAV-COUSR03C-04**: PF5 -> delete the user whose ID is on the screen.
  Source: `app/cbl/COUSR03C.cbl:121-122 (MAIN-PARA)`.
- **NAV-COUSR03C-05**: PF12 -> XCTL to COADM01C (not shown in the key legend).
  Source: `app/cbl/COUSR03C.cbl:123-125 (MAIN-PARA)`.
- **NAV-COUSR03C-06**: Any other AID -> "Invalid key pressed. Please see below...         " and the
  screen is re-sent with the received data. For CLEAR and PA1-PA3 the terminal sends no field data:
  RECEIVE gets MAPFAIL (not tested), the map keeps its initial storage, and User ID, First Name, Last
  Name, and User Type are blank on the re-sent screen; the header and the invalid-key message are filled
  as usual (SEND-USRDEL-SCREEN). Target: C-SHORTREAD (ledger-online.md). No cursor field is set in this
  branch (unlike NAV-COUSR01C-04): it is the only COUSR03C send that moves -1 to no length field before
  SEND MAP ... CURSOR, so the cursor position is left to BMS, not chosen by the program (where BMS then
  puts it is inferred from CICS documentation, not observed). Target: focus on User ID, the only
  editable field, as after the read and delete errors (Q-COUSR03C-06). Source:
  `app/cbl/COUSR03C.cbl:126-129 (MAIN-PARA)`, `app/cbl/COUSR03C.cbl:213-225 (SEND-USRDEL-SCREEN)`.
- **NAV-COUSR03C-07**: Every redisplay ends with RETURN TRANSID(`CU03`) COMMAREA(CARDDEMO-COMMAREA).
  Source: `app/cbl/COUSR03C.cbl:134-137 (MAIN-PARA)`.

### Error handling
- **ERR-COUSR03C-01**: READ NOTFND -> "User ID NOT found...", cursor User ID (fetch: fields left blank).
  Source: `app/cbl/COUSR03C.cbl:287-292 (READ-USER-SEC-FILE)`.
- **ERR-COUSR03C-02**: READ other error -> DISPLAY RESP/RESP2, "Unable to lookup User...", cursor First Name.
  Source: `app/cbl/COUSR03C.cbl:293-299 (READ-USER-SEC-FILE)`.
- **ERR-COUSR03C-03**: On PF5 the DELETE runs even when the READ UPDATE failed. Without a record held for
  update and without RIDFLD, CICS rejects the DELETE (INVREQ), so the final message for an unknown user ID
  is "Unable to Update User..." rather than "User ID NOT found...".
  Source: `app/cbl/COUSR03C.cbl:188-192 (DELETE-USER-INFO)`, `app/cbl/COUSR03C.cbl:329-335`.
- **ERR-COUSR03C-04**: DELETE NOTFND -> "User ID NOT found...", cursor User ID.
  Source: `app/cbl/COUSR03C.cbl:323-328 (DELETE-USER-SEC-FILE)`.
- **ERR-COUSR03C-05**: DELETE other error -> DISPLAY RESP/RESP2, "Unable to Update User..." (the text says
  Update, not Delete), cursor First Name. Source: `app/cbl/COUSR03C.cbl:329-335 (DELETE-USER-SEC-FILE)`.
- **ERR-COUSR03C-06**: RECEIVE MAP RESP is captured but not tested.
  Source: `app/cbl/COUSR03C.cbl:230-238 (RECEIVE-USRDEL-SCREEN)`.

## Data effects
- Fetch: no change (READ UPDATE lock until task end).
- Delete (PF5): READ UPDATE and DELETE of the same USRSEC record in one task; the record is physically
  removed (no soft delete, no archive). No other file references USRSEC, so nothing cascades.
- USRSEC is `RECOVERY(NONE)` and `JOURNAL(NO)` (`app/csd/CARDDEMO.CSD:88-96`): the delete is not backed out on
  abend and no audit record is written.

## Quirks
- **Q-COUSR03C-01**: PF5 deletes the user whose ID is in the field at that moment, with no confirmation step and
  no check that it was fetched first. Changing the ID after a fetch deletes a different user than the one
  displayed. Recommendation: fix (delete by the fetched key held in state, with an explicit confirmation).
  Source: `app/cbl/COUSR03C.cbl:174-192`.
- **Q-COUSR03C-02**: An administrator can delete their own signed-on user ID or the last remaining admin; the
  session continues (CDEMO-USER-ID still holds the deleted ID) until sign-off, after which sign-on fails with
  "User not found. Try again ...". Recommendation: decide (guard against self-delete and last-admin delete).
  Source: `app/cbl/COUSR03C.cbl:305-322`.
- **Q-COUSR03C-03**: The delete error messages say "Unable to Update User..." and an unknown ID on PF5 ends with
  that message instead of "User ID NOT found..." (ERR-COUSR03C-03). Recommendation: fix wording in the target
  (keep for parity tests if required). Source: `app/cbl/COUSR03C.cbl:332`.
- **Q-COUSR03C-04**: PF12 returns to the admin menu but is not in the key legend; PF3 is labeled Back and goes to
  the caller. Recommendation: preserve or document. Source: `app/cbl/COUSR03C.cbl:123-125`, `app/bms/COUSR03.bms:144-148`.
- **Q-COUSR03C-05**: Several SENDs per turn (the READ sends the "Press PF5 ..." screen before the fields are
  filled or before the DELETE result). Recommendation: do not port. Source: `app/cbl/COUSR03C.cbl:286`.
- **Q-COUSR03C-06**: Error messages after a failed read put the cursor on First Name, which is a protected
  field. Recommendation: cosmetic. Source: `app/cbl/COUSR03C.cbl:298`, `app/cbl/COUSR03C.cbl:334`.
- **Q-COUSR03C-07**: No authorization check of CDEMO-USER-TYPE (see Q-COADM01C-01) and no audit trail of deletions.
  Recommendation: fix. Source: `app/cbl/COUSR03C.cbl:82-137`.
- **Q-COUSR03C-08**: WS-USR-MODIFIED and its 88-levels are unused copies from COUSR02C. Recommendation: ignore.
  Source: `app/cbl/COUSR03C.cbl:45-47`.

## Test scenarios
- **TS-COUSR03C-01** (covers BR-COUSR03C-02, BR-COUSR03C-03): Given the sample USRSEC and `D` typed next to `USER0005`
  in COUSR00C, when COUSR03C starts, then User ID shows `USER0005`, First Name, Last Name, and type `U` are
  displayed, and the message is "Press PF5 key to delete this user ...".
- **TS-COUSR03C-02** (covers BR-COUSR03C-04, BR-COUSR03C-05): Given TS-COUSR03C-01, when PF5 is pressed, then record
  `USER0005` no longer exists in USRSEC, all fields are cleared, and the green message
  "User USER0005 has been deleted ..." is shown.
- **TS-COUSR03C-03** (covers VR-COUSR03C-01): Given an empty screen (from COADM01C option 4), when ENTER is
  pressed, then "User ID can NOT be empty...".
- **TS-COUSR03C-04** (covers VR-COUSR03C-02): Given an empty screen, when PF5 is pressed, then
  "User ID can NOT be empty..." and nothing is deleted.
- **TS-COUSR03C-05** (covers ERR-COUSR03C-01): Given the sample USRSEC, when `NOSUCHUS` is entered with ENTER, then
  "User ID NOT found...".
- **TS-COUSR03C-06** (covers ERR-COUSR03C-03): Given the sample USRSEC, when `NOSUCHUS` is entered and PF5 is pressed,
  then the final message is "Unable to Update User..." and nothing is deleted.
- **TS-COUSR03C-07** (covers BR-COUSR03C-04, Q-COUSR03C-01): Given the sample USRSEC, when `USER0004` is typed and PF5 is
  pressed without a prior ENTER, then `USER0004` is deleted and "User USER0004 has been deleted ..." is shown.
- **TS-COUSR03C-08** (covers NAV-COUSR03C-02): Given `USER0003` displayed after selection from the list, when PF3
  is pressed, then control passes to COUSR00C and `USER0003` still exists.
- **TS-COUSR03C-09** (covers NAV-COUSR03C-05): Given any state, when PF12 is pressed, then control passes to
  COADM01C and nothing is deleted.
- **TS-COUSR03C-10** (covers NAV-COUSR03C-03): Given a displayed user, when PF4 is pressed, then all fields are cleared.
- **TS-COUSR03C-11** (covers NAV-COUSR03C-06): Given any state, when PF8 is pressed, then
  "Invalid key pressed. Please see below...".
- **TS-COUSR03C-12** (covers Q-COUSR03C-02): Given `ADMIN001` is signed on, when `ADMIN001` is deleted, then the
  delete succeeds; after PF3 to the admin menu and PF3 to sign off, signing on as `ADMIN001` gives
  "User not found. Try again ...".

## Open questions
- Should deleting users be a soft delete (deactivate) in the target, to keep history and allow audit?
- Should self-delete and last-admin delete be prevented (Q-COUSR03C-02)?
