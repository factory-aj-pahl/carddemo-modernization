"""GnuCOBOL harness: syntax-check batch programs and run them on the sample data.

Online CICS programs need a CICS region, and Db2 or IMS programs need their
precompilers, so only plain batch COBOL is compiled here. Datasets are built
from app/data/ASCII: sequential files become fixed-length records and KSDS
files are loaded into GnuCOBOL indexed files with the same primary key.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from .source import REPO_ROOT, Repository, SourceFile

DATA_DIR = REPO_ROOT / "app" / "data" / "ASCII"

# The distro cobc passes -D_FORTIFY_SOURCE while gcc also defines it; the
# resulting warnings are harmless noise.
_NOISE = ("_FORTIFY_SOURCE", "this is the location of the previous definition")


@dataclass(frozen=True)
class Dataset:
    dd: str
    source: str
    record_length: int
    copybook: str
    key_length: int | None = None

    @property
    def indexed(self) -> bool:
        return self.key_length is not None


DATASETS = {
    d.dd: d
    for d in (
        Dataset("ACCTFILE", "acctdata.txt", 300, "CVACT01Y", key_length=11),
        Dataset("CARDFILE", "carddata.txt", 150, "CVACT02Y", key_length=16),
        Dataset("XREFFILE", "cardxref.txt", 50, "CVACT03Y", key_length=16),
        Dataset("CUSTFILE", "custdata.txt", 500, "CVCUS01Y", key_length=9),
        Dataset("TCATBALF", "tcatbal.txt", 50, "CVTRA01Y", key_length=17),
        Dataset("DALYTRAN", "dailytran.txt", 350, "CVTRA06Y"),
    )
}


@dataclass(frozen=True)
class BatchJob:
    program: str
    description: str
    inputs: tuple[str, ...]
    outputs: tuple[str, ...] = ()


JOBS = {
    j.program: j
    for j in (
        BatchJob("CBACT02C", "List every card record (READCARD job)", ("CARDFILE",)),
        BatchJob("CBACT03C", "List every card cross-reference record (READXREF job)", ("XREFFILE",)),
        BatchJob("CBCUS01C", "List every customer record (READCUST job)", ("CUSTFILE",)),
        BatchJob(
            "CBTRN02C",
            "Post daily transactions (POSTTRAN job)",
            ("DALYTRAN", "XREFFILE", "ACCTFILE", "TCATBALF"),
            ("TRANFILE", "DALYREJS"),
        ),
    )
}


@dataclass
class JobResult:
    job: BatchJob
    workdir: Path
    returncode: int
    output: str
    files: dict[str, Path] = field(default_factory=dict)


class CobcNotFoundError(RuntimeError):
    pass


def find_cobc() -> str:
    cobc = os.environ.get("COBC") or shutil.which("cobc")
    if not cobc:
        raise CobcNotFoundError("GnuCOBOL (cobc) not found; install it with `sudo apt-get install gnucobol`")
    return cobc


def clean_output(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not any(n in line for n in _NOISE))


def _include_args(repo: Repository, source: SourceFile) -> list[str]:
    args = []
    for d in repo.include_dirs(source.module):
        args += ["-I", str(d)]
    return args


def syntax_candidates(repo: Repository) -> list[SourceFile]:
    """Batch programs without EXEC CICS, EXEC SQL, or EXEC DLI, which GnuCOBOL can compile directly."""
    return [f for f in repo.programs if f.is_batch and not f.uses_sql and "EXEC DLI" not in f.upper_code]


def syntax_check(repo: Repository, source: SourceFile, cobc: str) -> subprocess.CompletedProcess[str]:
    cmd = [cobc, "-fsyntax-only", "-std=ibm", *_include_args(repo, source), str(source.path)]
    proc = subprocess.run(cmd, check=False, cwd=REPO_ROOT, capture_output=True, text=True)
    proc.stdout = clean_output(proc.stdout + proc.stderr)
    return proc


def _run(cmd: list[str], cwd: Path, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    full_env = {**os.environ, **(env or {})}
    proc = subprocess.run(cmd, check=False, cwd=cwd, capture_output=True, text=True, env=full_env)
    proc.stdout = clean_output(proc.stdout + proc.stderr)
    return proc


def fixed_records(dataset: Dataset) -> bytes:
    """Convert an ASCII sample file into fixed-length records, padding trimmed trailing FILLER."""
    text = (DATA_DIR / dataset.source).read_bytes().decode("latin-1")
    records = [line.rstrip("\r") for line in text.split("\n") if line.rstrip("\r")]
    out = bytearray()
    for record in records:
        if len(record) > dataset.record_length:
            raise ValueError(f"{dataset.source}: record longer than {dataset.record_length} bytes")
        out += record.ljust(dataset.record_length).encode("latin-1")
    return bytes(out)


_LOADER = """\
       IDENTIFICATION DIVISION.
       PROGRAM-ID. KSDSLOAD.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT IN-FILE ASSIGN TO INFILE
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS IN-STATUS.
           SELECT OUT-FILE ASSIGN TO OUTFILE
               ORGANIZATION IS INDEXED
               ACCESS MODE IS DYNAMIC
               RECORD KEY IS OUT-KEY
               FILE STATUS IS OUT-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  IN-FILE.
       01  IN-REC                 PIC X({reclen}).
       FD  OUT-FILE.
       01  OUT-REC.
           05  OUT-KEY            PIC X({keylen}).
           05  FILLER             PIC X({rest}).
       WORKING-STORAGE SECTION.
       01  IN-STATUS              PIC XX VALUE '00'.
       01  OUT-STATUS             PIC XX.
       PROCEDURE DIVISION.
           OPEN INPUT IN-FILE OUTPUT OUT-FILE
           PERFORM UNTIL IN-STATUS NOT = '00'
               READ IN-FILE
                   AT END CONTINUE
                   NOT AT END
                       WRITE OUT-REC FROM IN-REC
                       IF OUT-STATUS NOT = '00'
                           DISPLAY 'KSDSLOAD WRITE STATUS ' OUT-STATUS
                                   ' KEY ' OUT-KEY
                           MOVE 8 TO RETURN-CODE
                       END-IF
               END-READ
           END-PERFORM
           CLOSE IN-FILE OUT-FILE
           GOBACK.
"""


def _loader(workdir: Path, cobc: str, dataset: Dataset) -> Path:
    if dataset.key_length is None:
        raise ValueError(f"{dataset.dd} is sequential and needs no KSDS loader")
    name = f"ksdsload_{dataset.record_length}_{dataset.key_length}"
    exe = workdir / ".build" / name
    if exe.exists():
        return exe
    exe.parent.mkdir(parents=True, exist_ok=True)
    src = exe.with_suffix(".cbl")
    src.write_text(
        _LOADER.format(
            reclen=dataset.record_length, keylen=dataset.key_length, rest=dataset.record_length - dataset.key_length
        )
    )
    proc = _run([cobc, "-x", "-o", str(exe), str(src)], workdir)
    if proc.returncode != 0:
        raise RuntimeError(f"failed to build KSDS loader:\n{proc.stdout}")
    return exe


def prepare_dataset(workdir: Path, cobc: str, dataset: Dataset) -> Path:
    target = workdir / dataset.dd
    if not dataset.indexed:
        target.write_bytes(fixed_records(dataset))
        return target
    staged = workdir / ".build" / f"{dataset.dd}.seq"
    staged.parent.mkdir(parents=True, exist_ok=True)
    staged.write_bytes(fixed_records(dataset))
    proc = _run([str(_loader(workdir, cobc, dataset))], workdir, {"DD_INFILE": str(staged), "DD_OUTFILE": str(target)})
    if proc.returncode != 0:
        raise RuntimeError(f"loading {dataset.dd} failed:\n{proc.stdout}")
    return target


def compile_program(repo: Repository, source: SourceFile, workdir: Path, cobc: str) -> Path:
    exe = workdir / ".build" / source.member
    exe.parent.mkdir(parents=True, exist_ok=True)
    proc = _run([cobc, "-x", "-std=ibm", *_include_args(repo, source), "-o", str(exe), str(source.path)], REPO_ROOT)
    if proc.returncode != 0:
        raise RuntimeError(f"compiling {source.rel} failed:\n{proc.stdout}")
    return exe


def run_job(program: str, workdir: Path, repo: Repository | None = None) -> JobResult:
    repo = repo or Repository()
    job = JOBS.get(program.upper())
    if job is None:
        raise KeyError(f"no local batch job for {program}; choose one of {', '.join(sorted(JOBS))}")
    cobc = find_cobc()
    workdir.mkdir(parents=True, exist_ok=True)
    source = next(f for f in repo.programs if f.member == job.program)
    exe = compile_program(repo, source, workdir, cobc)
    env = {}
    for dd in job.inputs:
        env[f"DD_{dd}"] = str(prepare_dataset(workdir, cobc, DATASETS[dd]))
    for dd in job.outputs:
        (workdir / dd).unlink(missing_ok=True)
        env[f"DD_{dd}"] = str(workdir / dd)
    proc = _run([str(exe)], workdir, env)
    files = {dd: workdir / dd for dd in job.inputs + job.outputs if (workdir / dd).exists()}
    return JobResult(job, workdir, proc.returncode, proc.stdout, files)
