"""Comparator for batch parity: expected (reference) output directory vs actual (Java) output directory.

Every file in either directory is compared record by record (LF-terminated lines, or fixed-length
records for the binary files in BINARY). A file present on one side only, a different record count,
or any differing byte is a finding. The only normalizations are the run-time date and time fields
listed in NORMALIZE (byte ranges) and SYSOUT_NORMALIZE (job-log lines): both sides must hold a
well-formed value there, and the value itself is not compared.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DB2_TS = re.compile(r"\d{4}-\d\d-\d\d-\d\d\.\d\d\.\d\d\.\d\d0000")
EXPORT_TS = re.compile(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.00    ")
CURRENT_DATE = re.compile(r"\d{16}[+-]\d{4}")
ISO_DATE = re.compile(r"\d{4}-\d\d-\d\d")
CLOCK = re.compile(r"\d\d:\d\d:\d\d")

# file name -> list of (start, end, pattern): zero-based byte ranges holding run-time timestamps.
NORMALIZE = {
    "transact.txt": [(304, 330, DB2_TS)],  # TRAN-PROC-TS, BR-CBTRN02C-05
    "expfile.dat": [(1, 27, EXPORT_TS)],  # export timestamp, BR-CBEXPORT-01
    "errout.txt": [(0, 21, CURRENT_DATE)],  # FUNCTION CURRENT-DATE, BR-CBIMPORT-07
    "systran.txt": [(278, 304, DB2_TS), (304, 330, DB2_TS)],  # interest TRAN-ORIG-TS, TRAN-PROC-TS, BR-CBACT04C-06
}

# Job-log lines that show the run's own date or time: (line prefix, pattern of the rest).
SYSOUT_NORMALIZE = [
    ("CBEXPORT: Export Date: ", ISO_DATE),  # BR-CBEXPORT-01
    ("CBEXPORT: Export Time: ", CLOCK),
    ("CBIMPORT: Import Date: ", ISO_DATE),  # BR-CBIMPORT-10
    ("CBIMPORT: Import Time: ", CLOCK),
]

# Files of fixed-length records without line terminators: name -> record length.
BINARY = {"expfile.dat": 500}


# Card-number fields, masked in printed findings (B-MASK applies to evidence output too).
CARD_FIELDS = {
    "transact.txt": [(262, 278)],
    "systran.txt": [(262, 278)],
    "dalyrejs.txt": [(262, 278)],
    "dailytran.txt": [(262, 278)],
    "cardxref.txt": [(0, 16)],
    "carddata.txt": [(0, 16)],
}


# CVEXPORT records: PAN and SSN fields by record type (BR-CBEXPORT-04..08).
EXPORT_FIELDS = {"C": [(314, 323)], "X": [(40, 56)], "T": [(292, 308)], "D": [(40, 56)]}


def _mask(name: str, rec: str) -> str:
    fields = CARD_FIELDS.get(name, [])
    if name in BINARY and rec:
        fields = EXPORT_FIELDS.get(rec[0], [])
    for start, end in fields:
        if len(rec) >= end:
            rec = rec[:start] + "*" * (end - start - 4) + rec[end - 4:]
    return rec


def _lines(path: Path) -> list[str]:
    data = path.read_bytes().decode("latin-1")
    if path.name in BINARY:
        n = BINARY[path.name]
        recs = [data[i:i + n] for i in range(0, len(data), n)]
        if recs and len(recs[-1]) != n:
            recs[-1] += "\x00(partial record)"
        return recs
    if data and not data.endswith("\n"):
        data += "\n"
    return data.split("\n")[:-1] if data else []


def compare_file(name: str, exp: list[str], act: list[str], limit: int = 5) -> list[str]:
    findings = []
    if len(exp) != len(act):
        findings.append(f"{name}: {len(act)} records, expected {len(exp)}")
    rules = NORMALIZE.get(name, [])
    if name == "sysout.txt":
        exp, act = _normalize_sysout(name, exp, act, findings)
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


def _normalize_sysout(name: str, exp: list[str], act: list[str], findings: list[str]) -> tuple[list[str], list[str]]:
    def norm(lines: list[str], side: str) -> list[str]:
        out = []
        for i, line in enumerate(lines):
            for prefix, pat in SYSOUT_NORMALIZE:
                if line.startswith(prefix):
                    if not pat.fullmatch(line[len(prefix):]):
                        findings.append(f"{name}: line {i + 1} {side} {line!r} does not match {prefix}{pat.pattern}")
                    line = prefix + "~"
                    break
            out.append(line)
        return out

    return norm(exp, "expected"), norm(act, "actual")


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
