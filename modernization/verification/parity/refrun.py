"""GnuCOBOL reference runner for batch parity (B-PARITY in decisions/ledger-batch-data.md).

Compiles the original COBOL program from app/cbl (or a patched copy under a temporary
directory; app/ is never written), loads a dataset directory of ASCII sample-format files
into GnuCOBOL sequential and indexed files, runs the program, and writes the outputs as
LF-terminated fixed-length records named like the sample files, so they can be compared with
the Java job's outputs and table exports.

JCL steps around a program that have no COBOL source (the SORT steps of TRANREPT and CREASTMT,
the COMBTRAN merge) are reproduced in Python by the functions in PREPARE and by combtran().
A program that takes a PARM (CBACT04C) is called through a generated driver that passes the
PARM as the z/OS LINKAGE area (halfword length + text); the PARM comes from the dataset's
parm.txt (first line) or the program default.

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
from collections.abc import Callable
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
    alt_key: tuple[int, int, bool] | None = None  # (offset, length, with duplicates)

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
    sources: tuple[str, ...] = ()  # source files in app/cbl, main program first (default <member>.cbl)
    parm: str | None = None  # default PARM; the program is then called through a PARM driver
    prepare: str | None = None  # PREPARE function deriving inputs the JCL builds before the step

    @property
    def source_files(self) -> tuple[str, ...]:
        return self.sources or (f"{self.member}.cbl",)


XREF = File("XREFFILE", "cardxref.txt", 50, 16)
ACCT = File("ACCTFILE", "acctdata.txt", 300, 11)
TCAT = File("TCATBALF", "tcatbal.txt", 50, 17)
DALY = File("DALYTRAN", "dailytran.txt", 350)
CARD = File("CARDFILE", "carddata.txt", 150, 16)
CUST = File("CUSTFILE", "custdata.txt", 500, 9)
TRAN = File("TRANSACT", "transact.txt", 350, 16)
EXPF = File("EXPFILE", "expfile.dat", 500, 4, key_offset=27, binary=True, missing_if_absent=True)

# Default PARM of JOB-INTCALC (app/jcl/INTCALC.jcl STEP15).
INTCALC_PARM = "2022071800"

PROGRAMS = {
    p.member if p.member != "CBSTM03A" else "CBSTM03A+CBSTM03B": p
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
        Program(
            "CBTRN01C",
            inputs=(DALY, CUST, XREF, CARD, ACCT, File("TRANFILE", "transact.txt", 350, 16)),
            outputs=(),
            unload=(),
            patches=("RP-CBTRN01C-1", "RP-CBTRN01C-2"),
        ),
        Program(
            "CBACT04C",
            inputs=(TCAT, File("XREFFILE", "cardxref.txt", 50, 16, (25, 11, True)), ACCT,
                    File("DISCGRP", "discgrp.txt", 50, 16)),
            outputs=(File("TRANSACT", "systran.txt", 350),),  # the SYSTRAN generation
            unload=("TRANSACT", "ACCTFILE"),
            patches=("RP-CBACT04C-1", "RP-CBACT04C-2"),
            data_patches=("RP-DATA-1",),
            parm=INTCALC_PARM,
        ),
        Program(
            "CBTRN03C",
            inputs=(File("TRANFILE", "tranrept-input.txt", 350), File("CARDXREF", "cardxref.txt", 50, 16),
                    File("TRANTYPE", "trantype.txt", 60, 2), File("TRANCATG", "trancatg.txt", 60, 6),
                    File("DATEPARM", "dateparm.txt", 80)),
            outputs=(File("TRANREPT", "tranrept.txt", 133),),
            unload=("TRANREPT",),
            patches=("RP-CBTRN03C-1", "RP-CBTRN03C-2", "RP-CBTRN03C-3"),
            prepare="TRANREPT-SORT",
        ),
        Program(
            "CBSTM03A",
            inputs=(File("TRNXFILE", "trxfl.txt", 350, 32), XREF, CUST, ACCT),
            outputs=(File("STMTFILE", "stmtfile.txt", 80), File("HTMLFILE", "htmlfile.txt", 100)),
            unload=("STMTFILE", "HTMLFILE"),
            patches=("RP-CBSTM03A-1", "RP-CBSTM03A-2", "RP-CBSTM03A-3", "RP-CBSTM03A-4"),
            data_patches=("RP-DATA-1",),
            sources=("CBSTM03A.CBL", "CBSTM03B.CBL"),
            prepare="CREASTMT-SORT",
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


def date_range(dateparm: list[str]) -> tuple[str, str]:
    """DATEPARM first record: start date X(10), one filler byte, end date X(10)."""
    rec = dateparm[0] if dateparm else " " * 80
    return rec[0:10], rec[11:21]


def tranrept_sort(dataset: Path, staged: dict[str, list[str]]) -> None:
    """JOB-TRANREPT STEP05R: unload TRANSACT (key order), INCLUDE processing date (305-314) within the
    DATEPARM range, SORT by card number (263-278). The range is the one CBTRN03C reads (Q-TRANREPT-03)."""
    start, end = date_range(staged.get("dateparm.txt", []))
    trans = sorted(records(dataset / "transact.txt", 350), key=lambda r: r[0:16])
    picked = [r for r in trans if start <= r[304:314] <= end]
    staged["tranrept-input.txt"] = sorted(picked, key=lambda r: r[262:278])


def creastmt_sort(dataset: Path, staged: dict[str, list[str]]) -> None:
    """JOB-CREASTMT STEP010/STEP020: SORT FIELDS=(263,16,CH,A,1,16,CH,A),
    OUTREC FIELDS=(1:263,16,17:1,262,279:279,50), REPRO into the TRXFL KSDS (key 32)."""
    trans = records(dataset / "transact.txt", 350)
    out = [(r[262:278] + r[0:262] + r[278:328]).ljust(350) for r in sorted(trans, key=lambda r: (r[262:278], r[0:16]))]
    staged["trxfl.txt"] = out


PREPARE: dict[str, Callable[[Path, dict[str, list[str]]], None]] = {
    "TRANREPT-SORT": tranrept_sort,
    "CREASTMT-SORT": creastmt_sort,
}


def combtran(backup: list[str], systran: list[str]) -> list[str]:
    """JOB-COMBTRAN STEP05R + STEP10: SORT FIELDS=(TRAN-ID,A) of TRANSACT.BKUP(0) and SYSTRAN(0), REPRO
    into TRANSACT (key = TRAN-ID). Equal keys keep input order (backup first)."""
    return sorted(backup + systran, key=lambda r: r[0:16])


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
{alt_select}               FILE STATUS IS KSDS-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD  SEQ-FILE.
       01  SEQ-REC                PIC X({reclen}).
       FD  KSDS-FILE.
       01  KSDS-REC.
{prefix}           05  KSDS-KEY           PIC X({keylen}).
{rest}
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
                       IF KSDS-STATUS NOT = '00' AND NOT = '02'
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

_PARM_DRIVER = """\
       IDENTIFICATION DIVISION.
       PROGRAM-ID. PARMDRV.
      * z/OS EXEC PGM=...,PARM=: the LINKAGE area is a halfword length
      * followed by the PARM text.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-ENV                 PIC X(100) VALUE SPACES.
       01  WS-PARM-AREA.
           05  WS-PARM-LENGTH     PIC S9(4) COMP.
           05  WS-PARM-TEXT       PIC X(100).
       PROCEDURE DIVISION.
           ACCEPT WS-ENV FROM ENVIRONMENT 'REFRUN_PARM'
           MOVE WS-ENV TO WS-PARM-TEXT
           MOVE FUNCTION LENGTH(FUNCTION TRIM(WS-ENV TRAILING))
             TO WS-PARM-LENGTH
           CALL '{member}' USING WS-PARM-AREA
           STOP RUN.
"""


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

    def _utility(self, kind: str, f: File) -> Path:
        rec, key, koff, alt = f.record_length, f.key_length or 0, f.key_offset, f.alt_key
        name = f"{kind}{rec}K{key}" + (f"O{koff}" if koff else "") + \
            (f"A{alt[0]}L{alt[1]}{'D' if alt[2] else ''}" if alt else "")
        exe = self.build / name
        if not exe.exists():
            body = _LOAD_BODY if kind == "LOAD" else _UNLOAD_BODY
            if alt:
                off, ln, dups = alt
                rest = (f"           05  FILLER             PIC X({off - koff - key}).\n" if off > koff + key else "") + \
                    f"           05  KSDS-ALT           PIC X({ln}).\n" + \
                    (f"           05  FILLER             PIC X({rec - off - ln})." if rec > off + ln else "")
                alt_select = "               ALTERNATE RECORD KEY IS KSDS-ALT" + (" WITH DUPLICATES" if dups else "") + "\n"
            else:
                rest = f"           05  FILLER             PIC X({rec - koff - key})." if rec > koff + key else ""
                alt_select = ""
            src = exe.with_suffix(".cbl")
            prefix = f"           05  FILLER             PIC X({koff}).\n" if koff else ""
            src.write_text(_LOADER.format(name=name, reclen=rec, keylen=key, rest=rest.rstrip("\n"),
                                          alt_select=alt_select, prefix=prefix, body=body))
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

    def sources(self, prog: Program) -> list[Path]:
        """The program sources to compile: the originals, or patched copies outside app/."""
        originals = [APP / "cbl" / s for s in prog.source_files]
        if not self.patched or not prog.patches:
            return originals
        root = Path(tempfile.mkdtemp(prefix=f"refsrc-{prog.member}-", dir=self.build))
        copies = []
        for original in originals:
            copy = root / "app" / "cbl" / original.name
            copy.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(original, copy)
            copies.append(copy)
        for patch in prog.patches:
            p = run(["patch", "-p1", "--no-backup-if-mismatch", "-d", str(root), "-i", str(PATCHES / f"{patch}.patch")], root)
            if p.returncode != 0:
                raise RuntimeError(f"{patch} does not apply:\n{p.stdout}{p.stderr}")
        return copies

    # Kept for callers of the single-source API.
    def source(self, prog: Program) -> Path:
        return self.sources(prog)[0]

    def compile(self, prog: Program) -> Path:
        exe = self.build / prog.member
        srcs = [str(s) for s in self.sources(prog)]
        if prog.parm is not None:
            driver = self.build / f"PARMDRV-{prog.member}.cbl"
            driver.write_text(_PARM_DRIVER.format(member=prog.member))
            srcs.insert(0, str(driver))
        cmd = [self.cobc, "-x", "-std=ibm", "-I", str(APP / "cpy"), "-o", str(exe), *srcs]
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
        if prog.prepare:
            PREPARE[prog.prepare](dataset, staged)
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
                for extra in self.workdir.glob(f"{f.dd}.*"):
                    extra.unlink()
                p = run([str(self._utility("LOAD", f))], self.build, {"DD_SEQFILE": str(seq), "DD_KSDSFILE": str(target)})
                if p.returncode != 0:
                    raise RuntimeError(f"loading {f.dd} failed:\n{p.stdout}{p.stderr}")
            else:
                shutil.copyfile(seq, target)
            env[f"DD_{f.dd}"] = str(target)
        for f in prog.outputs:
            (self.workdir / f.dd).unlink(missing_ok=True)
            env[f"DD_{f.dd}"] = str(self.workdir / f.dd)
        if prog.parm is not None:
            parm_file = dataset / "parm.txt"
            parm = parm_file.read_text().splitlines()[0].strip() if parm_file.exists() else prog.parm
            env["REFRUN_PARM"] = parm
        return env

    def unload(self, f: File, out: Path) -> None:
        src = self.workdir / f.dd
        if not src.exists():
            return
        if f.indexed:
            seq = self.build / f"{f.dd}.unl"
            p = run([str(self._utility("UNLD", f))], self.build, {"DD_SEQFILE": str(seq), "DD_KSDSFILE": str(src)})
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
    work = a.workdir or Path(tempfile.mkdtemp(prefix=f"refrun-{a.program.split('+')[0]}-"))
    rc = Runner(work, sign=a.sign, patched=not a.unpatched).run(a.program, a.dataset.resolve(), a.out.resolve())
    sys.stdout.write((a.out / "sysout.txt").read_text(encoding="latin-1"))
    print(f"RC={rc} outputs in {a.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
