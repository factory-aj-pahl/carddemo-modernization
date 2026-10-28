# COTRTLIC: Paged, filterable list of Db2 transaction types with in-line update and delete

| Attribute | Value |
|:--|:--|
| Source | `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` (2098 lines) |
| Module | app-transaction-type-db2 |
| Kind | Online (CICS), pseudo-conversational, embedded Db2 SQL |
| Transaction ID / Job | CTLI (program `app/app-transaction-type-db2/csd/CRDDEMOD.csd:11-17`, transaction `:25-34`; DB2TRAN `CTLITRAN` -> DB2ENTRY `CARDDEMO`, `PLAN(CARDDEMO)`, `AUTHTYPE(USERID)`, `THREADLIMIT(1)`, `THREADWAIT(YES)`, `:45-55`) |
| BMS map | mapset `COTRTLI` / map `CTRTLIA` (`app/app-transaction-type-db2/bms/COTRTLI.bms`, symbolic map `app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy`); the program redefines the row area as two 7-row arrays (`COTRTLIC.cbl:434-478`) |
| Copybooks | `COCOM01Y` (CARDDEMO-COMMAREA), `CVCRD01Y` (CC-WORK-AREA, CCARD-AID-*, CCARD-NEXT-*), `COTTL01Y`, `COTRTLI`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y`, `CVACT02Y` (included, unused), `DFHBMSCA`, `DFHAID`, `CSSTRPFY` (YYYY-STORE-PFKEY); SQL INCLUDE `SQLCA`, `DCLTRTYP` (`app/app-transaction-type-db2/dcl/DCLTRTYP.dcl`), `CSDB2RWY`, `CSDB2RPY` (`app/app-transaction-type-db2/cpy/`) |
| Calls / XCTL targets | CALL `DSNTIAC` (in 9999-FORMAT-DB2-MESSAGE); XCTL `COTRTUPC` (PF2); XCTL CDEMO-FROM-PROGRAM or `COADM01C` (PF3) |
| Data stores | Db2 `CARDDEMO.TRANSACTION_TYPE` (ENT-DB2-TRAN-TYPE): Browse via cursors `C-TR-TYPE-FORWARD` / `C-TR-TYPE-BACKWARD`, `SELECT COUNT(1)`, Update (`TR_DESCRIPTION` by `TR_TYPE`), Delete (by `TR_TYPE`); `SYSIBM.SYSDUMMY1` (priming query). No VSAM access. |

## Purpose
COTRTLIC is the administrator's list screen for the Db2 reference table of transaction types (type
code `01`-`99` and description). It shows seven types per page in type-code order, can filter by an
exact two-digit type code and by a description substring, and pages forward (PF8) and backward (PF7).
On any listed row the administrator can type `D` and confirm with PF10 to delete the type, or type `U`,
edit the description in place, and press PF10 to save it. PF2 opens the single-record maintenance
screen COTRTUPC and PF3 returns to the admin menu. The Db2 table is separate from the VSAM file
TRANTYPE that the batch report CBTRN03C reads; changes made here reach batch only through
JOB-TRANEXTR and a VSAM reload (see Data effects).

## Invocation and state
- Entered by XCTL from COADM01C option 5 `Transaction Type List/Update (Db2) ` (`app/cpy/COADM02Y.cpy:46-49`,
  NAV-COADM01C-04) with CDEMO-PGM-CONTEXT = 0 and CDEMO-FROM-PROGRAM = `COADM01C`. When the module is not
  installed, COADM01C reports PGMIDERR (ERR-COADM01C-01).
- Can also be started by typing `CTLI` (EIBCALEN = 0). The program then initializes the COMMAREA itself and
  marks the user as an administrator (BR-COTRTLIC-01); no sign-on or user-type check is made (Q-COTRTLIC-05).
- The code also expects a return from COTRTUPC (PF3 with CDEMO-FROM-TRANID = `CTTU`, BR-COTRTLIC-02). This path
  is used after PF2: COTRTUPC keeps CDEMO-FROM-PROGRAM = `COTRTLIC` for its whole conversation, and its PF3
  XCTLs back here with FROM = `CTTU`/`COTRTUPC` (NAV-COTRTUPC-02).
- Each turn ends with `RETURN TRANSID('CTLI') COMMAREA(WS-COMMAREA) LENGTH(2000)`
  (`COTRTLIC.cbl:899-915 (COMMON-RETURN)`). WS-COMMAREA holds CARDDEMO-COMMAREA (160 bytes) followed by the
  program extension WS-THIS-PROGCOMMAREA (427 bytes, `COTRTLIC.cbl:377-418`); the rest of the 2000 bytes is
  spaces. Before returning, COMMON-RETURN sets CDEMO-FROM-TRANID = `CTLI`, CDEMO-FROM-PROGRAM = `COTRTLIC`,
  CDEMO-LAST-MAPSET = `COTRTLI`, CDEMO-LAST-MAP = `CTRTLIA`.

| Offset (0-based) | Field | Picture | Meaning |
|--:|:--|:--|:--|
| 0-159 | CARDDEMO-COMMAREA | `COCOM01Y` | Standard COMMAREA |
| 160 | WS-CA-TYPE-CD | X(02) | Type-code filter as last entered (raw screen value) |
| 162 | WS-CA-TYPE-DESC | X(50) | Description filter as last entered (raw screen value, without `%`) |
| 212 | WS-CA-ROW-TR-CODE-OUT(1..7) / WS-CA-ROW-TR-DESC-OUT(1..7) | 7 x (X(02) + X(50)) | Rows currently displayed (LOW-VALUES = empty row) |
| 576 | WS-CA-ROW-SELECTED | S9(4) COMP | Index (1-7) of the selected row from the last edit, 0 = none |
| 578 | WS-CA-LAST-TR-CODE | X(02) | Start key for PF8 (see BR-COTRTLIC-05) |
| 580 | WS-CA-FIRST-TR-CODE | X(02) | Type code of row 1; start key for ENTER re-reads and PF7 |
| 582 | WS-CA-SCREEN-NUM | 9(1) | Page number; 88 CA-FIRST-PAGE = 1 |
| 583 | WS-CA-LAST-PAGE-DISPLAYED | 9(1) | 88 CA-LAST-PAGE-SHOWN = 0, CA-LAST-PAGE-NOT-SHOWN = 9 |
| 584 | WS-CA-NEXT-PAGE-IND | X(01) | `Y` = another page exists (CA-NEXT-PAGE-EXISTS), LOW-VALUES = no |
| 585 | WS-CA-DELETE-FLAG | X(01) | `Y` = delete confirmation pending; LOW-VALUES = none or succeeded (two 88s share LOW-VALUES) |
| 586 | WS-CA-UPDATE-FLAG | X(01) | `Y` = update confirmation pending; LOW-VALUES = none or succeeded |

- Working storage is re-initialized on every turn (`INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA`,
  `COTRTLIC.cbl:500-502`). INITIALIZE sets every alphanumeric flag to spaces and every numeric item to zero,
  ignoring VALUE clauses, so flags whose "no" value is LOW-VALUES start each turn in neither state
  (relevant to Q-COTRTLIC-01 and Q-COTRTLIC-13).
- Host variables used by the cursors: WS-START-KEY X(02), WS-EDIT-TYPE-FLAG X(01) (`'1'` = type filter
  valid), WS-TYPE-CD-FILTER X(02), WS-EDIT-DESC-FLAG X(01) (`'1'` = description filter valid),
  WS-TYPE-DESC-FILTER X(52) (`COTRTLIC.cbl:103-110`, `:274-278`).

## Screen
BMS convention: `POS=(r,c)` is the attribute byte, so data starts in column c+1. The mock-up shows the
first-entry state with the seed data of JOB-CREADB21.

```text
             1         2         3         4         5         6         7         8
    12345678901234567890123456789012345678901234567890123456789012345678901234567890
01   Tran: CTLI                AWS Mainframe Modernization           Date: mm/dd/yy
02   Prog: COTRTLIC                    CardDemo                      Time: hh:mm:ss
03
04                              Maintain Transaction Type                 Page  1
05
06                                Type Filter:  __
07
08      Description Filter:  __________________________________________________
09
10      Select      Type                      Description
11      ------     -----     --------------------------------------------------
12        _          01      PURCHASE
13        _          02      PAYMENT
14        _          03      CREDIT
15        _          04      AUTHORIZATION
16        _          05      REFUND
17        _          06      REVERAL
18        _          07      ADJUSTMENT
19
20
21                       Type U to update, D to delete any record
22
23   <ERRMSG: 78 characters, red>
24   F2=Add   F3=Exit  F7=Page Up   F8=Page Dn  F10=Save
```

| Field | BMS name | Len | In/Out | Attributes (BMS default) | Meaning |
|:--|:--|--:|:--|:--|:--|
| Tran (1,7) | TRNNAME | 4 | Out | ASKIP,FSET,NORM, blue | `CTLI` |
| Title 1 (1,21) | TITLE01 | 40 | Out | ASKIP,NORM, yellow | CCDA-TITLE01 |
| Date (1,71) | CURDATE | 8 | Out | ASKIP,NORM, blue | Current date `mm/dd/yy` |
| Prog (2,7) | PGMNAME | 8 | Out | ASKIP,NORM, blue | `COTRTLIC` |
| Title 2 (2,21) | TITLE02 | 40 | Out | ASKIP,NORM, yellow | CCDA-TITLE02 |
| Time (2,71) | CURTIME | 8 | Out | ASKIP,NORM, blue | Current time `hh:mm:ss` |
| Page (4,76) | PAGENO | 3 | Out | default (ASKIP) | WS-CA-SCREEN-NUM, one digit left-justified (Q-COTRTLIC-06) |
| Type Filter (6,44) | TRTYPE | 2 | In/Out | FSET,IC,NORM,UNPROT, green, underline | Exact type-code filter |
| Description Filter (8,25) | TRDESC | 50 | In/Out | FSET,NORM,UNPROT, green, underline | Description substring filter |
| Select row n (11+n,6), n = 1-7 | TRTSEL1-TRTSEL7 | 1 | In/Out | FSET,NORM,PROT, underline; program unprotects filled rows | Action code `D` or `U` |
| Type row n (11+n,17) | TRTTYP1-TRTTYP7 | 2 | Out (returned, FSET) | FSET,NORM,PROT | TR_TYPE of row n; read back as the key for update and delete |
| Description row n (11+n,25) | TRTYPD1-TRTYPD7 | 50 | In/Out | FSET,NORM,UNPROT in BMS; program protects all rows except a row in update mode | TR_DESCRIPTION of row n |
| Row 8 (19,6 / 19,17 / 19,25) | TRTSELA, TRTTYPA, TRTDSCA | 1/2/50 | none | FSET,NORM,PROT | Defined in the map but never filled or read (the program handles 7 rows) |
| Info line (21,19) | INFOMSG | 45 | Out | PROT, neutral | Centered instruction or confirmation text (BR-COTRTLIC-17) |
| Message (23,1) | ERRMSG | 78 | Out | ASKIP,BRT,FSET, red | WS-RETURN-MSG (75 bytes) |
| Keys (24,1..44) | BUTNF02, BUTNF03, BUTNF07, BUTNF08, BUTNF10 | 7/7/10/10/8 | Out | ASKIP,NORM, turquoise | `F2=Add`, `F3=Exit`, `F7=Page Up`, `F8=Page Dn`, `F10=Save` (always shown) |

Unnamed literals: `Maintain Transaction Type` (4,28) neutral, `Page ` (4,70), `Type Filter:` (6,30) and
`Description Filter:` (8,4) turquoise, column headings `Select ` (10,4), `Type` (10,16), `Description`
(10,42) and the dashes on row 11 (neutral).

## Processing flow
1. 0000-MAIN start (`COTRTLIC.cbl:498-532`): initialize CC-WORK-AREA, WS-MISC-STORAGE, WS-COMMAREA; clear
   WS-RETURN-MSG. IF EIBCALEN = 0, initialize both COMMAREA parts and apply BR-COTRTLIC-01. OTHERWISE copy
   DFHCOMMAREA bytes 1-160 into CARDDEMO-COMMAREA and bytes 161-587 into WS-THIS-PROGCOMMAREA.
2. PERFORM YYYY-STORE-PFKEY (`app/cpy/CSSTRPFY.cpy`): map EIBAID to CCARD-AID-* (PF13-PF24 become PF1-PF12).
3. Fresh-start check (BR-COTRTLIC-02, `:544-554`).
4. IF EIBCALEN > 0 AND CDEMO-FROM-PROGRAM = `COTRTLIC` (that is, the previous turn was this program):
   PERFORM 1000-RECEIVE-MAP (`:919-928`) = 1100-RECEIVE-SCREEN then 1200-EDIT-INPUTS.
   1. 1100-RECEIVE-SCREEN (`:930-958`): RECEIVE MAP into CTRTLIAI (RESP not checked). Copy TRTYPEI to
      WS-IN-TYPE-CD and TRDESCI to WS-IN-TYPE-DESC. FOR EACH row 1-7: copy TRTSELI to WS-EDIT-SELECT(i),
      TRTTYPI to WS-ROW-TR-CODE-IN(i); set WS-ROW-TR-DESC-IN(i) to LOW-VALUES, and IF TRTYPDI is neither `*`
      nor spaces, replace it with FUNCTION TRIM(TRTYPDI) (left-justified).
   2. 1200-EDIT-INPUTS (`:960-980`): set INPUT-OK and "do not protect rows"; PERFORM 1210-EDIT-ARRAY,
      1230-EDIT-DESC, 1220-EDIT-TYPECD, 1290-CROSS-EDITS in that order.
5. Key check (NAV-COTRTLIC-05, `:574-587`): valid keys are ENTER, PF2, PF3, PF7, PF8, and PF10 when a delete
   or update confirmation is pending; any other key is processed as ENTER.
6. IF PF3: NAV-COTRTLIC-01 (XCTL; the program ends here).
7. IF PF2 AND CDEMO-FROM-PROGRAM = `COTRTLIC`: NAV-COTRTLIC-02 (XCTL; the program ends here).
8. IF the key is not PF8, set CA-LAST-PAGE-NOT-SHOWN (`:657-661`).
9. IF PF10: keep it only when a confirmation is pending and neither filter nor the selected row changed;
   OTHERWISE process it as ENTER (BR-COTRTLIC-15, `:666-678`).
10. PERFORM 9998-PRIMING-QUERY; IF it failed, ERR-COTRTLIC-01 (the conversation ends) (`:684-691`).
11. Main decision (`:698-879`), first true branch wins:
    1. INPUT-ERROR: CCARD-ERROR-MSG = WS-RETURN-MSG; start key = WS-CA-FIRST-TR-CODE; IF neither filter is
       flagged NOT-OK, PERFORM 8000-READ-FORWARD; send the screen.
    2. PF7 AND CA-FIRST-PAGE (written as two identical WHEN clauses, Q-COTRTLIC-02): start key =
       WS-CA-FIRST-TR-CODE, 8000-READ-FORWARD, send.
    3. PF3, or CDEMO-PGM-REENTER AND CDEMO-FROM-PROGRAM is not `COTRTLIC` (unreachable, Q-COTRTLIC-03):
       initialize both COMMAREA parts and WS-MISC-STORAGE, set FROM fields to CTLI/COTRTLIC, admin,
       PGM-ENTER, first page, last page not shown, read forward from WS-CA-FIRST-TR-CODE, send.
    4. PF8 AND CA-NEXT-PAGE-EXISTS: start key = WS-CA-LAST-TR-CODE, add 1 to WS-CA-SCREEN-NUM,
       8000-READ-FORWARD, clear all row selections, send.
    5. PF7 AND NOT CA-FIRST-PAGE: start key = WS-CA-FIRST-TR-CODE, subtract 1 from WS-CA-SCREEN-NUM,
       8100-READ-BACKWARDS, clear all row selections, send.
    6. ENTER AND WS-DELETES-REQUESTED > 0 AND FROM-PROGRAM = `COTRTLIC`: start key = WS-CA-FIRST-TR-CODE;
       IF neither filter is NOT-OK, 8000-READ-FORWARD; send (the selected row is highlighted and the delete
       prompt shown, BR-COTRTLIC-13).
    7. PF10 AND WS-DELETES-REQUESTED > 0 AND FROM-PROGRAM = `COTRTLIC`: PERFORM 9300-DELETE-RECORD; set
       FLG-DELETED-YES if CA-DELETE-SUCCEEDED; send the screen (rows are not re-read); IF deleted, initialize
       CARDDEMO-COMMAREA, WS-THIS-PROGCOMMAREA and WS-MISC-STORAGE, set PGM-ENTER, first page, last page not
       shown (Q-COTRTLIC-04).
    8. ENTER AND WS-UPDATES-REQUESTED > 0 AND FROM-PROGRAM = `COTRTLIC`: as branch 6 (update prompt).
    9. PF10 AND WS-UPDATES-REQUESTED > 0 AND FROM-PROGRAM = `COTRTLIC`: PERFORM 9200-UPDATE-RECORD; IF
       CA-UPDATE-SUCCEEDED set FLG-UPDATE-COMPLETED; start key = WS-CA-FIRST-TR-CODE; 8000-READ-FORWARD; send.
       This branch has no GO TO and falls through to step 12.
    10. OTHERWISE: start key = WS-CA-FIRST-TR-CODE; 8000-READ-FORWARD; send.
12. After the decision (`:882-896`): IF INPUT-ERROR, set CCARD-ERROR-MSG and the CCARD-NEXT-* fields;
    in all cases go to COMMON-RETURN.
13. 1210-EDIT-ARRAY (`:982-1057`): BR-COTRTLIC-12, VR-COTRTLIC-03, VR-COTRTLIC-04; FOR EACH row from 7 down to
    1 with action `U`, PERFORM 1211-EDIT-ARRAY-DESC (VR-COTRTLIC-05 to VR-COTRTLIC-07).
14. 1220-EDIT-TYPECD (`:1096-1140`): BR-COTRTLIC-09, VR-COTRTLIC-01, and the change check of BR-COTRTLIC-08
    (code in the -EXIT paragraph, which runs because of PERFORM ... THRU).
15. 1230-EDIT-DESC (`:1142-1178`): BR-COTRTLIC-10 and the change check of BR-COTRTLIC-08.
16. 1290-CROSS-EDITS (`:1239-1271`): BR-COTRTLIC-11, VR-COTRTLIC-02.
17. 8000-READ-FORWARD (`:1603-1724`): BR-COTRTLIC-05. 8100-READ-BACKWARDS (`:1727-1796`): BR-COTRTLIC-06.
18. 2000-SEND-MAP (`:1274-1292`) = 2100-SCREEN-INIT (header, date, time, page number, info line dark),
    2200-SETUP-ARRAY-ATTRIBS, 2300-SCREEN-ARRAY-INIT, 2400-SETUP-SCREEN-ATTRS (BR-COTRTLIC-19 to -21),
    2500-SETUP-MESSAGE (BR-COTRTLIC-17, -18), 2600-SEND-SCREEN (`SEND MAP('CTRTLIA') MAPSET('COTRTLI')
    FROM(CTRTLIAO) CURSOR ERASE FREEKB`, RESP not checked).

## Rules
### Business rules
- **BR-COTRTLIC-01**: When started with no COMMAREA (EIBCALEN = 0), CARDDEMO-COMMAREA and the extension are
  initialized, CDEMO-FROM-TRANID = `CTLI`, CDEMO-FROM-PROGRAM = `COTRTLIC`, CDEMO-USRTYP-ADMIN is set,
  context = enter, last map/mapset = `CTRTLIA`/`COTRTLI`, page = 1, last page "not shown". Input is not
  received on this turn because EIBCALEN = 0. Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:515-525 (0000-MAIN)`.
- **BR-COTRTLIC-02**: When (context = enter AND CDEMO-FROM-PROGRAM is not `COTRTLIC`) OR (the key is PF3 AND
  CDEMO-FROM-TRANID = `CTTU`), the program starts afresh: the extension is initialized (filters, rows, keys,
  flags), context = enter, the key is treated as ENTER, page = 1, last page "not shown". This is the normal
  path on entry from COADM01C; the turn then ends in the OTHERWISE branch (read from the first type).
  Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:544-554 (0000-MAIN)`.
- **BR-COTRTLIC-03**: Screen input is received and edited only when EIBCALEN > 0 and CDEMO-FROM-PROGRAM =
  `COTRTLIC`; on the first turn after an XCTL the screen is not received. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:561-566 (0000-MAIN)`.
- **BR-COTRTLIC-04**: The list is the result of cursor C-TR-TYPE-FORWARD (ascending) or C-TR-TYPE-BACKWARD
  (descending), seven rows per page (WS-MAX-SCREEN-LINES = 7). Both filters are optional and combine with AND.
  SQL (verbatim, `COTRTLIC.cbl:338-368`):
  ```sql
  DECLARE C-TR-TYPE-FORWARD CURSOR FOR
      SELECT TR_TYPE
            ,TR_DESCRIPTION
        FROM CARDDEMO.TRANSACTION_TYPE
       WHERE TR_TYPE >= :WS-START-KEY
         AND ((:WS-EDIT-TYPE-FLAG = '1'
         AND  TR_TYPE = :WS-TYPE-CD-FILTER)
          OR (:WS-EDIT-TYPE-FLAG <> '1'))
         AND ((:WS-EDIT-DESC-FLAG = '1'
         AND  TR_DESCRIPTION LIKE
              TRIM(:WS-TYPE-DESC-FILTER))
          OR (:WS-EDIT-DESC-FLAG <> '1'))
    ORDER BY TR_TYPE

  DECLARE C-TR-TYPE-BACKWARD CURSOR FOR
      SELECT TR_TYPE
            ,TR_DESCRIPTION
        FROM CARDDEMO.TRANSACTION_TYPE
       WHERE TR_TYPE < :WS-START-KEY
         and ((:WS-EDIT-TYPE-FLAG = '1'
         and  TR_TYPE = :WS-TYPE-CD-FILTER)
          OR (:WS-EDIT-TYPE-FLAG <> '1'))
         AND ((:WS-EDIT-DESC-FLAG = '1'
         AND  TR_DESCRIPTION LIKE
              TRIM(:WS-TYPE-DESC-FILTER))
          OR (:WS-EDIT-DESC-FLAG <> '1'))
    ORDER BY TR_TYPE DESC
  ```
  The start key on a fresh start is spaces, so `TR_TYPE >= '  '` returns every row. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:338-368 (WORKING-STORAGE)`.
- **BR-COTRTLIC-05**: Forward read: clear all seven rows to LOW-VALUES; OPEN the forward cursor; set "next page
  exists"; FETCH into DCL-TR-TYPE / DCL-TR-DESCRIPTION (record re-initialized before each fetch) until seven rows
  are filled or the loop ends.
  - SQLCODE 0: put the row in the next slot (type and the 50-byte description text). Row 1 sets
    WS-CA-FIRST-TR-CODE and, if the page number is 0, sets it to 1. When the seventh row is placed, set
    WS-CA-LAST-TR-CODE to its type and FETCH once more (look-ahead): SQLCODE 0 -> next page exists and
    WS-CA-LAST-TR-CODE is overwritten with the eighth row's type (PF8 then starts at `>=` that row, which is
    correct); +100 -> no next page, and IF the key was PF8 and no message is set, message
    "No more pages for these search conditions"; other -> ERR-COTRTLIC-05.
  - SQLCODE +100 before seven rows: no next page; WS-CA-LAST-TR-CODE = the re-initialized DCL-TR-TYPE (spaces);
    IF PF8 and no message, "No more pages for these search conditions"; IF page = 1 and no row was read,
    message "No records found for this search condition.".
  - Other SQLCODE: ERR-COTRTLIC-05.
  - CLOSE the cursor (ERR-COTRTLIC-06).
  Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1603-1724 (8000-READ-FORWARD)`.
- **BR-COTRTLIC-06**: Backward read: clear all rows; WS-CA-LAST-TR-CODE = WS-CA-FIRST-TR-CODE (so PF8 from the
  resulting page starts at the first row of the page just left); slot = 7; set "next page exists"; OPEN the
  backward cursor (start key = first type of the current page) and FETCH: SQLCODE 0 places the row in the
  current slot and decrements it; when slot 1 is filled, WS-CA-FIRST-TR-CODE = that type and the loop ends.
  The page therefore shows the seven types before the current page in ascending order. Any other SQLCODE,
  including +100, is ERR-COTRTLIC-08. CLOSE runs in the -EXIT paragraph. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1727-1796 (8100-READ-BACKWARDS)`.
- **BR-COTRTLIC-07**: Paging: PF8 with CA-NEXT-PAGE-EXISTS reads forward from WS-CA-LAST-TR-CODE and adds 1 to
  the page number; PF7 when the page is not 1 subtracts 1 and reads backward from WS-CA-FIRST-TR-CODE; PF7 on
  page 1 re-reads page 1; PF8 without a next page falls to the OTHERWISE branch and re-reads the current page
  from WS-CA-FIRST-TR-CODE. Row selections are cleared after PF7/PF8 paging (`INITIALIZE
  WS-EDIT-SELECT-FLAGS`). Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:721-790 (0000-MAIN)`,
  `:870-878`.
- **BR-COTRTLIC-08**: Filter change detection: the type filter is "unchanged" when the screen value equals
  WS-CA-TYPE-CD, or when the filter is blank and WS-CA-TYPE-CD is zeros, LOW-VALUES, or spaces; the description
  filter is "unchanged" when the screen value equals WS-CA-TYPE-DESC, or when it is blank and WS-CA-TYPE-DESC is
  LOW-VALUES or spaces. On a change the paging variables are initialized (first/last key spaces, page 0,
  last-page indicator 0, next-page indicator space), the new value is stored in the COMMAREA, and the
  "changed" flag is set; the list is then read from the first matching type. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1125-1140 (1220-EDIT-TYPECD-EXIT)`, `:1165-1178 (1230-EDIT-DESC-EXIT)`.
- **BR-COTRTLIC-09**: Type filter: LOW-VALUES, spaces, or `00` means "no type filter" (WS-TYPE-CD-FILTER =
  zeros, flag blank). A numeric two-character value becomes the filter (WS-EDIT-TYPE-FLAG = `'1'`), matched with
  `TR_TYPE = :WS-TYPE-CD-FILTER`. Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1096-1123 (1220-EDIT-TYPECD)`.
- **BR-COTRTLIC-10**: Description filter: LOW-VALUES or spaces means "no description filter". Otherwise
  WS-TYPE-DESC-FILTER = `'%' || TRIM(input) || '%'` (1 + up to 50 + 1 bytes fits the X(52) field) and
  WS-EDIT-DESC-FLAG = `'1'`; the query uses `TR_DESCRIPTION LIKE TRIM(:WS-TYPE-DESC-FILTER)`, a case-sensitive
  substring match in which `%` and `_` typed by the user are wildcards (Q-COTRTLIC-09). Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1142-1164 (1230-EDIT-DESC)`.
- **BR-COTRTLIC-11**: When at least one filter is valid, the program counts the matching rows before listing:
  ```sql
  SELECT COUNT(1)
    INTO :WS-RECORDS-COUNT
    FROM CARDDEMO.TRANSACTION_TYPE
   WHERE ((:WS-EDIT-TYPE-FLAG = '1'
     AND  TR_TYPE = :WS-TYPE-CD-FILTER)
      OR  :WS-EDIT-TYPE-FLAG <> '1')
     AND
         ((:WS-EDIT-DESC-FLAG = '1'
     AND  TR_DESCRIPTION LIKE
          TRIM(:WS-TYPE-DESC-FILTER))
      OR  :WS-EDIT-DESC-FLAG <> '1')
  ```
  A count of 0 is VR-COTRTLIC-02; a SQL error is ERR-COTRTLIC-09. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1239-1271 (1290-CROSS-EDITS)`, `:1801-1834 (9100-CHECK-FILTERS)`.
- **BR-COTRTLIC-12**: Row action tally: counts of blank/LOW-VALUES, `D`, and `U` codes are taken over the seven
  select fields; actions requested = 7 - blanks (any non-blank character counts, including invalid ones); valid
  actions = deletes + updates. Rows are then scanned from 7 down to 1: a `D` or `U` row becomes I-SELECTED (so the
  topmost marked row wins) and, if more than one action was requested, is flagged in error. The row selection is
  "unchanged" when I-SELECTED equals WS-CA-ROW-SELECTED; otherwise WS-CA-ROW-SELECTED is updated and the
  "changed" flag set. Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:982-1053 (1210-EDIT-ARRAY)`.
- **BR-COTRTLIC-13**: Delete is a two-step action. Step 1: ENTER with exactly one `D` (and no other action)
  re-reads the page; while building the screen the row is highlighted (type and description neutral, cursor on
  its select field) and WS-CA-DELETE-FLAG is set to `Y`; the info line says "Delete HIGHLIGHTED row ? Press F10
  to confirm" when the filters did not change on this turn. Step 2: PF10 (accepted per BR-COTRTLIC-15) runs
  9300-DELETE-RECORD with the type code read back from that row's TRTTYP field:
  ```sql
  DELETE FROM CARDDEMO.TRANSACTION_TYPE
   WHERE TR_TYPE = :DCL-TR-TYPE
  ```
  SQLCODE 0 -> `EXEC CICS SYNCPOINT`, flag = succeeded (LOW-VALUES), info "HIGHLIGHTED row deleted.Hit Enter to
  continue", select code cleared on the screen; the rows are not re-read on this turn, and the COMMAREA is then
  reset (Q-COTRTLIC-04, Q-COTRTLIC-21). Other results: ERR-COTRTLIC-13 / ERR-COTRTLIC-14. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:794-834 (0000-MAIN)`, `:1391-1399 (2300-SCREEN-ARRAY-INIT)`,
  `:1896-1938 (9300-DELETE-RECORD)`.
- **BR-COTRTLIC-14**: Update is a two-step action. Step 1: ENTER with exactly one `U` re-reads the page, makes
  that row's description field unprotected (FSET, cursor in it, red if the description edit failed), sets
  WS-CA-UPDATE-FLAG = `Y`, and shows "Update HIGHLIGHTED row. Press F10 to save" when the filters did not
  change. The description shown is the edited value from the screen if it differs from the stored one (or `*`
  if it was blanked), otherwise the stored value. Step 2: PF10 runs 9200-UPDATE-RECORD with DCL-TR-TYPE = the
  row's type code, DCL-TR-DESCRIPTION-TEXT = TRIM(edited description), DCL-TR-DESCRIPTION-LEN = 50 (length of
  the untrimmed field, Q-COTRTLIC-10):
  ```sql
  UPDATE CARDDEMO.TRANSACTION_TYPE
     SET TR_DESCRIPTION = :DCL-TR-DESCRIPTION
   WHERE TR_TYPE = :DCL-TR-TYPE
  ```
  SQLCODE 0 -> `EXEC CICS SYNCPOINT`, flag = succeeded (LOW-VALUES), info "HIGHLIGHTED row was updated"; the page
  is re-read from WS-CA-FIRST-TR-CODE and the updated row is shown with neutral color, select code cleared,
  cursor on its select field. Other results: ERR-COTRTLIC-10 to ERR-COTRTLIC-12. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:838-868 (0000-MAIN)`, `:1356-1370 (2200-SETUP-ARRAY-ATTRIBS)`,
  `:1404-1425 (2300-SCREEN-ARRAY-INIT)`, `:1837-1892 (9200-UPDATE-RECORD)`.
- **BR-COTRTLIC-15**: PF10 is processed as PF10 only when a delete or update confirmation is pending
  (WS-CA-DELETE-FLAG or WS-CA-UPDATE-FLAG = `Y`) AND the type filter, the description filter, and the selected
  row are all unchanged on this turn; otherwise it is processed as ENTER. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:666-678 (0000-MAIN)`.
- **BR-COTRTLIC-16**: Any key other than ENTER, PF2, PF3, PF7, PF8, or an accepted PF10 is processed as ENTER,
  without an error message. Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:574-587 (0000-MAIN)`.
- **BR-COTRTLIC-17**: Info line and paging messages (2500-SETUP-MESSAGE, first true branch wins):
  1. a delete succeeded -> info "HIGHLIGHTED row deleted.Hit Enter to continue";
  2. an update succeeded -> info "HIGHLIGHTED row was updated";
  3. a filter is in error -> no info;
  4. ENTER with deletes > 0, exactly one action and one valid action -> info "Delete HIGHLIGHTED row ? Press
     F10 to confirm" if no info is set and neither filter changed;
  5. same for updates -> info "Update HIGHLIGHTED row. Press F10 to save";
  6. PF7 and page 1 -> message "No previous pages to display" (also after a successful PF7 from page 2,
     Q-COTRTLIC-20);
  7. PF8, no next page, last page already shown -> message "No more pages to display";
  8. PF8, no next page -> info "Type U to update, D to delete any record" (if no info) and mark the last page
     as shown;
  9. no info set, or next page exists -> info "Type U to update, D to delete any record";
  10. otherwise no info (unreachable, because the info is always empty when branch 9 is tested).
  The message line ERRMSG = WS-RETURN-MSG. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1504-1557 (2500-SETUP-MESSAGE)`.
- **BR-COTRTLIC-18**: The info text is centered in 45 characters (start = (45 - trimmed length) / 2 + 1) and
  sent in neutral color, unless it is empty or the message line holds "No records found for this search
  condition."; otherwise the info line stays dark. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1320-1322 (2100-SCREEN-INIT)`, `:1562-1579 (2500-SETUP-MESSAGE)`.
- **BR-COTRTLIC-19**: Row attributes (2200, rows 7 to 1): every description field is protected with FSET; a row
  whose COMMAREA slot is empty, or every row when rows must be protected (VR-COTRTLIC-01, VR-COTRTLIC-02), gets a
  protected select field; otherwise the select field is unprotected with FSET, red with the cursor if the row is
  in error, and the delete or update row is highlighted per BR-COTRTLIC-13 / BR-COTRTLIC-14. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1329-1379 (2200-SETUP-ARRAY-ATTRIBS)`.
- **BR-COTRTLIC-20**: Filter field attributes (2400) are left at the BMS defaults on the first turn
  (EIBCALEN = 0, or context = enter with CDEMO-FROM-PROGRAM = `COADM01C`). Otherwise: when any row action is
  requested, both filter fields keep their values but become autoskip/FSET in blue (they cannot be changed while
  an action is pending); when a filter is valid or in error its value is redisplayed and it is unprotected with
  FSET; a blank or `00` type filter is cleared. A filter in error is red with the cursor. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1438-1485 (2400-SETUP-SCREEN-ATTRS)`.
- **BR-COTRTLIC-21**: Cursor: when there is no input error and either no row action is requested or the key was
  PF7/PF8, the cursor goes to the type filter; otherwise to the field flagged by the edits (first field in map
  order with length -1). Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1489-1497 (2400-SETUP-SCREEN-ATTRS)`.
- **BR-COTRTLIC-22**: Every turn that reaches the main decision first runs the Db2 priming query
  `SELECT 1 INTO :WS-DUMMY-DB2-INT FROM SYSIBM.SYSDUMMY1 FETCH FIRST 1 ROW ONLY` (CSDB2RPY). Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:684-691 (0000-MAIN)`,
  `app/app-transaction-type-db2/cpy/CSDB2RPY.cpy:21-48 (9998-PRIMING-QUERY)`.

### Validation rules
- **VR-COTRTLIC-01**: The type filter, if supplied (not blank and not `00`), must be numeric in both positions;
  otherwise message "TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER", the filter is red with the cursor,
  all select fields are protected, and the list is not re-read (the rows from the previous turn are shown).
  The message is moved unconditionally and replaces any row-edit message. A single digit (for example `5`) fails
  because the second position is a space or LOW-VALUE. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1111-1118 (1220-EDIT-TYPECD)`, `:699-720 (0000-MAIN)`.
- **VR-COTRTLIC-02**: When the valid filters match no row (count = 0), message "No Records found for these
  filter conditions"; every valid filter is flagged in error (red, cursor), select fields are protected, and the
  list is not re-read. The message is moved unconditionally. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1251-1267 (1290-CROSS-EDITS)`.
- **VR-COTRTLIC-03**: Each select field must be blank, `D`, or `U` (upper case only); otherwise message "Action
  code selected is invalid" and that select field is red with the cursor. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1034-1038 (1210-EDIT-ARRAY)`.
- **VR-COTRTLIC-04**: At most one row may carry an action code; otherwise message "Please select only 1 action"
  (replaces VR-COTRTLIC-03) and every marked row is red. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1024-1027`, `:1049-1052 (1210-EDIT-ARRAY)`.
- **VR-COTRTLIC-05**: For a `U` row, if the trimmed, upper-cased description equals the trimmed, upper-cased
  stored description (and the lengths match), message "No change detected with respect to database values.".
  This is not an input error (Q-COTRTLIC-11). Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1060-1076 (1211-EDIT-ARRAY-DESC)`.
- **VR-COTRTLIC-06**: For a changed `U` row, the description must not be blank (`*` or spaces on the screen
  count as blank); otherwise message "Transaction Desc must be supplied." (only if no message is set yet), the
  field shows `*` in red with the cursor. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1078-1089 (1211-EDIT-ARRAY-DESC)`, `:1181-1205 (1240-EDIT-ALPHANUM-REQD)`.
- **VR-COTRTLIC-07**: For a changed `U` row, the description may contain only letters A-Z, a-z, digits, and
  spaces; otherwise message "Transaction Desc can have numbers or alphabets only." (only if no message is set),
  field red with the cursor. Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1207-1233 (1240-EDIT-ALPHANUM-REQD)`.
- **VR-COTRTLIC-08**: Message precedence within a turn: row-code messages (VR-03, VR-05 via SET, which always
  overwrites), then VR-04 (overwrites), then VR-06/VR-07 only if the line is empty, then VR-01 and VR-02
  (unconditional MOVE, overwrite everything before), then SQL messages only if the line is empty, then the PF7/PF8
  messages of BR-COTRTLIC-17 (unconditional). The line holds the last writer. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:960-1271`, `:1532-1540`.

### Navigation and keys
- **NAV-COTRTLIC-01**: PF3 -> CDEMO-TO-TRANID = CDEMO-FROM-TRANID unless it is LOW-VALUES, spaces, or `CTLI`
  (then `CA00`); CDEMO-TO-PROGRAM = CDEMO-FROM-PROGRAM unless LOW-VALUES, spaces, or `COTRTLIC` (then
  `COADM01C`); set FROM = `CTLI`/`COTRTLIC`, user type admin, context = enter, last map/mapset; `EXEC CICS
  SYNCPOINT`; XCTL to CDEMO-TO-PROGRAM with CARDDEMO-COMMAREA (160 bytes). Because COMMON-RETURN sets
  FROM-PROGRAM = `COTRTLIC` on every turn, PF3 always returns to COADM01C. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:591-625 (0000-MAIN)`.
- **NAV-COTRTLIC-02**: PF2 (only when the previous turn was this program) -> set FROM = `CTLI`/`COTRTLIC`,
  CDEMO-USRTYP-USER (Q-COTRTLIC-05), context = enter, last map/mapset, CCARD-NEXT-MAPSET/MAP =
  `COTRTUP`/`CTRTUPA`, WS-RETURN-MSG = "PF03 pressed. Exiting" (unused); XCTL `COTRTUPC` with
  CARDDEMO-COMMAREA, no SYNCPOINT. Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:630-652 (0000-MAIN)`.
- **NAV-COTRTLIC-03**: PF7 / PF8 page backward / forward per BR-COTRTLIC-07 with the messages of
  BR-COTRTLIC-05 and BR-COTRTLIC-17. Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:721-790 (0000-MAIN)`.
- **NAV-COTRTLIC-04**: PF10 confirms a pending delete or update (BR-COTRTLIC-13, -14, -15). Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:810-868 (0000-MAIN)`.
- **NAV-COTRTLIC-05**: ENTER (and any unaccepted key, BR-COTRTLIC-16) re-reads the current page from
  WS-CA-FIRST-TR-CODE (or from the first matching row after a filter change) and processes row actions. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:574-587`, `:794-806`, `:838-850`, `:870-878 (0000-MAIN)`.
- **NAV-COTRTLIC-06**: PF13-PF24 behave as PF1-PF12 (CSSTRPFY). Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:538-539 (0000-MAIN)`, `app/cpy/CSSTRPFY.cpy`.
- **NAV-COTRTLIC-07**: Every turn that does not XCTL ends with RETURN TRANSID `CTLI` and the 2000-byte
  COMMAREA, except ERR-COTRTLIC-01. Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:899-915 (COMMON-RETURN)`.

### Error handling
- **ERR-COTRTLIC-01**: Priming query SQLCODE not 0 -> WS-DB2-ERROR, action `'Db2 access failure. '`, message
  formatted per ERR-COTRTLIC-02 into WS-LONG-MSG (800 bytes); SEND-LONG-TEXT sends the 800 bytes with
  `SEND TEXT ERASE FREEKB` and issues `RETURN` without TRANSID, ending the conversation. The user sees, for
  example, "Db2 access failure. SQLCODE: -904 ..." and must enter a new transaction. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:684-691`, `:2085-2096 (SEND-LONG-TEXT)`,
  `app/app-transaction-type-db2/cpy/CSDB2RPY.cpy:21-48`.
- **ERR-COTRTLIC-02**: Every Db2 error message is built by 9999-FORMAT-DB2-MESSAGE: LRECL = 72; `CALL 'DSNTIAC'
  USING DFHEIBLK, DFHCOMMAREA, SQLCA, WS-DSNTIAC-FORMATTED, WS-DSNTIAC-LRECL`; then
  `STRING TRIM(WS-DB2-CURRENT-ACTION) ' SQLCODE:' WS-DISP-SQLCODE ' ' WS-DSNTIAC-FMTD-TEXT INTO WS-LONG-MSG`
  and WS-RETURN-MSG = the first 75 bytes. WS-DISP-SQLCODE is `PIC ----9` (for example `-532` shows as ` -532`,
  `+100` as `  100`). Source: `app/app-transaction-type-db2/cpy/CSDB2RPY.cpy:53-89 (9999-FORMAT-DB2-MESSAGE)`,
  `app/app-transaction-type-db2/cpy/CSDB2RWY.cpy:21-46`.
- **ERR-COTRTLIC-03**: If DSNTIAC returns a non-zero RETURN-CODE, the formatted text is replaced by
  `DSNTIAC CD` followed by the two-digit return code (the literal `'DSNTIAC CD: '` is truncated to 10 bytes).
  Source: `app/app-transaction-type-db2/cpy/CSDB2RPY.cpy:64-72`.
- **ERR-COTRTLIC-04**: OPEN of the forward cursor fails -> WS-DB2-ERROR, action `'C-TR-TYPE-FORWARD Open'`;
  fetch and close are skipped and the screen is sent with empty rows. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1609-1614 (8000-READ-FORWARD)`, `:1942-1965 (9400-OPEN-FORWARD-CURSOR)`.
- **ERR-COTRTLIC-05**: FETCH error in the forward loop -> loop ends, WS-DB2-ERROR, action
  `'C-TR-TYPE-FORWARD close'` (mislabeled, Q-COTRTLIC-08); FETCH error on the look-ahead -> loop ends, action
  `'C-TR-TYPE-FORWARD fetch'` (WS-DB2-ERROR not set). Messages only if the line is empty. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1681-1691`, `:1706-1717 (8000-READ-FORWARD)`.
- **ERR-COTRTLIC-06**: CLOSE failures -> WS-DB2-ERROR and action `'C-TR-TYPE-FORWARD close'` or
  `'C-TR-TYPE-BACKWARD close'`. Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1970-1993`, `:2026-2049`.
- **ERR-COTRTLIC-07**: OPEN of the backward cursor fails -> WS-DB2-ERROR, action `'C-TR-TYPE-BACKWARD Open'`;
  the fetch loop still runs and fails (its message is suppressed because the line is set). Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1746-1747`, `:1997-2021 (9500-OPEN-BACKWARD-CURSOR)`.
- **ERR-COTRTLIC-08**: Any non-zero FETCH result on the backward cursor, including +100, ends the loop, sets
  WS-DB2-ERROR and action `'Error on fetch Cursor C-TR-TYPE-BACKWARD'` (Q-COTRTLIC-07). Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1777-1790 (8100-READ-BACKWARDS)`.
- **ERR-COTRTLIC-09**: COUNT query SQLCODE not 0 -> INPUT-ERROR, action `'Error reading TRANSACTION_TYPE table '`
  (if the line is empty). Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1819-1832 (9100-CHECK-FILTERS)`.
- **ERR-COTRTLIC-10**: UPDATE SQLCODE +100 -> update stays pending (`Y`), action `'Record not found. Deleted by
  others ? '`; not an input error, so the page is re-read and shown. Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1861-1869 (9200-UPDATE-RECORD)`.
- **ERR-COTRTLIC-11**: UPDATE SQLCODE -911 -> update stays pending, INPUT-ERROR, action `'Deadlock. Someone else
  updating ?'`. Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1870-1879`.
- **ERR-COTRTLIC-12**: UPDATE other negative SQLCODE (including -913) -> update stays pending, action `'Update
  failed with'`. Positive SQLCODEs other than +100 match no branch: no SYNCPOINT, no message, flag unchanged
  (Q-COTRTLIC-18). Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1880-1889`.
- **ERR-COTRTLIC-13**: DELETE SQLCODE -532 (foreign key `TRC_TYPE_CODE` with `ON DELETE RESTRICT` from
  TRANSACTION_TYPE_CATEGORY) -> delete stays pending, action `'Please delete associated child records first:'`;
  the user sees "Please delete associated child records first: SQLCODE: -532 ..." Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1914-1925 (9300-DELETE-RECORD)`.
- **ERR-COTRTLIC-14**: DELETE any other non-zero SQLCODE (including +100 and -911) -> action `'Delete failed with
  message:'`; the flag keeps its value from the COMMAREA (`Y`). Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1926-1935`.
- **ERR-COTRTLIC-15**: RESP of RECEIVE MAP and SEND MAP is captured but never tested (MAPFAIL and send errors are
  ignored). Source: `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:931-935`, `:1588-1595`.

## Data effects
- Reads only, except on PF10:
  - Delete confirm: one `DELETE FROM CARDDEMO.TRANSACTION_TYPE WHERE TR_TYPE = :DCL-TR-TYPE`, followed on
    success by `EXEC CICS SYNCPOINT`. The FK rule makes Db2 reject the delete while category rows exist
    (ERR-COTRTLIC-13); the program never touches TRANSACTION_TYPE_CATEGORY.
  - Update confirm: one `UPDATE ... SET TR_DESCRIPTION = :DCL-TR-DESCRIPTION WHERE TR_TYPE = :DCL-TR-TYPE`; the
    stored value is the trimmed, left-justified text padded with spaces to 50 characters (VARCHAR length 50).
    Followed on success by SYNCPOINT.
- PF3 issues SYNCPOINT before XCTL; PF2 does not. The CICS task ends after each turn, which commits any
  remaining work.
- The tablespace CARDSPC1 is defined with `LOCKSIZE TABLE` (`app/app-transaction-type-db2/ctl/DB2CREAT.ctl`,
  JOB-CREADB21), so an UPDATE or DELETE locks the whole table until the SYNCPOINT, and a long-running JOB-MNTTRDB2
  (COBTUPDT, no intermediate commits) blocks this screen (Q-COBTUPDT-03).
- Relationship to batch: CBTRN03C reads the VSAM file TRANTYPE (ENT-TRAN-TYPE), not this table. A change made
  here reaches batch only after JOB-TRANEXTR unloads the table to `TRANTYPE.PS` and the VSAM file is reloaded
  (Q-TRANEXTR-01). The seed data differs from the VSAM sample: Db2 descriptions are upper case and type `06` is
  `REVERAL` (Q-CREADB21-04), while `app/data/ASCII/trantype.txt` is mixed case (`Reversal`).
- Modern equivalent: one `transaction_type` table shared by online and batch code (no extract); a paged REST
  endpoint `GET /transaction-types?code=&descContains=&page=` and `PATCH` / `DELETE /transaction-types/{code}`
  with a confirmation step in the UI; FK violation mapped to HTTP 409.

## Quirks
- **Q-COTRTLIC-01**: 1210-EDIT-ARRAY tests the "filter changed" flags (to clear row selections) before
  1220/1230 compute them on this turn, and the flags were initialized to spaces (neither `Y` nor LOW-VALUES), so
  the clearing never happens. A row index marked before a filter change stays selected, and after the re-read the
  highlighted row is a different record (the one now at that index); PF10 then acts on that record.
  Recommendation: fix (clear selections when a filter changes). Source:
  `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:991-995`, `:960-976`.
- **Q-COTRTLIC-02**: The PF7-on-first-page WHEN appears twice in a row; the first has no statements and shares
  the second's body. Harmless. Recommendation: fix (cosmetic). Source: `COTRTLIC.cbl:721-734`.
- **Q-COTRTLIC-03**: In the main EVALUATE, `WHEN CCARD-AID-PFK03` is unreachable (PF3 has already XCTLed or been
  turned into ENTER), and `CDEMO-PGM-REENTER AND FROM-PROGRAM <> COTRTLIC` is unreachable from the known callers
  (all pass context 0). Recommendation: drop in the target. Source: `COTRTLIC.cbl:738-762`.
- **Q-COTRTLIC-04**: After a successful delete the program initializes CARDDEMO-COMMAREA as well as its own
  extension, so the signed-on user ID, user type, and names are lost; the next PF3 returns to COADM01C with a
  blank user. The deleted row also stays on the screen until the next ENTER. Recommendation: fix (reset only the
  extension and re-read). Source: `COTRTLIC.cbl:823-834`.
- **Q-COTRTLIC-05**: No authorization: the program never checks CDEMO-USRTYP; started directly as CTLI it sets
  itself to admin, PF3 sets admin, and PF2 sets CDEMO-USRTYP-USER before XCTL to COTRTUPC (which does not
  check it either and sets admin again on PF3, Q-COTRTUPC-10). Recommendation: fix (enforce admin role). Source: `COTRTLIC.cbl:520`, `:611`, `:634`.
- **Q-COTRTLIC-06**: The page number is `9(1)`: after page 9, ADD gives 0 (page 0 is not "first page", so PF7
  then reads backward), and PAGENO shows one digit left-justified in a 3-character field. 100 possible type codes
  allow 15 pages. Recommendation: fix. Source: `COTRTLIC.cbl:403`, `:770`, `:1318`.
- **Q-COTRTLIC-07**: The backward read has no +100 branch: when fewer than seven rows precede the current first
  row (possible after concurrent inserts/deletes or after the page counter wrapped), the user gets "Error on fetch
  Cursor C-TR-TYPE-BACKWARD SQLCODE:  100 ..." and a page whose top rows are empty. Recommendation: fix.
  Source: `COTRTLIC.cbl:1761-1790`.
- **Q-COTRTLIC-08**: A forward FETCH error inside the loop is reported as `C-TR-TYPE-FORWARD close`.
  Recommendation: fix. Source: `COTRTLIC.cbl:1712`.
- **Q-COTRTLIC-09**: The description filter is case-sensitive (Db2 seed data is upper case, so `pay` finds
  nothing), and `%` / `_` in the input are not escaped. Recommendation: decide. Source: `COTRTLIC.cbl:1156-1162`, `:347-349`.
- **Q-COTRTLIC-10**: The UPDATE sets the VARCHAR length to 50 (the untrimmed field length), so every updated
  description is stored with trailing spaces; COTRTUPC and COBTUPDT behave the same. Recommendation: decide
  (trim on migration). Source: `COTRTLIC.cbl:1841-1844`.
- **Q-COTRTLIC-11**: "No change detected ..." (VR-COTRTLIC-05) is not an input error. On ENTER the user sees that
  message together with "Update HIGHLIGHTED row. Press F10 to save"; PF10 then runs the UPDATE even if nothing
  changed, and a case-only change is reported as "no change" on ENTER but saved on PF10. Recommendation: decide.
  Source: `COTRTLIC.cbl:1060-1076`, `:854-868`.
- **Q-COTRTLIC-12**: Message precedence is inconsistent: some edits SET or MOVE unconditionally (VR-01, VR-02,
  VR-03, VR-04, VR-05), others only when the line is empty (VR-COTRTLIC-08). Recommendation: decide.
- **Q-COTRTLIC-13**: `INITIALIZE WS-MISC-STORAGE` (which contains CSDB2RWY) sets WS-DSNTIAC-MESG-LEN to 0,
  overriding `VALUE +720`, so DSNTIAC is called with a zero-length message area. DSNTIAC is then expected to
  return a non-zero code, in which case every Db2 message ends in `DSNTIAC CD` plus the code instead of the Db2
  text (ERR-COTRTLIC-03). Recommendation: fix; verify on a real system. Source: `COTRTLIC.cbl:500-502`, `:304`,
  `app/app-transaction-type-db2/cpy/CSDB2RWY.cpy:33-34`.
- **Q-COTRTLIC-14**: WS-DB2-ERROR set by the list reads is never tested after the read; the screen is sent with
  whatever rows were filled and the error text. Recommendation: preserve (acceptable) or fix.
- **Q-COTRTLIC-15**: CA-DELETE-SUCCEEDED / CA-DELETE-NOT-REQUESTED (and the update pair) share LOW-VALUES, and a
  failed update leaves WS-CA-UPDATE-FLAG = `Y` across turns, so PF10 is "valid" later with no selection; it then
  falls to the OTHERWISE branch (re-read). Harmless. Recommendation: fix in the target (explicit state).
- **Q-COTRTLIC-16**: The map defines an eighth row (row 19: TRTSELA, TRTTYPA, TRTDSCA) that is never used.
  Recommendation: drop.
- **Q-COTRTLIC-17**: The key for UPDATE/DELETE is read back from the protected TRTTYP field on the screen, not
  from the COMMAREA copy of the row. Recommendation: fix (use the server-side copy).
  Source: `COTRTLIC.cbl:1839`, `:1898`.
- **Q-COTRTLIC-18**: UPDATE warnings (positive SQLCODE other than +100) are not handled: no SYNCPOINT, no message,
  no success flag, although the row was changed (commit at task end). Recommendation: fix.
- **Q-COTRTLIC-19**: No optimistic concurrency: the change check compares with the COMMAREA copy, and the UPDATE
  overwrites whatever is in the table. Recommendation: decide (add a version or compare-and-set).
- **Q-COTRTLIC-20**: After PF7 from page 2 to page 1 the program also shows "No previous pages to display",
  because the message is chosen from "PF7 and now on page 1". Similarly, arriving at the last page with PF8 shows
  "No more pages for these search conditions" immediately. Recommendation: fix. Source: `COTRTLIC.cbl:1532-1535`,
  `:1674-1680`, `:1698-1701`.
- **Q-COTRTLIC-21**: When a filter is in error, the list is not re-read but the paging variables were already
  reset by the change check, so the old rows are shown with page number 0. Recommendation: fix. Source:
  `COTRTLIC.cbl:1134`, `:713-717`.

## Test scenarios
Unless stated otherwise: Db2 loaded by JOB-CREADB21 with the 7 seed types (`01 PURCHASE`, `02 PAYMENT`,
`03 CREDIT`, `04 AUTHORIZATION`, `05 REFUND`, `06 REVERAL`, `07 ADJUSTMENT`), the user on the admin menu.
- **TS-COTRTLIC-01** (covers BR-COTRTLIC-02, -04, -05, -17, -18, -20): Given the seed data, when option 5 is
  chosen on COADM01C, then the list shows types 01-07 in order, PAGENO `1`, info "Type U to update, D to delete
  any record", message line empty, cursor on the type filter, and the COMMAREA has first key `01`, last key
  `07`, next-page indicator LOW-VALUES.
- **TS-COTRTLIC-02** (covers BR-COTRTLIC-05, -07, -17): Given TS-01, when PF8 is pressed, then page 1 is shown
  again with message "No more pages for these search conditions"; when PF8 is pressed again, the message is "No
  more pages to display".
- **TS-COTRTLIC-03** (covers BR-COTRTLIC-07, -17): Given TS-01, when PF7 is pressed, then page 1 is re-read and
  the message is "No previous pages to display".
- **TS-COTRTLIC-04** (covers BR-COTRTLIC-05, -06, -07, Q-COTRTLIC-20): Given three extra types `08 TEST A`,
  `09 TEST B`, `10 TEST C`, when PF8 is pressed on page 1, then page 2 shows 08-10, PAGENO `2`, message "No more
  pages for these search conditions"; when PF7 is then pressed, page 1 shows 01-07 with message "No previous
  pages to display".
- **TS-COTRTLIC-05** (covers BR-COTRTLIC-08, -09, -11): Given TS-01, when `03` is typed in the type filter and
  ENTER pressed, then only `03 CREDIT` is listed, page `1`.
- **TS-COTRTLIC-06** (covers VR-COTRTLIC-01, Q-COTRTLIC-21): Given TS-01, when `A1` (or `5`) is typed in the type
  filter, then message "TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER", the filter is red, select fields
  are protected, the previous rows remain, PAGENO shows `0`.
- **TS-COTRTLIC-07** (covers VR-COTRTLIC-02): Given TS-01, when `99` is typed in the type filter, then message "No
  Records found for these filter conditions" and the filter is red.
- **TS-COTRTLIC-08** (covers BR-COTRTLIC-10, Q-COTRTLIC-09): Given TS-01, when `PAY` is typed in the description
  filter, then only `02 PAYMENT` is listed; when `pay` is typed, then "No Records found for these filter
  conditions".
- **TS-COTRTLIC-09** (covers VR-COTRTLIC-03): Given TS-01, when `X` is typed on row 2, then message "Action code
  selected is invalid" and row 2's select field is red with the cursor; no database change.
- **TS-COTRTLIC-10** (covers VR-COTRTLIC-04): Given TS-01, when `D` is typed on rows 1 and 3, then message "Please
  select only 1 action" and both select fields are red.
- **TS-COTRTLIC-11** (covers BR-COTRTLIC-13, ERR-COTRTLIC-13): Given the seed categories are loaded (category
  rows exist for every type), when `D` is typed on row 7 and ENTER pressed, then row 7 is highlighted and the info
  is "Delete HIGHLIGHTED row ? Press F10 to confirm"; when PF10 is pressed, then the message starts "Please delete
  associated child records first: SQLCODE: -532" and type 07 still exists.
- **TS-COTRTLIC-12** (covers BR-COTRTLIC-13, Q-COTRTLIC-04): Given type `08 TEST A` with no categories on page 2,
  when `D` is typed on its row, ENTER, then PF10, then the info is "HIGHLIGHTED row deleted.Hit Enter to continue",
  the row is still displayed, the table no longer has type 08, and the returned COMMAREA has a blank user ID;
  the next ENTER shows page 1 without 08.
- **TS-COTRTLIC-13** (covers BR-COTRTLIC-14, VR-COTRTLIC-05, Q-COTRTLIC-11): Given TS-01, when `U` is typed on
  row 5 and ENTER pressed, then the description of row 5 is editable, the info is "Update HIGHLIGHTED row. Press
  F10 to save" and the message is "No change detected with respect to database values."; when the description is
  changed to `REFUND CREDIT` and PF10 pressed, then the info is "HIGHLIGHTED row was updated" and
  `TR_DESCRIPTION` for `05` is `REFUND CREDIT` padded with spaces to 50 characters.
- **TS-COTRTLIC-14** (covers VR-COTRTLIC-07): Given TS-13 step 1, when the description is changed to
  `REFUND-CR` and PF10 pressed, then message "Transaction Desc can have numbers or alphabets only.", the field is
  red, and the table is unchanged.
- **TS-COTRTLIC-15** (covers VR-COTRTLIC-06): Given TS-13 step 1, when the description is erased and ENTER pressed,
  then message "Transaction Desc must be supplied." and the field shows `*` in red.
- **TS-COTRTLIC-16** (covers BR-COTRTLIC-15, BR-COTRTLIC-16): Given TS-01 with no pending action, when PF10 (or
  PF5) is pressed, then the page is re-read as for ENTER with no error message.
- **TS-COTRTLIC-17** (covers NAV-COTRTLIC-01): Given TS-01, when PF3 is pressed, then a SYNCPOINT is taken and
  control passes to COADM01C with CDEMO-FROM-TRANID `CTLI`, CDEMO-FROM-PROGRAM `COTRTLIC`, context 0.
- **TS-COTRTLIC-18** (covers NAV-COTRTLIC-02): Given TS-01, when PF2 is pressed, then control passes to COTRTUPC
  with CDEMO-FROM-PROGRAM `COTRTLIC`, context 0, user type `U`.
- **TS-COTRTLIC-19** (covers ERR-COTRTLIC-01): Given Db2 is unavailable, when CTLI is started, then a plain text
  screen starting "Db2 access failure. SQLCODE:" is sent and no next transaction is set.
- **TS-COTRTLIC-20** (covers ERR-COTRTLIC-10): Given TS-13 step 1 for type 05 and type 05 is deleted by another
  user before PF10, when PF10 is pressed, then the message starts "Record not found. Deleted by others ?
  SQLCODE:  100" and the update stays pending.
- **TS-COTRTLIC-21** (covers Q-COTRTLIC-01): Given TS-01, when `D` is typed on row 3 (`03 CREDIT`) and at the same
  time `R` is typed in the description filter, then the list shows 01, 03, 04, 05, 06 and row 3 (`04
  AUTHORIZATION`) is highlighted for deletion with no info text.
- **TS-COTRTLIC-22** (covers BR-COTRTLIC-01, Q-COTRTLIC-05): Given a terminal with no CardDemo sign-on, when
  `CTLI` is typed, then the list is shown (page 1) and the COMMAREA user type is `A`.

## Open questions
- What isolation level and bind options does plan CARDDEMO use (cursor stability vs. uncommitted read)? The
  bind JCL is not in the repository.
- What does DSNTIAC return when called with a zero message length (Q-COTRTLIC-13)? Confirm on a Db2 system.
- Should the Db2 table or the VSAM file be the system of record for transaction types? Today they can diverge
  (case, `REVERAL`, online changes not extracted).
- Is the page-size of 7 a requirement or a screen-space artifact (the map has room for 8 rows)?
- Should descriptions be restricted to letters, digits, and spaces (the edit rejects punctuation that some
  category descriptions in other data sets may need)?
