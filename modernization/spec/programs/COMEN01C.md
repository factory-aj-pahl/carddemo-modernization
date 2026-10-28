# COMEN01C: Main menu for regular users; table-driven dispatch to the account, card, transaction, report, bill payment, and authorization functions

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COMEN01C.cbl` (308 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CM00 (`app/csd/CARDDEMO.CSD:399-405`, program definition `app/csd/CARDDEMO.CSD:235-241`) |
| BMS map | mapset `COMEN01` / map `COMEN1A` (`app/bms/COMEN01.bms`, symbolic map `app/cpy-bms/COMEN01.CPY`) |
| Copybooks | `COCOM01Y` (CARDDEMO-COMMAREA), `COMEN02Y` (main menu option table), `COMEN01` (symbolic map), `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y` (copied, not used), `DFHAID`, `DFHBMSCA` |
| Calls / XCTL targets | XCTL `COSGN00C` (sign-off / no COMMAREA); XCTL to the option program from `COMEN02Y`: `COACTVWC`, `COACTUPC`, `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `COTRN00C`, `COTRN01C`, `COTRN02C`, `CORPT00C`, `COBIL00C`, `COPAUS0C`; EXEC CICS INQUIRE PROGRAM (for `COPAUS0C`) |
| Data stores | None (WS-USRSEC-FILE is declared but never used) |

## Purpose
COMEN01C shows the CardDemo main menu to regular users after sign-on. It lists the eleven functions
defined in the option table `COMEN02Y`, accepts an option number, checks it, and transfers control
to the selected program. PF3 signs the user off by returning to the sign-on program. The optional
Pending Authorization function is offered only if its program is installed in the CICS region.

## Invocation and state
- Entered by XCTL from COSGN00C after a successful sign-on of a non-admin user
  (CDEMO-PGM-CONTEXT = 0), and by XCTL from each function program when the user presses its exit key
  (COTRN00C, COTRN01C, COTRN02C, CORPT00C, COBIL00C, COACTVWC, COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC,
  COPAUS0C; these move `COMEN01C` to CDEMO-TO-PROGRAM and set context 0).
- If started with no COMMAREA (EIBCALEN = 0, for example CM00 typed on a clear screen), it XCTLs to
  COSGN00C without a COMMAREA.
- With a COMMAREA, it copies DFHCOMMAREA(1:EIBCALEN) into CARDDEMO-COMMAREA (160 bytes, `COCOM01Y`).
  CDEMO-PGM-CONTEXT distinguishes the first display (0 = CDEMO-PGM-ENTER) from a reply to the menu
  (1 = CDEMO-PGM-REENTER). The program sets it to 1 before its first send and to 0 before any XCTL
  to an option program.
- Fields read: CDEMO-PGM-CONTEXT, CDEMO-USER-TYPE (for the admin-only check), CDEMO-TO-PROGRAM (in
  RETURN-TO-SIGNON-SCREEN). Fields written before XCTL to an option: CDEMO-FROM-TRANID = `CM00`,
  CDEMO-FROM-PROGRAM = `COMEN01C`, CDEMO-PGM-CONTEXT = 0. CDEMO-USER-ID and CDEMO-USER-TYPE are passed
  through unchanged.
- Each turn ends with RETURN TRANSID(`CM00`) COMMAREA(CARDDEMO-COMMAREA) (length 160).

### Menu option table (`app/cpy/COMEN02Y.cpy:19-98`)
CDEMO-MENU-OPT-COUNT = 11. Each entry: CDEMO-MENU-OPT-NUM 9(02), CDEMO-MENU-OPT-NAME X(35),
CDEMO-MENU-OPT-PGMNAME X(08), CDEMO-MENU-OPT-USRTYPE X(01). The REDEFINES declares OCCURS 12.

| No. | Label (CDEMO-MENU-OPT-NAME) | Target program | Target transaction (target program's own TRANSID literal) | User type | Source lines |
|--:|:--|:--|:--|:--|:--|
| 1 | Account View | COACTVWC | CAVW | U | `app/cpy/COMEN02Y.cpy:25-29` |
| 2 | Account Update | COACTUPC | CAUP | U | `app/cpy/COMEN02Y.cpy:31-35` |
| 3 | Credit Card List | COCRDLIC | CCLI | U | `app/cpy/COMEN02Y.cpy:37-41` |
| 4 | Credit Card View | COCRDSLC | CCDL | U | `app/cpy/COMEN02Y.cpy:43-47` |
| 5 | Credit Card Update | COCRDUPC | CCUP | U | `app/cpy/COMEN02Y.cpy:49-53` |
| 6 | Transaction List | COTRN00C | CT00 | U | `app/cpy/COMEN02Y.cpy:55-59` |
| 7 | Transaction View | COTRN01C | CT01 | U | `app/cpy/COMEN02Y.cpy:61-65` |
| 8 | Transaction Add | COTRN02C | CT02 | U (a commented-out label reads `Transaction Add (Admin Only)`) | `app/cpy/COMEN02Y.cpy:67-72` |
| 9 | Transaction Reports | CORPT00C | CR00 | U | `app/cpy/COMEN02Y.cpy:74-78` |
| 10 | Bill Payment | COBIL00C | CB00 | U | `app/cpy/COMEN02Y.cpy:80-84` |
| 11 | Pending Authorization View | COPAUS0C (optional module `app/app-authorization-ims-db2-mq`) | CPVS | U | `app/cpy/COMEN02Y.cpy:86-90` |

The target transaction column is informational (WS-TRANID, LIT-THISTRANID, or WS-CICS-TRANID in the
target program); the menu uses XCTL to the program, not the transaction. No entry has user type `A`, so the admin-only
rule (BR-COMEN01C-05) never fires with the shipped table.

## Screen
BMS convention: `POS=(r,c)` is the attribute byte, so data starts in column c+1. The mock-up shows the
options exactly as BUILD-MENU-OPTIONS formats them: the option number is PIC 9(02), so it is shown
with a leading zero.

```text
             1         2         3         4         5         6         7         8
    12345678901234567890123456789012345678901234567890123456789012345678901234567890
01   Tran: CM00                AWS Mainframe Modernization           Date: mm/dd/yy
02   Prog: COMEN01C                    CardDemo                      Time: hh:mm:ss
03
04                                     Main Menu
05
06                      01. Account View
07                      02. Account Update
08                      03. Credit Card List
09                      04. Credit Card View
10                      05. Credit Card Update
11                      06. Transaction List
12                      07. Transaction View
13                      08. Transaction Add
14                      09. Transaction Reports
15                      10. Bill Payment
16                      11. Pending Authorization View
17
18
19
20                 Please select an option : __
21
22
23   <message line, 78 bytes>
24   ENTER=Continue  F3=Exit
```

| Field (row,col of first data byte) | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|--:|:--|:--|:--|
| Tran (1,8) | TRNNAME | 4 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Transaction ID of the program (WS-TRANID) |
| Title 1 (1,22) | TITLE01 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE01 from COTTL01Y |
| Date (1,72) | CURDATE | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='mm/dd/yy' | Current date MM/DD/YY (POPULATE-HEADER-INFO) |
| Prog (2,8) | PGMNAME | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Program name (WS-PGMNAME) |
| Title 2 (2,22) | TITLE02 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE02 from COTTL01Y |
| Time (2,72) | CURTIME | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='hh:mm:ss' | Current time HH:MM:SS |
| OPTN001 (6,21) | OPTN001 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 1: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN002 (7,21) | OPTN002 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 2: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN003 (8,21) | OPTN003 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 3: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN004 (9,21) | OPTN004 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 4: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN005 (10,21) | OPTN005 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 5: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN006 (11,21) | OPTN006 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 6: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN007 (12,21) | OPTN007 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 7: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN008 (13,21) | OPTN008 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 8: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN009 (14,21) | OPTN009 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 9: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN010 (15,21) | OPTN010 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 10: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN011 (16,21) | OPTN011 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 11: '<nn>. <name>' built by BUILD-MENU-OPTIONS |
| OPTN012 (17,21) | OPTN012 | 40 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL=' ' | Option line 12: '<nn>. <name>' built by BUILD-MENU-OPTIONS; always blank (table has fewer entries) |
| Option (20,42) | OPTION | 2 | In/Out | (FSET,IC,NORM,NUM,UNPROT), HILIGHT=UNDERLINE, JUSTIFY=(RIGHT,ZERO) | Option number entry; NUM; right-justified, zero-filled on input; initial cursor (IC); echoed back as WS-OPTION |
| Message (23,2) | ERRMSG | 78 | Out | (ASKIP,BRT,FSET), COLOR=RED | Message line (WS-MESSAGE); default color red, overridden by ERRMSGC where the program sets it |

Unnamed literals: `Tran:`, `Prog:`, `Date:`, `Time:` (blue), `Main Menu` at row 4 (neutral, bright),
`Please select an option :` at row 20 (turquoise, bright), and the key legend
`ENTER=Continue  F3=Exit` at row 24 (yellow). OPTN012 (row 17) is always blank.

## Processing flow
1. MAIN-PARA (`app/cbl/COMEN01C.cbl:75-110`): clear the error flag, WS-MESSAGE, and ERRMSGO.
2. IF EIBCALEN = 0: move `COSGN00C` to CDEMO-FROM-PROGRAM and PERFORM RETURN-TO-SIGNON-SCREEN (XCTL to
   CDEMO-TO-PROGRAM, defaulted to `COSGN00C` when blank or LOW-VALUES, without a COMMAREA).
3. OTHERWISE copy the COMMAREA in. IF CDEMO-PGM-CONTEXT is not 1: set it to 1, set the output map to
   LOW-VALUES, and PERFORM SEND-MENU-SCREEN.
4. OTHERWISE RECEIVE the map and evaluate EIBAID: ENTER -> PROCESS-ENTER-KEY; PF3 -> set
   CDEMO-TO-PROGRAM to `COSGN00C` and PERFORM RETURN-TO-SIGNON-SCREEN; any other key -> message
   CCDA-MSG-INVALID-KEY and PERFORM SEND-MENU-SCREEN.
5. RETURN TRANSID(CM00) with the COMMAREA.
6. PROCESS-ENTER-KEY (`app/cbl/COMEN01C.cbl:115-191`):
   1. Scan the 2-byte OPTIONI from position 2 down to position 1 for the last character that is not a
      space (stop at position 1). Move OPTIONI(1:that position) to WS-OPTION-X (X(02) JUSTIFIED RIGHT),
      replace every space with `0`, move it to the numeric WS-OPTION, and move WS-OPTION back to the
      OPTION output field.
   2. IF WS-OPTION is not numeric, is greater than CDEMO-MENU-OPT-COUNT (11), or is zero: error
      "Please enter a valid option number..." and send the menu.
   3. IF the user is type `U` and the option's user type is `A`: error
      "No access - Admin Only option... " and send the menu. (This test is not skipped when step 2 failed.)
   4. IF no error, evaluate the option's program name:
      - `COPAUS0C`: INQUIRE PROGRAM(COPAUS0C) NOHANDLE. IF EIBRESP is NORMAL, set FROM-TRANID,
        FROM-PROGRAM, context 0 and XCTL to COPAUS0C with the COMMAREA. OTHERWISE set the message color to
        red and build the message `This option ` + option name up to the first double space +
        ` is not installed...`.
      - A name starting with `DUMMY`: set the message color to green and build
        `This option ` + option name up to the first single space + `is coming soon ...`.
      - Any other name: set FROM-TRANID = `CM00`, FROM-PROGRAM = `COMEN01C`, context 0, and XCTL to the
        program with the COMMAREA.
      - Then (reached only when no XCTL happened) PERFORM SEND-MENU-SCREEN.
7. SEND-MENU-SCREEN (`app/cbl/COMEN01C.cbl:208-220`): POPULATE-HEADER-INFO, BUILD-MENU-OPTIONS, move
   WS-MESSAGE to ERRMSG, SEND MAP with ERASE (no CURSOR option; the map's IC places the cursor on OPTION).
8. BUILD-MENU-OPTIONS (`app/cbl/COMEN01C.cbl:262-303`): FOR EACH index 1 to CDEMO-MENU-OPT-COUNT, build
   a 40-byte text = 2-digit number + `. ` + the 35-byte name, and move it to OPTN001-OPTN012 by index.

## Rules
### Business rules
- **BR-COMEN01C-01**: When entered with no COMMAREA (EIBCALEN = 0), the program transfers control to
  COSGN00C without a COMMAREA, which shows a clean sign-on screen.
  Source: `app/cbl/COMEN01C.cbl:82-84 (MAIN-PARA)`, `app/cbl/COMEN01C.cbl:196-203 (RETURN-TO-SIGNON-SCREEN)`.
- **BR-COMEN01C-02**: When entered with a COMMAREA whose CDEMO-PGM-CONTEXT is not 1 (first entry from
  sign-on or return from a function), the menu is displayed with an empty option field and no message,
  and CDEMO-PGM-CONTEXT is set to 1. Source: `app/cbl/COMEN01C.cbl:86-90 (MAIN-PARA)`.
- **BR-COMEN01C-03**: The menu lists the options of `COMEN02Y` in table order, one per line on rows 6-16,
  formatted `nn. <name>` with a two-digit zero-padded number.
  Source: `app/cbl/COMEN01C.cbl:262-303 (BUILD-MENU-OPTIONS)`, `app/cpy/COMEN02Y.cpy:21-98`.
- **BR-COMEN01C-04**: The option entry is normalized: trailing spaces are ignored, the digits are
  right-justified, and leading spaces become zeros, so `3`, ` 3`, `3 `, and `03` all select option 3. The
  normalized two-digit value is echoed in the option field when the menu is redisplayed.
  Source: `app/cbl/COMEN01C.cbl:117-125 (PROCESS-ENTER-KEY)`, `app/bms/COMEN01.bms:145-149` (NUM, JUSTIFY=(RIGHT,ZERO)).
- **BR-COMEN01C-05**: When the signed-on user is type `U` and the selected option's user type is `A`, the
  option is refused with "No access - Admin Only option... " (note the trailing space). No shipped option
  has type `A`. Source: `app/cbl/COMEN01C.cbl:136-143 (PROCESS-ENTER-KEY)`.
- **BR-COMEN01C-06**: For option 11 (program `COPAUS0C`), the program first asks CICS whether the
  program is defined (INQUIRE PROGRAM NOHANDLE). If it is, control is transferred to it; if not, the menu
  is redisplayed with the red message "This option Pending Authorization View is not installed...".
  Source: `app/cbl/COMEN01C.cbl:147-168 (PROCESS-ENTER-KEY)`.
- **BR-COMEN01C-07**: For an option whose program name starts with `DUMMY` (none in the shipped table),
  the menu is redisplayed with a green message `This option <first word of name>is coming soon ...`.
  Source: `app/cbl/COMEN01C.cbl:169-176 (PROCESS-ENTER-KEY)`.
- **BR-COMEN01C-08**: For every other valid option, CDEMO-FROM-TRANID = `CM00`, CDEMO-FROM-PROGRAM =
  `COMEN01C`, CDEMO-PGM-CONTEXT = 0 are set and control is transferred (XCTL) to the option's program with
  the 160-byte COMMAREA. User ID and user type are passed on unchanged.
  Source: `app/cbl/COMEN01C.cbl:177-187 (PROCESS-ENTER-KEY)`.
- **BR-COMEN01C-09**: Every send fills the header: titles from COTTL01Y, `CM00`, `COMEN01C`, date
  MM/DD/YY, time HH:MM:SS (no APPLID/SYSID on this map).
  Source: `app/cbl/COMEN01C.cbl:238-257 (POPULATE-HEADER-INFO)`.

### Validation rules
- **VR-COMEN01C-01**: The option must be numeric, between 1 and CDEMO-MENU-OPT-COUNT (11) inclusive;
  otherwise message "Please enter a valid option number..." and the menu is redisplayed (cursor on the
  option field via IC). Blank, `0`, `00`, `12`-`99`, and non-numeric input all fail.
  Source: `app/cbl/COMEN01C.cbl:127-134 (PROCESS-ENTER-KEY)`.
- **VR-COMEN01C-02**: See BR-COMEN01C-05 (user-type restriction message "No access - Admin Only option... ").
  Source: `app/cbl/COMEN01C.cbl:136-143 (PROCESS-ENTER-KEY)`.

### Navigation and keys
- **NAV-COMEN01C-01**: ENTER -> PROCESS-ENTER-KEY (option dispatch).
  Source: `app/cbl/COMEN01C.cbl:94-95 (MAIN-PARA)`.
- **NAV-COMEN01C-02**: PF3 -> XCTL to COSGN00C without a COMMAREA (sign-off). The sign-on program then
  shows a clean sign-on screen; no thank-you message is shown.
  Source: `app/cbl/COMEN01C.cbl:96-98 (MAIN-PARA)`, `app/cbl/COMEN01C.cbl:196-203 (RETURN-TO-SIGNON-SCREEN)`.
- **NAV-COMEN01C-03**: Any other AID -> message "Invalid key pressed. Please see below...         " and
  the menu is redisplayed (option field is cleared by the redisplay because BUILD writes only option
  lines; the received option value remains in the shared map buffer and is re-sent).
  Source: `app/cbl/COMEN01C.cbl:99-102 (MAIN-PARA)`.
- **NAV-COMEN01C-04**: Valid option -> XCTL to the program listed in the option table (see table above),
  with context 0 so the target treats it as a first entry.
  Source: `app/cbl/COMEN01C.cbl:156-159`, `app/cbl/COMEN01C.cbl:184-187 (PROCESS-ENTER-KEY)`.
- **NAV-COMEN01C-05**: Every redisplay ends with RETURN TRANSID(`CM00`) COMMAREA(CARDDEMO-COMMAREA).
  Source: `app/cbl/COMEN01C.cbl:107-110 (MAIN-PARA)`.

### Error handling
- **ERR-COMEN01C-01**: INQUIRE PROGRAM for COPAUS0C returns a non-NORMAL EIBRESP (PGMIDERR, not
  defined) -> red message "This option Pending Authorization View is not installed..." and the menu is
  redisplayed. Source: `app/cbl/COMEN01C.cbl:148-168 (PROCESS-ENTER-KEY)`.
- **ERR-COMEN01C-02**: XCTL to any option program has no RESP or HANDLE CONDITION. If the program is not
  defined or cannot be loaded, the task abends (PGMIDERR, abend AEI0). This also applies to COPAUS0C when
  it is defined but its load module is missing. Source: `app/cbl/COMEN01C.cbl:156-159`, `app/cbl/COMEN01C.cbl:184-187`.
- **ERR-COMEN01C-03**: The RECEIVE MAP RESP is captured but not tested.
  Source: `app/cbl/COMEN01C.cbl:225-233 (RECEIVE-MENU-SCREEN)`.

## Data effects
None. No file is read or written. Only the COMMAREA fields listed above change.

## Quirks
- **Q-COMEN01C-01**: The admin-only check is effectively dead. No option in `COMEN02Y` has type `A`, and
  the check tests CDEMO-USRTYP-USER (`U`) explicitly, so a user with any other type (possible because
  COUSR01C/COUSR02C do not validate the type) would also pass it. Authorization is enforced only by
  which menu the sign-on program chooses. Recommendation: fix. In the target, every function endpoint
  checks the authenticated role server-side; keep a role column in the menu definition.
  Source: `app/cbl/COMEN01C.cbl:136-143`, `app/cpy/COMEN02Y.cpy:29-90`.
- **Q-COMEN01C-02**: The table REDEFINES declares `OCCURS 12` over data for 11 entries, so entry 12
  overlaps storage after the table. The loop runs only to CDEMO-MENU-OPT-COUNT (11), so OPTN012 is
  always blank. The copybook header comment says "Admin Menu Options" although it is the main menu.
  Recommendation: fix (size the table from the data). Source: `app/cpy/COMEN02Y.cpy:2`, `app/cpy/COMEN02Y.cpy:93-98`.
- **Q-COMEN01C-03**: The checks do not short-circuit. When the option is invalid (0, non-numeric), the
  admin-only test in step 6.3 still subscripts the table with that value (index 0 reads the byte before
  the table; a non-numeric value is unpredictable). Without SSRANGE this does not abend, and with the
  shipped data it does not change the message. Recommendation: fix (validate, then stop).
  Source: `app/cbl/COMEN01C.cbl:127-143`.
- **Q-COMEN01C-04**: The `DUMMY` branch builds its message with the name cut at the first space and no
  space before `is coming soon ...` (for example `This option Accountis coming soon ...`). The branch is
  unreachable with the shipped table. Recommendation: do not port; use a "coming soon" flag if needed.
  Source: `app/cbl/COMEN01C.cbl:169-176`.
- **Q-COMEN01C-05**: Only COPAUS0C is protected by an availability check. A missing program for any other
  option abends the task, whereas COADM01C handles PGMIDERR with a message.
  Recommendation: fix (uniform "not installed" handling, feature flags for optional modules).
  Source: `app/cbl/COMEN01C.cbl:147-187`.
- **Q-COMEN01C-06**: Admin users can never reach this menu: COSGN00C sends type `A` users to COADM01C,
  and the admin menu has no link to the main menu. So admins cannot use account, card, transaction,
  report, or bill-payment functions. Recommendation: decide (keep role separation or give admins both menus).
  Source: `app/cbl/COSGN00C.cbl:230-240`, `app/cpy/COADM02Y.cpy:19-59`.
- **Q-COMEN01C-07**: Line 179 and line 180 both move WS-PGMNAME to CDEMO-FROM-PROGRAM, and the moves of
  WS-USER-ID / SEC-USR-TYPE are commented out; `CSUSR01Y` and WS-USRSEC-FILE are unused. No functional
  effect. Recommendation: ignore. Source: `app/cbl/COMEN01C.cbl:178-183`.
- **Q-COMEN01C-08**: The "not installed" message is built with `DELIMITED BY '  '` on the 35-byte name, so a
  name containing a double space would be cut there. Recommendation: preserve the visible text
  "This option Pending Authorization View is not installed...". Source: `app/cbl/COMEN01C.cbl:163-167`.

## Test scenarios
- **TS-COMEN01C-01** (covers BR-COMEN01C-02, BR-COMEN01C-03, BR-COMEN01C-09): Given a COMMAREA from COSGN00C for
  `USER0001` (type `U`, context 0), when COMEN01C runs, then the Main Menu shows 11 lines
  `01. Account View` ... `11. Pending Authorization View` on rows 6-16, row 17 is blank, the message line
  is empty, Tran is `CM00`, and the returned COMMAREA has CDEMO-PGM-CONTEXT = 1.
- **TS-COMEN01C-02** (covers BR-COMEN01C-08, NAV-COMEN01C-04): Given the menu is displayed, when `6` is
  entered with ENTER, then control passes to COTRN00C with CDEMO-FROM-PROGRAM = `COMEN01C`,
  CDEMO-FROM-TRANID = `CM00`, CDEMO-PGM-CONTEXT = 0, and CDEMO-USER-ID = `USER0001`.
- **TS-COMEN01C-03** (covers BR-COMEN01C-04): Given the menu, when the option is entered as `3`, ` 3`, or
  `03`, then control passes to COCRDLIC in each case.
- **TS-COMEN01C-04** (covers VR-COMEN01C-01): Given the menu, when `12` is entered, then the message is
  "Please enter a valid option number..." and the option field shows `12`.
- **TS-COMEN01C-05** (covers VR-COMEN01C-01): Given the menu, when ENTER is pressed with the option blank,
  or with `0`, then the message is "Please enter a valid option number...".
- **TS-COMEN01C-06** (covers BR-COMEN01C-06, ERR-COMEN01C-01): Given COPAUS0C is not defined in the region,
  when `11` is entered, then the menu is redisplayed with the red message
  "This option Pending Authorization View is not installed...".
- **TS-COMEN01C-07** (covers BR-COMEN01C-06): Given COPAUS0C is installed, when `11` is entered, then control
  passes to COPAUS0C with context 0.
- **TS-COMEN01C-08** (covers NAV-COMEN01C-02): Given the menu, when PF3 is pressed, then control passes to
  COSGN00C with no COMMAREA and the clean sign-on screen is displayed.
- **TS-COMEN01C-09** (covers NAV-COMEN01C-03): Given the menu, when PF7 is pressed, then the menu is
  redisplayed with "Invalid key pressed. Please see below...".
- **TS-COMEN01C-10** (covers BR-COMEN01C-01): Given no COMMAREA, when transaction CM00 is started, then
  control passes to COSGN00C without a COMMAREA.
- **TS-COMEN01C-11** (covers BR-COMEN01C-05, Q-COMEN01C-01): Given a test copy of the option table in which
  option 8 has user type `A`, when a type `U` user selects `8`, then the message is
  "No access - Admin Only option... "; and when a type `X` user selects `8`, then control passes to COTRN02C.
- **TS-COMEN01C-12** (covers BR-COMEN01C-02): Given a function program (for example COBIL00C) returns to
  COMEN01C with context 0 after PF3, when COMEN01C runs, then the menu is shown fresh with no message.

## Open questions
- Was option 8 (Transaction Add) intended to be admin-only? The commented-out label
  `Transaction Add (Admin Only)` suggests it once was (`app/cpy/COMEN02Y.cpy:69`).
- Should admins have access to the regular functions in the target (Q-COMEN01C-06)?
