# COACTVWC: Display one account and its customer, looked up by account number

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COACTVWC.cbl` (941 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CAVW (`app/csd/CARDDEMO.CSD:317-318`) |
| BMS map | mapset `COACTVW` / map `CACTVWA` (`app/bms/COACTVW.bms`, symbolic map `app/cpy-bms/COACTVW.CPY`) |
| Copybooks | `CVCRD01Y` (CC-WORK-AREA: CC-ACCT-ID, AID flags, next-program fields), `COCOM01Y` (CARDDEMO-COMMAREA), `COTTL01Y` (titles), `COACTVW` (symbolic map), `CSDAT01Y` (date/time work fields), `CSMSG01Y` (unused), `CSMSG02Y` (ABEND-DATA), `CSUSR01Y` (unused), `CVACT01Y` (ACCOUNT-RECORD), `CVACT02Y` (CARD-RECORD, unused), `CVACT03Y` (CARD-XREF-RECORD), `CVCUS01Y` (CUSTOMER-RECORD), `CSSTRPFY` (procedure code YYYY-STORE-PFKEY), IBM `DFHBMSCA`, `DFHAID` |
| Calls / XCTL targets | XCTL to `CDEMO-FROM-PROGRAM`, or `COMEN01C` when that is blank (PF3). No CALLs. |
| Data stores | `CXACAIX` (alternate-index path over the card cross-reference by account, ENT-CARD-XREF): Read, key XREF-ACCT-ID (11). `ACCTDAT` (ENT-ACCOUNT): Read, key ACCT-ID (11). `CUSTDAT` (ENT-CUSTOMER): Read, key CUST-ID (9). No updates. |

## Purpose
Shows the full account master record (status, balances, limits, dates, group) and the owning
customer's details (name, address, phones, SSN, date of birth, FICO score, government id, EFT
account, primary-holder flag) for one account number typed by the user. The account number is
resolved to a customer through the card cross-reference. The program is read-only and is reached
from main menu option 1 "Account View" (`app/cpy/COMEN02Y.cpy:28`).

## Invocation and state

**Entry paths**

| Entry | How detected | Effect |
|:--|:--|:--|
| Main menu option 1 (`COMEN01C` XCTL with PGM-CONTEXT 0) | `CDEMO-FROM-PROGRAM` = `COMEN01C` and not PGM-REENTER | CARDDEMO-COMMAREA and the program extension are re-initialized (Q-COACTVWC-03); empty search screen |
| Transaction CAVW typed with no COMMAREA | EIBCALEN = 0 | Same as menu entry |
| Own pseudo-conversational turn | `CDEMO-PGM-REENTER` (set just before every SEND MAP) | Map received, account number edited, records read, screen redisplayed |
| Any other caller with PGM-CONTEXT 0 | `CDEMO-PGM-ENTER` | Empty search screen. CDEMO-ACCT-ID passed in is not used to pre-fill or read. |

**CARDDEMO-COMMAREA usage**

| Field | Read | Written |
|:--|:--|:--|
| CDEMO-FROM-PROGRAM / CDEMO-FROM-TRANID | Yes: entry path and PF3 target | `COACTVWC` / `CAVW`, only on PF3 exit |
| CDEMO-TO-PROGRAM / CDEMO-TO-TRANID | No | On PF3, from FROM-PROGRAM / FROM-TRANID, defaults `COMEN01C` / `CM00` |
| CDEMO-PGM-CONTEXT | Yes (0 = ENTER, 1 = REENTER) | REENTER before every SEND MAP; ENTER on PF3 exit |
| CDEMO-ACCT-ID | No | Valid entered account number, or 0 when the input is blank or invalid |
| CDEMO-CUST-ID / CDEMO-CARD-NUM | CDEMO-CUST-ID is the CUSTDAT key | From the cross-reference record (XREF-CUST-ID, XREF-CARD-NUM) when it is found |
| CDEMO-USER-TYPE | No | Forced to `U` on PF3 (Q-COACTVWC-03) |
| CDEMO-LAST-MAPSET / CDEMO-LAST-MAP | No | `COACTVW` / `CACTVWA` on PF3 |

**Program-specific COMMAREA extension**: `WS-THIS-PROGCOMMAREA` = CA-FROM-PROGRAM X(8) and
CA-FROM-TRANID X(4), placed after CARDDEMO-COMMAREA. It is copied in and out but never used.
Source: `app/cbl/COACTVWC.cbl:213-216`. RETURN passes a 2000-byte COMMAREA (`WS-COMMAREA`).
No account data is carried between turns; every ENTER re-reads all three files.

## Screen
Mock-up with account `00000000001` from `app/data/ASCII/acctdata.txt` line 1 and customer
`000000001` from `app/data/ASCII/custdata.txt` line 1 (SSN and government id masked). Text starts
one column after each BMS POS, which is the attribute byte. Alignment is approximate; the field
table gives exact positions.

```
         1         2         3         4         5         6         7         8
12345678901234567890123456789012345678901234567890123456789012345678901234567890
 Tran: CAVW           AWS Mainframe Modernization            Date: mm/dd/yy
 Prog: COACTVWC              CardDemo                         Time: hh:mm:ss

                                 View Account
                    Account Number : 00000000001      Active Y/N:  Y
         Opened: 2014-11-20             Credit Limit        : +      2,020.00
         Expiry: 2025-05-20             Cash credit Limit   : +      1,020.00
         Reissue: 2025-05-20            Current Balance     : +        194.00
                                        Current Cycle Credit: +           .00
         Account Group:                 Current Cycle Debit : +           .00
                                 Customer Details
         Customer id  : 000000001                SSN:  ***-**-****
         Date of birth: 1961-06-08               FICO Score:  274
 First Name                 Middle Name:               Last Name :
 Immanuel                   Madeline                   Kessler
 Address: 618 Deshaun Route                                     State   NC
          Apt. 802                                              Zip  12546
 City     Altenwerthshire                                       Country USA
 Phone 1: (908)119-8310 Government Issued Id Ref    : ********************
 Phone 2: (373)693-8684 EFT Account Id: 0053581756 Primary Card Holder Y/N:Y

                       Enter or update id of account to display
<row 23: ERRMSG, red, bright>
   F3=Exit
```

All fields without an explicit ATTRB in the BMS source take the BMS default (ASKIP, NORM), so
every field other than ACCTSID is display-only.

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Transaction id (1,7) | TRNNAME | 4 | Out | ASKIP, FSET, blue | `CAVW` |
| Title lines (1,21), (2,21) | TITLE01, TITLE02 | 40 | Out | ASKIP, yellow | CCDA-TITLE01 / CCDA-TITLE02 from `COTTL01Y` |
| Date (1,71) | CURDATE | 8 | Out | ASKIP, blue | Current date `MM/DD/YY` |
| Program (2,7) | PGMNAME | 8 | Out | ASKIP, blue | `COACTVWC` |
| Time (2,71) | CURTIME | 8 | Out | ASKIP, blue | Current time `HH:MM:SS` |
| Account number (5,38) | ACCTSID | 11 | In/Out | BMS: UNPROT, FSET, IC, NORM, green, underline, PICIN `99999999999`, VALIDN=MUSTFILL. Program: MDT on (`DFHBMFSE`) every send; red when the filter is in error; `*` in red when blank on a re-entry turn | Search key: account number |
| Active (5,70) | ACSTTUS | 1 | Out | ASKIP, underline | ACCT-ACTIVE-STATUS |
| Opened (6,17) | ADTOPEN | 10 | Out | default, underline | ACCT-OPEN-DATE as stored (`YYYY-MM-DD`) |
| Credit Limit (6,61) | ACRDLIM | 15 | Out | default, underline, JUSTIFY=RIGHT, PICOUT `+ZZZ,ZZZ,ZZZ.99` | ACCT-CREDIT-LIMIT |
| Expiry (7,17) | AEXPDT | 10 | Out | default, underline | ACCT-EXPIRAION-DATE |
| Cash credit Limit (7,61) | ACSHLIM | 15 | Out | as ACRDLIM | ACCT-CASH-CREDIT-LIMIT |
| Reissue (8,17) | AREISDT | 10 | Out | default, underline | ACCT-REISSUE-DATE |
| Current Balance (8,61) | ACURBAL | 15 | Out | as ACRDLIM | ACCT-CURR-BAL |
| Current Cycle Credit (9,61) | ACRCYCR | 15 | Out | as ACRDLIM | ACCT-CURR-CYC-CREDIT |
| Account Group (10,23) | AADDGRP | 10 | Out | default, underline | ACCT-GROUP-ID |
| Current Cycle Debit (10,61) | ACRCYDB | 15 | Out | as ACRDLIM | ACCT-CURR-CYC-DEBIT |
| Customer id (12,23) | ACSTNUM | 9 | Out | default, underline | CUST-ID |
| SSN (12,54) | ACSTSSN | 12 | Out | default, underline | CUST-SSN formatted `NNN-NN-NNNN` |
| Date of birth (13,23) | ACSTDOB | 10 | Out | default, underline | CUST-DOB-YYYY-MM-DD as stored |
| FICO Score (13,61) | ACSTFCO | 3 | Out | default, underline | CUST-FICO-CREDIT-SCORE |
| First name (15,1) | ACSFNAM | 25 | Out | default, underline | CUST-FIRST-NAME |
| Middle name (15,28) | ACSMNAM | 25 | Out | default, underline | CUST-MIDDLE-NAME |
| Last name (15,55) | ACSLNAM | 25 | Out | default, underline | CUST-LAST-NAME |
| Address line 1 (16,10) | ACSADL1 | 50 | Out | default, underline | CUST-ADDR-LINE-1 |
| State (16,73) | ACSSTTE | 2 | Out | default, underline | CUST-ADDR-STATE-CD |
| Address line 2 (17,10) | ACSADL2 | 50 | Out | default, underline | CUST-ADDR-LINE-2 |
| Zip (17,73) | ACSZIPC | 5 | Out | default, underline, JUSTIFY=RIGHT | First 5 characters of CUST-ADDR-ZIP X(10) |
| City (18,10) | ACSCITY | 50 | Out | default, underline | CUST-ADDR-LINE-3 (the third address line is the city) |
| Country (18,73) | ACSCTRY | 3 | Out | default, underline | CUST-ADDR-COUNTRY-CD |
| Phone 1 (19,10) | ACSPHN1 | 13 | Out | default, underline | First 13 characters of CUST-PHONE-NUM-1 X(15), normally `(AAA)BBB-CCCC` |
| Government id (19,58) | ACSGOVT | 20 | Out | default, underline | CUST-GOVT-ISSUED-ID |
| Phone 2 (20,10) | ACSPHN2 | 13 | Out | default, underline | First 13 characters of CUST-PHONE-NUM-2 |
| EFT Account Id (20,41) | ACSEFTC | 10 | Out | default, underline | CUST-EFT-ACCOUNT-ID |
| Primary Card Holder (20,78) | ACSPFLG | 1 | Out | default, underline | CUST-PRI-CARD-HOLDER-IND |
| Info message (22,23) | INFOMSG | 45 | Out | PROT, neutral; program sets dark when empty, neutral otherwise | WS-INFO-MSG X(40) |
| Error message (23,1) | ERRMSG | 78 | Out | ASKIP, BRT, red | WS-RETURN-MSG X(75) |
| Key legend (24,1) | (unnamed) | - | Out | ASKIP | `  F3=Exit ` |

Labels (all ASKIP constants): `View Account` (4,33); `Account Number :` (5,19); `Active Y/N: `
(5,57); `Opened:` (6,8); `Credit Limit        :` (6,39); `Expiry:` (7,8);
`Cash credit Limit   :` (7,39); `Reissue:` (8,8); `Current Balance     :` (8,39);
`Current Cycle Credit:` (9,39); `Account Group:` (10,8); `Current Cycle Debit :` (10,39);
`Customer Details` (11,32); `Customer id  :` (12,8); `SSN:` (12,49); `Date of birth:` (13,8);
`FICO Score:` (13,49); `First Name` (14,1); `Middle Name: ` (14,28); `Last Name : ` (14,55);
`Address:` (16,1); `State ` (16,63); `Zip` (17,63); `City ` (18,1); `Country` (18,63);
`Phone 1:` (19,1); `Government Issued Id Ref    : ` (19,24); `Phone 2:` (20,1);
`EFT Account Id: ` (20,24); `Primary Card Holder Y/N:` (20,53). Source: `app/bms/COACTVW.bms`.

## Processing flow
1. `0000-MAIN`: HANDLE ABEND LABEL(`ABEND-ROUTINE`). INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE
   (every edit flag becomes space, which is the "blank" value of the account filter flag), and
   WS-COMMAREA. Clear the return message.
2. IF EIBCALEN = 0, or CDEMO-FROM-PROGRAM = `COMEN01C` and not PGM-REENTER: INITIALIZE
   CARDDEMO-COMMAREA and WS-THIS-PROGCOMMAREA (PGM-CONTEXT becomes 0 = ENTER). OTHERWISE copy both
   from DFHCOMMAREA (NAV-COACTVWC-06).
3. `YYYY-STORE-PFKEY` maps EIBAID to the CCARD-AID flags (PF13-PF24 act as PF1-PF12). IF the AID is
   neither ENTER nor PF3, treat it as ENTER (NAV-COACTVWC-01).
4. EVALUATE:
   1. PF3: exit by XCTL (NAV-COACTVWC-02).
   2. PGM-ENTER: `1000-SEND-MAP` (empty search screen), then `COMMON-RETURN` (NAV-COACTVWC-03).
   3. PGM-REENTER: `2000-PROCESS-INPUTS`. IF an input error was found: `1000-SEND-MAP`.
      OTHERWISE: `9000-READ-ACCT`, then `1000-SEND-MAP`. Then `COMMON-RETURN` (NAV-COACTVWC-04).
   4. OTHERWISE: send plain text `UNEXPECTED DATA SCENARIO` and end the conversation
      (ERR-COACTVWC-07).
5. `COMMON-RETURN`: copy CARDDEMO-COMMAREA and the extension into WS-COMMAREA; RETURN
   TRANSID(`CAVW`) COMMAREA(WS-COMMAREA) LENGTH(2000) (NAV-COACTVWC-05).

**`2000-PROCESS-INPUTS`**
1. `2100-RECEIVE-MAP`: RECEIVE MAP(`CACTVWA`) MAPSET(`COACTVW`) INTO CACTVWAI. RESP is not checked
   (ERR-COACTVWC-09).
2. `2200-EDIT-MAP-INPUTS`: set input OK and filter valid. IF ACCTSID is `*` or spaces, CC-ACCT-ID =
   LOW-VALUES; OTHERWISE CC-ACCT-ID = ACCTSID. Perform `2210-EDIT-ACCOUNT` (VR-COACTVWC-01,
   VR-COACTVWC-02). Then IF the filter is blank, set the message to `No input received`
   (VR-COACTVWC-01).
3. Copy the return message to CCARD-ERROR-MSG and the program, mapset, and map names to the
   CCARD-NEXT-* fields (not used afterwards).

**`9000-READ-ACCT`** (BR-COACTVWC-01)
1. Set "no info message". Key = CDEMO-ACCT-ID.
2. `9200-GETCARDXREF-BYACCT`: READ `CXACAIX` RIDFLD = the 11-digit account id. NORMAL: CDEMO-CUST-ID
   = XREF-CUST-ID, CDEMO-CARD-NUM = XREF-CARD-NUM. NOTFND: ERR-COACTVWC-01. OTHER: ERR-COACTVWC-02.
3. IF the account filter is now "not OK" (any cross-reference failure), stop.
4. `9300-GETACCTDATA-BYACCT`: READ `ACCTDAT` INTO ACCOUNT-RECORD with the same key. NORMAL: mark
   "account found". NOTFND: ERR-COACTVWC-03. OTHER: ERR-COACTVWC-04.
5. The test `IF DID-NOT-FIND-ACCT-IN-ACCTDAT` that would stop here is never true (Q-COACTVWC-01), so
   processing always continues.
6. `9400-GETCUSTDATA-BYCUST`: READ `CUSTDAT` INTO CUSTOMER-RECORD with key CDEMO-CUST-ID (9 digits).
   NORMAL: mark "customer found". NOTFND: ERR-COACTVWC-05. OTHER: ERR-COACTVWC-06.

**`1000-SEND-MAP`**
1. `1100-SCREEN-INIT`: clear the output map to LOW-VALUES; titles, transaction id, program name,
   date `MM/DD/YY`, and time `HH:MM:SS` (BR-COACTVWC-08).
2. `1200-SETUP-SCREEN-VARS`:
   - IF EIBCALEN = 0: set the info message to the prompt; nothing else is moved.
   - OTHERWISE: account number echo (BR-COACTVWC-05); IF account found OR customer found, move the
     account fields (BR-COACTVWC-02); IF customer found, move the customer fields
     (BR-COACTVWC-03).
   - IF no info message is set: info = `Enter or update id of account to display`
     (BR-COACTVWC-06).
   - ERRMSG = WS-RETURN-MSG; INFOMSG = WS-INFO-MSG.
3. `1300-SETUP-SCREEN-ATTRS`: BR-COACTVWC-07.
4. `1400-SEND-SCREEN`: set PGM-REENTER; SEND MAP(`CACTVWA`) MAPSET(`COACTVW`) FROM CACTVWAO CURSOR
   ERASE FREEKB. RESP is captured and not checked.

## Rules
### Business rules
- **BR-COACTVWC-01**: Lookup chain. The account number is resolved in this order:
  1. Card cross-reference by account (`CXACAIX`), giving the customer id and a card number.
  2. Account master (`ACCTDAT`) by account number.
  3. Customer master (`CUSTDAT`) by the customer id from step 1.

  A cross-reference failure stops the chain. An account-master failure does not (Q-COACTVWC-01).
  If the account has several cards, the cross-reference path returns the first record for the
  account and that card number goes to CDEMO-CARD-NUM. Source:
  `app/cbl/COACTVWC.cbl:687-718 (9000-READ-ACCT)`, `:723-770 (9200-GETCARDXREF-BYACCT)`,
  `:774-820 (9300-GETACCTDATA-BYACCT)`, `:825-869 (9400-GETCUSTDATA-BYCUST)`.
- **BR-COACTVWC-02**: Account fields displayed (when the account or the customer was found):
  - Active Y/N = ACCT-ACTIVE-STATUS.
  - Current Balance, Credit Limit, Cash credit Limit, Current Cycle Credit, Current Cycle Debit =
    the S9(10)V99 amounts edited by the map PICOUT `+ZZZ,ZZZ,ZZZ.99` (sign always shown, leading
    zeros suppressed up to the decimal point, so zero shows as `+           .00`, 9 integer
    positions; Q-COACTVWC-08).
  - Opened, Expiry, Reissue = ACCT-OPEN-DATE, ACCT-EXPIRAION-DATE, ACCT-REISSUE-DATE as stored.
  - Account Group = ACCT-GROUP-ID.

  Source: `app/cbl/COACTVWC.cbl:471-491 (1200-SETUP-SCREEN-VARS)`.
- **BR-COACTVWC-03**: Customer fields displayed (only when the customer was found):
  - Customer id = CUST-ID.
  - SSN = CUST-SSN characters 1-3, `-`, 4-5, `-`, 6-9 (Q-COACTVWC-09).
  - FICO Score, Date of birth, First/Middle/Last name, Address lines 1 and 2, State, Country,
    Government Issued Id, EFT Account Id, Primary Card Holder = the stored values.
  - City = CUST-ADDR-LINE-3.
  - Zip = first 5 characters of CUST-ADDR-ZIP.
  - Phone 1 and 2 = first 13 characters of CUST-PHONE-NUM-1/2.

  Source: `app/cbl/COACTVWC.cbl:493-523 (1200-SETUP-SCREEN-VARS)`.
- **BR-COACTVWC-04**: The program never writes. It issues at most three READs (no UPDATE) per ENTER.
  Source: whole program.
- **BR-COACTVWC-05**: Account number echo. IF the account filter is blank, the field is sent as
  LOW-VALUES (shown empty, or `*` on a re-entry turn per BR-COACTVWC-07). OTHERWISE it shows
  CC-ACCT-ID as typed, including invalid input. On the first display after entry the filter flag
  is blank, so the field is empty. Source: `app/cbl/COACTVWC.cbl:462-469`.
- **BR-COACTVWC-06**: Info line. It is `Enter or update id of account to display` in every case:
  on entry, after errors, and after a successful display. `9000-READ-ACCT` clears the info message
  and nothing sets another one (Q-COACTVWC-04). Source: `app/cbl/COACTVWC.cbl:113-116`,
  `:462-463`, `:527-534`, `:689`.
- **BR-COACTVWC-07**: Screen attributes:
  - ACCTSID gets MDT on (`DFHBMFSE`, unprotected) on every send.
  - The cursor is always on ACCTSID (all three EVALUATE branches do the same).
  - ACCTSID color is default; red when the account filter is "not OK" (format error or any
    cross-reference or account-master failure).
  - IF the filter is blank and this is a re-entry turn, ACCTSID shows `*` in red.
  - INFOMSG is dark when there is no info message, neutral otherwise.

  Source: `app/cbl/COACTVWC.cbl:541-572 (1300-SETUP-SCREEN-ATTRS)`.
- **BR-COACTVWC-08**: Header: TITLE01/TITLE02 from COTTL01Y, TRNNAME `CAVW`, PGMNAME `COACTVWC`,
  CURDATE = month/day/2-digit year, CURTIME = hours:minutes:seconds, all from FUNCTION
  CURRENT-DATE. Source: `app/cbl/COACTVWC.cbl:431-455 (1100-SCREEN-INIT)`.
- **BR-COACTVWC-09**: Context passed on: CDEMO-ACCT-ID = the edited account number (0 when blank or
  invalid); CDEMO-CUST-ID and CDEMO-CARD-NUM = values from the cross-reference record when it was
  read. These survive into the next program on PF3. Source: `app/cbl/COACTVWC.cbl:660`, `:675`,
  `:678`, `:739-740`.

### Validation rules
- **VR-COACTVWC-01**: Account number is required. When ACCTSID is LOW-VALUES, spaces, or `*`: input
  error, filter blank, CDEMO-ACCT-ID = 0, no file is read. `2210-EDIT-ACCOUNT` sets
  `Account number not provided`, but `2200-EDIT-MAP-INPUTS` then overwrites it, so the message
  shown is `No input received`. ACCTSID shows `*` in red with the cursor on it. Source:
  `app/cbl/COACTVWC.cbl:628-633`, `:640-642 (2200-EDIT-MAP-INPUTS)`,
  `:653-662 (2210-EDIT-ACCOUNT)`.
- **VR-COACTVWC-02**: Account number must be numeric in all 11 positions and not all zeros.
  Otherwise: input error, filter "not OK", CDEMO-ACCT-ID = 0, message
  `Account Filter must  be a non-zero 11 digit number` (two spaces between "must" and "be"),
  ACCTSID echoed in red, no file read. A number shorter than 11 digits is not numeric (trailing
  blanks), unless the terminal enforces MUSTFILL (Q-COACTVWC-06). Source:
  `app/cbl/COACTVWC.cbl:666-680 (2210-EDIT-ACCOUNT)`.
- **VR-COACTVWC-03**: Message precedence. Every message is written only when no message is set
  yet (WS-RETURN-MSG-OFF guard), so the first failure wins. The exception is
  `No input received` (VR-COACTVWC-01), which always replaces the earlier text. File-error texts
  (ERR-COACTVWC-02, -04, -06) are moved without the guard and replace any earlier message. Source:
  `app/cbl/COACTVWC.cbl:657`, `:670`, `:744`, `:793`, `:845`, `:766`, `:816`, `:865`.

### Navigation and keys
- **NAV-COACTVWC-01**: AID handling. PF13-PF24 are mapped to PF1-PF12. Only ENTER and PF3 are
  valid; every other AID (including CLEAR, PA1, PA2) is treated as ENTER, with no message. Source:
  `app/cbl/COACTVWC.cbl:299-314`, `app/cpy/CSSTRPFY.cpy:17-79`.
- **NAV-COACTVWC-02**: PF3 exit:
  - CDEMO-TO-TRANID = CDEMO-FROM-TRANID, or `CM00` when that is LOW-VALUES or spaces.
  - CDEMO-TO-PROGRAM = CDEMO-FROM-PROGRAM, or `COMEN01C` when that is LOW-VALUES or spaces.
  - Then CDEMO-FROM-TRANID = `CAVW`, CDEMO-FROM-PROGRAM = `COACTVWC`, user type `U`, PGM-ENTER,
    CDEMO-LAST-MAPSET = `COACTVW`, CDEMO-LAST-MAP = `CACTVWA`.
  - XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA).

  Because menu entry clears FROM-PROGRAM, PF3 always returns to COMEN01C in practice. PF3 is
  honored on the first turn too (before any map is received). Source:
  `app/cbl/COACTVWC.cbl:324-352 (0000-MAIN)`.
- **NAV-COACTVWC-03**: PGM-ENTER (menu entry, direct CAVW, or PF3 return from elsewhere with
  context 0): send the empty search screen with info `Enter or update id of account to display`
  and the cursor on the account number. Source: `app/cbl/COACTVWC.cbl:353-360`.
- **NAV-COACTVWC-04**: PGM-REENTER: receive, edit, read (only when the edit passed), and redisplay.
  Source: `app/cbl/COACTVWC.cbl:361-374`.
- **NAV-COACTVWC-05**: Every non-exit turn ends with RETURN TRANSID(`CAVW`) and the 2000-byte
  COMMAREA. Source: `app/cbl/COACTVWC.cbl:394-407 (COMMON-RETURN)`.
- **NAV-COACTVWC-06**: COMMAREA initialization. When EIBCALEN = 0, or the caller is COMEN01C and
  context is not REENTER, the whole CARDDEMO-COMMAREA and the extension are INITIALIZEd
  (Q-COACTVWC-03); otherwise both are restored from DFHCOMMAREA. Source:
  `app/cbl/COACTVWC.cbl:282-293`.

### Error handling
- **ERR-COACTVWC-01**: Cross-reference READ NOTFND: input error, filter "not OK" (ACCTSID red), and
  (if no message yet) the message is built from
  `Account:` + the 11-digit key + ` not found in` + ` Cross ref file.  Resp:` + RESP + ` Reas:` +
  RESP2, where RESP and RESP2 are the binary codes moved into X(10) fields (9 digits, zero-padded,
  followed by a space). The result is cut at 75 characters, so for NOTFND (RESP 13, RESP2 80) the
  screen shows `Account:00000000099 not found in Cross ref file.  Resp:000000013  Reas:0000`. The
  account and customer files are not read; no details are shown. Source:
  `app/cbl/COACTVWC.cbl:741-758`, `:697-699`.
- **ERR-COACTVWC-02**: Cross-reference READ other RESP: input error, filter "not OK", message
  `File Error: READ     on CXACAIX   returned RESP nnnnnnnnn ,RESP2 nnnnnnnnn ` (layout
  `WS-FILE-ERROR-MESSAGE`: `File Error: ` + operation X(8) + ` on ` + file X(9) + ` returned RESP `
  + RESP X(10) + `,RESP2 ` + RESP2 X(10), cut to 75 characters). No further reads. Source:
  `app/cbl/COACTVWC.cbl:86-105`, `:759-768`.
- **ERR-COACTVWC-03**: Account master READ NOTFND: input error, filter "not OK" (ACCTSID red), and
  (if no message yet) message `Account:` + key + ` not found in` + ` Acct Master file.Resp:` + RESP +
  ` Reas:` + RESP2 (cut to 75). Processing continues to the customer read (Q-COACTVWC-01). Source:
  `app/cbl/COACTVWC.cbl:789-807`.
- **ERR-COACTVWC-04**: Account master READ other RESP: input error, filter "not OK", message
  `File Error: READ     on ACCTDAT   returned RESP ...` (same layout). Processing continues to the
  customer read. Source: `app/cbl/COACTVWC.cbl:809-818`.
- **ERR-COACTVWC-05**: Customer master READ NOTFND: input error, customer filter "not OK" (no
  visible effect; ACCTSID is not turned red), and (if no message yet) message `CustId:` + the
  9-digit key + ` not found` + ` in customer master.Resp: ` + RESP + ` REAS:` + RESP2 (cut to 75).
  Account fields are still shown if the account was found; customer fields are not. Source:
  `app/cbl/COACTVWC.cbl:839-857`.
- **ERR-COACTVWC-06**: Customer master READ other RESP: input error, message
  `File Error: READ     on CUSTDAT   returned RESP ...` (same layout). Source:
  `app/cbl/COACTVWC.cbl:858-868`.
- **ERR-COACTVWC-07**: Unexpected context (PGM-CONTEXT neither 0 nor 1): ABEND-CULPRIT =
  `COACTVWC`, ABEND-CODE = `0001` (not shown), message `UNEXPECTED DATA SCENARIO` sent with SEND TEXT
  ERASE FREEKB, then RETURN without TRANSID, ending the conversation. Source:
  `app/cbl/COACTVWC.cbl:375-382`, `:877-890 (SEND-PLAIN-TEXT)`.
- **ERR-COACTVWC-08**: Any abend (HANDLE ABEND) goes to `ABEND-ROUTINE`: IF ABEND-MSG is LOW-VALUES
  it becomes `UNEXPECTED ABEND OCCURRED.`; ABEND-CULPRIT = `COACTVWC`; SEND FROM(ABEND-DATA)
  NOHANDLE; HANDLE ABEND CANCEL; ABEND ABCODE(`9999`). Source:
  `app/cbl/COACTVWC.cbl:916-937 (ABEND-ROUTINE)`, `app/cpy/CSMSG02Y.cpy`.
- **ERR-COACTVWC-09**: RECEIVE MAP and SEND MAP responses are captured and not checked. Because
  ACCTSID is always sent with MDT on, a RECEIVE normally returns it. Source:
  `app/cbl/COACTVWC.cbl:610-617`, `:583-590`.

## Data effects
None on files. Per ENTER with a valid account number: one READ of CXACAIX, then (unless it
failed) one READ of ACCTDAT and one of CUSTDAT, all without UPDATE, so no locks are held and no
SYNCPOINT is issued. COMMAREA effects: CDEMO-ACCT-ID, CDEMO-CUST-ID, CDEMO-CARD-NUM
(BR-COACTVWC-09); on PF3 the routing fields and user type (NAV-COACTVWC-02).

## Quirks
- **Q-COACTVWC-01**: An account missing from ACCTDAT does not stop processing. The 88-level
  `DID-NOT-FIND-ACCT-IN-ACCTDAT` is tested but never set (its SET is commented out). The customer
  is still read. If the customer is found, `1200-SETUP-SCREEN-VARS` shows the account fields
  (because "account OR customer found") from an ACCOUNT-RECORD that was never read in this task.
  The result is unpredictable values or a data exception on the numeric edits. The same applies
  after a non-NOTFND account read error. Recommendation: fix (stop after an account-master
  failure and show no account fields). Source: `app/cbl/COACTVWC.cbl:131-132`, `:704-706`,
  `:471-472`, `:792`.
- **Q-COACTVWC-02**: For a blank account number, `2210-EDIT-ACCOUNT` sets
  `Account number not provided`, which is immediately replaced by `No input received`. The first
  text is never shown. Several 88-level messages are never used: `PF03 pressed.Exiting`,
  `Account number must be a non zero 11 digit number` (twice), the three `Did not find ...` texts,
  `Error reading account card xref File`, and `Looks Good.... so far`. Recommendation: decide (keep
  the observable `No input received`). Source: `app/cbl/COACTVWC.cbl:117-138`, `:640-642`.
- **Q-COACTVWC-03**: Entry from the menu INITIALIZEs the whole CARDDEMO-COMMAREA. This erases
  CDEMO-USER-ID and CDEMO-USER-TYPE, and PF3 then forces user type `U`. An administrator who views
  an account returns to the menu as a regular user with no user id. Recommendation: fix (keep
  session identity). Source: `app/cbl/COACTVWC.cbl:282-286`, `:344`.
- **Q-COACTVWC-04**: The info message `Displaying details of given Account` is defined but never
  set. After a successful display the info line still says
  `Enter or update id of account to display`. Recommendation: decide. Source:
  `app/cbl/COACTVWC.cbl:115-116`, `:689`, `:528-530`.
- **Q-COACTVWC-05**: Not-found messages expose raw CICS RESP/RESP2 numbers and are cut at 75
  characters, so `Reas:` shows only the first 4 digits. Recommendation: fix (business wording,
  log the codes). Source: `app/cbl/COACTVWC.cbl:747-757`, `:796-806`, `:846-856`.
- **Q-COACTVWC-06**: ACCTSID has PICIN `99999999999` and VALIDN=MUSTFILL. MUSTFILL is enforced only
  by terminals with the field-validation feature. Without it, a short number such as `1` arrives
  left-justified with trailing blanks, fails the NUMERIC test, and gets the format error. The
  message text also has a double space (`must  be`). Recommendation: fix in the target (accept
  1-11 digits and left-pad with zeros, or keep an exact 11-digit rule, but decide explicitly).
  Source: `app/bms/COACTVW.bms:84-90`, `app/cbl/COACTVWC.cbl:666-673`.
- **Q-COACTVWC-07**: Dead code: the `IF INPUT-ERROR` block after the EVALUATE (every branch has
  already returned), the duplicate `0000-MAIN-EXIT` paragraph, `SEND-LONG-TEXT`, the CA-CALL-CONTEXT
  extension, the card-program literals (`COCRDLIC`, `COCRDUPC`, `COCRDSLC` and their maps), and
  copybooks CVACT02Y, CSMSG01Y, CSUSR01Y. Recommendation: drop. Source:
  `app/cbl/COACTVWC.cbl:387-392`, `:408-413`, `:896-909`, `:151-183`.
- **Q-COACTVWC-08**: Amounts are S9(10)V99 but the PICOUT `+ZZZ,ZZZ,ZZZ.99` has 9 integer
  positions, so the highest digit of values of 1,000,000,000.00 or more is lost on display.
  Recommendation: fix (show the full value). Source: `app/bms/COACTVW.bms:117-121`,
  `app/cpy/CVACT01Y.cpy`.
- **Q-COACTVWC-09**: The full SSN (formatted) and government id are displayed to any signed-on
  user, with no authorization check. Recommendation: decide (masking and access policy, see
  `docs/data-handling.md`). Source: `app/cbl/COACTVWC.cbl:496-504`, `:519`.
- **Q-COACTVWC-10**: Phone fields show only 13 of 15 stored characters. The stored format
  `(AAA)BBB-CCCC` is 13 characters, so nothing is lost for well-formed data. Recommendation:
  preserve (format phones in the UI). Source: `app/bms/COACTVW.bms:319-321`.

## Test scenarios
SSNs and government ids are masked. The full values are in `app/data/ASCII/custdata.txt`.

- **TS-COACTVWC-01** (NAV-COACTVWC-03, NAV-COACTVWC-06, BR-COACTVWC-06): Given main menu option 1,
  when the program starts, then the screen shows an empty, editable account number with the
  cursor on it, no details, info `Enter or update id of account to display`, and a blank error
  line. CDEMO-USER-ID is now blank (Q-COACTVWC-03).
- **TS-COACTVWC-02** (BR-COACTVWC-01, -02, -03): Given the search screen, when `00000000001` is
  entered, then the screen shows:
  - Active `Y`; Opened `2014-11-20`; Expiry `2025-05-20`; Reissue `2025-05-20`.
  - Credit Limit `+      2,020.00`; Cash credit Limit `+      1,020.00`; Current Balance
    `+        194.00`; cycle credit and debit `+           .00`; Account Group blank.
  - Customer id `000000001`; SSN `***-**-****` (formatted 3-2-4 with dashes); DOB `1961-06-08`;
    FICO `274`; name `Immanuel` / `Madeline` / `Kessler`.
  - Address `618 Deshaun Route` / `Apt. 802`; City `Altenwerthshire`; State `NC`; Zip `12546`;
    Country `USA`.
  - Phones `(908)119-8310` and `(373)693-8684`; EFT `0053581756`; Primary `Y`.
  - Info `Enter or update id of account to display`; no error.

  CDEMO-ACCT-ID = 1 and CDEMO-CUST-ID = 1.
- **TS-COACTVWC-03** (VR-COACTVWC-01, BR-COACTVWC-07): Given the search screen, when ENTER is
  pressed with the account number empty, then the error is `No input received`, the account field
  shows `*` in red, and no file is read.
- **TS-COACTVWC-04** (VR-COACTVWC-01): Given the field shows `*` from the previous turn, when ENTER
  is pressed again unchanged, then the result is the same as TS-COACTVWC-03.
- **TS-COACTVWC-05** (VR-COACTVWC-02): When `00000000000` is entered, then the error is
  `Account Filter must  be a non-zero 11 digit number`, the field is red and shows
  `00000000000`, and no details are shown.
- **TS-COACTVWC-06** (VR-COACTVWC-02, Q-COACTVWC-06): On a terminal without MUSTFILL enforcement,
  when `1` is entered, then the error is `Account Filter must  be a non-zero 11 digit number`.
- **TS-COACTVWC-07** (ERR-COACTVWC-01): When `00000000099` (not in the cross-reference) is entered,
  then the error is `Account:00000000099 not found in Cross ref file.  Resp:000000013  Reas:0000`,
  the field is red, and no details are shown.
- **TS-COACTVWC-08** (ERR-COACTVWC-03, Q-COACTVWC-01): Given a cross-reference record for account
  `00000000077` pointing at customer `000000001`, and no account `00000000077` in ACCTDAT, when
  `00000000077` is entered, then the error begins `Account:00000000077 not found in Acct Master
  file.Resp:000000013`, the customer details of customer 1 are shown, and the account fields
  contain unpredictable values (or the task abends with `9999`).
- **TS-COACTVWC-09** (ERR-COACTVWC-05): Given the cross-reference for account `00000000001` points
  to a customer id missing from CUSTDAT, when `00000000001` is entered, then the account fields
  are shown, the customer fields are empty, the account field is not red, and the error begins
  `CustId:000000001 not found in customer master.Resp: 000000013 ` (with that customer id).
- **TS-COACTVWC-10** (ERR-COACTVWC-02): Given CXACAIX is closed or disabled, when `00000000001` is
  entered, then the error line begins `File Error: READ     on CXACAIX   returned RESP ` and the
  account field is red.
- **TS-COACTVWC-11** (NAV-COACTVWC-02, Q-COACTVWC-03): Given any screen state, when PF3 is pressed,
  then XCTL goes to COMEN01C with CDEMO-FROM-PROGRAM = `COACTVWC`, CDEMO-FROM-TRANID = `CAVW`,
  CDEMO-PGM-CONTEXT = 0, CDEMO-USER-TYPE = `U`, CDEMO-LAST-MAPSET = `COACTVW`.
- **TS-COACTVWC-12** (NAV-COACTVWC-01): Given account `00000000001` is displayed, when PF5 (or CLEAR,
  PA1) is pressed, then the account is re-read and displayed again exactly as for ENTER. PF15 is
  folded to PF3 by CSSTRPFY and exits like PF3.
- **TS-COACTVWC-13** (BR-COACTVWC-05): Given account `00000000001` is displayed, when the field is
  changed to `00000000002` and ENTER is pressed, then account 2 is shown (balance `+        158.00`,
  credit limit `+      6,130.00`, cash limit `+      5,448.00`, opened `2013-06-19`).

## Open questions
- Should a regular user be allowed to view any account and its customer's SSN, or only accounts
  linked to their own user id (Q-COACTVWC-09)? No authorization check exists.
- When an account has more than one card, which card should be passed on in CDEMO-CARD-NUM? The
  source takes whatever the alternate index returns first.
- How does the production terminal population handle VALIDN=MUSTFILL (Q-COACTVWC-06)? This decides
  whether short account numbers ever reach the program.
- Should the view pre-fill and display CDEMO-ACCT-ID when called by another program with an
  account already chosen? The source ignores it.
