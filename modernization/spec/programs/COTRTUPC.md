# COTRTUPC: Single-record add, update, and delete of a Db2 transaction type

| Attribute | Value |
|:--|:--|
| Source | `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` (1702 lines) |
| Module | app-transaction-type-db2 |
| Kind | Online (CICS), pseudo-conversational, embedded static Db2 SQL |
| Transaction ID / Job | CTTU (program `app/app-transaction-type-db2/csd/CRDDEMOD.csd:18-24`, transaction `:35-44`; DB2TRAN `CTTUTRAN` -> DB2ENTRY `CARDDEMO`, `PLAN(CARDDEMO)`, `AUTHTYPE(USERID)`, `THREADLIMIT(1)`, `THREADWAIT(YES)`, `DROLLBACK(YES)`, `:45-50`, `:56-60`) |
| BMS map | mapset `COTRTUP` / map `CTRTUPA` (`app/app-transaction-type-db2/bms/COTRTUP.bms`, symbolic map `app/app-transaction-type-db2/cpy-bms/COTRTUP.cpy`); the program literal is `'COTRTUP '` (X(8)) |
| Copybooks | `COCOM01Y` (CARDDEMO-COMMAREA), `CVCRD01Y` (CC-WORK-AREA, CCARD-AID-*, CCARD-NEXT-*, CCARD-ERROR-MSG), `COTTL01Y`, `COTRTUP`, `CSDAT01Y`, `CSMSG01Y`, `CSMSG02Y` (ABEND-DATA), `CSUSR01Y`, `CSUTLDWY` (included, unused), `DFHBMSCA`, `DFHAID`, `CSSETATY` (COPY REPLACING in 3300), `CSSTRPFY` (YYYY-STORE-PFKEY); SQL INCLUDE `SQLCA`, `DCLTRTYP` (`app/app-transaction-type-db2/dcl/DCLTRTYP.dcl`), `DCLTRCAT` (included, unused). It does **not** include `CSDB2RWY` / `CSDB2RPY` (ENT-DB2-COMMON-WORK): no priming query and no DSNTIAC call; SQL messages are built from `SQLERRM`. |
| Calls / XCTL targets | XCTL CDEMO-FROM-PROGRAM, or `COADM01C` when blank (PF3). No CALLs. |
| Data stores | Db2 `CARDDEMO.TRANSACTION_TYPE` (ENT-DB2-TRAN-TYPE): Read (singleton SELECT by `TR_TYPE`), Update (`TR_DESCRIPTION` by `TR_TYPE`), Insert (when the update finds no row), Delete (by `TR_TYPE`). `CARDDEMO.TRANSACTION_TYPE_CATEGORY` (ENT-DB2-TRAN-TYPE-CATEGORY) is not accessed, but its FK `ON DELETE RESTRICT` governs deletes. No VSAM access. |

## Purpose
COTRTUPC is the administrator's maintenance screen for one row of the Db2 reference table of transaction
types. The administrator types a two-digit type code; if the type exists, its description is shown and can be
changed (validated, then saved with PF5) or the type can be deleted (PF4, confirmed with a second PF4). If the
type does not exist, PF5 switches to "add" mode, the administrator types a description, and PF5 saves a new row.
The save is an "upsert": an UPDATE that finds no row is followed by an INSERT. Changes affect only the Db2 table;
the VSAM file TRANTYPE (ENT-TRAN-TYPE) read by batch report CBTRN03C is refreshed only through JOB-TRANEXTR and a
VSAM reload (see Data effects).

## Invocation and state
- Entered by XCTL from COADM01C option 6 `Transaction Type Maintenance (Db2) ` (`app/cpy/COADM02Y.cpy:50-53`,
  NAV-COADM01C-04). COADM01C sets CDEMO-FROM-TRANID = `CA00`, CDEMO-FROM-PROGRAM = `COADM01C`, context 0
  (`app/cbl/COADM01C.cbl:142-148`). When the module is not installed, COADM01C reports the XCTL failure
  (ERR-COADM01C-01).
- Entered by XCTL from COTRTLIC on PF2 (NAV-COTRTLIC-02) with CDEMO-FROM-TRANID = `CTLI`, CDEMO-FROM-PROGRAM =
  `COTRTLIC`, context 0, and CDEMO-USRTYP-USER set.
- Can be started by typing `CTTU` (EIBCALEN = 0); the COMMAREA is then initialized (BR-COTRTUPC-01) and no
  sign-on or user-type check is made (Q-COTRTUPC-10).
- Every turn ends with `RETURN TRANSID('CTTU') COMMAREA(WS-COMMAREA) LENGTH(2000)` (BR-COTRTUPC-27). Unlike
  COTRTLIC, COMMON-RETURN does **not** overwrite CDEMO-FROM-TRANID / CDEMO-FROM-PROGRAM, so they keep the caller's
  values for the whole conversation and PF3 returns to the caller (NAV-COTRTUPC-02).
- WS-COMMAREA (X(2000), initialized to spaces at the start of each turn) holds CARDDEMO-COMMAREA followed by the
  program extension WS-THIS-PROGCOMMAREA (105 bytes, `COTRTUPC.cbl:294-335`):

| Offset (0-based) | Field | Picture | Meaning |
|--:|:--|:--|:--|
| 0-159 | CARDDEMO-COMMAREA | `COCOM01Y` | Standard COMMAREA |
| 160 | TTUP-CHANGE-ACTION | X(01) | Conversation state (table below) |
| 161 | TTUP-OLD-TTYP-TYPE | X(02) | Type code as read from Db2 (spaces when not found) |
| 163 | TTUP-OLD-TTYP-TYPE-DESC | X(50) | Description as read from Db2 |
| 213 | TTUP-NEW-TTYP-TYPE | X(02) | Type code as entered (normalized to two digits when edited, BR-COTRTUPC-05) |
| 215 | TTUP-NEW-TTYP-TYPE-DESC | X(50) | Description as entered (trimmed, left-justified) |

State values of TTUP-CHANGE-ACTION (88-levels at `COTRTUPC.cbl:296-327`):

| Value | 88-level(s) | Meaning | Set by |
|:--|:--|:--|:--|
| LOW-VALUES or space | TTUP-DETAILS-NOT-FETCHED | Waiting for a type code | BR-COTRTUPC-01/-02/-03, VR failures, BR-COTRTUPC-06, BR-COTRTUPC-12 |
| `K` | TTUP-INVALID-SEARCH-KEYS | Invalid key entered | Set then immediately overwritten (Q-COTRTUPC-15) |
| `X` | TTUP-DETAILS-NOT-FOUND | Type not in table (or read error) | BR-COTRTUPC-10 |
| `S` | TTUP-SHOW-DETAILS | Row shown, may be edited or deleted | BR-COTRTUPC-10 |
| `R` | TTUP-CREATE-NEW-RECORD | Add mode: enter a description | BR-COTRTUPC-16 |
| `V` | TTUP-REVIEW-NEW-RECORD | Never set | (Q-COTRTUPC-15) |
| `9` | TTUP-CONFIRM-DELETE (in TTUP-DELETE-IN-PROGRESS) | Delete awaiting second PF4 | BR-COTRTUPC-14 |
| `8` | TTUP-START-DELETE (in DELETE-IN-PROGRESS) | Delete issued; persists after FK failure | BR-COTRTUPC-15, ERR-COTRTUPC-07 |
| `7` | TTUP-DELETE-DONE (in DELETE-IN-PROGRESS) | Delete committed | BR-COTRTUPC-28 |
| `6` | TTUP-DELETE-FAILED (in DELETE-IN-PROGRESS) | Delete failed | ERR-COTRTUPC-08 |
| `E` | TTUP-CHANGES-NOT-OK (in TTUP-CHANGES-MADE) | Edited, description edit pending or failed | BR-COTRTUPC-09 |
| `N` | TTUP-CHANGES-OK-NOT-CONFIRMED (in CHANGES-MADE) | Edits passed, waiting for PF5 | BR-COTRTUPC-09 |
| `L` | TTUP-CHANGES-OKAYED-LOCK-ERROR (in CHANGES-MADE, CHANGES-FAILED) | UPDATE got -911 | ERR-COTRTUPC-03 |
| `F` | TTUP-CHANGES-OKAYED-BUT-FAILED (in CHANGES-MADE, CHANGES-FAILED) | Intended for SQL failure; never reached | (Q-COTRTUPC-02) |
| `C` | TTUP-CHANGES-OKAYED-AND-DONE | Save finished (or reported as finished) | BR-COTRTUPC-19 |
| `B` | TTUP-CHANGES-BACKED-OUT | Update cancelled with PF12 | BR-COTRTUPC-12 |

- WS-MISC-STORAGE (all edit flags, messages, WS-INFO-MSG) is INITIALIZEd at the start of every turn
  (`COTRTUPC.cbl:352-354`). INITIALIZE puts spaces in the flags, so flags whose "valid" value is LOW-VALUES
  (FLG-TRANFILTER-ISVALID, FLG-DESCRIPTION-ISVALID) start each turn in no state (Q-COTRTUPC-16).

## Screen
BMS convention: `POS=(r,c)` is the attribute byte, so data starts in column c+1. The mock-up shows state `S`
after ENTER with type `1` and the seed data of JOB-CREADB21.

```text
             1         2         3         4         5         6         7         8
    12345678901234567890123456789012345678901234567890123456789012345678901234567890
01   Tran: CTTU                AWS Mainframe Modernization           Date: mm/dd/yy
02   Prog: COTRTUPC                    CardDemo                      Time: hh:mm:ss
03
04
05
06
07                              Maintain Transaction Type
08
09
10
11
12      Transaction Type  :   01
13
14      Description       :   PURCHASE__________________________________________
15
16
17
18
19
20
21
22                         Enter transaction type to be maintained
23   <ERRMSG: 78 characters, red>
24   ENTER=Process F3=Exit F4=Delete                                     F12=Cancel
```

| Field | BMS name | Len | In/Out | Attributes (BMS default; program overrides in 3300/3390/3391) | Meaning |
|:--|:--|--:|:--|:--|:--|
| Tran (1,7) | TRNNAME | 4 | Out | ASKIP,FSET,NORM, blue | `CTTU` |
| Title 1 (1,21) | TITLE01 | 40 | Out | ASKIP,NORM, yellow | CCDA-TITLE01 |
| Date (1,71) | CURDATE | 8 | Out | ASKIP,NORM, blue | Current date `mm/dd/yy` |
| Prog (2,7) | PGMNAME | 8 | Out | ASKIP,NORM, blue | `COTRTUPC` |
| Title 2 (2,21) | TITLE02 | 40 | Out | ASKIP,NORM, yellow | CCDA-TITLE02 |
| Time (2,71) | CURTIME | 8 | Out | ASKIP,NORM, blue | Current time `hh:mm:ss` |
| Transaction Type (12,26) | TRTYPCD | 2 | In/Out | IC,UNPROT, underline; program sets PROT+FSET (`DFHBMPRF`) or UNPROT+FSET (`DFHBMFSE`) per BR-COTRTUPC-23; red / `*` per BR-COTRTUPC-25 | Type code being maintained |
| Description (14,26) | TRTYDSC | 50 | In/Out | UNPROT, underline; program sets PROT+FSET or UNPROT+FSET per BR-COTRTUPC-23; red / `*` per CSSETATY | Type description |
| Info line (22,23) | INFOMSG | 45 | Out | ASKIP, neutral, HILIGHT=OFF; program sets ASKIP+BRT (`DFHBMASB`), or dark when empty | Centered prompt (BR-COTRTUPC-21, -22) |
| Message (23,1) | ERRMSG | 78 | Out | ASKIP,BRT,FSET, red | WS-RETURN-MSG (75 bytes) |
| Keys (24,1) | FKEYS | 21 | Out | ASKIP,NORM, yellow; dark in state `9` | `ENTER=Process F3=Exit` |
| F4 (24,23) | FKEY04 | 9 | Out | ASKIP,DRK; bright in `S`, `9` | `F4=Delete` |
| F5 (24,33) | FKEY05 | 8 | Out | ASKIP,DRK; bright in `N`, `X` | `F5=Save` |
| F6 (24,43) | FKEY06 | 6 | Out | ASKIP,DRK; never shown | `F6=Add` (Q-COTRTUPC-15) |
| F12 (24,69) | FKEY12 | 10 | Out | ASKIP,DRK; bright in `N`, `S`, `X`, `9`, `R` | `F12=Cancel` |

Unnamed literals: `Tran:` (1,1), `Date:` (1,65), `Prog:` (2,1), `Time:` (2,65) blue; `Maintain Transaction Type`
(7,28) neutral; `Transaction Type  :` (12,4) and `Description       :` (14,4) turquoise; stopper fields at
(12,29), (14,77), (22,69).

## Processing flow
1. 0000-MAIN (`COTRTUPC.cbl:345-381`): HANDLE ABEND LABEL(ABEND-ROUTINE); initialize CC-WORK-AREA,
   WS-MISC-STORAGE, WS-COMMAREA; clear WS-RETURN-MSG. IF EIBCALEN = 0 (or the two caller conditions, which are
   never true, Q-COTRTUPC-05), initialize CARDDEMO-COMMAREA and the extension, set context = enter and state =
   not fetched (BR-COTRTUPC-01). OTHERWISE copy the COMMAREA bytes 1-160 and 161-265 into CARDDEMO-COMMAREA and
   the extension.
2. Map the AID with YYYY-STORE-PFKEY (PF13-PF24 fold to PF1-PF12). Validate the key against the state in
   0001-CHECK-PFKEYS (NAV-COTRTUPC-01).
3. Apply the automatic reset (BR-COTRTUPC-03): for PF12 in `S`/`R`/`X`, and for states `C`, `L`, `F`, `B` with a
   blank old type, `7`, `6`, set context = enter and state = not fetched.
4. EVALUATE in this order (first match wins):
   1. PF3 -> NAV-COTRTUPC-02 (SYNCPOINT, XCTL to caller).
   2. First entry (context = enter and caller COADM01C or COTRTLIC, or context = enter and state not fetched)
      -> BR-COTRTUPC-02: clear the extension, send the empty prompt screen, set context = re-enter.
   3. PF4 and state `9` -> state `8`, 9800-DELETE-PROCESSING (BR-COTRTUPC-15), send screen.
   4. PF4 and state `S` -> state `9` (BR-COTRTUPC-14), send screen.
   5. PF5 and state `X` -> state `R` (BR-COTRTUPC-16), send screen.
   6. PF5 and state `N` -> 9600-WRITE-PROCESSING (BR-COTRTUPC-17), send screen.
   7. PF12 and state `N`, `9`, or `S` -> 2000-DECIDE-ACTION (BR-COTRTUPC-12), send screen. (`S` never reaches
      here because step 3 already reset it.)
   8. Invalid key (WS-RETURN-MSG = "Invalid key pressed") -> send the screen for the current state unchanged.
   9. OTHERWISE (ENTER, and PF5 in `9`/`8`/`7`/`6`) -> 1000-PROCESS-INPUTS, 2000-DECIDE-ACTION, send screen.
5. 1000-PROCESS-INPUTS (`:625-636`): RECEIVE MAP into CTRTUPAI (RESP not checked); 1150-STORE-MAP-IN-NEW
   (BR-COTRTUPC-04); 1200-EDIT-MAP-INPUTS:
   1. IF state `X` and the trimmed screen type equals NEW type -> BR-COTRTUPC-06 (re-lookup, no edits); exit.
   2. IF state is not `R` and not `N`: 1210-EDIT-TRANTYPE (VR-COTRTUPC-01..03, BR-COTRTUPC-05). IF blank ->
      state not fetched, exit. IF invalid -> state not fetched, exit. IF state is not fetched -> exit (a lookup
      follows in 2000).
   3. Mark the key valid; 1205-COMPARE-OLD-NEW (BR-COTRTUPC-08). IF no change, or state `N` or `C` -> exit.
   4. State `E`; edit the description (VR-COTRTUPC-05, -06). IF no error -> state `N` (BR-COTRTUPC-09).
6. 2000-DECIDE-ACTION (`:978-1081`), by state: not fetched or PF12 -> BR-COTRTUPC-10 / -12; `S` -> BR-COTRTUPC-13;
   `E`, `K`, `N` -> unchanged; `B` -> `E`; `C` -> `S` (unreachable); any other state (`9`, `8`, `R`... ) ->
   ERR-COTRTUPC-09 (abend). (`R` never arrives here: 1200 always moves it to `E` or `N`.)
7. 3000-SEND-MAP (`:1089-1104`): 3100 header; 3200 field values (BR-COTRTUPC-20); 3250 info and error
   message (BR-COTRTUPC-21, -22); 3300 attributes, cursor, colors (BR-COTRTUPC-23..25); 3390 info attribute;
   3391 PF-key labels (BR-COTRTUPC-26); 3400 `SEND MAP('CTRTUPA') MAPSET('COTRTUP ') FROM(CTRTUPAO) CURSOR ERASE
   FREEKB` (RESP not checked).
8. COMMON-RETURN (BR-COTRTUPC-27).

## Rules
### Business rules
- **BR-COTRTUPC-01**: When EIBCALEN = 0, CARDDEMO-COMMAREA and the extension are initialized (all fields spaces
  or zero, so user ID, user type, and FROM fields are blank), context = enter, state = not fetched. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:366-374 (0000-MAIN)`.
- **BR-COTRTUPC-02**: First entry: when (context = enter AND CDEMO-FROM-PROGRAM = `COADM01C`) OR (context =
  enter AND CDEMO-FROM-PROGRAM = `COTRTLIC`) OR (context = enter AND state = not fetched), the program
  initializes the extension (state, old and new values), WS-MISC-STORAGE, and CDEMO-ACCT-ID (set to zero),
  sends the screen with empty fields, info "Enter transaction type to be maintained", type field unprotected
  with the cursor on it, then sets context = re-enter and state = not fetched. The screen is not received on
  this turn. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:465-478 (0000-MAIN)`.
- **BR-COTRTUPC-03**: Automatic reset at the start of a turn: when (PF12 AND state `S`, `R`, or `X`) OR state
  `C` OR state `L` or `F` OR (state `B` AND old details are LOW-VALUES or spaces) OR state `7` OR state `6`, set
  context = enter and state = not fetched. Unless the key is PF3, the turn then follows BR-COTRTUPC-02, so
  anything typed on the previous screen is discarded (Q-COTRTUPC-19). Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:405-419 (0000-MAIN)`.
- **BR-COTRTUPC-04**: Screen capture (1150-STORE-MAP-IN-NEW): IF state `X` AND the key is not PF5 AND
  TRIM(screen type) = NEW type, keep the NEW values unchanged. OTHERWISE clear NEW; NEW type = LOW-VALUES when the
  screen type is `*` or spaces, else TRIM(screen type); NEW description = LOW-VALUES when the screen description
  is `*` or spaces, else TRIM(screen description). Protected fields are sent with FSET, so displayed values come
  back on every ENTER. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:652-685 (1150-STORE-MAP-IN-NEW)`.
- **BR-COTRTUPC-05**: Type normalization: when the type passes VR-COTRTUPC-01..03, NEW type =
  FUNCTION NUMVAL(type) moved to an unsigned PIC 9(02) and back to X(02), so `1`, ` 1`, `1 `, `+1`, `-1` all
  become `01`, and `.5` becomes `00` (Q-COTRTUPC-14). Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:834-842 (1210-EDIT-TRANTYPE)`.
- **BR-COTRTUPC-06**: Not-found re-lookup: IF state `X` AND TRIM(screen type) = NEW type (always true after
  BR-COTRTUPC-04 for a non-blank type), then for any key other than PF5 the state becomes not fetched, the key is
  marked valid, and all edits are skipped, so 2000 looks up the **raw, unvalidated** typed value
  (Q-COTRTUPC-01). Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:698-710 (1200-EDIT-MAP-INPUTS)`.
- **BR-COTRTUPC-07**: In states `R` and `N` the type is not edited (it was validated when looked up). Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:712-737 (1200-EDIT-MAP-INPUTS)`.
- **BR-COTRTUPC-08**: Change detection (1205-COMPARE-OLD-NEW): "no change" when UPPER(NEW type) = UPPER(OLD type)
  AND UPPER(TRIM(NEW description)) = UPPER(TRIM(OLD description)) AND the trimmed lengths are equal. On no
  change, if no message is set yet, message "No change detected with respect to values fetched.". A change in
  letter case only counts as no change (Q-COTRTUPC-11). Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:783-812 (1205-COMPARE-OLD-NEW)`.
- **BR-COTRTUPC-09**: After the compare: IF no change, or state `N`, or state `C`, the edits stop (description
  flags set valid, state unchanged). OTHERWISE state = `E`, the description is edited (VR-COTRTUPC-05, -06), and
  if no error was found state = `N` ("Changes validated.Press F5 to save"). Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:741-776 (1200-EDIT-MAP-INPUTS)`.
- **BR-COTRTUPC-10**: Lookup: when state = not fetched (or the key is PF12) AND the key is valid, clear
  WS-RETURN-MSG and perform 9000-READ-TRANTYPE: OLD details are initialized (spaces), info cleared, then
  ```sql
  SELECT TR_TYPE
        ,TR_DESCRIPTION
    INTO :DCL-TR-TYPE
        ,:DCL-TR-DESCRIPTION
    FROM CARDDEMO.TRANSACTION_TYPE
   WHERE TR_TYPE = :DCL-TR-TYPE
  ```
  with DCL-TR-TYPE = NEW type. SQLCODE 0 -> OLD type = TR_TYPE, OLD description = the first
  DCL-TR-DESCRIPTION-LEN characters of the VARCHAR text, state `S`. Any other SQLCODE -> state `X`
  (ERR-COTRTUPC-01, -02). Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:984-997 (2000-DECIDE-ACTION)`,
  `:1447-1527 (9000-READ-TRANTYPE, 9100-GET-TRANSACTION-TYPE, 9500-STORE-FETCHED-DATA)`.
- **BR-COTRTUPC-11**: When state = not fetched and the key is not valid (blank or invalid type), no lookup is made
  and the state stays not fetched. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:998-1008 (2000-DECIDE-ACTION)`.
- **BR-COTRTUPC-12**: PF12 cancel (main step 7; the key flag is never valid on this path, Q-COTRTUPC-16): state
  `9` -> message "Delete was cancelled", state not fetched (empty prompt screen, type unprotected); state `N` ->
  message "Update was cancelled", state `B` (old values redisplayed, description unprotected, info "Update
  transaction type details shown."); any other state -> not fetched. No re-read from Db2. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:524-533 (0000-MAIN)`, `:988-1010 (2000-DECIDE-ACTION)`.
- **BR-COTRTUPC-13**: Other 2000 transitions: state `9` with PF12 -> stays `9` (unreachable, the PF12 WHEN
  matches first); state `S` -> becomes `N` unless there is an input error, the "No change detected" message, or
  the WS-INVALID-KEY message (in practice `S` arrives here only with no change, so it stays `S`); `E` stays
  `E`; `B` -> `E`; `K` stays `K`; PF5 with `X` -> `R` (unreachable, handled in main); `N` stays `N`; `C` -> `S`
  and, when CDEMO-FROM-TRANID is blank, CDEMO-ACCT-ID and CDEMO-CARD-NUM = zero and CDEMO-ACCT-STATUS = LOW-VALUES
  (unreachable, `C` is reset by BR-COTRTUPC-03). Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1016-1072 (2000-DECIDE-ACTION)`.
- **BR-COTRTUPC-14**: PF4 in state `S` -> state `9`; the screen shows the old values, all fields protected, info
  "Delete this record ? Press F4 to confirm", `F4=Delete` and `F12=Cancel` shown and `ENTER=Process F3=Exit`
  hidden. The screen is not received. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:493-498 (0000-MAIN)`.
- **BR-COTRTUPC-15**: PF4 in state `9` -> state `8`, then
  ```sql
  DELETE FROM CARDDEMO.TRANSACTION_TYPE
   WHERE TR_TYPE = :DCL-TR-TYPE
  ```
  with DCL-TR-TYPE = OLD type (from the COMMAREA, not the screen). Outcomes in BR-COTRTUPC-28, ERR-COTRTUPC-07,
  -08. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:482-489 (0000-MAIN)`, `:1624-1663 (9800-DELETE-PROCESSING)`.
- **BR-COTRTUPC-16**: PF5 in state `X` -> state `R` (add mode) without receiving the screen; the NEW type kept
  from the lookup turn is shown protected, the description is unprotected with the cursor on it, info "Enter new
  transaction type details.". Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:503-508 (0000-MAIN)`.
- **BR-COTRTUPC-17**: PF5 in state `N` -> 9600-WRITE-PROCESSING without receiving the screen (values come from
  the COMMAREA NEW fields): DCL-TR-TYPE = NEW type, DCL-TR-DESCRIPTION-TEXT = TRIM(NEW description),
  DCL-TR-DESCRIPTION-LEN = 50 (BR-COTRTUPC-18), then
  ```sql
  UPDATE CARDDEMO.TRANSACTION_TYPE
     SET TR_DESCRIPTION = :DCL-TR-DESCRIPTION
   WHERE TR_TYPE = :DCL-TR-TYPE
  ```
  SQLCODE 0 -> `EXEC CICS SYNCPOINT`. SQLCODE +100 (no such row) -> 9700-INSERT-RECORD:
  ```sql
  INSERT INTO CARDDEMO.TRANSACTION_TYPE
             (TR_TYPE, TR_DESCRIPTION)
     VALUES ( :DCL-TR-TYPE
             ,:DCL-TR-DESCRIPTION)
  ```
  SQLCODE 0 -> SYNCPOINT. Other outcomes in ERR-COTRTUPC-03..06. The same path serves add (from `R`) and update
  (from `S`/`B`/`E`). Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:514-520 (0000-MAIN)`,
  `:1531-1620 (9600-WRITE-PROCESSING, 9700-INSERT-RECORD)`.
- **BR-COTRTUPC-18**: The stored description is the trimmed text left-justified in 50 characters with VARCHAR
  length 50 (FUNCTION LENGTH of the X(50) field), so trailing spaces are stored (same as COTRTLIC,
  Q-COTRTLIC-10). Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1538-1542 (9600-WRITE-PROCESSING)`.
- **BR-COTRTUPC-19**: State after a save attempt: IF WS-RETURN-MSG = "Could not lock record for update" ->
  `L`; IF WS-RETURN-MSG = "Update of record failed" -> `F` (never true, Q-COTRTUPC-02); IF WS-RETURN-MSG =
  "Record changed by some one else. Please review" -> `S` (never set); OTHERWISE `C`. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1580-1589 (9600-WRITE-PROCESSING)`.
- **BR-COTRTUPC-20**: Field values sent (3200): context = enter -> none (empty fields). Otherwise by state:
  not fetched -> empty; `S`, `9`, `6`, `7`, `B` -> NEW cleared, OLD type and OLD description shown; `E`, `N`,
  `L`, `F`, `X`, `K`, `R`, `C` -> NEW type and NEW description shown; any other (`8`) -> NEW cleared, OLD shown.
  Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1140-1201 (3200-SETUP-SCREEN-VARS, 3201, 3202, 3203)`.
- **BR-COTRTUPC-21**: Info line text (3250, first match): context = enter -> "Enter transaction type to be
  maintained"; not fetched or `K` -> "Enter transaction type to be maintained"; `X` -> "Press F05 to add. F12 to
  cancel"; `S`, or `B` with blank OLD type -> "Enter transaction type to be maintained" (Q-COTRTUPC-18); `B` or
  `E` -> "Update transaction type details shown."; `9` -> "Delete this record ? Press F4 to confirm"; `6` ->
  "Changes unsuccessful"; `7` -> "Delete successful."; `R` -> "Enter new transaction type details."; `N` ->
  "Changes validated.Press F5 to save"; `C` -> "Changes committed to database"; `L` or `F` -> "Changes
  unsuccessful"; info still blank (`8`) -> "Enter transaction type to be maintained". The error line shows
  WS-RETURN-MSG. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1210-1247, 1264 (3250-SETUP-INFOMSG)`.
- **BR-COTRTUPC-22**: The info text (X(40)) is centered in a 40-character work field: start = (40 - trimmed
  length) / 2 + 1 (integer), then moved to the 45-character INFOMSG, so it is centered on 40 columns plus five
  trailing blanks. INFOMSG is bright (ASKIP+BRT) when text is present, dark otherwise. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1251-1262 (3250-SETUP-INFOMSG)`, `:1386-1392 (3390-SETUP-INFOMSG-ATTRS)`.
- **BR-COTRTUPC-23**: Protection (3300): start with type, description, and info protected with FSET
  (`DFHBMPRF`). Then: not fetched, `K`, `X`, or (`B` with blank OLD type) -> type unprotected with FSET
  (`DFHBMFSE`); `S`, `E`, `R`, `B` -> description unprotected with FSET; `N`, `C`, `9`/`8`/`7`/`6` -> all
  protected; any other (`L`, `F`) -> type unprotected. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1272-1298 (3300-SETUP-SCREEN-ATTRS)`, `:1368-1381 (3310, 3320)`.
- **BR-COTRTUPC-24**: Cursor (first match): not fetched, `X`, `K`, type flag invalid or blank, `C`, (`B` with
  blank OLD type) -> type; `R`, the "No change detected" message, description invalid or blank, `E`/`N`/`L`/`F`,
  `B`, `S` -> description; otherwise (`9`, `8`, `7`, `6`) -> type. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1303-1325 (3300-SETUP-SCREEN-ATTRS)`.
- **BR-COTRTUPC-25**: Colors: type red when the type flag is "not OK" (invalid, not found, or read error) or the
  state is `6`; when the type flag is blank and context = re-enter, `*` is shown in the type field in red. When
  the state is not fetched, `X`, or `K`, or the type flag is blank or invalid, the description attributes are left
  as is; otherwise CSSETATY: when context = re-enter and the description flag is "not OK" or blank, the
  description is red, and if blank it shows `*`. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1331-1361 (3300-SETUP-SCREEN-ATTRS)`, `app/cpy/CSSETATY.cpy:17-27`.
- **BR-COTRTUPC-26**: PF-key labels (3391): `ENTER=Process F3=Exit` dark in `9`, bright otherwise; `F4=Delete`
  bright in `S`, `9`; `F5=Save` bright in `N`, `X`; `F12=Cancel` bright in `N`, `S`, `X`, `9`, `R`; `F6=Add`
  always dark. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1397-1423 (3391-SETUP-PFKEY-ATTRS)`.
- **BR-COTRTUPC-27**: COMMON-RETURN: CCARD-ERROR-MSG = WS-RETURN-MSG; CARDDEMO-COMMAREA and the extension are
  placed in WS-COMMAREA; `EXEC CICS RETURN TRANSID('CTTU') COMMAREA(WS-COMMAREA) LENGTH(2000)`. FROM fields
  and last map/mapset are not changed. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:559-571 (COMMON-RETURN)`.
- **BR-COTRTUPC-28**: DELETE SQLCODE 0 -> state `7`, `EXEC CICS SYNCPOINT`; the screen shows the deleted row's
  old values, all protected, info "Delete successful.". The next key resets to the prompt (BR-COTRTUPC-03).
  Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1634-1637 (9800-DELETE-PROCESSING)`.

### Validation rules
- **VR-COTRTUPC-01**: Transaction type must be supplied (not LOW-VALUES, spaces, `*`, or empty after TRIM);
  otherwise message "Tran Type code must be supplied.", state not fetched, type field shows `*` in red, cursor on
  type. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:907-930 (1245-EDIT-NUM-REQD)`, `:720-726`, `:1336-1340`.
- **VR-COTRTUPC-02**: Transaction type must pass FUNCTION TEST-NUMVAL (digits with optional leading/trailing
  spaces, sign, or decimal point); otherwise "Tran Type code must be numeric.", state not fetched, type red,
  cursor on type. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:934-949 (1245-EDIT-NUM-REQD)`, `:728-732`.
- **VR-COTRTUPC-03**: Transaction type must not be zero (NUMVAL = 0); otherwise "Tran Type code must not be
  zero.", state not fetched, type red. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:954-969 (1245-EDIT-NUM-REQD)`.
- **VR-COTRTUPC-04**: When the type is blank and no message is set yet, "No input received" would be shown; it
  never is, because VR-COTRTUPC-01 always sets its message first (Q-COTRTUPC-15). Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:720-723 (1200-EDIT-MAP-INPUTS)`.
- **VR-COTRTUPC-05**: Description must be supplied (not LOW-VALUES, spaces, or empty); otherwise "Transaction Desc
  must be supplied.", state `E`, description shows `*` in red, cursor on description. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:849-873 (1230-EDIT-ALPHANUM-REQD)`.
- **VR-COTRTUPC-06**: Description may contain only letters `A-Z`, `a-z`, digits, and spaces (all others,
  including `-`, `/`, `.`, `&`, are rejected); otherwise "Transaction Desc can have numbers or alphabets only.",
  state `E`, description red. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:876-899 (1230-EDIT-ALPHANUM-REQD)`.
- **VR-COTRTUPC-07**: The edited values must differ from the fetched ones (BR-COTRTUPC-08); otherwise "No change
  detected with respect to values fetched." and the state is unchanged (`S` stays `S`, `B` becomes `E`). Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:783-812`, `:1023-1042`.
- **VR-COTRTUPC-08**: The looked-up type must exist; otherwise "No record found for this key in database", state
  `X`, type red and unprotected, info "Press F05 to add. F12 to cancel", `F5=Save` and `F12=Cancel` shown. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1489-1494 (9100-GET-TRANSACTION-TYPE)`.
- Only the first error message of a turn is kept (each edit tests WS-RETURN-MSG-OFF before setting its text).

### Navigation and keys
- **NAV-COTRTUPC-01**: Valid keys (0001-CHECK-PFKEYS): PF3 always; ENTER unless state `9`; PF4 in `S`, `9`; PF5 in
  `N`, `X`, `9`, `8`, `7`, `6`; PF12 in `N`, `S`, `X`, `9`, `R`. Any other key (including CLEAR, PA1, PA2, PF1,
  PF2, PF6-PF11) -> message "Invalid key pressed" (if no message is set) and the current screen is resent with
  the state unchanged and no input received. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:577-608 (0001-CHECK-PFKEYS)`, `:539-542 (0000-MAIN)`.
- **NAV-COTRTUPC-02**: PF3 (any state) -> CDEMO-TO-TRANID = CDEMO-FROM-TRANID, or `CA00` when blank;
  CDEMO-TO-PROGRAM = CDEMO-FROM-PROGRAM, or `COADM01C` when blank; then FROM = `CTTU`/`COTRTUPC`,
  CDEMO-USRTYP-ADMIN, context = enter, last mapset/map = `COTRTUP `/`CTRTUPA`; `EXEC CICS SYNCPOINT`; XCTL
  CDEMO-TO-PROGRAM with CARDDEMO-COMMAREA (160 bytes). From COADM01C this returns to the admin menu; from
  COTRTLIC it returns to the list, which restarts on page 1 (BR-COTRTLIC-02); from a direct CTTU start it goes
  to COADM01C with a blank user. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:429-460 (0000-MAIN)`.
- **NAV-COTRTUPC-03**: PF4 -> request delete in `S` (BR-COTRTUPC-14), confirm delete in `9` (BR-COTRTUPC-15).
- **NAV-COTRTUPC-04**: PF5 -> add mode in `X` (BR-COTRTUPC-16), save in `N` (BR-COTRTUPC-17). PF5 in `9` or
  `8` goes to the OTHERWISE path and abends (Q-COTRTUPC-06, -07); in `7`/`6` the reset applies first.
- **NAV-COTRTUPC-05**: PF12 -> back to the prompt in `S`, `R`, `X` (BR-COTRTUPC-03); cancel delete in `9`
  and cancel update in `N` (BR-COTRTUPC-12).
- **NAV-COTRTUPC-06**: ENTER -> receive, edit, and decide (processing flow step 5-6): look up a type, validate
  changes, or re-show the current state.
- **NAV-COTRTUPC-07**: PF13-PF24 act as PF1-PF12 (`app/cpy/CSSTRPFY.cpy:17-79`).

### Error handling
- **ERR-COTRTUPC-01**: SELECT SQLCODE +100 -> input error, type flag "not OK", message "No record found for this key
  in database", state `X`. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1489-1494 (9100-GET-TRANSACTION-TYPE)`.
- **ERR-COTRTUPC-02**: SELECT SQLCODE < 0 -> input error, type flag "not OK", message "Error accessing:
  TRANSACTION_TYPE table. SQLCODE:" + SQLCODE (`----9`) + ":" + SQLERRM (2-byte length + 70 bytes), truncated to
  75 characters; state `X`, so the user is offered F5 to add (Q-COTRTUPC-09). A positive SQLCODE other than +100
  (warning) is treated as not found without a message. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1495-1509 (9100-GET-TRANSACTION-TYPE)`, `:993-997`.
- **ERR-COTRTUPC-03**: UPDATE SQLCODE -911 (deadlock or timeout, rolled back) -> message "Could not lock record for
  update", state `L`, info "Changes unsuccessful"; the next key returns to the prompt. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1561-1566, 1581-1582 (9600-WRITE-PROCESSING)`.
- **ERR-COTRTUPC-04**: UPDATE SQLCODE < 0 (other than -911) -> WS-RETURN-MSG is first set to "Update of record
  failed" and then overwritten from position 1 by "Error updating: TRANSACTION_TYPE Table. SQLCODE:" + SQLCODE +
  ":" + SQLERRM (truncated at 75); because the text no longer equals the 88-level value, the state becomes `C`
  and the info line says "Changes committed to database" (Q-COTRTUPC-02). No SYNCPOINT or ROLLBACK is issued.
  Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1567-1577, 1583-1588 (9600-WRITE-PROCESSING)`.
- **ERR-COTRTUPC-05**: INSERT SQLCODE <> 0 (for example -803 duplicate after a concurrent insert) -> message "Error
  inserting record into: TRANSACTION_TYPE Table. SQLCODE:" + the stale UPDATE SQLCODE (`  100`) + ":" + SQLERRM
  of the INSERT (Q-COTRTUPC-04); state `C`, info "Changes committed to database" (Q-COTRTUPC-02). Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1604-1619 (9700-INSERT-RECORD)`.
- **ERR-COTRTUPC-06**: UPDATE with a positive SQLCODE other than +100 matches no WHEN: no SYNCPOINT, state `C`; the
  change is committed when the task ends. Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1555-1588`.
- **ERR-COTRTUPC-07**: DELETE SQLCODE -532 (child rows in TRANSACTION_TYPE_CATEGORY, FK ON DELETE RESTRICT) ->
  message "Please delete associated child records first:SQLCODE :" + SQLCODE + ":" + SQLERRM + SQLERRM
  (truncated at 75); the state stays `8` (Q-COTRTUPC-06): old values shown, all fields protected, info "Enter
  transaction type to be maintained", only `ENTER=Process F3=Exit` shown. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1638-1649 (9800-DELETE-PROCESSING)`.
- **ERR-COTRTUPC-08**: DELETE any other SQLCODE (including +100 when another user already deleted the row, and
  negative codes) -> message "Delete failed with message:SQLCODE :" + SQLCODE + ":" + SQLERRM; state `6`, info
  "Changes unsuccessful", type red; the next key returns to the prompt. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1650-1661 (9800-DELETE-PROCESSING)`.
- **ERR-COTRTUPC-09**: 2000-DECIDE-ACTION with an unexpected state -> ABEND-CULPRIT = `COTRTUPC`, ABEND-CODE =
  `0001`, ABEND-REASON = spaces, ABEND-MSG = "UNEXPECTED DATA SCENARIO", then ABEND-ROUTINE (ERR-COTRTUPC-10).
  Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1073-1080 (2000-DECIDE-ACTION)`.
- **ERR-COTRTUPC-10**: ABEND-ROUTINE (also the HANDLE ABEND label for any abend in the task): IF ABEND-MSG =
  LOW-VALUES move "UNEXPECTED ABEND OCCURRED." (never true, the field starts as spaces, Q-COTRTUPC-08);
  ABEND-CULPRIT = `COTRTUPC`; ABEND-CODE = `9999`; `SEND FROM(ABEND-DATA)` (134 bytes: code, culprit, reason,
  message) `NOHANDLE ERASE`; HANDLE ABEND CANCEL; `ABEND ABCODE('9999')`. The transaction is backed out
  (`ACTION(BACKOUT)`); the terminal is left without a CTTU RETURN. Source:
  `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:348-350`, `:1675-1698 (ABEND-ROUTINE)`, `app/cpy/CSMSG02Y.cpy`.
- **ERR-COTRTUPC-11**: RECEIVE MAP and SEND MAP use RESP but the response is never tested (MAPFAIL and similar
  conditions are ignored). Source: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:642-647`, `:1433-1440`.
- **ERR-COTRTUPC-12**: Db2 connection or plan errors (for example -805, -818, -922, -924) surface only as SQLCODE
  < 0 through ERR-COTRTUPC-02/-04/-05/-08; there is no priming query (unlike COTRTLIC, ERR-COTRTLIC-01).

## Data effects
- Read only, except:
  - PF5 in `N`: one UPDATE of `TR_DESCRIPTION` by `TR_TYPE`; when it updates no row, one INSERT of (`TR_TYPE`,
    `TR_DESCRIPTION`). `EXEC CICS SYNCPOINT` after a successful UPDATE or INSERT. The values come from the
    COMMAREA NEW fields captured on the previous ENTER, not from the screen.
  - PF4 in `9`: one DELETE by the OLD type; SYNCPOINT on success. The FK of ENT-DB2-TRAN-TYPE-CATEGORY
    (`ON DELETE RESTRICT`) makes Db2 reject the delete with -532 while category rows exist; the program never
    deletes categories (ERR-COTRTUPC-07).
- There is no read-for-update or version check between the read and the write (Q-COTRTUPC-17). PF3 takes a
  SYNCPOINT before XCTL; every turn ends the task, which commits any open unit of work. An abend backs out.
- The tablespace is `LOCKSIZE TABLE` (JOB-CREADB21), so a write locks the whole table until the SYNCPOINT, and a
  long JOB-MNTTRDB2 run (COBTUPDT, no intermediate commits, Q-COBTUPDT-03) makes the UPDATE wait and end with
  -911 (ERR-COTRTUPC-03).
- Relationship to batch and VSAM: the batch report CBTRN03C and the base online programs use the VSAM file
  TRANTYPE (ENT-TRAN-TYPE) and TRANCATG (ENT-TRAN-CATEGORY), not these tables. Changes reach the VSAM files only
  after JOB-TRANEXTR unloads the tables to `TRANTYPE.PS` / `TRANCATG.PS` (fixed 60-byte layout `CVTRA03Y`, the
  description cast to CHAR(50)) and the VSAM files are reloaded by JOB-TRANTYPE / JOB-TRANCATG (Q-TRANEXTR-01).
  Batch changes to the same table come from JOB-MNTTRDB2 (COBTUPDT).
- Admin menu wiring (COADM02Y): option 5 = COTRTLIC (CTLI, list/update/delete), option 6 = COTRTUPC (CTTU, this
  program). Both rely on the DB2ENTRY `CARDDEMO` with `THREADLIMIT(1)` and `THREADWAIT(YES)`, so concurrent
  CTLI/CTTU users queue for one Db2 thread.
- Modern equivalent: `GET /transaction-types/{code}`, `PUT /transaction-types/{code}` (create or replace,
  with an explicit "create" vs "update" intent and optimistic version check), `DELETE /transaction-types/{code}`
  mapping the FK violation to HTTP 409; one `transaction_type` table shared by online and batch code, so no
  extract step.

## Quirks
- **Q-COTRTUPC-01**: Validation bypass on the not-found screen. In state `X`, 1150 stores the trimmed screen type
  into NEW type, and 1200 then finds "screen type = NEW type" true, so any changed, non-blank key is looked up
  as typed, without VR-COTRTUPC-02/-03 or zero padding (`5` is looked up as `5 `, `AB` as `AB`). If it is not
  found, PF5 and a description create a row with that key (state `R` skips the type edit), so non-numeric or
  unpadded type codes can be inserted. Recommendation: fix (always run the type edit before a lookup). Source:
  `COTRTUPC.cbl:654-673`, `:698-710`, `:712-714`.
- **Q-COTRTUPC-02**: Save failures are reported as success. On UPDATE < 0 (other than -911) and on any INSERT
  failure the code sets the 88-level "Update of record failed" and then STRINGs an SQL message into the same
  field, so the 88 test in 9600 fails and the state becomes `C` with info "Changes committed to database" (the
  red error line shows the SQL message). State `F` is never reached. Recommendation: fix (separate the state flag
  from the message text). Source: `COTRTUPC.cbl:1567-1589`, `:1607-1618`.
- **Q-COTRTUPC-03**: The SQL messages STRING `SQLERRM OF SQLCA`, a group of a 2-byte binary length plus 70
  characters, so two non-printable bytes appear in the message; the -532 message appends SQLERRM twice; all are
  cut at 75 characters, so the Db2 tokens are often lost. Recommendation: fix (use SQLERRMC(1:SQLERRML) or
  DSNTIAC as COTRTLIC does). Source: `COTRTUPC.cbl:1499-1507`, `:1569-1577`, `:1640-1649`, `:1653-1661`.
- **Q-COTRTUPC-04**: 9700-INSERT-RECORD does not move the INSERT's SQLCODE to WS-DISP-SQLCODE, so the message
  shows `100` (from the UPDATE) instead of the INSERT error code. Recommendation: fix. Source: `COTRTUPC.cbl:1596-1619`.
- **Q-COTRTUPC-05**: The entry test (`EIBCALEN = 0 OR (CDEMO-FROM-PROGRAM = 'COADM01C' AND NOT CDEMO-PGM-REENTER)
  OR (CDEMO-FROM-PROGRAM = 'COTRTLIC' AND NOT CDEMO-PGM-REENTER)`) reads CARDDEMO-COMMAREA in WORKING-STORAGE
  before the COMMAREA is copied into it, so on a new task the two caller conditions see uninitialized storage
  and are false. In practice only EIBCALEN = 0 initializes the COMMAREA; for callers the copy is made and the
  first-entry WHEN (BR-COTRTUPC-02) does the intended reset, keeping the signed-on user and the FROM fields, so PF3
  returns to the actual caller (COADM01C or COTRTLIC). On a direct `CTTU` start the COMMAREA is initialized, the
  user ID and type are blank, and PF3 goes to COADM01C with that blank user. Recommendation: fix (test
  DFHCOMMAREA after the copy; reject a direct start without a signed-on user). Source: `COTRTUPC.cbl:366-381`,
  `:429-443`.
- **Q-COTRTUPC-06**: After a DELETE that fails with -532 the state stays `8` (it is neither reset nor turned into
  `6`). The next ENTER (or PF5, which 0001 accepts for `8`) goes through 1000 and 2000, where `8` matches no WHEN,
  and the task abends with ABEND-ROUTINE (`9999`, "UNEXPECTED DATA SCENARIO"). Only PF3 leaves cleanly.
  Recommendation: fix (set `6`). Source: `COTRTUPC.cbl:1638-1649`, `:1073-1080`, `:588-593`.
- **Q-COTRTUPC-07**: PF5 is accepted in state `9` (0001 lists TTUP-DELETE-IN-PROGRESS) but has no handler; it takes
  the OTHERWISE path, the displayed values equal the old ones (no change), 2000 finds state `9` with no WHEN and
  the task abends. The `F5=Save` label is dark in `9`, so this needs a deliberate key press. Recommendation: fix
  (treat as invalid key). Source: `COTRTUPC.cbl:588-593`, `:548-555`, `:1073-1080`.
- **Q-COTRTUPC-08**: ABEND-ROUTINE overwrites ABEND-CODE with `9999`, so the `0001` set for "UNEXPECTED DATA
  SCENARIO" never appears; and it tests ABEND-MSG for LOW-VALUES while CSMSG02Y initializes it to spaces, so the
  default text "UNEXPECTED ABEND OCCURRED." is never used and a CICS abend shows an empty message.
  Recommendation: fix. Source: `COTRTUPC.cbl:1074-1079`, `:1677-1682`.
- **Q-COTRTUPC-09**: A Db2 error on the lookup is treated like "not found": state `X`, info "Press F05 to add. F12
  to cancel", and F5 leads to an add that will most likely fail the same way. Recommendation: fix (separate error
  state). Source: `COTRTUPC.cbl:993-997`, `:1495-1508`.
- **Q-COTRTUPC-10**: No authorization: CDEMO-USRTYP is never checked; a direct `CTTU` start works without a
  sign-on; entry from COTRTLIC PF2 arrives with user type `U` and is not refused; PF3 always sets user type
  `A` (admin) before returning. Recommendation: fix (enforce the admin role). Source: `COTRTUPC.cbl:366-381`, `:448`.
- **Q-COTRTUPC-11**: Change detection is case-insensitive, so changing only the letter case of a description
  (for example `PURCHASE` to `Purchase`) is rejected with "No change detected with respect to values fetched.".
  Recommendation: decide (the Db2 seed is upper case, the VSAM sample is mixed case, Q-CREADB21-04). Source:
  `COTRTUPC.cbl:786-797`.
- **Q-COTRTUPC-12**: The description is stored with trailing spaces (VARCHAR length always 50, BR-COTRTUPC-18);
  COTRTLIC and COBTUPDT do the same. Recommendation: decide (store trimmed in the target). Source: `COTRTUPC.cbl:1541-1542`.
- **Q-COTRTUPC-13**: The description edit allows only letters, digits, and spaces, while COBTUPDT accepts any text;
  the same table can therefore hold descriptions that this screen cannot save back unchanged (the edit runs on
  any change). Recommendation: decide. Source: `COTRTUPC.cbl:876-899`.
- **Q-COTRTUPC-14**: The type edit uses TEST-NUMVAL/NUMVAL, so signs and decimal points pass: `-1` and `+1`
  become `01`, `1.` becomes `01`, `.5` passes "not zero" and becomes `00`, which is then looked up and can be added.
  Recommendation: fix (accept only one or two digits, 01-99). Source: `COTRTUPC.cbl:934-969`, `:834-842`.
- **Q-COTRTUPC-15**: Dead or unreachable logic: state `K` is set and immediately overwritten by "not fetched"
  (`:729-730`), so every `K` branch is dead; state `V` is never set; 2000 branches for `9`+PF12, PF5+`X`, and `C`
  are unreachable; "No input received" is never shown; the `F` state, `DATA-WAS-CHANGED-BEFORE-UPDATE`, and
  messages "Selected transaction type shown above", "PF03 pressed.Exiting", "Invalid Key pressed. ", "Name can
  only contain alphabets and spaces", "Delete of record failed" (set but overwritten), and "Looks Good.... so far"
  are never displayed; `F6=Add` is never shown; DCLTRCAT, CSUTLDWY, TTYP-UPDATE-RECORD, and
  CICS-OUTPUT-EDIT-VARS (except two work fields) are unused. Recommendation: drop in the target.
- **Q-COTRTUPC-16**: Because WS-MISC-STORAGE is INITIALIZEd to spaces each turn, FLG-TRANFILTER-ISVALID (LOW-VALUES)
  is false whenever 1200 did not run. On the PF12 path 1200 never runs, so the "re-read the record on cancel"
  branch of 2000 is dead and a cancelled update redisplays the old values from the COMMAREA, not from Db2.
  Recommendation: preserve the visible behavior; drop the dead branch. Source: `COTRTUPC.cbl:352-354`, `:988-997`.
- **Q-COTRTUPC-17**: No optimistic locking: the save overwrites whatever is in the table without comparing it to
  the values read; if another user deleted the row in between, UPDATE returns +100 and the program silently
  re-inserts it. Recommendation: fix (version or compare-and-set; distinct create vs update). Source:
  `COTRTUPC.cbl:1544-1560`.
- **Q-COTRTUPC-18**: In state `S` (a row is shown and editable) the info line says "Enter transaction type to be
  maintained" instead of the unused "Selected transaction type shown above" or "Update transaction type details
  shown.". Recommendation: fix. Source: `COTRTUPC.cbl:1221-1225`.
- **Q-COTRTUPC-19**: After a cancelled add (`B` with blank old type), and after `C`, `L`, `F`, `7`, `6`, the next
  turn is reset before the screen is read, so a type typed into the (unprotected) field after "Update was
  cancelled" is ignored and the empty prompt is shown again. After "Delete successful." the deleted row stays on
  the screen until the next key. Recommendation: fix. Source: `COTRTUPC.cbl:405-419`, `:1176-1179`.
- **Q-COTRTUPC-20**: 3201-SHOW-INITIAL-VALUES moves LOW-VALUES to TRTYPCDO twice (the second was meant for the
  description); harmless because 3100 already cleared the whole output map. Recommendation: fix (cosmetic).
  Source: `COTRTUPC.cbl:1176-1179`.
- **Q-COTRTUPC-21**: Protected fields are sent with FSET and read back every turn, and in states `S`/`B`/`E` the
  NEW type comes from the returned (protected) type field, not from the COMMAREA. A modified 3270 data stream
  could change the key and make PF5 overwrite a different row. The delete uses the COMMAREA OLD type.
  Recommendation: fix (take the key from server-side state). Source: `COTRTUPC.cbl:667-673`, `:1369-1370`.
- **Q-COTRTUPC-22**: First entry sets CDEMO-ACCT-ID to zero, clearing account context that has nothing to do with
  transaction types. Recommendation: decide (probably copied from the account update template). Source:
  `COTRTUPC.cbl:471-473`.

## Test scenarios
Unless stated otherwise: Db2 loaded by JOB-CREADB21 with the 7 seed types (`01 PURCHASE`, `02 PAYMENT`,
`03 CREDIT`, `04 AUTHORIZATION`, `05 REFUND`, `06 REVERAL`, `07 ADJUSTMENT`) and the 18 seed categories (each type
01-07 has at least one), user `ADMIN001` on the admin menu.
- **TS-COTRTUPC-01** (covers BR-COTRTUPC-02, -21, -23, -26): Given the admin menu, when option 6 is chosen, then
  the screen shows empty type and description, info "Enter transaction type to be maintained", only
  `ENTER=Process F3=Exit`, cursor on the type field, and the COMMAREA keeps user `ADMIN001` with FROM `CA00`/`COADM01C`.
- **TS-COTRTUPC-02** (covers BR-COTRTUPC-05, -10, -20, -21, -26): Given TS-01, when `1` is entered, then type `01`
  and description `PURCHASE` are shown, state `S`, description unprotected with cursor, `F4=Delete` and
  `F12=Cancel` shown, info "Enter transaction type to be maintained".
- **TS-COTRTUPC-03** (covers VR-COTRTUPC-01, BR-COTRTUPC-25): Given TS-01, when ENTER is pressed with the type
  blank, then message "Tran Type code must be supplied.", type field shows `*` in red, state not fetched.
- **TS-COTRTUPC-04** (covers VR-COTRTUPC-02): Given TS-01, when `A1` is entered, then "Tran Type code must be
  numeric." with the type red.
- **TS-COTRTUPC-05** (covers VR-COTRTUPC-03): Given TS-01, when `00` is entered, then "Tran Type code must not be zero.".
- **TS-COTRTUPC-06** (covers VR-COTRTUPC-08, ERR-COTRTUPC-01, BR-COTRTUPC-26): Given TS-01, when `15` is entered,
  then "No record found for this key in database", info "Press F05 to add. F12 to cancel", type red and
  unprotected, `F5=Save` and `F12=Cancel` shown, state `X`.
- **TS-COTRTUPC-07** (covers BR-COTRTUPC-16, -09, -17, -18, -19): Given TS-06, when PF5 is pressed, then info "Enter
  new transaction type details." with `15` protected; when `INTEREST` is typed and ENTER pressed, then "Changes
  validated.Press F5 to save" with all fields protected; when PF5 is pressed, then the UPDATE returns +100, the
  INSERT adds (`15`, `INTEREST` padded to 50), a SYNCPOINT is taken, info "Changes committed to database".
- **TS-COTRTUPC-08** (covers BR-COTRTUPC-08, -09, -17): Given TS-02, when the description is changed to `PURCHASES`
  and ENTER pressed, then state `N` and "Changes validated.Press F5 to save"; when PF5 is pressed, then
  `TR_DESCRIPTION` of `01` is `PURCHASES` (padded) and info "Changes committed to database".
- **TS-COTRTUPC-09** (covers VR-COTRTUPC-07, Q-COTRTUPC-11): Given TS-02, when the description is changed to
  `Purchase` and ENTER pressed, then message "No change detected with respect to values fetched." and state `S`.
- **TS-COTRTUPC-10** (covers VR-COTRTUPC-05): Given TS-02, when the description is blanked and ENTER pressed, then
  "Transaction Desc must be supplied.", the description shows `*` in red, info "Update transaction type details shown.", state `E`.
- **TS-COTRTUPC-11** (covers VR-COTRTUPC-06): Given TS-02, when the description is `PURCHASE-RETAIL`, then
  "Transaction Desc can have numbers or alphabets only." and state `E`.
- **TS-COTRTUPC-12** (covers BR-COTRTUPC-12, BR-COTRTUPC-13): Given TS-08 before PF5 (state `N`), when PF12 is
  pressed, then message "Update was cancelled", the original `PURCHASE` is shown with the description
  unprotected, info "Update transaction type details shown.", and Db2 is unchanged.
- **TS-COTRTUPC-13** (covers BR-COTRTUPC-14, ERR-COTRTUPC-07, Q-COTRTUPC-06): Given TS-02, when PF4 is pressed, then
  info "Delete this record ? Press F4 to confirm" and `ENTER=Process F3=Exit` is hidden; when PF4 is pressed again,
  then Db2 returns -532 and the message starts "Please delete associated child records first:SQLCODE : -532:";
  when ENTER is then pressed, the task abends with code `9999` and text "UNEXPECTED DATA SCENARIO".
- **TS-COTRTUPC-14** (covers BR-COTRTUPC-15, -28, -03): Given type `15` added in TS-07 with no categories, when it is
  looked up and PF4 pressed twice, then the row is deleted, SYNCPOINT taken, info "Delete successful." with `15`
  still displayed; when ENTER is pressed, the empty prompt is shown.
- **TS-COTRTUPC-15** (covers BR-COTRTUPC-12): Given TS-13 after the first PF4 (state `9`), when PF12 is pressed,
  then message "Delete was cancelled" and the empty prompt with the type unprotected.
- **TS-COTRTUPC-16** (covers NAV-COTRTUPC-01): Given TS-13 after the first PF4, when ENTER is pressed, then message
  "Invalid key pressed" and the delete confirmation is shown again.
- **TS-COTRTUPC-17** (covers NAV-COTRTUPC-02): Given TS-02, when PF3 is pressed, then SYNCPOINT and XCTL to
  COADM01C with FROM `CTTU`/`COTRTUPC`, user type `A`, context 0, user `ADMIN001` kept.
- **TS-COTRTUPC-18** (covers NAV-COTRTUPC-02, Q-COTRTUPC-10): Given COTRTLIC page 1, when PF2 and then PF3 are
  pressed, then control returns to COTRTLIC with FROM-TRANID `CTTU`, and COTRTLIC restarts on page 1.
- **TS-COTRTUPC-19** (covers BR-COTRTUPC-06, Q-COTRTUPC-01): Given TS-06 (state `X`, NEW type `15`), when `AB` is
  typed in the type field and ENTER pressed, then `AB` is looked up without the numeric edit and "No record found
  for this key in database" is shown; PF5, a description, ENTER, and PF5 then insert type `AB`.
- **TS-COTRTUPC-20** (covers ERR-COTRTUPC-03): Given JOB-MNTTRDB2 is running and holds the table lock, when an
  update is saved with PF5, then message "Could not lock record for update", info "Changes unsuccessful"; the
  next ENTER shows the empty prompt.
- **TS-COTRTUPC-21** (covers ERR-COTRTUPC-04, Q-COTRTUPC-02): Given an UPDATE that fails with a negative SQLCODE
  other than -911 (for example the table is in check-pending), when PF5 is pressed in state `N`, then the error line
  starts "Error updating: TRANSACTION_TYPE Table. SQLCODE:" and the info line says "Changes committed to database".
- **TS-COTRTUPC-22** (covers BR-COTRTUPC-01, Q-COTRTUPC-05): Given no COMMAREA, when `CTTU` is typed on a clear
  screen, then the empty prompt is shown; PF3 then XCTLs to COADM01C with a blank user ID.
- **TS-COTRTUPC-23** (covers ERR-COTRTUPC-08): Given TS-02 and another user deletes type `01`'s categories and the
  type itself between the two PF4 presses, when PF4 is pressed, then Db2 returns +100, message "Delete failed with
  message:SQLCODE :  100:", info "Changes unsuccessful", type red.
- **TS-COTRTUPC-24** (covers Q-COTRTUPC-14): Given TS-01, when `-3` is entered, then type `03 CREDIT` is shown.

## Open questions
- Which SQLCODE does the FK violation return on the target Db2 level: -532 (expected for RESTRICT) or another code?
  Any other code goes to ERR-COTRTUPC-08 instead of ERR-COTRTUPC-07.
- Should add and update be separate operations (today PF5 always tries UPDATE first and silently inserts)?
- Should the description character rule (letters, digits, spaces) apply to all writers, including the batch
  COBTUPDT and the category table?
- Is the CICS WORKING-STORAGE content before the COMMAREA copy guaranteed to be non-matching (Q-COTRTUPC-05)?
  This depends on compiler options (for example WSCLEAR / STORAGE) on the target system.
- What bind options (isolation, lock timeout) does plan CARDDEMO use? They determine how often -911 occurs.
