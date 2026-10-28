# COADM01C: Admin menu; table-driven dispatch to user security maintenance and the optional Db2 transaction-type functions

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COADM01C.cbl` (288 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CA00 (`app/csd/CARDDEMO.CSD:327-334`, program definition `app/csd/CARDDEMO.CSD:189-195`) |
| BMS map | mapset `COADM01` / map `COADM1A` (`app/bms/COADM01.bms`, symbolic map `app/cpy-bms/COADM01.CPY`) |
| Copybooks | `COCOM01Y` (CARDDEMO-COMMAREA), `COADM02Y` (admin option table), `COADM01` (symbolic map), `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y` (copied, not used), `DFHAID`, `DFHBMSCA` |
| Calls / XCTL targets | XCTL `COSGN00C` (sign-off / no COMMAREA); XCTL to `COUSR00C`, `COUSR01C`, `COUSR02C`, `COUSR03C`, `COTRTLIC`, `COTRTUPC` |
| Data stores | None (WS-USRSEC-FILE is declared but never used) |

## Purpose
COADM01C is the menu for administrators. It lists the six admin functions defined in `COADM02Y`
(list, add, update, and delete users, and the two optional Db2 transaction-type functions), accepts
an option number, and transfers control to the selected program. If a selected program is not
installed, it shows a message instead of failing. PF3 signs off.

## Invocation and state
- Entered by XCTL from COSGN00C after a successful sign-on of a type `A` user (context 0), and by XCTL
  from the admin functions when they exit: COUSR00C (PF3), COUSR01C (PF3), COUSR02C and COUSR03C (PF12,
  and PF3 when they were called from the admin menu), COTRTLIC and COTRTUPC (optional module
  `app/app-transaction-type-db2`).
- If started with no COMMAREA (EIBCALEN = 0), it XCTLs to COSGN00C without a COMMAREA.
- With a COMMAREA it copies DFHCOMMAREA(1:EIBCALEN) into the 160-byte CARDDEMO-COMMAREA and uses
  CDEMO-PGM-CONTEXT (0 = first display, 1 = reply) exactly as COMEN01C does.
- CDEMO-USER-TYPE is never examined: the program does not verify that the caller is an administrator.
- Fields written before XCTL to an option: CDEMO-FROM-TRANID = `CA00`, CDEMO-FROM-PROGRAM = `COADM01C`,
  CDEMO-PGM-CONTEXT = 0. Only the 160-byte base COMMAREA is passed; the user programs' 34-byte
  extensions (CDEMO-CU00-INFO etc.) therefore start as spaces.
- Each turn ends with RETURN TRANSID(`CA00`) COMMAREA(CARDDEMO-COMMAREA).

### Admin option table (`app/cpy/COADM02Y.cpy:19-59`)
CDEMO-ADMIN-OPT-COUNT = 6 (a commented-out earlier value was 4, before the Db2 options were added).
Each entry: CDEMO-ADMIN-OPT-NUM 9(02), CDEMO-ADMIN-OPT-NAME X(35), CDEMO-ADMIN-OPT-PGMNAME X(08). There
is no user-type column. The REDEFINES declares OCCURS 9.

| No. | Label (CDEMO-ADMIN-OPT-NAME) | Target program | Target transaction (target program's own TRANSID literal) | Module | Source lines |
|--:|:--|:--|:--|:--|:--|
| 1 | User List (Security) | COUSR00C | CU00 | base | `app/cpy/COADM02Y.cpy:26-29` |
| 2 | User Add (Security) | COUSR01C | CU01 | base | `app/cpy/COADM02Y.cpy:31-34` |
| 3 | User Update (Security) | COUSR02C | CU02 | base | `app/cpy/COADM02Y.cpy:36-39` |
| 4 | User Delete (Security) | COUSR03C | CU03 | base | `app/cpy/COADM02Y.cpy:41-44` |
| 5 | Transaction Type List/Update (Db2) | COTRTLIC | CTLI | app-transaction-type-db2 | `app/cpy/COADM02Y.cpy:46-49` |
| 6 | Transaction Type Maintenance (Db2) | COTRTUPC | CTTU | app-transaction-type-db2 | `app/cpy/COADM02Y.cpy:50-53` |

## Screen
BMS convention: `POS=(r,c)` is the attribute byte, so data starts in column c+1. Option numbers are
PIC 9(02), so they are shown with a leading zero.

```text
             1         2         3         4         5         6         7         8
    12345678901234567890123456789012345678901234567890123456789012345678901234567890
01   Tran: CA00                AWS Mainframe Modernization           Date: mm/dd/yy
02   Prog: COADM01C                    CardDemo                      Time: hh:mm:ss
03
04                                     Admin Menu
05
06                      01. User List (Security)
07                      02. User Add (Security)
08                      03. User Update (Security)
09                      04. User Delete (Security)
10                      05. Transaction Type List/Update (Db2)
11                      06. Transaction Type Maintenance (Db2)
12
13
14
15
16
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

Unnamed literals: `Tran:`, `Prog:`, `Date:`, `Time:` (blue), `Admin Menu` at row 4 (neutral, bright),
`Please select an option :` at row 20 (turquoise, bright), and `ENTER=Continue  F3=Exit` at row 24
(yellow). The map is identical to COMEN01 except for the title; OPTN007-OPTN012 (rows 12-17) are always blank.

## Processing flow
1. MAIN-PARA (`app/cbl/COADM01C.cbl:75-114`): register `HANDLE CONDITION PGMIDERR(PGMIDERR-ERR-PARA)` for
   the whole task; clear the error flag, WS-MESSAGE, and ERRMSGO.
2. IF EIBCALEN = 0: move `COSGN00C` to CDEMO-FROM-PROGRAM and PERFORM RETURN-TO-SIGNON-SCREEN (XCTL to
   CDEMO-TO-PROGRAM, defaulted to `COSGN00C`, without a COMMAREA).
3. OTHERWISE copy the COMMAREA in. IF context is not 1: set it to 1, clear the output map, send the menu.
4. OTHERWISE RECEIVE the map and evaluate EIBAID: ENTER -> PROCESS-ENTER-KEY; PF3 -> CDEMO-TO-PROGRAM =
   `COSGN00C`, PERFORM RETURN-TO-SIGNON-SCREEN; other -> CCDA-MSG-INVALID-KEY and send the menu.
5. RETURN TRANSID(CA00) with the COMMAREA.
6. PROCESS-ENTER-KEY (`app/cbl/COADM01C.cbl:119-158`):
   1. Normalize the 2-byte option exactly as COMEN01C does (right-justify, spaces to zeros), echo it.
   2. IF not numeric, greater than CDEMO-ADMIN-OPT-COUNT (6), or zero: error
      "Please enter a valid option number..." and send the menu.
   3. IF no error: IF the option's program name does not start with `DUMMY`, set FROM-TRANID = `CA00`,
      FROM-PROGRAM = `COADM01C`, context 0 and XCTL to it with the COMMAREA. If the XCTL raises PGMIDERR,
      control goes to PGMIDERR-ERR-PARA. When the name starts with `DUMMY` (none shipped), set the message
      color green, message "This option is not installed ...", and send the menu.
7. PGMIDERR-ERR-PARA (`app/cbl/COADM01C.cbl:270-284`): message color green, message
   "This option is not installed ...", send the menu, RETURN TRANSID(CA00) with the COMMAREA.
8. SEND-MENU-SCREEN / BUILD-MENU-OPTIONS / POPULATE-HEADER-INFO behave as in COMEN01C, using the admin
   table, `CA00`, and `COADM01C` (`app/cbl/COADM01C.cbl:175-266`).

## Rules
### Business rules
- **BR-COADM01C-01**: When entered with no COMMAREA, control is transferred to COSGN00C without a
  COMMAREA (clean sign-on). Source: `app/cbl/COADM01C.cbl:86-88 (MAIN-PARA)`, `app/cbl/COADM01C.cbl:163-170 (RETURN-TO-SIGNON-SCREEN)`.
- **BR-COADM01C-02**: On first entry (context not 1) the admin menu is displayed with no message and
  context is set to 1. Source: `app/cbl/COADM01C.cbl:90-94 (MAIN-PARA)`.
- **BR-COADM01C-03**: The menu lists the 6 entries of `COADM02Y` as `nn. <name>` on rows 6-11.
  Source: `app/cbl/COADM01C.cbl:229-266 (BUILD-MENU-OPTIONS)`.
- **BR-COADM01C-04**: The option entry is normalized like the main menu (`1`, ` 1`, `1 `, `01` are the same)
  and echoed. Source: `app/cbl/COADM01C.cbl:121-129 (PROCESS-ENTER-KEY)`.
- **BR-COADM01C-05**: For a valid option whose program name does not start with `DUMMY`, set
  CDEMO-FROM-TRANID = `CA00`, CDEMO-FROM-PROGRAM = `COADM01C`, CDEMO-PGM-CONTEXT = 0 and XCTL to the
  program with the 160-byte COMMAREA. No user-type check is made.
  Source: `app/cbl/COADM01C.cbl:140-148 (PROCESS-ENTER-KEY)`.
- **BR-COADM01C-06**: For an option whose program name starts with `DUMMY` (none in the shipped table),
  the menu is redisplayed with the green message "This option is not installed ...".
  Source: `app/cbl/COADM01C.cbl:150-157 (PROCESS-ENTER-KEY)`.
- **BR-COADM01C-07**: Header as in COMEN01C with `CA00` / `COADM01C`.
  Source: `app/cbl/COADM01C.cbl:205-224 (POPULATE-HEADER-INFO)`.

### Validation rules
- **VR-COADM01C-01**: The option must be numeric and between 1 and 6; otherwise message
  "Please enter a valid option number..." and the menu is redisplayed. Here the XCTL step is skipped when
  this check fails. Source: `app/cbl/COADM01C.cbl:131-140 (PROCESS-ENTER-KEY)`.

### Navigation and keys
- **NAV-COADM01C-01**: ENTER -> PROCESS-ENTER-KEY. Source: `app/cbl/COADM01C.cbl:98-99 (MAIN-PARA)`.
- **NAV-COADM01C-02**: PF3 -> XCTL to COSGN00C without a COMMAREA (sign-off).
  Source: `app/cbl/COADM01C.cbl:100-102 (MAIN-PARA)`, `app/cbl/COADM01C.cbl:163-170`.
- **NAV-COADM01C-03**: Any other AID -> "Invalid key pressed. Please see below...         " and the menu is
  redisplayed. Source: `app/cbl/COADM01C.cbl:103-106 (MAIN-PARA)`.
- **NAV-COADM01C-04**: Options 1-6 -> XCTL to COUSR00C, COUSR01C, COUSR02C, COUSR03C, COTRTLIC, COTRTUPC
  respectively, context 0. Source: `app/cbl/COADM01C.cbl:145-148`, `app/cpy/COADM02Y.cpy:26-53`.
- **NAV-COADM01C-05**: Every redisplay ends with RETURN TRANSID(`CA00`) COMMAREA(CARDDEMO-COMMAREA).
  Source: `app/cbl/COADM01C.cbl:111-114`, `app/cbl/COADM01C.cbl:280-283`.

### Error handling
- **ERR-COADM01C-01**: PGMIDERR on the option XCTL (typically options 5 and 6 when the Db2 module is not
  installed) -> the menu is redisplayed with the green message "This option is not installed ..." and the
  task returns with TRANSID CA00. The option name is not included (that part of the STRING is commented
  out). Source: `app/cbl/COADM01C.cbl:77-79 (MAIN-PARA)`, `app/cbl/COADM01C.cbl:270-284 (PGMIDERR-ERR-PARA)`.
- **ERR-COADM01C-02**: The HANDLE CONDITION is active for the whole task, so a PGMIDERR on the sign-off
  XCTL to COSGN00C would also show "This option is not installed ...".
  Source: `app/cbl/COADM01C.cbl:77-79`, `app/cbl/COADM01C.cbl:168-170`.
- **ERR-COADM01C-03**: The RECEIVE MAP RESP is captured but not tested.
  Source: `app/cbl/COADM01C.cbl:192-200 (RECEIVE-MENU-SCREEN)`.

## Data effects
None. No file is read or written.

## Quirks
- **Q-COADM01C-01**: No authorization check. COADM01C and the user-maintenance programs it calls never test
  CDEMO-USER-TYPE. Admin access depends only on COSGN00C routing type `A` users here and on the fact that a
  regular user's menu has no path to this program. Direct entry of CA00 or CU00-CU03 without a COMMAREA is
  redirected to sign-on, but there is no CICS resource security either (`RESSEC(NO) CMDSEC(NO)`), so any
  way of starting these programs with a COMMAREA (for example a forged COMMAREA via a LINK, START, or CECI)
  gives full admin capability. Recommendation: fix. The target must enforce an ADMIN role on the admin
  menu and on every user-management API. Source: `app/cbl/COADM01C.cbl:86-158`, `app/csd/CARDDEMO.CSD:327-334`.
- **Q-COADM01C-02**: The table REDEFINES declares `OCCURS 9` over data for 6 entries and has no user-type
  column (unlike COMEN02Y). Recommendation: fix (size from data; add role metadata).
  Source: `app/cpy/COADM02Y.cpy:55-59`.
- **Q-COADM01C-03**: The "not installed" message is shown in green (informational color) and does not name
  the option. Recommendation: decide; the target may show `This option <name> is not installed`.
  Source: `app/cbl/COADM01C.cbl:150-157`, `app/cbl/COADM01C.cbl:271-277`.
- **Q-COADM01C-04**: Unlike COMEN01C, this menu has no INQUIRE check; it relies on HANDLE CONDITION. A program
  that is defined but fails for another reason (for example a Db2 plan error inside COTRTLIC) is not caught
  here. Recommendation: preserve the "not installed" behavior via feature flags in the target.
  Source: `app/cbl/COADM01C.cbl:77-79`, `app/cbl/COADM01C.cbl:141-148`.
- **Q-COADM01C-05**: Options 3 (update) and 4 (delete) started from this menu open an empty screen that
  needs a typed user ID; started from the user list (COUSR00C) they open with the selected user already
  fetched. Recommendation: preserve both entry modes. Source: `app/cbl/COUSR02C.cbl:99-104`, `app/cbl/COUSR03C.cbl:99-104`.
- **Q-COADM01C-06**: PGMIDERR-ERR-PARA ends with an extra period on its own line, and the paragraph falls
  through to nothing after RETURN; harmless. Recommendation: ignore. Source: `app/cbl/COADM01C.cbl:283-284`.

## Test scenarios
- **TS-COADM01C-01** (covers BR-COADM01C-02, BR-COADM01C-03): Given a COMMAREA from COSGN00C for `ADMIN001`
  (type `A`, context 0), when COADM01C runs, then the Admin Menu shows `01. User List (Security)` through
  `06. Transaction Type Maintenance (Db2)` on rows 6-11, rows 12-17 are blank, the message is empty, and the
  returned COMMAREA has context 1.
- **TS-COADM01C-02** (covers BR-COADM01C-05, NAV-COADM01C-04): Given the admin menu, when `1` is entered, then
  control passes to COUSR00C with CDEMO-FROM-PROGRAM = `COADM01C`, CDEMO-FROM-TRANID = `CA00`, context 0, and
  a 160-byte COMMAREA.
- **TS-COADM01C-03** (covers NAV-COADM01C-04): Given the admin menu, when `2`, `3`, or `4` is entered, then
  control passes to COUSR01C, COUSR02C, or COUSR03C respectively.
- **TS-COADM01C-04** (covers ERR-COADM01C-01): Given COTRTLIC is not installed, when `5` is entered, then the
  admin menu is redisplayed with the green message "This option is not installed ...".
- **TS-COADM01C-05** (covers VR-COADM01C-01): Given the admin menu, when `7`, `0`, or blank is entered, then
  the message is "Please enter a valid option number..." and no XCTL happens.
- **TS-COADM01C-06** (covers NAV-COADM01C-02): Given the admin menu, when PF3 is pressed, then control passes
  to COSGN00C without a COMMAREA (clean sign-on screen).
- **TS-COADM01C-07** (covers NAV-COADM01C-03): Given the admin menu, when PF2 is pressed, then the message is
  "Invalid key pressed. Please see below...".
- **TS-COADM01C-08** (covers BR-COADM01C-01): Given no COMMAREA, when CA00 is started, then control passes to
  COSGN00C.
- **TS-COADM01C-09** (covers Q-COADM01C-01): Given a COMMAREA with CDEMO-USER-TYPE = `U` and context 1, when
  `1` is entered, then control still passes to COUSR00C (documents the missing authorization check; the
  target must reject this with an authorization error).

## Open questions
- Are the Db2 options 5 and 6 expected in every target deployment, or should they be feature-flagged like
  the optional module is today?
- Should the target give admins any route to the regular main menu (see Q-COMEN01C-06)?
