# AGENTS.md

Guidance for coding agents and new contributors working in CardDemo, an AWS sample
mainframe credit card application (COBOL, CICS, VSAM, JCL, with optional Db2, IMS, and MQ
modules). The mainframe sources are the product. The Python package in `tools/carddemo/`
is local tooling that lints, documents, compiles, and runs them without a mainframe.

`python3 -m tools.carddemo check-docs` (part of `make docs-check` and CI) verifies that
every `make` target, tool subcommand, and repository path named in this file exists.

## Repository layout

| Path | Contents |
|:--|:--|
| `app/cbl`, `app/cpy`, `app/bms`, `app/cpy-bms` | Base application: COBOL programs, copybooks, BMS maps, generated map copybooks |
| `app/jcl`, `app/proc`, `app/ctl`, `app/catlg` | Batch JCL, procedures, control cards, catalog listing |
| `app/data/ASCII`, `app/data/EBCDIC` | Sample datasets (synthetic data) in both encodings |
| `app/app-authorization-ims-db2-mq` | Optional module: pending authorizations (IMS, Db2, MQ) |
| `app/app-transaction-type-db2` | Optional module: transaction type maintenance (Db2) |
| `app/app-vsam-mq` | Optional module: account and date inquiry over MQ |
| `app/scheduler` | CA7 and Control-M schedule definitions |
| `tools/carddemo` | Static analysis, inventory generator, GnuCOBOL runner |
| `tests/unit`, `tests/integration` | pytest suites for the tooling and for the COBOL batch behaviour |
| `docs` | Architecture, data model, rules, runbooks, generated inventory |
| `scripts` | Helpers that upload, compile, and submit on a real mainframe over an FTP tunnel |

Optional modules are self-contained: a program under `app/app-<module>/` may use base
copybooks from `app/cpy`, but base programs must never COPY from a module, and modules
must not COPY from each other (rule CD040).

## Setup

```sh
make setup          # .venv with pinned tools from requirements-dev.txt, plus git hooks
make setup-cobol    # GnuCOBOL 3.x via apt; needed for syntax checks, batch runs, cobc tests
cp .env.example .env   # optional; documents the environment variables the tooling reads
```

Python 3.10+ is required. There is no JDK, Node, or database dependency. The devcontainer in
`.devcontainer/devcontainer.json` installs everything above.

## Commands

| Task | Command |
|:--|:--|
| Everything CI runs | `make check` |
| COBOL/JCL static analysis (new findings only) | `make lint` |
| Every finding, including accepted ones | `make lint-all` |
| Accept current findings into the baseline | `make baseline` |
| Paragraph complexity ranking | `make complexity` |
| Python lint and format check / apply formatting | `make lint-python` / `make format` |
| Type check the tooling | `make typecheck` |
| GnuCOBOL syntax check of batch programs | `make syntax` |
| Tests | `make test` (all), `make test-unit` (no GnuCOBOL needed) |
| Tests with coverage gate and JUnit report | `make coverage` |
| Regenerate `docs/INVENTORY.md` | `make docs` |
| Check generated docs and this file | `make docs-check` |
| Run a batch program locally | `make run-batch PROGRAM=CBTRN02C` |

The same commands are available directly: `python3 -m tools.carddemo lint`,
`python3 -m tools.carddemo complexity`, `python3 -m tools.carddemo inventory`,
`python3 -m tools.carddemo syntax`, `python3 -m tools.carddemo run-batch`, and
`python3 -m tools.carddemo check-docs`.

There is no mainframe build in this repository. Compiling the CICS, Db2, IMS, and MQ
programs requires a z/OS environment; follow the Installation section of `README.md` and the
compile JCL in `samples/jcl`.

## Making changes

- COBOL is fixed format: sequence area in columns 1-6, indicator in column 7, code in
  columns 8-72, nothing past column 80, no tab characters (rules CD001, CD002). Keep the
  existing CRLF or LF line endings of the file you edit.
- Member names are 1-8 characters, upper case, starting with a letter, and must match
  `PROGRAM-ID` (CD010, CD011). Online programs are `CO*C`, batch programs `CB*C`, most shared
  record copybooks end in `Y` (for example `CVACT01Y`), and a BMS map is named after its
  program without the trailing `C` (`COSGN00` for `COSGN00C`).
- Record layouts are contracts with the VSAM files and sample data. If you change a copybook
  that describes a dataset, update the data under `app/data` and the README dataset table;
  `tests/integration/test_data_layouts.py` checks record lengths against all three.
- Debt markers must link a tracking issue (CD060): write `TODO(#123): ...` or `TODO(CARD-42): ...`.
  The same applies to `FIXME(#123)` and `HACK(#123)`.
- Never DISPLAY card numbers, CVV codes, SSNs, or other customer PII (CD070). See
  `docs/data-handling.md`.
- After adding, removing, or renaming programs or copybooks, run `make docs`.
- `make lint` fails only on new findings. Fix them; use `make baseline` only for findings
  you deliberately accept, and explain why in the pull request. Metric rules (complexity,
  file size, duplicate length) cannot grow past the value recorded in
  `tools/carddemo/lint-baseline.json`.
- Rule details and rationale are in `docs/static-analysis.md`.

## Tests

- Files are `test_<subject>.py`, functions are `test_<behaviour>`, named for the behaviour
  checked (for example `test_posting_job_posts_valid_and_rejects_overlimit_transactions`).
- `tests/unit` uses small COBOL snippets built with the `cobol()` helper and the `make_repo`
  fixture from `tests/conftest.py`. Unit tests must not depend on the real `app/` tree.
- `tests/integration` runs against the real sources. Tests that compile or run COBOL carry
  `@pytest.mark.cobc`; they skip when `cobc` is missing unless `CARDDEMO_REQUIRE_COBC=1`
  (set in CI).
- Each test writes only to its `tmp_path`, so the suite runs in parallel with `-n auto`.
- Coverage of `tools/carddemo` must stay at or above 90 percent (`pyproject.toml`).

## Interactive QA

You cannot run the CICS screens without a mainframe, but you can exercise the batch layer:

```sh
make run-batch PROGRAM=CBTRN02C   # POSTTRAN: posts app/data/ASCII/dailytran.txt
```

The run compiles the program, loads the sample files into indexed files under
`.batch-runs/CBTRN02C/`, prints the job output, and lists the output datasets. A good
run ends with RC=4, 300 transactions processed, and 43 rejected with reason
`0102 OVERLIMIT TRANSACTION` (rejects are written to `DALYREJS`). CBACT02C, CBACT03C,
and CBCUS01C list the card, cross-reference, and customer files. Inspect the files in the
work directory to confirm the effect of a change before writing a test for it.

On a real mainframe, sign on with transaction CC00 using `ADMIN001` (admin) or `USER0001`
(user); both use the demo password documented in `README.md`. Operating procedures for the
batch cycle are in `docs/runbooks/batch-operations.md`.

## Further reading

- `docs/architecture.md`: online and batch flows, programs, and data stores
- `docs/data-model.md`: VSAM files, keys, copybooks, and Db2/IMS objects
- `docs/INVENTORY.md`: generated program and copybook inventory
- `.factory/skills/carddemo-cobol-change/SKILL.md`: step-by-step workflow for COBOL changes
- `CONTRIBUTING.md`: pull request process
