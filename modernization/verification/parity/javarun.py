"""Java runner for batch parity: the counterpart of refrun.py for the Spring Batch jar.

Resets a scratch database, loads a dataset directory with the jar's loader, runs the job, and
writes the same output directory layout as refrun.py (sysout.txt, rc.txt, <name>.txt), so
compare.py can judge the pair. The database comes from CARDDEMO_DB (default carddemo_test);
CARDDEMO_DB_PASSWORD must be set (source ~/.carddemo/secrets.env).
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
JAR = REPO / "modernization" / "backend" / "batch" / "target" / "carddemo-batch.jar"
SAMPLE = REPO / "app" / "data" / "ASCII"


@dataclass(frozen=True)
class Job:
    command: str
    inputs: dict[str, str]  # job parameter -> dataset file name
    outputs: dict[str, str]  # job parameter -> output file name
    exports: tuple[str, ...]  # tables exported after the run, by legacy file name
    renames: dict[str, str] = field(default_factory=dict)  # exported legacy file name -> output file name
    skip_load: tuple[str, ...] = ()  # dataset files the job's reference does not read from a loaded table
    params: Callable[[Path, str], list[str]] | None = None  # extra job parameters from the dataset


def business_date(dataset: Path, default: str) -> list[str]:
    return [f"businessDate={default}"]


def intcalc_date(dataset: Path, default: str) -> list[str]:
    """The reference PARM (parm.txt, default 2022071800) is YYYYMMDD + 2 digits: the same business date."""
    f = dataset / "parm.txt"
    parm = f.read_text().splitlines()[0].strip() if f.exists() else "2022071800"
    return [f"businessDate={parm[0:4]}-{parm[4:6]}-{parm[6:8]}"]


def report_range(dataset: Path, default: str) -> list[str]:
    """DATEPARM: start date X(10), one filler byte, end date X(10) (the range CBTRN03C reads)."""
    f = dataset / "dateparm.txt"
    rec = f.read_text().splitlines()[0] if f.exists() else ""
    return [f"startDate={rec[0:10].strip()}", f"endDate={rec[11:21].strip()}"]


JOBS = {
    "CBTRN02C": Job(
        "POSTTRAN",
        inputs={"dailyFile": "dailytran.txt"},
        outputs={"rejectsFile": "dalyrejs.txt"},
        exports=("transact.txt", "acctdata.txt", "tcatbal.txt"),
        params=business_date,
    ),
    "CBTRN01C": Job("TRANVERIFY", inputs={"dailyFile": "dailytran.txt"}, outputs={}, exports=()),
    # The reference writes only the new interest rows (SYSTRAN), so the dataset's transactions are not loaded.
    "CBACT04C": Job(
        "INTCALC",
        inputs={},
        outputs={},
        exports=("transact.txt", "acctdata.txt"),
        renames={"transact.txt": "systran.txt"},
        skip_load=("transact.txt",),
        params=intcalc_date,
    ),
    "CBTRN03C": Job("TRANREPT", inputs={}, outputs={"reportFile": "tranrept.txt"}, exports=(), params=report_range),
    "CBSTM03A+CBSTM03B": Job(
        "CREASTMT",
        inputs={},
        outputs={"stmtFile": "stmtfile.txt", "htmlFile": "htmlfile.txt"},
        exports=(),
        params=business_date,
    ),
}

class JavaRunner:
    def __init__(self, jar: Path = JAR, database: str | None = None, business_date: str = "2022-06-10"):
        if not jar.exists():
            raise RuntimeError(f"{jar} not found; build with mvn -f modernization/backend package")
        if not os.environ.get("CARDDEMO_DB_PASSWORD"):
            raise RuntimeError("CARDDEMO_DB_PASSWORD is not set")
        self.jar = jar
        self.database = database or os.environ.get("CARDDEMO_DB_PARITY", "carddemo_test")
        self.business_date = business_date
        self.java = shutil.which("java") or "java"

    def jar_run(self, *args: str, check: bool = True, log_dir: Path) -> subprocess.CompletedProcess[str]:
        env = {**os.environ, "CARDDEMO_DB": self.database, "CARDDEMO_LOG_DIR": str(log_dir)}
        p = subprocess.run([self.java, "-jar", str(self.jar), "--spring.flyway.clean-disabled=false", *args],
                           env=env, capture_output=True, text=True, encoding="latin-1", check=False)
        if check and p.returncode != 0:
            raise RuntimeError(f"carddemo-batch {' '.join(args)} exited {p.returncode}:\n{p.stdout}{p.stderr}")
        return p

    def run(self, member: str, dataset: Path, out: Path) -> int:
        job = JOBS[member]
        out.mkdir(parents=True, exist_ok=True)
        logs = Path(tempfile.mkdtemp(prefix=f"javarun-{member.split('+')[0]}-"))
        load_dir = dataset
        if job.skip_load:
            load_dir = logs / "load"
            load_dir.mkdir()
            for f in dataset.iterdir():
                if f.is_file() and f.name not in job.skip_load:
                    shutil.copyfile(f, load_dir / f.name)
        self.jar_run("reset", log_dir=logs)
        self.jar_run("load", str(load_dir), log_dir=logs)
        params = job.params(dataset, self.business_date) if job.params else []
        params += [f"{k}={dataset / v}" for k, v in job.inputs.items()]
        params += [f"{k}={out / v}" for k, v in job.outputs.items()]
        p = self.jar_run(job.command, *params, check=False, log_dir=logs)
        (out / "sysout.txt").write_text(p.stdout, encoding="latin-1")
        (out / "rc.txt").write_text(f"{p.returncode}\n")
        if p.stderr:
            (logs / "stderr.log").write_text(p.stderr, encoding="latin-1")
            print(f"stderr kept in {logs / 'stderr.log'}", file=sys.stderr)
        if job.exports:
            self.jar_run("export", str(out), "files=" + ",".join(job.exports), log_dir=logs)
        for exported, name in job.renames.items():
            (out / exported).replace(out / name)
        return p.returncode

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("program", choices=sorted(JOBS))
    ap.add_argument("--dataset", type=Path, default=SAMPLE)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--business-date", default="2022-06-10")
    a = ap.parse_args(argv)
    rc = JavaRunner(business_date=a.business_date).run(a.program, a.dataset.resolve(), a.out.resolve())
    sys.stdout.write((a.out / "sysout.txt").read_text(encoding="latin-1"))
    print(f"RC={rc} outputs in {a.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
