# Fidelity ledger: online programs

Covers every `Q-` ID defined in `../spec/programs/` for COSGN00C, COMEN01C, COADM01C, COUSR00C,
COUSR01C, COUSR02C, COUSR03C, COACTVWC, COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC, COTRN00C, COTRN01C,
COTRN02C, COBIL00C, CORPT00C, and CSUTLDTC. Decision values, the classification policy, and the
user decisions (D- IDs) are defined in `README.md`. Behavior not listed here as FIX or CHANGE is
preserved as specified in `../spec/`.

Message text in backticks is verbatim, including leading, doubled, and trailing spaces and
punctuation. Text marked **NEW** does not exist in the COBOL. Text marked "reused from X" is an
existing verbatim CardDemo message taken from another program. "Focus" is the modern equivalent of
the 3270 cursor position. "USER" and "ADMIN" are the roles of user types `U` and `A`.

## Conventions used by the rows

These rules are shared by many rows. A row that cites one of them applies it exactly as written here.

| ID | Rule |
|:--|:--|
| C-ROLE | (D-AUTH, D-FIX-SEC) The role is taken from the server-side session created at sign-on, never from client input. Admin endpoints (admin menu, user list, add, update, delete, transaction-type maintenance) require ADMIN and answer HTTP 403 to a USER session; the UI then shows the Main Menu with `No access - Admin Only option... ` (reused from COMEN01C, trailing space included). A request with no valid session goes to a clean sign-on screen with no message. |
| C-SCOPE | (D-USER-ACCT) A USER may read or change only the accounts linked to their user ID and the cards, transactions, and bill payments of those accounts; an ADMIN is unrestricted. An out-of-scope key is answered exactly as if it did not exist, with the program's own not-found message, so existence is not revealed. Lists, paging, page numbers, "highest/last record" lookups, and boundary messages are computed over the in-scope rows only. |
| C-MASK | (D-FIX-SEC) The server never sends a full card number, SSN, government id, CVV, or password to the client. Card numbers are shown as `************` + last 4 digits (for example `************1234`); SSNs as `***-**-` + last 4 digits; government ids as `*` for every character except the last 4. Where a masked value is shown in an input field, submitting the field unchanged means "keep the stored value" (for keys: "use the value held in the session"); any other submitted value is treated as new input and validated by the existing rules. What the user types stays in the browser field as typed. |
| C-SESSION | The CARDDEMO-COMMAREA is replaced by the server-side session plus explicit request parameters. Navigation (menu entry, PF3, PF12, returns between programs) never changes the signed-on user ID or role. |
| C-RENDER | One request produces one response that shows the final screen state the COBOL user would see (the last SEND of the task); intermediate SENDs are not reproduced. |
| C-KEYLEN | (D-ACCTID, DF-9) Numeric key fields keep the COBOL full-length rule: account numbers are typed as 11 digits and card and transaction ids as 16 digits, with leading zeros. Shorter input gets the program's existing message; input is never left-padded. |
| C-AUDIT | (D-SEC-EXTRAS) An audit record is one row in a server-side audit table (actor user ID, action, target key, changed field names, timestamp, and for sign-on events the client address). Audited events: sign-on success, failure, and lockout (Q-COSGN00C-05); every successful user add, update, and delete (COUSR01C, COUSR02C, Q-COUSR03C-07); every successful account or customer update (Q-COACTUPC-28); every successful card update (Q-COCRDUPC-17). Audit records are not shown on any screen and never contain passwords, full card numbers, CVVs, SSNs, or government ids. |

## COSGN00C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COSGN00C-01 | Passwords stored and compared in plain text; demo password published | security | FIX | Passwords are stored only as BCrypt hashes (cost 12) and checked with BCrypt verify. Migration and the seed hash the USRSEC values; the demo users are loaded only by the demo/test seed profile. No plain-text password is stored, logged, or returned (C-MASK). Messages unchanged except as stated in Q-COSGN00C-04. | n/a |
| Q-COSGN00C-02 | Entered ID and password are upper-cased, stored ones are not, so lower-case users can never sign on | functional-defect | FIX | User IDs are case-insensitive: upper-cased on add, update, and sign-on, and stored upper case. Passwords are case-sensitive: verified exactly as typed, with no upper-casing. `user0001` / `PASSWORD` signs on as `USER0001`; `USER0001` / `password` fails with the Q-COSGN00C-04 message (TS-COSGN00C-04 changes accordingly); a user created with password `abcd1234` signs on with `abcd1234` (TS-COSGN00C-11 now succeeds). | n/a |
| Q-COSGN00C-03 | Password field starts with 8 hidden underscores that are sent back as data | platform | PLATFORM | The password input starts empty and sends only the typed characters: typing `PASSW` sends `PASSW`. | n/a |
| Q-COSGN00C-04 | Distinct "User not found" and "Wrong Password" messages allow user-ID enumeration | security | FIX (D-FIX-SEC, D-SEC-EXTRAS) | An unknown user ID and a wrong password get the same response: message `Invalid User ID or Password. Try again ...` (**NEW**; replaces `User not found. Try again ...` and `Wrong Password. Try again ...` on the sign-on screen), the password field is cleared, the User ID stays as typed (upper-cased), and focus goes to Password. For an unknown ID the server still runs a BCrypt verify against a fixed dummy hash, so response time does not reveal existence. `Please enter User ID ...`, `Please enter Password ...`, and `Unable to verify the User ...` are unchanged. The generic message is set by D-SEC-EXTRAS and overrides D-COSMETIC here. | n/a |
| Q-COSGN00C-05 | No failed-attempt limit, lockout, audit log, expiry, or idle timeout | security | FIX (D-FIX-SEC, D-SEC-EXTRAS) | (D-SEC-EXTRAS) After 5 consecutive failed sign-ons for one user ID, sign-on for that ID is refused for 15 minutes from the fifth failure with the same message `Invalid User ID or Password. Try again ...` (**NEW**, as in Q-COSGN00C-04), so a lockout is not disclosed. Attempts during the lockout, including ones with the correct password, are refused without extending it; when the lockout ends the counter starts again at 0. A successful sign-on resets the counter. The server-side session expires after 15 minutes without a request; the next request shows the sign-on screen with empty fields and no message. Sign-on success, failure, and lockout write an audit record (C-AUDIT) with the client address. No password expiry is added. | n/a |
| Q-COSGN00C-06 | CARDDEMO-COMMAREA is never initialized at sign-on, so later programs see stale fields | platform | PLATFORM | A successful sign-on creates a new server-side session (any previous session id is invalidated) holding only the user ID, role, and the from-program and from-transaction values of BR-COSGN00C-06; account, card, and customer context start empty (C-SESSION). | n/a |
| Q-COSGN00C-07 | Any user type other than `A` signs on as a regular user | security | FIX | User type is an enumeration: `A` (ADMIN) or `U` (USER), enforced by a database CHECK constraint and by the add and update screens (Q-COUSR01C-03, Q-COUSR02C-04). `A` goes to the Admin Menu, `U` to the Main Menu. Migration rejects and reports any USRSEC record with another type (the sample data has none). | n/a |
| Q-COSGN00C-08 | The received password is sent back to the terminal on an error redisplay | security | FIX | No response contains the password (C-MASK). After any sign-on error the password field is empty. | n/a |
| Q-COSGN00C-09 | Invalid-key path sends an uninitialized map; wrong-password branch does not set the error flag | platform | PLATFORM | An invalid key shows the sign-on screen with empty fields and `Invalid key pressed. Please see below...`. The error-flag detail has no counterpart. | n/a |
| Q-COSGN00C-10 | Programs that see EIBCALEN = 0 XCTL here with a COMMAREA, so sign-on receives foreign input | platform | PLATFORM | Any request without a valid session (never signed on, expired, or direct URL) is redirected to a clean sign-on screen with no message (C-ROLE). | n/a |
| Q-COSGN00C-11 | CDEMO-USER-ID is overwritten with the entered ID before verification | dead-code | NOT-PORTED | No counterpart: identity is bound to the session only after successful authentication, and a failed attempt leaves no identity. | n/a |
| Q-COSGN00C-12 | Map oddities: 9-byte CURTIME, PROT instead of ASKIP header fields, stray dark input field | platform | PLATFORM | The header shows the time as `HH:MM:SS` as on every other screen, header fields cannot take focus, and there is no stray input field. Tab order is User ID, then Password. | n/a |

## COMEN01C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COMEN01C-01 | Admin-only menu check is dead and tests type `U` explicitly | security | FIX | The menu definition carries a role column, and every function endpoint checks the session role (C-ROLE). A USER who selects an ADMIN-only option gets `No access - Admin Only option... ` and stays on the menu. With the shipped table no main-menu option is admin-only, so the visible menu is unchanged. | n/a |
| Q-COMEN01C-02 | Table REDEFINES declares OCCURS 12 over data for 11 entries | platform | PLATFORM | The menu is built from the 11 defined options; there is no 12th row. | n/a |
| Q-COMEN01C-03 | Option checks do not short-circuit and subscript the table with an invalid option | platform | PLATFORM | The option is validated first; an invalid option shows `Please enter a valid option number...` and nothing else is evaluated. The visible result is unchanged. | n/a |
| Q-COMEN01C-04 | Unreachable `DUMMY` branch builds a malformed "coming soon" message | dead-code | NOT-PORTED | No "coming soon" handling exists in the target. | n/a |
| Q-COMEN01C-05 | Only COPAUS0C has an availability check; a missing program abends the task | platform | PLATFORM | Each option has a feature flag; all are on by default (D-SCOPE). Selecting a disabled option shows, in red, `This option ` + option name + ` is not installed...` (for option 11: `This option Pending Authorization View is not installed...`) and stays on the menu. | n/a |
| Q-COMEN01C-06 | Admin users can never reach the main menu or its functions | business-choice | PRESERVE (DF-10) | Business choice (DF-10): admins sign on to the Admin Menu only, and the Admin Menu has no link to the Main Menu. The API does not scope ADMIN requests (D-USER-ACCT), but no admin screen leads to main-menu functions. | n/a |
| Q-COMEN01C-07 | Duplicate move to CDEMO-FROM-PROGRAM; unused copybook and file name | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COMEN01C-08 | "Not installed" message built with DELIMITED BY double space | cosmetic | PRESERVE | The visible text stays `This option Pending Authorization View is not installed...` (red). | n/a |

## COADM01C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COADM01C-01 | Admin menu and user-maintenance programs never check the user type | security | FIX | The Admin Menu and options 1-6 require ADMIN server-side (C-ROLE). A USER session gets HTTP 403 and the Main Menu with `No access - Admin Only option... ` (reused from COMEN01C). TS-COADM01C-09 becomes this rejection. | n/a |
| Q-COADM01C-02 | Table REDEFINES declares OCCURS 9 over 6 entries and has no role column | platform | PLATFORM | The Admin Menu is built from the 6 defined options, each recorded with role ADMIN. | n/a |
| Q-COADM01C-03 | "Not installed" message is green and does not name the option | cosmetic | PRESERVE | A disabled option shows `This option is not installed ...` in the informational (green) style, without the option name. | n/a |
| Q-COADM01C-04 | Relies on HANDLE CONDITION PGMIDERR instead of an INQUIRE check | platform | PLATFORM | Each option has a feature flag (options 5 and 6 belong to the transaction-type module, on by D-SCOPE). A disabled option gives the Q-COADM01C-03 message. Failures inside an enabled function are handled by that function. | n/a |
| Q-COADM01C-05 | Options 3 and 4 open empty from the menu but pre-fetched from the user list | business-choice | PRESERVE | Both entry modes are kept: from the menu the update and delete screens start empty with focus on User ID; from the list they open with the selected user fetched. | n/a |
| Q-COADM01C-06 | Extra period and fall-through after RETURN in PGMIDERR-ERR-PARA | dead-code | NOT-PORTED | No counterpart. | n/a |

## COUSR00C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COUSR00C-01 | ENTER always restarts the list at the search key or first user, page 1 | business-choice | PRESERVE | ENTER (with or without a selection error) rebuilds the list from the search key, or from the first user when the search is blank, with page number 1. | n/a |
| Q-COUSR00C-02 | End-of-file messages appear on normal pages and replace "Invalid selection" | functional-defect | FIX | The end-of-file messages stay as in the COBOL (for example `You have reached the bottom of the page...` on the first display of the 10 sample users, and `You have reached the top of the page...` after PF7 back to the first page). When the same ENTER also has an invalid selection code, the message is `Invalid selection. Valid values are U and D` (it can no longer be replaced by a paging message). | n/a |
| Q-COUSR00C-03 | Search key beyond the last user (or empty file) abends the task with INVREQ | functional-defect | FIX | The list is shown empty with page 1 and the message `User ID NOT found...` (reused from COUSR02C/COUSR03C). No abend. TS-COUSR00C-11 becomes this outcome. | n/a |
| Q-COUSR00C-04 | Last-key paging field is not updated after a short last page | functional-defect | FIX | The paging state always holds the first and last user ID actually displayed. No visible change: PF8 on a short last page still gives `You are already at the bottom of the page...`. | n/a |
| Q-COUSR00C-05 | Page number counts from the search start; PF7 cannot go before the search key | business-choice | PRESERVE | After a search the first page shown is page 1, and PF7 on it gives `You are already at the top of the page...` even if earlier users exist. | n/a |
| Q-COUSR00C-06 | Selection codes are not cleared when the list is rebuilt | functional-defect | FIX | Every list rebuild clears all Sel fields. After TS-COUSR00C-09 the list shows page 1 with an empty Sel column and the message `Invalid selection. Valid values are U and D`. | n/a |
| Q-COUSR00C-07 | Skip-one-record logic depends on EIBAID rather than on paging direction | platform | PLATFORM | Paging uses explicit direction with keyset bounds (forward: IDs greater than the last shown; backward: IDs less than the first shown). Returning from update or delete shows page 1 from the first user, as now. No user is ever skipped. | n/a |
| Q-COUSR00C-08 | One turn can SEND the map several times | platform | PLATFORM | C-RENDER. | n/a |
| Q-COUSR00C-09 | Paging keys held between turns break when the boundary user is deleted | concurrency | FIX | Paging bounds are exclusive keys, so a deleted boundary user causes no error and hides no user: PF7 shows the 10 users before the remembered first key, and PF8 shows the users after the remembered last key, none skipped. `Unable to lookup User...` is shown only for a real data-access failure. | n/a |
| Q-COUSR00C-10 | No user-type check; any caller can list all users | security | FIX | ADMIN required (C-ROLE). | n/a |
| Q-COUSR00C-11 | PF3 always returns to the Admin Menu | business-choice | PRESERVE | PF3 goes to the Admin Menu regardless of the caller. | n/a |
| Q-COUSR00C-12 | Search is a start position, not a filter | business-choice | PRESERVE | The list starts at the first user ID greater than or equal to the search key and continues in key order. | n/a |
| Q-COUSR00C-13 | Unused working storage; DISPLAYs of RESP codes | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COUSR00C-14 | Selected user ID is taken from terminal-returned fields | security | FIX | A selection is resolved on the server from the row number of the page the server last sent (rows 1-10 to user IDs). A submitted user ID that was not on that page is ignored: no navigation, the list is redisplayed. | n/a |

## COUSR01C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COUSR01C-01 | Password stored in plain text; no confirmation or policy | security | FIX | The password is stored as a BCrypt hash (Q-COSGN00C-01). The field accepts 1-8 characters as today; no confirmation field or complexity rule is added. Messages unchanged. | n/a |
| Q-COUSR01C-02 | ID and password stored as typed but compared upper-cased at sign-on | functional-defect | FIX | The User ID is upper-cased before the duplicate check and the write; the password is hashed exactly as typed (Q-COSGN00C-02). Typing `test0003` stores `TEST0003`, the message is `User TEST0003 has been added ...`, and `test0003` then signs on (TS-COUSR01C-10 changes accordingly). | n/a |
| Q-COUSR01C-03 | User type not validated; any non-blank character is stored | security | FIX | The type is upper-cased and must then be `A` or `U`. Otherwise the message is `User Type must be A or U...` (**NEW**), focus goes to User Type, and nothing is written. This check runs right after `User Type can NOT be empty...`. `a` is stored as `A`; `Z` is rejected (TS-COUSR01C-09 changes). | n/a |
| Q-COUSR01C-04 | SEC-USR-FILLER is written from uninitialized storage | data-corruption | FIX | The user table has no filler column. Any fixed-width unload of USRSEC writes the 23 filler bytes as spaces. | n/a |
| Q-COUSR01C-05 | Screen advertises `F12=Exit` but PF12 is rejected as an invalid key | functional-defect | FIX | F12 returns to the Admin Menu without writing anything, as PF12 does in COUSR02C and COUSR03C. The legend stays `F12=Exit`. TS-COUSR01C-13 changes accordingly. | n/a |
| Q-COUSR01C-06 | User IDs with spaces are accepted and the success message truncates them | functional-defect | FIX | After upper-casing, the User ID must be 1-8 characters, letters A-Z and digits only. Otherwise the message is `User ID must be letters and digits only...` (**NEW**), focus goes to User ID, and nothing is written. This check runs right after `User ID can NOT be empty...`. | n/a |
| Q-COUSR01C-07 | No user-type check of the caller | security | FIX | ADMIN required (C-ROLE). | n/a |
| Q-COUSR01C-08 | Ungrammatical message "User ID already exist..." | cosmetic | PRESERVE | The text stays `User ID already exist...`. | n/a |

## COUSR02C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COUSR02C-01 | PF3 saves and exits unconditionally, hiding validation and update errors | functional-defect | FIX | PF3 with no field changed exits to the caller without a message (as now). PF3 with changes runs the PF5 save: on success it exits (the success message is not shown, as now); on a validation error or a failed update it stays on the screen with that message (for example `First Name can NOT be empty...`) and writes nothing. TS-COUSR02C-10 changes: the user stays on the screen with `First Name can NOT be empty...`. | n/a |
| Q-COUSR02C-02 | Key not pinned: PF5 overwrites whatever user ID is on screen | data-corruption | FIX | The server remembers the user ID last fetched on this screen. PF5 (and the PF3 save) update only that user, and only when the User ID field still equals it. When nothing was fetched or the field holds another ID, PF5 behaves exactly as ENTER: it fetches the typed ID (typed edits are discarded, Q-COUSR02C-10) and writes nothing. In TS-COUSR02C-12, PF5 fetches `USER0002` and shows `Press PF5 key to save your updates ...`; `USER0002` is unchanged. | n/a |
| Q-COUSR02C-03 | Stored password is sent to the terminal on fetch | security | FIX | A fetch never returns the password; the Password field is empty after a fetch. On save, an empty Password keeps the current password, so `Password can NOT be empty...` is no longer shown on this screen. A typed password replaces it (stored as a BCrypt hash, case-sensitive). A typed password that matches the current hash does not count as a change. | n/a |
| Q-COUSR02C-04 | No type validation or case handling; an admin can change their own type | security | FIX | The type is upper-cased and must be `A` or `U`, otherwise `User Type must be A or U...` (**NEW**, same text as COUSR01C). An admin cannot change the type of their own signed-on user ID: `You cannot change your own User Type...` (**NEW**), nothing written. This also prevents demoting the last admin, because only an admin can reach this screen. When another user's type or password changes, that user's active sessions are ended. | n/a |
| Q-COUSR02C-05 | Fetch takes a needless exclusive lock; no lost-update protection | concurrency | FIX | A fetch is a plain read that remembers the row version. Save updates only if the version is unchanged. Otherwise nothing is written, the fields are refreshed with the current values (Password empty), and the message is `Record changed by some one else. Please review` (reused from COACTUPC/COCRDUPC). If the user was deleted in between, the message is `User ID NOT found...`. | n/a |
| Q-COUSR02C-06 | Save does not check its READ; a missing user gives "Unable to Update User..." | functional-defect | FIX | A save for a user that no longer exists shows `User ID NOT found...` with focus on User ID; nothing is written. With Q-COUSR02C-02, an unknown typed ID on PF5 is handled as a fetch and shows the same message (TS-COUSR02C-13 changes). | n/a |
| Q-COUSR02C-07 | Several SENDs per turn | platform | PLATFORM | C-RENDER. | n/a |
| Q-COUSR02C-08 | Change detection is exact; identical values give "Please modify to update ..." | business-choice | PRESERVE | Change detection compares names and type exactly (a case-only change is a change and is saved); no change gives `Please modify to update ...` and no write. The password is compared as stated in Q-COUSR02C-03. | n/a |
| Q-COUSR02C-09 | No user-type check of the caller | security | FIX | ADMIN required (C-ROLE). | n/a |
| Q-COUSR02C-10 | ENTER always re-fetches and discards typed edits | business-choice | PRESERVE | ENTER fetches the user in the User ID field and overwrites the other fields with the stored values (Password shown empty per Q-COUSR02C-03). | n/a |

## COUSR03C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COUSR03C-01 | PF5 deletes whatever user ID is on screen, with no fetch or confirmation | data-corruption | FIX | The server remembers the user ID last fetched on this screen. PF5 deletes only that user, and only when the User ID field still equals it; the fetched screen with `Press PF5 key to delete this user ...` is the confirmation step. When nothing was fetched or the field holds another ID, PF5 behaves exactly as ENTER (fetch and show `Press PF5 key to delete this user ...`, or `User ID NOT found...`) and deletes nothing. In TS-COUSR03C-07 the first PF5 shows `USER0004` with the prompt and a second PF5 deletes it. | n/a |
| Q-COUSR03C-02 | An admin can delete their own signed-on ID or the last admin | security | FIX | Deleting the signed-on user's own ID is refused with `You cannot delete your own User ID...` (**NEW**) and nothing is deleted. This also protects the last admin, because only an admin can reach this screen. TS-COUSR03C-12 changes accordingly. | n/a |
| Q-COUSR03C-03 | Delete errors say "Unable to Update User..."; unknown ID on PF5 ends with that text | cosmetic | PRESERVE | Delete failures still show `Unable to Update User...` verbatim. An unknown ID on PF5 is now handled as a fetch (Q-COUSR03C-01) and shows `User ID NOT found...`. A user deleted by someone else between fetch and PF5 shows `User ID NOT found...` (ERR-COUSR03C-04). | n/a |
| Q-COUSR03C-04 | PF12 returns to the admin menu but is not in the key legend | cosmetic | PRESERVE | F12 goes to the Admin Menu, F3 goes back to the caller, and the legend is unchanged (F12 not listed). | n/a |
| Q-COUSR03C-05 | Several SENDs per turn | platform | PLATFORM | C-RENDER. | n/a |
| Q-COUSR03C-06 | Errors after a failed read put the cursor on protected First Name | platform | PLATFORM | After a read or delete error, focus goes to User ID, the only editable field. | n/a |
| Q-COUSR03C-07 | No user-type check of the caller; no audit trail of deletions | security | FIX (D-FIX-SEC, D-SEC-EXTRAS) | ADMIN required (C-ROLE). Every deletion writes an audit record (C-AUDIT), and the deleted user's active sessions are ended. | n/a |
| Q-COUSR03C-08 | Unused WS-USR-MODIFIED copied from COUSR02C | dead-code | NOT-PORTED | No counterpart. | n/a |

## COACTVWC

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COACTVWC-01 | Account missing from ACCTDAT does not stop processing; unread account data is shown | functional-defect | FIX | When the account master read fails, processing stops. The customer is not read, no account or customer fields are shown, the account number is shown in error style, and for not-found the message is `Account:00000000077 not found in Acct Master file.Resp:000000013  Reas:0000` (ERR-COACTVWC-03 text with the entered number). TS-COACTVWC-08 changes accordingly. | n/a |
| Q-COACTVWC-02 | "Account number not provided" is always overwritten; several messages are unused | dead-code | NOT-PORTED | The observable behavior is kept: a blank account number gives `No input received`. The overwritten and unused texts are not ported. | n/a |
| Q-COACTVWC-03 | Menu entry INITIALIZEs the COMMAREA, erasing user id and type; PF3 forces type `U` | functional-defect | FIX | C-SESSION: user ID and role survive entry and PF3. No visible change for a USER. | n/a |
| Q-COACTVWC-04 | Info message "Displaying details of given Account" is defined but never set | functional-defect | FIX | After a successful display, the info line is `Displaying details of given Account`. On entry and after any error it stays `Enter or update id of account to display`. | n/a |
| Q-COACTVWC-05 | Not-found messages expose raw RESP/RESP2 numbers and are cut at 75 characters | cosmetic | PRESERVE | The not-found messages are reproduced verbatim, including the fixed NOTFND codes and the 75-character cut: `Account:00000000099 not found in Cross ref file.  Resp:000000013  Reas:0000`, `Account:<11 digits> not found in Acct Master file.Resp:000000013  Reas:0000`, and `CustId:<9 digits> not found in customer master.Resp: 000000013  REAS:0000000`. The cross-reference text is also the out-of-scope answer (C-SCOPE). | n/a |
| Q-COACTVWC-06 | MUSTFILL is enforced only by some terminals; message has a double space | platform | PLATFORM | The field accepts at most 11 digits. The server accepts only exactly 11 digits that are not all zero; anything else (including `1`) gives `Account Filter must  be a non-zero 11 digit number` (double space kept). C-KEYLEN. | n/a |
| Q-COACTVWC-07 | Dead code, duplicate exit paragraph, unused literals and copybooks | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COACTVWC-08 | Display picture has 9 integer positions; the top digit of large amounts is lost | functional-defect | FIX | Amounts show all 10 integer digits. Values below 1,000,000,000.00 look exactly as today (for example `+      2,020.00`); `1234567890.12` shows as `+1,234,567,890.12`. | n/a |
| Q-COACTVWC-09 | Full SSN and government id shown to any signed-on user | security | FIX | The SSN is shown as `***-**-` + last 4 digits and the government id as in C-MASK. Access follows C-SCOPE: a USER can view only linked accounts, and any other account number gives the cross-reference not-found message of Q-COACTVWC-05. | n/a |
| Q-COACTVWC-10 | Phone fields show 13 of 15 stored characters | cosmetic | PRESERVE | Phones are shown as the first 13 characters, `(AAA)BBB-CCCC`. | n/a |

## COACTUPC

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COACTUPC-01 | Rewrite layout writes the group id into the ZIP bytes and FILLER garbage into ACCT-GROUP-ID | data-corruption | FIX | The save updates columns by name: the edited group id goes to ACCT-GROUP-ID, and ACCT-ADDR-ZIP and every other column not edited on the screen keep their stored values. In fixed-width exports bytes 103-112 stay the ZIP and bytes 113-122 hold the group id. Repair of already-shifted data is Q-ENT-01/Q-ENT-02 in `ledger-batch-data.md`. | n/a |
| Q-COACTUPC-02 | Customer lock failure is reported as "Changes committed to database" | functional-defect | FIX | A customer lock failure gives state L: error `Could not lock customer record for update`, info `Changes unsuccessful. Please try again`, and nothing written (account included, Q-COACTUPC-03). TS-COACTUPC-16 changes accordingly. | n/a |
| Q-COACTUPC-03 | Account and customer rewrites are not atomic | concurrency | FIX | Account and customer are updated in one database transaction. If either update fails, both are rolled back, the error is `Update of record failed`, the info is `Changes unsuccessful. Please try again`, and the state is F. ACCTDAT record 24 is unchanged in the TS-COACTUPC-17 situation. | n/a |
| Q-COACTUPC-04 | After a concurrency conflict the stale snapshot is kept, so every retry fails | functional-defect | FIX | On a conflict, the current stored values become the new snapshot and are shown, with state S, error `Record changed by some one else. Please review`, and info `Update account details presented above.`. A later change plus F5 is checked against this new snapshot. Nothing stays locked between requests. | n/a |
| Q-COACTUPC-05 | Change detection upper-cases and trims, so case-only corrections cannot be saved | functional-defect | FIX | Change detection compares the trimmed values case-sensitively: changing `kessler` to `Kessler` is a change and goes through validation and the F5 confirmation. Only changed fields are edited (Q-COACTUPC-07). | n/a |
| Q-COACTUPC-06 | Blank group id (LOW-VALUES vs spaces) always counts as a change | functional-defect | FIX | Blank, spaces, and LOW-VALUES compare equal. ENTER without edits on an account with a blank group id gives `No change detected with respect to values fetched.`, focus on Active Y/N, state S. Because only changed fields are edited (Q-COACTUPC-07), the stored KS/15414 state and ZIP are never checked here. TS-COACTUPC-06 changes accordingly. | n/a |
| Q-COACTUPC-07 | Every field is validated on every change, so most sample accounts cannot be saved | user-decision | CHANGE (D-VALIDATE-CHANGED) | Only fields the user changed are validated. A field is changed when its submitted value differs from the fetched value under the change detection of Q-COACTUPC-05 and Q-COACTUPC-06 (trimmed, case-sensitive, blank equals spaces equals LOW-VALUES); a masked SSN or government id submitted as shown is unchanged (Q-COACTUPC-28). Multi-part fields are one field: if any part of the SSN, a phone number, or a date changes, all parts of that field are edited. The state/ZIP cross edit (VR-COACTUPC-33) runs when State or ZIP changed. Changed fields are edited in the COBOL order and the first failing message is shown, with the existing highlighting and focus. Unchanged fields are accepted and written back exactly as stored, even when they would fail an edit. TS-COACTUPC-18 changes: account `00000000001` with credit limit changed to `3000.00` gives `Changes validated.Press F5 to save` although its stored FICO is 274. | n/a |
| Q-COACTUPC-08 | State/ZIP table is wrong for states whose ZIP codes start with 0 | functional-defect | FIX | For CT, MA, ME, NH, NJ, RI, and VT, a table entry `ssNN` matches a ZIP whose first digit is `0` and whose digits 2-3 are `NN`. For PR and VI it matches a ZIP whose first two digits are `00` and whose digits 3-4 are `NN`. All other entries keep comparing ZIP digits 1-2. Examples: CT `06103` passes, CT `60601` fails with `Invalid zip code for state`, PR `00601` passes, VI `00802` passes. TS-COACTUPC-19 changes accordingly. | n/a |
| Q-COACTUPC-09 | Military and freely associated codes (AA, AE, AP, FM, MH, PW) are in the ZIP table but not the state list | business-choice | PRESERVE | These codes are rejected with `State: is not a valid state code` (TS-COACTUPC-20 unchanged). Their unreachable ZIP pairs are not ported. | n/a |
| Q-COACTUPC-10 | A blank optional phone is written as `(` + LOW-VALUES + `)` ... | data-corruption | FIX | A phone with all three parts blank is stored empty (15 spaces in fixed-width exports) and shown as three empty parts. Partially blank phones keep the existing messages. | n/a |
| Q-COACTUPC-11 | Phone parts are split by position, assuming `(AAA)BBB-CCCC` | business-choice | PRESERVE | Phones keep the `(AAA)BBB-CCCC` storage format and are split by position. Migration reports, without changing them, stored phones that do not match this format. | n/a |
| Q-COACTUPC-12 | Field name cut to "Current Cycle Credit Limi"; cycle totals called "Limit" | cosmetic | PRESERVE | Labels and messages stay verbatim, for example `Current Cycle Credit Limi is not valid` and `Current Cycle Debit Limit must be supplied.`. | n/a |
| Q-COACTUPC-13 | A date rejected by CSUTLDTC is not highlighted and the DOB future check still runs | functional-defect | FIX | A date that fails the calendar check is shown in error style with focus on its year part (first failing field in BR-COACTUPC-19 order), and the Date of Birth future check is skipped for an invalid DOB. The message text is unchanged. | n/a |
| Q-COACTUPC-14 | CSUTLDTC gets an 8-byte date but always passes 10 bytes to CEEDAYS | platform | PLATFORM | The calendar date is validated directly as year, month, and day. A date that passes the existing year, month, day, and leap-year edits is valid; no extra message is produced. | n/a |
| Q-COACTUPC-15 | Amounts accept any NUMVAL-C value: negative limits, balance edits, silent truncation | data-corruption | FIX | An amount with more than 10 integer digits or more than 2 decimal places is rejected with the field's existing message `<field name> is not valid` (for example `Credit Limit is not valid`) instead of being truncated. Negative limits, a cash limit above the credit limit, and online edits of the balance and cycle totals stay allowed, as in the COBOL. | n/a |
| Q-COACTUPC-16 | Account number is protected once details are shown | business-choice | PRESERVE | To switch accounts the user exits with F3 or completes a save; F12 re-reads the same account. | n/a |
| Q-COACTUPC-17 | With no errors the cursor goes to the protected account number | platform | PLATFORM | Focus goes to the first field in error; with no error in state S or E it goes to Active Y/N, the first editable field. | n/a |
| Q-COACTUPC-18 | F5 saves the values received on the F5 turn without re-validating | security | FIX | F5 saves the values the server validated and kept at the confirmation turn (state N). Values submitted with F5 are ignored. | n/a |
| Q-COACTUPC-19 | After a save attempt the next key shows an empty search screen; typed account is ignored | business-choice | PRESERVE | After states C, L, or F, the next ENTER or F12 shows the empty search screen with info `Enter or update id of account to update`. On the result screen the account number is read-only, because any typed value would be ignored. | n/a |
| Q-COACTUPC-20 | "Not found" flags are never set; unread account or customer data is used | functional-defect | FIX | Account not found: processing stops, there is no customer read and no snapshot, the state stays "not fetched", and the message is `Account:<11 digits> not found in Acct Master file.Resp:000000013  Reas:0000`. Customer not found: no snapshot, no details shown, the state stays "not fetched", and the message is `CustId:<9 digits> not found in customer master.Resp: 000000013  REAS:0000000`. | n/a |
| Q-COACTUPC-21 | Menu entry INITIALIZEs the COMMAREA; PF3 forces type `U` | functional-defect | FIX | C-SESSION. No visible change for a USER. | n/a |
| Q-COACTUPC-22 | Unused paragraphs, unreachable WHENs, unused messages | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COACTUPC-23 | Comments around the EFT and primary-flag snippets are swapped | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COACTUPC-24 | Account number must be typed as all 11 digits | business-choice | PRESERVE | C-KEYLEN: `24` or `0000000024` gives `Account Number if supplied must be a 11 digit Non-Zero Number`. | n/a |
| Q-COACTUPC-25 | Account status and primary flag reject lowercase `y`/`n` | business-choice | PRESERVE | `y` gives `Account Status must be Y or N.` (and `Primary Card Holder must be Y or N.` for the primary flag). | n/a |
| Q-COACTUPC-26 | Name, city, and country allow only letters and spaces; some fields are not edited | business-choice | PRESERVE | `Anne-Marie` gives `First Name can have alphabets only.`, and `St. Louis` gives `City can have alphabets only.`. Address line 2, group id, and government id stay unedited. | n/a |
| Q-COACTUPC-27 | DOB equal to today is "in the future"; century limited to 1900-2099 | business-choice | PRESERVE | Today's date gives `Date of Birth:cannot be in the future ` (trailing space kept); years outside 1900-2099 give the existing century message. | n/a |
| Q-COACTUPC-28 | Full SSN and government id displayed and editable by any user, no audit | security | FIX | Access follows C-SCOPE (out of scope gives the cross-reference not-found message). The SSN parts are shown as `***`, `**`, and the last 4 digits; the government id is masked (C-MASK). If all three SSN parts are submitted as shown, the SSN is unchanged. To change it the user types all three parts, which go through the existing SSN edits; a part left masked fails with `SSN: First 3 chars must be all numeric.` or `SSN 4th & 5th chars must be all numeric.`. A government id submitted as shown is kept; any other value replaces it. Every saved change of SSN or government id writes an audit record (C-AUDIT). | n/a |
| Q-COACTUPC-29 | "Details of selected account shown above" is always overwritten | dead-code | NOT-PORTED | The text is not ported. After a fetch the info line shows `Update account details presented above.`, as today; the state-based info message of BR-COACTUPC-17 is the intended design. | n/a |
| Q-COACTUPC-30 | Amounts shown with 9 integer positions; re-entry saves a different value | data-corruption | FIX | Amounts show all 10 integer digits as in Q-COACTVWC-08, so submitting a displayed value unchanged saves the same value. | n/a |

## COCRDLIC

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COCRDLIC-01 | Header promises account scoping for non-admins, but every user sees every card | user-decision | CHANGE (D-USER-ACCT) | D-USER-ACCT (C-SCOPE): a USER sees only the cards of linked accounts. An account filter or card filter outside the user's scope gives `NO RECORDS FOUND FOR THIS SEARCH CONDITION.`. An ADMIN is unrestricted. | n/a |
| Q-COCRDLIC-02 | After a selection error the list restarts at the file start but keeps the page number | functional-defect | FIX | After `PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE` or `INVALID ACTION CODE`, the same page is shown again (same cards, same page number), with the typed codes on the rows where they were typed and the offending rows in error style. | n/a |
| Q-COCRDLIC-03 | A new or changed filter starts from the current page's first card | functional-defect | FIX | Applying or changing a filter starts at page 1 with the first matching card. No match gives `NO RECORDS FOUND FOR THIS SEARCH CONDITION.`. | n/a |
| Q-COCRDLIC-04 | Next-page indicator uses the next raw record, so PF8 can show an empty page | functional-defect | FIX | The next-page indicator is set only when another matching card exists. PF8 never shows an empty page. On the last page, PF8 gives the COBOL "no next page" responses: the first press keeps the page with error `NO MORE RECORDS TO SHOW` and info `TYPE S FOR DETAIL, U TO UPDATE ANY RECORD`, and the next press gives `NO MORE PAGES TO DISPLAY`. | n/a |
| Q-COCRDLIC-05 | Filtering scans the whole card file for every page | platform | PLATFORM | Indexed queries by card number or account id. Order (card number ascending) and page contents are the same. | n/a |
| Q-COCRDLIC-06 | One-digit page number shows page 10 as 0 | functional-defect | FIX | The page number is an unbounded integer (`10`, `11`, ...); PF7 from page 10 shows page 9. | n/a |
| Q-COCRDLIC-07 | Filtered PF7 hitting the file start reports a file error | functional-defect | FIX | When a backward page reaches the first matching card before 7 rows are collected, the target shows page 1 (the first up to 7 matching cards from the start) with page number 1 and no file-error message. | n/a |
| Q-COCRDLIC-08 | Selected-row index 0 reads the byte before the array | platform | PLATFORM | No row selected means no selection; behavior unchanged. | n/a |
| Q-COCRDLIC-09 | Stray `I` moves an attribute byte into subscript I | dead-code | NOT-PORTED | No visible effect; nothing ported. | n/a |
| Q-COCRDLIC-10 | Row 1 has different protection, marking, and cursor handling | platform | PLATFORM | All 7 rows behave the same. In error, a blank selected row shows `*`, and focus goes to the first row in error. | n/a |
| Q-COCRDLIC-11 | User type forced to `U` on every transfer; COMMAREA re-initialized on PF3 return | functional-defect | FIX | C-SESSION. No visible change for a USER. | n/a |
| Q-COCRDLIC-12 | Lowercase `s` and `u` are rejected | business-choice | PRESERVE | `s` or `u` gives `INVALID ACTION CODE`. | n/a |
| Q-COCRDLIC-13 | Filters and page position are lost after view or update | business-choice | PRESERVE | Returning from view or update shows page 1 with blank filters. | n/a |
| Q-COCRDLIC-14 | Copies 254 bytes beyond the 160-byte COMMAREA passed by XCTL | platform | PLATFORM | No counterpart: the list state is held in the session and reset on fresh entry. | n/a |
| Q-COCRDLIC-15 | Unreachable statements, unused fields, duplicated WHEN | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COCRDLIC-16 | Full 16-digit card numbers on the list | security | FIX | Card numbers are shown masked (C-MASK). A selection is resolved by row number on the server, which maps it to the full card number in the session. The full number is never sent to the client. | n/a |
| Q-COCRDLIC-17 | "NO MORE RECORDS TO SHOW" appears in the red error line | cosmetic | PRESERVE | Shown in the error line and error style whenever the COBOL shows it. | n/a |
| Q-COCRDLIC-18 | Stopper fields CRDSTP2-7 overlap the zero-length stopper | platform | PLATFORM | No counterpart. | n/a |

## COCRDSLC

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COCRDSLC-01 | Entered account is not checked against the card's account | functional-defect | FIX | The card is shown only when its stored account id equals the entered (or passed) account and is in scope (C-SCOPE). Otherwise the message is `Did not find cards for this search condition`, both keys are in error style, and no details are shown. | n/a |
| Q-COCRDSLC-02 | Invalid (non-numeric) keys are redisplayed empty | cosmetic | PRESERVE | A key that fails the numeric edit is redisplayed empty, in error style, with focus on it. | n/a |
| Q-COCRDSLC-03 | Menu entry INITIALIZEs the COMMAREA; PF3 sets type `U` | functional-defect | FIX | C-SESSION. No visible change for a USER. | n/a |
| Q-COCRDSLC-04 | "IF SUPPLIED" wording for mandatory keys; unused messages | cosmetic | PRESERVE | Observable texts are verbatim: `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER`, `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER`, `Account number not provided`, `Card number not provided`, `No input received`. Unused texts are not ported. | n/a |
| Q-COCRDSLC-05 | LIT-CCLISTMAP names this program's own map; unused | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COCRDSLC-06 | Copies 12 bytes beyond the 160-byte COMMAREA | platform | PLATFORM | No counterpart (C-SESSION). | n/a |
| Q-COCRDSLC-07 | ERRMSG LENGTH=80 overflows into row 24 | platform | PLATFORM | One message line holding up to 80 characters. | n/a |
| Q-COCRDSLC-08 | Legend says "ENTER=Search Cards" even when keys are protected | cosmetic | PRESERVE | The legend is unchanged on both paths. | n/a |
| Q-COCRDSLC-09 | Unreachable IF INPUT-ERROR block; unused SEND-LONG-TEXT | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COCRDSLC-10 | Name shown as stored here but upper-cased in COCRDUPC | business-choice | PRESERVE | The view shows the embossed name as stored (for example `Aniya Von`). | n/a |
| Q-COCRDSLC-11 | Full card number displayed | security | FIX | After a read, and on entry from the list, the card number field shows the masked number (C-MASK). ENTER with the masked value re-reads the card held in the session. The CVV is never displayed. | n/a |

## COCRDUPC

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COCRDUPC-01 | Every save overwrites the CVV with a value derived from spaces | data-corruption | FIX | The online card update never changes the CVV; the stored CVV is kept. | n/a |
| Q-COCRDUPC-02 | The on-screen account id is written, re-parenting the card | data-corruption | FIX | The account id is never written from input. A fetch requires the card's stored account id to equal the entered account and to be in scope; otherwise `Did not find cards for this search condition`. A save leaves the account id unchanged. | n/a |
| Q-COCRDUPC-03 | 59-byte record FILLER is written from leftover storage | data-corruption | FIX | A save updates only the name, status, and expiry columns. There is no filler column; fixed-width exports write the 59 filler bytes as spaces. | n/a |
| Q-COCRDUPC-04 | Name shown upper case after fetch, so any save writes it upper case | business-choice | PRESERVE | After a fetch the name is shown upper case, and a save writes the name field as submitted, so a status-only change stores `ANIYA VON`. | n/a |
| Q-COCRDUPC-05 | Name edit rejects apostrophes, hyphens, and periods | business-choice | PRESERVE | `Lucious O'Connell` gives `Card name can only contain alphabets and spaces` on any save. | n/a |
| Q-COCRDUPC-06 | Expiry day kept when month or year changes, producing impossible dates | data-corruption | FIX (DF-7) | If the kept day is after the last day of the new month and year, the stored day becomes that last day: `2025-08-29` with month `02` stores `2025-02-28`, and with year `2028` stores `2028-02-29`. No message is shown (the day is not on the screen) (DF-7). | n/a |
| Q-COCRDUPC-07 | Change detection ignores case | business-choice | PRESERVE | A case-only name change gives `No change detected with respect to values fetched.`. | n/a |
| Q-COCRDUPC-08 | PF12 on the menu path edits the discarded input first | functional-defect | FIX | F12 never edits the discarded input. It re-reads the card and shows the stored values in state S with info `Details of selected card shown above` and no error message. | n/a |
| Q-COCRDUPC-09 | List entry sets state S even when the fetch fails | functional-defect | FIX | If the card is not found on list entry, the state stays "not fetched": error `Did not find cards for this search condition`, no details, details not editable, and no save possible. The keys are shown read-only (card masked), and F3 returns to the list. | n/a |
| Q-COCRDUPC-10 | Menu entry INITIALIZEs the COMMAREA; exit forces type `U` | functional-defect | FIX | C-SESSION. No visible change for a USER. | n/a |
| Q-COCRDUPC-11 | Copies 329 bytes beyond the 160-byte COMMAREA, including the state byte | platform | PLATFORM | The screen state is always reset on entry; no stale state can send the user back to the list. | n/a |
| Q-COCRDUPC-12 | Key fields cannot be changed after a fetch | business-choice | PRESERVE | To edit another card the user exits with F3 or completes the save cycle. | n/a |
| Q-COCRDUPC-13 | After a save attempt the next key shows an empty search screen; conflict discards edits | business-choice | PRESERVE | As in BR-COCRDUPC-09 and BR-COCRDUPC-06: the menu path shows an empty search screen after a save attempt, and a conflict shows the current stored values with the user's edits discarded. | n/a |
| Q-COCRDUPC-14 | ERRMSG overflow, attribute value in a color byte, PROT account overridden | platform | PLATFORM | One 80-character message line; normal field styling; the account field is editable exactly in the states where BR-COCRDUPC-15 makes it unprotected. | n/a |
| Q-COCRDUPC-15 | "F12=Cancel" shown only in state N although F12 also works in S and E | cosmetic | PRESERVE | The legend shows `F12=Cancel` only in state N; F12 works in states S, E, and N. | n/a |
| Q-COCRDUPC-16 | Unreachable branches, unused messages and items | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COCRDUPC-17 | Any signed-on user can change any card; no audit record | user-decision | CHANGE (D-USER-ACCT, D-SEC-EXTRAS) | D-USER-ACCT (C-SCOPE): a USER can fetch and update only cards of linked accounts; any other card gives `Did not find cards for this search condition`. Every saved card change writes an audit record (C-AUDIT) with the card's last 4 digits. | n/a |
| Q-COCRDUPC-18 | Messages contain source typos | cosmetic | PRESERVE | Verbatim: `Changes validated.Press F5 to save`, `Record changed by some one else. Please review`, `ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER`, `CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER`. | n/a |
| Q-COCRDUPC-19 | JUSTIFY=RIGHT expiry fields: a one-digit month may fail | platform | PLATFORM | The expiry month accepts `5` or `05` (an integer 1-12, stored as 2 digits); out-of-range values give `Card expiry month must be between 1 and 12`. The year is 4 digits. | n/a |

## COTRN00C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COTRN00C-01 | Return from the detail screen with PF5 skips the first record | functional-defect | FIX | Entering the list from COTRN01C with F5 shows page 1 starting at the first (in-scope) transaction; nothing is skipped. | n/a |
| Q-COTRN00C-02 | Search ID beyond the last key abends the task with INVREQ | functional-defect | FIX | The list is shown empty with page 1 and the message `Transaction ID NOT found...` (reused from COTRN01C). No abend. | n/a |
| Q-COTRN00C-03 | Page numbers count from the last ENTER start point | business-choice | PRESERVE | After a search the first page is page 1, and PF7 on it gives `You are already at the top of the page...`. | n/a |
| Q-COTRN00C-04 | Search ID must be typed as all 16 digits | business-choice | PRESERVE | C-KEYLEN: `683580` gives `Tran ID must be Numeric ...`. | n/a |
| Q-COTRN00C-05 | End-of-file messages appear even when a full page is shown | cosmetic | PRESERVE | `You have reached the top of the page...` and `You have reached the bottom of the page...` appear exactly when the COBOL shows them. | n/a |
| Q-COTRN00C-06 | Invalid selection is not an error; only the first marked row counts | functional-defect | FIX | Kept: only the first marked row is considered, other marks are ignored, and the list is rebuilt from the search ID or the start. Fixed: `Invalid selection. Valid value is S` is shown and is not replaced by a paging message on the same response (as in Q-COUSR00C-02). | n/a |
| Q-COTRN00C-07 | After a non-numeric search ID, STARTBR still runs with a stale key | functional-defect | FIX | A non-numeric search ID gives `Tran ID must be Numeric ...`, and the list is not re-read: the page shown before this ENTER stays displayed with its page number and paging position. | n/a |
| Q-COTRN00C-08 | Every user sees all customers' transactions | user-decision | CHANGE (D-USER-ACCT) | D-USER-ACCT (C-SCOPE): a USER lists only transactions whose card belongs to a linked account. Search positions, paging, page numbers, and boundary messages work within that set. | n/a |
| Q-COTRN00C-09 | One task can SEND the map several times | platform | PLATFORM | C-RENDER. | n/a |
| Q-COTRN00C-10 | Paging assumes the boundary record still exists | concurrency | FIX | Keyset paging on exclusive bounds (`id > last shown` forward, `id < first shown` backward). Deleted boundary records cause no skipped or repeated rows; records added between turns appear in key order. | n/a |
| Q-COTRN00C-11 | Backward page reaching the file start leaves rows blank and keys stale | functional-defect | FIX | A backward page that reaches the first in-scope transaction shows the first 10 in-scope transactions as page 1 with `You have reached the top of the page...`. | n/a |
| Q-COTRN00C-12 | Date column has a 2-digit year; description cut to 26 characters | cosmetic | PRESERVE | The list shows the origination date as `MM/DD/YY` and the first 26 characters of the description. | n/a |

## COTRN01C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COTRN01C-01 | View-only read uses READ UPDATE and serializes viewers | concurrency | FIX | Plain read with no lock. | n/a |
| Q-COTRN01C-02 | Map sent twice on first entry with a preselected ID | platform | PLATFORM | C-RENDER. | n/a |
| Q-COTRN01C-03 | F5 to the list makes the list skip its first record | functional-defect | FIX | Fixed in the list (Q-COTRN00C-01): F5 shows page 1 from the first in-scope transaction. | n/a |
| Q-COTRN01C-04 | ID must be typed as all 16 digits; non-numeric input just gives "not found" | business-choice | PRESERVE | C-KEYLEN: `683580` and any non-numeric input give `Transaction ID NOT found...`. | n/a |
| Q-COTRN01C-05 | Full card number on screen | security | FIX | The card number is shown masked (C-MASK). | n/a |
| Q-COTRN01C-06 | Display drops time parts and truncates description, merchant name, and city | cosmetic | PRESERVE | Same fields and lengths as the COBOL screen: dates without time, description 60, merchant name 30, city 25 characters. | n/a |
| Q-COTRN01C-07 | No scoping by user, account, or card | user-decision | CHANGE (D-USER-ACCT) | D-USER-ACCT (C-SCOPE): a transaction outside the user's scope gives `Transaction ID NOT found...`. | n/a |
| Q-COTRN01C-08 | Type and category shown as codes, no descriptions | business-choice | PRESERVE | Codes only; reference descriptions are not looked up. | n/a |

## COTRN02C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COTRN02C-01 | New ID is highest key + 1 with no lock | concurrency | FIX | The TRAN-ID comes atomically from a database sequence that starts above the highest existing TRAN-ID and is shared by every online writer of transactions (COBIL00C). It is a 16-digit zero-padded number. Concurrent adds never collide, so `Tran ID already exist...` does not occur on the normal path. | n/a |
| Q-COTRN02C-02 | Online add bypasses posting: no balance, limit, or card checks | business-choice | PRESERVE | Adding a transaction writes only the transaction row, as in BR-COTRN02C-08. | n/a |
| Q-COTRN02C-03 | Timestamps stored as a 10-character date; processing date typed by the user | business-choice | PRESERVE | Both dates are stored as typed with no time part (fixed-width exports: `YYYY-MM-DD` followed by 16 spaces), and the processing date stays user-entered. | n/a |
| Q-COTRN02C-04 | PF5 copies the last record of the whole file and may add it in the same keystroke | functional-defect | FIX | F5 copies the fields of the highest-ID transaction in the user's scope (C-SCOPE; the whole table for an ADMIN) into the form. It then runs the ENTER validations with CONFIRM treated as blank, so it shows either the first failing message or `Confirm to add this transaction...`. It never writes on the F5 keystroke. TS-COTRN02C-13 changes: the copy is shown for review and nothing is written. | n/a |
| Q-COTRN02C-05 | With sample data PF5 always ends with "Proc Date can NOT be empty..." | business-choice | PRESERVE | A copied blank processing date stays blank, so F5 with the sample data shows `Proc Date can NOT be empty...` (Q-COTRN02C-04 validation). No date is invented. | n/a |
| Q-COTRN02C-06 | When both account and card are typed, the card is silently replaced | data-corruption | FIX | When both are typed, the card must belong to the account; otherwise the message is `Card Number does not match the Account ID...` (**NEW**), focus goes to Card Number, and nothing is written. The typed card is kept on every later turn and is the card stored. When only the card is typed, the account is filled from it, and the card is not re-derived on the confirming turn. | n/a |
| Q-COTRN02C-07 | Account with several cards hits DUPKEY on the account index | functional-defect | FIX (DF-6) | When only the account is typed and it has several cards, the card with the lowest card number is used, with no error (DF-6). | n/a |
| Q-COTRN02C-08 | Numeric fields must be typed at full length with leading zeros | business-choice | PRESERVE | C-KEYLEN, also for type (2), category (4), and merchant ID (9): `1` as an account gives `Account ID must be Numeric...`, and type `1` gives `Type CD must be Numeric...`. | n/a |
| Q-COTRN02C-09 | Type and category not checked against reference data; zero and negative amounts accepted | functional-defect | FIX | After the numeric checks, the type code must exist in TRANTYPE, otherwise `Type CD NOT found...` (**NEW**, focus Type CD), and the type + category pair must exist in TRANCATG, otherwise `Category CD NOT found...` (**NEW**, focus Category CD). Reason: CBTRN03C abends on unknown codes (ERR-CBTRN03C-04). Zero amounts, negative purchase amounts, and free-text source are still accepted (business choice). | n/a |
| Q-COTRN02C-10 | Dead prefill from CDEMO-CT02-TRN-SELECTED, unreachable blanking block, unused items | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COTRN02C-11 | Only the first failing rule is reported | business-choice | PRESERVE | One message at a time, in the COBOL rule order, implemented as normal control flow. | n/a |
| Q-COTRN02C-12 | Dates outside the CEEDAYS range (2513) are accepted | functional-defect | FIX (DF-8) | Dates before 1582-10-15 are rejected with `Orig Date - Not a valid date...` or `Proc Date - Not a valid date...` (Q-CSUTLDTC-05). `1500-01-01` is rejected (TS-COTRN02C-09 changes). | n/a |
| Q-COTRN02C-13 | Empty TRANSACT gives "Transaction ID NOT found..." and nothing can be added | functional-defect | FIX | With no transactions, the first new ID is `0000000000000001` (BR-COTRN02C-05 intent). | n/a |
| Q-COTRN02C-14 | Option is open to regular users; the menu label once said "Admin Only" | user-decision | CHANGE (D-USER-ACCT) | D-USER-ACCT (C-SCOPE): open to USER and ADMIN, but a USER can add only for linked accounts. An out-of-scope account gives `Account ID NOT found...` and an out-of-scope card gives `Card Number NOT found...`. | n/a |
| Q-COTRN02C-15 | Full card number shown after it is derived from the account | security | FIX | The derived card is shown masked (for example `************3697`) and held in the session; a masked value submitted back means "use the held card" (C-MASK). | n/a |

## COBIL00C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-COBIL00C-01 | Payment ID is highest key + 1 with no lock | concurrency | FIX | The same database sequence as Q-COTRN02C-01. Concurrent payments never collide. | n/a |
| Q-COBIL00C-02 | Errors not checked during the payment; balance updated even when the write fails | data-corruption | FIX | The confirmed payment (account lock, card lookup, ID, transaction insert, balance update) is one database transaction. Any failure rolls it all back: the balance and transactions are unchanged, the message is the failing step's existing text (for example `Unable to Add Bill pay Transaction...`), and no success message is shown. | n/a |
| Q-COBIL00C-03 | One task can SEND the map several times | platform | PLATFORM | C-RENDER. | n/a |
| Q-COBIL00C-04 | Account number not validated; must be typed as all 11 digits | business-choice | PRESERVE | C-KEYLEN: `1` gives `Account ID NOT found...`. | n/a |
| Q-COBIL00C-05 | Payment stored with a positive amount, opposite to batch credits | business-choice | PRESERVE | The payment row keeps a positive TRAN-AMT with type `02` and category `0002`. | n/a |
| Q-COBIL00C-06 | Payment bypasses TCATBAL, cycle credit, and status checks | business-choice | PRESERVE | Only the transaction row and the account's current balance change, as in BR-COBIL00C-06. | n/a |
| Q-COBIL00C-07 | Balances of 1,000,000,000.00 or more are truncated in TRAN-AMT | functional-defect | FIX | If the balance exceeds 999,999,999.99 (the largest TRAN-AMT), nothing is written and the message is `Balance too large for online payment...` (**NEW**). | n/a |
| Q-COBIL00C-08 | Balance shown comes from the previous turn; the amount paid is re-read on confirm | concurrency | FIX | The confirming request carries the balance that was shown. If the current balance differs, nothing is written, the screen shows the current balance with CONFIRM cleared, and the message is `Confirm to make a bill payment...`. The success text is unchanged (`Payment successful.  Your Transaction ID is <id>.`). | n/a |
| Q-COBIL00C-09 | Balance moved to the screen even when the account was not read | functional-defect | FIX | The balance is filled only from an account read in the same request. No visible change. | n/a |
| Q-COBIL00C-10 | Copied CDEMO-CB00-INFO extension unused; prefill path dead | dead-code | NOT-PORTED | No counterpart. | n/a |
| Q-COBIL00C-11 | Account with several cards gives DUPKEY; payment still proceeds | functional-defect | FIX (DF-6) | The payment is attributed to the account's card with the lowest card number, with no error (DF-6). | n/a |
| Q-COBIL00C-12 | Empty TRANSACT abends the task | functional-defect | FIX | With no transactions, the first payment ID is `0000000000000001`. | n/a |
| Q-COBIL00C-13 | ACCTDAT read with UPDATE on display-only turns | concurrency | FIX | The display turn is a plain read; only the confirmed payment locks the account inside its transaction. | n/a |

## CORPT00C

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CORPT00C-01 | NUMVAL-C runs before the NUMERIC checks, so the "not valid Year" messages can never appear | functional-defect | FIX | The raw text of each date part must be digits. Otherwise the existing message for that part is shown, in rule order: for example `20A2` as start year gives `Start Date - Not a valid Year...`, and `1x` as end month gives `End Date - Not a valid Month...`. | n/a |
| Q-CORPT00C-02 | Two-digit year `22` is accepted as year 0022 | functional-defect | FIX (DF-8) | `22` passes the digit check, but year 0022 is outside the supported range and gives `Start Date - Not a valid date...` or `End Date - Not a valid date...` (Q-CSUTLDTC-05). | n/a |
| Q-CORPT00C-03 | Start date after end date is accepted and gives an empty report | business-choice | PRESERVE | The request is accepted and produces a report with no transactions (TS-CORPT00C-11 keeps the COBOL outcome). | n/a |
| Q-CORPT00C-04 | Any character selects a report type; extra selections ignored | platform | PLATFORM | The report type is a single choice of Monthly, Yearly, or Custom (radio buttons; the API takes one enum value). No choice gives `Select a report type to print report...`. | n/a |
| Q-CORPT00C-05 | Monthly and Yearly mean the current calendar month and year | business-choice | PRESERVE | As in BR-CORPT00C-02 and BR-CORPT00C-03, using the server's current date. | n/a |
| Q-CORPT00C-06 | Report selects by processing date, so records with a blank processing date never appear | business-choice | PRESERVE | Selection by TRAN-PROC-TS within the range. | n/a |
| Q-CORPT00C-07 | Hard-coded job stream, NOTIFY of the region user, same job name | platform | PLATFORM | The request launches the report job once, with parameters start date, end date, and requester user ID. For a USER the job filters to the requester's linked accounts (D-REPORT); for an ADMIN it is unfiltered. No JCL is generated. | n/a |
| Q-CORPT00C-08 | Every request creates new TRANSACT backup and extract generations | platform | PLATFORM | The report reads the transaction store directly. An online request creates no backup or extract generation. | n/a |
| Q-CORPT00C-09 | 17 WRITEQ TD calls are not atomic and can interleave | platform | PLATFORM | One atomic job launch. If the launch fails, nothing is started and the message is `Unable to Write TDQ (JOBS)...` (existing text). | n/a |
| Q-CORPT00C-10 | Success message says "submitted for printing" but returns no job ID or location | business-choice | PRESERVE | The message stays `<Monthly/Yearly/Custom> report submitted for printing ...` with no job ID. The report output is placed as defined for TRANREPT in `ledger-batch-data.md`. | n/a |
| Q-CORPT00C-11 | Confirm `N` silently clears the whole form | business-choice | PRESERVE | CONFIRM `N` or `n` shows the empty form with no message. | n/a |
| Q-CORPT00C-12 | Only the first error is shown | business-choice | PRESERVE | One message at a time, in the COBOL rule order. | n/a |
| Q-CORPT00C-13 | Unused file name and copybook, unused no-erase branch, OCCURS 1000 over 17 lines, log line | dead-code | NOT-PORTED | No counterpart. | n/a |

## CSUTLDTC

| Q ID | Summary | Category | Decision | Target behavior | Reference patch |
|:--|:--|:--|:--|:--|:--|
| Q-CSUTLDTC-01 | 88-level FC-INVALID-DATE actually means "valid" | cosmetic | PRESERVE | Behavior kept: an all-zero feedback token means valid. The target's internal name says "valid"; nothing is user-visible. | n/a |
| Q-CSUTLDTC-02 | Diagnostic TstDate field is overwritten with the length prefix and 8 characters | platform | PLATFORM | The target validator returns a structured result (valid, out of supported range, or invalid) with no diagnostic text. No caller shows the TstDate text. | n/a |
| Q-CSUTLDTC-03 | Always passes 10 bytes of date and picture, reading past shorter caller fields | platform | PLATFORM | Callers pass explicit date strings of the declared format (`YYYY-MM-DD` or `YYYYMMDD`). Calendar rules: Gregorian, leap years, days per month, range 1582-10-15 to 9999-12-31. | n/a |
| Q-CSUTLDTC-04 | Lilian day number computed but never returned | dead-code | NOT-PORTED | No caller needs it; not returned. | n/a |
| Q-CSUTLDTC-05 | COTRN02C and CORPT00C accept CEEDAYS 2513 (unsupported range) as valid | functional-defect | FIX (DF-8) | Out-of-range dates (before 1582-10-15) are invalid for every caller, giving each caller's existing "Not a valid date" message (Q-COTRN02C-12, Q-CORPT00C-02). COACTUPC already rejects them. | n/a |

## Business choices preserved

Every PRESERVE row with category business-choice. The target keeps the COBOL behavior.

- Q-COMEN01C-06: Admin users can never reach the main menu or its functions.
- Q-COADM01C-05: Options 3 and 4 open empty from the menu but pre-fetched from the user list.
- Q-COUSR00C-01: ENTER always restarts the list at the search key or first user, page 1.
- Q-COUSR00C-05: Page number counts from the search start; PF7 cannot go before the search key.
- Q-COUSR00C-11: PF3 always returns to the Admin Menu.
- Q-COUSR00C-12: Search is a start position, not a filter.
- Q-COUSR02C-08: Change detection is exact; identical values give "Please modify to update ...".
- Q-COUSR02C-10: ENTER always re-fetches and discards typed edits.
- Q-COACTUPC-09: Military and freely associated codes (AA, AE, AP, FM, MH, PW) are in the ZIP table but not the state list.
- Q-COACTUPC-11: Phone parts are split by position, assuming `(AAA)BBB-CCCC`.
- Q-COACTUPC-16: Account number is protected once details are shown.
- Q-COACTUPC-19: After a save attempt the next key shows an empty search screen; typed account is ignored.
- Q-COACTUPC-24: Account number must be typed as all 11 digits.
- Q-COACTUPC-25: Account status and primary flag reject lowercase `y`/`n`.
- Q-COACTUPC-26: Name, city, and country allow only letters and spaces; some fields are not edited.
- Q-COACTUPC-27: DOB equal to today is "in the future"; century limited to 1900-2099.
- Q-COCRDLIC-12: Lowercase `s` and `u` are rejected.
- Q-COCRDLIC-13: Filters and page position are lost after view or update.
- Q-COCRDSLC-10: Name shown as stored here but upper-cased in COCRDUPC.
- Q-COCRDUPC-04: Name shown upper case after fetch, so any save writes it upper case.
- Q-COCRDUPC-05: Name edit rejects apostrophes, hyphens, and periods.
- Q-COCRDUPC-07: Change detection ignores case.
- Q-COCRDUPC-12: Key fields cannot be changed after a fetch.
- Q-COCRDUPC-13: After a save attempt the next key shows an empty search screen; conflict discards edits.
- Q-COTRN00C-03: Page numbers count from the last ENTER start point.
- Q-COTRN00C-04: Search ID must be typed as all 16 digits.
- Q-COTRN01C-04: ID must be typed as all 16 digits; non-numeric input just gives "not found".
- Q-COTRN01C-08: Type and category shown as codes, no descriptions.
- Q-COTRN02C-02: Online add bypasses posting: no balance, limit, or card checks.
- Q-COTRN02C-03: Timestamps stored as a 10-character date; processing date typed by the user.
- Q-COTRN02C-05: With sample data PF5 always ends with "Proc Date can NOT be empty...".
- Q-COTRN02C-08: Numeric fields must be typed at full length with leading zeros.
- Q-COTRN02C-11: Only the first failing rule is reported.
- Q-COBIL00C-04: Account number not validated; must be typed as all 11 digits.
- Q-COBIL00C-05: Payment stored with a positive amount, opposite to batch credits.
- Q-COBIL00C-06: Payment bypasses TCATBAL, cycle credit, and status checks.
- Q-CORPT00C-03: Start date after end date is accepted and gives an empty report.
- Q-CORPT00C-05: Monthly and Yearly mean the current calendar month and year.
- Q-CORPT00C-06: Report selects by processing date, so records with a blank processing date never appear.
- Q-CORPT00C-10: Success message says "submitted for printing" but returns no job ID or location.
- Q-CORPT00C-11: Confirm `N` silently clears the whole form.
- Q-CORPT00C-12: Only the first error is shown.

## New behavior introduced

Every FIX or CHANGE row that changes what a user sees on a screen or what a later screen, report,
or job sees in the data. **NEW** marks message text that does not exist in the COBOL.

Sign-on and user administration:
- Q-COSGN00C-02: user IDs are case-insensitive and stored upper case; passwords are case-sensitive (`USER0001` / `password` no longer signs on).
- Q-COSGN00C-04 (D-SEC-EXTRAS): unknown user and wrong password both show `Invalid User ID or Password. Try again ...` (**NEW**); the password field is cleared.
- Q-COSGN00C-05 (D-SEC-EXTRAS): 5 consecutive failed sign-ons lock the ID for 15 minutes (same generic message); a session expires after 15 idle minutes; sign-on events are audited (C-AUDIT).
- C-AUDIT (D-SEC-EXTRAS): sign-ons and every saved user add, update, and delete, account or customer update, and card update write an audit record (not visible on screens).
- Q-COADM01C-01: a USER session reaching admin functions gets the Main Menu with `No access - Admin Only option... ` (reused).
- Q-COUSR00C-02: `Invalid selection. Valid values are U and D` is no longer replaced by an end-of-file message.
- Q-COUSR00C-03: a search beyond the last user shows an empty list with `User ID NOT found...` (reused) instead of an abend.
- Q-COUSR00C-06: Sel fields are cleared whenever the list is rebuilt.
- Q-COUSR00C-09: deleting a boundary user between pages no longer gives `Unable to lookup User...` or hides a user.
- Q-COUSR01C-02: a new user ID is stored upper case (`test0003` is added as `TEST0003`).
- Q-COUSR01C-03: user type is upper-cased and must be `A` or `U`: `User Type must be A or U...` (**NEW**).
- Q-COUSR01C-05: F12 on Add User returns to the Admin Menu (previously "Invalid key pressed").
- Q-COUSR01C-06: user ID must be letters and digits only: `User ID must be letters and digits only...` (**NEW**).
- Q-COUSR02C-01: PF3 stays on the screen and shows the message when the save fails validation or the update fails.
- Q-COUSR02C-02: PF5 with a User ID other than the fetched one acts as ENTER (fetch) and writes nothing.
- Q-COUSR02C-03: the Password field is empty after a fetch; leaving it empty keeps the password, so `Password can NOT be empty...` no longer appears on update.
- Q-COUSR02C-04: `User Type must be A or U...` (**NEW**) and `You cannot change your own User Type...` (**NEW**); a user whose type or password changes is signed out.
- Q-COUSR02C-05: a concurrent change gives `Record changed by some one else. Please review` (reused) and refreshes the fields.
- Q-COUSR02C-06: saving a user that no longer exists shows `User ID NOT found...` instead of `Unable to Update User...`.
- Q-COUSR03C-01: PF5 with a User ID other than the fetched one acts as ENTER (fetch and prompt); a delete needs a fetch first.
- Q-COUSR03C-02: deleting one's own ID gives `You cannot delete your own User ID...` (**NEW**).
- Q-COUSR03C-07: deletions are audited and the deleted user's sessions end.

Account view and update:
- Q-COACTVWC-01: a missing account master record stops the display; no account or customer fields are shown.
- Q-COACTVWC-04: the info line shows `Displaying details of given Account` after a successful display.
- Q-COACTVWC-08: amounts of 1,000,000,000.00 or more show all 10 integer digits.
- Q-COACTVWC-09: SSN shown as `***-**-1234` and government id masked; USER limited to linked accounts.
- Q-COACTUPC-01: saving an account no longer destroys the ZIP or the group id (affects the interest rate group used by CBACT04C).
- Q-COACTUPC-02: a customer lock failure shows `Changes unsuccessful. Please try again` instead of `Changes committed to database`.
- Q-COACTUPC-03: a failed customer update also rolls back the account update.
- Q-COACTUPC-04: after `Record changed by some one else. Please review` the current values are shown and the next save can succeed.
- Q-COACTUPC-05: case-only corrections (`kessler` to `Kessler`) can be saved.
- Q-COACTUPC-06: ENTER without edits on a blank-group account gives `No change detected with respect to values fetched.` instead of running all edits.
- Q-COACTUPC-07 (D-VALIDATE-CHANGED): only fields the user changed are validated; unchanged stored values are accepted, so sample accounts with legacy values (FICO 274, non-general area codes, rejected state/ZIP pairs) can be updated.
- Q-COACTUPC-08: ZIP codes starting with 0 are checked correctly (CT `06103` passes, CT `60601` now fails).
- Q-COACTUPC-10: a blank optional phone is stored empty instead of with LOW-VALUES.
- Q-COACTUPC-13: a date rejected by the calendar check is highlighted and gets focus.
- Q-COACTUPC-15: amounts with more than 10 integer digits or more than 2 decimals are rejected (`<field> is not valid`) instead of truncated.
- Q-COACTUPC-20: a missing account or customer stops the fetch; no details from an unread record are shown.
- Q-COACTUPC-28: SSN and government id are masked on the update screen; SSN changes need all three parts retyped; changes are audited; USER limited to linked accounts.
- Q-COACTUPC-30: large amounts show all 10 integer digits and survive an unchanged re-save.

Cards:
- Q-COCRDLIC-01: a USER lists only cards of linked accounts.
- Q-COCRDLIC-02: after a selection error the same page is shown again.
- Q-COCRDLIC-03: a new or changed filter starts at page 1.
- Q-COCRDLIC-04: PF8 never shows an empty page.
- Q-COCRDLIC-06: page numbers above 9 display correctly.
- Q-COCRDLIC-07: filtered PF7 reaching the start shows page 1 instead of a file error.
- Q-COCRDLIC-16: card numbers shown as `************1234`.
- Q-COCRDSLC-01: the card is shown only when it belongs to the entered account; otherwise `Did not find cards for this search condition`.
- Q-COCRDSLC-11: card number masked after a read.
- Q-COCRDUPC-01: the CVV is no longer overwritten on save.
- Q-COCRDUPC-02: the card's account id is never changed by a save; a card that does not belong to the entered account is not found.
- Q-COCRDUPC-06: an expiry day past the end of the new month is stored as the last day of that month.
- Q-COCRDUPC-08: F12 on the menu path no longer shows an edit message from the discarded input.
- Q-COCRDUPC-09: a card deleted since the list was shown gives a non-editable "not found" screen instead of empty editable details.
- Q-COCRDUPC-17: a USER can update only cards of linked accounts; card updates are audited.

Transactions, bill payment, reports:
- Q-COTRN00C-01: returning from the detail screen with F5 no longer skips the first transaction.
- Q-COTRN00C-02: a search beyond the last ID shows an empty list with `Transaction ID NOT found...` (reused) instead of an abend.
- Q-COTRN00C-06: `Invalid selection. Valid value is S` is no longer replaced by a paging message.
- Q-COTRN00C-07: a non-numeric search ID keeps the current page displayed.
- Q-COTRN00C-08: a USER lists only transactions of linked accounts.
- Q-COTRN00C-11: a backward page reaching the start shows a full first page.
- Q-COTRN01C-05: card number masked.
- Q-COTRN01C-07: out-of-scope transaction gives `Transaction ID NOT found...`.
- Q-COTRN02C-01 and Q-COBIL00C-01: concurrent adds and payments no longer fail with `Tran ID already exist...`.
- Q-COTRN02C-04: F5 copies the user's latest in-scope transaction and never adds it on the same keystroke.
- Q-COTRN02C-06: a card that does not belong to the typed account gives `Card Number does not match the Account ID...` (**NEW**); the typed card is the one stored.
- Q-COTRN02C-07 and Q-COBIL00C-11: accounts with several cards use the lowest card number instead of failing.
- Q-COTRN02C-09: unknown codes give `Type CD NOT found...` (**NEW**) or `Category CD NOT found...` (**NEW**).
- Q-COTRN02C-12, Q-CORPT00C-02, Q-CSUTLDTC-05: dates before 1582-10-15 (for example `1500-01-01`, or year `22` read as 0022) are rejected with the existing "Not a valid date" messages.
- Q-COTRN02C-13 and Q-COBIL00C-12: the first transaction can be added, and the first payment made, when TRANSACT is empty.
- Q-COTRN02C-14: a USER can add transactions only for linked accounts and cards.
- Q-COTRN02C-15: the derived card number is masked.
- Q-COBIL00C-02: a failed payment step leaves the balance unchanged.
- Q-COBIL00C-07: balances above 999,999,999.99 give `Balance too large for online payment...` (**NEW**).
- Q-COBIL00C-08: if the balance changed after it was shown, the payment is not made and the user is asked to confirm again with the new balance.
- Q-CORPT00C-01: non-digit date parts give the existing "Not a valid Month/Day/Year" messages, which could never appear before.

## Counts

Rows by category and decision (220 rows).

| Category | FIX | PRESERVE | CHANGE | PLATFORM | NOT-PORTED | Total |
|:--|--:|--:|--:|--:|--:|--:|
| security | 24 | 0 | 0 | 0 | 0 | 24 |
| data-corruption | 13 | 0 | 0 | 0 | 0 | 13 |
| concurrency | 9 | 0 | 0 | 0 | 0 | 9 |
| functional-defect | 51 | 0 | 0 | 0 | 0 | 51 |
| cosmetic | 0 | 18 | 0 | 0 | 0 | 18 |
| business-choice | 0 | 42 | 0 | 0 | 0 | 42 |
| platform | 0 | 0 | 0 | 37 | 0 | 37 |
| dead-code | 0 | 0 | 0 | 0 | 20 | 20 |
| user-decision | 0 | 0 | 6 | 0 | 0 | 6 |
| Total | 97 | 60 | 6 | 37 | 20 | 220 |
