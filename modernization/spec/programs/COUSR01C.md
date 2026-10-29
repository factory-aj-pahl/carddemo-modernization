# COUSR01C: Add a new user to the user security file USRSEC

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COUSR01C.cbl` (299 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CU01 (`app/csd/CARDDEMO.CSD:459-468`, program definition `app/csd/CARDDEMO.CSD:285-291`) |
| BMS map | mapset `COUSR01` / map `COUSR1A` (`app/bms/COUSR01.bms`, symbolic map `app/cpy-bms/COUSR01.CPY`) |
| Copybooks | `COCOM01Y` (CARDDEMO-COMMAREA, 160 bytes, no extension), `COUSR01` (symbolic map), `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y` (SEC-USER-DATA), `DFHAID`, `DFHBMSCA` |
| Calls / XCTL targets | XCTL `COADM01C` (PF3), XCTL `COSGN00C` when EIBCALEN = 0, passing the 160-byte working-storage COMMAREA (Q-COSGN00C-10) |
| Data stores | CICS file `USRSEC` (KSDS, 80-byte SEC-USER-DATA, key SEC-USR-ID 8 bytes): Write |

## Purpose
COUSR01C lets an administrator create a user: first name, last name, user ID, password, and user
type. All five fields are mandatory. The new record is written to USRSEC; a duplicate user ID is
rejected. After a successful add the screen is cleared for the next entry.

## Invocation and state
- Entered by XCTL from COADM01C option 2 (context 0, COMMAREA 160 bytes). No other program calls it.
- If started with no COMMAREA (EIBCALEN = 0), it XCTLs to COSGN00C *with* a COMMAREA (see
  Q-COSGN00C-10).
- With a COMMAREA, it copies DFHCOMMAREA(1:EIBCALEN) into CARDDEMO-COMMAREA and uses CDEMO-PGM-CONTEXT
  (0 = first display, 1 = reply). No program-specific COMMAREA extension and no other state is kept: all
  entered data travels in the screen fields.
- Each turn ends with RETURN TRANSID(`CU01`) COMMAREA(CARDDEMO-COMMAREA).
- Before XCTL (RETURN-TO-PREV-SCREEN) it sets CDEMO-FROM-TRANID = `CU01`, CDEMO-FROM-PROGRAM = `COUSR01C`,
  CDEMO-PGM-CONTEXT = 0.

## Screen
BMS convention: `POS=(r,c)` is the attribute byte, so data starts in column c+1.

```text
             1         2         3         4         5         6         7         8
    12345678901234567890123456789012345678901234567890123456789012345678901234567890
01   Tran: CU01                AWS Mainframe Modernization           Date: mm/dd/yy
02   Prog: COUSR01C                    CardDemo                      Time: hh:mm:ss
03
04                                     Add User
05
06
07
08        First Name: ____________________       Last Name: ____________________
09
10
11        User ID: ________ (8 Char)             Password:          (8 Char)
12
13
14        User Type: _ (A=Admin, U=User)
15
16
17
18
19
20
21
22
23   <message line, 78 bytes>
24   ENTER=Add User  F3=Back  F4=Clear  F12=Exit
```

| Field (row,col of first data byte) | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|--:|:--|:--|:--|
| Tran (1,8) | TRNNAME | 4 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Transaction ID of the program (WS-TRANID) |
| Title 1 (1,22) | TITLE01 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE01 from COTTL01Y |
| Date (1,72) | CURDATE | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='mm/dd/yy' | Current date MM/DD/YY (POPULATE-HEADER-INFO) |
| Prog (2,8) | PGMNAME | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Program name (WS-PGMNAME) |
| Title 2 (2,22) | TITLE02 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE02 from COTTL01Y |
| Time (2,72) | CURTIME | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='hh:mm:ss' | Current time HH:MM:SS |
| First Name (8,19) | FNAME | 20 | In/Out | (FSET,IC,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | First name (SEC-USR-FNAME); initial cursor (IC) |
| Last Name (8,57) | LNAME | 20 | In/Out | (FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | Last name (SEC-USR-LNAME) |
| User ID (11,16) | USERID | 8 | In/Out | (FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | New User ID (record key SEC-USR-ID) |
| Password (11,56) | PASSWD | 8 | In/Out | (DRK,FSET,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | Password, non-display (DRK) |
| User Type (14,18) | USRTYPE | 1 | In/Out | (FSET,NORM,UNPROT), COLOR=GREEN, HILIGHT=UNDERLINE | User type; screen hint '(A=Admin, U=User)' but not validated |
| Message (23,2) | ERRMSG | 78 | Out | (ASKIP,BRT,FSET), COLOR=RED | Message line (WS-MESSAGE); default color red, overridden by ERRMSGC where the program sets it |

Unnamed literals: header labels (blue), `Add User` at row 4 (neutral, bright), `First Name:` (turquoise,
no ATTRB so default ASKIP), `Last Name:`, `User ID:`, `Password:`, `User Type: ` (turquoise), the hints
`(8 Char)` and `(A=Admin, U=User)` (blue), and the key legend `ENTER=Add User  F3=Back  F4=Clear  F12=Exit`
at row 24 (yellow). F12 is advertised but not handled (Q-COUSR01C-05).

## Processing flow
1. MAIN-PARA (`app/cbl/COUSR01C.cbl:71-110`): error flag off; clear WS-MESSAGE and ERRMSGO.
2. IF EIBCALEN = 0: CDEMO-TO-PROGRAM = `COSGN00C`, PERFORM RETURN-TO-PREV-SCREEN.
3. OTHERWISE copy the COMMAREA in. IF context is not 1: set it to 1, clear the output map, cursor on
   First Name, send the screen.
4. OTHERWISE RECEIVE the map and evaluate EIBAID: ENTER -> PROCESS-ENTER-KEY; PF3 -> CDEMO-TO-PROGRAM =
   `COADM01C`, RETURN-TO-PREV-SCREEN; PF4 -> CLEAR-CURRENT-SCREEN; other -> error flag, cursor First Name,
   CCDA-MSG-INVALID-KEY, send the screen.
5. RETURN TRANSID(CU01) with the COMMAREA.
6. PROCESS-ENTER-KEY (`app/cbl/COUSR01C.cbl:115-160`): check in this order and stop at the first failure
   (each failure sets the error flag, the message, the cursor, and sends the screen with the entered data):
   First Name, Last Name, User ID, Password, User Type must not be spaces or LOW-VALUES. IF all present,
   cursor on First Name, build SEC-USER-DATA from the screen fields exactly as typed (SEC-USR-ID = User ID,
   SEC-USR-FNAME = First Name, SEC-USR-LNAME = Last Name, SEC-USR-PWD = Password, SEC-USR-TYPE = User Type)
   and PERFORM WRITE-USER-SEC-FILE.
7. WRITE-USER-SEC-FILE (`app/cbl/COUSR01C.cbl:238-274`): WRITE USRSEC FROM SEC-USER-DATA (80 bytes) with
   RIDFLD SEC-USR-ID, and evaluate RESP:
   - NORMAL: clear all input fields and the message (INITIALIZE-ALL-FIELDS), set the message color green,
     message `User ` + SEC-USR-ID up to the first space + ` has been added ...`, send the screen.
   - DUPKEY or DUPREC: error "User ID already exist...", cursor on User ID, send the screen.
   - Other: error "Unable to Add User...", cursor on First Name, send the screen.
8. CLEAR-CURRENT-SCREEN / INITIALIZE-ALL-FIELDS (`app/cbl/COUSR01C.cbl:279-295`): cursor First Name, set
   User ID, First Name, Last Name, Password, User Type, and the message to spaces, send the screen.

## Rules
### Business rules
- **BR-COUSR01C-01**: When entered with no COMMAREA, control is transferred to COSGN00C.
  Source: `app/cbl/COUSR01C.cbl:78-80 (MAIN-PARA)`, `app/cbl/COUSR01C.cbl:165-178 (RETURN-TO-PREV-SCREEN)`.
- **BR-COUSR01C-02**: On first entry the Add User screen is shown empty with the cursor on First Name.
  Source: `app/cbl/COUSR01C.cbl:83-87 (MAIN-PARA)`.
- **BR-COUSR01C-03**: A new USRSEC record is built from the screen exactly as typed: no upper-casing, trimming,
  or format check of the user ID, names, password, or type. SEC-USR-FILLER (23 bytes) is not set.
  Source: `app/cbl/COUSR01C.cbl:153-159 (PROCESS-ENTER-KEY)`.
- **BR-COUSR01C-04**: The record is added with a keyed WRITE; the user ID is the unique key.
  Source: `app/cbl/COUSR01C.cbl:240-248 (WRITE-USER-SEC-FILE)`.
- **BR-COUSR01C-05**: After a successful add, all fields are cleared, the cursor is on First Name, and the
  green message `User <id> has been added ...` is shown, where `<id>` is the user ID up to its first space
  (for example "User TEST0001 has been added ..."). Source: `app/cbl/COUSR01C.cbl:251-259 (WRITE-USER-SEC-FILE)`.
- **BR-COUSR01C-06**: Header with `CU01` / `COUSR01C`, date MM/DD/YY, time HH:MM:SS.
  Source: `app/cbl/COUSR01C.cbl:214-233 (POPULATE-HEADER-INFO)`.

### Validation rules
Checked in this order; only the first failure is reported. The entered values stay on the screen.
- **VR-COUSR01C-01**: First Name must not be spaces or LOW-VALUES; otherwise "First Name can NOT be empty..."
  and cursor on First Name. Source: `app/cbl/COUSR01C.cbl:118-123 (PROCESS-ENTER-KEY)`.
- **VR-COUSR01C-02**: Last Name must not be blank; otherwise "Last Name can NOT be empty..." and cursor on
  Last Name. Source: `app/cbl/COUSR01C.cbl:124-129 (PROCESS-ENTER-KEY)`.
- **VR-COUSR01C-03**: User ID must not be blank; otherwise "User ID can NOT be empty..." and cursor on
  User ID. Source: `app/cbl/COUSR01C.cbl:130-135 (PROCESS-ENTER-KEY)`.
- **VR-COUSR01C-04**: Password must not be blank; otherwise "Password can NOT be empty..." and cursor on
  Password. Source: `app/cbl/COUSR01C.cbl:136-141 (PROCESS-ENTER-KEY)`.
- **VR-COUSR01C-05**: User Type must not be blank; otherwise "User Type can NOT be empty..." and cursor on
  User Type. Any non-blank character is accepted (the `A`/`U` hint is not enforced).
  Source: `app/cbl/COUSR01C.cbl:142-147 (PROCESS-ENTER-KEY)`.
- **VR-COUSR01C-06**: The user ID must not already exist; otherwise "User ID already exist..." and cursor on
  User ID. Source: `app/cbl/COUSR01C.cbl:260-266 (WRITE-USER-SEC-FILE)`.

### Navigation and keys
- **NAV-COUSR01C-01**: ENTER -> validate and add (PROCESS-ENTER-KEY).
  Source: `app/cbl/COUSR01C.cbl:91-92 (MAIN-PARA)`.
- **NAV-COUSR01C-02**: PF3 -> XCTL to COADM01C with the COMMAREA, discarding anything typed.
  Source: `app/cbl/COUSR01C.cbl:93-95 (MAIN-PARA)`.
- **NAV-COUSR01C-03**: PF4 -> clear all fields and the message, cursor First Name.
  Source: `app/cbl/COUSR01C.cbl:96-97 (MAIN-PARA)`, `app/cbl/COUSR01C.cbl:279-295`.
- **NAV-COUSR01C-04**: Any other AID, including PF12 shown on the screen as `F12=Exit` -> message
  "Invalid key pressed. Please see below...         ", cursor First Name, screen re-sent with the typed
  data. For CLEAR and PA1-PA3 the terminal sends no field data: RECEIVE gets MAPFAIL (not tested), the
  map keeps its initial storage, and the five input fields (First Name, Last Name, User ID, Password,
  User Type) are blank on the re-sent screen; the header and the invalid-key message are filled as usual
  (SEND-USRADD-SCREEN), and the cursor is on First Name. Target: C-SHORTREAD
  (ledger-online.md). Source: `app/cbl/COUSR01C.cbl:98-102 (MAIN-PARA)`.
- **NAV-COUSR01C-05**: Every redisplay ends with RETURN TRANSID(`CU01`) COMMAREA(CARDDEMO-COMMAREA).
  Source: `app/cbl/COUSR01C.cbl:107-110 (MAIN-PARA)`.

### Error handling
- **ERR-COUSR01C-01**: WRITE RESP DUPKEY or DUPREC -> "User ID already exist..." (see VR-COUSR01C-06).
  Source: `app/cbl/COUSR01C.cbl:260-266 (WRITE-USER-SEC-FILE)`.
- **ERR-COUSR01C-02**: WRITE any other non-NORMAL RESP (NOTOPEN, NOSPACE, DISABLED, ...) -> "Unable to Add
  User...", cursor First Name. The RESP is not logged (the DISPLAY is commented out).
  Source: `app/cbl/COUSR01C.cbl:267-273 (WRITE-USER-SEC-FILE)`.
- **ERR-COUSR01C-03**: RECEIVE MAP RESP is captured but not tested.
  Source: `app/cbl/COUSR01C.cbl:201-209 (RECEIVE-USRADD-SCREEN)`.

## Data effects
- On a successful ENTER: one USRSEC record is inserted with key SEC-USR-ID = User ID as typed (8 bytes,
  padded with spaces), SEC-USR-FNAME (20), SEC-USR-LNAME (20), SEC-USR-PWD (8, plain text, as typed),
  SEC-USR-TYPE (1, as typed), SEC-USR-FILLER (23 bytes of uninitialized working storage).
- No other record is changed. USRSEC is defined with `RECOVERY(NONE)` and `JOURNAL(NO)`
  (`app/csd/CARDDEMO.CSD:88-96`), so the write is not backed out on abend and is not journaled. There is no
  audit record of who created the user.

## Quirks
- **Q-COUSR01C-01**: The password is stored in plain text (see Q-COSGN00C-01). There is no confirmation field,
  minimum length, or complexity rule. Recommendation: fix (hash at write; password policy).
  Source: `app/cbl/COUSR01C.cbl:157`.
- **Q-COUSR01C-02**: User ID and password are stored exactly as typed, but COSGN00C upper-cases the entered
  values before lookup and compare. Unless the terminal upper-cases input, a user created with lower-case
  letters in the ID or password can never sign on. Recommendation: fix (normalize IDs at creation; compare
  hashed case-sensitive passwords). Source: `app/cbl/COUSR01C.cbl:154-157`, `app/cbl/COSGN00C.cbl:132-136`.
- **Q-COUSR01C-03**: The user type is not validated. Any non-blank character (including lower-case `a`) is
  stored; COSGN00C treats anything except `A` as a regular user. Recommendation: fix (enumeration
  ADMIN/USER). Source: `app/cbl/COUSR01C.cbl:142-147`, `app/cbl/COUSR01C.cbl:158`.
- **Q-COUSR01C-04**: SEC-USR-FILLER is never initialized, so 23 bytes of undefined working storage are
  written (normally binary zeros, while the sample file has spaces). Recommendation: fix (write spaces / drop
  the column). Source: `app/cbl/COUSR01C.cbl:153-159`, `app/cpy/CSUSR01Y.cpy:23`.
- **Q-COUSR01C-05**: The screen advertises `F12=Exit`, but PF12 is not handled and produces
  "Invalid key pressed. Please see below...". Recommendation: decide (implement PF12 as cancel to the admin
  menu, as in COUSR02C/COUSR03C). Source: `app/bms/COUSR01.bms:155-159`, `app/cbl/COUSR01C.cbl:90-103`.
- **Q-COUSR01C-06**: User IDs with leading or embedded spaces are accepted. The success message cuts the ID at
  the first space (`DELIMITED BY SPACE`), so `AB CD` gives "User AB has been added ...", and an ID with a
  leading space gives "User  has been added ...". Recommendation: fix (validate ID format, for example
  1-8 alphanumerics). Source: `app/cbl/COUSR01C.cbl:255-258`.
- **Q-COUSR01C-07**: No authorization check of CDEMO-USER-TYPE (see Q-COADM01C-01). Recommendation: fix.
  Source: `app/cbl/COUSR01C.cbl:71-110`.
- **Q-COUSR01C-08**: The message "User ID already exist..." is ungrammatical; it is user-visible text.
  Recommendation: decide (preserve verbatim for parity tests or correct). Source: `app/cbl/COUSR01C.cbl:263`.

## Test scenarios
- **TS-COUSR01C-01** (covers BR-COUSR01C-02): Given COADM01C option 2, when COUSR01C starts, then the Add User
  screen is empty, the message line is empty, and the cursor is on First Name.
- **TS-COUSR01C-02** (covers VR-COUSR01C-01): Given the empty screen, when ENTER is pressed, then the message
  is "First Name can NOT be empty..." and the cursor is on First Name.
- **TS-COUSR01C-03** (covers VR-COUSR01C-02): Given only First Name `TEST`, when ENTER is pressed, then the
  message is "Last Name can NOT be empty..." and First Name still shows `TEST`.
- **TS-COUSR01C-04** (covers VR-COUSR01C-03): Given First and Last Name, when ENTER is pressed, then
  "User ID can NOT be empty...".
- **TS-COUSR01C-05** (covers VR-COUSR01C-04): Given names and User ID `TEST0001`, when ENTER is pressed, then
  "Password can NOT be empty...".
- **TS-COUSR01C-06** (covers VR-COUSR01C-05): Given names, ID, and password, when ENTER is pressed with User
  Type blank, then "User Type can NOT be empty...".
- **TS-COUSR01C-07** (covers BR-COUSR01C-03, BR-COUSR01C-04, BR-COUSR01C-05): Given the sample USRSEC, when First Name
  `TEST`, Last Name `USER`, User ID `TEST0001`, Password `PASSWORD`, User Type `U` are entered, then USRSEC
  gains record `TEST0001` with FNAME `TEST` padded to 20, LNAME `USER` padded to 20, PWD `PASSWORD`, TYPE `U`;
  all fields are cleared; the message "User TEST0001 has been added ..." is shown in green.
- **TS-COUSR01C-08** (covers VR-COUSR01C-06, ERR-COUSR01C-01): Given the sample USRSEC, when all fields are entered with User
  ID `ADMIN001`, then the message is "User ID already exist..." with the cursor on User ID, and the existing
  record is unchanged.
- **TS-COUSR01C-09** (covers Q-COUSR01C-03): Given the sample USRSEC, when a user `TEST0002` is added with type
  `Z`, then the record is written with SEC-USR-TYPE = `Z`.
- **TS-COUSR01C-10** (covers Q-COUSR01C-02): Given a user added as `test0003` with password `abc12345` (lower
  case, terminal without upper-case translation), when that user tries to sign on with the same values, then
  COSGN00C answers "User not found. Try again ...".
- **TS-COUSR01C-11** (covers NAV-COUSR01C-03): Given typed values, when PF4 is pressed, then all fields and the
  message are cleared.
- **TS-COUSR01C-12** (covers NAV-COUSR01C-02): Given typed values, when PF3 is pressed, then control passes to
  COADM01C and nothing is written.
- **TS-COUSR01C-13** (covers NAV-COUSR01C-04, Q-COUSR01C-05): Given the screen, when PF12 is pressed, then the
  message is "Invalid key pressed. Please see below...".
- **TS-COUSR01C-14** (covers ERR-COUSR01C-02): Given USRSEC is closed, when a valid new user is entered, then the
  message is "Unable to Add User...".

## Open questions
- Is there a business rule for the user ID format (the sample IDs are 8 characters, upper case, letters
  followed by digits)?
- Should the target keep a separate "created by / created at" audit for user maintenance?
