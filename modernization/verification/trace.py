#!/usr/bin/env python3
"""Traceability matrix: every owed rule, scenario, and approved fix -> tests that ran and passed.

Owed set (derived from the spec and ledger, not from the tests):
  - every bold BR-, VR-, NAV-, ERR-, TS- ID in spec/programs/*.md and spec/jobs.md;
  - every Q- ID whose ledger decision is FIX or CHANGE.
An owed rule may instead be resolved in verification/trace-exclusions.md by a ledger row with
decision PLATFORM (whose Q- ID must itself be covered by a passing test: the modern equivalent),
NOT-PORTED (dead code; nothing to test), or a C-/B- convention (which must be covered).

Evidence:
  - Java tests: @Covers({...}) on a test method or class in backend/*/src/test/java, joined with
    surefire JUnit XML (backend/*/target/surefire-reports/TEST-*.xml) by class and method;
  - name-tagged JUnit XML (Playwright e2e/test-results/junit.xml and any --junit file): IDs in the
    test title;
  - batch parity results (--batch-results results.json from verify_batch.py): a MATCH pair covers
    the IDs in its "Rules exercised" cell of verification/parity/datasets.md.

Writes verification/traceability.md (and --json) and exits 1 when an owed ID is uncovered, a test
failed, or a tagged test was skipped. Standard library only.
"""
from __future__ import annotations

import argparse
import glob
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
MOD = HERE.parent
SPEC = MOD / "spec"
DEC = MOD / "decisions"
ID_RE = r"(?:BR|VR|NAV|ERR|Q|TS)-[A-Z0-9]+(?:-[A-Z0-9]+)*-\d+"
BOLD = re.compile(r"\*\*(" + ID_RE + r")\*\*")
TOKEN = re.compile(r"(?<![A-Za-z0-9-])(" + ID_RE + r"|[BC]-[A-Z]{3,}(?:-[A-Z]+)*)(?![A-Za-z0-9])")
RANGE = re.compile(r"\b((?:BR|VR|NAV|ERR|TS)-[A-Z0-9]+)-(\d+)\.\.(\d+)\b")
RULE_TYPES = ("BR", "VR", "NAV", "ERR", "TS", "Q")


@dataclass
class Test:
    name: str
    status: str  # passed, failed, skipped, missing
    ids: set[str] = field(default_factory=set)


def owed_ids() -> tuple[dict[str, str], dict[str, str]]:
    """(owed id -> program/source, ledger id -> decision)."""
    owed: dict[str, str] = {}
    for p in sorted((SPEC / "programs").glob("*.md")) + [SPEC / "jobs.md"]:
        for i in BOLD.findall(p.read_text()):
            if not i.startswith("Q-"):
                owed[i] = p.stem if p.parent.name == "programs" else "jobs"
    ledger: dict[str, str] = {}
    for f in ("ledger-online.md", "ledger-batch-data.md"):
        for line in (DEC / f).read_text().splitlines():
            m = re.match(r"^\| (Q-[A-Z0-9-]+) \| .*? \| [a-z-]+ \| (FIX|PRESERVE|CHANGE|PLATFORM|NOT-PORTED)\b", line)
            if m:
                ledger[m.group(1)] = m.group(2)
            m = re.match(r"^\| ([BC]-[A-Z]+) \|", line)
            if m:
                ledger[m.group(1)] = "CONVENTION"
    for q, d in ledger.items():
        if d in ("FIX", "CHANGE"):
            owed[q] = q.split("-")[1]
    return owed, ledger


def java_tags(root: Path) -> dict[tuple[str, str | None], set[str]]:
    """(fully qualified class name with $ for nesting, method or None for class level) -> IDs."""
    tags: dict[tuple[str, str | None], set[str]] = defaultdict(set)
    for path in glob.glob(str(root / "backend" / "*" / "src" / "test" / "java" / "**" / "*.java"), recursive=True):
        src = Path(path).read_text()
        pkg = re.search(r"^\s*package\s+([\w.]+)\s*;", src, re.MULTILINE)
        prefix = pkg.group(1) + "." if pkg else ""
        code = strip_comments_and_strings(src)
        stack: list[tuple[str, int]] = []
        depth = 0
        pending_class: str | None = None
        i = 0
        decls = [(m.start(), m.group(1)) for m in re.finditer(r"\b(?:class|record|enum|interface)\s+(\w+)", code)]
        di = 0
        for i, ch in enumerate(code):
            while di < len(decls) and decls[di][0] <= i:
                pending_class = decls[di][1]
                di += 1
            if ch == "{":
                depth += 1
                if pending_class:
                    stack.append((pending_class, depth))
                    pending_class = None
            elif ch == "}":
                if stack and stack[-1][1] == depth:
                    stack.pop()
                depth -= 1
            elif ch == "@" and code.startswith("@Covers", i):
                close = code.index(")", i)
                ids = set(re.findall(r'"([^"]+)"', src[i:close + 1]))
                rest = code[close + 1:]
                m = re.match(r"(?:\s*@\w+(?:\.\w+)*(?:\([^)]*\))?)*\s*((?:\w+\s+)*?)(class|record|enum|interface)\s+(\w+)", rest)
                cls = "$".join(n for n, _ in stack)
                if m:
                    tags[(prefix + (cls + "$" if cls else "") + m.group(3), None)] |= ids
                    continue
                m = re.match(r"(?:\s*@\w+(?:\.\w+)*(?:\([^)]*\))?)*\s*(?:(?:public|protected|private|static|final|default)\s+)*[\w<>\[\],.? ]+?\s+(\w+)\s*\(", rest)
                if m and cls:
                    tags[(prefix + cls, m.group(1))] |= ids
    return tags


def strip_comments_and_strings(src: str) -> str:
    """Same length as src; comments, string, and char literals blanked so braces can be counted."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
        elif src[i] in "\"'":
            q, j = src[i], i + 1
            while j < n and src[j] != q:
                j += 2 if src[j] == "\\" else 1
            j += 1
        else:
            i += 1
            continue
        for k in range(i, min(j, n)):
            if out[k] != "\n":
                out[k] = " "
        # keep the annotation text intact for @Covers("...") value extraction (taken from src)
        i = j
    return "".join(out)


def junit_cases(paths: list[str]) -> list[tuple[str, str, str]]:
    """(classname, name, status) for every testcase."""
    cases = []
    for p in paths:
        try:
            root = ET.parse(p).getroot()
        except ET.ParseError as e:
            raise SystemExit(f"cannot parse {p}: {e}")
        for tc in root.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                status = "failed"
            elif tc.find("skipped") is not None:
                status = "skipped"
            else:
                status = "passed"
            cases.append((tc.get("classname", ""), tc.get("name", ""), status))
    return cases


def expand(cell: str) -> set[str]:
    ids = set(TOKEN.findall(cell))
    for prefix, a, b in RANGE.findall(cell):
        w = len(a)
        ids |= {f"{prefix}-{k:0{w}d}" for k in range(int(a), int(b) + 1)}
    return ids


def batch_tests(results: Path) -> list[Test]:
    data = json.loads(results.read_text())
    rules: dict[str, set[str]] = {}
    in_table = False
    for line in (HERE / "parity" / "datasets.md").read_text().splitlines():
        if line.startswith("## "):
            in_table = line.strip() == "## Owed pairs"
            continue
        c = [x.strip() for x in line.strip().strip("|").split("|")]
        if in_table and len(c) >= 4 and re.match(r"^[A-Z0-9+]+$", c[0]):
            rules[f"{c[0]}/{c[1]}"] = expand(c[3])
    tests = []
    for tag, ids in rules.items():
        findings = data.get(tag)
        status = "missing" if findings is None else ("passed" if not findings else "failed")
        tests.append(Test(f"batch parity {tag}", status, ids))
    return tests


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--junit", action="append", default=[], help="extra name-tagged JUnit XML files (globs)")
    ap.add_argument("--batch-results", type=Path, help="results.json written by verify_batch.py")
    ap.add_argument("--out", type=Path, default=HERE / "traceability.md")
    ap.add_argument("--json", type=Path)
    a = ap.parse_args(argv)

    owed, ledger = owed_ids()
    tests: list[Test] = []

    tags = java_tags(MOD)
    surefire = glob.glob(str(MOD / "backend" / "*" / "target" / "surefire-reports" / "TEST-*.xml"))
    seen_methods: set[tuple[str, str | None]] = set()
    for cls, name, status in junit_cases(surefire):
        method = re.split(r"[\[(]", name, maxsplit=1)[0]
        ids = tags.get((cls, method), set()) | tags.get((cls, None), set())
        outer = cls.split("$")[0]
        if outer != cls:
            ids |= tags.get((outer, None), set())
        if (cls, method) in tags:
            seen_methods.add((cls, method))
        tests.append(Test(f"{cls}#{name}", status, set(ids)))
    for key, ids in tags.items():
        if key[1] is not None and key not in seen_methods:
            tests.append(Test(f"{key[0]}#{key[1]}", "missing", set(ids)))

    named = [str(MOD / "e2e" / "test-results" / "junit.xml")]
    for g in a.junit:
        named += glob.glob(g)
    for cls, name, status in junit_cases([p for p in named if Path(p).exists()]):
        title = name.split(" › ")[-1]
        tests.append(Test(f"{cls} › {name}", status, set(TOKEN.findall(title))))

    if a.batch_results:
        tests += batch_tests(a.batch_results)

    passing: dict[str, list[str]] = defaultdict(list)
    for t in tests:
        if t.status == "passed":
            for i in t.ids:
                passing[i].append(t.name)

    exclusions: dict[str, tuple[str, str]] = {}
    exf = HERE / "trace-exclusions.md"
    problems: list[str] = []
    if exf.exists():
        for line in exf.read_text().splitlines():
            c = [x.strip() for x in line.strip().strip("|").split("|")]
            if len(c) >= 3 and re.match("^" + ID_RE + "$", c[0]):
                exclusions[c[0]] = (c[1], c[2])

    status: dict[str, str] = {}
    for i in owed:
        if passing.get(i):
            status[i] = "covered"
            continue
        ex = exclusions.get(i)
        if ex:
            row = ex[0]
            d = ledger.get(row)
            if d == "NOT-PORTED":
                status[i] = f"resolved by {row} (NOT-PORTED)"
                continue
            if d in ("PLATFORM", "CONVENTION") and passing.get(row):
                status[i] = f"resolved by {row} ({d}, covered)"
                continue
            if d not in ("PLATFORM", "CONVENTION", "NOT-PORTED"):
                problems.append(f"exclusion for {i} cites {row}, which is not a PLATFORM/NOT-PORTED row or convention")
        status[i] = "uncovered"

    failed = [t for t in tests if t.status == "failed"]
    skipped_tagged = [t for t in tests if t.status == "skipped" and t.ids]
    missing = [t for t in tests if t.status == "missing"]
    skipped_untagged = [t for t in tests if t.status == "skipped" and not t.ids]
    known = set(owed) | set(ledger) | set(exclusions)
    spec_all = set()
    for p in list(SPEC.glob("*.md")) + list((SPEC / "programs").glob("*.md")):
        spec_all |= set(BOLD.findall(p.read_text()))
    unknown = sorted({i for t in tests for i in t.ids if re.match("^" + ID_RE + "$", i) and i not in spec_all and i not in known})

    by_type: dict[str, Counter] = defaultdict(Counter)
    by_prog: dict[str, Counter] = defaultdict(Counter)
    for i, s in status.items():
        k = i.split("-")[0]
        ok = s != "uncovered"
        by_type[k]["owed"] += 1
        by_type[k]["ok"] += ok
        by_prog[owed[i]]["owed"] += 1
        by_prog[owed[i]]["ok"] += ok
    total_ok = sum(1 for s in status.values() if s != "uncovered")

    lines = ["# Traceability matrix", "",
             "Generated by `verification/trace.py`; do not edit by hand.", "",
             f"Owed IDs covered or resolved: **{total_ok}/{len(owed)}**. Tests: {len(tests)} "
             f"({sum(t.status == 'passed' for t in tests)} passed, {len(failed)} failed, "
             f"{sum(t.status == 'skipped' for t in tests)} skipped, {len(missing)} tagged but not run).", "",
             "## By ID type", "", "| Type | Covered | Owed |", "|:--|--:|--:|"]
    lines += [f"| {k} | {by_type[k]['ok']} | {by_type[k]['owed']} |" for k in RULE_TYPES if k in by_type]
    lines += ["", "## By program", "", "| Program | Covered | Owed |", "|:--|--:|--:|"]
    lines += [f"| {p} | {c['ok']} | {c['owed']} |" for p, c in sorted(by_prog.items())]
    unc = sorted(i for i, s in status.items() if s == "uncovered")
    lines += ["", f"## Uncovered ({len(unc)})", ""] + ([", ".join(unc)] if unc else ["None."])
    if failed or skipped_tagged or missing:
        lines += ["", "## Failed, skipped, or not-run tagged tests", ""]
        lines += [f"- {t.status}: {t.name}" for t in failed + skipped_tagged + missing]
    if unknown:
        lines += ["", "## Tags that name no spec or ledger ID", "", ", ".join(unknown)]
    lines += ["", "## Matrix", "", "| ID | Status | Passing tests |", "|:--|:--|:--|"]
    for i in sorted(owed):
        names = passing.get(i, [])
        shown = "; ".join(names[:3]) + (f" (+{len(names) - 3})" if len(names) > 3 else "")
        lines.append(f"| {i} | {status[i]} | {shown} |")
    a.out.write_text("\n".join(lines) + "\n")
    if a.json:
        a.json.write_text(json.dumps({"owed": len(owed), "covered": total_ok, "uncovered": unc,
                                      "failed": [t.name for t in failed],
                                      "skipped_tagged": [t.name for t in skipped_tagged],
                                      "skipped_untagged": [t.name for t in skipped_untagged],
                                      "not_run": [t.name for t in missing], "unknown_tags": unknown,
                                      "problems": problems}, indent=2))

    print(f"owed IDs covered or resolved: {total_ok}/{len(owed)}")
    for k in RULE_TYPES:
        if k in by_type:
            print(f"  {k}: {by_type[k]['ok']}/{by_type[k]['owed']}")
    print(f"tests: {len(tests)}; failed {len(failed)}; skipped tagged {len(skipped_tagged)}; "
          f"skipped untagged {len(skipped_untagged)}; tagged but not run {len(missing)}; unknown tags {len(unknown)}")
    for p in problems:
        print(f"  {p}")
    print(f"wrote {a.out}")
    ok = not unc and not failed and not skipped_tagged and not missing and not problems
    print("OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
