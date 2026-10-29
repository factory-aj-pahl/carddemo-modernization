# COPAUS0C: List an account's pending authorizations, five per page, with the account and summary header

| Attribute | Value |
|:--|:--|
| Source | `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl` (1032 lines) |
| Module | app-authorization-ims-db2-mq |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CPVS (`app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd:18-22,49-50`, PROFILE DFHCICST) |
| BMS map | mapset COPAU00, map COPAU0A (`app/app-authorization-ims-db2-mq/bms/COPAU00.bms`, symbolic map `app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy`, COPAU0AI / COPAU0AO) |
| Copybooks | COCOM01Y (+ CDEMO-CPVS-INFO extension), COPAU00, COTTL01Y, CSDAT01Y, CSMSG01Y, CSMSG02Y, CVACT01Y, CVACT02Y (unused), CVACT03Y, CVCUS01Y, CIPAUSMY, CIPAUDTY, DFHAID, DFHBMSCA |
| Calls / XCTL targets | XCTL COPAUS1C (select 'S'); XCTL COMEN01C (PF3; COSGN00C if the target is blank). EXEC DLI SCHD / TERM / GU / GNP. |
| Data stores | VSAM CXACAIX (card cross-reference alternate index path by account id): Read. VSAM ACCTDAT: Read by account id. VSAM CUSTDAT: Read by customer id. IMS DBPAUTP0 through PSB PSBPAUTB, PCB 1: PAUTSUM0 GU by ACCNTID; PAUTDTL1 GNP (sequential) and GNP qualified by PAUT9CTS. No updates. |

## Purpose
COPAUS0C is the "View Authorizations" screen (main menu option 11, "Pending Authorization
View"). The user enters an account id; the program shows the customer's name, address and phone,
the account's credit and cash limits, the pending-authorization summary counters and balances
from IMS, and a page of up to five pending authorizations, newest first. The user can page
forward and backward and select one authorization with 'S' to see its details in COPAUS1C.

## Invocation and state
- Entered from the main menu COMEN01C option 11 (`app/cpy/COMEN02Y.cpy`, user type 'U';
  `app/cbl/COMEN01C.cbl:147-168` checks with INQUIRE PROGRAM that COPAUS0C is installed and XCTLs
  to it, otherwise shows "This option ... is not installed..."), by PF3 from COPAUS1C, or by
  typing CPVS (EIBCALEN = 0).
- COMMAREA: COCOM01Y fields used: CDEMO-FROM-TRANID, CDEMO-FROM-PROGRAM, CDEMO-TO-PROGRAM,
  CDEMO-PGM-CONTEXT (0 = enter, 1 = reenter), CDEMO-ACCT-ID 9(11), CDEMO-CUST-ID, CDEMO-CARD-NUM.
  Program extension CDEMO-CPVS-INFO (`app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:117-126`):

| Field | Picture | Use |
|:--|:--|:--|
| CDEMO-CPVS-PAU-SEL-FLG | X(1) | Selection character typed by the user |
| CDEMO-CPVS-PAU-SELECTED | X(8) | Key (PAUT9CTS) of the selected authorization, passed to COPAUS1C |
| CDEMO-CPVS-PAUKEY-PREV-PG | X(8) OCCURS 20 | Key of the first row of each page (index = page number) |
| CDEMO-CPVS-PAUKEY-LAST | X(8) | Key of the last row shown |
| CDEMO-CPVS-PAGE-NUM | S9(4) COMP | Current page number |
| CDEMO-CPVS-NEXT-PAGE-FLG | X(1) | 'Y' when another row exists after the current page |
| CDEMO-CPVS-AUTH-KEYS | X(8) OCCURS 5 | Keys of the five rows shown |

- Every turn ends with `EXEC CICS RETURN TRANSID('CPVS') COMMAREA(CARDDEMO-COMMAREA)`.
- The IMS position is not kept across turns; paging re-establishes it with GU summary and a
  qualified GNP on a saved key.

## Screen
```
Tran: CPVS          AWS Mainframe Modernization (TITLE01)        Date: mm/dd/yy
Prog: COPAUS0C      CardDemo (TITLE02)                           Time: hh:mm:ss
                             View Authorizations

  Search Acct Id: ___________
  Name:  xxxxxxxxxxxxxxxxxxxxxxxxx         Customer Id: 999999999
         xxxxxxxxxxxxxxxxxxxxxxxxx         Acct Status: x
         xxxxxxxxxxxxxxxxxxxxxxxxx
         PH: xxxxxxxxxxxxx                 Approval # : 999 Decline #:  999

     Credit Lim: -zzzzzzz9.99  Cash Lim: -zzzz9.99 Appr Amt: -zzzz9.99
     Credit Bal: -zzzzzzz9.99  Cash Bal: -zzzz9.99 Decl Amt: -zzzz9.99

 Sel   Transaction ID     Date       Time     Type   A/D  STS    Amount
 ---  ---------------- --------  --------    ----   ---  ---  ------------
  _   xxxxxxxxxxxxxxx  mm/dd/yy  hh:mm:ss    xxxx    x    x   -zzzzzzz9.99
  _   (rows 16 to 20, five rows)
           Type 'S' to View Authorization details from the list
<ERRMSG, red, 78>
ENTER=Continue  F3=Back  F7=Backward  F8=Forward
```

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Transaction | TRNNAME | 4 | Out | (1,7) ASKIP FSET blue | 'CPVS' |
| Title 1 | TITLE01 | 40 | Out | (1,21) ASKIP FSET yellow | CCDA-TITLE01 from COTTL01Y |
| Date | CURDATE | 8 | Out | (1,71) ASKIP FSET blue | Current date MM/DD/YY |
| Program | PGMNAME | 8 | Out | (2,7) ASKIP FSET blue | 'COPAUS0C' |
| Title 2 | TITLE02 | 40 | Out | (2,21) ASKIP FSET yellow | CCDA-TITLE02 |
| Time | CURTIME | 8 | Out | (2,71) ASKIP FSET blue | Current time HH:MM:SS |
| Search Acct Id | ACCTID | 11 | In/Out | (5,19) UNPROT FSET green underline; cursor home | Account id, 11 digits |
| Name | CNAME | 25 | Out | (6,10) ASKIP blue | First name, middle initial, last name |
| Customer Id | CUSTID | 9 | Out | (6,58) ASKIP blue | CUST-ID |
| Address 1 | ADDR001 | 25 | Out | (7,10) ASKIP blue | Address line 1 "," line 2 |
| Acct Status | ACCSTAT | 1 | Out | (7,58) ASKIP blue | Never populated |
| Address 2 | ADDR002 | 25 | Out | (8,10) ASKIP blue | Line 3 "," state "," first 5 of ZIP |
| Phone | PHONE1 | 13 | Out | (9,15) ASKIP blue | First 13 characters of CUST-PHONE-NUM-1 |
| Approval # | APPRCNT | 3 | Out | (9,58) ASKIP blue | PA-APPROVED-AUTH-CNT, 3 digits |
| Decline # | DECLCNT | 3 | Out | (9,76) ASKIP blue | PA-DECLINED-AUTH-CNT, 3 digits |
| Credit Lim | CREDLIM | 12 | Out | (11,19) ASKIP FSET blue | ACCT-CREDIT-LIMIT, -zzzzzzz9.99 |
| Cash Lim | CASHLIM | 9 | Out | (11,46) ASKIP FSET blue | ACCT-CASH-CREDIT-LIMIT, -zzzz9.99 |
| Appr Amt | APPRAMT | 10 | Out | (11,69) ASKIP FSET blue | PA-APPROVED-AUTH-AMT, -zzzz9.99 |
| Credit Bal | CREDBAL | 12 | Out | (12,19) ASKIP FSET blue | PA-CREDIT-BALANCE, -zzzzzzz9.99 |
| Cash Bal | CASHBAL | 9 | Out | (12,46) ASKIP FSET blue | PA-CASH-BALANCE, -zzzz9.99 |
| Decl Amt | DECLAMT | 10 | Out | (12,69) ASKIP FSET blue | PA-DECLINED-AUTH-AMT, -zzzz9.99 |
| Select n | SEL0001 to SEL0005 | 1 | In | rows 16-20 col 3, UNPROT FSET green underline; set protected when the row is empty, unprotected when filled | 'S' or 's' selects the row |
| Transaction ID n | TRNID01 to TRNID05 | 16 | Out | col 8 ASKIP FSET blue | PA-TRANSACTION-ID (15) |
| Date n | PDATE01 to PDATE05 | 8 | Out | col 27 ASKIP FSET blue | PA-AUTH-ORIG-DATE as MM/DD/YY |
| Time n | PTIME01 to PTIME05 | 8 | Out | col 38 ASKIP FSET blue | PA-AUTH-ORIG-TIME as HH:MM:SS |
| Type n | PTYPE01 to PTYPE05 | 4 | Out | col 49 ASKIP FSET blue | PA-AUTH-TYPE |
| A/D n | PAPRV01 to PAPRV05 | 1 | Out | col 58 ASKIP FSET blue | 'A' if response code '00', else 'D' |
| STS n | PSTAT01 to PSTAT05 | 1 | Out | col 63 ASKIP FSET blue | PA-MATCH-STATUS (P, D, E, M) |
| Amount n | PAMT001 to PAMT005 | 12 | Out | col 67 ASKIP FSET blue | PA-APPROVED-AMT, -zzzzzzz9.99 |
| Message | ERRMSG | 78 | Out | (23,1) ASKIP BRT FSET red | WS-MESSAGE |

## Processing flow
1. MAIN-PARA (178-257): clear the error flag, EOF flag, next-page flag and message; erase = yes;
   cursor on ACCTID.
   1. IF EIBCALEN = 0: initialize the COMMAREA, TO-PROGRAM = COPAUS0C, set reenter, send an
      empty screen (erase).
   2. OTHERWISE copy the COMMAREA (this restores CDEMO-CPVS-NEXT-PAGE-FLG, overriding the reset
      in step 1).
      - IF not reenter (arrived by XCTL): set reenter; IF CDEMO-ACCT-ID is numeric, show it in
        ACCTID and use it as the search account, OTHERWISE blank; GATHER-DETAILS; send with erase.
      - OTHERWISE RECEIVE the map (RESP not checked) and evaluate the key:
        - ENTER: PROCESS-ENTER-KEY; put the search account (or space) back in ACCTID; send.
        - PF3: TO-PROGRAM = COMEN01C; RETURN-TO-PREV-SCREEN (XCTL).
        - PF7: PROCESS-PF7-KEY; send.
        - PF8: PROCESS-PF8-KEY; send.
        - OTHER: error flag on, message CCDA-MSG-INVALID-KEY, send.
   3. RETURN TRANSID CPVS with the COMMAREA.
2. PROCESS-ENTER-KEY (261-338):
   1. IF ACCTID is spaces or low-values: message "Please enter Acct Id...", error flag on.
   2. OTHERWISE IF ACCTID is not numeric: message "Acct Id must be Numeric ...", error flag on.
   3. OTHERWISE: search account and CDEMO-ACCT-ID = ACCTID. Take the first of SEL0001 to SEL0005
      (in row order) that is not spaces or low-values; remember its character and the key of that
      row (CDEMO-CPVS-AUTH-KEYS(n)); if none, both are spaces. IF a character and a key are
      present: IF the character is 'S' or 's', set TO-PROGRAM = COPAUS1C, FROM-TRANID = CPVS,
      FROM-PROGRAM = COPAUS0C, context = 0 (enter) and XCTL to COPAUS1C; OTHERWISE message
      "Invalid selection. Valid value is S".
   4. GATHER-DETAILS (always, including after errors 1 and 2, in which case the search account is
      low-values and nothing is read).
3. GATHER-DETAILS (342-358): page number = 0. IF the search account is not low-values:
   GATHER-ACCOUNT-DETAILS; INITIALIZE-AUTH-DATA; IF the summary was found, PROCESS-PAGE-FORWARD.
4. GATHER-ACCOUNT-DETAILS (750-808):
   1. GETCARDXREF-BYACCT: READ CXACAIX with the search account. NORMAL: CDEMO-CUST-ID =
      XREF-CUST-ID, CDEMO-CARD-NUM = XREF-CARD-NUM. NOTFND: not-found message, send, continue.
      OTHER: error flag, system-error message, send, continue.
   2. GETACCTDATA-BYACCT: READ ACCTDAT with XREF-ACCT-ID (same outcomes).
   3. GETCUSTDATA-BYCUST: READ CUSTDAT with XREF-CUST-ID (same outcomes).
   4. Fill CUSTID, CNAME, ADDR001, ADDR002, PHONE1, CREDLIM, CASHLIM.
   5. GET-AUTH-SUMMARY: SCHEDULE-PSB, then DL/I "get unique the summary whose ACCNTID equals
      CDEMO-ACCT-ID". IF found: fill APPRCNT, DECLCNT, CREDBAL, CASHBAL, APPRAMT, DECLAMT.
      OTHERWISE move ZERO to all six fields.
5. PROCESS-PAGE-FORWARD (415-454), only IF the error flag is off:
   1. Row index = 1; CDEMO-CPVS-PAUKEY-LAST = low-values.
   2. FOR EACH row while index <= 5 and not EOF and no error: IF the key is PF7 and this is row 1,
      REPOSITION-AUTHORIZATIONS (DL/I "get next detail under the current summary whose PAUT9CTS
      equals the saved key"); OTHERWISE GET-AUTHORIZATIONS (DL/I "get next detail under the current
      summary"). IF a detail was returned: POPULATE-AUTH-LIST for this row; index + 1; LAST = this
      key; IF index is now 2 (first row just filled): page number + 1 and PREV-PG(page number) =
      this key.
   3. IF not EOF and no error: read one more detail; next-page flag = 'Y' if one was returned,
      otherwise 'N'.
6. PROCESS-PF7-KEY (362-385): IF page number > 1: page number - 1; saved key =
   PREV-PG(page number); GET-AUTH-SUMMARY; send without erase; next-page = 'Y';
   INITIALIZE-AUTH-DATA; PROCESS-PAGE-FORWARD. OTHERWISE message "You are already at the top of
   the page..." and send without erase.
7. PROCESS-PF8-KEY (388-412): IF LAST is spaces or low-values, saved key = low-values;
   OTHERWISE saved key = LAST, GET-AUTH-SUMMARY, REPOSITION-AUTHORIZATIONS (this re-reads the
   last row shown, so the next GNP returns the following one). Send without erase. IF next-page =
   'Y': INITIALIZE-AUTH-DATA and PROCESS-PAGE-FORWARD. OTHERWISE message "You are already at the
   bottom of the page...".
8. POPULATE-AUTH-LIST (522-605): for row n, save the key in CDEMO-CPVS-AUTH-KEYS(n) and fill
   TRNIDnn, PDATEnn, PTIMEnn, PTYPEnn, PAPRVnn, PSTATnn, PAMT00n; set SEL000n unprotected.
9. INITIALIZE-AUTH-DATA (608-662): for rows 1 to 5, set SEL000n protected and blank all row fields.
10. SEND-PAULST-SCREEN (681-709): IF the PSB is scheduled, mark it not scheduled and SYNCPOINT;
    POPULATE-HEADER-INFO (titles, CPVS, COPAUS0C, date MM/DD/YY, time HH:MM:SS); ERRMSG = message;
    SEND MAP with ERASE and CURSOR, or without ERASE when erase = no.
11. RETURN-TO-PREV-SCREEN (665-677): IF TO-PROGRAM is blank use COSGN00C; FROM-TRANID = CPVS,
    FROM-PROGRAM = COPAUS0C, context = 0; XCTL.

## Rules
### Business rules
- **BR-COPAUS0C-01**: A valid account id typed on the screen becomes the search account and is
  stored in CDEMO-ACCT-ID for later screens. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:282-284 (PROCESS-ENTER-KEY)`.
- **BR-COPAUS0C-02**: Arriving from another program, the screen opens directly on CDEMO-ACCT-ID
  when it is numeric (any value, including zeros). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:202-219 (MAIN-PARA)`.
- **BR-COPAUS0C-03**: Every ENTER (and every arrival by XCTL) rebuilds the screen from page 1:
  page number reset to 0, account details re-read, list cleared, first page read if a summary
  exists. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:342-358 (GATHER-DETAILS)`.
- **BR-COPAUS0C-04**: The account is resolved to a card cross-reference through the account
  alternate index CXACAIX (first xref record for the account); its customer id and card number are
  stored in CDEMO-CUST-ID and CDEMO-CARD-NUM. Account and customer are then read by the xref's
  account and customer ids. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:812-831 (GETCARDXREF-BYACCT)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:865-877 (GETACCTDATA-BYACCT)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:915-928 (GETCUSTDATA-BYCUST)`.
- **BR-COPAUS0C-05**: Header formatting: CNAME = first name (up to the first space) + ' ' + first
  character of middle name + ' ' + last name (up to the first space); ADDR001 = address line 1 (up
  to the first double space) + ',' + line 2 (up to double space); ADDR002 = line 3 (up to double
  space) + ',' + state + ',' + first 5 characters of ZIP; PHONE1 = phone 1; CREDLIM = account credit
  limit (-zzzzzzz9.99); CASHLIM = cash credit limit (-zzzz9.99). Each target is truncated to its
  field length. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:757-783 (GATHER-ACCOUNT-DETAILS)`.
- **BR-COPAUS0C-06**: Summary header: IF the IMS summary exists, APPRCNT and DECLCNT = counts as 3
  digits, CREDBAL = credit balance (-zzzzzzz9.99), CASHBAL, APPRAMT, DECLAMT = cash balance,
  approved amount, declined amount (-zzzz9.99). OTHERWISE figurative ZERO is moved to all six
  fields, which fills each field with '0' characters. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:785-807 (GATHER-ACCOUNT-DETAILS)`.
- **BR-COPAUS0C-07**: The summary is read with CDEMO-ACCT-ID (the typed account), not the xref
  account. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:966-977 (GET-AUTH-SUMMARY)`.
- **BR-COPAUS0C-08**: The list shows the account's detail segments in IMS key order, which is
  newest first (see COPAUA0C BR-COPAUA0C-15), five per page. Without a summary no list is shown.
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:415-443 (PROCESS-PAGE-FORWARD)`.
- **BR-COPAUS0C-09**: Row formatting: Amount = approved amount (-zzzzzzz9.99); Date = orig date
  YYMMDD shown as MM/DD/YY; Time = orig time HHMMSS shown as HH:MM:SS; A/D = 'A' when response code
  is '00', otherwise 'D'; STS = match status; Type = auth type; Transaction ID = transaction id. A
  filled row's select field is unprotected. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:522-605 (POPULATE-AUTH-LIST)`.
- **BR-COPAUS0C-10**: Paging bookkeeping: after a page is read, LAST = key of the last row; when
  the first row of a page is filled, page number + 1 and PREV-PG(page number) = that row's key; one
  extra detail is read to set the next-page flag. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:420-452 (PROCESS-PAGE-FORWARD)`.
- **BR-COPAUS0C-11**: Empty rows are blank and their select fields protected. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:608-662 (INITIALIZE-AUTH-DATA)`.
- **BR-COPAUS0C-12**: Before each send, if the PSB is scheduled the program issues SYNCPOINT,
  which releases the PSB (read-only, nothing to commit). The header shows CPVS, COPAUS0C, the
  titles, and the current date and time. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:681-747 (SEND-PAULST-SCREEN, POPULATE-HEADER-INFO)`.

### Validation rules
- **VR-COPAUS0C-01**: ACCTID must not be spaces or low-values; otherwise message "Please enter
  Acct Id..." and cursor on ACCTID; no data is read. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:264-271 (PROCESS-ENTER-KEY)`.
- **VR-COPAUS0C-02**: ACCTID must be numeric in all 11 positions (no right-justification or zero
  fill is applied, so "7" followed by spaces fails); otherwise message "Acct Id must be Numeric
  ..." and cursor on ACCTID. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:273-280 (PROCESS-ENTER-KEY)`.
- **VR-COPAUS0C-03**: The first non-blank select field must contain 'S' or 's'; otherwise message
  "Invalid selection. Valid value is S", cursor on ACCTID, and the screen is rebuilt for the
  account. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:310-331 (PROCESS-ENTER-KEY)`.
- **VR-COPAUS0C-04**: A selection on a row whose saved key is spaces or low-values is ignored
  without a message. Only the first non-blank select field (rows 1 to 5 in order) is considered.
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:285-312 (PROCESS-ENTER-KEY)`.

### Navigation and keys
- **NAV-COPAUS0C-01**: EIBCALEN = 0 (transaction typed directly): fresh COMMAREA, empty screen with
  the cursor on ACCTID. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:190-198 (MAIN-PARA)`.
- **NAV-COPAUS0C-02**: First entry by XCTL (context not reenter): the screen is built for
  CDEMO-ACCT-ID and sent with erase. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:202-219 (MAIN-PARA)`.
- **NAV-COPAUS0C-03**: ENTER -> validate and rebuild (or select). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:225-234 (MAIN-PARA)`.
- **NAV-COPAUS0C-04**: 'S' or 's' on a filled row -> XCTL COPAUS1C with CDEMO-CPVS-PAU-SELECTED =
  row key, CDEMO-ACCT-ID = account, FROM-TRANID = 'CPVS', FROM-PROGRAM = 'COPAUS0C', context 0.
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:313-325 (PROCESS-ENTER-KEY)`.
- **NAV-COPAUS0C-05**: PF3 -> XCTL COMEN01C with FROM-TRANID 'CPVS', FROM-PROGRAM 'COPAUS0C',
  context 0 (COSGN00C only if the target were blank, which cannot happen here). Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:235-238 (MAIN-PARA)`,
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:665-677 (RETURN-TO-PREV-SCREEN)`.
- **NAV-COPAUS0C-06**: PF7 -> previous page when page number > 1, otherwise "You are already at
  the top of the page...". The screen is sent without erase. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:362-385 (PROCESS-PF7-KEY)`.
- **NAV-COPAUS0C-07**: PF8 -> next page when the next-page flag is 'Y', otherwise "You are already
  at the bottom of the page...". Sent without erase. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:388-412 (PROCESS-PF8-KEY)`.
- **NAV-COPAUS0C-08**: Any other key -> message CCDA-MSG-INVALID-KEY
  "Invalid key pressed. Please see below...         " (from CSMSG01Y), cursor on ACCTID, screen sent with
  erase and without re-reading data. For CLEAR and PA1-PA3 the terminal sends no field data: RECEIVE gets
  MAPFAIL (not tested), the map keeps its initial storage, and the account field and the list are blank
  on the re-sent screen (Q-COPAUS0C-09 describes PF keys). Target: C-SHORTREAD (ledger-online.md).
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:245-249 (MAIN-PARA)`.
- **NAV-COPAUS0C-09**: Every turn that does not XCTL ends with RETURN TRANSID 'CPVS' and the
  COMMAREA. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:254-257 (MAIN-PARA)`.

### Error handling
In every case below the paragraph sets the message, puts the cursor on ACCTID and performs
SEND-PAULST-SCREEN at once, then processing continues (see Q-COPAUS0C-07). Resp and Reas are
shown as 9-digit unsigned numbers (WS-RESP-CD-DIS / WS-REAS-CD-DIS PIC 9(9)).
- **ERR-COPAUS0C-01**: CXACAIX NOTFND -> "Account:" + search account + " not found in XREF file.
  Resp:" + RESP + " Reas:" + RESP2. The error flag is not set. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:832-845 (GETCARDXREF-BYACCT)`.
- **ERR-COPAUS0C-02**: CXACAIX other RESP -> error flag on, "Account:" + account + " System error
  while reading XREF file. Resp:" + RESP + " Reas:" + RESP2. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:846-860 (GETCARDXREF-BYACCT)`.
- **ERR-COPAUS0C-03**: ACCTDAT NOTFND -> "Account:" + xref account + " not found in ACCT file.
  Resp:" + RESP + " Reas:" + RESP2 (no error flag). Other RESP -> error flag on, "Account:" +
  account + " System error while reading ACCT file. Resp:" + RESP + " Reas:" + RESP2. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:879-911 (GETACCTDATA-BYACCT)`.
- **ERR-COPAUS0C-04**: CUSTDAT NOTFND -> "Customer:" + customer id + " not found in CUST file.
  Resp:" + RESP + " Reas:" + RESP2 (no error flag). Other RESP -> error flag on, "Customer:" +
  id + " System error while reading CUST file. Resp:" + RESP + " Reas:" + RESP2. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:930-962 (GETCUSTDATA-BYCUST)`.
- **ERR-COPAUS0C-05**: Summary GU status other than blank, FW, GE -> error flag on, " System error
  while reading AUTH Summary: Code:" + DL/I status. GE means "no summary" (zeros shown, no list).
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:979-996 (GET-AUTH-SUMMARY)`.
- **ERR-COPAUS0C-06**: Detail GNP status other than blank, FW, GE, GB -> error flag on, " System
  error while reading AUTH Details: Code:" + status. GE or GB means end of the list. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:458-486 (GET-AUTHORIZATIONS)`.
- **ERR-COPAUS0C-07**: Qualified repositioning GNP status other than blank, FW, GE, GB -> error
  flag on, " System error while repos. AUTH Details: Code:" + status. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:488-519 (REPOSITION-AUTHORIZATIONS)`.
- **ERR-COPAUS0C-08**: PSB schedule fails (after one TERM and retry on TC) -> error flag on,
  " System error while scheduling PSB: Code:" + status. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:1001-1031 (SCHEDULE-PSB)`.
- **ERR-COPAUS0C-09**: The RESP of RECEIVE MAP is stored but never tested (MAPFAIL is ignored).
  Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:712-722 (RECEIVE-PAULST-SCREEN)`.

## Data effects
Read-only. No VSAM or IMS record is changed. COMMAREA effects: CDEMO-ACCT-ID (typed account),
CDEMO-CUST-ID and CDEMO-CARD-NUM (from the xref record), and the CDEMO-CPVS-INFO paging fields.
SYNCPOINT is issued before each send only to release the PSB.

## Quirks
- **Q-COPAUS0C-01**: PF7 decrements the page number, but PROCESS-PAGE-FORWARD then increments it
  again on the first row and overwrites PREV-PG(page) with the first row's key. Effects: once PF7
  has been used, the page number never drops to 1, so "You are already at the top of the page..."
  no longer appears; from page 3 or later, repeated PF7 keeps showing page N-1 and never reaches
  page 1. Recommendation: fix. Source:
  `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:365-379,436-441`.
- **Q-COPAUS0C-02**: PREV-PG has 20 entries. Paging forward past page 20 stores a key at
  subscript 21 or more, overwriting the following COMMAREA fields (LAST, page number, flags).
  Account 13 in the sample has 58 authorizations (12 pages), so this needs more than 100 rows.
  Recommendation: fix. Source: `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:120,440`.
- **Q-COPAUS0C-03**: Arriving from the menu, CDEMO-ACCT-ID is numeric even when no account was
  chosen (zeros after the sign-on INITIALIZE), so the program searches for account 00000000000 and
  shows a not-found message instead of an empty prompt. Recommendation: decide.
- **Q-COPAUS0C-04**: A not-found cross-reference does not stop processing: the account read uses
  XREF-ACCT-ID left over from an earlier read (or the initial working storage), then the customer
  read, each sending the screen again. The user sees the last message sent. Recommendation: fix.
- **Q-COPAUS0C-05**: ACCSTAT (Acct Status) is on the map but never filled, although
  ACCT-ACTIVE-STATUS is read. Recommendation: fix or decide.
- **Q-COPAUS0C-06**: CASHBAL, APPRAMT and DECLAMT use edit picture -zzzz9.99 (9 characters), so
  amounts of 100000.00 or more lose high-order digits; APPRAMT and DECLAMT fields are 10 long.
  APPRCNT/DECLCNT show only 3 digits (counts over 999 are truncated). When no summary exists, the
  six fields show strings of '0' characters (for example "000000000000" in CREDBAL) rather than
  formatted zero amounts. Recommendation: fix.
- **Q-COPAUS0C-07**: Error paragraphs send the screen immediately and then return to normal flow,
  so one turn can send the map several times. Because SEND-PAULST-SCREEN also SYNCPOINTs (releasing
  the PSB), a DL/I call after such an error in the same turn runs without a scheduled PSB.
  Recommendation: fix.
- **Q-COPAUS0C-08**: The Amount column shows the approved amount, so declined authorizations show
  0.00 instead of the requested amount. Recommendation: decide.
- **Q-COPAUS0C-09**: On an invalid key, the screen is re-sent with erase from the received map
  only. Fields defined with FSET (limits, balances, amounts, list rows) come back from the terminal
  and are redisplayed; fields without FSET (name, customer id, address, phone, counts) are blank
  after the erase. Recommendation: fix.
- **Q-COPAUS0C-10**: CNAME takes the first and last names only up to their first space ("Mary Ann"
  shows as "Mary"); a blank middle name leaves three spaces between names. ADDR001 is truncated at
  25 characters. Recommendation: preserve (display only) or decide.
- **Q-COPAUS0C-11**: CDEMO-CPVS-AUTH-KEYS is not cleared when a page has fewer than five rows; the
  stale keys belong to protected rows, so they cannot be selected through the screen.
  Recommendation: preserve.
- **Q-COPAUS0C-12**: The account record copybook CVACT02Y and the names CARDDAT and CCXREF are
  declared but not used. Recommendation: preserve.

## Test scenarios
Sample data: account 7 (card 4859452612877065, customer 7 "Cooper" / "Dennis" / "Mayert",
address "6490 Zakary Locks", "Apt. 765", "Madieport", state AL, ZIP 34206-2974, phone
"(698)282-4096"; credit limit 2065.00, cash limit 264.00) with an IMS summary of 50 approved
authorizations totalling 74.79, credit balance 74.79 and 50 details. Account 2 has no IMS summary.
- **TS-COPAUS0C-01** (BR-01, BR-04 to BR-06, BR-08, BR-09): Given account 7, when the user types
  00000000007 and presses ENTER, then the screen shows Name "Cooper D Mayert", Customer Id
  "000000007", address line 1 "6490 Zakary Locks,Apt. 76", line 2 "Madieport,AL,34206", PH
  "(698)282-4096", Credit Lim "     2065.00", Cash Lim "   264.00", Approval # "050", Decline #
  "000", Credit Bal "       74.79", Cash Bal "     0.00", Appr Amt "    74.79", Decl Amt
  "     0.00", and the five newest authorizations with A/D 'A' and STS 'P'; CDEMO-CUST-ID = 7 and
  CDEMO-CARD-NUM = 4859452612877065.
- **TS-COPAUS0C-02** (VR-01): Given an empty ACCTID, when ENTER, then "Please enter Acct Id...".
- **TS-COPAUS0C-03** (VR-02): Given ACCTID "7", when ENTER, then "Acct Id must be Numeric ...".
- **TS-COPAUS0C-04** (VR-03): Given account 7 is displayed, when the user types 'X' in row 2 and
  presses ENTER, then "Invalid selection. Valid value is S" and the first page is shown again.
- **TS-COPAUS0C-05** (NAV-04): Given account 7 is displayed, when the user types 'S' in row 1 and
  presses ENTER, then control passes to COPAUS1C with CDEMO-CPVS-PAU-SELECTED = the newest detail's
  key and CDEMO-FROM-PROGRAM = 'COPAUS0C'.
- **TS-COPAUS0C-06** (NAV-07, BR-10): Given account 7 page 1, when PF8 is pressed, then rows 6 to 10
  are shown and the page number is 2; after nine PF8 presses (page 10, rows 46 to 50), another PF8
  shows "You are already at the bottom of the page...".
- **TS-COPAUS0C-07** (NAV-06): Given account 7 page 1 just displayed by ENTER, when PF7 is pressed,
  then "You are already at the top of the page...".
- **TS-COPAUS0C-08** (Q-COPAUS0C-01): Given account 7, when the user presses PF8 twice (rows 11 to
  15) and then PF7 twice, then both PF7 presses show rows 6 to 10.
- **TS-COPAUS0C-09** (BR-06, Q-COPAUS0C-06): Given account 2 (xref card 0923877193247330, no
  summary), when 00000000002 is entered, then the customer 2 header and limits 6130.00 / 5448.00 are
  shown, the six summary fields show '0' characters, and the list is empty with protected select
  fields.
- **TS-COPAUS0C-10** (ERR-01): Given account 99999999999 is not in CXACAIX, when it is entered, then
  a message beginning "Account:99999999999 not found in XREF file. Resp:000000013" is sent (later
  ACCT/CUST messages may replace it, see Q-COPAUS0C-04).
- **TS-COPAUS0C-11** (NAV-05): Given any state, when PF3 is pressed, then XCTL to COMEN01C.
- **TS-COPAUS0C-12** (NAV-08): Given account 7 displayed, when PF5 is pressed, then "Invalid key
  pressed. Please see below...         " is shown.
- **TS-COPAUS0C-13** (NAV-02): Given CDEMO-ACCT-ID = 7 in the COMMAREA and context 0, when COMEN01C
  XCTLs here, then the screen opens showing account 7 without the user typing it.

## Open questions
- Should ACCSTAT show ACCT-ACTIVE-STATUS, and should the Amount column show the requested amount
  for declines?
- Is opening on account 00000000000 from the menu acceptable, or should zeros be treated as "no
  account"?
- The menu option is restricted to user type 'U' in COMEN02Y; is that intentional for an
  operations screen?

## Target mapping proposal (non-normative)
Same tables as proposed in `modernization/spec/programs/COPAUA0C.md` (pending_auth_summary,
pending_auth_detail).
- GU PAUTSUM0 WHERE ACCNTID: `SELECT * FROM pending_auth_summary WHERE account_id = :acct`.
- Page forward (GNP x 5 plus look-ahead): keyset pagination
  `SELECT ... FROM pending_auth_detail WHERE account_id = :acct AND auth_key > :last_key
  ORDER BY auth_key FETCH FIRST 6 ROWS ONLY` (the sixth row only sets "has next").
- Page backward: `... AND auth_key < :first_key ORDER BY auth_key DESC FETCH FIRST 5 ROWS ONLY`,
  then reverse; this removes the PREV-PG array and Q-COPAUS0C-01/02.
- REST shape: `GET /accounts/{accountId}/pending-authorizations?after={key}&limit=5` returning the
  header (customer, limits, summary) and the rows. The xref, account and customer reads become joins
  to the base account, card cross-reference and customer tables.
