---
name: carddemo-cobol-change
description: Make and verify a change to CardDemo COBOL programs, copybooks, or JCL. Use when editing anything under app/, adding a program or copybook, or debugging a batch job.
---

# Changing CardDemo COBOL, copybooks, or JCL

## 1. Orient

- Find the program in `docs/INVENTORY.md` (module, copybooks, calls, complexity) and its flow in
  `docs/architecture.md`. Record layouts and keys are in `docs/data-model.md`.
- Check which module owns the file. Base code (`app/cbl`, `app/cpy`) must not COPY from
  `app/app-*` modules.
- For CICS programs, read the COMMAREA in `app/cpy/COCOM01Y.cpy` and the BMS map copybook in
  `app/cpy-bms` before changing screen logic.

## 2. Edit safely

- Fixed format: code in columns 8-72, comments with `*` in column 7, no tabs, no line past
  column 80. Keep the file's existing line endings.
- Changing a copybook changes every program that copies it. List them with
  `rg -l "COPY <NAME>" app` and re-check each one.
- Changing a record copybook that describes a VSAM file also means updating the sample data and
  the README dataset table; record length must stay consistent.
- Never DISPLAY PII (card numbers, CVV, SSN, DOB, passwords). Debt markers need an issue:
  `TODO(#123)`.

## 3. Verify

Run, in order, and fix anything that fails:

```sh
make lint          # new static-analysis findings only
make syntax        # GnuCOBOL syntax check for batch programs without CICS/SQL/DLI
make test          # unit + integration, including batch job behaviour
make docs          # if programs or copybooks were added, removed, or changed
make docs-check
```

For batch programs that the local runner supports (CBACT02C, CBACT03C, CBCUS01C, CBTRN02C), run
`make run-batch PROGRAM=<name>` and inspect the outputs under `.batch-runs/<name>/`. If the
change alters behaviour, add or update a test in `tests/integration/test_batch_jobs.py` that
asserts the new output.

CICS, Db2, IMS, and MQ programs cannot be compiled or run locally. For them, rely on `make lint`,
review the EXEC blocks by hand, and state in the pull request that mainframe testing is needed.

## 4. Hand off

- If you accepted a lint finding with `make baseline`, say why in the pull request.
- Fill in the pull request template, including which checks ran and which need a mainframe.
