"""Report verification (schedule-cycle part c): CORPT00C report runs on the real Airflow against the reference.

With the stack running (make up: API, web app, Airflow with the report DAG), for ADMIN001 and USER0001:

1. In the browser (e2e/verification/report.spec.ts), request a Custom report for the range, wait until the
   carddemo_transaction_report run is Completed, and download the TRANREPT file.
2. Reference: GnuCOBOL CBTRN03C with its reference patches (parity/refrun.py), on the stack's own tables
   exported in their legacy layouts (transact, cardxref, trantype, trancatg) and the same range as DATEPARM.
   For a regular user the reference TRANSACT is filtered to the cards of the user's linked accounts (D-REPORT).
3. The downloaded file must equal the reference tranrept.txt byte for byte.

Transaction history: when the sample daily file has not been posted yet, the real daily cycle
(carddemo_daily_cycle, business date --business-date, default 2022-06-10) is triggered first through the
Airflow REST API with app/data/ASCII/dailytran.txt in its inbox; POSTTRAN stamps the posted rows with the run
time. The range defaults to the first to the last processing date found in the database.

Needs the environment of modernization/ops/stack-env.sh (CARDDEMO_DB, CARDDEMO_DB_PASSWORD, ports), cobc,
the built batch jar, and e2e/node_modules. Writes everything to --out (results.json, per-user folders).
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODERNIZATION = HERE.parent.parent
REPO = MODERNIZATION.parent
sys.path.insert(0, str(MODERNIZATION / "verification" / "parity"))

import compare  # noqa: E402
import refrun  # noqa: E402

USERS = ("ADMIN001", "USER0001")
EXPORTS = ("transact.txt", "cardxref.txt", "trantype.txt", "trancatg.txt")
ENV = os.environ
DB = ENV.get("CARDDEMO_DB", "carddemo")
AIRFLOW = f"http://127.0.0.1:{ENV.get('CARDDEMO_AIRFLOW_PORT', '8080')}"
API = f"http://127.0.0.1:{ENV.get('CARDDEMO_API_PORT', '8081')}"
JAR = Path(ENV.get("CARDDEMO_BATCH_JAR", MODERNIZATION / "backend" / "batch" / "target" / "carddemo-batch.jar"))
SQLCMD = ENV.get("SQLCMD", "/opt/mssql-tools18/bin/sqlcmd")


def sql(query: str) -> list[str]:
    p = subprocess.run([SQLCMD, "-S", "127.0.0.1", "-d", DB, "-U", "carddemo_app", "-C", "-h", "-1", "-W", "-b",
                        "-Q", f"SET NOCOUNT ON; {query}"], env={**ENV, "SQLCMDPASSWORD": ENV["CARDDEMO_DB_PASSWORD"]},
                       capture_output=True, text=True, check=False)
    if p.returncode != 0:
        raise RuntimeError(f"sqlcmd failed: {p.stdout}{p.stderr}")
    return [line.strip() for line in p.stdout.splitlines() if line.strip()]


def http(method: str, path: str, body: dict | None = None, token: str | None = None) -> tuple[int, dict]:
    req = urllib.request.Request(f"{AIRFLOW}{path}", method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json",
                                          **({"Authorization": f"Bearer {token}"} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {"detail": e.read().decode("utf-8", "replace")[:300]}


def airflow_token() -> str:
    user = ENV.get("AIRFLOW_USERNAME", "carddemo")
    pwfile = Path(ENV.get("AIRFLOW_PASSWORD_FILE", Path.home() / ".carddemo/airflow/simple_auth_manager_passwords.json.generated"))
    status, body = http("POST", "/auth/token", {"username": user, "password": json.loads(pwfile.read_text())[user]})
    if status // 100 != 2:
        raise RuntimeError(f"Airflow /auth/token answered {status}")
    return body["access_token"]


def ensure_history(business_date: str, log: list[str]) -> None:
    """Posts the sample daily file through the real daily-cycle DAG unless it has been posted already
    (bill payments alone do not count as history)."""
    daily = REPO / "app" / "data" / "ASCII" / "dailytran.txt"
    ids = sorted({line[0:16] for line in daily.read_text(encoding="latin-1").splitlines() if line.strip()})
    posted = int(sql("SELECT COUNT(*) FROM card_transaction WHERE tran_id IN ("
                     + ",".join(f"'{i}'" for i in ids) + ")")[0])
    if posted:
        log.append(f"{posted} transactions of the sample daily file already posted; daily cycle not triggered")
        return
    inbox = Path(ENV.get("CARDDEMO_INBOX", Path.home() / ".carddemo" / "inbox")) / business_date
    inbox.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(daily, inbox / "dailytran.txt")
    token = airflow_token()
    run_id = f"verify_report_{business_date}_{int(time.time())}"
    status, body = http("POST", "/api/v2/dags/carddemo_daily_cycle/dagRuns", {
        "dag_run_id": run_id, "logical_date": None, "conf": {"business_date": business_date}}, token)
    if status // 100 != 2:
        raise RuntimeError(f"daily cycle trigger answered {status}: {body}")
    state = "queued"
    for _ in range(180):
        time.sleep(5)
        status, body = http("GET", f"/api/v2/dags/carddemo_daily_cycle/dagRuns/{run_id}", token=token)
        state = body.get("state", state)
        if state in ("success", "failed"):
            break
    _, tasks = http("GET", f"/api/v2/dags/carddemo_daily_cycle/dagRuns/{run_id}/taskInstances", token=token)
    summary = ", ".join(f"{t['task_id']}={t['state']}" for t in tasks.get("task_instances", []))
    log.append(f"daily cycle {run_id} (business date {business_date}): {state}; tasks {summary}")
    if state != "success":
        raise RuntimeError(f"daily cycle run {run_id} ended {state}")


def browser_report(user: str, start: str, end: str, work: Path) -> dict:
    out = work / "downloaded-TRANREPT.txt"
    meta = work / "run.json"
    env = {**ENV, "REPORT_USER": user, "REPORT_START": start, "REPORT_END": end, "REPORT_OUT": str(out),
           "REPORT_META": str(meta), "REPORT_TEST_RESULTS": str(work / "playwright")}
    p = subprocess.run(["npx", "playwright", "test", "-c", "playwright.report.config.ts"], cwd=MODERNIZATION / "e2e",
                       env=env, capture_output=True, text=True, check=False)
    (work / "playwright.log").write_text(p.stdout + p.stderr)
    info = json.loads(meta.read_text()) if meta.exists() else {}
    info["playwright_exit"] = p.returncode
    return info


def export_tables(dest: Path) -> None:
    dest.mkdir(parents=True)
    logs = dest.parent / "jar-logs"
    logs.mkdir(exist_ok=True)
    p = subprocess.run(["java", "-Xmx512m", "-jar", str(JAR), "export", str(dest), "files=" + ",".join(EXPORTS)],
                       env={**ENV, "CARDDEMO_LOG_DIR": str(logs)}, capture_output=True, text=True, check=False)
    (dest.parent / "export.log").write_text(p.stdout + p.stderr)
    if p.returncode != 0:
        raise RuntimeError(f"export failed (exit {p.returncode}); see {dest.parent / 'export.log'}")


def linked_accounts(user: str) -> set[int]:
    return {int(a) for a in sql(f"SELECT account_id FROM user_account WHERE user_id = '{user}'")}


def reference(user: str, start: str, end: str, tables: Path, work: Path) -> Path:
    """CBTRN03C on the exported tables; a regular user's TRANSACT holds only the linked accounts' cards."""
    ds = work / "reference-input"
    ds.mkdir(parents=True)
    for n in EXPORTS:
        shutil.copyfile(tables / n, ds / n)
    role = sql(f"SELECT user_type FROM app_user WHERE user_id = '{user}'")
    if role != ["A"]:
        accounts = linked_accounts(user)
        cards = {r[0:16] for r in refrun.records(tables / "cardxref.txt", 50) if int(r[25:36]) in accounts}
        kept = [r for r in refrun.records(tables / "transact.txt", 350) if r[262:278] in cards]
        (ds / "transact.txt").write_bytes("".join(r + "\n" for r in kept).encode("latin-1"))
        (work / "scope.json").write_text(json.dumps({"linked_accounts": sorted(accounts), "cards": len(cards),
                                                     "transactions_kept": len(kept)}, indent=2) + "\n")
    (ds / "dateparm.txt").write_text(f"{start} {end}".ljust(80) + "\n")
    out = work / "reference"
    rc = refrun.Runner(work / "refrun-work").run("CBTRN03C", ds, out)
    if rc != 0:
        raise RuntimeError(f"reference CBTRN03C RC {rc}; see {out / 'sysout.txt'}")
    return out / "tranrept.txt"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, help="outputs (default: a new temporary directory); must be empty")
    ap.add_argument("--start", help="range start YYYY-MM-DD (default: first processing date in the database)")
    ap.add_argument("--end", help="range end YYYY-MM-DD (default: last processing date in the database)")
    ap.add_argument("--business-date", default="2022-06-10", help="daily cycle date used to create history if none")
    a = ap.parse_args(argv)
    root = (a.out or Path(tempfile.mkdtemp(prefix="verify-report-"))).resolve()
    if root.exists() and any(root.iterdir()):
        raise SystemExit(f"{root} is not empty")
    root.mkdir(parents=True, exist_ok=True)
    log: list[str] = []
    results: dict = {"database": DB, "api": API, "airflow": AIRFLOW, "users": {}}
    ok = True
    try:
        ensure_history(a.business_date, log)
        first, last = sql("SELECT CONVERT(CHAR(10), MIN(proc_ts), 23), CONVERT(CHAR(10), MAX(proc_ts), 23) "
                          "FROM card_transaction WHERE proc_ts IS NOT NULL")[0].split()
        start, end = a.start or first, a.end or last
        results["range"] = [start, end]
        rows = sql(f"SELECT COUNT(*) FROM card_transaction WHERE CAST(proc_ts AS DATE) BETWEEN '{start}' AND '{end}'")[0]
        log.append(f"range {start} to {end}: {rows} transactions in the database")
        for user in USERS:
            work = root / user
            work.mkdir()
            info = browser_report(user, start, end, work)
            findings = []
            if info.get("playwright_exit") != 0 or not (work / "downloaded-TRANREPT.txt").exists():
                findings.append(f"browser run failed (exit {info.get('playwright_exit')}); see {work / 'playwright.log'}")
            tables = work / "tables"
            export_tables(tables)
            ref = reference(user, start, end, tables, work)
            got = work / "downloaded-TRANREPT.txt"
            if got.exists():
                e, g = ref.read_bytes(), got.read_bytes()
                if e != g:
                    findings += compare.compare_file("tranrept.txt", compare._lines(ref), compare._lines(got)) or [
                        "files differ in bytes but not in records (line endings or trailing data)"]
                info.update(report_lines=len(g.splitlines()), reference_lines=len(e.splitlines()),
                            identical=e == g, bytes=len(g))
            info["findings"] = findings
            results["users"][user] = info
            ok &= not findings
            print(f"{'MATCH   ' if not findings else 'MISMATCH'} {user} run {info.get('runId')} "
                  f"states {' -> '.join(info.get('states', []))}: {info.get('report_lines', 0)} lines")
            for f in findings:
                print(f"    {f}")
    except Exception as e:  # a run that cannot complete is a failure
        ok = False
        results["error"] = str(e)
        print(f"FAILED: {e}")
    results["log"] = log
    results["passed"] = ok
    (root / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    for line in log:
        print(line)
    print(f"report verification: {'PASS' if ok else 'FAIL'}; outputs in {root}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
