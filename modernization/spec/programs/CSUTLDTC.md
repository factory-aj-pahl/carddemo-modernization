# CSUTLDTC: Date validation subroutine that wraps the Language Environment service CEEDAYS

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CSUTLDTC.cbl` (157 lines) |
| Module | base |
| Kind | Subroutine (called with `CALL 'CSUTLDTC'`; runs under CICS in the callers below) |
| Transaction ID / Job | None |
| BMS map | none |
| Copybooks | none |
| Calls / XCTL targets | `CALL "CEEDAYS"` (Language Environment callable service that converts a date to a Lilian day number) |
| Data stores | none |

## Purpose
Checks whether a character date is a real calendar date in a given format. It calls the Language
Environment service CEEDAYS, translates the CEEDAYS feedback code into a short text, and returns an
80-character result whose first four characters are the severity code. Callers use it to reject
impossible dates such as `2023-02-29` after their own format checks. The Lilian day number that
CEEDAYS computes is discarded.

## Invocation and state
- Interface: `PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT`.

  | Parameter | PIC | Direction | Meaning |
  |:--|:--|:--|:--|
  | `LS-DATE` | X(10) | In | Date text. All 10 bytes are passed to CEEDAYS, including trailing blanks. |
  | `LS-DATE-FORMAT` | X(10) | In | CEEDAYS picture string, for example `YYYY-MM-DD`. All 10 bytes are passed. |
  | `LS-RESULT` | X(80) | Out | Formatted result (layout in BR-CSUTLDTC-02) |
  | `RETURN-CODE` | special register | Out | Set to the CEEDAYS severity: 0 for valid, normally 3 for an error |

- Callers:
  - `COTRN02C` (`app/cbl/COTRN02C.cbl:389-427`) and `CORPT00C` (`app/cbl/CORPT00C.cbl:388-426`) pass a
    10-character date and `'YYYY-MM-DD'`.
  - `COACTUPC` calls it through the inline copybook `CSUTLDPY` (`app/cpy/CSUTLDPY.cpy:285-298`) with
    `WS-EDIT-DATE-CCYYMMDD` and `'YYYYMMDD'` (see Q-CSUTLDTC-03).
- The program keeps no state between calls. `WS-MESSAGE` is re-initialized on every call, and the
  program ends with `EXIT PROGRAM`.

## Processing flow
1. Initialize `WS-MESSAGE`. INITIALIZE leaves the FILLER constants in place and clears the named
   fields. Clear `WS-DATE`.
2. **A000-MAIN**:
   1. Build two variable-length strings for CEEDAYS, each a 2-byte binary length followed by text:
      the date string (length 10, the text of `LS-DATE`) and the picture string (length 10, the text
      of `LS-DATE-FORMAT`). Copy `LS-DATE-FORMAT` into `WS-DATE-FMT`. Set `OUTPUT-LILLIAN` to 0.
   2. CALL CEEDAYS with the date string, the picture string, `OUTPUT-LILLIAN`, and `FEEDBACK-CODE` (12 bytes).
   3. Move the whole date string group, including its 2-byte length, into `WS-DATE` (Q-CSUTLDTC-02).
      Move the feedback severity into `WS-SEVERITY-N` and the feedback message number into `WS-MSG-NO-N`.
   4. Evaluate the first 8 bytes of the feedback code and set the 15-character `WS-RESULT` (BR-CSUTLDTC-03).
3. Move `WS-MESSAGE` to `LS-RESULT` and the severity to `RETURN-CODE`, then EXIT PROGRAM.

## Rules
### Business rules
- **BR-CSUTLDTC-01**: The date is validated by CEEDAYS against the picture string. The valid range for
  CEEDAYS is 15 October 1582 to 31 December 9999. Both strings are always passed with length 10 and
  are never trimmed. Source: `app/cbl/CSUTLDTC.cbl:103-120 (A000-MAIN)`.
- **BR-CSUTLDTC-02**: The layout of `LS-RESULT` (80 bytes; positions are 1-based):

  | Pos | Len | Content |
  |:--|:--|:--|
  | 1-4 | 4 | Severity as 4 digits: `0000` valid, `0003` error |
  | 5-15 | 11 | Constant `Mesg Code:` followed by one space |
  | 16-19 | 4 | Message number as 4 digits: `0000` if valid, else for example `2508` |
  | 20 | 1 | Space |
  | 21-35 | 15 | Result text (BR-CSUTLDTC-03) |
  | 36 | 1 | Space |
  | 37-45 | 9 | Constant `TstDate:` followed by one space |
  | 46-55 | 10 | Bytes 1-10 of the date string group: X'000A' followed by the first 8 characters of the date (Q-CSUTLDTC-02) |
  | 56 | 1 | Space |
  | 57-66 | 10 | Constant `Mask used:` |
  | 67-76 | 10 | `LS-DATE-FORMAT` |
  | 77-80 | 4 | Spaces |

  Callers in this repository read only positions 1-4 (`CSUTLDTC-RESULT-SEV-CD`) and 16-19
  (`CSUTLDTC-RESULT-MSG-NUM`). Source: `app/cbl/CSUTLDTC.cbl:42-57, 97`.
- **BR-CSUTLDTC-03**: Feedback-code mapping. A feedback token is severity (2 bytes), message number
  (2 bytes), X'59' (the case and severity control byte), and `CEE` in EBCDIC. The symbolic message
  names and meanings are from the IBM Language Environment documentation; the program tests only
  the hex values.

  | 88-level name | Token (hex) | Severity / message number | LE message | Meaning | `WS-RESULT` |
  |:--|:--|:--|:--|:--|:--|
  | FC-INVALID-DATE (means **valid**) | 0000000000000000 | 0 / 0 | none | Date is valid | `Date is valid` |
  | FC-INSUFFICIENT-DATA | 000309CB59C3C5C5 | 3 / 2507 | CEE2EB | Not enough data in the date string | `Insufficient` |
  | FC-BAD-DATE-VALUE | 000309CC59C3C5C5 | 3 / 2508 | CEE2EC | Date value not valid, for example 2023-02-29 | `Datevalue error` |
  | FC-INVALID-ERA | 000309CD59C3C5C5 | 3 / 2509 | CEE2ED | Era not recognized | `Invalid Era    ` |
  | FC-UNSUPP-RANGE | 000309D159C3C5C5 | 3 / 2513 | CEE2EH | Date outside 1582-10-15 to 9999-12-31 | `Unsupp. Range  ` |
  | FC-INVALID-MONTH | 000309D559C3C5C5 | 3 / 2517 | CEE2EL | Month not recognized | `Invalid month  ` |
  | FC-BAD-PIC-STRING | 000309D659C3C5C5 | 3 / 2518 | CEE2EM | Picture string not valid | `Bad Pic String ` |
  | FC-NON-NUMERIC-DATA | 000309D859C3C5C5 | 3 / 2520 | CEE2EO | Non-numeric data, or the date does not match the picture | `Nonnumeric data` |
  | FC-YEAR-IN-ERA-ZERO | 000309D959C3C5C5 | 3 / 2521 | CEE2EP | Year in era is zero | `YearInEra is 0 ` |
  | (any other token) | | as returned | | | `Date is invalid` |

  Source: `app/cbl/CSUTLDTC.cbl:60-80, 128-149 (A000-MAIN)`.
- **BR-CSUTLDTC-04**: `RETURN-CODE` is set to the severity: 0 when valid, otherwise the CEEDAYS severity
  (3 for every error listed above). No caller in this repository tests `RETURN-CODE`.
  Source: `app/cbl/CSUTLDTC.cbl:98`.
- **BR-CSUTLDTC-05**: How callers decide validity:
  - COTRN02C and CORPT00C treat a severity of `0000` as valid. They also treat message number `2513`
    (unsupported range) as valid (Q-CSUTLDTC-05). Every other result is invalid.
  - COACTUPC (through CSUTLDPY) treats only severity 0 as valid.

  A replacement service must return enough detail for each caller to apply its own rule.
  Source: `app/cbl/COTRN02C.cbl:397-407, 417-427`; `app/cbl/CORPT00C.cbl:396-406, 416-426`; `app/cpy/CSUTLDPY.cpy:299-301`.

### Validation rules
None of its own. The routine is the date validation used by VR-COTRN02C-22, VR-COTRN02C-23,
VR-CORPT00C-14, and VR-CORPT00C-15.

### Navigation and keys
Not applicable (subroutine).

### Error handling
- **ERR-CSUTLDTC-01**: Because a feedback-code argument is passed, CEEDAYS reports errors in that area
  and does not signal a condition. The routine never abends for a bad date; it returns a non-zero
  severity. Source: `app/cbl/CSUTLDTC.cbl:116-120`.

## Data effects
None. The routine is a pure function of its inputs.

## Quirks
- **Q-CSUTLDTC-01**: The 88-level `FC-INVALID-DATE` matches the all-zero token, which actually means the
  date is **valid**; the routine correctly maps it to "Date is valid". The name is misleading.
  Recommendation: rename in the target system. Source: `app/cbl/CSUTLDTC.cbl:62, 129-130`.
- **Q-CSUTLDTC-02**: Line 122 moves the variable-length group `WS-DATE-TO-TEST` (2-byte binary length
  plus text) into `WS-DATE` X(10). The "TstDate:" part of the result therefore holds X'000A' followed
  by only the first 8 date characters, and the correct value moved at line 107 is overwritten.
  Diagnostic text only. Recommendation: fix or drop. Source: `app/cbl/CSUTLDTC.cbl:107-108, 122`.
- **Q-CSUTLDTC-03**: The routine always passes 10 bytes of date and 10 bytes of picture string. When a
  caller passes a shorter area, the routine reads beyond it. COACTUPC passes `WS-EDIT-DATE-CCYYMMDD`
  (8 characters, `app/cpy/CSUTLDWY.cpy:4`) with the picture `'YYYYMMDD'`, so 2 bytes of adjacent
  storage become part of the date string. Whether CEEDAYS accepts the result depends on those bytes
  and on how CEEDAYS handles the trailing part of the picture. Recommendation: in the target system,
  pass explicit strings; whoever writes the COACTUPC spec should verify this.
  Source: `app/cbl/CSUTLDTC.cbl:84-85, 105-112`.
- **Q-CSUTLDTC-04**: The Lilian day number is computed but never returned. Recommendation: preserve
  (no caller needs it). Source: `app/cbl/CSUTLDTC.cbl:41, 114-120`.
- **Q-CSUTLDTC-05**: Two callers accept `2513` (unsupported range) as valid, so dates before 1582-10-15,
  such as `1500-01-01`, pass their validation. Recommendation: decide (most likely reject such dates).
  Source: see BR-CSUTLDTC-05.

## Test scenarios
In these scenarios `R` is `LS-RESULT` and positions are 1-based.
- **TS-CSUTLDTC-01** (covers BR-CSUTLDTC-01, -02, -03): Given `LS-DATE = '2022-06-15'` and
  `LS-DATE-FORMAT = 'YYYY-MM-DD'`. When the routine is called, then `R(1:4) = '0000'`,
  `R(16:4) = '0000'`, `R(21:15) = 'Date is valid  '`, `R(67:10) = 'YYYY-MM-DD'`, and RETURN-CODE is 0.
- **TS-CSUTLDTC-02** (covers BR-CSUTLDTC-03): Given `'2023-02-29'` (not a leap year) and `'YYYY-MM-DD'`.
  Then `R(1:4) = '0003'`, `R(16:4) = '2508'`, `R(21:15) = 'Datevalue error'`, and RETURN-CODE is 3.
- **TS-CSUTLDTC-03** (covers BR-CSUTLDTC-03): Given `'2024-02-29'` (a leap year). Then `R(1:4) = '0000'`.
- **TS-CSUTLDTC-04** (covers BR-CSUTLDTC-03, -05, Q-CSUTLDTC-05): Given `'1500-01-01'`. Then
  `R(1:4) = '0003'`, `R(16:4) = '2513'`, and `R(21:15) = 'Unsupp. Range  '`. COTRN02C and CORPT00C
  accept this date.
- **TS-CSUTLDTC-05** (covers BR-CSUTLDTC-03): Given `'2022-13-01'`. Then `R(1:4) = '0003'` and
  `R(16:4)` is not `'2513'`, so the callers reject the date.
- **TS-CSUTLDTC-06** (covers BR-CSUTLDTC-03): Given `'2022-06-31'` (June has 30 days). Then the severity
  is `0003` and the message number is not `2513`.

## Open questions
- What is the exact CEEDAYS message number for each malformed input (for example `2022-13-01` gives
  2508 or 2517)? The target system needs only "valid", "out of supported range", and "other invalid".
- Should the target system keep accepting out-of-range dates (2513) in transaction add and reports?
