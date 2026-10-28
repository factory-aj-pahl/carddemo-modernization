#!/usr/bin/env python3
"""Consistency check for the fidelity ledger in modernization/decisions.

Exits 1 on any mismatch and prints row counts. Standard library only.
"""
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "spec"
DEC = ROOT / "decisions"
LEDGERS = {"online": DEC / "ledger-online.md", "batch": DEC / "ledger-batch-data.md"}
ONLINE_MEMBERS = {
    "COSGN00C", "COMEN01C", "COADM01C", "COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C",
    "COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC", "COTRN00C", "COTRN01C",
    "COTRN02C", "COBIL00C", "CORPT00C", "CSUTLDTC",
}
CATEGORIES = [
    "security", "data-corruption", "concurrency", "functional-defect", "cosmetic",
    "business-choice", "platform", "dead-code", "user-decision",
]
DECISIONS = ["FIX", "PRESERVE", "CHANGE", "PLATFORM", "NOT-PORTED"]
DECISION_RE = re.compile(r"^(FIX|PRESERVE|CHANGE|PLATFORM|NOT-PORTED)(?: \(([A-Z0-9-]+(?:, [A-Z0-9-]+)*)\))?$")
SPEC_ID_RE = re.compile(r"\*\*(Q-[A-Z0-9]+(?:-[A-Z0-9]+)*-\d+)\*\*")
UNRESOLVED_RE = re.compile(
    r"confirm with the user|for the user to confirm|needs user confirmation|listed for confirmation|confirm the intended",
    re.IGNORECASE,
)
RP_RE = re.compile(r"\bRP-[A-Z0-9]+-\d+\b")
CONV_RE = re.compile(r"(?<![A-Za-z0-9-])[BC]-[A-Z]{3,}\b")

errors = []


def err(msg):
    errors.append(msg)


def cells(line):
    return [c.strip() for c in line.strip().strip("|").split(" | ")]


def section(lines, heading):
    try:
        start = lines.index(heading)
    except ValueError:
        return None
    end = start + 1
    while end < len(lines) and not lines[end].startswith("## "):
        end += 1
    return lines[start + 1:end]


def decision_ids():
    text = (DEC / "README.md").read_text()
    return set(re.findall(r"^\| ((?:D|DF)-[A-Z0-9-]+) \|", text, re.MULTILINE))


def spec_ids():
    found = Counter()
    files = sorted(SPEC.glob("*.md")) + sorted((SPEC / "programs").glob("*.md"))
    for path in files:
        found.update(set(SPEC_ID_RE.findall(path.read_text())))
    return set(found)


def member(qid):
    return qid.split("-")[1]


def main():
    known = decision_ids()
    if not known:
        err("README.md: no D- or DF- decision IDs found")
    spec = spec_ids()
    seen = Counter()
    conventions = set()
    parsed = {}

    for name, path in LEDGERS.items():
        lines = path.read_text().split("\n")
        parsed[name] = lines
        for line in lines:
            m = re.match(r"^\| ([BC]-[A-Z]+) \|", line)
            if m:
                conventions.add(m.group(1))

    rp_defined = set()
    rp_rows = []
    for line in parsed["batch"] + parsed["online"]:
        m = re.match(r"^\| (RP-[A-Z0-9]+-\d+) \| (.*?) \|", line)
        if m:
            rp_defined.add(m.group(1))
            rp_rows.append((m.group(1), re.findall(r"Q-[A-Z0-9]+(?:-[A-Z0-9]+)*-\d+", m.group(2))))

    totals = {}
    for name, lines in parsed.items():
        table = Counter()
        rows = []
        for n, line in enumerate(lines, 1):
            if not line.startswith("| Q-"):
                continue
            where = f"{LEDGERS[name].name}:{n}"
            c = cells(line)
            if len(c) != 6 or any(x == "" for x in c):
                err(f"{where}: expected 6 non-empty cells, got {len(c)}")
                continue
            qid, summary, cat, dec, target, ref = c
            rows.append(c)
            seen[qid] += 1
            if qid not in spec:
                err(f"{where}: {qid} is not a bold Q- ID in spec/")
            in_online = member(qid) in ONLINE_MEMBERS
            if (name == "online") != in_online:
                err(f"{where}: {qid} is in the wrong ledger")
            if cat not in CATEGORIES:
                err(f"{where}: {qid} unknown category '{cat}'")
            m = DECISION_RE.match(dec)
            if not m:
                err(f"{where}: {qid} invalid decision '{dec}'")
                continue
            base, cited = m.group(1), (m.group(2) or "")
            cited_ids = [x for x in cited.split(", ") if x]
            for x in cited_ids:
                if x not in known:
                    err(f"{where}: {qid} cites unknown decision {x}")
            for x in re.findall(r"\b(?:D|DF)-[A-Z0-9]+(?:-[A-Z0-9]+)*\b", target):
                if x not in known:
                    err(f"{where}: {qid} target cites unknown decision {x}")
            if base == "CHANGE" and not any(x.startswith("D-") for x in cited_ids):
                err(f"{where}: {qid} CHANGE must cite a D- decision")
            if base in ("FIX", "CHANGE") and target.lower() in ("n/a", "none", "-"):
                err(f"{where}: {qid} {base} row has no target behavior")
            if UNRESOLVED_RE.search(line):
                err(f"{where}: {qid} still asks for user confirmation")
            for rp in RP_RE.findall(ref + " " + target):
                if rp not in rp_defined:
                    err(f"{where}: {qid} references undefined {rp}")
            for conv in CONV_RE.findall(target):
                if conv not in conventions:
                    err(f"{where}: {qid} references undefined convention {conv}")
            table[(cat, base)] += 1
        totals[name] = (rows, table)

        for line in lines:
            if line.startswith("| C-") or line.startswith("| B-"):
                if UNRESOLVED_RE.search(line):
                    err(f"{LEDGERS[name].name}: convention {cells(line)[0]} still asks for confirmation")
        for heading in ("## Open questions for the user", "## Decisions the user should confirm"):
            if heading in lines:
                err(f"{LEDGERS[name].name}: section '{heading}' still present")

        expected_bc = {c[0] for c in rows if c[2] == "business-choice" and c[3].split(" (")[0] == "PRESERVE"}
        bc = section(lines, "## Business choices preserved")
        if bc is None:
            err(f"{LEDGERS[name].name}: missing 'Business choices preserved'")
        else:
            listed = [re.match(r"^- (Q-[A-Z0-9]+(?:-[A-Z0-9]+)*-\d+):", l).group(1) for l in bc if re.match(r"^- Q-", l)]
            if Counter(listed) != Counter(expected_bc):
                err(f"{LEDGERS[name].name}: business-choice list differs: missing "
                    f"{sorted(expected_bc - set(listed))}, extra {sorted(set(listed) - expected_bc)}")

        counts = section(lines, "## Counts")
        if counts is None:
            err(f"{LEDGERS[name].name}: missing 'Counts'")
        else:
            for l in counts:
                c = cells(l) if l.startswith("| ") else []
                if len(c) != 7 or c[0] in ("Category", ":--"):
                    continue
                if c[0] == "Total":
                    want = [sum(v for (k, d), v in table.items() if d == dd) for dd in DECISIONS]
                else:
                    want = [table.get((c[0], dd), 0) for dd in DECISIONS]
                want.append(sum(want))
                if [int(x) for x in c[1:]] != want:
                    err(f"{LEDGERS[name].name}: counts row '{c[0]}' is {c[1:]}, rows give {want}")
            m = re.search(r"\((\d+) rows\)", "\n".join(counts))
            if not m or int(m.group(1)) != len(rows):
                err(f"{LEDGERS[name].name}: counts header row total does not equal {len(rows)}")

    for rp, qids in rp_rows:
        for q in qids:
            if q not in seen:
                err(f"reference patch {rp} names {q}, which has no ledger row")

    for qid in sorted(spec):
        if seen[qid] == 0:
            err(f"{qid}: defined in spec/ but missing from the ledgers")
    for qid, n in sorted(seen.items()):
        if n > 1:
            err(f"{qid}: appears {n} times in the ledgers")

    print(f"spec Q- IDs: {len(spec)}")
    for name, (rows, table) in totals.items():
        by_dec = Counter(c[3].split(" (")[0] for c in rows)
        print(f"{LEDGERS[name].name}: {len(rows)} rows; " + ", ".join(f"{d} {by_dec.get(d, 0)}" for d in DECISIONS))
    print(f"ledger rows total: {sum(len(r) for r, _ in totals.values())}")
    if errors:
        print(f"FAILED: {len(errors)} problem(s)")
        for e in errors:
            print(f"  {e}")
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
