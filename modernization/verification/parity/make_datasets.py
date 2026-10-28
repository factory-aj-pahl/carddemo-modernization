"""Generates the differential datasets for batch parity (datasets.md) under generated/.

Every dataset is derived deterministically from the sample files in app/data/ASCII plus the edge
records defined here, so a rerun produces identical bytes (``--check`` verifies that). The export
files for CBIMPORT are built by an encoder written from spec/programs/CBEXPORT.md (BR-CBEXPORT-03..08),
independent of both the COBOL and the Java code, with the fixed timestamp ``2022-06-10 00:00:00.00``.

Usage: python3 make_datasets.py [--out DIR] [--check]
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SAMPLE = REPO / "app" / "data" / "ASCII"
OUT = HERE / "generated"
TIMESTAMP = "2022-06-10 00:00:00.00    "

_POS = "{ABCDEFGHI"
_NEG = "}JKLMNOPQR"


def records(name: str, length: int) -> list[str]:
    out = []
    for line in (SAMPLE / name).read_bytes().decode("latin-1").split("\n"):
        line = line.rstrip("\r")
        if line:
            out.append(line.ljust(length))
    return out


def write(path: Path, recs: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes("".join(r + "\n" for r in recs).encode("latin-1"))


def put(rec: str, offset: int, value: str) -> str:
    return rec[:offset] + value + rec[offset + len(value):]


# ---- zoned overpunch (EBCDIC sign convention used by the sample data) ----

def zoned(value: int, length: int) -> str:
    """S9(n) zoned with the sign overpunched on the last digit; value is unscaled."""
    digits = f"{abs(value):0{length}d}"
    if len(digits) > length:
        raise ValueError(f"{value} does not fit {length} digits")
    last = int(digits[-1])
    return digits[:-1] + (_NEG[last] if value < 0 else _POS[last])


def unzoned(field: str) -> int:
    last = field[-1]
    if last in _POS:
        return int(field[:-1] + str(_POS.index(last)))
    if last in _NEG:
        return -int(field[:-1] + str(_NEG.index(last)))
    return int(field)


# ---- CVEXPORT encoder (BR-CBEXPORT-03..08) ----

def binary(value: int, length: int) -> bytes:
    return value.to_bytes(length, "big", signed=True)


def packed(value: int, length: int, signed: bool = True) -> bytes:
    nibbles = length * 2 - 1
    digits = f"{abs(value):0{nibbles}d}"[-nibbles:]
    sign = 0xF if not signed else (0xD if value < 0 else 0xC)
    out = bytearray()
    for i in range(length):
        hi = int(digits[2 * i])
        lo = int(digits[2 * i + 1]) if 2 * i + 1 < nibbles else sign
        out.append(hi << 4 | lo)
    return bytes(out)


def header(kind: str, seq: int) -> bytearray:
    e = bytearray(b" " * 500)
    e[0:1] = kind.encode("latin-1")
    e[1:27] = TIMESTAMP.encode("latin-1")
    e[27:31] = seq.to_bytes(4, "big")
    e[31:35] = b"0001"
    e[35:40] = b"NORTH"
    return e


def b(s: str) -> bytes:
    return s.encode("latin-1")


def export_customer(r: str, seq: int) -> bytes:
    e = header("C", seq)
    e[40:44] = binary(int(r[0:9]), 4)
    e[44:364] = b(r[9:329])
    e[364:366] = packed(int(r[329:332]), 2, signed=False)
    return bytes(e)


def export_account(r: str, seq: int) -> bytes:
    e = header("A", seq)
    e[40:52] = b(r[0:12])
    e[52:59] = packed(unzoned(r[12:24]), 7)
    e[59:71] = b(r[24:36])
    e[71:78] = packed(unzoned(r[36:48]), 7)
    e[78:120] = b(r[48:90])
    e[120:128] = binary(unzoned(r[90:102]), 8)
    e[128:148] = b(r[102:122])
    return bytes(e)


def export_xref(r: str, seq: int) -> bytes:
    e = header("X", seq)
    e[40:65] = b(r[0:25])
    e[65:73] = binary(int(r[25:36]), 8)
    return bytes(e)


def export_transaction(r: str, seq: int) -> bytes:
    e = header("T", seq)
    e[40:172] = b(r[0:132])
    e[172:178] = packed(unzoned(r[132:143]), 6)
    e[178:182] = binary(int(r[143:152]), 4)
    e[182:360] = b(r[152:330])
    return bytes(e)


def export_card(r: str, seq: int) -> bytes:
    e = header("D", seq)
    e[40:56] = b(r[0:16])
    e[56:64] = binary(int(r[16:27]), 8)
    e[64:66] = binary(int(r[27:30]), 2)
    e[66:127] = b(r[30:91])
    return bytes(e)


def rp_data_1(accounts: list[str], discgrp: list[str]) -> list[str]:
    groups = {g[0:10] for g in discgrp}
    return [r[:102] + " " * 10 + r[102:112] + r[122:] if r[112:122].strip() == "" and r[102:112] in groups else r
            for r in accounts]


def export_file(masters: dict[str, list[str]]) -> list[bytes]:
    """The CBEXPORT output for the given masters (key order per section, continuous sequence)."""
    out: list[bytes] = []
    for name, enc in (("custdata.txt", export_customer), ("acctdata.txt", export_account),
                      ("cardxref.txt", export_xref), ("transact.txt", export_transaction),
                      ("carddata.txt", export_card)):
        for r in sorted(masters[name]):
            out.append(enc(r, len(out) + 1))
    return out


# ---- datasets ----

def sample_masters() -> dict[str, list[str]]:
    return {
        "custdata.txt": records("custdata.txt", 500),
        "acctdata.txt": records("acctdata.txt", 300),
        "cardxref.txt": records("cardxref.txt", 50),
        "carddata.txt": records("carddata.txt", 150),
        "transact.txt": records("dailytran.txt", 350),
        "discgrp.txt": records("discgrp.txt", 50),
    }


def edge_masters() -> dict[str, list[str]]:
    """Sample masters in reverse file order, with signed, zero, and maximum values in the numeric fields."""
    m = sample_masters()
    acct = sorted(m["acctdata.txt"])
    acct[0] = put(acct[0], 12, zoned(-19400, 12))           # negative balance -194.00
    acct[0] = put(acct[0], 90, zoned(-123451, 12))          # non-zero negative cycle debit
    acct[1] = put(acct[1], 12, zoned(99999999999, 12))      # balance 999,999,999.99
    acct[1] = put(acct[1], 78, zoned(7, 12))                # cycle credit 0.07
    acct[1] = put(acct[1], 90, zoned(999999999999, 12))     # cycle debit at the S9(10)V99 maximum
    acct[2] = put(acct[2], 24, zoned(-100, 12))             # negative credit limit -1.00
    acct[2] = put(acct[2], 36, zoned(-99999999999, 12))     # negative cash limit
    m["acctdata.txt"] = acct[::-1]
    tran = sorted(m["transact.txt"])
    tran[0] = put(tran[0], 132, zoned(-50477, 11))          # refund -504.77
    tran[1] = put(tran[1], 132, zoned(99999999999, 11))     # 999,999,999.99
    tran[2] = put(tran[2], 132, zoned(0, 11))               # zero amount
    tran[3] = put(tran[3], 143, "999999999")                # largest merchant ID
    tran[4] = put(tran[4], 143, "000000000")                # merchant ID zero
    m["transact.txt"] = tran[::-1]
    cust = sorted(m["custdata.txt"])
    cust[0] = put(cust[0], 329, "850")
    cust[1] = put(cust[1], 329, "300")
    m["custdata.txt"] = cust[::-1]
    m["cardxref.txt"] = sorted(m["cardxref.txt"])[::-1]
    m["carddata.txt"] = sorted(m["carddata.txt"])[::-1]
    return m


def import_edge() -> list[bytes]:
    """Export records out of sequence order, unknown types, and values at the field limits."""
    m = sample_masters()
    m["acctdata.txt"] = rp_data_1(m["acctdata.txt"], m["discgrp.txt"])
    recs = export_file(edge_masters() | {"acctdata.txt": rp_data_1(edge_masters()["acctdata.txt"], m["discgrp.txt"])})
    cust = put(sorted(m["custdata.txt"])[0], 0, "999999999")
    cust = put(cust, 329, "000")
    xref = put(put(sorted(m["cardxref.txt"])[0], 25, "99999999999"), 16, "999999999")
    card = put(put(sorted(m["carddata.txt"])[0], 16, "99999999999"), 27, "000")
    card2 = put(sorted(m["carddata.txt"])[1], 27, "999")
    extra = [export_customer(cust, 600), export_xref(xref, 601), export_card(card, 602), export_card(card2, 603)]
    unknown = []
    for kind, seq in (("Z", 123456789), (" ", 700), ("c", 999999999), ("X", 0)):
        e = header(kind, seq)
        unknown.append(bytes(e))
    unknown[3] = export_xref(sorted(m["cardxref.txt"])[1], 0)  # sequence 0 sorts first
    allrecs = recs + extra + unknown[:3] + [unknown[3]]
    # file order differs from key order: odd positions first, then even ones reversed
    return allrecs[1::2] + allrecs[0::2][::-1]


def build(out: Path) -> dict[str, Path]:
    if out.exists():
        shutil.rmtree(out)
    sets: dict[str, Path] = {}

    def ds(name: str) -> Path:
        d = out / name
        d.mkdir(parents=True)
        sets[name] = d
        return d

    d = ds("empty")
    for n in ("custdata.txt", "acctdata.txt", "cardxref.txt", "carddata.txt", "transact.txt", "discgrp.txt"):
        (d / n).write_bytes(b"")

    d = ds("listing-edge")
    em = edge_masters()
    for n in ("custdata.txt", "cardxref.txt", "carddata.txt"):
        write(d / n, em[n])

    d = ds("export-sample")
    for n, recs in sample_masters().items():
        write(d / n, recs)

    d = ds("export-edge")
    for n, recs in edge_masters().items():
        write(d / n, recs)

    d = ds("export-notran")
    for n, recs in sample_masters().items():
        write(d / n, [] if n == "transact.txt" else recs)

    m = sample_masters()
    m["acctdata.txt"] = rp_data_1(m["acctdata.txt"], m["discgrp.txt"])
    (ds("import-sample") / "expfile.dat").write_bytes(b"".join(export_file(m)))
    (ds("import-edge") / "expfile.dat").write_bytes(b"".join(import_edge()))
    (ds("import-empty") / "expfile.dat").write_bytes(b"")
    ds("import-missing")
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
        files = sorted(p.name for p in path.iterdir())
        print(f"{name}: {', '.join(files) if files else '(no files)'}")
    print(f"sha256 {first}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
