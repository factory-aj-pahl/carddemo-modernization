"""Comparator for batch parity: expected (reference) output directory vs actual (Java) output directory.

Every file in either directory is compared record by record (LF-terminated lines). A file present
on one side only, a different record count, or any differing byte is a finding. The only
normalizations are the generated-timestamp fields listed in NORMALIZE: both sides must hold a
well-formed value there, and the value itself is not compared.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DB2_TS = re.compile(r"\d{4}-\d\d-\d\d-\d\d\.\d\d\.\d\d\.\d\d0000")

# file name -> list of (start, end, pattern): zero-based byte ranges holding run-time timestamps.
NORMALIZE = {
    "transact.txt": [(304, 330, DB2_TS)],  # TRAN-PROC-TS, BR-CBTRN02C-05
    "systran.txt": [(278, 304, DB2_TS), (304, 330, DB2_TS)],  # interest TRAN-ORIG-TS, TRAN-PROC-TS, BR-CBACT04C-06
}


# Card-number fields, masked in printed findings (B-MASK applies to evidence output too).
CARD_FIELDS = {
    "transact.txt": [(262, 278)],
    "systran.txt": [(262, 278)],
    "dalyrejs.txt": [(262, 278)],
    "dailytran.txt": [(262, 278)],
    "cardxref.txt": [(0, 16)],
    "carddata.txt": [(0, 16)],
}


def _mask(name: str, rec: str) -> str:
    for start, end in CARD_FIELDS.get(name, []):
        if len(rec) >= end:
            rec = rec[:start] + "*" * (end - start - 4) + rec[end - 4:]
    return rec


def _lines(path: Path) -> list[str]:
    data = path.read_bytes().decode("latin-1")
    if data and not data.endswith("\n"):
        data += "\n"
    return data.split("\n")[:-1] if data else []


def compare_file(name: str, exp: list[str], act: list[str], limit: int = 5) -> list[str]:
    findings = []
    if len(exp) != len(act):
        findings.append(f"{name}: {len(act)} records, expected {len(exp)}")
    rules = NORMALIZE.get(name, [])
    shown = 0
    for i in range(max(len(exp), len(act))):
        e = exp[i] if i < len(exp) else None
        a = act[i] if i < len(act) else None
        if e is None or a is None:
            if shown < limit:
                side = "missing" if a is None else "extra"
                findings.append(f"{name}: record {i + 1} {side}: {_mask(name, e if a is None else a)[:80]!r}")
                shown += 1
            continue
        for start, end, pat in rules:
            for side, rec in (("expected", e), ("actual", a)):
                if not pat.fullmatch(rec[start:end]):
                    findings.append(f"{name}: record {i + 1} {side} bytes {start + 1}-{end} {rec[start:end]!r} "
                                    f"do not match {pat.pattern}")
            e = e[:start] + "~" * (end - start) + e[end:]
            a = a[:start] + "~" * (end - start) + a[end:]
        if e != a:
            if shown < limit:
                col = next((j for j in range(min(len(e), len(a))) if e[j] != a[j]), min(len(e), len(a)))
                lo = max(0, col - 10)
                findings.append(f"{name}: record {i + 1} differs at byte {col + 1}: expected "
                                f"{_mask(name, e)[lo:col + 20]!r}, actual {_mask(name, a)[lo:col + 20]!r}")
            shown += 1
    if shown > limit:
        findings.append(f"{name}: {shown - limit} more differing records")
    return findings


def compare_dirs(expected: Path, actual: Path) -> tuple[list[str], list[str]]:
    """Returns (compared file names, findings)."""
    names = sorted({p.name for p in expected.iterdir() if p.is_file()} | {p.name for p in actual.iterdir() if p.is_file()})
    findings = []
    for n in names:
        e, a = expected / n, actual / n
        if not e.exists():
            findings.append(f"{n}: unexpected file in actual output")
        elif not a.exists():
            findings.append(f"{n}: missing from actual output")
        else:
            findings += compare_file(n, _lines(e), _lines(a))
    return names, findings


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("expected", type=Path)
    ap.add_argument("actual", type=Path)
    a = ap.parse_args(argv)
    names, findings = compare_dirs(a.expected, a.actual)
    for f in findings:
        print("DIFF", f)
    print(f"{'MATCH' if not findings else 'MISMATCH'}: {len(names)} files compared, {len(findings)} findings")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
