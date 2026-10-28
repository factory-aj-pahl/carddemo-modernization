"""Data round-trip check (data-roundtrip): every owed file in owed-files.md loads and exports back.

For a fresh schema (carddemo-batch reset), loads the owed files with the jar's loader, checks that each
target table has one row per record, exports the tables with the jar's exporter, and compares the export
with the source byte for byte after only LF and right-pad normalization (plus the documented Q-ENT-02
group-ID move in acctdata.txt). Also checks overpunch spot values in the database and the USRSEC users.
The Db2 module seed files (DB2LTTYP.ctl, DB2LTCAT.ctl, SQL text) are checked on a second fresh schema:
loaded with `carddemo-batch loaddb2`, exported, and compared byte for byte with no normalization.
The owed set is the table in owed-files.md; a missing or unlisted file is a failure.

Needs CARDDEMO_DB_PASSWORD (source ~/.carddemo/secrets.env); the database is CARDDEMO_DB_PARITY
(default carddemo_test).
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
OWED_MD = HERE / "owed-files.md"
JAR = REPO / "modernization" / "backend" / "batch" / "target" / "carddemo-batch.jar"
SCANNED_DIRS = ("app/data/ASCII", "app/data/EBCDIC")

sys.path.insert(0, str(HERE.parent / "parity"))
import refrun  # noqa: E402  (RP-DATA-1, the Q-ENT-02 migration rule as a file transform)

_ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*([\d]+|n/a)\s*\|\s*([\d]+|n/a)\s*\|\s*([^|]+?)\s*\|\s*(owed|pending|not-owed)\s*\|"
                  r"\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|\s*$")

# Signed zoned spot values (entities.md DF-02, DF-03): (file, raw field, byte range, table, key column, column, value).
SPOTS = [
    ("dailytran.txt", "0000005047G", (132, 143), "card_transaction", ("tran_id", 0, 16), "amount", "504.77"),
    ("dailytran.txt", "0000009190}", (132, 143), "card_transaction", ("tran_id", 0, 16), "amount", "-919.00"),
    ("dailytran.txt", "0000000567P", (132, 143), "card_transaction", ("tran_id", 0, 16), "amount", "-56.77"),
    ("acctdata.txt", "00000001940{", (12, 24), "account", ("account_id", 0, 11), "current_balance", "194.00"),
    # S9(04)V99: 00150{ is 0015.00 (entities.md said 1.50; corrected there).
    ("discgrp.txt", "00150{", (16, 22), "disclosure_group", None, "int_rate", "15.00"),
]

# Db2 seed SQL files (JOB-CREADB21): loaddb2 parameter and target table.
DB2_SEEDS = {"DB2LTTYP.ctl": ("types", "transaction_type"), "DB2LTCAT.ctl": ("categories", "transaction_category")}

BCRYPT = re.compile(r"\$2[aby]\$\d\d\$[./A-Za-z0-9]{53}")


@dataclass
class Row:
    path: str
    records: int | None
    length: int | None
    table: str
    status: str
    owner: str
    notes: str

    @property
    def name(self) -> str:
        return Path(self.path).name


def owed_rows(md: Path = OWED_MD) -> list[Row]:
    rows = []
    for line in md.read_text().splitlines():
        m = _ROW.match(line)
        if m:
            rec, length = (None if v == "n/a" else int(v) for v in (m.group(2), m.group(3)))
            rows.append(Row(m.group(1), rec, length, m.group(4).strip("`"), m.group(5), m.group(6), m.group(7)))
    return rows


def source_records(path: Path, length: int) -> list[str]:
    """Source lines with only the allowed normalizations: CR stripped, right-padded to the record length."""
    return refrun.records(path, length)


def exported_records(path: Path, length: int) -> list[str]:
    data = path.read_bytes().decode("latin-1")
    if "\r" in data:
        raise ValueError(f"{path.name}: export contains CR")
    return [line.ljust(length) for line in data.split("\n")[:-1]] if data else []


def diff_records(name: str, exp: list[str], act: list[str], limit: int = 3) -> list[str]:
    findings = []
    if len(exp) != len(act):
        findings.append(f"{name}: export has {len(act)} records, expected {len(exp)}")
    shown = 0
    for i, (e, a) in enumerate(zip(exp, act)):
        if e != a:
            if shown < limit:
                col = next((j for j in range(min(len(e), len(a))) if e[j] != a[j]), min(len(e), len(a)))
                findings.append(f"{name}: record {i + 1} differs at byte {col + 1}: expected {e[max(0, col - 5):col + 15]!r}, "
                                f"exported {a[max(0, col - 5):col + 15]!r}")
            shown += 1
    if shown > limit:
        findings.append(f"{name}: {shown - limit} more differing records")
    return findings


class Db:
    def __init__(self, database: str):
        self.database = database
        self.sqlcmd = os.environ.get("SQLCMD") or shutil.which("sqlcmd") or "/opt/mssql-tools18/bin/sqlcmd"

    def query(self, sql: str) -> list[list[str]]:
        p = subprocess.run([self.sqlcmd, "-S", os.environ.get("CARDDEMO_DB_HOST", "127.0.0.1"), "-U", "carddemo_app",
                            "-C", "-d", self.database, "-h", "-1", "-W", "-s", "\x1f", "-b", "-Q", "SET NOCOUNT ON; " + sql],
                           env={**os.environ, "SQLCMDPASSWORD": os.environ["CARDDEMO_DB_PASSWORD"]},
                           capture_output=True, text=True, encoding="latin-1", check=False)
        if p.returncode != 0:
            raise RuntimeError(f"sqlcmd failed: {p.stdout}{p.stderr}")
        return [line.split("\x1f") for line in p.stdout.splitlines() if line.strip()]

    def scalar(self, sql: str) -> str:
        rows = self.query(sql)
        return rows[0][0] if rows else ""


class Jar:
    def __init__(self, database: str, logs: Path):
        if not JAR.exists():
            raise RuntimeError(f"{JAR} not found; run make -C modernization backend")
        self.env = {**os.environ, "CARDDEMO_DB": database, "CARDDEMO_LOG_DIR": str(logs)}

    def run(self, *args: str) -> str:
        p = subprocess.run([shutil.which("java") or "java", "-jar", str(JAR), "--spring.flyway.clean-disabled=false", *args],
                           env=self.env, capture_output=True, text=True, encoding="latin-1", check=False)
        if p.returncode != 0:
            raise RuntimeError(f"carddemo-batch {' '.join(args)} exited {p.returncode}:\n{p.stdout}{p.stderr}")
        return p.stdout


def staged_name(row: Row) -> str:
    # DALYTRAN is a job input file; its round trip goes through the identical TRANSACT layout (owed-files.md).
    return "transact.txt" if row.name == "dailytran.txt" else row.name


def check_listing(rows: list[Row]) -> list[str]:
    listed = {r.path for r in rows}
    findings = []
    for d in SCANNED_DIRS:
        for p in sorted((REPO / d).iterdir()):
            rel = p.relative_to(REPO).as_posix()
            if p.is_file() and p.name != ".gitkeep" and rel not in listed:
                findings.append(f"{rel}: not listed in owed-files.md")
    for r in rows:
        if not (REPO / r.path).exists():
            findings.append(f"{r.path}: listed ({r.status}) but missing")
    return findings


def check_usrsec(row: Row, db: Db) -> tuple[list[str], int]:
    data = (REPO / row.path).read_bytes()
    findings = []
    if len(data) % 80:
        findings.append(f"{row.name}: {len(data)} bytes is not a multiple of 80")
    src = [data[i:i + 80].decode("cp037") for i in range(0, len(data) - len(data) % 80, 80)]
    users = {r[0]: r for r in db.query("SELECT user_id, first_name, last_name, user_type, password_hash FROM app_user")}
    if len(users) != len(src):
        findings.append(f"{row.name}: app_user has {len(users)} rows, file has {len(src)} records")
    for i, rec in enumerate(src):
        uid = rec[0:8].strip()
        u = users.get(uid)
        if u is None:
            findings.append(f"{row.name}: record {i + 1} user {uid} not loaded")
            continue
        _, first, last, utype, pw_hash = (u + [""] * 5)[:5]
        rebuilt = uid.ljust(8) + first.ljust(20) + last.ljust(20) + rec[48:56] + utype.ljust(1) + " " * 23
        findings += [f.replace("exported", "rebuilt") for f in diff_records(row.name, [rec], [rebuilt])]
        if not BCRYPT.fullmatch(pw_hash) or pw_hash == rec[48:56].strip():
            findings.append(f"{row.name}: record {i + 1} user {uid} password is not stored as a BCrypt hash")
    return findings, len(users)


def check_db2_seeds(rows: list[Row], jar: Jar, db: Db, out: Path) -> tuple[dict[str, list[str]], dict[str, tuple[int, int]]]:
    """Fresh schema, load the seed SQL files, export them, compare bytes exactly; returns findings and counts."""
    jar.run("reset")
    loaded = dict(re.findall(r"^LOADED (\S+) (\d+)$",
                             jar.run("loaddb2", *[f"{DB2_SEEDS[r.name][0]}={REPO / r.path}" for r in rows]), re.M))
    jar.run("export", str(out), "files=" + ",".join(r.name for r in rows))
    findings: dict[str, list[str]] = {}
    counts: dict[str, tuple[int, int]] = {}
    for r in rows:
        f = findings.setdefault(r.path, [])
        n_loaded = int(loaded.get(r.name, -1))
        n_rows = int(db.scalar(f"SELECT COUNT(*) FROM {DB2_SEEDS[r.name][1]}"))
        counts[r.path] = (n_loaded, n_rows)
        if r.records is not None and n_loaded != r.records:
            f.append(f"loader reported {loaded.get(r.name, 'nothing')} rows, owed-files.md says {r.records}")
        if n_rows != n_loaded:
            f.append(f"{DB2_SEEDS[r.name][1]} has {n_rows} rows, loader reported {n_loaded}")
        exported = out / r.name
        if not exported.exists():
            f.append("not exported")
            continue
        src, act = (REPO / r.path).read_bytes(), exported.read_bytes()
        if src != act:
            pos = next((i for i in range(min(len(src), len(act))) if src[i] != act[i]), min(len(src), len(act)))
            f.append(f"export differs at byte {pos + 1}: expected {src[max(0, pos - 10):pos + 20]!r}, "
                     f"exported {act[max(0, pos - 10):pos + 20]!r} ({len(act)} vs {len(src)} bytes)")
    return findings, counts


def check_spots(stage: Path, db: Db) -> list[str]:
    findings = []
    for name, raw, (lo, hi), table, key, column, value in SPOTS:
        src = stage / ("transact.txt" if name == "dailytran.txt" else name)
        length = {"transact.txt": 350, "acctdata.txt": 300, "discgrp.txt": 50}[src.name]
        hits = [r for r in refrun.records(src, length) if r[lo:hi] == raw]
        if not hits:
            findings.append(f"spot {raw}: not found in {name} bytes {lo + 1}-{hi}")
            continue
        rec = hits[0]
        if key:
            kcol, ko, kl = key
            kval = rec[ko:ko + kl].strip()
            kval = f"'{kval}'" if kcol == "tran_id" else str(int(kval))
            where = f"{kcol} = {kval}"
        else:
            where = f"group_id = '{rec[0:10].strip()}' AND type_cd = '{rec[10:12]}' AND cat_cd = {int(rec[12:16])}"
        got = db.scalar(f"SELECT {column} FROM {table} WHERE {where}")
        ok = got != "" and Decimal(got) == Decimal(value)
        print(f"  spot {name} {raw} -> {table}.{column} = {got or 'no row'} (expected {value}) {'OK' if ok else 'FAIL'}")
        if not ok:
            findings.append(f"spot {name} {raw}: {table}.{column} is {got or 'missing'}, expected {value}")
    return findings


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, help="keep staged and exported files here (default: a temporary directory)")
    a = ap.parse_args(argv)
    if not os.environ.get("CARDDEMO_DB_PASSWORD"):
        print("CARDDEMO_DB_PASSWORD is not set (source ~/.carddemo/secrets.env)")
        return 2
    database = os.environ.get("CARDDEMO_DB_PARITY", "carddemo_test")
    root = (a.out or Path(tempfile.mkdtemp(prefix="verify-data-"))).resolve()
    stage, out, logs = root / "stage", root / "export", root / "logs"
    for d in (stage, out, logs):
        shutil.rmtree(d, ignore_errors=True)
        d.mkdir(parents=True)

    rows = owed_rows()
    owed = [r for r in rows if r.status == "owed"]
    failures: dict[str, list[str]] = {"listing": check_listing(rows)}
    if not owed:
        failures["listing"].append("owed-files.md has no owed rows")
    fixed = [r for r in owed if r.path.startswith("app/data/ASCII/")]
    usrsec = [r for r in owed if r.name.endswith("USRSEC.PS")]
    db2_seeds = [r for r in owed if r.name in DB2_SEEDS and r.path.startswith("app/app-transaction-type-db2/")]
    unknown = [r for r in owed if r not in fixed and r not in usrsec and r not in db2_seeds]
    failures["listing"] += [f"{r.path}: owed but verify_data.py has no check for it" for r in unknown]
    for r in fixed:
        if (REPO / r.path).exists():
            shutil.copyfile(REPO / r.path, stage / staged_name(r))

    db = Db(database)
    jar = Jar(database, logs)
    print(f"verify-data: database {database}, files in {root}")
    jar.run("reset")
    load_args = ["load", str(stage)] + [f"usrsec={REPO / r.path}" for r in usrsec[:1]]
    loaded = dict(re.findall(r"^LOADED (\S+) (\d+)$", jar.run(*load_args), re.M))
    jar.run("export", str(out), "files=" + ",".join(staged_name(r) for r in fixed))

    report = []
    for r in fixed:
        f = failures.setdefault(r.path, [])
        src_path = REPO / r.path
        if not src_path.exists():
            f.append("missing")
            report.append((r, None, None, None))
            continue
        exp = source_records(src_path, r.length)
        if r.name == "acctdata.txt":
            staged = {"acctdata.txt": exp}
            refrun.rp_data_1(REPO / "app/data/ASCII", staged)
            moved = sum(1 for x, y in zip(source_records(src_path, r.length), staged["acctdata.txt"]) if x != y)
            exp = staged["acctdata.txt"]
        else:
            moved = 0
        if r.records is not None and len(exp) != r.records:
            f.append(f"file has {len(exp)} records, owed-files.md says {r.records}")
        rows_in_table = int(db.scalar(f"SELECT COUNT(*) FROM {r.table}"))
        if rows_in_table != len(exp):
            f.append(f"{r.table} has {rows_in_table} rows, file has {len(exp)} records")
        if int(loaded.get(staged_name(r), -1)) != len(exp):
            f.append(f"loader reported {loaded.get(staged_name(r), 'nothing')} for {staged_name(r)}")
        exported = out / staged_name(r)
        act = exported_records(exported, r.length) if exported.exists() else []
        if not exported.exists():
            f.append("not exported")
        f += diff_records(r.name, exp, act)
        report.append((r, len(exp), rows_in_table, moved))

    for r in usrsec:
        f, n = check_usrsec(r, db)
        links = int(db.scalar("SELECT COUNT(*) FROM user_account"))
        failures[r.path] = f
        report.append((r, r.records, n, None))
        print(f"  USRSEC account links: {links}")

    print("  overpunch spot values:")
    failures["spots"] = check_spots(stage, db)

    if db2_seeds:
        seed_findings, seed_counts = check_db2_seeds(db2_seeds, jar, db, root / "export-db2")
        failures.update(seed_findings)
        for r in db2_seeds:
            report.append((r, seed_counts[r.path][0], seed_counts[r.path][1], None))

    print("\nPer-file results")
    print(f"{'file':<62} {'records':>7} {'rows':>5}  result")
    for r, nrec, nrows, moved in report:
        res = "OK" if not failures.get(r.path) else "FAIL"
        extra = f" (Q-ENT-02 rule changed {moved} records)" if moved else ""
        extra += " (staged as transact.txt)" if staged_name(r) != r.name else ""
        extra += " (Db2 seed SQL, fresh schema, exact bytes)" if r in db2_seeds else ""
        print(f"{r.path:<62} {nrec if nrec is not None else '-':>7} {nrows if nrows is not None else '-':>5}  {res}{extra}")
    for r in rows:
        if r.status == "pending":
            print(f"{r.path:<62} {'':>7} {'':>5}  PENDING ({r.owner})")
    not_owed = sum(1 for r in rows if r.status == "not-owed")
    print(f"{not_owed} files listed as not owed (reasons in owed-files.md)")

    all_findings = [f"{k}: {x}" for k, v in failures.items() for x in v]
    for f in all_findings:
        print("FAIL", f)
    ok = len(owed) - sum(1 for r in owed if failures.get(r.path))
    print(f"data round trip: {ok}/{len(owed)} owed files matched, {len(all_findings)} findings, "
          f"{sum(1 for r in rows if r.status == 'pending')} pending")
    return 0 if not all_findings else 1


if __name__ == "__main__":
    raise SystemExit(main())
