# COSGN00C: Sign-on screen that authenticates a user against USRSEC and routes to the admin or main menu

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COSGN00C.cbl` (260 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CC00 (`app/csd/CARDDEMO.CSD:378-387`, program definition `app/csd/CARDDEMO.CSD:249-256`) |
| BMS map | mapset `COSGN00` / map `COSGN0A` (`app/bms/COSGN00.bms`, symbolic map `app/cpy-bms/COSGN00.CPY`) |
| Copybooks | `COCOM01Y` (CARDDEMO-COMMAREA), `COSGN00` (symbolic map), `COTTL01Y` (screen titles), `CSDAT01Y` (date/time work fields), `CSMSG01Y` (common messages), `CSUSR01Y` (SEC-USER-DATA), `DFHAID`, `DFHBMSCA` |
| Calls / XCTL targets | XCTL `COADM01C` (admin users), XCTL `COMEN01C` (all other users); EXEC CICS ASSIGN APPLID / SYSID |
| Data stores | CICS file `USRSEC` (KSDS `AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS`, 80-byte records, key SEC-USR-ID offset 0 length 8): Read by key (no UPDATE) |

## Purpose
COSGN00C is the entry point of the CardDemo online application. It displays the sign-on screen,
accepts a user ID and password, looks the user up in the user security file USRSEC, and compares the
entered password with the stored one. On success it records the user ID and user type in the
COMMAREA and transfers control to the admin menu (user type `A`) or the regular main menu (any other
type). PF3 ends the session with a thank-you message.

## Invocation and state
- Started by a terminal user typing transaction `CC00` (EIBCALEN = 0), or by XCTL from another
  program. Programs that "sign off" or detect a missing COMMAREA XCTL here: `COMEN01C` and
  `COADM01C` XCTL *without* a COMMAREA (EIBCALEN = 0, clean sign-on screen); `COUSR00C`-`COUSR03C`,
  `COTRN00C`-`COTRN02C`, `COBIL00C`, `CORPT00C`, `COPAUS0C` and others XCTL *with* a COMMAREA when they
  were started without one (see Q-COSGN00C-10).
- First-time detection uses only EIBCALEN. The program never copies DFHCOMMAREA into its working
  storage COMMAREA, so nothing from a previous turn is used (Q-COSGN00C-06).
- Each turn ends with `EXEC CICS RETURN TRANSID('CC00') COMMAREA(CARDDEMO-COMMAREA) LENGTH(160)`,
  so the next AID key from the terminal re-enters COSGN00C with EIBCALEN = 160.
- COMMAREA fields written before the successful XCTL (all other fields are left as whatever the
  task's working storage contains):

| COMMAREA field (`COCOM01Y`) | Value set |
|:--|:--|
| CDEMO-FROM-TRANID | `CC00` |
| CDEMO-FROM-PROGRAM | `COSGN00C` |
| CDEMO-USER-ID | Entered user ID, upper-cased (also set on every ENTER, even before verification) |
| CDEMO-USER-TYPE | SEC-USR-TYPE from the USRSEC record (`A` = admin, `U` = user; 88-levels CDEMO-USRTYP-ADMIN / CDEMO-USRTYP-USER) |
| CDEMO-PGM-CONTEXT | 0 (CDEMO-PGM-ENTER), which tells the menu program this is its first entry |

## Screen
BMS convention: `POS=(r,c)` is the attribute byte, so data starts in column c+1. The mock-up shows data
positions. `x` marks output data, `_` marks unprotected input; the password field is non-display.

```text
             1         2         3         4         5         6         7         8
    12345678901234567890123456789012345678901234567890123456789012345678901234567890
01   Tran : CC00               AWS Mainframe Modernization          Date : mm/dd/yy
02   Prog : COSGN00C                   CardDemo                     Time : hh:mm:ss
03   AppID: xxxxxxxx                                                SysID: xxxx
04
05        This is a Credit Card Demo Application for Mainframe Modernization
06
07                       +========================================+
08                       |%%%%%%%  NATIONAL RESERVE NOTE  %%%%%%%%|
09                       |%(1)  THE UNITED STATES OF KICSLAND (1)%|
10                       |%$              ___       ********  $%|
11                       |%$    {x}       (o o)                 $%|
12                       |%$     ******  (  V  )      O N E     $%|
13                       |%(1)          ---m-m---             (1)%|
14                       |%%~~~~~~~~~~~ ONE DOLLAR ~~~~~~~~~~~~~%%|
15                       +========================================+
16
17                  Type your User ID and Password, then press ENTER:
18
19                               User ID     : ________ (8 Char)
20                               Password    :          (8 Char) _
21
22
23   <message line, 78 bytes>
24   ENTER=Sign-on  F3=Exit
```

| Field (row,col of first data byte) | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|--:|:--|:--|:--|
| Tran (1,9) | TRNNAME | 4 | Out | (ASKIP,FSET,NORM), COLOR=BLUE | Transaction ID of the program (WS-TRANID) |
| Title 1 (1,22) | TITLE01 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE01 from COTTL01Y |
| Date (1,72) | CURDATE | 8 | Out | (ASKIP,FSET,NORM), COLOR=BLUE, INITIAL='mm/dd/yy' | Current date MM/DD/YY (POPULATE-HEADER-INFO) |
| Prog (2,9) | PGMNAME | 8 | Out | (FSET,NORM,PROT), COLOR=BLUE | Program name (WS-PGMNAME) |
| Title 2 (2,22) | TITLE02 | 40 | Out | (ASKIP,FSET,NORM), COLOR=YELLOW | CCDA-TITLE02 from COTTL01Y |
| Time (2,72) | CURTIME | 9 | Out | (FSET,NORM,PROT), COLOR=BLUE, INITIAL='Ahh:mm:ss' | Current time HH:MM:SS (9-byte field; 8 bytes used, last byte blank) |
| AppID (3,9) | APPLID | 8 | Out | (FSET,NORM,PROT), COLOR=BLUE | CICS APPLID (EXEC CICS ASSIGN APPLID) |
| SysID (3,72) | SYSID | 8 | Out | (FSET,NORM,PROT), COLOR=BLUE, INITIAL='        ' | CICS SYSID (EXEC CICS ASSIGN SYSID; 4 bytes into 8-byte field) |
| User ID (19,44) | USERID | 8 | In/Out | (FSET,IC,NORM,UNPROT), COLOR=GREEN, HILIGHT=OFF | User ID entry; initial cursor (IC) |
| Password (20,44) | PASSWD | 8 | In/Out | (DRK,FSET,UNPROT), COLOR=GREEN, HILIGHT=OFF, INITIAL='________' | Password entry, non-display (DRK). Initial value is 8 underscores, which are invisible and are returned as data if not overtyped (see Q-COSGN00C-03) |
| Unnamed (20,62) | (unnamed) | 1 | In/Out | (DRK,UNPROT), INITIAL=' ' | Stray 1-byte dark unprotected field; the program never reads it |
| Message (23,2) | ERRMSG | 78 | Out | (ASKIP,BRT,FSET), COLOR=RED | Message line (WS-MESSAGE); default color red, overridden by ERRMSGC where the program sets it |

Unnamed literals (all ASKIP): `Tran :`, `Prog :`, `AppID:`, `Date :`, `Time :`, `SysID:` labels
(blue; `AppID:` is `(FSET,NORM,PROT)`), the banner line at row 5 (neutral), the dollar-bill art rows 7-15
(blue), the instruction at row 17 and the `User ID     :` / `Password    :` labels (turquoise), `(8 Char)`
hints (blue), and the key legend `ENTER=Sign-on  F3=Exit` at row 24 (yellow). Mapset options:
`CTRL=(ALARM,FREEKB)`, `EXTATT=YES`, `MODE=INOUT`, `TIOAPFX=YES`.

Header values (POPULATE-HEADER-INFO, `app/cbl/COSGN00C.cbl:177-204`): TITLE01 = `      AWS Mainframe Modernization       `
and TITLE02 = `              CardDemo                  ` (`app/cpy/COTTL01Y.cpy:18-22`), TRNNAME = `CC00`,
PGMNAME = `COSGN00C`, CURDATE = MM/DD/YY from FUNCTION CURRENT-DATE, CURTIME = HH:MM:SS, APPLID and SYSID
from EXEC CICS ASSIGN.

## Processing flow
1. MAIN-PARA (`app/cbl/COSGN00C.cbl:73-102`): clear the error flag, WS-MESSAGE, and ERRMSGO.
2. IF EIBCALEN = 0 (first entry): set the whole output map to LOW-VALUES (so BMS initial values from
   the physical map are displayed), set the cursor to User ID, and PERFORM SEND-SIGNON-SCREEN.
3. OTHERWISE evaluate EIBAID:
   - ENTER: PERFORM PROCESS-ENTER-KEY.
   - PF3: move CCDA-MSG-THANK-YOU to WS-MESSAGE and PERFORM SEND-PLAIN-TEXT, which sends the text,
     erases the screen, frees the keyboard, and issues `EXEC CICS RETURN` with no TRANSID. The session ends.
   - Any other key: set the error flag, message CCDA-MSG-INVALID-KEY, PERFORM SEND-SIGNON-SCREEN.
4. RETURN TRANSID(CC00) with the 160-byte COMMAREA (not reached after a successful XCTL or after PF3).
5. PROCESS-ENTER-KEY (`app/cbl/COSGN00C.cbl:108-140`):
   1. RECEIVE MAP COSGN0A into COSGN0AI (RESP captured and ignored).
   2. IF User ID is spaces or LOW-VALUES: error "Please enter User ID ...", cursor User ID, send screen.
      OTHERWISE IF Password is spaces or LOW-VALUES: error "Please enter Password ...", cursor Password,
      send screen.
   3. Upper-case the entered User ID into WS-USER-ID and CDEMO-USER-ID; upper-case the entered Password
      into WS-USER-PWD (this happens even when step 2 found an error).
   4. IF no error: PERFORM READ-USER-SEC-FILE.
6. READ-USER-SEC-FILE (`app/cbl/COSGN00C.cbl:209-257`): READ USRSEC INTO SEC-USER-DATA with RIDFLD
   WS-USER-ID (8 bytes) and evaluate the RESP:
   - 0 (NORMAL): IF SEC-USR-PWD = WS-USER-PWD, set the COMMAREA fields listed above; IF the user type
     is `A`, XCTL to COADM01C, OTHERWISE XCTL to COMEN01C, passing CARDDEMO-COMMAREA.
     OTHERWISE message "Wrong Password. Try again ...", cursor Password, send screen.
   - 13 (NOTFND): error "User not found. Try again ...", cursor User ID, send screen.
   - Any other RESP: error "Unable to verify the User ...", cursor User ID, send screen.
7. SEND-SIGNON-SCREEN (`app/cbl/COSGN00C.cbl:145-157`): populate the header, move WS-MESSAGE to ERRMSG,
   SEND MAP with ERASE and CURSOR (symbolic cursor positioning: the field whose length was set to -1).
   On error redisplays, the received User ID and Password are still in the map buffer (input and output
   maps share storage), so they are sent back to the terminal.

## Rules
### Business rules
- **BR-COSGN00C-01**: When the program is entered with no COMMAREA (EIBCALEN = 0), it displays an empty
  sign-on screen with the cursor on User ID and no message, then returns with TRANSID CC00.
  Source: `app/cbl/COSGN00C.cbl:80-83 (MAIN-PARA)`, `app/cbl/COSGN00C.cbl:98-102 (MAIN-PARA)`.
- **BR-COSGN00C-02**: The entered User ID and Password are both converted to upper case
  (FUNCTION UPPER-CASE) before the lookup and comparison. The stored values are not converted, so
  sign-on is case-insensitive only for credentials that are stored in upper case.
  Source: `app/cbl/COSGN00C.cbl:132-136 (PROCESS-ENTER-KEY)`.
- **BR-COSGN00C-03**: The user is looked up by an exact-key READ of USRSEC using the upper-cased
  8-byte User ID (padded with spaces on the right by BMS input mapping).
  Source: `app/cbl/COSGN00C.cbl:211-219 (READ-USER-SEC-FILE)`.
- **BR-COSGN00C-04**: Authentication is a plain-text equality comparison of the stored 8-byte
  SEC-USR-PWD with the upper-cased entered password. No hashing, salting, attempt counting, or
  lockout exists. Source: `app/cbl/COSGN00C.cbl:223 (READ-USER-SEC-FILE)`.
- **BR-COSGN00C-05**: On successful authentication the COMMAREA is set to CDEMO-FROM-TRANID = `CC00`,
  CDEMO-FROM-PROGRAM = `COSGN00C`, CDEMO-USER-ID = upper-cased user ID, CDEMO-USER-TYPE = SEC-USR-TYPE,
  CDEMO-PGM-CONTEXT = 0. Source: `app/cbl/COSGN00C.cbl:224-228 (READ-USER-SEC-FILE)`.
- **BR-COSGN00C-06**: When the authenticated user's type is `A` (CDEMO-USRTYP-ADMIN), control is
  transferred to COADM01C (admin menu). For any other type value, including `U`, lower-case `a`,
  blank, or any other character, control is transferred to COMEN01C (main menu).
  Source: `app/cbl/COSGN00C.cbl:230-240 (READ-USER-SEC-FILE)`.
- **BR-COSGN00C-07**: Every screen send fills the header: titles from COTTL01Y, transaction `CC00`,
  program `COSGN00C`, date as MM/DD/YY (two-digit year from CURRENT-DATE positions 3-4 of the year),
  time as HH:MM:SS, CICS APPLID and SYSID. Source: `app/cbl/COSGN00C.cbl:177-204 (POPULATE-HEADER-INFO)`.
- **BR-COSGN00C-08**: Every redisplay of the sign-on map uses SEND MAP with ERASE and CURSOR; the cursor
  goes to the field whose length was set to -1 (User ID or Password).
  Source: `app/cbl/COSGN00C.cbl:145-157 (SEND-SIGNON-SCREEN)`.

### Validation rules
- **VR-COSGN00C-01**: User ID must not be spaces or LOW-VALUES; otherwise message
  "Please enter User ID ..." and cursor on User ID. This check runs first; when it fails, the password
  is not checked. Source: `app/cbl/COSGN00C.cbl:118-122 (PROCESS-ENTER-KEY)`.
- **VR-COSGN00C-02**: Password must not be spaces or LOW-VALUES; otherwise message
  "Please enter Password ..." and cursor on Password. Source: `app/cbl/COSGN00C.cbl:123-127 (PROCESS-ENTER-KEY)`.
- **VR-COSGN00C-03**: The entered password (upper-cased) must equal the stored password; otherwise
  message "Wrong Password. Try again ..." and cursor on Password. The User ID stays on the screen.
  Source: `app/cbl/COSGN00C.cbl:241-245 (READ-USER-SEC-FILE)`.

### Navigation and keys
- **NAV-COSGN00C-01**: ENTER -> validate and authenticate (PROCESS-ENTER-KEY).
  Source: `app/cbl/COSGN00C.cbl:86-87 (MAIN-PARA)`.
- **NAV-COSGN00C-02**: PF3 -> send plain text "Thank you for using CardDemo application...      "
  (CCDA-MSG-THANK-YOU, 50 bytes including six trailing spaces, sent as the 80-byte WS-MESSAGE) with
  ERASE and FREEKB, then `EXEC CICS RETURN` without TRANSID: the pseudo-conversation ends and the terminal
  is free for any transaction. Source: `app/cbl/COSGN00C.cbl:88-90 (MAIN-PARA)`,
  `app/cbl/COSGN00C.cbl:162-172 (SEND-PLAIN-TEXT)`, `app/cpy/CSMSG01Y.cpy:18-19`.
- **NAV-COSGN00C-03**: Any other AID (ENTER and PF3 excepted: PF1, PF2, PF4-PF24, PA1-PA3, CLEAR) ->
  message "Invalid key pressed. Please see below...         " (CCDA-MSG-INVALID-KEY, 50 bytes with
  trailing spaces) and the sign-on map is redisplayed. No RECEIVE is done, so typed input is not kept.
  Source: `app/cbl/COSGN00C.cbl:91-94 (MAIN-PARA)`, `app/cpy/CSMSG01Y.cpy:20-21`.
- **NAV-COSGN00C-04**: Successful sign-on -> XCTL to COADM01C (type `A`) or COMEN01C (other types)
  with CARDDEMO-COMMAREA (160 bytes). Source: `app/cbl/COSGN00C.cbl:230-240 (READ-USER-SEC-FILE)`.
- **NAV-COSGN00C-05**: Every path that redisplays the sign-on screen ends with RETURN TRANSID(`CC00`)
  COMMAREA(CARDDEMO-COMMAREA) LENGTH(160). Source: `app/cbl/COSGN00C.cbl:98-102 (MAIN-PARA)`.

### Error handling
- **ERR-COSGN00C-01**: USRSEC READ RESP = 13 (NOTFND) -> message "User not found. Try again ...",
  cursor on User ID. Source: `app/cbl/COSGN00C.cbl:247-251 (READ-USER-SEC-FILE)`.
- **ERR-COSGN00C-02**: USRSEC READ with any other non-zero RESP (for example NOTOPEN, DISABLED, IOERR)
  -> message "Unable to verify the User ...", cursor on User ID. RESP and RESP2 are not logged.
  Source: `app/cbl/COSGN00C.cbl:252-256 (READ-USER-SEC-FILE)`.
- **ERR-COSGN00C-03**: The RESP of RECEIVE MAP is captured but never tested; MAPFAIL or other receive
  errors fall through to the field checks with whatever is in the input map.
  Source: `app/cbl/COSGN00C.cbl:110-115 (PROCESS-ENTER-KEY)`.
- **ERR-COSGN00C-04**: The XCTL commands have no RESP or HANDLE; if COADM01C or COMEN01C is not
  defined, the task abends (PGMIDERR, abend code AEI0).
  Source: `app/cbl/COSGN00C.cbl:231-239 (READ-USER-SEC-FILE)`.

## Data effects
None. USRSEC is only read (no UPDATE). The program writes no file or queue and keeps no audit trail of
sign-on attempts. The only state handed on is the COMMAREA described above.

## Quirks
- **Q-COSGN00C-01**: Passwords are stored in USRSEC in plain text (SEC-USR-PWD PIC X(08)) and compared in
  plain text. The ten sample users loaded by `app/jcl/DUSRSECJ.jcl:35-44` all share the same demo password,
  which `README.md:204-205` publishes. Recommendation: fix. Store salted adaptive hashes (bcrypt or
  Argon2) or delegate to an identity provider; migrate users with a forced password reset; never load
  the demo credentials outside test environments. Source: `app/cbl/COSGN00C.cbl:223`, `app/cpy/CSUSR01Y.cpy:21`.
- **Q-COSGN00C-02**: Entered credentials are upper-cased but stored ones are not. COUSR01C and COUSR02C
  store the User ID and password exactly as typed. If the terminal does not upper-case input
  (UCTRAN), a user created with a lower-case ID or password can never sign on ("User not found. Try
  again ..." or "Wrong Password. Try again ..."). The effective password alphabet is also reduced to
  upper case. Recommendation: decide. The target should normalize user IDs (case-insensitive) the same
  way at creation and sign-on, and treat passwords as case-sensitive hashed secrets.
  Source: `app/cbl/COSGN00C.cbl:132-136`.
- **Q-COSGN00C-03**: The Password field has `INITIAL='________'` (8 underscores) with the DRK and FSET
  attributes. On first display the underscores are present but invisible and are transmitted back as
  data. A password shorter than 8 characters typed without erasing to end of field keeps trailing
  underscores and fails with "Wrong Password. Try again ...". Recommendation: do not reproduce; the
  target password input starts empty. Source: `app/bms/COSGN00.bms:175-180`.
- **Q-COSGN00C-04**: Different messages for an unknown user ("User not found. Try again ...") and a wrong
  password ("Wrong Password. Try again ...") allow user-ID enumeration. Recommendation: fix. Use one
  generic failure message. Source: `app/cbl/COSGN00C.cbl:242-249`.
- **Q-COSGN00C-05**: There is no failed-attempt counter, lockout, throttling, sign-on audit log, password
  expiry, or idle timeout (the CC00 definition has `DTIMOUT(NO)` and `RESSEC(NO) CMDSEC(NO)`).
  Recommendation: fix in the target (lockout or backoff, audit events, session timeout).
  Source: `app/cbl/COSGN00C.cbl:209-257`, `app/csd/CARDDEMO.CSD:378-387`.
- **Q-COSGN00C-06**: CARDDEMO-COMMAREA is never initialized. COSGN00C neither copies DFHCOMMAREA in nor
  INITIALIZEs the area; on success it sets only five fields. CDEMO-TO-TRANID, CDEMO-TO-PROGRAM, the
  customer, account, and card fields, and CDEMO-LAST-MAP/MAPSET carry whatever the task's working storage
  contained (transaction `STORAGECLEAR(NO)`), and later programs may test them (for example
  `CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES`). Recommendation: fix. Create a clean session object at
  sign-on. Source: `app/cbl/COSGN00C.cbl:48`, `app/cbl/COSGN00C.cbl:224-228`.
- **Q-COSGN00C-07**: Routing depends on type `A` only. A record with any type other than `A` (for example
  `a`, `X`, blank) signs on as a regular user. COUSR01C and COUSR02C do not validate the type, so such
  records can exist. COMEN01C's admin-only check tests for `U` explicitly, so a type `X` user would pass
  it. Recommendation: fix. Restrict user type to an enumeration {ADMIN, USER} with a constraint.
  Source: `app/cbl/COSGN00C.cbl:230`, `app/cpy/COCOM01Y.cpy:26-28`.
- **Q-COSGN00C-08**: On an error redisplay the received password is still in the shared input/output map
  buffer and is sent back to the terminal in the outbound data stream (hidden only by the DRK
  attribute). Recommendation: fix. Never echo a password back to the client.
  Source: `app/cbl/COSGN00C.cbl:145-157`, `app/cpy-bms/COSGN00.CPY` (COSGN0AO redefines COSGN0AI).
- **Q-COSGN00C-09**: On the invalid-key path the output map COSGN0AO is sent without being set to
  LOW-VALUES and without a RECEIVE, so its content depends on fresh working-storage contents (normally
  binary zeros, which makes BMS show the map's initial values). The wrong-password branch does not set
  WS-ERR-FLG, unlike the other error branches (no functional effect). Recommendation: preserve the
  visible behavior (fresh screen plus message); ignore the storage detail.
  Source: `app/cbl/COSGN00C.cbl:91-94`, `app/cbl/COSGN00C.cbl:241-245`.
- **Q-COSGN00C-10**: Programs that detect EIBCALEN = 0 and XCTL here *with* a COMMAREA (for example
  COUSR00C-COUSR03C via RETURN-TO-PREV-SCREEN) make COSGN00C see EIBCALEN > 0. Their AID is
  usually ENTER, so COSGN00C performs a RECEIVE MAP against terminal input that was not produced by
  the sign-on map (typically the typed transaction code). The likely result is MAPFAIL, ignored, and the
  message "Please enter User ID ..." instead of a clean sign-on screen. Recommendation: fix. The target
  redirects unauthenticated requests to a clean login page. Source: `app/cbl/COSGN00C.cbl:80-96`,
  `app/cbl/COUSR00C.cbl:110-112`, `app/cbl/COUSR00C.cbl:506-517`.
- **Q-COSGN00C-11**: CDEMO-USER-ID is overwritten with the entered (upper-cased) user ID on every ENTER,
  before the user is verified. It is not used after a failed attempt because the next turn does not read
  the COMMAREA. Recommendation: preserve as no-op; the target must only bind identity after
  successful authentication. Source: `app/cbl/COSGN00C.cbl:132-134`.
- **Q-COSGN00C-12**: CURTIME on this map is 9 bytes with `INITIAL='Ahh:mm:ss'`, while every other CardDemo
  map uses 8 bytes; the 8-byte time leaves the ninth byte blank. AppID label and the PGMNAME, CURTIME,
  APPLID, SYSID fields are `PROT` rather than `ASKIP`, so the cursor can tab onto them. A stray 1-byte
  unprotected dark field exists at row 20 column 62. Recommendation: cosmetic; do not reproduce.
  Source: `app/bms/COSGN00.bms:70-93`, `app/bms/COSGN00.bms:190-193`.

## Test scenarios
Sample data: USRSEC is loaded by `app/jcl/DUSRSECJ.jcl` (in-stream records at lines 35-44; EBCDIC copy
`app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`, 10 records of 80 bytes; there is no ASCII copy). Users
`ADMIN001`-`ADMIN005` have type `A`, `USER0001`-`USER0005` have type `U`, and all have the demo password
`PASSWORD` (`README.md:204-205`).

- **TS-COSGN00C-01** (covers BR-COSGN00C-01, BR-COSGN00C-07): Given no COMMAREA, when transaction CC00 is
  started, then the sign-on screen is shown with Tran `CC00`, Prog `COSGN00C`, today's date as MM/DD/YY,
  an empty message line, the cursor on User ID, and the next transaction set to CC00.
- **TS-COSGN00C-02** (covers VR-COSGN00C-01): Given the sign-on screen, when ENTER is pressed with User ID
  blank and Password `PASSWORD`, then the message is "Please enter User ID ..." and the cursor is on User ID.
- **TS-COSGN00C-03** (covers VR-COSGN00C-02): Given the sign-on screen, when User ID `ADMIN001` and a blank
  Password are entered, then the message is "Please enter Password ..." and the cursor is on Password.
- **TS-COSGN00C-04** (covers BR-COSGN00C-02, BR-COSGN00C-05, BR-COSGN00C-06): Given the sample USRSEC, when
  User ID `admin001` and password `password` (lower case) are entered, then control passes to COADM01C
  with CDEMO-USER-ID = `ADMIN001`, CDEMO-USER-TYPE = `A`, CDEMO-FROM-PROGRAM = `COSGN00C`,
  CDEMO-FROM-TRANID = `CC00`, CDEMO-PGM-CONTEXT = 0.
- **TS-COSGN00C-05** (covers BR-COSGN00C-06): Given the sample USRSEC, when `USER0001` / `PASSWORD` is
  entered, then control passes to COMEN01C with CDEMO-USER-TYPE = `U`.
- **TS-COSGN00C-06** (covers VR-COSGN00C-03): Given the sample USRSEC, when `USER0001` with password
  `WRONGPWD` is entered, then the message is "Wrong Password. Try again ..." and the cursor is on Password.
- **TS-COSGN00C-07** (covers ERR-COSGN00C-01): Given the sample USRSEC, when `NOSUCHUS` / `PASSWORD` is
  entered, then the message is "User not found. Try again ..." and the cursor is on User ID.
- **TS-COSGN00C-08** (covers ERR-COSGN00C-02): Given the USRSEC file is closed or disabled, when
  `ADMIN001` / `PASSWORD` is entered, then the message is "Unable to verify the User ...".
- **TS-COSGN00C-09** (covers NAV-COSGN00C-02): Given the sign-on screen, when PF3 is pressed, then the
  screen is erased and shows only "Thank you for using CardDemo application...", and no next transaction
  is scheduled.
- **TS-COSGN00C-10** (covers NAV-COSGN00C-03): Given the sign-on screen, when PF5 (or CLEAR) is pressed,
  then the sign-on screen is redisplayed with "Invalid key pressed. Please see below...".
- **TS-COSGN00C-11** (covers Q-COSGN00C-02): Given a USRSEC record `TEST0001` whose stored password is
  `abcd1234` (lower case, as COUSR01C can store it), when `TEST0001` / `abcd1234` is entered, then the
  message is "Wrong Password. Try again ...".
- **TS-COSGN00C-12** (covers Q-COSGN00C-07, BR-COSGN00C-06): Given a USRSEC record `TEST0002` with
  type `X` and password `PASSWORD`, when it signs on, then control passes to COMEN01C (not COADM01C).
- **TS-COSGN00C-13** (covers Q-COSGN00C-03): Given the freshly displayed sign-on screen, when `USER0001` is
  typed and only the first 5 characters `PASSW` are typed over the hidden underscores, then the
  password received is `PASSW___` and the message is "Wrong Password. Try again ...".

## Open questions
- Does the production terminal definition upper-case input (UCTRAN)? This decides whether lower-case
  IDs and passwords created by COUSR01C/COUSR02C can ever sign on (Q-COSGN00C-02).
- Should the modernized sign-on keep the 8-character limit on user IDs and passwords, which comes from
  the 8-byte record fields?
- Is the published demo password acceptable in any non-test environment of the target?
