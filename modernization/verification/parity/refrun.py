"""GnuCOBOL reference runner for batch parity (B-PARITY in decisions/ledger-batch-data.md).

Compiles the original COBOL program from app/cbl (or a patched copy under a temporary
directory; app/ is never written), loads a dataset directory of ASCII sample-format files
into GnuCOBOL sequential and indexed files, runs the program, and writes the outputs as
LF-terminated fixed-length records named like the sample files, so they can be compared with
the Java job's outputs and table exports.

Output directory layout:
    sysout.txt   program DISPLAY output (compiler/runtime noise removed)
    rc.txt       process return code
    <name>.txt   one file per output or updated dataset, records in key order for indexed files
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
APP = REPO / "app"
PATCHES = HERE / "patches"
SAMPLE = APP / "data" / "ASCII"

_NOISE = ("_FORTIFY_SOURCE", "this is the location of the previous definition")


@dataclass(frozen=True)
class File:
    dd: str
    name: str  # file name in the dataset / output directory
    record_length: int
    key_length: int | None = None  # None = sequential
    key_offset: int = 0
    binary: bool = False  # fixed-length records without line terminators (COMP/COMP-3 content)
    missing_if_absent: bool = False  # absent from the dataset -> the DD names no file (open status 35)

    @property
    def indexed(self) -> bool:
        return self.key_length is not None


@dataclass(frozen=True)
class Program:
    member: str
    inputs: tuple[File, ...]
    outputs: tuple[File, ...]
    unload: tuple[str, ...]  # DDs whose final content is part of the result
    patches: tuple[str, ...] = ()
    data_patches: tuple[str, ...] = ()
    env: dict[str, str] = field(default_factory=dict)


XREF = File("XREFFILE", "cardxref.txt", 50, 16)
ACCT = File("ACCTFILE", "acctdata.txt", 300, 11)
TCAT = File("TCATBALF", "tcatbal.txt", 50, 17)
DALY = File("DALYTRAN", "dailytran.txt", 350)
CARD = File("CARDFILE", "carddata.txt", 150, 16)
CUST = File("CUSTFILE", "custdata.txt", 500, 9)
TRAN = File("TRANSACT", "transact.txt", 350, 16)
EXPF = File("EXPFILE", "expfile.dat", 500, 4, key_offset=27, binary=True, missing_if_absent=True)

PROGRAMS = {
    p.member: p
    for p in (
        Program(
            "CBTRN02C",
            inputs=(DALY, XREF, ACCT, TCAT),
            outputs=(File("TRANFILE", "transact.txt", 350, 16), File("DALYREJS", "dalyrejs.txt", 430)),
            unload=("TRANFILE", "ACCTFILE", "TCATBALF", "DALYREJS"),
            patches=("RP-CBTRN02C-1", "RP-CBTRN02C-2"),
            data_patches=("RP-DATA-1",),
        ),
        Program("CBACT02C", inputs=(CARD,), outputs=(), unload=(), patches=("RP-CBACT02C-1",)),
        Program("CBACT03C", inputs=(XREF,), outputs=(), unload=(), patches=("RP-CBACT03C-1", "RP-CBACT03C-2")),
        Program("CBCUS01C", inputs=(CUST,), outputs=(), unload=(), patches=("RP-CBCUS01C-1", "RP-CBCUS01C-2")),
        Program(
            "CBEXPORT",
            inputs=(CUST, ACCT, XREF, TRAN, CARD),
            outputs=(EXPF,),
            unload=("EXPFILE",),
            data_patches=("RP-DATA-1",),
        ),
        Program(
            "CBIMPORT",
            inputs=(EXPF,),
            outputs=(File("CUSTOUT", "custout.txt", 500), File("ACCTOUT", "acctout.txt", 300),
                     File("XREFOUT", "xrefout.txt", 50), File("TRNXOUT", "trnxout.txt", 350),
                     File("CARDOUT", "cardout.txt", 150), File("ERROUT", "errout.txt", 132)),
            unload=("CUSTOUT", "ACCTOUT", "XREFOUT", "TRNXOUT", "CARDOUT", "ERROUT"),
            patches=("RP-CBIMPORT-1", "RP-CBIMPORT-2"),
        ),
    )
}


def clean(text: str) -> str:
    return "".join(line + "\n" for line in text.splitlines() if not any(n in line for n in _NOISE))


def run(cmd: list[str], cwd: Path, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, cwd=cwd, env={**os.environ, **(env or {})}, capture_output=True, text=True,
                          encoding="latin-1", check=False)


def records(path: Path, length: int, binary: bool = False) -> list[str]:
    """Lines of an ASCII sample-format file as fixed-length records (CR stripped, short lines padded).

    A binary file is cut into fixed-length records instead (no line terminators)."""
    if not path.exists():
        return []
    if binary:
        data = path.read_bytes().decode("latin-1")
        if len(data) % length:
            raise ValueError(f"{path.name}: {len(data)} bytes is not a multiple of {length}")
        return [data[i:i + length] for i in range(0, len(data), length)]
    out = []
    for line in path.read_bytes().decode("latin-1").split("\n"):
        line = line.rstrip("\r")
        if not line:
            continue
        if len(line) > length:
            raise ValueError(f"{path.name}: record longer than {length} bytes")
        out.append(line.ljust(length))
    return out


def rp_data_1(dataset: Path, staged: dict[str, list[str]]) -> None:
    """RP-DATA-1 (Q-ENT-02): a group ID stored in the ZIP slot (bytes 103-112) moves to bytes 113-122."""
    groups = {r[0:10] for r in records(dataset / "discgrp.txt", 50)}
    accts = staged.get("acctdata.txt")
    if accts is None:
        return
    for i, r in enumerate(accts):
        if r[112:122].strip() == "" and r[102:112] in groups:
            accts[i] = r[:102] + " " * 10 + r[102:112] + r[122:]


DATA_PATCHES = {"RP-DATA-1": rp_data_1}

_LOADER = """\
       IDENTIFICATION DIVISION.
       PROGRAM-ID. {name}.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT SEQ-FILE ASSIGN TO SEQFILE
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS SEQ-STATUS.
           SELECT KSDS-FILE ASSIGN TO KSDSFILE
               ORGANIZATION IS INDEXED
               ACCESS MODE IS DYNAMIC
               RECORD KEY IS KSDS-KEY
               FILE STATUS IS KSDS-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  SEQ-FILE.
       01  SEQ-REC                PIC X({reclen}).
       FD  KSDS-FILE.
       01  KSDS-REC.
{prefix}           05  KSDS-KEY           PIC X({keylen}).
           05  FILLER             PIC X({rest}).
       WORKING-STORAGE SECTION.
       01  SEQ-STATUS             PIC XX VALUE '00'.
       01  KSDS-STATUS            PIC XX VALUE '00'.
       PROCEDURE DIVISION.
{body}
           GOBACK.
"""

# B-ABEND: the harness stands in for the Language Environment abend service. Off z/OS a CALL 'CEE3ABD'
# otherwise fails with "module not found" and exit code 1; the stub ends the run unit with exit code 99,
# the target's abend exit code, and displays nothing (the DISPLAY lines before it are the evidence).
_CEE3ABD = """\
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEE3ABD.
       PROCEDURE DIVISION.
           MOVE 99 TO RETURN-CODE
           STOP RUN.
"""

_LOAD_BODY = """\
           OPEN INPUT SEQ-FILE OUTPUT KSDS-FILE
           PERFORM UNTIL SEQ-STATUS NOT = '00'
               READ SEQ-FILE
                   AT END CONTINUE
                   NOT AT END
                       WRITE KSDS-REC FROM SEQ-REC
                       IF KSDS-STATUS NOT = '00'
                           DISPLAY 'LOAD STATUS ' KSDS-STATUS
                           MOVE 8 TO RETURN-CODE
                       END-IF
               END-READ
           END-PERFORM
           CLOSE SEQ-FILE KSDS-FILE"""

_UNLOAD_BODY = """\
           OPEN INPUT KSDS-FILE OUTPUT SEQ-FILE
           IF KSDS-STATUS NOT = '00'
               DISPLAY 'UNLOAD OPEN STATUS ' KSDS-STATUS
               MOVE 8 TO RETURN-CODE
               GOBACK
           END-IF
           PERFORM UNTIL KSDS-STATUS NOT = '00'
               READ KSDS-FILE NEXT
                   AT END CONTINUE
                   NOT AT END WRITE SEQ-REC FROM KSDS-REC
               END-READ
           END-PERFORM
           CLOSE SEQ-FILE KSDS-FILE"""


class Runner:
    def __init__(self, workdir: Path, cobc: str | None = None, sign: str = "EBCDIC", patched: bool = True):
        self.workdir = workdir
        self.build = workdir / ".build"
        self.build.mkdir(parents=True, exist_ok=True)
        self.cobc = cobc or os.environ.get("COBC") or shutil.which("cobc") or ""
        if not self.cobc:
            raise RuntimeError("GnuCOBOL (cobc) not found")
        self.sign = sign
        self.patched = patched

    def _utility(self, kind: str, rec: int, key: int, offset: int = 0) -> Path:
        name = f"{kind}{rec}K{key}" + (f"O{offset}" if offset else "")
        exe = self.build / name
        if not exe.exists():
            body = _LOAD_BODY if kind == "LOAD" else _UNLOAD_BODY
            src = exe.with_suffix(".cbl")
            prefix = f"           05  FILLER             PIC X({offset}).\n" if offset else ""
            src.write_text(_LOADER.format(name=name, reclen=rec, keylen=key, rest=rec - key - offset, body=body,
                                          prefix=prefix))
            p = run([self.cobc, "-x", "-o", str(exe), str(src)], self.build)
            if p.returncode != 0:
                raise RuntimeError(f"building {name} failed:\n{p.stdout}{p.stderr}")
        return exe

    def abend_stub(self) -> Path:
        """Directory holding the CEE3ABD module, for COB_LIBRARY_PATH."""
        lib = self.build / "lib"
        module = lib / "CEE3ABD.so"
        if not module.exists():
            lib.mkdir(exist_ok=True)
            src = lib / "CEE3ABD.cbl"
            src.write_text(_CEE3ABD)
            p = run([self.cobc, "-m", "-o", str(module), str(src)], lib)
            if p.returncode != 0:
                raise RuntimeError(f"building CEE3ABD stub failed:\n{p.stdout}{p.stderr}")
        return lib

    def source(self, prog: Program) -> Path:
        """The program source to compile: the original, or a patched copy outside app/."""
        original = APP / "cbl" / f"{prog.member}.cbl"
        if not self.patched or not prog.patches:
            return original
        root = Path(tempfile.mkdtemp(prefix=f"refsrc-{prog.member}-", dir=self.build))
        copy = root / "app" / "cbl" / original.name
        copy.parent.mkdir(parents=True)
        shutil.copy2(original, copy)
        for patch in prog.patches:
            p = run(["patch", "-p1", "--no-backup-if-mismatch", "-d", str(root), "-i", str(PATCHES / f"{patch}.patch")], root)
            if p.returncode != 0:
                raise RuntimeError(f"{patch} does not apply:\n{p.stdout}{p.stderr}")
        return copy

    def compile(self, prog: Program) -> Path:
        exe = self.build / prog.member
        cmd = [self.cobc, "-x", "-std=ibm", "-I", str(APP / "cpy"), "-o", str(exe), str(self.source(prog))]
        if self.sign:
            cmd.insert(3, f"-fsign={self.sign}")
        p = run(cmd, REPO)
        if p.returncode != 0:
            raise RuntimeError(f"compiling {prog.member} failed:\n{p.stdout}{p.stderr}")
        return exe

    def stage(self, prog: Program, dataset: Path) -> dict[str, str]:
        staged = {f.name: records(dataset / f.name, f.record_length, f.binary) for f in prog.inputs}
        if self.patched:
            for dp in prog.data_patches:
                DATA_PATCHES[dp](dataset, staged)
        env = {}
        for f in prog.inputs:
            target = self.workdir / f.dd
            if f.missing_if_absent and not (dataset / f.name).exists():
                target.unlink(missing_ok=True)
                env[f"DD_{f.dd}"] = str(target)
                continue
            seq = self.build / f"{f.dd}.seq"
            seq.write_bytes("".join(staged[f.name]).encode("latin-1"))
            if f.indexed:
                target.unlink(missing_ok=True)
                p = run([str(self._utility("LOAD", f.record_length, f.key_length, f.key_offset))], self.build,
                        {"DD_SEQFILE": str(seq), "DD_KSDSFILE": str(target)})
                if p.returncode != 0:
                    raise RuntimeError(f"loading {f.dd} failed:\n{p.stdout}{p.stderr}")
            else:
                shutil.copyfile(seq, target)
            env[f"DD_{f.dd}"] = str(target)
        for f in prog.outputs:
            (self.workdir / f.dd).unlink(missing_ok=True)
            env[f"DD_{f.dd}"] = str(self.workdir / f.dd)
        return env

    def unload(self, f: File, out: Path) -> None:
        src = self.workdir / f.dd
        if not src.exists():
            return
        if f.indexed:
            seq = self.build / f"{f.dd}.unl"
            p = run([str(self._utility("UNLD", f.record_length, f.key_length, f.key_offset))], self.build,
                    {"DD_SEQFILE": str(seq), "DD_KSDSFILE": str(src)})
            if p.returncode != 0:
                raise RuntimeError(f"unloading {f.dd} failed:\n{p.stdout}{p.stderr}")
        else:
            seq = src
        data = seq.read_bytes().decode("latin-1")
        n = f.record_length
        if len(data) % n:
            raise RuntimeError(f"{f.dd}: {len(data)} bytes is not a multiple of {n}")
        recs = [data[i:i + n] for i in range(0, len(data), n)]
        sep = "" if f.binary else "\n"
        (out / f.name).write_bytes("".join(r + sep for r in recs).encode("latin-1"))

    def run(self, member: str, dataset: Path, out: Path) -> int:
        prog = PROGRAMS[member]
        exe = self.compile(prog)
        env = {**self.stage(prog, dataset), "COB_LIBRARY_PATH": str(self.abend_stub()), **prog.env}
        p = run([str(exe)], self.workdir, env)
        out.mkdir(parents=True, exist_ok=True)
        (out / "sysout.txt").write_text(clean(p.stdout + p.stderr), encoding="latin-1")
        (out / "rc.txt").write_text(f"{p.returncode}\n")
        files = {f.dd: f for f in prog.inputs + prog.outputs}
        for dd in prog.unload:
            self.unload(files[dd], out)
        return p.returncode


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("program", choices=sorted(PROGRAMS))
    ap.add_argument("--dataset", type=Path, default=SAMPLE, help="directory of sample-format input files")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--workdir", type=Path, help="scratch directory (default: a new temporary directory)")
    ap.add_argument("--sign", default="EBCDIC", help="-fsign value; empty string for the cobc default (ASCII)")
    ap.add_argument("--unpatched", action="store_true", help="run the original program without reference patches")
    a = ap.parse_args(argv)
    work = a.workdir or Path(tempfile.mkdtemp(prefix=f"refrun-{a.program}-"))
    rc = Runner(work, sign=a.sign, patched=not a.unpatched).run(a.program, a.dataset.resolve(), a.out.resolve())
    sys.stdout.write((a.out / "sysout.txt").read_text(encoding="latin-1"))
    print(f"RC={rc} outputs in {a.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
