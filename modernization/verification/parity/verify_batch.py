"""Batch parity driver: every owed (program, dataset) pair in datasets.md, reference vs Java.

For each pair: run the patched GnuCOBOL reference (refrun.py), run the Java job on a fresh
database (javarun.py), and compare the output directories (compare.py). The owed set is the
table in datasets.md, read before anything runs; a pair that cannot run counts as failed. The
generated datasets (make_datasets.py, make_cycle_datasets.py) are rebuilt first. Results are also written to
<out>/results.json (pair, verdict, findings, and the "Rules exercised" cell of its row).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
import traceback
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import compare  # noqa: E402
import javarun  # noqa: E402
import make_datasets  # noqa: E402
import make_cycle_datasets  # noqa: E402
import refrun  # noqa: E402

REPO = HERE.parents[2]
DATASETS_MD = HERE / "datasets.md"
_ROW = re.compile(r"^\|\s*([A-Z0-9+]+)\s*\|\s*([a-z0-9-]+)\s*\|\s*`([^`]+)`\s*\|\s*([^|]*)\|")


def owed_pairs(md: Path = DATASETS_MD) -> list[tuple[str, str, Path]]:
    """(program, dataset name, dataset directory) for each row of the "Owed pairs" table."""
    pairs, in_table = [], False
    for line in md.read_text().splitlines():
        if line.startswith("## "):
            in_table = line.strip() == "## Owed pairs"
            continue
        m = _ROW.match(line) if in_table else None
        if m:
            pairs.append((m.group(1), m.group(2), (REPO / m.group(3)).resolve()))
    return pairs


def rules_exercised(md: Path = DATASETS_MD) -> dict[tuple[str, str], str]:
    """(program, dataset name) -> the "Rules exercised" cell of its owed-pairs row."""
    out, in_table = {}, False
    for line in md.read_text().splitlines():
        if line.startswith("## "):
            in_table = line.strip() == "## Owed pairs"
            continue
        m = _ROW.match(line) if in_table else None
        if m:
            out[(m.group(1), m.group(2))] = m.group(4).strip()
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, help="keep outputs here (default: a temporary directory)")
    ap.add_argument("--only", help="run only this program")
    a = ap.parse_args(argv)
    root = (a.out or Path(tempfile.mkdtemp(prefix="verify-batch-"))).resolve()
    make_datasets.build(make_datasets.OUT)
    make_cycle_datasets.build(make_cycle_datasets.OUT)
    pairs = owed_pairs()
    rules = rules_exercised()
    if a.only:
        pairs = [p for p in pairs if p[0] == a.only]
    if not pairs:
        print("no owed pairs selected")
        return 2
    results = {}
    for program, name, dataset in pairs:
        tag = f"{program}/{name}"
        base = root / program / name
        try:
            if not dataset.is_dir():
                raise RuntimeError(f"dataset directory {dataset} missing")
            refrun.Runner(base / "ref-work").run(program, dataset, base / "expected")
            javarun.JavaRunner().run(program, dataset, base / "actual")
            _, findings = compare.compare_dirs(base / "expected", base / "actual")
        except Exception as e:  # a pair that cannot run is a failure, not a skip
            findings = [f"did not run: {e}"]
            traceback.print_exc()
        results[tag] = findings
        print(f"{'MATCH   ' if not findings else 'MISMATCH'} {tag}")
        for f in findings:
            print(f"    {f}")
    matched = sum(1 for f in results.values() if not f)
    root.mkdir(parents=True, exist_ok=True)
    (root / "results.json").write_text(json.dumps({
        "owed": len(pairs),
        "matched": matched,
        "pairs": [{"program": p, "dataset": n, "verdict": "MATCH" if not results[f"{p}/{n}"] else "MISMATCH",
                   "findings": results[f"{p}/{n}"], "rules": rules.get((p, n), "")} for p, n, _ in pairs],
    }, indent=2) + "\n")
    print(f"batch parity: {matched}/{len(pairs)} owed pairs matched; outputs in {root}")
    return 0 if matched == len(pairs) else 1


if __name__ == "__main__":
    raise SystemExit(main())
