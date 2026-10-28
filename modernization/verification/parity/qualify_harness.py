"""Qualifies the batch parity harness (refrun.py, compare.py) before its verdicts are trusted.

Four checks, each printed as PASS or FAIL with its evidence:

1. Known outcomes of the unmodified COBOL: CBTRN02C on the sample data with -fsign=EBCDIC gives
   RC 4, 300 processed, 38 rejects all with reason 0102; with the cobc default sign it gives 43.
2. The unmodified listing programs show every sample record: CBACT02C once each, CBACT03C and
   CBCUS01C twice each (Q-CBACT03C-01, Q-CBCUS01C-01), framed by the start and end messages.
3. Every RP- patch named in decisions/*.md: a patch file applies cleanly, cumulatively per
   program, to a temporary copy and the patched program compiles; build and data patches are
   implemented by refrun.py; app/ has no changes afterwards (git status --porcelain -- app/).
4. Comparator mutations on reference output pairs (CBTRN02C and CBEXPORT): the unmodified pair
   matches; a flipped byte, a dropped record, an added trailing record, a renamed output file, a
   removed table row, and a malformed normalized timestamp are each reported with the file name
   (and record number where one exists).

Usage: python3 qualify_harness.py [--out DIR]. Exit code 0 only when every check passes.
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import compare  # noqa: E402
import make_datasets  # noqa: E402
import refrun  # noqa: E402

REPO = HERE.parents[2]
DECISIONS = REPO / "modernization" / "decisions"
SAMPLE = REPO / "app" / "data" / "ASCII"
_RP_ROW = re.compile(r"^\|\s*(RP-[A-Z0-9]+-\d+)\s*\|")

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, evidence: str) -> bool:
    results.append((name, ok, evidence))
    print(f"{'PASS' if ok else 'FAIL'} {name}: {evidence}")
    return ok


def ref(work: Path, member: str, out: Path, dataset: Path = SAMPLE, sign: str = "EBCDIC", patched: bool = False) -> list[str]:
    refrun.Runner(work, sign=sign, patched=patched).run(member, dataset, out)
    return (out / "sysout.txt").read_text(encoding="latin-1").splitlines()


def known_outcomes(root: Path) -> None:
    for sign, rejects in (("EBCDIC", 38), ("", 43)):
        out = root / f"cbtrn02c-{sign or 'default'}"
        lines = ref(root / f"w-cbtrn02c-{sign or 'default'}", "CBTRN02C", out, sign=sign)
        rc = int((out / "rc.txt").read_text())
        reasons = [r[350:354] for r in refrun.records(out / "dalyrejs.txt", 430)]
        processed = "TRANSACTIONS PROCESSED :000000300" in lines
        rejected = f"TRANSACTIONS REJECTED  :{rejects:09d}" in lines
        ok = rc == 4 and processed and rejected and len(reasons) == rejects and set(reasons) == {"0102"}
        check(f"1 CBTRN02C unpatched -fsign={sign or '(default)'}", ok,
              f"RC {rc}, processed-300 line {'present' if processed else 'MISSING'}, rejected line for {rejects} "
              f"{'present' if rejected else 'MISSING'}, {len(reasons)} rejects with reasons {sorted(set(reasons))}")


def listing_counts(root: Path) -> None:
    for member, name, length, times in (("CBACT02C", "carddata.txt", 150, 1), ("CBACT03C", "cardxref.txt", 50, 2),
                                        ("CBCUS01C", "custdata.txt", 500, 2)):
        sample = refrun.records(SAMPLE / name, length)
        lines = ref(root / f"w-{member}", member, root / member.lower())
        body = lines[1:-1]
        framed = lines[:1] == [f"START OF EXECUTION OF PROGRAM {member}"] and lines[-1:] == [f"END OF EXECUTION OF PROGRAM {member}"]
        shown = [ln.ljust(length) for ln in body]
        each = all(shown.count(r) == times for r in sample)
        ok = framed and len(body) == times * len(sample) and each
        check(f"2 {member} unpatched record count", ok,
              f"{len(body)} record lines for {len(sample)} sample records (expected {times} per record), "
              f"every sample record shown {times}x: {each}, framed: {framed}")


def rp_ids() -> list[str]:
    ids = []
    for md in sorted(DECISIONS.glob("*.md")):
        for line in md.read_text().splitlines():
            m = _RP_ROW.match(line)
            if m and m.group(1) not in ids:
                ids.append(m.group(1))
    return ids


def app_clean() -> tuple[bool, str]:
    p = subprocess.run(["git", "-C", str(REPO), "status", "--porcelain", "--", "app/"], capture_output=True, text=True, check=False)
    return p.returncode == 0 and not p.stdout.strip(), p.stdout.strip() or "(clean)"


def patches(root: Path) -> None:
    ids = rp_ids()
    by_member: dict[str, list[str]] = {}
    for rp in ids:
        member = rp.split("-")[1]
        if member in ("BUILD", "DATA"):
            continue
        by_member.setdefault(member, []).append(rp)
    check("3 RP-BUILD-1 implemented", refrun.Runner.__init__.__defaults__[1] == "EBCDIC",
          "refrun.Runner compiles with -fsign=EBCDIC by default")
    check("3 RP-DATA-1 implemented", "RP-DATA-1" in refrun.DATA_PATCHES, "refrun.DATA_PATCHES['RP-DATA-1']")
    cobc = shutil.which("cobc") or ""
    for member, rps in sorted(by_member.items()):
        missing = [rp for rp in rps if not (refrun.PATCHES / f"{rp}.patch").exists()]
        if missing:
            check(f"3 {member} patches", False, f"no patch file for {', '.join(missing)}")
            continue
        tmp = Path(tempfile.mkdtemp(prefix=f"rp-{member}-", dir=root))
        targets = set()
        for rp in rps:
            for line in (refrun.PATCHES / f"{rp}.patch").read_text(encoding="latin-1").splitlines():
                if line.startswith("+++ "):
                    targets.add(line[4:].split("\t")[0].split("/", 1)[1])
        for t in targets:
            (tmp / t).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(REPO / t, tmp / t)
        log = []
        ok = True
        for rp in rps:
            p = subprocess.run(["patch", "-p1", "--no-backup-if-mismatch", "-d", str(tmp), "-i", str(refrun.PATCHES / f"{rp}.patch")],
                               capture_output=True, text=True, check=False)
            fuzz = "fuzz" in p.stdout or "offset" in p.stdout.lower()
            ok = ok and p.returncode == 0 and not fuzz
            log.append(f"{rp} {'applied' if p.returncode == 0 and not fuzz else 'FAILED: ' + (p.stdout + p.stderr).strip()}")
        sources = [tmp / t for t in targets if t.lower().endswith(".cbl")]
        for src in sources:
            c = subprocess.run([cobc, "-fsyntax-only", "-std=ibm", "-fsign=EBCDIC", "-I", str(REPO / "app" / "cpy"), str(src)],
                               capture_output=True, text=True, check=False)
            ok = ok and c.returncode == 0
            log.append(f"{src.name} {'compiles' if c.returncode == 0 else 'DOES NOT COMPILE: ' + c.stderr.strip()[:300]}")
        check(f"3 {member} patches", ok, "; ".join(log))
    clean, status = app_clean()
    check("3 app/ unchanged", clean, f"git status --porcelain -- app/: {status}")


def mutation(root: Path, label: str, expected: Path, mutate, *needles: str) -> None:
    actual = root / f"mut-{label}"
    shutil.rmtree(actual, ignore_errors=True)
    shutil.copytree(expected, actual)
    mutate(actual)
    _, findings = compare.compare_dirs(expected, actual)
    text = " | ".join(findings)
    ok = bool(findings) and all(n in text for n in needles)
    check(f"4 comparator {label}", ok, text[:300] if findings else "no finding (mutation passed unnoticed)")


def rewrite(path: Path, fn) -> None:
    data = path.read_bytes().decode("latin-1")
    path.write_bytes(fn(data).encode("latin-1"))


def comparator(root: Path) -> None:
    posting = root / "pair-cbtrn02c"
    ref(root / "w-pair-cbtrn02c", "CBTRN02C", posting, patched=True)
    ds = make_datasets.build(root / "datasets")
    export = root / "pair-cbexport"
    ref(root / "w-pair-cbexport", "CBEXPORT", export, dataset=ds["export-sample"], patched=True)
    for exp in (posting, export):
        _, findings = compare.compare_dirs(exp, exp)
        check(f"4 comparator unmodified {exp.name}", not findings, f"{len(findings)} findings")

    def flip(name: str, rec: int, col: int, length: int | None = None):
        def f(d: Path) -> None:
            def g(s: str) -> str:
                if length:
                    i = rec * length + col
                    return s[:i] + chr(ord(s[i]) ^ 0x01) + s[i + 1:]
                lines = s.split("\n")
                line = lines[rec]
                lines[rec] = line[:col] + chr(ord(line[col]) ^ 0x01) + line[col + 1:]
                return "\n".join(lines)
            rewrite(d / name, g)
        return f

    def malform(name: str, rec: int, col: int, length: int | None = None):
        """Puts a letter into a normalized timestamp field; a changed but well-formed time is not a finding."""
        def f(d: Path) -> None:
            def g(s: str) -> str:
                if length:
                    i = rec * length + col
                    return s[:i] + "X" + s[i + 1:]
                lines = s.split("\n")
                lines[rec] = lines[rec][:col] + "X" + lines[rec][col + 1:]
                return "\n".join(lines)
            rewrite(d / name, g)
        return f

    def drop(name: str, rec: int, length: int | None = None):
        def f(d: Path) -> None:
            if length:
                rewrite(d / name, lambda s: s[:rec * length] + s[(rec + 1) * length:])
            else:
                rewrite(d / name, lambda s: "\n".join(x for i, x in enumerate(s.split("\n")) if i != rec))
        return f

    def append(name: str, length: int | None = None):
        def f(d: Path) -> None:
            if length:
                rewrite(d / name, lambda s: s + s[:length])
            else:
                rewrite(d / name, lambda s: s + s.split("\n")[0] + "\n")
        return f

    def rename(name: str, new: str):
        return lambda d: (d / name).rename(d / new)

    mutation(root, "flip-byte-dalyrejs", posting, flip("dalyrejs.txt", 4, 20), "dalyrejs.txt", "record 5")
    mutation(root, "drop-record-transact", posting, drop("transact.txt", 9), "transact.txt", "record")
    mutation(root, "add-trailing-dalyrejs", posting, append("dalyrejs.txt"), "dalyrejs.txt", "record 39 extra")
    mutation(root, "rename-dalyrejs", posting, rename("dalyrejs.txt", "dalyrejs.dat"),
             "dalyrejs.txt: missing", "dalyrejs.dat: unexpected")
    mutation(root, "remove-table-row-acctdata", posting, drop("acctdata.txt", 49), "acctdata.txt", "record 50 missing")
    mutation(root, "flip-sysout", posting, flip("sysout.txt", 51, 30), "sysout.txt", "record 52")
    mutation(root, "malformed-timestamp-transact", posting, malform("transact.txt", 0, 305), "transact.txt", "record 1")
    mutation(root, "flip-byte-expfile", export, flip("expfile.dat", 120, 60, 500), "expfile.dat", "record 121")
    mutation(root, "drop-record-expfile", export, drop("expfile.dat", 3, 500), "expfile.dat", "record")
    mutation(root, "add-trailing-expfile", export, append("expfile.dat", 500), "expfile.dat", "record 501 extra")
    mutation(root, "partial-record-expfile", export, lambda d: rewrite(d / "expfile.dat", lambda s: s[:-1]), "expfile.dat", "record 500")
    mutation(root, "malformed-timestamp-expfile", export, malform("expfile.dat", 7, 3, 500), "expfile.dat", "record 8")
    mutation(root, "rc", export, lambda d: (d / "rc.txt").write_text("99\n"), "rc.txt", "record 1")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, help="keep work files here (default: a temporary directory)")
    a = ap.parse_args(argv)
    root = (a.out or Path(tempfile.mkdtemp(prefix="qualify-harness-"))).resolve()
    root.mkdir(parents=True, exist_ok=True)
    for step in (known_outcomes, listing_counts, patches, comparator):
        try:
            step(root)
        except Exception as e:  # a check that cannot run is a failure
            check(step.__name__, False, f"did not run: {e}")
    failed = [n for n, ok, _ in results if not ok]
    print(f"harness qualification: {len(results) - len(failed)}/{len(results)} checks passed; work files in {root}")
    for n in failed:
        print(f"    failed: {n}")
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
