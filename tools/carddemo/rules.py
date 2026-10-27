"""Lint rules for CardDemo mainframe sources.

Rule IDs are stable; docs/static-analysis.md describes each one.
"""

from __future__ import annotations

import hashlib
import re
import subprocess
from collections.abc import Callable, Iterable
from pathlib import Path

from . import procedure
from .copybook import DataItem, parse_records
from .duplicates import find_duplicates
from .findings import Finding
from .source import BASE_MODULE, MAINFRAME_SUFFIXES, Repository, is_system_copybook, strip_literals

MAX_RECORD_COLUMNS = 80
MAX_SOURCE_LINES = 5000
MAX_PARAGRAPH_COMPLEXITY = 25
MAX_TRACKED_FILE_BYTES = 1024 * 1024

_MEMBER_RE = re.compile(r"^[A-Z#$@][A-Z0-9#$@]{0,7}$")
_TODO_RE = re.compile(r"\b(TODO|FIXME|HACK)\b")
_TODO_OK_RE = re.compile(r"\b(TODO|FIXME|HACK)\s*\((#\d+|[A-Z][A-Z0-9]+-\d+)\)")
_PII_NAME_RE = re.compile(r"(SSN|CARD-NUM|CARD-NO|CVV|PASSWORD|PWD|PASSWD|GOVT-ISSUED|DOB-|FICO)")
_TOKEN_RE = re.compile(r"[A-Z0-9][A-Z0-9-]*")
_STATEMENT_START_RE = re.compile(
    r"^\s*(MOVE|PERFORM|IF|ELSE|END-|DISPLAY|CALL|READ|WRITE|REWRITE|ADD|SUBTRACT|COMPUTE|"
    r"EVALUATE|WHEN|GO|GOBACK|STOP|EXEC|SET|INITIALIZE|STRING|OPEN|CLOSE|CONTINUE|EXIT|INSPECT)\b"
)
# These files define or test the debt-marker rule, so they contain the markers as data.
_DEBT_MARKER_EXEMPT = {"tools/carddemo/rules.py", "tests/unit/test_rules.py"}
_TEXT_SUFFIXES = {".py", ".sh", ".awk", ".md", ".yml", ".yaml", ".toml", ".cfg", ".txt", ".template"}

Rule = Callable[[Repository], Iterable[Finding]]


def _line_key(text: str) -> str:
    return hashlib.sha1(text.strip().encode("latin-1")).hexdigest()[:12]


def check_record_format(repo: Repository) -> Iterable[Finding]:
    """CD001 tab characters and CD002 records wider than the 80-byte dataset LRECL."""
    for f in repo.files:
        for line in f.lines:
            if "\t" in line.raw:
                yield Finding(
                    "CD001",
                    f.rel,
                    line.number,
                    "tab character; mainframe datasets need spaces in fixed columns",
                    key=_line_key(line.raw),
                )
            if len(line.raw) > MAX_RECORD_COLUMNS:
                yield Finding(
                    "CD002",
                    f.rel,
                    line.number,
                    f"record is {len(line.raw)} columns; upload to an FB 80 dataset truncates it",
                    key=_line_key(line.raw),
                )


def check_naming(repo: Repository) -> Iterable[Finding]:
    """CD010 PDS member names and CD011 PROGRAM-ID matching the member name."""
    for f in repo.files:
        if not _MEMBER_RE.match(f.path.stem):
            yield Finding(
                "CD010",
                f.rel,
                1,
                f"member name '{f.path.stem}' must be 1-8 uppercase characters (A-Z, 0-9, #, $, @)",
                key=f.path.stem,
            )
    for f in repo.programs:
        if f.program_id and f.program_id != f.member:
            yield Finding(
                "CD011", f.rel, 1, f"PROGRAM-ID {f.program_id} does not match member name {f.member}", key=f.program_id
            )


def check_size(repo: Repository) -> Iterable[Finding]:
    """CD021 oversized COBOL sources and CD022 large tracked files."""
    for f in repo.files:
        if len(f.lines) > MAX_SOURCE_LINES:
            yield Finding(
                "CD021",
                f.rel,
                1,
                f"{len(f.lines)} lines exceeds {MAX_SOURCE_LINES}; split the program or copybook",
                value=len(f.lines),
            )
    for rel in _tracked_files(repo.root):
        path = repo.root / rel
        if path.is_file() and path.stat().st_size > MAX_TRACKED_FILE_BYTES:
            yield Finding(
                "CD022",
                rel,
                1,
                f"{path.stat().st_size} bytes exceeds {MAX_TRACKED_FILE_BYTES}; keep large binaries out of git",
                value=path.stat().st_size,
            )


def check_procedures(repo: Repository) -> Iterable[Finding]:
    """CD020 paragraph complexity and CD030 unreachable paragraphs."""
    reported: set[tuple[str, str]] = set()
    for f in repo.programs:
        model = procedure.build(repo, f)
        if model is None:
            continue
        for para in model.paragraphs:
            if para.complexity > MAX_PARAGRAPH_COMPLEXITY and (para.origin, para.name) not in reported:
                reported.add((para.origin, para.name))
                yield Finding(
                    "CD020",
                    para.origin,
                    para.line,
                    f"paragraph {para.name} has cyclomatic complexity {para.complexity} "
                    f"(limit {MAX_PARAGRAPH_COMPLEXITY})",
                    key=para.name,
                    value=para.complexity,
                )
        for para in model.unreachable():
            yield Finding(
                "CD030",
                f.rel,
                para.line,
                f"paragraph {para.name} is never performed, jumped to, or reached by fall-through",
                key=para.name,
            )


def check_copybooks(repo: Repository) -> Iterable[Finding]:
    """CD031 unused copybooks, CD032 unresolved COPY, CD040 module boundary violations."""
    used: set[str] = set()
    for f in repo.files:
        for line_no, name in f.copies:
            used.add(name)
            candidates = repo.copybooks.get(name, [])
            if not candidates:
                if not is_system_copybook(name):
                    yield Finding(
                        "CD032", f.rel, line_no, f"COPY {name} does not resolve to a repository copybook", key=name
                    )
                continue
            allowed = {f.module, BASE_MODULE}
            if not any(c.module in allowed for c in candidates):
                owners = ", ".join(sorted({c.module for c in candidates}))
                yield Finding(
                    "CD040",
                    f.rel,
                    line_no,
                    f"COPY {name} reaches into optional module {owners}; "
                    f"{f.module} code may only use its own or base copybooks",
                    key=name,
                )
    for name, books in sorted(repo.copybooks.items()):
        if name not in used:
            for book in books:
                yield Finding("CD031", book.rel, 1, f"copybook {name} is not copied by any program", key=name)


def check_duplicates(repo: Repository) -> Iterable[Finding]:
    """CD050 duplicated blocks of procedure or data code."""
    yield from find_duplicates(repo)


def check_debt_markers(repo: Repository) -> Iterable[Finding]:
    """CD060 TODO/FIXME/HACK markers must reference an issue, e.g. TODO(#123)."""
    for rel in _tracked_files(repo.root):
        path = repo.root / rel
        if path.suffix.lower() not in _TEXT_SUFFIXES | MAINFRAME_SUFFIXES:
            continue
        if not path.is_file() or rel in _DEBT_MARKER_EXEMPT:
            continue
        for number, text in enumerate(path.read_text("latin-1").splitlines(), start=1):
            if _TODO_RE.search(text) and not _TODO_OK_RE.search(text):
                yield Finding(
                    "CD060", rel, number, "debt marker must link an issue, for example TODO(#123)", key=_line_key(text)
                )


def pii_data_names(repo: Repository) -> set[str]:
    """Data names that are PII fields, or groups and records that contain one."""
    names: set[str] = set()

    def visit(item: DataItem) -> bool:
        has = bool(_PII_NAME_RE.search(item.name))
        for child in item.children:
            has = visit(child) or has
        if has and item.name != "FILLER":
            names.add(item.name)
        return has

    for books in repo.copybooks.values():
        for book in books:
            if book.suffix == ".cpy" and "cpy-bms" not in book.rel:
                for record in parse_records(book):
                    visit(record)
    return names


def check_pii_display(repo: Repository) -> Iterable[Finding]:
    """CD070 DISPLAY of card numbers, SSNs, passwords, or records containing them."""
    sensitive = pii_data_names(repo)
    for f in repo.programs:
        lines = f.code_lines
        for i, line in enumerate(lines):
            code = strip_literals(line.code).upper()
            if not re.search(r"\bDISPLAY\b", code):
                continue
            statement = code[code.index("DISPLAY") :]
            for nxt in lines[i + 1 : i + 6]:
                ncode = strip_literals(nxt.code).upper()
                if _STATEMENT_START_RE.match(ncode) or statement.rstrip().endswith("."):
                    break
                statement += " " + ncode
            hits = sorted({tok for tok in _TOKEN_RE.findall(statement) if tok in sensitive or _PII_NAME_RE.search(tok)})
            if hits:
                yield Finding(
                    "CD070",
                    f.rel,
                    line.number,
                    f"DISPLAY writes sensitive data to SYSOUT ({', '.join(hits)}); mask it first",
                    key=",".join(hits),
                )


RULES: list[Rule] = [
    check_record_format,
    check_naming,
    check_size,
    check_procedures,
    check_copybooks,
    check_duplicates,
    check_debt_markers,
    check_pii_display,
]


def run_all(repo: Repository) -> list[Finding]:
    findings: list[Finding] = []
    for rule in RULES:
        findings.extend(rule(repo))
    return sorted(findings, key=lambda f: (f.path, f.line, f.rule))


def _tracked_files(root: Path) -> list[str]:
    """Files git would commit: tracked plus untracked-but-not-ignored, so new files are checked too."""
    try:
        out = subprocess.run(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
            cwd=root,
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        return [
            p.relative_to(root).as_posix() for p in sorted(root.rglob("*")) if p.is_file() and ".git" not in p.parts
        ]
    return sorted({line for line in out.splitlines() if line})
