# COACTUPC: Update an account and its customer, with validation, confirmation, and optimistic locking

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COACTUPC.cbl` (4236 lines; includes procedure copybook `CSUTLDPY` at line 4232) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CAUP (`app/csd/CARDDEMO.CSD:306-308`) |
| BMS map | mapset `COACTUP` / map `CACTUPA` (`app/bms/COACTUP.bms`, symbolic map `app/cpy-bms/COACTUP.CPY`) |
| Copybooks | `CSUTLDWY` (date-edit work area), `CVCRD01Y` (CC-WORK-AREA: CC-ACCT-ID, AID flags), `CSLKPCDY` (area-code, state, and state/zip lookup tables), `COTTL01Y`, `COACTUP` (symbolic map), `CSDAT01Y`, `CSMSG01Y` (unused), `CSMSG02Y` (ABEND-DATA), `CSUSR01Y` (unused), `CVACT01Y` (ACCOUNT-RECORD), `CVACT03Y` (CARD-XREF-RECORD), `CVCUS01Y` (CUSTOMER-RECORD), `COCOM01Y` (CARDDEMO-COMMAREA), `CSSETATY` (attribute snippet, copied 39 times), `CSSTRPFY` (YYYY-STORE-PFKEY), `CSUTLDPY` (date-edit paragraphs), IBM `DFHBMSCA`, `DFHAID` |
| Calls / XCTL targets | CALL `CSUTLDTC` (date validation through CEEDAYS, from `CSUTLDPY`). XCTL to `CDEMO-FROM-PROGRAM`, or `COMEN01C` when that is blank (PF3). |
| Data stores | `CXACAIX` (alternate-index path over the card cross-reference by account, ENT-CARD-XREF): Read, key XREF-ACCT-ID (11). `ACCTDAT` (ENT-ACCOUNT): Read; Read UPDATE then Rewrite; key ACCT-ID (11). `CUSTDAT` (ENT-CUSTOMER): Read; Read UPDATE then Rewrite; key CUST-ID (9). |

## Purpose
Lets a user fetch an account by number, see the account and its customer on one screen, change
almost any field of both records, have every field validated, confirm with F5, and save. The
save re-reads both records with UPDATE, checks that nobody else changed them since they were
fetched, and rewrites the account record and then the customer record. It is reached from main
menu option 2 "Account Update" (`app/cpy/COMEN02Y.cpy:34`). It is the only online program that
changes ACCTDAT balances, limits, and dates directly, and the only one that changes customer data.

## Invocation and state

**Entry paths**

| Entry | How detected | Effect |
|:--|:--|:--|
| Main menu option 2 (`COMEN01C` XCTL with PGM-CONTEXT 0) | CDEMO-FROM-PROGRAM = `COMEN01C` and not PGM-REENTER | CARDDEMO-COMMAREA and the program extension are INITIALIZEd (Q-COACTUPC-21); PGM-ENTER; state "details not fetched"; empty search screen |
| Transaction CAUP typed with no COMMAREA | EIBCALEN = 0 | Same as menu entry |
| Own pseudo-conversational turn | PGM-REENTER (set after every first send) | Map received, processed according to the state below |

**CARDDEMO-COMMAREA usage**

| Field | Read | Written |
|:--|:--|:--|
| CDEMO-FROM-PROGRAM / CDEMO-FROM-TRANID | Entry path; PF3 target | `COACTUPC` / `CAUP` on PF3 only |
| CDEMO-TO-PROGRAM / CDEMO-TO-TRANID | No | On PF3: FROM values, defaults `COMEN01C` / `CM00` |
| CDEMO-PGM-CONTEXT | Yes | ENTER on fresh entry, after a completed or failed save, and on PF3; REENTER after every send |
| CDEMO-ACCT-ID | No | Edited account number (0 when blank or invalid); ACCT-ID after a fetch; 0 after a completed or failed save |
| CDEMO-CUST-ID | Yes: CUSTDAT key for fetch and save | XREF-CUST-ID, then CUST-ID after a fetch |
| CDEMO-CUST-FNAME / MNAME / LNAME, CDEMO-ACCT-STATUS, CDEMO-CARD-NUM | No | From the fetched records (BR-COACTUPC-03) |
| CDEMO-USER-TYPE | No | Forced to `U` on PF3 |
| CDEMO-LAST-MAPSET | Yes: equal to `COCRDLI` gives the account field default color | `COACTUP ` truncated to 7 = `COACTUP` on PF3 |
| CDEMO-LAST-MAP | No | `CACTUPA` on PF3 |

**Program-specific COMMAREA extension** `WS-THIS-PROGCOMMAREA` (after CARDDEMO-COMMAREA; the
RETURN COMMAREA is 2000 bytes). Source: `app/cbl/COACTUPC.cbl:652-848`.

| Item | Contents |
|:--|:--|
| ACUP-CHANGE-ACTION X(1) | Conversation state, see BR-COACTUPC-01 |
| ACUP-OLD-DETAILS | Snapshot of the fetched records: account id, status, the five amounts (X(12) redefined S9(10)V99), open / expiry / reissue dates as `YYYYMMDD` (X(8), split into year, month, day), group id, customer id, names, address lines 1-3, state, country, zip, phones as stored X(15), SSN 9(9), government id, DOB `YYYYMMDD`, EFT id, primary flag, FICO 9(3) |
| ACUP-NEW-DETAILS | Same layout, holding the values received from the screen on the latest turn. Phones are kept as parts at positions 2-4, 6-8, 10-13 of an X(15); SSN as 3 + 2 + 4 characters |

The X(15) text of the five amounts (`ACUP-NEW-*-X`) lives in working storage, not in the
COMMAREA, and is rebuilt on every turn.

## Screen
Mock-up after fetching account `00000000024` (state S), using `app/data/ASCII/acctdata.txt` line 24
and `app/data/ASCII/custdata.txt` line 24, with SSN and government id masked. Text starts one
column after each BMS POS. Alignment is approximate; the field table gives exact positions.

```
         1         2         3         4         5         6         7         8
12345678901234567890123456789012345678901234567890123456789012345678901234567890
 Tran: CAUP           AWS Mainframe Modernization            Date: mm/dd/yy
 Prog: COACTUPC              CardDemo                         Time: hh:mm:ss

                                 Update Account
                    Account Number : 00000000024      Active Y/N:  Y
         Opened : 2015 - 08 - 08        Credit Limit        : +      5,174.00
         Expiry : 2025 - 02 - 11        Cash credit Limit   : +      4,129.00
         Reissue: 2025 - 02 - 11        Current Balance     : +        400.00
                                        Current Cycle Credit: +          0.00
         Account Group:                 Current Cycle Debit : +          0.00
                                 Customer Details
         Customer id  : 000000024                SSN:   *** - ** - ****
         Date of birth: 1996 - 01 - 24           FICO Score:   711
 First Name                 Middle Name:               Last Name :
 Stefanie                   Verla                      Dickinson
 Address: 6367 Stracke River                                    State   KS
          Apt. 444                                              Zip  15414
 City     East Otho                                             Country USA
 Phone 1: 617 348 9142  Government Issued Id Ref    : ********************
 Phone 2: 330 116 5634  EFT Account Id: 0005459662 Primary Card Holder Y/N:Y

                       Update account details presented above.
<row 23: ERRMSG, red, bright>
 ENTER=Process F3=Exit F5=Save F12=Cancel     (F5 and F12 dark unless enabled)
```

Program attribute control overrides the BMS attributes on every send (BR-COACTUPC-18): "editable"
below means unprotected with MDT on (`DFHBMFSE`) in states S and E; every field is otherwise
protected with MDT on (`DFHBMPRF`).

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Transaction id (1,7) | TRNNAME | 4 | Out | ASKIP, blue | `CAUP` |
| Title lines (1,21), (2,21) | TITLE01, TITLE02 | 40 | Out | ASKIP, yellow | From `COTTL01Y` |
| Date (1,71), Time (2,71) | CURDATE, CURTIME | 8 | Out | ASKIP, blue | `MM/DD/YY`, `HH:MM:SS` |
| Program (2,7) | PGMNAME | 8 | Out | ASKIP, blue | `COACTUPC` |
| Account number (5,38) | ACCTSID | 11 | In/Out | BMS UNPROT, IC, underline. Editable only in state "not fetched" and after a lock or update failure; protected in S, E, N, C | Search key |
| Active Y/N (5,70) | ACSTTUS | 1 | In/Out | Editable in S/E | ACCT-ACTIVE-STATUS |
| Opened (6,17) (6,24) (6,29) | OPNYEAR, OPNMON, OPNDAY | 4, 2, 2 | In/Out | Editable in S/E; `-` constants between | ACCT-OPEN-DATE parts |
| Credit Limit (6,61) | ACRDLIM | 15 | In/Out | BMS FSET UNPROT; editable in S/E | ACCT-CREDIT-LIMIT, shown `+ZZZ,ZZZ,ZZZ.99` |
| Expiry (7,17) (7,24) (7,29) | EXPYEAR, EXPMON, EXPDAY | 4, 2, 2 | In/Out | Editable in S/E | ACCT-EXPIRAION-DATE parts |
| Cash credit Limit (7,61) | ACSHLIM | 15 | In/Out | Editable in S/E | ACCT-CASH-CREDIT-LIMIT |
| Reissue (8,17) (8,24) (8,29) | RISYEAR, RISMON, RISDAY | 4, 2, 2 | In/Out | Editable in S/E | ACCT-REISSUE-DATE parts |
| Current Balance (8,61) | ACURBAL | 15 | In/Out | Editable in S/E | ACCT-CURR-BAL |
| Current Cycle Credit (9,61) | ACRCYCR | 15 | In/Out | Editable in S/E | ACCT-CURR-CYC-CREDIT |
| Account Group (10,23) | AADDGRP | 10 | In/Out | Editable in S/E | ACCT-GROUP-ID (Q-COACTUPC-01) |
| Current Cycle Debit (10,61) | ACRCYDB | 15 | In/Out | Editable in S/E | ACCT-CURR-CYC-DEBIT |
| Customer id (12,23) | ACSTNUM | 9 | In/Out | Always protected | CUST-ID |
| SSN (12,55) (12,61) (12,66) | ACTSSN1, ACTSSN2, ACTSSN3 | 3, 2, 4 | In/Out | Editable in S/E; BMS INITIAL `999`, `99`, `9999`, shown whenever the program sends LOW-VALUES (state "not fetched") | CUST-SSN parts |
| Date of birth (13,23) (13,30) (13,35) | DOBYEAR, DOBMON, DOBDAY | 4, 2, 2 | In/Out | Editable in S/E | CUST-DOB-YYYY-MM-DD parts |
| FICO Score (13,62) | ACSTFCO | 3 | In/Out | Editable in S/E | CUST-FICO-CREDIT-SCORE |
| First, Middle, Last name (15,1) (15,28) (15,55) | ACSFNAM, ACSMNAM, ACSLNAM | 25 each | In/Out | Editable in S/E | CUST-FIRST/MIDDLE/LAST-NAME |
| Address line 1 (16,10), line 2 (17,10) | ACSADL1, ACSADL2 | 50 | In/Out | Editable in S/E | CUST-ADDR-LINE-1/2 |
| State (16,73) | ACSSTTE | 2 | In/Out | Editable in S/E | CUST-ADDR-STATE-CD |
| Zip (17,73) | ACSZIPC | 5 | In/Out | Editable in S/E | CUST-ADDR-ZIP (first 5 of X(10)) |
| City (18,10) | ACSCITY | 50 | In/Out | Editable in S/E | CUST-ADDR-LINE-3 |
| Country (18,73) | ACSCTRY | 3 | In/Out | Always protected ("edits USA specific") | CUST-ADDR-COUNTRY-CD |
| Phone 1 (19,10) (19,14) (19,18) | ACSPH1A, ACSPH1B, ACSPH1C | 3, 3, 4 | In/Out | Editable in S/E | Area code, prefix, line number of CUST-PHONE-NUM-1 |
| Government id (19,58) | ACSGOVT | 20 | In/Out | Editable in S/E | CUST-GOVT-ISSUED-ID |
| Phone 2 (20,10) (20,14) (20,18) | ACSPH2A, ACSPH2B, ACSPH2C | 3, 3, 4 | In/Out | Editable in S/E | CUST-PHONE-NUM-2 parts |
| EFT Account Id (20,41) | ACSEFTC | 10 | In/Out | Editable in S/E | CUST-EFT-ACCOUNT-ID |
| Primary Card Holder (20,78) | ACSPFLG | 1 | In/Out | Editable in S/E | CUST-PRI-CARD-HOLDER-IND |
| Info message (22,23) | INFOMSG | 45 | Out | Protected; dark when empty, otherwise ASKIP bright | WS-INFO-MSG X(40) |
| Error message (23,1) | ERRMSG | 78 | Out | ASKIP, BRT, red | WS-RETURN-MSG X(75) |
| Key legend (24,1) | FKEYS | 21 | Out | ASKIP, yellow | `ENTER=Process F3=Exit` |
| F5 legend (24,23) | FKEY05 | 7 | Out | ASKIP, DRK, yellow; bright when confirmation is pending | `F5=Save` |
| F12 legend (24,31) | FKEY12 | 10 | Out | ASKIP, DRK, yellow; bright in states E, N, L, F | `F12=Cancel` |

Labels: `Update Account` (4,33); `Opened :`, `Expiry :`, `Reissue:` (rows 6-8, col 8); the other
labels are the same as COACTVW (`Account Number :`, `Active Y/N: `, `Credit Limit        :`,
`Cash credit Limit   :`, `Current Balance     :`, `Current Cycle Credit:`, `Account Group:`,
`Current Cycle Debit :`, `Customer Details`, `Customer id  :`, `SSN:`, `Date of birth:`,
`FICO Score:`, `First Name`, `Middle Name: `, `Last Name : `, `Address:`, `State `, `Zip`,
`City `, `Country`, `Phone 1:`, `Government Issued Id Ref    : `, `Phone 2:`,
`EFT Account Id: `, `Primary Card Holder Y/N:`). Source: `app/bms/COACTUP.bms`.

## Processing flow
1. `0000-MAIN`: HANDLE ABEND LABEL(`ABEND-ROUTINE`). INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE,
   WS-COMMAREA. Clear the return message.
2. IF EIBCALEN = 0, or FROM-PROGRAM = `COMEN01C` and not PGM-REENTER: INITIALIZE CARDDEMO-COMMAREA
   and WS-THIS-PROGCOMMAREA, set PGM-ENTER and state "not fetched". OTHERWISE restore both from
   DFHCOMMAREA.
3. Map the AID (NAV-COACTUPC-01).
4. EVALUATE (first match wins):
   1. PF3: exit (NAV-COACTUPC-02).
   2. State "not fetched" and PGM-ENTER, or FROM-PROGRAM = `COMEN01C` and not PGM-REENTER:
      INITIALIZE the extension; `3000-SEND-MAP`; set PGM-REENTER and "not fetched"; return
      (NAV-COACTUPC-03).
   3. State C, L, or F: INITIALIZE the extension, WS-MISC-STORAGE, and CDEMO-ACCT-ID; set
      PGM-ENTER; `3000-SEND-MAP` (empty search screen); set PGM-REENTER and "not fetched"; return
      (NAV-COACTUPC-04).
   4. OTHERWISE: `1000-PROCESS-INPUTS`, `2000-DECIDE-ACTION`, `3000-SEND-MAP`, return
      (NAV-COACTUPC-05).
5. `COMMON-RETURN`: RETURN TRANSID(`CAUP`) with the 2000-byte COMMAREA (NAV-COACTUPC-06).

**`1000-PROCESS-INPUTS`**
1. `1100-RECEIVE-MAP`: RECEIVE MAP into CACTUPAI (RESP not checked), INITIALIZE ACUP-NEW-DETAILS,
   then convert each field (BR-COACTUPC-07). IF state is "not fetched", only the account number
   is taken.
2. `1200-EDIT-MAP-INPUTS`:
   1. IF state "not fetched": `1210-EDIT-ACCOUNT` (VR-COACTUPC-01, -02); clear ACUP-OLD account
      data; IF the account is blank set `No input received`; stop.
   2. OTHERWISE mark account and customer as found and filters valid; set the info message
      `Details of selected account shown above` (always replaced later, Q-COACTUPC-29);
      `1205-COMPARE-OLD-NEW` (BR-COACTUPC-08).
   3. IF no change, or state N, or state C: clear all field edit flags and stop.
   4. OTHERWISE set state E and run the field edits in the order of VR-COACTUPC-07 to
      VR-COACTUPC-32, then the state/zip cross edit VR-COACTUPC-33.
   5. IF no input error, set state N.

**`2000-DECIDE-ACTION`** (EVALUATE, first match wins)
1. State "not fetched", or PF12: IF the account filter is valid, clear the message,
   `9000-READ-ACCT`, and IF the customer was found set state S (BR-COACTUPC-02, BR-COACTUPC-10).
2. State S: IF no input error and a change was detected, set state N. (Not reachable with a
   change, because step 2.4 above has already moved the state to E or N.)
3. State E: nothing.
4. State N with PF5: `9600-WRITE-PROCESSING`, then set the state from the outcome
   (BR-COACTUPC-15).
5. State N (any other key): nothing.
6. State C: set state S and, IF FROM-TRANID is blank, zero CDEMO-ACCT-ID and CDEMO-CARD-NUM and set
   CDEMO-ACCT-STATUS to LOW-VALUES. (Unreachable: state C is caught in 0000-MAIN.)
7. OTHERWISE: abend (ERR-COACTUPC-12).

**`9000-READ-ACCT`**: INITIALIZE ACUP-OLD-DETAILS; key = CC-ACCT-ID; `9200-GETCARDXREF-BYACCT`;
stop if the filter is not OK; `9300-GETACCTDATA-BYACCT`; (the "not found in ACCTDAT" stop test is
never true, Q-COACTUPC-20); `9400-GETCUSTDATA-BYCUST` with CDEMO-CUST-ID; (same for customer);
`9500-STORE-FETCHED-DATA`.

**`3000-SEND-MAP`**: `3100-SCREEN-INIT` (header, BR-COACTUPC-23); `3200-SETUP-SCREEN-VARS`
(BR-COACTUPC-22, then 3201 / 3202 / 3203 per BR-COACTUPC-04 to -06); `3250-SETUP-INFOMSG`
(BR-COACTUPC-17); `3300-SETUP-SCREEN-ATTRS` (BR-COACTUPC-18 to -20); `3390-SETUP-INFOMSG-ATTRS`
(BR-COACTUPC-21); `3400-SEND-SCREEN` (SEND MAP CURSOR ERASE FREEKB, RESP not checked).

**`9600-WRITE-PROCESSING`**: see BR-COACTUPC-11 to BR-COACTUPC-14.

## Rules
### Business rules
- **BR-COACTUPC-01**: Conversation state ACUP-CHANGE-ACTION.

  | Value | 88-level | Meaning | Entered when |
  |:--|:--|:--|:--|
  | LOW-VALUES or space | ACUP-DETAILS-NOT-FETCHED | Search screen | Entry; after state C, L, F; fetch failed |
  | `S` | ACUP-SHOW-DETAILS | Fetched; original values shown, editable | Fetch succeeded; PF12; save found data changed by someone else |
  | `E` | ACUP-CHANGES-NOT-OK | Changes entered, at least one edit failed | Any change detected (set before edits) |
  | `N` | ACUP-CHANGES-OK-NOT-CONFIRMED | Changes valid, waiting for F5 | All edits passed |
  | `C` | ACUP-CHANGES-OKAYED-AND-DONE | Save finished (or customer lock failed, Q-COACTUPC-02) | After F5 |
  | `L` | ACUP-CHANGES-OKAYED-LOCK-ERROR | Account record could not be locked | After F5 |
  | `F` | ACUP-CHANGES-OKAYED-BUT-FAILED | A REWRITE failed | After F5 |

  Group 88-levels: ACUP-CHANGES-MADE = E, N, C, L, F; ACUP-CHANGES-FAILED = L, F. Source:
  `app/cbl/COACTUPC.cbl:654-668`.
- **BR-COACTUPC-02**: Fetch. With a valid account number: READ CXACAIX (gives CDEMO-CUST-ID and
  CDEMO-CARD-NUM), stop on failure; READ ACCTDAT by account; READ CUSTDAT by CDEMO-CUST-ID; then
  store the snapshot. The state becomes S only when the customer was found. Otherwise it stays
  "not fetched" and the search screen is redisplayed with the error. Source:
  `app/cbl/COACTUPC.cbl:2568-2580 (2000-DECIDE-ACTION)`, `:3608-3649 (9000-READ-ACCT)`,
  `:3650-3799`.
- **BR-COACTUPC-03**: Snapshot (`9500-STORE-FETCHED-DATA`):
  - COMMAREA: CDEMO-ACCT-ID = ACCT-ID, CDEMO-CUST-ID = CUST-ID, CDEMO-CUST-FNAME / MNAME / LNAME,
    CDEMO-ACCT-STATUS = ACCT-ACTIVE-STATUS, CDEMO-CARD-NUM = XREF-CARD-NUM.
  - ACUP-OLD-DETAILS: every account and customer field. Dates are taken from characters 1-4, 6-7,
    9-10 of the stored `YYYY-MM-DD` and kept as `YYYYMMDD`. Amounts are kept as numbers; phones
    as the stored 15 characters.

  Source: `app/cbl/COACTUPC.cbl:3801-3884`.
- **BR-COACTUPC-04**: Original values (`3202-SHOW-ORIGINAL-VALUES`, states S and OTHER):
  - Clear all field edit flags; info message `Update account details presented above.`.
  - IF account or customer found: status; the five amounts edited `+ZZZ,ZZZ,ZZZ.99`; date parts;
    group.
  - IF customer found: customer id; SSN characters 1-3, 4-5, 6-9 into the three SSN fields;
    FICO; DOB parts; names; address lines 1-3 (line 3 as City); state; zip; country; phone parts
    from characters 2-4, 6-8, 10-13 of each stored phone; government id; EFT id; primary flag.

  Source: `app/cbl/COACTUPC.cbl:2787-2865`.
- **BR-COACTUPC-05**: Updated values (`3203-SHOW-UPDATED-VALUES`, states E, N, C, L, F): every field
  shows ACUP-NEW-DETAILS as received. An amount that passed TEST-NUMVAL-C is shown re-edited as
  `+ZZZ,ZZZ,ZZZ.99`; an invalid one is shown as typed. Source: `app/cbl/COACTUPC.cbl:2870-2951`.
- **BR-COACTUPC-06**: Blank values (`3201-SHOW-INITIAL-VALUES`, state "not fetched" or account
  number 0): every detail field is sent as LOW-VALUES, so the map shows its INITIAL value (the SSN
  fields show `999`, `99`, `9999`; others are empty). Nothing is sent at all when PGM-ENTER is set
  (3200 skips everything), with the same visible result. Source:
  `app/cbl/COACTUPC.cbl:2698-2785`.
- **BR-COACTUPC-07**: Receive conversions (`1100-RECEIVE-MAP`):
  - For every input field: `*` or all spaces becomes LOW-VALUES, else the text is copied.
  - The account number goes to CC-ACCT-ID and ACUP-NEW-ACCT-ID-X. In state "not fetched" nothing
    else is taken.
  - Amounts: the X(15) text is kept in working storage. IF TEST-NUMVAL-C of the text = 0, the
    numeric ACUP-NEW-*-N = NUMVAL-C(text) (high-order digits beyond 10 integer digits are
    truncated, Q-COACTUPC-15). Otherwise the numeric field keeps its INITIALIZE value.
  - Date parts, SSN parts, phone parts, FICO, names, addresses, state, zip, country, government
    id, EFT id, primary flag, group id, and customer id are copied into ACUP-NEW-DETAILS. City
    goes to ADDR-LINE-3.

  Source: `app/cbl/COACTUPC.cbl:1039-1425`.
- **BR-COACTUPC-08**: Change detection (`1205-COMPARE-OLD-NEW`). No change means all of the
  following are equal between the new and old values:
  - Exact: account id; the five amounts (numeric representation); open, expiry, reissue dates;
    the six phone parts; SSN; DOB; EFT id; FICO.
  - After UPPER-CASE (no TRIM): account status.
  - After UPPER-CASE and TRIM: group id; customer id; first, middle, last name; address lines
    1-3; state; country; zip; government id; primary flag.

  No change sets the message `No change detected with respect to values fetched.`; otherwise a
  change is flagged. Case-only edits count as "no change" (Q-COACTUPC-05). A blank group id never
  compares equal (Q-COACTUPC-06). Source: `app/cbl/COACTUPC.cbl:1681-1779`.
- **BR-COACTUPC-09**: When edits run. After a fetch, every ENTER re-receives all fields and
  compares. IF there is no change, or the state is N or C, no field is validated and the flags are
  cleared. OTHERWISE the state becomes E and **every** field is validated, not only the changed
  ones (Q-COACTUPC-07). IF no edit fails the state becomes N. Source:
  `app/cbl/COACTUPC.cbl:1452-1676 (1200-EDIT-MAP-INPUTS)`.
- **BR-COACTUPC-10**: PF12 (valid only after a fetch) re-reads the account and customer from
  the files and returns to state S with the stored values, discarding the typed changes. The
  edits of step 2.4 still run first on that turn, but their message is cleared and their flags
  are reset by 3202. Source: `app/cbl/COACTUPC.cbl:2572-2580`, `:2789`.
- **BR-COACTUPC-11**: Save sequence (`9600-WRITE-PROCESSING`, state N with PF5):
  1. READ ACCTDAT UPDATE with key CC-ACCT-ID into ACCOUNT-RECORD. On failure: ERR-COACTUPC-07.
  2. READ CUSTDAT UPDATE with key CDEMO-CUST-ID into CUSTOMER-RECORD. On failure:
     ERR-COACTUPC-08.
  3. `9700-CHECK-CHANGE-IN-REC` (BR-COACTUPC-12). On mismatch: ERR-COACTUPC-11.
  4. Build the account image (BR-COACTUPC-13) and REWRITE ACCTDAT. On failure: ERR-COACTUPC-09.
  5. Build the customer image (BR-COACTUPC-14) and REWRITE CUSTDAT. On failure: ERR-COACTUPC-10.

  The values written are the ACUP-NEW values received on the F5 turn itself; they are not
  validated again (Q-COACTUPC-18). Source: `app/cbl/COACTUPC.cbl:3888-4107`.
- **BR-COACTUPC-12**: Optimistic concurrency check. The re-read records must still equal the
  snapshot. Account: status, the five amounts, the three dates (by part), group id compared in
  lower case. Customer: first, middle, last name, address lines 1-3, state, country, government id
  compared in upper case; zip, phone 1, phone 2, SSN, DOB (by part), EFT id, primary flag, FICO
  compared exactly. Account id and customer id are not compared. Source:
  `app/cbl/COACTUPC.cbl:4109-4195`.
- **BR-COACTUPC-13**: Account image written (ACCT-UPDATE-RECORD, 300 bytes, INITIALIZEd first):
  - ACCT-UPDATE-ID = ACUP-NEW-ACCT-ID; ACTIVE-STATUS = new status.
  - CURR-BAL, CREDIT-LIMIT, CASH-CREDIT-LIMIT, CURR-CYC-CREDIT, CURR-CYC-DEBIT = the new numeric
    amounts.
  - OPEN-DATE, EXPIRAION-DATE, REISSUE-DATE = year `-` month `-` day from the new parts.
  - GROUP-ID = the new group id.

  The layout has no ACCT-ADDR-ZIP field, so GROUP-ID occupies record bytes 103-112, the place of
  ACCT-ADDR-ZIP in CVACT01Y. The real ACCT-GROUP-ID bytes 113-122 and the rest of the record come
  from the 188-byte FILLER, which INITIALIZE does not set (Q-COACTUPC-01). Source:
  `app/cbl/COACTUPC.cbl:418-433`, `:3956-4002`, `app/cpy/CVACT01Y.cpy`.
- **BR-COACTUPC-14**: Customer image written (CUST-UPDATE-RECORD, 300 bytes, INITIALIZEd first;
  same field order as CVCUS01Y):
  - ID = new customer id (from the protected field); names; address lines 1-3; state; country;
    zip.
  - PHONE-NUM-1 and -2 = `(` + area + `)` + prefix + `-` + line number (13 characters plus 2
    trailing positions).
  - SSN = the three SSN parts as a 9-digit number; government id.
  - DOB = year `-` month `-` day; EFT id; primary flag; FICO.
  - The 168-byte FILLER is not set by INITIALIZE.

  Source: `app/cbl/COACTUPC.cbl:434-456`, `:4007-4059`.
- **BR-COACTUPC-15**: Save outcome (by the message 88-level set in 9600):

  | Outcome | New state | Error line | Info line |
  |:--|:--|:--|:--|
  | Account lock failed | L | `Could not lock account record for update` | `Changes unsuccessful. Please try again` |
  | A REWRITE failed | F | `Update of record failed` | `Changes unsuccessful. Please try again` |
  | Data changed by someone else | S | `Record changed by some one else. Please review` | `Update account details presented above.` |
  | Customer lock failed | C | `Could not lock customer record for update` | `Changes committed to database` (Q-COACTUPC-02) |
  | Both rewrites succeeded | C | (blank) | `Changes committed to database` |

  Source: `app/cbl/COACTUPC.cbl:2602-2615`, `:479-528`.
- **BR-COACTUPC-16**: After state C, L, or F the screen shows the new values, all protected
  (C) or with only the account number editable (L, F). The next key other than PF3 ignores the
  input, clears the program state and CDEMO-ACCT-ID, and shows the empty search screen. Source:
  `app/cbl/COACTUPC.cbl:979-989`.
- **BR-COACTUPC-17**: Info message (`3250-SETUP-INFOMSG`, first match):
  - PGM-ENTER, or state "not fetched": `Enter or update id of account to update`.
  - S or E: `Update account details presented above.`
  - N: `Changes validated.Press F5 to save`
  - C: `Changes committed to database`
  - L or F: `Changes unsuccessful. Please try again`
  - No info message set: `Enter or update id of account to update`.

  Source: `app/cbl/COACTUPC.cbl:2955-2982`, `:463-477`.
- **BR-COACTUPC-18**: Field protection (`3300`, `3310`, `3320`): first protect every field with MDT
  on. Then: "not fetched" makes the account number editable; S and E make every detail field
  editable except customer id and country; N and C leave everything protected; any other state
  (L, F) makes the account number editable. The account number is never editable in S or E
  (Q-COACTUPC-16). Source: `app/cbl/COACTUPC.cbl:2986-3006`, `:3441-3495`, `:3500-3561`.
- **BR-COACTUPC-19**: Cursor (first match): info message is `Details of selected account shown
  above` or the error is `No change detected ...` -> Active Y/N; account filter blank or in error
  -> account number; otherwise the first field whose flag is "not OK" or "blank" in this order:
  status, open year, month, day, credit limit, expiry year, month, day, cash limit, reissue year,
  month, day, current balance, cycle credit, cycle debit, SSN parts 1-3, DOB year, month, day,
  FICO, first name, middle name (not OK only), last name, address line 1, state, zip, city,
  country, phone 1 parts A-C, phone 2 parts A-C, EFT id, primary flag; otherwise the account
  number (Q-COACTUPC-17). Source: `app/cbl/COACTUPC.cbl:3009-3167`.
- **BR-COACTUPC-20**: Colors:
  - The account number gets default color when CDEMO-LAST-MAPSET = `COCRDLI` (never true in
    practice).
  - It is red when the filter is in error.
  - It shows `*` in red when the filter is blank on a re-entry turn.
  - IF state "not fetched" or the filter is blank or in error, stop. Otherwise each detail field
    whose flag is "not OK" or "blank" on a re-entry turn turns red, and a "blank" one shows `*`
    (`CSSETATY`, 39 fields).

  Source: `app/cbl/COACTUPC.cbl:3171-3192`, `:3208-3436`, `app/cpy/CSSETATY.cpy:18-27`.
- **BR-COACTUPC-21**: Key legend: INFOMSG is dark when empty, otherwise bright. `F12=Cancel` is
  shown bright when the state is E, N, L, or F. `F5=Save` and `F12=Cancel` are shown bright when
  the info message is `Changes validated.Press F5 to save`. In state S, F12 works but is not shown.
  Source: `app/cbl/COACTUPC.cbl:3566-3583`.
- **BR-COACTUPC-22**: Account number echo (`3200`): on PGM-ENTER nothing is moved. Otherwise, IF
  CC-ACCT-ID is 0 and the filter is valid the field is sent empty; else CC-ACCT-ID as typed.
  Source: `app/cbl/COACTUPC.cbl:2700-2708`.
- **BR-COACTUPC-23**: Header as in COACTVWC: titles, `CAUP`, `COACTUPC`, `MM/DD/YY`, `HH:MM:SS`.
  Source: `app/cbl/COACTUPC.cbl:2668-2692`.
- **BR-COACTUPC-24**: Editable data. The user can change: account status, credit limit, cash credit
  limit, current balance, current cycle credit, current cycle debit, open / expiry / reissue
  dates, group id, SSN, DOB, FICO, names, address lines 1-2, city, state, zip, both phones,
  government id, EFT id, primary flag. Account number, customer id, and country cannot be changed.
  No field is cross-checked against business limits (Q-COACTUPC-15). Source:
  `app/cbl/COACTUPC.cbl:3500-3561`.

### Validation rules
Messages are built as TRIM(field name) + suffix. Only the first failing message is kept
(VR-COACTUPC-34), but every check that runs sets its field flag (SSN parts 2 and 3 are skipped
when part 1 fails, VR-COACTUPC-17). "Blank" means LOW-VALUES (from `*` or an
empty field), spaces, or a zero-length trimmed value. Field names come from
`app/cbl/COACTUPC.cbl:1472-1657`.

**Shared date edit** (`EDIT-DATE-CCYYMMDD` in `app/cpy/CSUTLDPY.cpy:18-331`), used with name
`<D>` = `Open Date`, `Expiry Date`, `Reissue Date`, or `Date of Birth`. All steps run in sequence;
a failing step skips the rest of its own paragraph only.
1. Year (4 chars): blank -> `<D> : Year must be supplied.`; not numeric -> `<D> must be 4 digit
   number.`; century not 19 or 20 -> `<D> : Century is not valid.` (`:25-87`).
2. Month (2 chars): blank -> `<D> : Month must be supplied.`; not 1-12 (tested on the numeric
   redefinition) or not numeric -> `<D>: Month must be a number between 1 and 12.` (`:91-144`).
3. Day (2 chars): blank -> `<D> : Day must be supplied.`; not numeric or not 1-31 ->
   `<D>:day must be a number between 1 and 31.` (`:150-204`).
4. Combinations (run even if a part failed): day 31 in a month other than 1, 3, 5, 7, 8, 10,
   12 -> `<D>:Cannot have 31 days in this month.`; February 30 -> `<D>:Cannot have 30 days in this
   month.`; February 29 when the year is not divisible by 4 (by 400 when the last two digits are
   00) -> `<D>:Not a leap year.Cannot have 29 days in this month.` (`:209-279`).
5. IF any part flag is not valid, stop. Otherwise CALL `CSUTLDTC` with the 8-character date and
   picture `YYYYMMDD` (Q-COACTUPC-14); severity not 0 ->
   `<D> validation error Sev code: <ssss> Message code: <mmmm>` (`:284-321`). After this call the
   date flags are always reset to valid (Q-COACTUPC-13).

- **VR-COACTUPC-01**: Account number required (state "not fetched"). Blank or `*`: input error,
  CDEMO-ACCT-ID and ACUP-NEW-ACCT-ID = 0. The text `Account number not provided` is replaced by
  `No input received`. The field shows `*` in red with the cursor on it. Source:
  `app/cbl/COACTUPC.cbl:1787-1797`, `:1441-1443`.
- **VR-COACTUPC-02**: Account number must be numeric in all 11 positions and not zero. Otherwise
  `Account Number if supplied must be a 11 digit Non-Zero Number`, CDEMO-ACCT-ID = 0, the field is
  echoed in red. There is no PICIN or MUSTFILL on this map, so a short number always fails
  (Q-COACTUPC-24). Source: `app/cbl/COACTUPC.cbl:1801-1817`.
- **VR-COACTUPC-03**: Account must be in the cross-reference: see ERR-COACTUPC-01.
- **VR-COACTUPC-04**: Account and customer must exist: see ERR-COACTUPC-03 and ERR-COACTUPC-05.
- **VR-COACTUPC-05**: At least one value must differ from the snapshot (BR-COACTUPC-08). Otherwise
  `No change detected with respect to values fetched.`, the cursor goes to Active Y/N, and the
  state does not change. Source: `app/cbl/COACTUPC.cbl:1463-1468`, `:1769`.
- **VR-COACTUPC-06**: Edits run only on a changed screen in state S or E (BR-COACTUPC-09).
- **VR-COACTUPC-07**: Account Status (`1220-EDIT-YESNO`): blank, spaces, or `0` ->
  `Account Status must be supplied.`; not `Y` or `N` (uppercase only) ->
  `Account Status must be Y or N.`. Source: `app/cbl/COACTUPC.cbl:1472-1476`, `:1856-1893`.
- **VR-COACTUPC-08**: Open Date: shared date edit with `<D>` = `Open Date` (for example
  `Open Date : Year must be supplied.`, `Open Date:Cannot have 31 days in this month.`). Source:
  `app/cbl/COACTUPC.cbl:1478-1482`.
- **VR-COACTUPC-09**: Credit Limit (`1250-EDIT-SIGNED-9V2`): the 15-character text all LOW-VALUES
  or all spaces -> `Credit Limit must be supplied.`; TEST-NUMVAL-C not 0 ->
  `Credit Limit is not valid` (no trailing dot). Any value NUMVAL-C accepts passes, including
  negative values, `$`, commas, `CR`, `DB` (Q-COACTUPC-15). Source:
  `app/cbl/COACTUPC.cbl:1484-1488`, `:2180-2219`.
- **VR-COACTUPC-10**: Expiry Date: shared date edit with `<D>` = `Expiry Date`. No ordering check
  against the open date. Source: `app/cbl/COACTUPC.cbl:1490-1494`.
- **VR-COACTUPC-11**: Cash Credit Limit: as VR-COACTUPC-09 with name `Cash Credit Limit`. Not
  compared with the credit limit. Source: `app/cbl/COACTUPC.cbl:1496-1501`.
- **VR-COACTUPC-12**: Reissue Date: shared date edit with `<D>` = `Reissue Date`. Source:
  `app/cbl/COACTUPC.cbl:1503-1507`.
- **VR-COACTUPC-13**: Current Balance: as VR-COACTUPC-09 with name `Current Balance`. Source:
  `app/cbl/COACTUPC.cbl:1509-1513`.
- **VR-COACTUPC-14**: Current cycle credit: as VR-COACTUPC-09 with name
  `Current Cycle Credit Limit`. The 26-character name is cut to 25 by WS-EDIT-VARIABLE-NAME
  X(25), so the messages read `Current Cycle Credit Limi must be supplied.` and
  `Current Cycle Credit Limi is not valid` (Q-COACTUPC-12). Source: `app/cbl/COACTUPC.cbl:53`,
  `:1515-1520`.
- **VR-COACTUPC-15**: Current cycle debit: as VR-COACTUPC-09 with name `Current Cycle Debit Limit`
  (`Current Cycle Debit Limit must be supplied.`, `Current Cycle Debit Limit is not valid`). Source:
  `app/cbl/COACTUPC.cbl:1522-1527`.
- **VR-COACTUPC-16**: SSN part 1 (3 chars, `1245-EDIT-NUM-REQD` with name `SSN: First 3 chars`):
  blank -> `SSN: First 3 chars must be supplied.`; not all numeric ->
  `SSN: First 3 chars must be all numeric.`; zero -> `SSN: First 3 chars must not be zero.`;
  then 666 or 900-999 ->
  `SSN: First 3 chars: should not be 000, 666, or between 900 and 999`. Source:
  `app/cbl/COACTUPC.cbl:2439-2464`, `:2109-2175`, `:121-123`.
- **VR-COACTUPC-17**: SSN part 2 (2 chars, name `SSN 4th & 5th chars`):
  `SSN 4th & 5th chars must be supplied.`, `SSN 4th & 5th chars must be all numeric.`,
  `SSN 4th & 5th chars must not be zero.`. No range rule is applied although the comments describe
  one (Q-ENT-24). This edit runs only when SSN part 1 passed its required/numeric/non-zero edit
  (the `IF FLG-EDIT-US-SSN-PART1-ISVALID` at line 2448 is closed only by the period at line 2488);
  when part 1 is blank, non-numeric, or zero, parts 2 and 3 are not edited, not flagged, and not
  highlighted, and the cursor never goes to them (Q-COACTUPC-31). A part 1 of 666 or 900-999 still
  lets parts 2 and 3 be edited. Source: `app/cbl/COACTUPC.cbl:2448-2488 (1265-EDIT-US-SSN)`.
- **VR-COACTUPC-18**: SSN part 3 (4 chars, name `SSN Last 4 chars`):
  `SSN Last 4 chars must be supplied.`, `SSN Last 4 chars must be all numeric.`,
  `SSN Last 4 chars must not be zero.`. No range rule is applied (Q-ENT-24). Same condition as
  VR-COACTUPC-17: runs only when part 1 passed `1245-EDIT-NUM-REQD`. Source:
  `app/cbl/COACTUPC.cbl:2448-2488 (1265-EDIT-US-SSN)`.
- **VR-COACTUPC-19**: Date of Birth: shared date edit with `<D>` = `Date of Birth`; then, IF the
  date flags are valid, the date must be strictly before today (FUNCTION CURRENT-DATE, compared by
  INTEGER-OF-DATE), otherwise `Date of Birth:cannot be in the future ` (trailing space; today is
  rejected). Source: `app/cbl/COACTUPC.cbl:1533-1543`, `app/cpy/CSUTLDPY.cpy:341-369`.
- **VR-COACTUPC-20**: FICO Score (3 chars): `FICO Score must be supplied.`,
  `FICO Score must be all numeric.`, `FICO Score must not be zero.`; then outside 300-850 ->
  `FICO Score: should be between 300 and 850`. Source: `app/cbl/COACTUPC.cbl:1545-1556`,
  `:2514-2530`, `:848-849`.
- **VR-COACTUPC-21**: First Name (25 chars, `1225-EDIT-ALPHA-REQD`): blank ->
  `First Name must be supplied.`; any character other than A-Z, a-z, or space ->
  `First Name can have alphabets only.` (hyphens, apostrophes, digits, and periods are rejected).
  Source: `app/cbl/COACTUPC.cbl:1560-1566`, `:1898-1950`.
- **VR-COACTUPC-22**: Middle Name (25 chars, `1235-EDIT-ALPHA-OPT`): may be blank; otherwise
  letters and spaces only, else `Middle Name can have alphabets only.`. Source:
  `app/cbl/COACTUPC.cbl:1568-1574`, `:2012-2056`.
- **VR-COACTUPC-23**: Last Name: as VR-COACTUPC-21 with `Last Name must be supplied.` and
  `Last Name can have alphabets only.`. Source: `app/cbl/COACTUPC.cbl:1576-1582`.
- **VR-COACTUPC-24**: Address Line 1 (50 chars, `1215-EDIT-MANDATORY`): blank ->
  `Address Line 1 must be supplied.`; any content accepted. Source:
  `app/cbl/COACTUPC.cbl:1584-1590`, `:1824-1851`.
- **VR-COACTUPC-25**: State (2 chars): `State must be supplied.`, `State can have alphabets only.`;
  then, if alphabetic, it must be one of the 56 codes of VALID-US-STATE-CODE (50 states, DC, AS,
  GU, MP, PR, VI; uppercase only), else `State: is not a valid state code`. Source:
  `app/cbl/COACTUPC.cbl:1592-1602`, `:2493-2510`, `app/cpy/CSLKPCDY.cpy` (VALID-US-STATE-CODE).
- **VR-COACTUPC-26**: Zip (5 screen chars): `Zip must be supplied.`, `Zip must be all numeric.`,
  `Zip must not be zero.`. Source: `app/cbl/COACTUPC.cbl:1605-1611`.
- **VR-COACTUPC-27**: City (50 chars): `City must be supplied.`, `City can have alphabets only.`
  (so `St. Louis` is rejected). Source: `app/cbl/COACTUPC.cbl:1615-1621`.
- **VR-COACTUPC-28**: Country (3 chars, protected on screen): `Country must be supplied.`,
  `Country can have alphabets only.`. Source: `app/cbl/COACTUPC.cbl:1623-1630`.
- **VR-COACTUPC-29**: Phone Number 1 (`1260-EDIT-US-PHONE-NUM`, name `Phone Number 1`). IF all three
  parts are blank the phone is valid (optional; Q-COACTUPC-10). Otherwise each part is checked
  independently:
  - Area code: blank -> `Phone Number 1: Area code must be supplied.`; not numeric ->
    `Phone Number 1: Area code must be A 3 digit number.`; zero ->
    `Phone Number 1: Area code cannot be zero`; not in VALID-GENERAL-PURP-CODE (410 codes; N11
    and other "easily recognizable" codes are excluded) ->
    `Phone Number 1: Not valid North America general purpose area code`.
  - Prefix: `Phone Number 1: Prefix code must be supplied.`,
    `Phone Number 1: Prefix code must be A 3 digit number.`,
    `Phone Number 1: Prefix code cannot be zero`.
  - Line number: `Phone Number 1: Line number code must be supplied.`,
    `Phone Number 1: Line number code must be A 4 digit number.`,
    `Phone Number 1: Line number code cannot be zero`.

  Source: `app/cbl/COACTUPC.cbl:1632-1638`, `:2225-2426`, `app/cpy/CSLKPCDY.cpy:521-930`.
- **VR-COACTUPC-30**: Phone Number 2: as VR-COACTUPC-29 with name `Phone Number 2`. Source:
  `app/cbl/COACTUPC.cbl:1640-1646`.
- **VR-COACTUPC-31**: EFT Account Id (10 chars): `EFT Account Id must be supplied.`,
  `EFT Account Id must be all numeric.`, `EFT Account Id must not be zero.`. Source:
  `app/cbl/COACTUPC.cbl:1648-1655`.
- **VR-COACTUPC-32**: Primary Card Holder: `Primary Card Holder must be supplied.` (blank, spaces, or
  `0`), `Primary Card Holder must be Y or N.`. Source: `app/cbl/COACTUPC.cbl:1657-1662`.
- **VR-COACTUPC-33**: State/zip cross edit, only when both the state and zip edits passed: state +
  first 2 zip digits must be in VALID-US-STATE-ZIP-CD2-COMBO (240 pairs), else
  `Invalid zip code for state`; both fields are flagged and the cursor goes to State. The table is
  wrong for several states (Q-COACTUPC-08). Source: `app/cbl/COACTUPC.cbl:1664-1669`,
  `:2536-2557`, `app/cpy/CSLKPCDY.cpy:1071-1314`.
- **VR-COACTUPC-34**: Message precedence: every edit writes its message only if none is set yet
  (WS-RETURN-MSG-OFF), so the message shown is from the first failing check in the order
  VR-COACTUPC-07 to VR-COACTUPC-33. Every failing check that runs still flags its field red (SSN
  parts 2 and 3 are skipped when part 1 fails, VR-COACTUPC-17). Source: guards at
  every STRING in `app/cbl/COACTUPC.cbl:1824-2557` and `app/cpy/CSUTLDPY.cpy`.
- **VR-COACTUPC-35**: Fields with no edit: Address Line 2, Account Group, Government Issued Id,
  Customer id (protected), and the account number after a fetch (protected). Source:
  `app/cbl/COACTUPC.cbl:1613-1614`.

### Navigation and keys
- **NAV-COACTUPC-01**: AID handling. PF13-PF24 act as PF1-PF12. Valid keys: ENTER; PF3; PF5 only
  in state N; PF12 only when details have been fetched (any state other than "not fetched").
  Every other AID, including PF5 and PF12 outside those states, is treated as ENTER. Source:
  `app/cbl/COACTUPC.cbl:898-916`, `app/cpy/CSSTRPFY.cpy:17-79`.
- **NAV-COACTUPC-02**: PF3 in any state: TO-TRANID = FROM-TRANID or `CM00`; TO-PROGRAM =
  FROM-PROGRAM or `COMEN01C`; FROM-TRANID = `CAUP`; FROM-PROGRAM = `COACTUPC`; user type `U`;
  PGM-ENTER; LAST-MAPSET `COACTUP`; LAST-MAP `CACTUPA`; EXEC CICS SYNCPOINT; XCTL with
  CARDDEMO-COMMAREA. Unsaved changes are discarded without warning. Source:
  `app/cbl/COACTUPC.cbl:927-959`.
- **NAV-COACTUPC-03**: First display: empty search screen, account number editable with the cursor
  on it, info `Enter or update id of account to update`. Source: `app/cbl/COACTUPC.cbl:964-973`.
- **NAV-COACTUPC-04**: Reset after save: in states C, L, F any key except PF3 shows the empty
  search screen (BR-COACTUPC-16). Source: `app/cbl/COACTUPC.cbl:979-989`.
- **NAV-COACTUPC-05**: Normal turn: process inputs, decide, send. Source:
  `app/cbl/COACTUPC.cbl:996-1003`.
- **NAV-COACTUPC-06**: Every non-exit turn ends with RETURN TRANSID(`CAUP`) and the 2000-byte
  COMMAREA. Source: `app/cbl/COACTUPC.cbl:1007-1020`.
- **NAV-COACTUPC-07**: In state N, ENTER does nothing but redisplay; the user must press F5 to save
  or F12 to cancel. Source: `app/cbl/COACTUPC.cbl:2620-2621`.
- **NAV-COACTUPC-08**: Turn summary.

  | State | Key | Result |
  |:--|:--|:--|
  | not fetched | ENTER (or any non-PF3) | Edit account number; fetch; S, or stay with error |
  | S | ENTER, no change | Stay S, `No change detected with respect to values fetched.` |
  | S or E | ENTER, change | All edits; E with first error, or N |
  | S, E, N | PF12 | Re-fetch; S |
  | N | ENTER | Stay N |
  | N | PF5 | Save; L, F, S, or C |
  | C, L, F | any non-PF3 | Empty search screen |
  | any | PF3 | Exit |

### Error handling
- **ERR-COACTUPC-01**: Cross-reference READ NOTFND: input error, filter not OK (account number red),
  message `Account:` + key + ` not found in` + ` Cross ref file.  Resp:` + RESP + ` Reas:` + RESP2
  (RESP and RESP2 as 9 digits plus a space, cut at 75 characters), state stays "not fetched".
  Source: `app/cbl/COACTUPC.cbl:3650-3698`.
- **ERR-COACTUPC-02**: Cross-reference READ other RESP: `File Error: READ     on CXACAIX   returned
  RESP nnnnnnnnn ,RESP2 nnnnnnnnn ` (WS-FILE-ERROR-MESSAGE layout as in COACTVWC), filter not OK.
  Source: `app/cbl/COACTUPC.cbl:3686-3696`, `:388-408`.
- **ERR-COACTUPC-03**: Account READ NOTFND: input error, filter not OK, message `Account:` + key +
  ` not found in` + ` Acct Master file.Resp:` + RESP + ` Reas:` + RESP2. Processing continues to
  the customer read and the snapshot (Q-COACTUPC-20). Source: `app/cbl/COACTUPC.cbl:3701-3748`.
- **ERR-COACTUPC-04**: Account READ other RESP: `File Error: READ     on ACCTDAT   returned RESP
  ...`. Source: `app/cbl/COACTUPC.cbl:3736-3746`.
- **ERR-COACTUPC-05**: Customer READ NOTFND: input error, message `CustId:` + key + ` not found` +
  ` in customer master.Resp: ` + RESP + ` REAS:` + RESP2. Customer not found, so the state stays
  "not fetched". Source: `app/cbl/COACTUPC.cbl:3752-3797`.
- **ERR-COACTUPC-06**: Customer READ other RESP: `File Error: READ     on CUSTDAT   returned RESP
  ...`; state stays "not fetched". Source: `app/cbl/COACTUPC.cbl:3785-3795`.
- **ERR-COACTUPC-07**: READ UPDATE ACCTDAT not NORMAL (record locked by another task, not found,
  file error): input error, message `Could not lock account record for update`, nothing written,
  state L. Source: `app/cbl/COACTUPC.cbl:3894-3915`.
- **ERR-COACTUPC-08**: READ UPDATE CUSTDAT not NORMAL: input error, message
  `Could not lock customer record for update`, nothing written, but state C (Q-COACTUPC-02). The
  account record stays locked until the task ends. Source: `app/cbl/COACTUPC.cbl:3919-3942`.
- **ERR-COACTUPC-09**: REWRITE ACCTDAT not NORMAL: message `Update of record failed`, state F,
  customer not written. Source: `app/cbl/COACTUPC.cbl:4065-4081`.
- **ERR-COACTUPC-10**: REWRITE CUSTDAT not NORMAL: message `Update of record failed`, SYNCPOINT
  ROLLBACK, state F. The account rewrite is not undone because ACCTDAT is defined RECOVERY(NONE)
  (Q-COACTUPC-03). Source: `app/cbl/COACTUPC.cbl:4085-4103`, `app/csd/CARDDEMO.CSD:1-9`.
- **ERR-COACTUPC-11**: Concurrency mismatch (BR-COACTUPC-12): message
  `Record changed by some one else. Please review`, nothing written, state S, and the screen shows
  the old snapshot, not the current file values (Q-COACTUPC-04). Source:
  `app/cbl/COACTUPC.cbl:4142-4145`, `:4188-4191`, `:2611-2612`.
- **ERR-COACTUPC-12**: Unexpected state in `2000-DECIDE-ACTION`: ABEND-CULPRIT `COACTUPC`,
  ABEND-CODE `0001`, ABEND-MSG `UNEXPECTED DATA SCENARIO`, then `ABEND-ROUTINE` (ABEND `9999`).
  Source: `app/cbl/COACTUPC.cbl:2633-2640`.
- **ERR-COACTUPC-13**: Any abend goes to `ABEND-ROUTINE`: default message
  `UNEXPECTED ABEND OCCURRED.`, culprit `COACTUPC`, SEND ABEND-DATA NOHANDLE, HANDLE ABEND
  CANCEL, ABEND ABCODE(`9999`). Source: `app/cbl/COACTUPC.cbl:4203-4226`.
- **ERR-COACTUPC-14**: RECEIVE MAP and SEND MAP responses are captured and not checked. Source:
  `app/cbl/COACTUPC.cbl:1040-1045`, `:3594-3601`.

## Data effects
Only the F5 turn in state N writes. In one CICS task, in this order:
1. READ UPDATE ACCTDAT (key = entered account) - lock.
2. READ UPDATE CUSTDAT (key = CDEMO-CUST-ID from the fetch) - lock.
3. Compare both with the snapshot; stop without writing on any difference.
4. REWRITE ACCTDAT with the image of BR-COACTUPC-13: all account fields from the screen;
   ACCT-ADDR-ZIP bytes replaced by the group id; ACCT-GROUP-ID and FILLER bytes unset.
5. REWRITE CUSTDAT with the image of BR-COACTUPC-14: all customer fields from the screen; phones
   rebuilt as `(AAA)BBB-CCCC`; dates as `YYYY-MM-DD`.
6. On a customer REWRITE failure, SYNCPOINT ROLLBACK (ineffective for the non-recoverable files).

There is no explicit SYNCPOINT after success; the updates and locks end with the task at RETURN.
PF3 issues SYNCPOINT before XCTL. Reads in the fetch path are without UPDATE. No other file is
touched; the card record (CARDDAT) is not changed even if the account status changes.

## Quirks
- **Q-COACTUPC-01**: The account update record has no ACCT-ADDR-ZIP field. The group id is written
  into the zip bytes (103-112), and the real ACCT-GROUP-ID (113-122) gets whatever the
  uninitialized FILLER holds. Every save therefore destroys ACCT-ADDR-ZIP (`A000000000` in all
  sample accounts) and loses the group id that CBACT04C uses to find the interest rate
  (DISCGRP, with fallback to `DEFAULT`). Same defect as Q-ENT-01 in `entities.md`; Q-ENT-02 records
  that all sample accounts already have this shape. Recommendation: fix (map fields by name). Source:
  `app/cbl/COACTUPC.cbl:418-433`, `:4002`, `app/cpy/CVACT01Y.cpy`, `app/cbl/CBACT04C.cbl:210`,
  `:419-438`.
- **Q-COACTUPC-02**: A failure to lock the customer record falls into WHEN OTHER and sets state C.
  The screen shows `Changes committed to database` together with
  `Could not lock customer record for update`, although nothing was written. Recommendation: fix
  (treat it as state L). Source: `app/cbl/COACTUPC.cbl:2606-2615`, `:3937-3941`.
- **Q-COACTUPC-03**: The two rewrites are not atomic. ACCTDAT and CUSTDAT are RECOVERY(NONE), so the
  SYNCPOINT ROLLBACK after a failed customer rewrite does not undo the account rewrite. Result:
  the account is updated and the customer is not. Recommendation: fix (one database transaction).
  Source: `app/cbl/COACTUPC.cbl:4099-4101`, `app/csd/CARDDEMO.CSD:1-9`, `:50-60`.
- **Q-COACTUPC-04**: After `Record changed by some one else. Please review` the snapshot is not
  refreshed and the screen shows the old snapshot (3202), not the current data. Any new change
  followed by F5 fails the same way until the user presses PF12. The check also leaves through
  GO TO 9600-WRITE-PROCESSING-EXIT from inside the performed 9700 paragraph (out of the PERFORM
  range), and the locks are kept until task end. Recommendation: fix (reload current values and
  show them). Source: `app/cbl/COACTUPC.cbl:4142-4145`, `:4188-4191`, `:2611-2612`.
- **Q-COACTUPC-05**: Change detection uppercases and trims text fields, so a case-only correction
  (for example `kessler` to `Kessler`) is reported as `No change detected with respect to values
  fetched.` and cannot be saved. Recommendation: fix (exact comparison). Source:
  `app/cbl/COACTUPC.cbl:1697-1766`.
- **Q-COACTUPC-06**: A blank group id is received as LOW-VALUES, while the snapshot holds spaces.
  TRIM removes spaces but not LOW-VALUES, so the comparison never matches. For every sample
  account (all have a blank group) ENTER without any edit is treated as a change and runs the full
  validation, and if all edits pass it offers F5 and would rewrite the unchanged data (with
  Q-COACTUPC-01 damage). Recommendation: fix (normalize blanks before comparing). Source:
  `app/cbl/COACTUPC.cbl:1213-1218`, `:1697-1700`.
- **Q-COACTUPC-07**: Every field is validated on every change, not only the changed ones. None of
  the 50 sample customers passes all edits (for example account 1 has FICO 274; account 3 has a
  non-general area code; accounts 10, 16, 24, 43 have state/zip combinations rejected by the
  table; account 18 has state `AP`). Such accounts cannot be updated at all until the stored bad
  value is also corrected. Recommendation: decide.
- **Q-COACTUPC-08**: The state/zip-prefix table is wrong for states whose ZIP codes start with 0
  (for example CT is listed as 60-69 instead of 06; MA 10-27 and 55; ME 39-49; NH 30-38;
  NJ 70-89; VT 50-59; RI 28-29; PR 60-79 and 90-98; VI 80-85). Real addresses such as CT 06103 are
  rejected with `Invalid zip code for state`. Recommendation: fix (use a correct reference table).
  Source: `app/cpy/CSLKPCDY.cpy:1071-1314`.
- **Q-COACTUPC-09**: The state/zip table contains AA, AE, AP, FM, MH, and PW pairs, but those codes
  are not in the state list, so they fail earlier with `State: is not a valid state code`.
  Recommendation: decide (military and freely associated state addresses). Source:
  `app/cpy/CSLKPCDY.cpy:1012-1070`, `:1071-1314`.
- **Q-COACTUPC-10**: The "all three parts blank" test of the phone edit checks the area code twice
  instead of the line number (`NUMA EQUAL SPACES OR NUMC EQUAL LOW-VALUES`). Because blank input
  is always converted to LOW-VALUES, the outcome is the same as the intended test. A blank optional
  phone is then written as `(` + LOW-VALUES + `)` + LOW-VALUES + `-` + LOW-VALUES.
  Same finding as Q-ENT-25 in `entities.md`. Recommendation: fix (store an empty phone). Source:
  `app/cbl/COACTUPC.cbl:2234-2244`,
  `:4027-4041`.
- **Q-COACTUPC-11**: Phone parts are compared and written by position; the stored text is assumed
  to be `(AAA)BBB-CCCC`. Data in any other format is split wrongly on display. Recommendation:
  preserve the format, but validate stored data on migration. Source:
  `app/cbl/COACTUPC.cbl:2846-2857`.
- **Q-COACTUPC-12**: Field names: `Current Cycle Credit Limit` is cut to `Current Cycle Credit Limi`
  by the 25-byte name area, and both cycle fields are called "Limit" though they are cycle totals.
  Recommendation: fix wording. Source: `app/cbl/COACTUPC.cbl:53`, `:1515`, `:1522`.
- **Q-COACTUPC-13**: When CSUTLDTC rejects a date, the shared date edit jumps to
  EDIT-DATE-LE-EXIT, which is followed by `SET WS-EDIT-DATE-IS-VALID TO TRUE` in the same
  paragraph. The message is shown, but the date fields are not red and the cursor does not go to
  them. For Date of Birth, the future-date check then also runs. Recommendation: fix. Source:
  `app/cpy/CSUTLDPY.cpy:315`, `:323-328`.
- **Q-COACTUPC-14**: CSUTLDTC receives the 8-character WS-EDIT-DATE-CCYYMMDD but always passes 10
  bytes to CEEDAYS, so the date string is the 8 digits followed by the first 2 bytes of
  WS-EDIT-DATE-BINARY (X'0000' during the date edits of a turn, because WS-MISC-STORAGE is
  INITIALIZEd and the binary is only set later by the DOB future check). The picture is
  `YYYYMMDD` followed by the first 2 bytes of WS-DATE-VALIDATION-RESULT (spaces after INITIALIZE).
  Whether CEEDAYS accepts this depends on how it treats the trailing bytes (see Q-CSUTLDTC-03).
  Recommendation: in the target, validate the calendar date directly. Source:
  `app/cpy/CSUTLDWY.cpy:4-37`, `:58-63`, `app/cpy/CSUTLDPY.cpy:290-296`,
  `app/cbl/CSUTLDTC.cbl:84-85`, `:105-112`.
- **Q-COACTUPC-15**: Amounts accept anything NUMVAL-C accepts: negative credit and cash limits, a
  cash limit above the credit limit, any balance and cycle totals, and values with more than 10
  integer digits (silently truncated). Changing the balance online bypasses the transaction
  ledger. Recommendation: decide (most likely restrict balance and cycle fields to read-only and
  add range checks). Source: `app/cbl/COACTUPC.cbl:1073-1112`, `:2180-2219`.
- **Q-COACTUPC-16**: Once details are shown, the account number is protected. The only ways to
  switch accounts are PF3 and re-entry, or finishing a save. PF12 only re-reads the same account.
  Recommendation: decide. Source: `app/cbl/COACTUPC.cbl:3441-3442`, `:3500-3561`.
- **Q-COACTUPC-17**: The info message `Details of selected account shown above` is replaced by
  `3250-SETUP-INFOMSG` before the cursor logic runs, so the first cursor rule only fires for the
  "no change" message. With no errors the cursor goes to the account number, which is protected in
  state S. Recommendation: fix (cursor on the first editable field). Source:
  `app/cbl/COACTUPC.cbl:1452`, `:2955-2977`, `:3009-3012`, `:3165-3166`.
- **Q-COACTUPC-18**: F5 saves the values received on the F5 turn without validating them again.
  In state N all fields are protected, so normally they are the confirmed values, but a modified
  data stream could change them. Recommendation: fix (save the server-side validated values).
  Source: `app/cbl/COACTUPC.cbl:1463-1467`, `:3956-4059`.
- **Q-COACTUPC-19**: After a completed or failed save, the next ENTER or PF12 discards the screen
  and shows an empty search screen. After a lock failure the account number is editable, but
  whatever the user types is ignored on the next turn. Recommendation: decide. Source:
  `app/cbl/COACTUPC.cbl:979-989`, `:3004-3005`.
- **Q-COACTUPC-20**: `9000-READ-ACCT` tests `DID-NOT-FIND-ACCT-IN-ACCTDAT` and
  `DID-NOT-FIND-CUST-IN-CUSTDAT`, which are never set. An account missing from ACCTDAT still leads
  to the customer read, the snapshot, and (if the customer exists) state S with account fields
  from an unread record. A missing customer still runs the snapshot, putting unread customer data
  into CDEMO-CUST-FNAME and related fields. Recommendation: fix. Source:
  `app/cbl/COACTUPC.cbl:3627-3643`, `:3801-3811`.
- **Q-COACTUPC-21**: Entry from the menu INITIALIZEs CARDDEMO-COMMAREA (user id and type are lost)
  and PF3 forces user type `U`, as in COACTVWC. Recommendation: fix. Source:
  `app/cbl/COACTUPC.cbl:880-886`, `:947`.
- **Q-COACTUPC-22**: Dead or unused code: paragraphs `1230-EDIT-ALPHANUM-REQD` and
  `1240-EDIT-ALPHANUM-OPT`; the WHEN for state C in `2000-DECIDE-ACTION`; the state C test in
  `1200-EDIT-MAP-INPUTS`; the state S branch of 2000 with a change; messages
  `Last name not provided`, `Name can only contain alphabets and spaces`, the account-status,
  credit-limit, and card-expiry texts, the `Did not find ...` texts,
  `Did not find cards for this search condition`, `Error reading Card Data File`,
  `Looks Good.... so far`, `PF03 pressed.Exiting`. Recommendation: drop. Source:
  `app/cbl/COACTUPC.cbl:479-528`, `:1955-2105`, `:2585-2591`, `:2625-2632`.
- **Q-COACTUPC-23**: The comments around the EFT and primary-flag attribute snippets are swapped
  (the snippet order is primary flag, then EFT). No behavioral effect. Recommendation: ignore.
  Source: `app/cbl/COACTUPC.cbl:3420-3436`.
- **Q-COACTUPC-24**: The update map has no PICIN or MUSTFILL on the account number, so `24` or
  `0000000024` fails with `Account Number if supplied must be a 11 digit Non-Zero Number`; the user
  must type all 11 digits. Recommendation: fix (left-pad). Source: `app/bms/COACTUP.bms:84-87`,
  `app/cbl/COACTUPC.cbl:1802-1803`.
- **Q-COACTUPC-25**: Account status and primary flag reject lowercase `y` / `n`, although change
  detection compares them in upper case. Recommendation: decide (uppercase the input). Source:
  `app/cbl/COACTUPC.cbl:78`, `:1685-1688`.
- **Q-COACTUPC-26**: Name, city, and country edits allow only A-Z, a-z, and space, which rejects
  common real values (O'Brien, Smith-Jones, St. Louis). Address line 2, group id, and government
  id are not edited at all. Recommendation: decide.
- **Q-COACTUPC-27**: Date of birth equal to today is rejected with the "future" message; there is
  no minimum or maximum age and the century check allows 1900-2099 only. Recommendation: decide.
  Source: `app/cpy/CSUTLDPY.cpy:350-368`, `:70-84`.
- **Q-COACTUPC-28**: The full SSN and government id are displayed and editable by any signed-on
  user, with no authorization check or audit trail of the change. Recommendation: decide (see
  `docs/data-handling.md`).
- **Q-COACTUPC-29**: The info message `Details of selected account shown above` is never displayed
  (always overwritten by `3250-SETUP-INFOMSG`). Recommendation: drop. Source:
  `app/cbl/COACTUPC.cbl:1452`, `:2955-2977`.
- **Q-COACTUPC-30**: Amounts are displayed with 9 integer positions (`+ZZZ,ZZZ,ZZZ.99`) although they
  hold 10, so a value of 1,000,000,000.00 or more loses its top digit on display; re-entering the
  displayed text would then save a different value. Recommendation: fix. Source:
  `app/cbl/COACTUPC.cbl:371`.
- **Q-COACTUPC-31**: In `1265-EDIT-US-SSN` the `IF FLG-EDIT-US-SSN-PART1-ISVALID` has no END-IF of
  its own, so the edits of SSN parts 2 and 3 run only when part 1 passed its required/numeric/non-zero
  edit. With part 1 blank, non-numeric, or zero, an invalid part 2 or 3 is not flagged, highlighted,
  or given the cursor (the message is part 1's either way, VR-COACTUPC-34, and no save is possible).
  The comments and the paragraph layout show that all three parts were meant to be edited.
  Recommendation: fix (edit all three parts). Source: `app/cbl/COACTUPC.cbl:2447-2488 (1265-EDIT-US-SSN)`.

## Test scenarios
SSNs and government ids are masked. Sample keys are from `app/data/ASCII/acctdata.txt` and
`custdata.txt` (account N belongs to customer N).

- **TS-COACTUPC-01** (NAV-COACTUPC-03, BR-COACTUPC-06): Given main menu option 2, then the screen
  shows an editable empty account number with the cursor on it, SSN placeholders `999`-`99`-`9999`,
  all other fields empty and protected, info `Enter or update id of account to update`, legend
  `ENTER=Process F3=Exit` only.
- **TS-COACTUPC-02** (VR-COACTUPC-01): Given the search screen, when ENTER is pressed with the
  account number empty, then the error is `No input received` and the account field shows `*` in
  red.
- **TS-COACTUPC-03** (VR-COACTUPC-02, Q-COACTUPC-24): When `24` is entered, then the error is
  `Account Number if supplied must be a 11 digit Non-Zero Number` and the field shows `24` in red.
  The same happens for `00000000000`.
- **TS-COACTUPC-04** (ERR-COACTUPC-01): When `00000000099` is entered, then the error is
  `Account:00000000099 not found in Cross ref file.  Resp:000000013  Reas:0000` and the state stays
  "not fetched".
- **TS-COACTUPC-05** (BR-COACTUPC-02, -04, -17, -18): When `00000000024` is entered, then the screen
  shows state S as in the mock-up:
  - Active `Y`; Credit Limit `+      5,174.00`; Cash `+      4,129.00`; Balance `+        400.00`;
    cycle values `+          0.00`.
  - Opened `2015`-`08`-`08`; Expiry and Reissue `2025`-`02`-`11`; group blank.
  - Customer `000000024`; SSN `***`-`**`-`****`; DOB `1996`-`01`-`24`; FICO `711`.
  - `Stefanie` / `Verla` / `Dickinson`; `6367 Stracke River` / `Apt. 444` / `East Otho`; `KS`;
    `15414`; `USA`.
  - Phones `617` `348` `9142` and `330` `116` `5634`; EFT `0005459662`; Primary `Y`.
  - Info `Update account details presented above.`; detail fields editable except customer id
    and country; account number protected.

  CDEMO-ACCT-ID = 24, CDEMO-CUST-ID = 24, CDEMO-CUST-LNAME = `Dickinson`.
- **TS-COACTUPC-06** (Q-COACTUPC-06, Q-COACTUPC-07, VR-COACTUPC-33): Given account `00000000024` in
  state S, when ENTER is pressed without changing anything, then the blank group id counts as a
  change, all fields are validated, and the error is `Invalid zip code for state` (KS with zip
  15414), state E, State and Zip red, cursor on State, F12 legend shown.
- **TS-COACTUPC-07** (VR-COACTUPC-05): Given an account whose ACCT-GROUP-ID is non-blank (for example
  set to `GROUPA` in test data) and whose customer passes all edits, in state S, when ENTER is
  pressed without changes, then the error is `No change detected with respect to values fetched.`,
  the state stays S, and the cursor is on Active Y/N.
- **TS-COACTUPC-08** (BR-COACTUPC-09, VR-COACTUPC-34): Given account `00000000024` in state S, when
  Zip is changed to `66044` and ENTER is pressed, then there is no error, the info is
  `Changes validated.Press F5 to save`, `F5=Save` and `F12=Cancel` are bright, every field is
  protected, and the state is N.
- **TS-COACTUPC-09** (BR-COACTUPC-11, -13, -14, -15, Q-COACTUPC-01): Continuing TS-COACTUPC-08,
  when F5 is pressed, then:
  - CUSTDAT record `000000024` has zip `66044`, phones `(617)348-9142` and `(330)116-5634`, DOB
    `1996-01-24`, and the other fields unchanged.
  - ACCTDAT record `00000000024` has the same status, amounts, and dates, but bytes 103-112
    (ACCT-ADDR-ZIP, previously `A000000000`) now hold the group id received (LOW-VALUES for a blank
    group), and bytes 113-122 hold unspecified FILLER content.
  - The info is `Changes committed to database`, the error line is blank, all fields are
    protected, and the state is C.
- **TS-COACTUPC-10** (NAV-COACTUPC-04, BR-COACTUPC-16): Continuing TS-COACTUPC-09, when ENTER is
  pressed, then the empty search screen is shown, CDEMO-ACCT-ID = 0, and the state is "not fetched".
- **TS-COACTUPC-11** (BR-COACTUPC-10, NAV-COACTUPC-08): Given state N from TS-COACTUPC-08, when PF12
  is pressed, then the record is re-read, the original zip `15414` is shown, the state is S, and
  nothing is written.
- **TS-COACTUPC-12** (NAV-COACTUPC-01): Given state S, when PF5 is pressed, then it is handled as
  ENTER (TS-COACTUPC-06 result); nothing is saved.
- **TS-COACTUPC-13** (NAV-COACTUPC-07): Given state N, when ENTER is pressed, then the screen is
  redisplayed unchanged in state N.
- **TS-COACTUPC-14** (ERR-COACTUPC-11, Q-COACTUPC-04): Given state N from TS-COACTUPC-08, and
  another task changes customer 24's first name before F5, when F5 is pressed, then the error is
  `Record changed by some one else. Please review`, the info is `Update account details presented
  above.`, the screen shows the old snapshot (not the other task's change), and nothing is written.
- **TS-COACTUPC-15** (ERR-COACTUPC-07): Given state N, and ACCTDAT record 24 is held by another
  task's READ UPDATE (or ACCTDAT is closed), when F5 is pressed, then the error is
  `Could not lock account record for update`, the info is `Changes unsuccessful. Please try
  again`, the account number is editable, and nothing is written.
- **TS-COACTUPC-16** (ERR-COACTUPC-08, Q-COACTUPC-02): Given state N, and CUSTDAT record 24 cannot
  be read for update, when F5 is pressed, then the error is
  `Could not lock customer record for update` and the info is `Changes committed to database`;
  nothing is written.
- **TS-COACTUPC-17** (ERR-COACTUPC-10, Q-COACTUPC-03): Given state N, and the CUSTDAT REWRITE fails,
  when F5 is pressed, then the error is `Update of record failed`, the info is
  `Changes unsuccessful. Please try again`, and ACCTDAT record 24 remains rewritten.
- **TS-COACTUPC-18** (VR-COACTUPC-20, Q-COACTUPC-07): Given account `00000000001` in state S, when
  Credit Limit is changed to `3000.00` and ENTER is pressed, then the error is
  `FICO Score: should be between 300 and 850` (stored FICO 274), FICO is red with the cursor on it,
  and the state is E.
- **TS-COACTUPC-19** (VR-COACTUPC-33, Q-COACTUPC-08): Given account `00000000010` (state CT, zip
  44803) in state S, when any field is changed, then the error is `Invalid zip code for state`.
  Changing the zip to `06103` gives the same error; changing it to `60601` passes.
- **TS-COACTUPC-20** (VR-COACTUPC-25, Q-COACTUPC-09): Given account `00000000018` (state AP), when
  any field is changed, then the error is `State: is not a valid state code`.

The following validation scenarios start from account `00000000024` in state S with Zip set to
`66044` (so no other edit fails) plus the change shown; each expects state E, the quoted error,
and the named field red with the cursor on it.

- **TS-COACTUPC-21** (VR-COACTUPC-07, Q-COACTUPC-25): Active Y/N `y` -> `Account Status must be Y or N.`;
  Active Y/N empty -> `Account Status must be supplied.` (field shows `*`).
- **TS-COACTUPC-22** (VR-COACTUPC-08): Open month `13` -> `Open Date: Month must be a number between 1 and 12.`;
  open day `31` with month `04` -> `Open Date:Cannot have 31 days in this month.`; open date
  `2023`-`02`-`29` -> `Open Date:Not a leap year.Cannot have 29 days in this month.`; open year
  `1899` -> `Open Date : Century is not valid.`; open year empty ->
  `Open Date : Year must be supplied.`. Open date `2000`-`02`-`29` passes.
- **TS-COACTUPC-23** (VR-COACTUPC-09, VR-COACTUPC-14): Credit Limit `ABC` -> `Credit Limit is not valid`;
  Credit Limit empty -> `Credit Limit must be supplied.`; Current Cycle Credit `X` ->
  `Current Cycle Credit Limi is not valid`. Credit Limit `-500` passes (Q-COACTUPC-15).
- **TS-COACTUPC-24** (VR-COACTUPC-16): SSN part 1 `666` ->
  `SSN: First 3 chars: should not be 000, 666, or between 900 and 999`; `000` ->
  `SSN: First 3 chars must not be zero.`; part 2 `0A` -> `SSN 4th & 5th chars must be all numeric.`.
- **TS-COACTUPC-25** (VR-COACTUPC-19): DOB = today's date -> `Date of Birth:cannot be in the future `.
- **TS-COACTUPC-26** (VR-COACTUPC-20): FICO `900` -> `FICO Score: should be between 300 and 850`;
  FICO `000` -> `FICO Score must not be zero.`.
- **TS-COACTUPC-27** (VR-COACTUPC-21, VR-COACTUPC-22, VR-COACTUPC-27): First name `Anne-Marie` ->
  `First Name can have alphabets only.`; middle name empty passes; city `St. Louis` ->
  `City can have alphabets only.`.
- **TS-COACTUPC-28** (VR-COACTUPC-29): Phone 1 area `911` ->
  `Phone Number 1: Not valid North America general purpose area code`; Phone 1 area empty with
  prefix and line filled -> `Phone Number 1: Area code must be supplied.`; Phone 2 all three parts
  empty passes and is saved as `(` + LOW-VALUES pattern (Q-COACTUPC-10).
- **TS-COACTUPC-29** (VR-COACTUPC-31, VR-COACTUPC-32): EFT `0000000000` ->
  `EFT Account Id must not be zero.`; Primary `X` -> `Primary Card Holder must be Y or N.`.
- **TS-COACTUPC-30** (VR-COACTUPC-34): Active Y/N `X` and FICO `900` together -> the error is
  `Account Status must be Y or N.` (first in order), and both fields are red; the cursor is on
  Active Y/N.
- **TS-COACTUPC-31** (NAV-COACTUPC-02): Given any state, when PF3 is pressed, then SYNCPOINT, XCTL to
  COMEN01C, CDEMO-FROM-PROGRAM = `COACTUPC`, user type `U`; unsaved changes are lost.
- **TS-COACTUPC-32** (VR-COACTUPC-17, VR-COACTUPC-18, Q-COACTUPC-31): Given account `00000000024` in
  state S, when SSN part 1 is changed to `ABC` and part 3 to `12X4` and ENTER is pressed, then (as
  coded) the error is `SSN: First 3 chars must be all numeric.`, SSN part 1 is red with the cursor,
  and parts 2 and 3 are not red. With part 1 `123` instead, the error is
  `SSN Last 4 chars must be all numeric.` and part 3 is red.

## Open questions
- Should online users be able to change the current balance and cycle totals at all
  (Q-COACTUPC-15)? In the batch design these come only from posted transactions.
- Should unchanged fields be validated (Q-COACTUPC-07)? With the current rules no sample account
  can be saved without first correcting other stored data.
- Does CEEDAYS accept the 10-byte date and picture strings built from 8-byte fields
  (Q-COACTUPC-14)? If it does not, every date edit fails with a `validation error Sev code`
  message and no update can ever be saved. This needs a test on z/OS.
- Is the blank-group comparison mismatch (Q-COACTUPC-06) observed on the real system? It depends on
  FUNCTION TRIM leaving LOW-VALUES in place, which is the documented behavior.
- What should happen to the card record(s) when the account status changes to `N`? The program
  does not touch CARDDAT.
- Which access rule applies: may any user update any account and its customer's SSN
  (Q-COACTUPC-28)? Is an audit trail required?
- Should ACCT-ADDR-ZIP be kept at all (it holds `A000000000` in every sample account and is not shown
  anywhere)? This affects the fix for Q-COACTUPC-01.
