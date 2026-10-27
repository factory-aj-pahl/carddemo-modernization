"""Command-line entry point: python3 -m tools.carddemo <command>."""

from __future__ import annotations

import argparse
import sys
import tempfile
from collections import Counter
from pathlib import Path

from . import agentsmd, batch, inventory, procedure
from .findings import apply_baseline, load_baseline, write_baseline
from .rules import run_all
from .source import REPO_ROOT, Repository


def cmd_lint(args: argparse.Namespace) -> int:
    repo = Repository()
    findings = run_all(repo)
    if args.rule:
        findings = [f for f in findings if f.rule in set(args.rule)]
    if args.update_baseline:
        write_baseline(findings)
        print(f"Recorded {len(findings)} findings in the baseline.")
        return 0
    result = apply_baseline(findings, [] if args.no_baseline else load_baseline())
    shown = findings if args.all else result.new
    for f in shown:
        print(f.format())
    counts = Counter(f.rule for f in result.new)
    summary = ", ".join(f"{rule}: {n}" for rule, n in sorted(counts.items())) or "none"
    print(f"\n{len(result.new)} new finding(s) ({summary}); {len(result.known)} known in baseline.")
    if result.stale:
        print(
            f"{len(result.stale)} baseline entr{'y is' if len(result.stale) == 1 else 'ies are'} fixed; "
            "run `make baseline` to drop them."
        )
    return 1 if result.new else 0


def cmd_complexity(args: argparse.Namespace) -> int:
    repo = Repository()
    rows = {}
    for f in repo.programs:
        model = procedure.build(repo, f)
        for p in model.paragraphs if model else []:
            rows[(p.origin, p.name)] = p.complexity
    ranked = sorted(rows.items(), key=lambda kv: (-kv[1], kv[0]))[: args.top]
    width = max((len(o) + len(n) for (o, n), _ in ranked), default=10) + 1
    for (origin, name), value in ranked:
        print(f"{value:5d}  {(origin + ' ' + name).ljust(width)}")
    return 0


def cmd_inventory(args: argparse.Namespace) -> int:
    repo = Repository()
    if args.check:
        if inventory.is_current(repo):
            print("docs/INVENTORY.md is up to date.")
            return 0
        print("docs/INVENTORY.md is out of date; run `make docs`.", file=sys.stderr)
        return 1
    inventory.write(repo)
    print(f"Wrote {inventory.INVENTORY_PATH.relative_to(REPO_ROOT)}")
    return 0


def cmd_syntax(args: argparse.Namespace) -> int:
    try:
        cobc = batch.find_cobc()
    except batch.CobcNotFoundError as exc:
        print(exc, file=sys.stderr)
        return 1 if args.require_cobc else 0
    repo = Repository()
    failures = 0
    for source in batch.syntax_candidates(repo):
        proc = batch.syntax_check(repo, source, cobc)
        status = "ok" if proc.returncode == 0 else "FAILED"
        print(f"{status:6} {source.rel}")
        if proc.returncode != 0:
            failures += 1
            print(proc.stdout)
    print(f"\n{failures} program(s) failed the GnuCOBOL syntax check.")
    return 1 if failures else 0


def cmd_run_batch(args: argparse.Namespace) -> int:
    workdir = Path(args.workdir) if args.workdir else Path(tempfile.mkdtemp(prefix="carddemo-"))
    try:
        result = batch.run_job(args.program, workdir.resolve())
    except (KeyError, batch.CobcNotFoundError, RuntimeError) as exc:
        print(exc, file=sys.stderr)
        return 2
    print(result.output)
    print(f"--- {result.job.program} ({result.job.description}) ended with RC={result.returncode}")
    for dd, path in result.files.items():
        print(f"    {dd:9} {path}  ({path.stat().st_size} bytes)")
    return 0 if result.returncode in (0, 4) else result.returncode


def cmd_check_docs(args: argparse.Namespace) -> int:
    problems = agentsmd.validate(set(COMMANDS))
    for problem in problems:
        print(f"AGENTS.md: {problem}", file=sys.stderr)
    if not problems:
        print("AGENTS.md references are valid.")
    return 1 if problems else 0


COMMANDS = {
    "lint": cmd_lint,
    "complexity": cmd_complexity,
    "inventory": cmd_inventory,
    "syntax": cmd_syntax,
    "run-batch": cmd_run_batch,
    "check-docs": cmd_check_docs,
}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python3 -m tools.carddemo", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    lint = sub.add_parser("lint", help="run the static analysis rules against the baseline")
    lint.add_argument("--all", action="store_true", help="also print findings already in the baseline")
    lint.add_argument("--no-baseline", action="store_true", help="treat every finding as new")
    lint.add_argument("--update-baseline", action="store_true", help="record current findings as accepted")
    lint.add_argument("--rule", action="append", help="only report this rule ID (repeatable)")

    complexity = sub.add_parser("complexity", help="rank paragraphs by cyclomatic complexity")
    complexity.add_argument("--top", type=int, default=20)

    inv = sub.add_parser("inventory", help="regenerate docs/INVENTORY.md")
    inv.add_argument("--check", action="store_true", help="fail if docs/INVENTORY.md is out of date")

    syntax = sub.add_parser("syntax", help="GnuCOBOL syntax check of the batch programs")
    syntax.add_argument("--require-cobc", action="store_true", help="fail when cobc is not installed")

    run = sub.add_parser("run-batch", help="compile a batch program and run it on the sample data")
    run.add_argument("program", choices=sorted(batch.JOBS), help="program to run")
    run.add_argument("--workdir", help="directory for datasets and outputs (default: a new temp dir)")

    sub.add_parser("check-docs", help="verify commands and paths referenced in AGENTS.md")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return COMMANDS[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
