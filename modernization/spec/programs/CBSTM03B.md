# CBSTM03B: File I/O subroutine for the statement program (open, read, keyed read, close)

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/CBSTM03B.CBL` (230 lines) |
| Module | base |
| Kind | Batch subroutine (CALLed, not run as a job step) |
| Transaction ID / Job | Called by CBSTM03A in CREASTMT STEP040 (`app/jcl/CREASTMT.JCL:79-96`); uses the DDs of that step |
| BMS map | none |
| Copybooks | none (record layouts are declared inline as key + opaque data) |
| Calls / XCTL targets | none |
| Data stores | DD `TRNXFILE` (KSDS, 350 bytes, key FD-TRNXS-ID = card 16 + transaction ID 16, offset 0 length 32): Browse (sequential); DD `XREFFILE` (KSDS, 50 bytes, key card number offset 0 length 16): Browse (sequential); DD `CUSTFILE` (KSDS, 500 bytes, key customer ID X(9) offset 0): Read (random); DD `ACCTFILE` (KSDS, 300 bytes, key account ID 9(11) offset 0): Read (random). All opened INPUT only |

## Purpose
CBSTM03B performs all VSAM I/O for the statement program CBSTM03A. The caller passes a parameter block
naming the file (DD name), the operation, and for keyed reads the key; CBSTM03B performs the operation,
copies any record read into the 1000-byte buffer, and returns the 2-character file status. It holds no
business logic.

## Invocation and state
- Interface: `CALL 'CBSTM03B' USING LK-M03B-AREA`, a 1040-byte block:

| Field | Picture | Meaning |
|:--|:--|:--|
| LK-M03B-DD | X(8) | `TRNXFILE`, `XREFFILE`, `CUSTFILE`, or `ACCTFILE` |
| LK-M03B-OPER | X(1) | `O` open, `C` close, `R` sequential read, `K` keyed read, `W` write, `Z` rewrite |
| LK-M03B-RC | X(2) | Out: file status of the selected file |
| LK-M03B-KEY | X(25) | In: key value, left-justified |
| LK-M03B-KEY-LN | S9(4) zoned | In: number of key bytes to use |
| LK-M03B-FLDT | X(1000) | Out: record read (READ ... INTO, space-padded beyond the record length) |

- State between calls: the four file connectors and their status fields (static working storage, so
  files stay open across calls until closed).
- Return code: not set.

## Processing flow
1. EVALUATE LK-M03B-DD: `TRNXFILE` -> 1000-TRNXFILE-PROC; `XREFFILE` -> 2000-XREFFILE-PROC;
   `CUSTFILE` -> 3000-CUSTFILE-PROC; `ACCTFILE` -> 4000-ACCTFILE-PROC; OTHER -> GOBACK with nothing done.
2. In the selected paragraph, the first matching operation runs (`O` OPEN INPUT; `R` READ next INTO
   buffer for TRNXFILE/XREFFILE; `K` move the first KEY-LN bytes of the key to the record key and READ
   INTO buffer for CUSTFILE/ACCTFILE; `C` CLOSE), then x900-EXIT copies that file's status to LK-M03B-RC.
3. GOBACK.

## Rules
### Business rules
- **BR-CBSTM03B-01**: Dispatch by DD name: exactly the four literal names select a file; WHEN OTHER
  returns immediately and leaves LK-M03B-RC unchanged (the caller pre-sets it to zero). Source:
  `app/cbl/CBSTM03B.CBL:116-131 (0000-START, 9999-GOBACK)`.
- **BR-CBSTM03B-02**: TRNXFILE and XREFFILE support `O` (OPEN INPUT), `R` (READ next record INTO
  LK-M03B-FLDT), and `C` (CLOSE). Reads are sequential in key order: card + transaction ID for
  TRNXFILE, card number for XREFFILE. Source: `app/cbl/CBSTM03B.CBL:133-155 (1000-TRNXFILE-PROC)`,
  `app/cbl/CBSTM03B.CBL:157-179 (2000-XREFFILE-PROC)`.
- **BR-CBSTM03B-03**: CUSTFILE and ACCTFILE support `O`, `K`, and `C`. For `K`, the key is
  LK-M03B-KEY(1:LK-M03B-KEY-LN) moved to the record key: X(9) customer ID, or 9(11) account ID (an
  alphanumeric-to-numeric move, digits taken as-is). CBSTM03A passes key lengths 9 and 11. Source:
  `app/cbl/CBSTM03B.CBL:181-204 (3000-CUSTFILE-PROC)`, `app/cbl/CBSTM03B.CBL:206-229 (4000-ACCTFILE-PROC)`.
- **BR-CBSTM03B-04**: After any operation on a selected file (including no operation, see
  Q-CBSTM03B-01), LK-M03B-RC = that file's current 2-character status. The caller interprets it
  (`00` success, `10` end of file, `23` not found). Source: `app/cbl/CBSTM03B.CBL:151-152`,
  `app/cbl/CBSTM03B.CBL:175-176`, `app/cbl/CBSTM03B.CBL:200-201`, `app/cbl/CBSTM03B.CBL:225-226`.
- **BR-CBSTM03B-05**: READ ... INTO moves the record to the 1000-byte buffer as an alphanumeric move,
  so bytes beyond the record length (350, 50, 500, 300) are spaces. On an unsuccessful read the buffer
  keeps what the caller put there (CBSTM03A fills it with spaces first). Source:
  `app/cbl/CBSTM03B.CBL:141`, `app/cbl/CBSTM03B.CBL:165`, `app/cbl/CBSTM03B.CBL:190`, `app/cbl/CBSTM03B.CBL:215`.

### Validation rules
None. The operation code and key length are not checked.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-CBSTM03B-01**: No error is raised or displayed. Every failure (open, read, not found, end of
  file, close) is returned only as the file status in LK-M03B-RC; handling is the caller's job (see
  ERR-CBSTM03A-01 to ERR-CBSTM03A-07). Source: `app/cbl/CBSTM03B.CBL:133-229`.

## Data effects
Read-only. Opens all four files INPUT. No writes, no rewrites.

## Quirks
- **Q-CBSTM03B-01**: Unsupported operations do nothing but still return the file's last status: `W` and
  `Z` have 88-levels but no code; `R` on CUSTFILE/ACCTFILE and `K` on TRNXFILE/XREFFILE fall through the
  IFs to x900-EXIT. A caller would see the status of the previous operation (often `00`) and believe the
  call succeeded. Recommendation: fix (reject unsupported operations explicitly). Source:
  `app/cbl/CBSTM03B.CBL:103-108`, `app/cbl/CBSTM03B.CBL:135-152`.
- **Q-CBSTM03B-02**: An unknown DD name returns without touching LK-M03B-RC, so the caller's preset value
  (zero) looks like success. Recommendation: fix. Source: `app/cbl/CBSTM03B.CBL:127-128`.
- **Q-CBSTM03B-03**: LK-M03B-KEY-LN is not range-checked; 0, a negative value, or more than 25 gives an
  invalid reference modification (undefined on z/OS without SSRANGE). Recommendation: fix (in Java the
  typed repository method removes the parameter). Source: `app/cbl/CBSTM03B.CBL:189`, `app/cbl/CBSTM03B.CBL:214`.
- **Q-CBSTM03B-04**: The subroutine exists only to demonstrate a CALL interface; in a Java port it maps
  to four read-only repositories (sequential iterators for TRNXFILE and XREFFILE, find-by-id for
  customers and accounts). Recommendation: decide (do not port the parameter-block protocol).
  Source: `app/cbl/CBSTM03B.CBL:25-27`.

## Test scenarios
Tests drive the subroutine directly with a parameter block (the sample CREASTMT run exercises every
supported path: 312 TRNXFILE reads plus end of file, 50 XREFFILE reads plus end of file, 50 customer and
50 account keyed reads, 4 opens, 4 closes, all `00`).

- **TS-CBSTM03B-01** (covers BR-CBSTM03B-02, BR-CBSTM03B-04, BR-CBSTM03B-05): Given the sample xref KSDS, when
  the caller sends `XREFFILE`/`O`, then `XREFFILE`/`R` 51 times, then `XREFFILE`/`C`, then the statuses are
  `00`, fifty `00` with buffers whose first 50 bytes are the records in card order (first card
  0500****5740) and remaining 950 bytes spaces, then `10`, then `00`.
- **TS-CBSTM03B-02** (covers BR-CBSTM03B-03): Given CUSTFILE is open, when the caller sends `CUSTFILE`/`K`
  with key `000000050` and key length 9, then RC is `00` and the buffer starts with customer `000000050`;
  with key `999999999`, RC is `23`.
- **TS-CBSTM03B-03** (covers BR-CBSTM03B-03): Given ACCTFILE is open, when `ACCTFILE`/`K` is sent with key
  `00000000050` and length 11, then RC is `00` and the buffer holds account 50.
- **TS-CBSTM03B-04** (covers BR-CBSTM03B-01, Q-CBSTM03B-02): Given RC preset to `00`, when DD `FOOFILE` is
  sent, then RC is still `00` and no file is touched.
- **TS-CBSTM03B-05** (covers Q-CBSTM03B-01): Given TRNXFILE was opened with status `00`, when `TRNXFILE`/`W`
  is sent, then nothing is written and RC is `00` (current behaviour; a fixed implementation reports an
  unsupported operation).
- **TS-CBSTM03B-06** (covers ERR-CBSTM03B-01): Given the CUSTFILE DD points to a missing dataset, when
  `CUSTFILE`/`O` is sent, then RC is `35` and nothing is displayed.

## Open questions
- None specific to this subroutine; its behaviour is fully determined by the caller's use (see `CBSTM03A.md`).
