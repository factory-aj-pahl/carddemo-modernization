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
from dataclasses import dataclass
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


JOBS = {
    "CBTRN02C": Job(
        "POSTTRAN",
        inputs={"dailyFile": "dailytran.txt"},
        outputs={"rejectsFile": "dalyrejs.txt"},
        exports=("transact.txt", "acctdata.txt", "tcatbal.txt"),
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
        logs = Path(tempfile.mkdtemp(prefix=f"javarun-{member}-"))
        self.jar_run("reset", log_dir=logs)
        self.jar_run("load", str(dataset), log_dir=logs)
        params = [f"businessDate={self.business_date}"]
        params += [f"{k}={dataset / v}" for k, v in job.inputs.items()]
        params += [f"{k}={out / v}" for k, v in job.outputs.items()]
        p = self.jar_run(job.command, *params, check=False, log_dir=logs)
        (out / "sysout.txt").write_text(p.stdout, encoding="latin-1")
        (out / "rc.txt").write_text(f"{p.returncode}\n")
        if p.stderr:
            (logs / "stderr.log").write_text(p.stderr, encoding="latin-1")
            print(f"stderr kept in {logs / 'stderr.log'}", file=sys.stderr)
        self.jar_run("export", str(out), "files=" + ",".join(job.exports), log_dir=logs)
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
