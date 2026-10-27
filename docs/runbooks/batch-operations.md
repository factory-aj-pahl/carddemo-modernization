# Runbook: daily batch cycle

Audience: whoever operates CardDemo on a mainframe or emulator (for example AWS Mainframe
Modernization). Dataset names assume the HLQ `AWS.M2.CARDDEMO`; schedules for CA7 and
Control-M are in `app/scheduler`. Background on each step is in `docs/architecture.md`.

## Normal run

Submit in this order and check each return code before continuing. RC 0 is clean; RC 4 is
a warning that is expected where noted below; anything higher, or an abend, stops the cycle.

| # | Job | What it does | Expected RC |
|--:|:--|:--|:--|
| 1 | CLOSEFIL | Closes the VSAM files in CICS (CEMT SET FILE CLOSED) so batch has exclusive access | 0 |
| 2 | TRANBKP | Copies TRANSACT.VSAM.KSDS to the TRANSACT.BKUP(+1) GDG, then redefines TRANSACT | 0 |
| 3 | POSTTRAN | CBTRN02C posts DALYTRAN.PS to TRANSACT, ACCTDATA, and TCATBALF | 0, or 4 when any transaction is rejected |
| 4 | INTCALC | CBACT04C computes interest from TCATBALF and DISCGRP, writes SYSTRAN(+1) | 0 |
| 5 | TRANBKP | Backs up TRANSACT again before the combine | 0 |
| 6 | COMBTRAN | Sorts TRANSACT.BKUP(0) and SYSTRAN(0) into TRANSACT.COMBINED(+1) and reloads TRANSACT | 0 |
| 7 | CREASTMT | CBSTM03A writes STATEMNT.PS and STATEMNT.HTML | 0 |
| 8 | TRANIDX | Rebuilds the TRANSACT alternate index | 0 |
| 9 | OPENFIL | Reopens the VSAM files in CICS | 0 |

`scripts/run_full_batch.sh` submits the whole sequence through the FTP tunnel described in
`.env.example`; `scripts/run_posting.sh` and `scripts/run_interest_calc.sh` run single steps.

## Checks after POSTTRAN

- RC 4 means rejects exist. Each reject is the 350-byte transaction followed by an 80-byte
  validation trailer in `DALYREJS(+1)`. Reason codes:

  | Code | Meaning | Action |
  |:--|:--|:--|
  | 100 | Invalid card number (not in CARDXREF) | Check the card was loaded by XREFFILE |
  | 101 | Account record not found | Check ACCTFILE load |
  | 102 | Over credit limit | Business reject; no action unless volumes spike |
  | 103 | Transaction after account expiration | Business reject |

- The job log shows `TRANSACTIONS PROCESSED` and `TRANSACTIONS REJECTED`. With the sample
  data the counts are 300 and 43 (all reason 102). You can reproduce this without a
  mainframe: `make run-batch PROGRAM=CBTRN02C`.

## Failures and recovery

| Symptom | Likely cause | Fix |
|:--|:--|:--|
| File status 93 or 9x on open, or the job waits on enqueue | CICS still has the file open | Run CLOSEFIL, confirm with `CEMT I FILE(*)`, resubmit the failed step |
| ABEND U999 from a CB* program after `ERROR READING` or `ERROR WRITING` | The program calls CEE3ABD after any unexpected file status; the status is in the preceding DISPLAY | Fix the dataset (missing load, wrong record length) and restart from the failed step |
| RC 12 from IDCAMS in TRANBKP or COMBTRAN | Dataset already exists or GDG base missing | Run DEFGDGB once per environment; delete leftover datasets named in the message |
| POSTTRAN posts the same day twice | DALYTRAN.PS was not replaced | Roll back (below), then load the correct DALYTRAN.PS |

### Rolling back a bad POSTTRAN or INTCALC

TRANBKP keeps five generations of the transaction file (GDG limit set by DEFGDGB).

1. Run CLOSEFIL.
2. Restore transactions: IDCAMS `REPRO INDATASET(AWS.M2.CARDDEMO.TRANSACT.BKUP(0))
   OUTDATASET(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS) REPLACE`. Use `BKUP(-1)` if the second
   TRANBKP of the cycle already ran.
3. Restore account and category balances by rerunning ACCTFILE and TCATBALF from the
   last known-good sequential copies (the sample environment reloads from `ACCTDATA.PS` and
   `TCATBALF.PS`).
4. Rerun TRANIDX, then OPENFIL. Resume the cycle at POSTTRAN once the input is corrected.

## Escalation

There is no on-call rotation for this sample. Open a GitHub issue with the bug report
template and attach the job log with PII masked (see `docs/data-handling.md`). Security
problems go to AWS Security as described in `CONTRIBUTING.md`, not to a public issue.
