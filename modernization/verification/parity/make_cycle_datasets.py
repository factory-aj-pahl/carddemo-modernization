"""Generates the batch-cycle parity datasets (datasets.md) under generated-cycle/.

Every dataset is derived deterministically from the sample files in app/data/ASCII plus the edge
records defined here, so a rerun produces identical bytes (``--check`` verifies that). Transaction
files carry fixed processing timestamps, so the date-range selection of CBTRN03C does not depend on
the day the datasets were built.

Usage: python3 make_cycle_datasets.py [--out DIR] [--check]
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SAMPLE = REPO / "app" / "data" / "ASCII"
OUT = HERE / "generated-cycle"

# INTCALC PARM of app/jcl/INTCALC.jcl; the Java job gets business date 2022-07-18 from it.
PARM = "2022071800"
REPORT_RANGE = ("2022-06-10", "2022-06-11")

_POS = "{ABCDEFGHI"
_NEG = "}JKLMNOPQR"

MASTERS = ("acctdata.txt", "carddata.txt", "custdata.txt", "cardxref.txt", "discgrp.txt", "tcatbal.txt",
           "trancatg.txt", "trantype.txt")
LENGTHS = {"acctdata.txt": 300, "carddata.txt": 150, "custdata.txt": 500, "cardxref.txt": 50, "discgrp.txt": 50,
           "tcatbal.txt": 50, "trancatg.txt": 60, "trantype.txt": 60, "dailytran.txt": 350, "transact.txt": 350}


def records(name: str) -> list[str]:
    out = []
    for line in (SAMPLE / name).read_bytes().decode("latin-1").split("\n"):
        line = line.rstrip("\r")
        if line:
            out.append(line.ljust(LENGTHS[name]))
    return out


def write(path: Path, recs: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes("".join(r + "\n" for r in recs).encode("latin-1"))


def put(rec: str, offset: int, value: str) -> str:
    return rec[:offset] + value + rec[offset + len(value):]


def zoned(cents: int, length: int) -> str:
    """S9(n)V99 zoned decimal with the sign overpunched on the last digit (EBCDIC convention)."""
    digits = f"{abs(cents):0{length}d}"
    if len(digits) > length:
        raise ValueError(f"{cents} does not fit {length} digits")
    last = int(digits[-1])
    return digits[:-1] + (_NEG[last] if cents < 0 else _POS[last])


def proc_ts(date: str) -> str:
    return f"{date}-12.00.00.000000"


def sample() -> dict[str, list[str]]:
    return {n: records(n) for n in MASTERS + ("dailytran.txt",)}


def transactions(daily: list[str], dates: list[str]) -> list[str]:
    """TRANSACT records from daily records: processing timestamp from dates, cycled by position."""
    return sorted(put(r, 304, proc_ts(dates[i % len(dates)])) for i, r in enumerate(daily))


def acct_index(accts: list[str], acct_id: int) -> int:
    key = f"{acct_id:011d}"
    return next(i for i, r in enumerate(accts) if r[0:11] == key)


def card_of(xref: list[str], acct_id: int) -> str:
    key = f"{acct_id:011d}"
    return min(r[0:16] for r in xref if r[25:36] == key)


def cycle_edge() -> dict[str, list[str]]:
    """Edge masters and daily records for TRANVERIFY, POSTTRAN, and INTCALC."""
    m = sample()
    accts, xref, tcat, daily = m["acctdata.txt"], m["cardxref.txt"], m["tcatbal.txt"], m["dailytran.txt"]
    # account 1: no group in either slot -> DEFAULT fallback with the message pair (BR-CBACT04C-02)
    i = acct_index(accts, 1)
    accts[i] = put(put(accts[i], 102, " " * 10), 112, " " * 10)
    # account 2: group ZEROAPR (rate 0) in the ZIP slot, moved by RP-DATA-1 (BR-CBACT04C-03)
    i = acct_index(accts, 2)
    accts[i] = put(accts[i], 102, "ZEROAPR   ")
    # account 5: non-zero cycle credit and debit, reset by INTCALC (BR-CBACT04C-07)
    i = acct_index(accts, 5)
    accts[i] = put(put(accts[i], 78, zoned(10000, 12)), 90, zoned(5025, 12))
    # account 6: cycle totals but no category balance -> not touched (Q-CBACT04C-07)
    i = acct_index(accts, 6)
    accts[i] = put(accts[i], 78, zoned(777, 12))
    tcat = [r for r in tcat if r[0:11] != f"{6:011d}"]
    # account 3: several categories, positive, negative, and zero balances (BR-CBACT04C-01, -04, -05)
    tcat = [r for r in tcat if r[0:11] != f"{3:011d}"]
    for type_cd, cat, cents in (("01", 1, -123456), ("01", 2, 1234567), ("02", 1, -123457), ("03", 1, 0)):
        tcat.append(f"{3:011d}{type_cd}{cat:04d}{zoned(cents, 11)}".ljust(50, "0"))
    # account 4: a second, lower card -> interest goes to the lowest card (Q-CBACT04C-03)
    cust4 = next(r[16:25] for r in xref if r[25:36] == f"{4:011d}")
    xref.append(f"0000000000000004{cust4}{4:011d}".ljust(50))
    # a card whose account does not exist (BR-CBTRN01C-04, POSTTRAN reason 101)
    xref.append(f"4444333322221111{1:09d}{99999999999:011d}".ljust(50))
    template = sorted(daily)[0]
    extra = [
        # card not in the cross-reference (BR-CBTRN01C-03, POSTTRAN reason 100)
        put(put(template, 0, "9000000000000001"), 262, "9999999999999999"),
        # card whose account is missing (reason 101)
        put(put(template, 0, "9000000000000002"), 262, "4444333322221111"),
        # account 1 expired 2025-05-20; original date after it (reason 103)
        put(put(put(template, 0, "9000000000000003"), 262, card_of(xref, 1)), 278, "2026-01-01 00:00:00.000000"),
        # a credit on account 4's new card, posted to a new category balance (BR-CBTRN02C-06, -07)
        put(put(put(put(template, 0, "9000000000000004"), 262, "0000000000000004"), 132, zoned(-2500, 11)), 16,
            "030001"),
    ]
    m["dailytran.txt"] = daily[:150] + extra[:2] + daily[150:] + extra[2:]
    m["tcatbal.txt"] = sorted(r.ljust(50)[:50] for r in tcat)
    m["cardxref.txt"] = sorted(xref)
    m["acctdata.txt"] = accts
    return m


def statement_edge() -> dict[str, list[str]]:
    """Sample masters plus transactions, with name and address edges and a card over the old table limits."""
    m = sample()
    cust, xref = m["custdata.txt"], m["cardxref.txt"]

    def edit(cust_id: int, *changes: tuple[int, int, str]) -> None:
        i = next(k for k, r in enumerate(cust) if r[0:9] == f"{cust_id:09d}")
        r = cust[i]
        for off, ln, v in changes:
            r = put(r, off, v.ljust(ln)[:ln])
        cust[i] = r

    edit(1, (34, 25, ""))                                   # no middle name
    edit(2, (184, 50, ""), (234, 2, ""))                    # no address line 3, no state
    edit(3, (9, 25, "Mary Ann"), (59, 25, "Van Dyke"))      # embedded spaces in names
    edit(4, (9, 25, ""), (34, 25, ""))                      # last name only
    edit(5, (236, 3, ""), (239, 10, ""))                    # no country, no ZIP
    daily = m.pop("dailytran.txt")
    busiest = max(sorted({r[262:278] for r in daily}), key=lambda c: sum(1 for r in daily if r[262:278] == c))
    copies = [r for r in sorted(daily) if r[262:278] == busiest]
    extra = []
    for n in range(14):
        r = copies[n % len(copies)]
        extra.append(put(put(r, 0, f"91{n:014d}"), 132, zoned((n + 1) * 1111 * (-1 if n % 4 == 3 else 1), 11)))
    # a card with no transactions (statement without transaction lines)
    cust1 = next(r[16:25] for r in xref if r[25:36] == f"{1:011d}")
    m["cardxref.txt"] = sorted(xref + [f"0000000000000001{cust1}{1:011d}".ljust(50)])
    m["transact.txt"] = transactions(daily + extra, ["2022-06-10"])
    return m


def build(out: Path) -> dict[str, Path]:
    if out.exists():
        shutil.rmtree(out)
    sets: dict[str, Path] = {}

    def ds(name: str, files: dict[str, list[str]], **text: str) -> None:
        d = out / name
        d.mkdir(parents=True)
        for n, recs in files.items():
            write(d / n, recs)
        for n, body in text.items():
            (d / n.replace("_", ".")).write_text(body)
        sets[name] = d

    dateparm = f"{REPORT_RANGE[0]} {REPORT_RANGE[1]}".ljust(80) + "\n"
    s = sample()
    ds("cycle-edge", cycle_edge(), parm_txt=PARM + "\n")
    ds("statement-edge", statement_edge())
    daily = s.pop("dailytran.txt")
    ds("report-edge", s | {"transact.txt": transactions(daily, ["2022-06-09", "2022-06-10", "2022-06-11", "2022-06-12"])},
       dateparm_txt=dateparm)
    ds("report-sample", s | {"transact.txt": transactions(daily, ["2022-06-10"])},
       dateparm_txt="2022-06-10 2022-06-10".ljust(80) + "\n")
    ds("empty-input", s | {"dailytran.txt": [], "tcatbal.txt": [], "transact.txt": []}, parm_txt=PARM + "\n",
       dateparm_txt=dateparm)
    ds("report-none", s | {"transact.txt": transactions(daily, ["2022-06-12"])}, dateparm_txt=dateparm)
    return sets


def digest(root: Path) -> str:
    h = hashlib.sha256()
    for p in sorted(root.rglob("*")):
        if p.is_file():
            h.update(str(p.relative_to(root)).encode())
            h.update(p.read_bytes())
    return h.hexdigest()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, default=OUT)
    ap.add_argument("--check", action="store_true", help="build twice and confirm identical bytes")
    a = ap.parse_args(argv)
    sets = build(a.out)
    first = digest(a.out)
    if a.check:
        build(a.out)
        if digest(a.out) != first:
            print("datasets are not deterministic")
            return 1
    for name, path in sets.items():
        print(f"{name}: {', '.join(sorted(p.name for p in path.iterdir()))}")
    print(f"sha256 {first}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
