# COUSR02C: Fetch a user from USRSEC and update its names, password, or user type

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COUSR02C.cbl` (414 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CU02 (`app/csd/CARDDEMO.CSD:469-478`, program definition `app/csd/CARDDEMO.CSD:292-298`) |
| BMS map | mapset `COUSR02` / map `COUSR2A` (`app/bms/COUSR02.bms`, symbolic map `app/cpy-bms/COUSR02.CPY`) |
| Copybooks | `COCOM01Y` (CARDDEMO-COMMAREA, extended in-line with CDEMO-CU02-INFO), `COUSR02` (symbolic map), `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y` (SEC-USER-DATA), `DFHAID`, `DFHBMSCA` |
| Calls / XCTL targets | XCTL to CDEMO-FROM-PROGRAM (PF3: `COUSR00C` or `COADM01C`), XCTL `COADM01C` (PF12), XCTL `COSGN00C` (no COMMAREA) |
| Data stores | CICS file `USRSEC` (KSDS, 80-byte SEC-USER-DATA, key SEC-USR-ID 8 bytes): Read for update by key, Rewrite |

## Purpose
COUSR02C lets an administrator change an existing user's first name, last name, password, and user
type. The administrator enters (or receives from the user list) a user ID, presses ENTER to fetch the
record, edits the fields, and presses PF5 to save or PF3 to save and exit. A save is only written if at
least one field differs from the stored record.

## Invocation and state
- Entered by XCTL from COUSR00C when the user types `U` next to a user (COMMAREA 194 bytes,
  CDEMO-CU02-USR-SELECTED = selected user ID, CDEMO-FROM-PROGRAM = `COUSR00C`), or from COADM01C option 3
  (COMMAREA 160 bytes; the extension is padded with spaces, so no user is preselected;
  CDEMO-FROM-PROGRAM = `COADM01C`). Context is 0 in both cases.
- If started with no COMMAREA (EIBCALEN = 0), it XCTLs to COSGN00C *with* a COMMAREA (see Q-COSGN00C-10).
- COMMAREA extension CDEMO-CU02-INFO (offsets 160-193) has the same layout as COUSR00C's CDEMO-CU00-INFO:
  USRID-FIRST X(08), USRID-LAST X(08), PAGE-NUM 9(08), NEXT-PAGE-FLG X(01), USR-SEL-FLG X(01),
  USR-SELECTED X(08). This program reads only CDEMO-CU02-USR-SELECTED; the other fields are passed through
  unchanged, so COUSR00C's paging state survives the round trip (COUSR00C then resets it anyway).
- CDEMO-FROM-PROGRAM keeps the caller's name for the whole conversation (only RETURN-TO-PREV-SCREEN changes
  it), and PF3 returns there.
- The user ID being edited is not kept in the COMMAREA; every PF5 uses whatever is in the User ID field
  at that moment (Q-COUSR02C-02).
- Each turn ends with RETURN TRANSID(`CU02`) COMMAREA(CARDDEMO-COMMAREA) (194 bytes).
- Before XCTL it sets CDEMO-FROM-TRANID = `CU02`, CDEMO-FROM-PROGRAM = `COUSR02C`, CDEMO-PGM-CONTEXT = 0.

## Screen
BMS convention: `POS=(r,c)` is the attribute byte, so data starts in column c+1.

```text
             1         2         3         4         5         6         7         8
    12345678901234567890123456789012345678901234567890123456789012345678901234567890
01   Tran: CU02                AWS Mainframe Modernization           Date: mm/dd/yy
02   Prog: COUSR02C                    CardDemo                      Time: hh:mm:ss
03
04                                     Update User
05
06        Enter User ID: ________
07
08        **********************************************************************
09
10
11        First Name: ____________________       Last Name: ____________________
12
13        Password:          (8 Char)
14
15        User Type: _ (A=Admin, U=User)
16
17
18
19
20
21
22
23   <message line, 78 bytes>
24   ENTER=Fetch  F3=Save&Exit  F4=Clear  F5=Save  F12=Cancel
```

| Field (row,col of first data byte) | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|--:|:--|:--|:--|
| Tran (1,8) | TRNNAME | 4 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Transaction ID of the program (WS-TRANID) |
| Title 1 (1,22) | TITLE01 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE01 from COTTL01Y |
| Date (1,72) | CURDATE | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='mm/dd/yy' | Current date MM/DD/YY (POPULATE-HEADER-INFO) |
| Prog (2,8) | PGMNAME | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Program name (WS-PGMNAME) |
| Title 2 (2,22) | TITLE02 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE02 from COTTL01Y |
| Time (2,72) | CURTIME | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='hh:mm:ss' | Current time HH:MM:SS |
| Enter User ID (6,22) | USRIDIN | 8 | In/Out | (FSET,IC,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | User ID to fetch/update (key); initial cursor (IC) |
| First Name (11,19) | FNAME | 20 | In/Out | (FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | First name (editable) |
| Last Name (11,57) | LNAME | 20 | In/Out | (FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | Last name (editable) |
| Password (13,17) | PASSWD | 8 | In/Out | (DRK,FSET,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | Password (editable, DRK). Filled with the stored password on fetch (see Q-COUSR02C-03) |
| User Type (15,18) | USRTYPE | 1 | In/Out | (FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | User type (editable, not validated) |
| Message (23,2) | ERRMSG | 78 | Out | (ASKIP,BRT,FSET), COLOR=RED | Message line (WS-MESSAGE); default color red, overridden by ERRMSGC where the program sets it |

Unnamed literals: header labels (blue), `Update User` at row 4 (neutral, bright), `Enter User ID:` at row
6 (green), a row of 70 asterisks at row 8 (yellow), the labels `First Name:`, `Last Name:`, `Password:`,
`User Type: ` (turquoise), the hints `(8 Char)` and `(A=Admin, U=User)` (blue), and the key legend
`ENTER=Fetch  F3=Save&Exit  F4=Clear  F5=Save  F12=Cancel` at row 24 (yellow; `&&` in the BMS source
is an escaped `&`).

## Processing flow
1. MAIN-PARA (`app/cbl/COUSR02C.cbl:82-138`): error flag off, modified flag off; clear WS-MESSAGE and ERRMSGO.
2. IF EIBCALEN = 0: CDEMO-TO-PROGRAM = `COSGN00C`, PERFORM RETURN-TO-PREV-SCREEN.
3. OTHERWISE copy the COMMAREA in. IF context is not 1: set it to 1, clear the output map, cursor on User
   ID; IF CDEMO-CU02-USR-SELECTED is not spaces or LOW-VALUES, move it to the User ID field and PERFORM
   PROCESS-ENTER-KEY (fetch); then send the screen.
4. OTHERWISE RECEIVE the map and evaluate EIBAID:
   - ENTER -> PROCESS-ENTER-KEY (fetch).
   - PF3 -> PERFORM UPDATE-USER-INFO (save), then CDEMO-TO-PROGRAM = CDEMO-FROM-PROGRAM (or `COADM01C` if
     blank) and RETURN-TO-PREV-SCREEN. The XCTL happens whatever the save outcome was.
   - PF4 -> CLEAR-CURRENT-SCREEN.
   - PF5 -> UPDATE-USER-INFO (save).
   - PF12 -> CDEMO-TO-PROGRAM = `COADM01C`, RETURN-TO-PREV-SCREEN (no save).
   - Other -> error flag, CCDA-MSG-INVALID-KEY, send the screen.
5. RETURN TRANSID(CU02) with the COMMAREA.
6. PROCESS-ENTER-KEY (`app/cbl/COUSR02C.cbl:143-172`):
   1. IF User ID is spaces or LOW-VALUES: error "User ID can NOT be empty...", cursor User ID, send.
      OTHERWISE cursor User ID.
   2. IF no error: set First Name, Last Name, Password, User Type on the screen to spaces; READ USRSEC
      UPDATE by User ID (READ-USER-SEC-FILE). A NORMAL read sets the message
      "Press PF5 key to save your updates ..." in neutral color and sends the screen (before the fields are
      filled).
   3. IF still no error: move the stored first name, last name, password, and type into the screen fields
      and send the screen again (now with data and the "Press PF5 ..." message).
7. UPDATE-USER-INFO (`app/cbl/COUSR02C.cbl:177-245`):
   1. Check, in order, that User ID, First Name, Last Name, Password, and User Type are not blank; the first
      failure sets the error flag, its message and cursor, and sends the screen. IF all present, cursor on
      First Name.
   2. IF no error: READ USRSEC UPDATE by the User ID currently on the screen (on NORMAL this also sends the
      "Press PF5 key to save your updates ..." screen). The RESP of this READ is not checked before the next
      step. For each of First Name, Last Name, Password, User Type: IF the screen value differs from the
      record value, move the screen value into the record and set the modified flag.
   3. IF modified: REWRITE the record (UPDATE-USER-SEC-FILE). OTHERWISE message
      "Please modify to update ..." in red and send the screen.
8. UPDATE-USER-SEC-FILE (`app/cbl/COUSR02C.cbl:358-390`): REWRITE USRSEC FROM SEC-USER-DATA and evaluate RESP:
   NORMAL -> green message `User ` + SEC-USR-ID up to the first space + ` has been updated ...`, send (fields
   stay on the screen); NOTFND -> "User ID NOT found...", cursor User ID; other -> DISPLAY RESP/RESP2,
   "Unable to Update User...", cursor First Name.
9. CLEAR-CURRENT-SCREEN / INITIALIZE-ALL-FIELDS (`app/cbl/COUSR02C.cbl:395-411`): cursor User ID; User ID,
   First Name, Last Name, Password, User Type, and message set to spaces; send.

## Rules
### Business rules
- **BR-COUSR02C-01**: When entered with no COMMAREA, control is transferred to COSGN00C.
  Source: `app/cbl/COUSR02C.cbl:90-92 (MAIN-PARA)`, `app/cbl/COUSR02C.cbl:250-261 (RETURN-TO-PREV-SCREEN)`.
- **BR-COUSR02C-02**: On first entry with a preselected user (from COUSR00C), the record is fetched and shown
  at once; without one (from COADM01C), an empty screen is shown with the cursor on User ID.
  Source: `app/cbl/COUSR02C.cbl:95-105 (MAIN-PARA)`.
- **BR-COUSR02C-03**: Fetch (ENTER) reads USRSEC by the entered user ID (exact key, as typed, no upper-casing)
  and shows first name, last name, password (in the non-display field), and type, with the neutral-color
  message "Press PF5 key to save your updates ...". The fetch uses READ UPDATE, which holds an exclusive
  record lock until the task ends at the RETURN; nothing is rewritten.
  Source: `app/cbl/COUSR02C.cbl:157-172 (PROCESS-ENTER-KEY)`, `app/cbl/COUSR02C.cbl:320-339 (READ-USER-SEC-FILE)`.
- **BR-COUSR02C-04**: Save (PF5, and PF3) re-reads the record for update by the user ID currently on the
  screen and compares each editable field (first name, last name, password, type) byte for byte with the
  stored value. Fields that differ are replaced by the screen value. The user ID (key) and SEC-USR-FILLER
  are never changed. Source: `app/cbl/COUSR02C.cbl:215-234 (UPDATE-USER-INFO)`.
- **BR-COUSR02C-05**: When at least one field differs, the record is rewritten and the green message
  `User <id> has been updated ...` is shown (id cut at the first space), cursor on First Name; the edited
  values stay on the screen. Source: `app/cbl/COUSR02C.cbl:236-237`, `app/cbl/COUSR02C.cbl:360-376 (UPDATE-USER-SEC-FILE)`.
- **BR-COUSR02C-06**: New values are stored exactly as typed: no upper-casing of the password, no validation
  of the type value. Source: `app/cbl/COUSR02C.cbl:219-234 (UPDATE-USER-INFO)`.
- **BR-COUSR02C-07**: Header with `CU02` / `COUSR02C`, date MM/DD/YY, time HH:MM:SS.
  Source: `app/cbl/COUSR02C.cbl:296-315 (POPULATE-HEADER-INFO)`.

### Validation rules
- **VR-COUSR02C-01**: On fetch (ENTER), User ID must not be spaces or LOW-VALUES; otherwise
  "User ID can NOT be empty..." and cursor on User ID. Source: `app/cbl/COUSR02C.cbl:145-151 (PROCESS-ENTER-KEY)`.
- **VR-COUSR02C-02**: On save (PF5/PF3), User ID must not be blank; otherwise "User ID can NOT be empty...",
  cursor User ID. Source: `app/cbl/COUSR02C.cbl:180-185 (UPDATE-USER-INFO)`.
- **VR-COUSR02C-03**: On save, First Name must not be blank; otherwise "First Name can NOT be empty...",
  cursor First Name. Source: `app/cbl/COUSR02C.cbl:186-191 (UPDATE-USER-INFO)`.
- **VR-COUSR02C-04**: On save, Last Name must not be blank; otherwise "Last Name can NOT be empty...", cursor
  Last Name. Source: `app/cbl/COUSR02C.cbl:192-197 (UPDATE-USER-INFO)`.
- **VR-COUSR02C-05**: On save, Password must not be blank; otherwise "Password can NOT be empty...", cursor
  Password. Source: `app/cbl/COUSR02C.cbl:198-203 (UPDATE-USER-INFO)`.
- **VR-COUSR02C-06**: On save, User Type must not be blank; otherwise "User Type can NOT be empty...", cursor
  User Type. Any non-blank character is accepted. Source: `app/cbl/COUSR02C.cbl:204-209 (UPDATE-USER-INFO)`.
- **VR-COUSR02C-07**: On save, at least one field must differ from the stored record; otherwise the red message
  "Please modify to update ..." and no write. Source: `app/cbl/COUSR02C.cbl:238-243 (UPDATE-USER-INFO)`.

### Navigation and keys
- **NAV-COUSR02C-01**: ENTER -> fetch the user in the User ID field (any edits on the screen are replaced by
  the stored values). Source: `app/cbl/COUSR02C.cbl:109-110 (MAIN-PARA)`.
- **NAV-COUSR02C-02**: PF3 -> save (as PF5), then XCTL to CDEMO-FROM-PROGRAM (`COUSR00C` when opened from the
  list, `COADM01C` when opened from the admin menu, `COADM01C` if blank). Validation or save messages from this
  turn are never seen because the XCTL follows unconditionally.
  Source: `app/cbl/COUSR02C.cbl:111-119 (MAIN-PARA)`.
- **NAV-COUSR02C-03**: PF4 -> clear User ID, all fields, and the message; cursor User ID.
  Source: `app/cbl/COUSR02C.cbl:120-121`, `app/cbl/COUSR02C.cbl:395-411`.
- **NAV-COUSR02C-04**: PF5 -> save and stay on the screen. Source: `app/cbl/COUSR02C.cbl:122-123 (MAIN-PARA)`.
- **NAV-COUSR02C-05**: PF12 -> XCTL to COADM01C without saving (even when opened from the list).
  Source: `app/cbl/COUSR02C.cbl:124-126 (MAIN-PARA)`.
- **NAV-COUSR02C-06**: Any other AID -> "Invalid key pressed. Please see below...         " and the screen is
  re-sent with the typed data (no cursor field is flagged). Source: `app/cbl/COUSR02C.cbl:127-130 (MAIN-PARA)`.
- **NAV-COUSR02C-07**: Every redisplay ends with RETURN TRANSID(`CU02`) COMMAREA(CARDDEMO-COMMAREA).
  Source: `app/cbl/COUSR02C.cbl:135-138 (MAIN-PARA)`.

### Error handling
- **ERR-COUSR02C-01**: READ NOTFND -> "User ID NOT found...", cursor User ID. On fetch the name, password, and
  type fields are left blank. Source: `app/cbl/COUSR02C.cbl:340-345 (READ-USER-SEC-FILE)`.
- **ERR-COUSR02C-02**: READ other error -> `DISPLAY 'RESP:' ... 'REAS:' ...`, "Unable to lookup User...",
  cursor First Name. Source: `app/cbl/COUSR02C.cbl:346-352 (READ-USER-SEC-FILE)`.
- **ERR-COUSR02C-03**: During save, a failed READ is not checked: the comparison runs against a record area
  that holds only the key (other fields are leftover working storage), at least one field normally differs,
  and REWRITE is issued without a successful READ UPDATE. CICS rejects it (INVREQ), giving the final
  message "Unable to Update User..." instead of "User ID NOT found...".
  Source: `app/cbl/COUSR02C.cbl:215-237 (UPDATE-USER-INFO)`, `app/cbl/COUSR02C.cbl:383-389`.
- **ERR-COUSR02C-04**: REWRITE NOTFND -> "User ID NOT found...", cursor User ID.
  Source: `app/cbl/COUSR02C.cbl:377-382 (UPDATE-USER-SEC-FILE)`.
- **ERR-COUSR02C-05**: REWRITE other error -> DISPLAY RESP/RESP2, "Unable to Update User...", cursor First Name.
  Source: `app/cbl/COUSR02C.cbl:383-389 (UPDATE-USER-SEC-FILE)`.
- **ERR-COUSR02C-06**: RECEIVE MAP RESP is captured but not tested.
  Source: `app/cbl/COUSR02C.cbl:283-291 (RECEIVE-USRUPD-SCREEN)`.

## Data effects
- Fetch (ENTER or preselected first entry): no change; READ UPDATE takes an exclusive lock on the record
  that is released when the task ends (RETURN).
- Save (PF5 or PF3) with changes: READ UPDATE then REWRITE of the same 80-byte record in one task.
  SEC-USR-ID unchanged; SEC-USR-FNAME, SEC-USR-LNAME, SEC-USR-PWD (plain text), SEC-USR-TYPE set to the
  screen values; SEC-USR-FILLER preserved from the read. There is no optimistic-concurrency check between
  the fetch and the save: the screen values overwrite whatever another administrator stored in between.
- USRSEC is `RECOVERY(NONE)` and `JOURNAL(NO)` (`app/csd/CARDDEMO.CSD:88-96`): no backout, no journal, no
  audit of who changed what.

## Quirks
- **Q-COUSR02C-01**: PF3 is "Save & Exit": it runs the full save and then exits unconditionally, so a
  validation error, "Please modify to update ...", or a failed REWRITE is never shown, and unintended edits
  are saved without confirmation. Recommendation: decide (the target should save explicitly and report errors
  before navigating). Source: `app/cbl/COUSR02C.cbl:111-119`.
- **Q-COUSR02C-02**: The key is not pinned. The User ID field stays editable after a fetch, and PF5 updates the
  record whose ID is on the screen at that moment, using the other values on the screen. Fetching user A,
  changing the ID to B, and pressing PF5 overwrites B's names, password, and type with A's values.
  Recommendation: fix (keep the fetched key in server-side state; make the ID read-only after fetch).
  Source: `app/cbl/COUSR02C.cbl:215-234`.
- **Q-COUSR02C-03**: The stored plain-text password is sent to the terminal on fetch (hidden only by the DRK
  attribute) and can be revealed by any 3270 emulator or trace. Recommendation: fix (never return passwords;
  provide a separate "set new password" action). Source: `app/cbl/COUSR02C.cbl:169`, `app/bms/COUSR02.bms:130-134`.
- **Q-COUSR02C-04**: No validation of the user type and no upper-casing; a lower-case password or a type other
  than `A`/`U` can be stored, with the sign-on effects described in Q-COSGN00C-02 and Q-COSGN00C-07.
  An admin can also demote or change the password of the signed-on user or of the last admin.
  Recommendation: fix (enumerated type, role-change guard). Source: `app/cbl/COUSR02C.cbl:204-234`.
- **Q-COUSR02C-05**: The fetch uses READ UPDATE although it never rewrites, taking a needless exclusive lock;
  USRSEC has `STRINGS(1)`, so concurrent USRSEC requests queue. Recommendation: do not port (plain read on fetch,
  optimistic version check on save). Source: `app/cbl/COUSR02C.cbl:322-331`, `app/csd/CARDDEMO.CSD:91`.
- **Q-COUSR02C-06**: The READ result is not checked during save (ERR-COUSR02C-03), so a missing user produces
  "Unable to Update User..." rather than "User ID NOT found...". Recommendation: fix.
  Source: `app/cbl/COUSR02C.cbl:215-218`.
- **Q-COUSR02C-07**: Several SENDs per turn: a fetch sends the screen once from READ-USER-SEC-FILE (message
  only) and again after filling the fields; first entry from the list sends three times; a save sends the
  "Press PF5 ..." screen before the final result. Recommendation: do not port (render once).
  Source: `app/cbl/COUSR02C.cbl:103-105`, `app/cbl/COUSR02C.cbl:171`, `app/cbl/COUSR02C.cbl:339`.
- **Q-COUSR02C-08**: Change detection is an exact comparison of fixed-length fields, so changing only letter
  case or trailing content counts as a change; retyping identical values gives "Please modify to update ...".
  Recommendation: preserve the "no change, no write" rule. Source: `app/cbl/COUSR02C.cbl:219-243`.
- **Q-COUSR02C-09**: No authorization check of CDEMO-USER-TYPE (see Q-COADM01C-01). Recommendation: fix.
  Source: `app/cbl/COUSR02C.cbl:82-138`.
- **Q-COUSR02C-10**: Edits typed before ENTER are discarded: ENTER always re-fetches and overwrites the screen.
  Recommendation: preserve (ENTER = Fetch is advertised). Source: `app/cbl/COUSR02C.cbl:157-172`.

## Test scenarios
- **TS-COUSR02C-01** (covers BR-COUSR02C-02, BR-COUSR02C-03): Given the sample USRSEC and a selection of `USER0001`
  in COUSR00C, when COUSR02C starts, then the User ID field shows `USER0001`, First Name, Last Name, and Type
  `U` are filled from the record, the hidden Password field holds the stored password, and the message is
  "Press PF5 key to save your updates ...".
- **TS-COUSR02C-02** (covers BR-COUSR02C-02): Given COADM01C option 3, when COUSR02C starts, then all fields are
  empty and the cursor is on User ID.
- **TS-COUSR02C-03** (covers VR-COUSR02C-01): Given the empty screen, when ENTER is pressed, then the message is
  "User ID can NOT be empty...".
- **TS-COUSR02C-04** (covers ERR-COUSR02C-01): Given the sample USRSEC, when `NOSUCHUS` is entered and ENTER is
  pressed, then the message is "User ID NOT found..." and the other fields are blank.
- **TS-COUSR02C-05** (covers BR-COUSR02C-04, BR-COUSR02C-05): Given `USER0002` has been fetched, when Last Name is changed
  to `NEWNAME` and PF5 is pressed, then the USRSEC record `USER0002` has SEC-USR-LNAME `NEWNAME` (padded to
  20) and all other fields unchanged, and the green message "User USER0002 has been updated ..." is shown.
- **TS-COUSR02C-06** (covers VR-COUSR02C-07): Given `USER0002` has been fetched, when PF5 is pressed without changes,
  then the red message "Please modify to update ..." is shown and the record is not rewritten.
- **TS-COUSR02C-07** (covers VR-COUSR02C-03): Given a fetched user, when First Name is blanked and PF5 is pressed,
  then the message is "First Name can NOT be empty..." and nothing is written.
- **TS-COUSR02C-08** (covers VR-COUSR02C-05, VR-COUSR02C-06): Given a fetched user, when Password is blanked (or User Type
  is blanked) and PF5 is pressed, then "Password can NOT be empty..." (or "User Type can NOT be empty...").
- **TS-COUSR02C-09** (covers NAV-COUSR02C-02, Q-COUSR02C-01): Given `USER0003` was opened from the user list and its
  First Name changed, when PF3 is pressed, then the record is updated and control passes to COUSR00C with no
  confirmation message visible.
- **TS-COUSR02C-10** (covers NAV-COUSR02C-02, Q-COUSR02C-01): Given a fetched user with First Name blanked, when PF3 is pressed,
  then nothing is written and control still passes to the caller.
- **TS-COUSR02C-11** (covers NAV-COUSR02C-05): Given a fetched user with edits, when PF12 is pressed, then nothing is
  written and control passes to COADM01C (also when opened from the list).
- **TS-COUSR02C-12** (covers Q-COUSR02C-02): Given `USER0001` has been fetched, when the User ID field is changed to
  `USER0002` and PF5 is pressed, then record `USER0002` receives the first name, last name, password, and type
  displayed for `USER0001`, and the message is "User USER0002 has been updated ...".
- **TS-COUSR02C-13** (covers ERR-COUSR02C-03): Given the screen with User ID `NOSUCHUS` and all other fields
  filled, when PF5 is pressed, then the final message is "Unable to Update User..." and nothing is written.
- **TS-COUSR02C-14** (covers NAV-COUSR02C-03): Given a fetched user, when PF4 is pressed, then User ID and all fields
  are cleared.
- **TS-COUSR02C-15** (covers NAV-COUSR02C-06): Given any screen state, when PF7 is pressed, then the message is
  "Invalid key pressed. Please see below..." and the typed data is kept.
- **TS-COUSR02C-16** (covers BR-COUSR02C-06, Q-COUSR02C-04): Given `USER0004` fetched, when the type is changed to `A`
  and PF5 is pressed, then the record type becomes `A`, and the next sign-on of `USER0004` routes to COADM01C.

## Open questions
- Should an administrator be allowed to change their own type or the type of the last remaining admin?
- Should PF3 keep "save and exit" semantics in the target, or become "exit without saving" with a prompt?
