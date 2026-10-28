# COBSWAIT: Batch utility that pauses the job step for a number of centiseconds read from SYSIN

| Attribute | Value |
|:--|:--|
| Source | `app/cbl/COBSWAIT.cbl` (41 lines); assembler helper `app/asm/MVSWAIT.asm` (30 lines) with macro `app/maclib/ASMWAIT.mac` |
| Module | base |
| Kind | Batch |
| Transaction ID / Job | JOB-WAITSTEP, step WAIT (`app/jcl/WAITSTEP.jcl:22-27`) |
| BMS map | none |
| Copybooks | none |
| Calls / XCTL targets | static CALL `MVSWAIT` (assembler; issues `STIMER WAIT,BINTVL=`) |
| Data stores | SYSIN (in-stream card, first 8 bytes used); no files |

## Purpose
COBSWAIT makes a job step wait for a fixed time. It reads the wait interval, in hundredths of a second,
from the first 8 characters of SYSIN and asks the operating system to suspend the step for that interval
through the assembler routine MVSWAIT. It is used in the schedule to delay a job (for example to let
CICS file closes or other jobs complete). It reads and writes no data.

## Invocation and state
- JCL: `//WAIT EXEC PGM=COBSWAIT` with `SYSIN DD *` containing `00003600      VALUE IN CENTISECONDS`
  (`app/jcl/WAITSTEP.jcl:25-26`). Only columns 1-8 (`00003600`) are used; the rest of the card is a comment.
- The interval is in centiseconds (1/100 s): `00003600` = 3600 centiseconds = 36 seconds.
- No PARM is used despite the JCL comment calling it a parm.
- Return code: the program does not set RETURN-CODE. MVSWAIT clears register 15 before returning, so the
  RETURN-CODE special register is 0 after the CALL and STOP RUN ends the step with RC 0.

## Processing flow
1. ACCEPT PARM-VALUE (PIC X(8)) FROM SYSIN: the first 8 bytes of the first SYSIN record.
2. MOVE PARM-VALUE to MVSWAIT-TIME (PIC 9(8) COMP, a 4-byte unsigned binary fullword). The 8 characters
   are interpreted as an unsigned 8-digit decimal number.
3. CALL `MVSWAIT` USING MVSWAIT-TIME (by reference). MVSWAIT (`app/asm/MVSWAIT.asm:17-30`):
   1. Save the caller's registers.
   2. Load the address of the parameter from the parameter list and copy the fullword value into BINLBL.
   3. Issue `STIMER WAIT,BINTVL=BINLBL` (via macro ASMWAIT): the task waits for BINLBL x 0.01 seconds.
   4. Restore registers, set the "returned" flag X'FF' in byte 12 of the caller's save area, set R15 = 0,
      return.
4. STOP RUN.

## Rules
### Business rules
- **BR-COBSWAIT-01**: The wait interval is taken from SYSIN columns 1-8, read as an unsigned decimal
  integer in centiseconds. Source: `app/cbl/COBSWAIT.cbl:36-37 (PROCEDURE DIVISION)`.
- **BR-COBSWAIT-02**: The step is suspended for exactly that many hundredths of a second
  (`STIMER WAIT,BINTVL=`), then ends normally with RC 0. Source: `app/cbl/COBSWAIT.cbl:38-40`,
  `app/asm/MVSWAIT.asm:20-27`, `app/maclib/ASMWAIT.mac:17-20`.
- **BR-COBSWAIT-03**: The maximum representable interval is 99,999,999 centiseconds (about 11.6 days)
  because the field is PIC 9(8). Source: `app/cbl/COBSWAIT.cbl:30-31`.

### Validation rules
- **VR-COBSWAIT-01**: There is no validation of the SYSIN value. Non-numeric characters, a short value,
  or a missing SYSIN record are not detected by the program (see Q-COBSWAIT-01). Source: `app/cbl/COBSWAIT.cbl:36-37`.

### Navigation and keys
Not applicable (batch).

### Error handling
- **ERR-COBSWAIT-01**: No error handling exists. MVSWAIT always returns R15 = 0; a failure of STIMER
  itself would abend the task with a system code. Source: `app/asm/MVSWAIT.asm:23-27`.

## Data effects
None.

## Quirks
- **Q-COBSWAIT-01**: MOVE of an alphanumeric field to a binary numeric field performs no numeric check.
  Spaces or letters in columns 1-8 give an unpredictable interval or a data exception (S0C7) on z/OS,
  depending on the compiler's conversion code; an empty SYSIN gives an implementation-defined value.
  Recommendation: fix (validate a positive integer and fail with a clear message).
  Source: `app/cbl/COBSWAIT.cbl:36-37`.
- **Q-COBSWAIT-02**: The JCL comment and program banner call the value a "PARM", but it is read from SYSIN,
  not the EXEC PARM. Recommendation: decide (accept a job parameter in the target).
  Source: `app/cbl/COBSWAIT.cbl:5`, `app/jcl/WAITSTEP.jcl:20`.
- **Q-COBSWAIT-03**: `MVI 12(13),X'FF'` in MVSWAIT carries a developer comment asking whether it can be
  removed. It is the standard OS linkage "return flag" in the caller's save area and has no business
  effect. Recommendation: not applicable to the target (no assembler). Source: `app/asm/MVSWAIT.asm:25`.
- **Q-COBSWAIT-04**: The program depends on an assembler routine and the MVS STIMER service, so it cannot
  run under GnuCOBOL without a replacement `MVSWAIT` (for example a C stub calling `nanosleep`).
  Recommendation: replace with a scheduler delay or a sleep in the target. Source: `app/cbl/COBSWAIT.cbl:38`.
- **Q-COBSWAIT-05**: A fixed sleep is a timing-based synchronization between jobs and is inherently racy.
  Recommendation: decide (replace with an explicit dependency or resource check in the scheduler).
  Source: `app/jcl/WAITSTEP.jcl:19-27`.

## Test scenarios
- **TS-COBSWAIT-01** (covers BR-COBSWAIT-01, BR-COBSWAIT-02): Given SYSIN `00003600      VALUE IN CENTISECONDS`,
  when WAITSTEP runs, then the step elapsed time is about 36 seconds and RC is 0.
- **TS-COBSWAIT-02** (covers BR-COBSWAIT-01): Given SYSIN `00000000`, when the program runs, then it ends
  immediately with RC 0.
- **TS-COBSWAIT-03** (covers BR-COBSWAIT-01): Given SYSIN `00000150`, when the program runs, then it waits
  1.5 seconds.
- **TS-COBSWAIT-04** (covers VR-COBSWAIT-01, Q-COBSWAIT-01): Given SYSIN `ABC     `, when the program runs,
  then the behavior is undefined in the source (record the observed result on the target platform; the
  target should reject the value).

## Open questions
- WAITSTEP is scheduled in Control-M folders DAILY-TransactionBackup, WEEKLY-DisclosureGroupsRefresh, and
  MONTHLY-InterestCalculation (`app/scheduler/CardDemo.controlm:14`, `app/scheduler/CardDemo.controlm:44`,
  `app/scheduler/CardDemo.controlm:81`) and in the CA7 schedule (`app/scheduler/CardDemo.ca7:97`). Which
  condition is the 36-second wait really standing in for (for example CICS file close completion)?
- What should the target do with a non-numeric or missing interval (fail, or default)?
