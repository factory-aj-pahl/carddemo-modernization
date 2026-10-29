# COCRDSLC: Display one credit card's details, looked up by account number and card number

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COCRDSLC.cbl` (887 lines) |
| Module | base |
| Kind | Online (CICS), pseudo-conversational |
| Transaction ID / Job | CCDL |
| BMS map | mapset `COCRDSL` / map `CCRDSLA` (`app/bms/COCRDSL.bms`, symbolic map `app/cpy-bms/COCRDSL.CPY`) |
| Copybooks | `CVCRD01Y`, `COCOM01Y`, `COTTL01Y`, `COCRDSL` (symbolic map), `CSDAT01Y`, `CSMSG01Y` (unused), `CSMSG02Y` (ABEND-DATA), `CSUSR01Y` (unused), `CVACT02Y` (CARD-RECORD), `CVCUS01Y` (customer layout, unused), `CSSTRPFY` (procedure code), IBM `DFHBMSCA`, `DFHAID`. Commented out: `CVACT01Y`, `CVACT03Y`. |
| Calls / XCTL targets | XCTL to `CDEMO-FROM-PROGRAM` (normally `COCRDLIC`) or `COMEN01C` when that is blank (PF3) |
| Data stores | `CARDDAT` (ENT-CARD): Read by primary key CARD-NUM. `CARDAIX` (account path) read only in dead paragraph `9150-GETCARD-BYACCT`. |

## Purpose
Shows the embossed name, active status, and expiry month/year of one card. The user reaches it
from the card list (COCRDLIC, select code `S`), with the card already chosen, or from main menu
option 4 "Credit Card View", where the user types an account number and a card number. The card
is looked up by card number only; the account number must be a valid non-zero 11-digit number
but is not checked against the card (Q-COCRDSLC-01). The program is read-only.

## Invocation and state

**Entry paths**

| Entry | How detected | Effect |
|:--|:--|:--|
| From COCRDLIC (select `S`) | `CDEMO-PGM-ENTER` and `CDEMO-FROM-PROGRAM` = `COCRDLIC` | Keys taken from CDEMO-ACCT-ID / CDEMO-CARD-NUM; card read and shown at once; key fields protected |
| From main menu option 4 (`COMEN01C`, `app/cpy/COMEN02Y.cpy:42-46`) | `CDEMO-FROM-PROGRAM` = `COMEN01C` and not PGM-REENTER | CARDDEMO-COMMAREA is re-initialized (Q-COCRDSLC-03); empty search screen |
| Transaction CCDL typed with no COMMAREA | EIBCALEN = 0 | Same as menu entry |
| Own pseudo-conversational turn | `CDEMO-PGM-REENTER` (set just before every SEND MAP) | Map received, keys edited, card read |

**CARDDEMO-COMMAREA usage**

| Field | Read | Written |
|:--|:--|:--|
| CDEMO-FROM-PROGRAM / CDEMO-FROM-TRANID | Yes: entry path and PF3 target | `COCRDSLC` / `CCDL`, only on PF3 exit. They are not updated on the program's own turns, so they keep the caller's identity for the whole conversation. |
| CDEMO-TO-PROGRAM / CDEMO-TO-TRANID | No | Set on PF3 from FROM-PROGRAM / FROM-TRANID (defaults `COMEN01C` / `CM00`) |
| CDEMO-PGM-CONTEXT | Yes | REENTER (1) before every SEND MAP; ENTER (0) on PF3 exit |
| CDEMO-ACCT-ID / CDEMO-CARD-NUM | Yes (from list) | Set from the edited keys (0 when blank or invalid) |
| CDEMO-LAST-MAPSET | Yes: `COCRDLI` (with FROM-PROGRAM `COCRDLIC`) protects the keys | `COCRDSL` (7-byte truncation of `'COCRDSL '`) on PF3 |
| CDEMO-LAST-MAP | No | `CCRDSLA` on PF3 |
| CDEMO-USER-TYPE | No | Forced to `U` on PF3 |

**Program-specific COMMAREA extension**: `CA-CALL-CONTEXT` (CA-FROM-PROGRAM X(8), CA-FROM-TRANID
X(4)), 12 bytes after CARDDEMO-COMMAREA. It is copied in and out but never used. Source:
`app/cbl/COCRDSLC.cbl:200-203`. The RETURN COMMAREA is 2000 bytes. No other state is carried;
the card is re-read on every ENTER.

## Screen
The mock-up shows the card on `app/data/ASCII/carddata.txt` line 1 (masked).

```
         1         2         3         4         5         6         7         8
12345678901234567890123456789012345678901234567890123456789012345678901234567890
 Tran: CCDL                AWS Mainframe Modernization           Date: mm/dd/yy
 Prog: COCRDSLC                    CardDemo                      Time: hh:mm:ss

                              View Credit Card Detail


                       Account Number    :   00000000050
                       Card Number       :   0500********5740


    Name on card      :  Aniya Von

    Card Active Y/N   :  Y

    Expiry Date       :  03 / 2023




                            Displaying requested details

<row 23: ERRMSG, red, bright>
 ENTER=Search Cards  F3=Exit
```

| Field | BMS name | Len | In/Out | Attributes | Meaning |
|:--|:--|:--|:--|:--|:--|
| Transaction id (1,7) | TRNNAME | 4 | Out | ASKIP, FSET, blue | `CCDL` |
| Title lines (1,21) and (2,21) | TITLE01, TITLE02 | 40 | Out | ASKIP, yellow | From `COTTL01Y` |
| Date (1,71), Time (2,71) | CURDATE, CURTIME | 8 | Out | ASKIP, blue | MM/DD/YY, HH:MM:SS |
| Program (2,7) | PGMNAME | 8 | Out | ASKIP, blue | `COCRDSLC` |
| Account number (7,45) | ACCTSID | 11 | In/Out | BMS: UNPROT, FSET, IC, underline. Program: protected with MDT on (`DFHBMPRF`) and default color when entered from the list; otherwise unprotected with MDT on (`DFHBMFSE`). Red when in error; shows `*` when blank on a re-entry. | Search key 1 |
| Card number (8,45) | CARDSID | 16 | In/Out | Same rules as ACCTSID | Search key 2 |
| Name on card (11,25) | CRDNAME | 50 | Out | Default (ASKIP), underline | CARD-EMBOSSED-NAME as stored |
| Card active (13,25) | CRDSTCD | 1 | Out | ASKIP, underline | CARD-ACTIVE-STATUS |
| Expiry month (15,25) | EXPMON | 2 | Out | ASKIP, underline | Characters 6-7 of CARD-EXPIRAION-DATE (`YYYY-MM-DD`) |
| Separator (15,28) | (unnamed) | 1 | Out | default | `/` |
| Expiry year (15,30) | EXPYEAR | 4 | Out | ASKIP, underline | Characters 1-4 of CARD-EXPIRAION-DATE |
| Info message (20,25) | INFOMSG | 40 | Out | PROT, neutral; program sets dark when empty, neutral otherwise | WS-INFO-MSG |
| Error message (23,1) | ERRMSG | 80 | Out | ASKIP, BRT, FSET, red | WS-RETURN-MSG (75 bytes) |
| Key legend (24,1) | FKEYS | 75 | Out | ASKIP, yellow | `ENTER=Search Cards  F3=Exit` |

## Processing flow
1. `0000-MAIN`: HANDLE ABEND LABEL(`ABEND-ROUTINE`). Clear the work area, `WS-MISC-STORAGE` (all
   edit flags become space, which is the "blank" value), and the output COMMAREA. Clear the
   return message.
2. IF EIBCALEN = 0, or FROM-PROGRAM = `COMEN01C` and not PGM-REENTER: INITIALIZE
   CARDDEMO-COMMAREA and the program area (PGM-CONTEXT becomes 0 = ENTER, every other field
   spaces or zeros). OTHERWISE copy CARDDEMO-COMMAREA and the 12-byte program area from
   DFHCOMMAREA.
3. Map the AID. Only ENTER and PF3 are valid; any other AID is treated as ENTER.
4. EVALUATE:
   1. PF3: exit (NAV-COCRDSLC-02).
   2. PGM-ENTER and FROM-PROGRAM = `COCRDLIC`: mark input OK; CC-ACCT-ID = CDEMO-ACCT-ID;
      CC-CARD-NUM = CDEMO-CARD-NUM; `9000-READ-DATA`; `1000-SEND-MAP`; return.
   3. PGM-ENTER (menu, direct, or any other caller): `1000-SEND-MAP` with empty keys; return.
   4. PGM-REENTER: `2000-PROCESS-INPUTS` (RECEIVE MAP, then `2200-EDIT-MAP-INPUTS`). IF an input
      error was found: send the map with errors. OTHERWISE: `9000-READ-DATA`, then send the map.
      Return.
   5. OTHERWISE (PGM-CONTEXT neither 0 nor 1): send plain text `UNEXPECTED DATA SCENARIO` and
      RETURN without TRANSID (ERR-COCRDSLC-03).
5. `COMMON-RETURN`: RETURN TRANSID(`CCDL`) with the 2000-byte COMMAREA.

**Edits (`2200-EDIT-MAP-INPUTS`)**: a key field equal to `*` or spaces is replaced with
LOW-VALUES. Then `2210-EDIT-ACCOUNT` and `2220-EDIT-CARD` run (both always run). Finally, IF both
are blank, the message is overwritten with `No input received`.

**Read (`9100-GETCARD-BYACCTCARD`)**: READ `CARDDAT` RIDFLD = CC-CARD-NUM (16 bytes) INTO
CARD-RECORD. NORMAL sets the info message `   Displaying requested details` (the "found" flag).

**Send (`1000-SEND-MAP`)**:
1. `1100-SCREEN-INIT`: header fields.
2. `1200-SETUP-SCREEN-VARS`:
   - EIBCALEN = 0: prompt only.
   - Otherwise the account shows CC-ACCT-ID unless CDEMO-ACCT-ID = 0 (then blank); the card
     shows CC-CARD-NUM unless CDEMO-CARD-NUM = 0.
   - If the card was found: name, expiry month, expiry year, and status are shown.
   - If no info message is set: `Please enter Account and Card Number`.
   - The error line gets WS-RETURN-MSG.
3. `1300-SETUP-SCREEN-ATTRS`: BR-COCRDSLC-05 and BR-COCRDSLC-06.
4. `1400-SEND-SCREEN`: set PGM-REENTER; SEND MAP CURSOR ERASE FREEKB.

## Rules
### Business rules
- **BR-COCRDSLC-01**: The card is located by CARD-NUM only. The account number entered (or passed
  from the list) is validated for format but never compared with CARD-ACCT-ID or the
  cross-reference. Source: `app/cbl/COCRDSLC.cbl:736-773 (9100-GETCARD-BYACCTCARD)`.
- **BR-COCRDSLC-02**: Displayed card data:
  - CARD-EMBOSSED-NAME, as stored (case preserved).
  - CARD-ACTIVE-STATUS.
  - Expiry month = characters 6-7 of CARD-EXPIRAION-DATE.
  - Expiry year = characters 1-4.

  The expiry day, CVV, and record account id are not displayed. The account field shows the
  account that was entered or passed, not the record's. Source:
  `app/cbl/COCRDSLC.cbl:457-486 (1200-SETUP-SCREEN-VARS)`.
- **BR-COCRDSLC-03**: When entered from the list, the keys are trusted: the card is read
  immediately without edits. Source: `app/cbl/COCRDSLC.cbl:339-348 (0000-MAIN)`.
- **BR-COCRDSLC-04**: Info line. It shows `   Displaying requested details` (three leading
  spaces) when the card was read. In every other case it shows
  `Please enter Account and Card Number`. Source: `app/cbl/COCRDSLC.cbl:126-132`, `app/cbl/COCRDSLC.cbl:489-496`.
- **BR-COCRDSLC-05**: Key protection. Both key fields are protected with MDT on, in default color,
  when CDEMO-LAST-MAPSET = `COCRDLI` and CDEMO-FROM-PROGRAM = `COCRDLIC`. Neither is changed
  during the conversation, so the keys stay protected on every turn after a list entry.
  Otherwise both are unprotected with MDT on. Source: `app/cbl/COCRDSLC.cbl:504-512`, `app/cbl/COCRDSLC.cbl:526-531`.
- **BR-COCRDSLC-06**: Cursor and highlighting:
  - The cursor goes on the account field if it is in error or blank, else on the card field if
    it is in error or blank, else on the account field.
  - A key in error is red.
  - A blank key on a re-entry turn shows `*` in red.
  - The info line is dark when empty, neutral otherwise.

  Source: `app/cbl/COCRDSLC.cbl:514-557 (1300-SETUP-SCREEN-ATTRS)`.
- **BR-COCRDSLC-07**: The program never modifies data. Source: whole program.
- **BR-COCRDSLC-08**: User type is forced to `U` and PGM-CONTEXT to ENTER on exit. Source:
  `app/cbl/COCRDSLC.cbl:326-327`.

### Validation rules
- **VR-COCRDSLC-01**: Account number is required. LOW-VALUES, spaces, `*`, or all zeros give
  message `Account number not provided`. The cursor goes on the account field; on a re-entry turn
  the field shows `*` in red. CDEMO-ACCT-ID is set to 0. Source:
  `app/cbl/COCRDSLC.cbl:647-661 (2210-EDIT-ACCOUNT)`.
- **VR-COCRDSLC-02**: Account number must be numeric in all 11 positions. Otherwise message
  `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER`, the field is red and gets the cursor,
  CDEMO-ACCT-ID is set to 0, and the field is redisplayed empty (Q-COCRDSLC-02). Source:
  `app/cbl/COCRDSLC.cbl:665-678 (2210-EDIT-ACCOUNT)`.
- **VR-COCRDSLC-03**: Card number is required. LOW-VALUES, spaces, `*`, or all zeros give
  `Card number not provided` (only if no account message). On a re-entry turn the field shows `*`
  in red. CDEMO-CARD-NUM is set to 0. Source: `app/cbl/COCRDSLC.cbl:685-702 (2220-EDIT-CARD)`.
- **VR-COCRDSLC-04**: Card number must be numeric in all 16 positions. Otherwise
  `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER` (only if no account message). The field
  is red and is redisplayed empty. Source: `app/cbl/COCRDSLC.cbl:706-719 (2220-EDIT-CARD)`.
- **VR-COCRDSLC-05**: If both keys are blank, the message is `No input received`, replacing
  `Account number not provided`. Source: `app/cbl/COCRDSLC.cbl:636-640 (2200-EDIT-MAP-INPUTS)`.
- **VR-COCRDSLC-06**: The card must exist. READ NOTFND gives
  `Did not find cards for this search condition`. Both keys are red, both keys are echoed, no
  detail fields are shown, the info line is `Please enter Account and Card Number`, and the cursor
  is on the account field. Source: `app/cbl/COCRDSLC.cbl:755-761`.
- **VR-COCRDSLC-07**: Message precedence: account messages, then card messages, then (both blank)
  `No input received`. The card read happens only when there are no input errors (re-entry path).
  Source: `app/cbl/COCRDSLC.cbl:357-371`.

### Navigation and keys
- **NAV-COCRDSLC-01**: AID handling. PF13-PF24 act as PF1-PF12. Only ENTER and PF3 are valid;
  every other AID behaves as ENTER (re-edit and re-read), with no message. For CLEAR and PA1-PA3 the
  terminal sends no field data, so RECEIVE gets MAPFAIL (not tested, ERR-COCRDSLC-04) and both key
  fields keep their initial storage. The keys are then blank, even when they were protected after entry
  from the list: the error is `No input received`, no card is read, and CDEMO-ACCT-ID and CDEMO-CARD-NUM
  become 0 (VR-COCRDSLC-05). Target: C-SHORTREAD (ledger-online.md). Source:
  `app/cbl/COCRDSLC.cbl:284-299`.
- **NAV-COCRDSLC-02**: PF3 exits:
  - CDEMO-TO-TRANID = CDEMO-FROM-TRANID, or `CM00` if that is blank or LOW-VALUES.
  - CDEMO-TO-PROGRAM = CDEMO-FROM-PROGRAM, or `COMEN01C` if that is blank.
  - Then FROM-TRANID = `CCDL`, FROM-PROGRAM = `COCRDSLC`, user type `U`, PGM-ENTER,
    LAST-MAPSET `COCRDSL`, LAST-MAP `CCRDSLA`.
  - XCTL to CDEMO-TO-PROGRAM with CARDDEMO-COMMAREA.

  From the list path this returns to COCRDLIC. From the menu or direct path FROM-PROGRAM was
  cleared, so it goes to COMEN01C. CDEMO-ACCT-ID and CDEMO-CARD-NUM are passed back unchanged.
  Source: `app/cbl/COCRDSLC.cbl:305-334`.
- **NAV-COCRDSLC-03**: Every non-exit turn ends with RETURN TRANSID(`CCDL`) and a 2000-byte
  COMMAREA. Source: `app/cbl/COCRDSLC.cbl:394-407 (COMMON-RETURN)`.
- **NAV-COCRDSLC-04**: Entry from the menu or with no COMMAREA shows the empty search screen with
  the cursor on the account field. Source: `app/cbl/COCRDSLC.cbl:349-356`.

### Error handling
- **ERR-COCRDSLC-01**: A card READ response other than NORMAL or NOTFND gives the message
  `File Error: READ     on CARDDAT   returned RESP nnnnnnnnn ,RESP2 nnnnnnnnn ` (same layout as in
  COCRDLIC: 9-digit zero-padded values followed by a space). The account field is red and no
  details are shown. Source: `app/cbl/COCRDSLC.cbl:102-121`, `app/cbl/COCRDSLC.cbl:762-771`.
- **ERR-COCRDSLC-02**: Any abend (HANDLE ABEND) goes to `ABEND-ROUTINE`. If ABEND-MSG is
  LOW-VALUES it becomes `UNEXPECTED ABEND OCCURRED.`, and ABEND-CULPRIT becomes `COCRDSLC`. The
  134-byte ABEND-DATA (code 4, culprit 8, reason 50, message 72) is sent as raw data with
  NOHANDLE. The handler is cancelled and the task abends with code `9999`. Source:
  `app/cbl/COCRDSLC.cbl:857-878`, `app/cpy/CSMSG02Y.cpy:21-29`.
- **ERR-COCRDSLC-03**: If PGM-CONTEXT is neither 0 nor 1, the program sends plain text
  `UNEXPECTED DATA SCENARIO` (SEND TEXT ERASE FREEKB) and issues RETURN with no TRANSID, ending the
  conversation. ABEND-CODE `0001` is set but not shown. Source: `app/cbl/COCRDSLC.cbl:373-380`, `app/cbl/COCRDSLC.cbl:838-848`.
- **ERR-COCRDSLC-04**: RECEIVE MAP and SEND MAP responses are captured and ignored. A MAPFAIL
  (CLEAR, PA1-PA3) leaves the key fields with their initial storage, so they are treated as blank
  (NAV-COCRDSLC-01). Source:
  `app/cbl/COCRDSLC.cbl:596-603`, `app/cbl/COCRDSLC.cbl:569-576`.

## Data effects
None. One READ (no UPDATE) of CARDDAT per list entry or ENTER with valid keys.

## Quirks
- **Q-COCRDSLC-01**: The account number is mandatory but not checked against the card. With any
  valid non-zero 11-digit account (even one that does not exist) and an existing card number, the
  card is shown under the entered account number. Recommendation: fix (require CARD-ACCT-ID = the
  entered account, or look up via the cross-reference). Source: `app/cbl/COCRDSLC.cbl:736-773`.
- **Q-COCRDSLC-02**: Invalid (non-numeric) keys are not echoed. The redisplay uses CDEMO-ACCT-ID /
  CDEMO-CARD-NUM, which are zeroed on error, so the red field is empty and the user must retype.
  Recommendation: decide. Source: `app/cbl/COCRDSLC.cbl:462-472`, `app/cbl/COCRDSLC.cbl:673`, `app/cbl/COCRDSLC.cbl:714`.
- **Q-COCRDSLC-03**: Entry from the menu INITIALIZEs the whole CARDDEMO-COMMAREA. This erases
  CDEMO-USER-ID, CDEMO-USER-TYPE, and the customer and account context, and PF3 then sets user type
  `U`. Recommendation: fix (keep the session identity). Source: `app/cbl/COCRDSLC.cbl:268-272`, `app/cbl/COCRDSLC.cbl:326`.
- **Q-COCRDSLC-04**: Messages are inconsistent. The account and card messages say "IF SUPPLIED"
  although both keys are mandatory. Several 88-level messages are never used:
  `Account number must be a non zero 11 digit number`,
  `Card number if supplied must be a 16 digit number`,
  `Did not find this account in cards database` (only in dead `9150-GETCARD-BYACCT`),
  `Error reading Card Data File`, `Looks Good.... so far`, and `PF03 pressed.Exiting`.
  Recommendation: decide (keep the observable texts). Source: `app/cbl/COCRDSLC.cbl:134-158`.
- **Q-COCRDSLC-05**: `LIT-CCLISTMAP` is `CCRDSLA` (this program's own map), not the list map
  `CCRDLIA`. It is not used. Recommendation: ignore. Source: `app/cbl/COCRDSLC.cbl:177-178`.
- **Q-COCRDSLC-06**: On XCTL from COCRDLIC only 160 bytes are passed, but the program copies 12
  more bytes (CA-CALL-CONTEXT) from beyond EIBCALEN. They are unused, so this is harmless.
  Recommendation: fix (check EIBCALEN). Source: `app/cbl/COCRDSLC.cbl:274-278`.
- **Q-COCRDSLC-07**: ERRMSG is defined with LENGTH=80 at POS (23,1). Its data runs from row 23
  column 2 into row 24 column 1, over the FKEYS attribute byte. Recommendation: fix in UI (one
  80-column message line). Source: `app/bms/COCRDSL.bms:144-152`.
- **Q-COCRDSLC-08**: The key legend says `ENTER=Search Cards` even when the keys are protected
  (list path), where ENTER only re-reads the same card. Recommendation: decide.
- **Q-COCRDSLC-09**: Lines 386-391 (`IF INPUT-ERROR` after the EVALUATE) are unreachable because
  every branch returns or transfers. `SEND-LONG-TEXT` is unused. Recommendation: drop. Source:
  `app/cbl/COCRDSLC.cbl:384-391`, `app/cbl/COCRDSLC.cbl:820-830`.
- **Q-COCRDSLC-10**: The name is shown here as stored (for example `Aniya Von`), but COCRDUPC shows
  it uppercased, and any save there writes it back uppercased. Recommendation: decide.
- **Q-COCRDSLC-11**: Full card number displayed; CVV not displayed. Recommendation: decide (masking
  policy, see `docs/data-handling.md`).

## Test scenarios
Card numbers are masked first 4 + last 4. The full values are in `app/data/ASCII/carddata.txt`
at the cited line.

- **TS-COCRDSLC-01** (BR-COCRDSLC-02, BR-COCRDSLC-03, BR-COCRDSLC-05): Given COCRDLIC page 1, when
  `S` is entered on row 1 (account 00000000050, card 0500********5740, line 1), then the screen
  shows:
  - Account `00000000050` and card 0500********5740, both protected.
  - Name `Aniya Von`, Card Active `Y`, expiry `03` / `2023`.
  - Info `   Displaying requested details`, blank error line, cursor on the account field.
- **TS-COCRDSLC-02** (NAV-COCRDSLC-04): Given main menu option 4, then the screen shows empty,
  editable key fields, empty details, and info `Please enter Account and Card Number`.
- **TS-COCRDSLC-03** (BR-COCRDSLC-01): Given the search screen, when account `00000000027` and card
  0683********1516 (line 2) are entered, then the screen shows name `Ward Jones`, status `Y`, and
  expiry `07` / `2025`.
- **TS-COCRDSLC-04** (VR-COCRDSLC-05, BR-COCRDSLC-06): Given the search screen, when ENTER is
  pressed with both keys empty, then the error is `No input received`, both key fields show `*` in
  red, and the cursor is on the account field.
- **TS-COCRDSLC-05** (VR-COCRDSLC-01): When the account is empty and card 0683********1516 is
  entered, then the error is `Account number not provided` and the account shows `*`.
- **TS-COCRDSLC-06** (VR-COCRDSLC-01): When account `00000000000` and a valid card are entered,
  then the error is `Account number not provided`.
- **TS-COCRDSLC-07** (VR-COCRDSLC-03): When account `00000000027` is entered and the card is
  empty, then the error is `Card number not provided`, the card shows `*`, and the cursor is on
  the card field.
- **TS-COCRDSLC-08** (VR-COCRDSLC-02, Q-COCRDSLC-02): When account `ABC` and card 0683********1516
  are entered, then the error is `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER`, and the
  account field is red and empty.
- **TS-COCRDSLC-09** (VR-COCRDSLC-04): When account `00000000027` and card `0683ABC` are entered,
  then the error is `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER`.
- **TS-COCRDSLC-10** (VR-COCRDSLC-06): When account `00000000027` and card `0000000000000001` (not
  in the file) are entered, then the error is `Did not find cards for this search condition`,
  both keys are red and echoed, no details are shown, and the info line is
  `Please enter Account and Card Number`.
- **TS-COCRDSLC-11** (Q-COCRDSLC-01): When account `00000000001` (the account of card line 49) and
  card 0500********5740 (line 1, account 00000000050) are entered, then the details of card line 1
  (`Aniya Von`, `Y`, `03`/`2023`) are shown with account `00000000001`, and no error is shown.
- **TS-COCRDSLC-12** (NAV-COCRDSLC-02): Given a card displayed via the list, when PF3 is pressed,
  then XCTL goes to COCRDLIC with CDEMO-FROM-PROGRAM = `COCRDSLC`, CDEMO-TO-PROGRAM = `COCRDLIC`,
  CDEMO-PGM-CONTEXT = 0, and CDEMO-USER-TYPE = `U`. Given the menu path, PF3 goes to COMEN01C.
- **TS-COCRDSLC-13** (NAV-COCRDSLC-01): Given a card displayed via the list, when PF7 is pressed,
  then the same card is re-read and re-displayed (no paging, no error).
- **TS-COCRDSLC-14** (ERR-COCRDSLC-01): Given CARDDAT is closed or disabled, when a valid search is
  entered, then the error line begins `File Error: READ     on CARDDAT   returned RESP ` and the
  account field is red.

## Open questions
- Should the target enforce that the card belongs to the entered account, or should the view take
  only the card number (Q-COCRDSLC-01)?
- Should a regular user be restricted to viewing their own cards? No authorization check exists.
- Should invalid input be echoed back, as most screens in the application do (Q-COCRDSLC-02)?
