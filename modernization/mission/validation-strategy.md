# Validation strategy

## Where truth comes from and where the risk sits

The behavioral source of truth is the COBOL in `app/` at commit a3e87a4 (`app-unchanged`), as
adjusted by the fidelity ledger (`decisions`). Nothing else may define correctness: the spec is a
restatement for implementers and test authors, and the Java and React code never defines its own
expected results.

The dominant risks follow from what can and cannot be executed here:

1. **Batch logic** can be executed in its original form under GnuCOBOL. Risk is low if the
   comparison is exact and the inputs exercise every rule, so the evidence is differential.
2. **Online logic** (CICS, BMS, Db2, IMS, MQ) cannot be executed. Its expected behavior comes only
   from reading the COBOL, through the spec. The largest single risk in the mission is therefore
   **a spec that is wrong or incomplete**: tests derived from a wrong spec would pass on a wrong
   system. That risk gets an independent, source-level audit (`spec-fidelity-review`) in addition
   to mechanical completeness checks (`spec-completeness`).
3. **Deliberate deviations** (fixes, scoping, MQ broker, schedule) could silently spread beyond
   what was approved, or approved fixes could be forgotten. The ledger makes every quirk an explicit
   decision (`ledger-complete`), and each FIX/CHANGE row is owed a test (`rule-traceability`).
4. **New requirements** with no COBOL counterpart (BCrypt sessions, roles, user-account scoping,
   masking) are judged against the promise and decisions (`security-matrix`).

## Evidence routes per promised property

| Property | Evidence | Why it is sufficient |
|:--|:--|:--|
| P-SPEC | `spec-completeness`, `spec-fidelity-review` | Completeness derives its owed sets from the COBOL (inventory, copybooks, JCL members, message literals), not from the spec, so omissions show up as missing items. The review audits every decision branch of the 14 programs that carry the highest risk (money movement, credentials, multi-record updates, the authorization engine, report/statement formatting) and samples at least 25% of the rules of every other program, covering every rule type. |
| P-ARCH | `module-boundaries`, delivery review | The architecture's main structural claim (isolated contexts, extractable modules) is tested mechanically; the delivery review compares the code with `architecture.md`. |
| P-UI | `ui-look-approved`, `ui-accessibility`, `ui-review`, browser tests within `rule-traceability` | The user approves the look before screens are built; behavior (fields, messages, keys) is covered by tagged browser tests; a human-style visual review checks every route and state, because automated checks do not catch layout defects. |
| P-API | `rule-traceability`, `test-quality-review`, `exploratory-review` | Every BR/VR/NAV/ERR/TS ID is owed a passing tagged test; the quality review stops tags from standing in for real assertions; exploration catches behavior that falls between fixed scenarios. |
| P-MODULES | `module-boundaries` | The architecture test enforces dependency rules for every context; the standalone bill-payment service shows a context actually runs alone, which is what selective conversion means here. |
| P-DATA | `data-roundtrip` | The byte-for-byte round trip catches decoding errors (signs, packed fields, padding) that row counts alone would miss; spot-value assertions guard against an encoder and decoder that are wrong in the same way. |
| P-BATCH | `reference-harness-qualified`, `dataset-coverage-review`, `batch-parity` | The reference is real COBOL. The harness check confirms that sign semantics are actually in effect (38 rejects with them, 43 without), that patches never touch `app/`, and that the comparator fails on injected differences. The dataset review makes sure every batch rule is exercised, so parity is not limited to the sample data's happy paths. |
| P-SCHED | `schedule-cycle` | A daily run and a month-start run (with interest) are each compared with the reference chain's end state, which also covers the ordering between jobs and the replacement of the COMBTRAN merge by direct inserts; report DAG runs are compared with the reference report, both unfiltered and scoped. |
| P-MQ | `mq-roundtrip` | Every spec scenario of the three MQ programs runs over both transports, with correlation and poison-message handling checked explicitly. |
| P-SEC | `security-matrix`, `exploratory-review` | Endpoints are enumerated from the generated OpenAPI document at test time, so a new endpoint cannot escape the matrix; response scans catch secret and PII leaks. |
| P-RUN | `run-from-scratch`, `app-unchanged` | A clean rebuild following only the README proves the documented path works; `make check` proves the old tooling is unaffected. |

## Supporting checks and the evidence that relies on them

- `app-unchanged` underpins every check that reads the COBOL or builds the reference.
- `reference-harness-qualified` underpins `batch-parity` and `schedule-cycle`. Without it, a
  comparator that silently passes or a reference compiled with ASCII signs would hide defects.
- `dataset-coverage-review` underpins the breadth claim of `batch-parity`.
- `spec-fidelity-review` and `ledger-complete` underpin `rule-traceability`, because its owed set and
  expected outcomes come from the spec and the ledger.
- `test-quality-review` underpins `rule-traceability`'s claim that a tagged test really verifies its rule.

## Tolerances and owed sets

- **Batch comparison is exact.** The only differences allowed are those produced by the ledger's
  reference patches, and those differences come from patched COBOL, not from hand-written
  expectations. Business dates (for example the PARM dates that interest transaction ids depend on)
  are passed as the same job parameters on both sides. Run-time clock values are normalized, and only
  those listed in the parity README's "Comparator normalizations" table (TRAN-PROC-TS in
  `transact.txt`, the export timestamp in `expfile.dat`, the CBIMPORT error timestamp in `errout.txt`,
  and the export/import date and time SYSOUT lines); each must still be well formed on both sides.
- **Data round trip:** only line endings (LF) and right-padding to the record length are
  normalized, because the sample files mix CRLF/LF and trim trailing filler (`spec/entities.md`).
- **Owed sets are fixed before evaluation and come from independent sources:** the 44 programs come
  from `docs/INVENTORY.md`; jobs from the `app/jcl` member list; rules and scenarios from the spec's
  defined IDs; endpoints from the generated OpenAPI document; routes from the 21 BMS maps; batch
  pairs from `datasets.md`.

## What is sampled, and what that supports

- `spec-fidelity-review` samples at least 25% of the rules of lower-risk programs. A clean sample
  supports, but does not prove, the accuracy of the unsampled rules. Defects found in a sample
  trigger a full audit of that program.
- `test-quality-review` samples at least 10% of tagged tests per context (all of them for the four
  money- and decision-critical programs).
- `exploratory-review` samples the input space. Findings become regression tests; the fact that those
  tests pass then shows the specific fixes, not that the system generalizes to unseen inputs.
- The spec and its TS scenarios are visible to the implementers, so passing them shows conformance
  to the known scenarios. Independent exploration and the source-level review are the guard against
  an implementation tuned only to those scenarios.

## Deliberately not checked

- Behavior on a real mainframe (CICS timing, terminal UCTRAN, actual abend codes): no mainframe is
  available; the spec labels these "inferred".
- Production performance, load, and failover: non-goals. A basic sanity check is implicit in the
  batch and e2e runs at sample scale.
- The abend paths of batch programs that call CEE3ABD, and the programs GnuCOBOL cannot run
  (CBACT01C, COBSWAIT, every CICS/Db2/IMS/MQ program). They are covered only by spec-derived tests
  under `rule-traceability`; this is a weaker, single-source evidence route, accepted because no
  executable reference exists.
- Pixel-level similarity to the prototype: `ui-review` judges consistency of style, not pixels.

## Late-stage evidence (after the first final re-verify)

- `ui-review` ran in three rounds with fresh independent reviewers (5cfc595, 0a3a3fe, b7aef92) and
  a scoped confirmation at c94a797. Each round reviewed the fixes and screened all captured screens
  for regressions, because fixes to shared components (focus, Enter handling, field marking) can
  break screens they were not aimed at. The round-2 and round-3 reviewers also probed keyboard
  behaviour on all 21 screens in the browser, which the screenshots alone cannot show.
- `exploratory-review` findings EXP-1..EXP-3 were fixed with regression tests, and a second
  independent reviewer confirmed them with fresh probes. That reviewer found EXP-4, which was fixed
  with a REST/queue parity test. The passing regression tests show these specific fixes; they do not
  show that the system generalizes to unseen inputs (see the sampling section above).
- After these fixes the full suite was re-run on the merged tree (`evidence/final-verify-2`), and
  every check whose subject or direct inputs changed was re-recorded against the final tree.
  `test-quality-review` is re-established by an independent review of every test added or changed
  since its earlier evaluation. The earlier sample remains valid for unchanged tests.
  `dataset-coverage-review` stays current: its subject (the harness datasets and generator) and the
  spec are unchanged; the only harness changes since then are trace.py's failsafe-report support and
  the regenerated traceability.md, which do not affect batch datasets.
- An earlier delivery candidate was 8e65a3c (full re-verify at 77358f1, `evidence/final-verify-3`). The
  independent delivery review that followed (DR-1..DR-5, `evidence/delivery-review/`) reopened the
  work, so that candidate and its evaluations are superseded. They remain in the history only.

## Delivery review round

- DR-1 showed that sampled spec audits that found defects had not been escalated. The
  `spec-fidelity-review` procedure now carries the escalation rule: a sampled program with any
  defect, whether found by that review or by later work, is audited in full. The full audits
  (`full-escalation-online-1..5.md`, `full-escalation-batch.md`) and two independent
  resolution re-checks (`resolution-recheck.md`, `resolution-recheck-2.md`) are the evidence. The
  escalation is driven by the defects found, so it widens coverage where the spec proved weak.
  Programs that were sampled and found clean are still judged by the sample.
- DR-2 to DR-4 changed the schedule, backup, and interest-due logic, so `schedule-cycle`,
  `run-from-scratch`, and the cycle verification are re-run on the final commit, not carried over.
- Each batch of late code or test changes received a scoped independent test-quality review of
  every added or changed test (`evidence/test-quality-review/delta-*.md`). The findings were fixed,
  and the earlier sample still covers the unchanged tests.
- A late exploratory review (`evidence/exploratory-review/late-review.md`) probed the areas changed
  in this round with fresh inputs. Its findings X-1 and X-2 were fixed with regression tests. As
  before, those tests show that the specific fixes work, not that the system generalizes.
- `dataset-coverage-review` stays current. In this round its subject (the harness datasets and
  generator) did not change; apart from the regenerated traceability.md, the changes under verification/ are in
  verify_cycle and verify_report only, which do not read or build batch datasets. Of the
  specs in its owed set, only CBEXPORT.md changed, and only in a source citation (8e65a3c..bc41ac9).
  No BR- or ERR- ID or rule text in the owed set changed.
- The final full re-verify ran at 1c05fa2 (`evidence/final-verify-7`, all 12 steps green). The final
  commit 467e0f0 adds only text corrections in 4 spec/decisions files and the regenerated
  traceability.md; spec_check, ledger_check and trace.py were re-run on it. The run-from-scratch
  rehearsal ran at 467e0f0 itself (`evidence/run-from-scratch-4`). Every check whose subject or direct
  inputs changed was re-recorded; `evidence/final-audit.md` lists each remaining identity difference
  and why the evidence still applies (notably `ui-review`: the web tree is unchanged and no spec
  screen section changed; `dataset-coverage-review`: datasets and owed rules unchanged).
- What remains unestablished is unchanged from the sections above: online behavior rests on the
  spec's fidelity to the COBOL (no CICS runtime), abend-only paths rest on spec-derived tests, and
  sampled exploration does not prove behavior on unexplored inputs.
