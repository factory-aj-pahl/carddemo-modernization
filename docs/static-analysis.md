# Static analysis rules

`make lint` (`python3 -m tools.carddemo lint`) runs these rules over the mainframe sources in
`app/` and, for repository-wide rules, every file git would commit. CI and the pre-commit hook
run the same command.

## Baseline

The sources predate the rules, so existing findings are recorded in
`tools/carddemo/lint-baseline.json`. `make lint` fails only on findings that are not in the
baseline. Each entry is keyed by rule, file, and a stable key (paragraph name, copybook name,
or a hash of the line), not by line number, so unrelated edits do not invalidate it.

Metric rules (CD020, CD021, CD050) also record the measured value. A baselined paragraph may
get simpler, but if its complexity grows past the recorded value it is reported as new.

- `make lint-all` lists every finding, including baselined ones.
- `make baseline` rewrites the baseline from the current findings. Use it only to accept a
  finding deliberately, and say why in the pull request. When you fix a baselined finding,
  `make lint` prints that the entry is stale; run `make baseline` to drop it.

## Rules

| Rule | Checks | Threshold | Why |
|:--|:--|:--|:--|
| CD001 | Tab characters in COBOL, copybook, BMS, or JCL source | none allowed | Mainframe editors and compilers treat tabs inconsistently; they corrupt column positions |
| CD002 | Records wider than 80 columns | 80 | PDS libraries in this repo are FB 80; longer lines are truncated on upload |
| CD010 | Member name is not a valid PDS name | 1-8 chars, `A-Z0-9#$@`, starting with a letter or national char | File names become PDS member names |
| CD011 | `PROGRAM-ID` differs from the member name | must match | CICS and the linkage editor resolve programs by member name |
| CD020 | Paragraph cyclomatic complexity (McCabe, counting IF, WHEN, UNTIL, AND, OR) | 25 | Hot spots are hard to test and to translate during modernization |
| CD021 | Source file length | 5000 lines | Very large members should be split into called programs or copybooks |
| CD022 | Any committed file larger than 1 MiB | 1 MiB | Keeps binaries and data dumps out of git |
| CD030 | Paragraph unreachable from the entry point through PERFORM, PERFORM THRU, GO TO, or fall-through | none allowed | Dead code misleads readers and conversion tools |
| CD031 | Copybook not used by any program or copybook | none allowed | Unused layouts drift from the data they describe |
| CD032 | COPY or EXEC SQL INCLUDE that resolves to no copybook in the repository (system copybooks such as DFH*, CMQ*, SQLCA are exempt) | none allowed | The program cannot be compiled from this repository |
| CD040 | Module boundary: base code copying from an optional module, or one module copying from another | none allowed | Optional modules must be removable without breaking the base application |
| CD050 | Duplicated code: at least 30 consecutive normalized code lines repeated | 30 lines | Copy-paste logic should be a shared paragraph, copybook, or called program |
| CD060 | Debt marker without an issue link; write `TODO(#123)`, `FIXME(#123)`, or `HACK(CARD-42)` | none allowed | Untracked debt is never paid down |
| CD070 | DISPLAY of a PII field (SSN, card number, CVV, password, date of birth, FICO score) or a record containing one | none allowed | SYSOUT is retained and widely readable; see `docs/data-handling.md` |

Complexity rankings for the whole code base are available with `make complexity`.

## Python tooling

The Python code in `tools/` and `tests/` is checked with ruff (lint and format) and mypy in
strict mode: `make lint-python` and `make typecheck`. Configuration is in `pyproject.toml`.
