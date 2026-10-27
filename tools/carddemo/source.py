"""Fixed-format COBOL source model shared by the CardDemo checks.

Mainframe members are 80-byte records: columns 1-6 hold sequence numbers,
column 7 is the indicator area, columns 8-72 hold code, and columns 73-80 are
ignored by the compiler. Everything here works on that layout.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from functools import cached_property
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

BASE_MODULE = "base"

# Extensions of members that are uploaded to 80-column mainframe datasets.
MAINFRAME_SUFFIXES = {
    ".cbl",
    ".cpy",
    ".bms",
    ".jcl",
    ".prc",
    ".proc",
    ".asm",
    ".mac",
    ".ctl",
    ".ddl",
    ".dcl",
    ".psb",
    ".dbd",
    ".csd",
}
PROGRAM_SUFFIXES = {".cbl"}
COPYBOOK_SUFFIXES = {".cpy", ".dcl"}

# Copybooks supplied by CICS, Db2, IMS, MQ, or Language Environment, not by this repository.
SYSTEM_COPYBOOK_PREFIXES = ("DFH", "CMQ", "SQLCA", "SQLDA", "CEEIGZ")

_LITERAL_RE = re.compile(r"'[^']*'?|\"[^\"]*\"?")
_COPY_RE = re.compile(r"^\s*COPY\s+['\"]?([A-Z0-9#$@-]+)['\"]?", re.IGNORECASE)
_SQL_INCLUDE_RE = re.compile(r"\bINCLUDE\s+([A-Z0-9#$@-]+)", re.IGNORECASE)
_PROGRAM_ID_RE = re.compile(r"\bPROGRAM-ID\s*\.?\s*['\"]?([A-Z0-9#$@-]+)", re.IGNORECASE)


def strip_literals(text: str) -> str:
    return _LITERAL_RE.sub("''", text)


@dataclass(frozen=True)
class SourceLine:
    number: int
    raw: str

    @property
    def indicator(self) -> str:
        return self.raw[6] if len(self.raw) > 6 else " "

    @property
    def is_blank(self) -> bool:
        return not self.raw[6:72].strip()

    @property
    def is_comment(self) -> bool:
        return self.indicator in "*/"

    @property
    def is_code(self) -> bool:
        return not self.is_comment and not self.is_blank

    @property
    def code(self) -> str:
        """Columns 8-72, the only part of the record the compiler reads."""
        return self.raw[7:72]

    @property
    def starts_in_area_a(self) -> bool:
        return bool(self.raw[7:11].strip())


@dataclass(frozen=True)
class SourceFile:
    path: Path
    root: Path = REPO_ROOT

    @property
    def rel(self) -> str:
        return self.path.relative_to(self.root).as_posix()

    @property
    def suffix(self) -> str:
        return self.path.suffix.lower()

    @property
    def member(self) -> str:
        return self.path.stem.upper()

    @property
    def module(self) -> str:
        return module_of(self.path, self.root)

    @cached_property
    def lines(self) -> list[SourceLine]:
        text = self.path.read_bytes().decode("latin-1")
        return [SourceLine(number, raw.rstrip("\r")) for number, raw in enumerate(text.split("\n"), start=1)]

    @cached_property
    def code_lines(self) -> list[SourceLine]:
        return [line for line in self.lines if line.is_code]

    @cached_property
    def copies(self) -> list[tuple[int, str]]:
        """Copybooks pulled in with COPY or EXEC SQL INCLUDE, with their line numbers."""
        found: list[tuple[int, str]] = []
        if self.suffix not in PROGRAM_SUFFIXES | COPYBOOK_SUFFIXES:
            return found
        for line in self.code_lines:
            match = _COPY_RE.match(line.code)
            if match:
                found.append((line.number, match.group(1).upper()))
                continue
            if "INCLUDE" in line.code.upper():
                for name in _SQL_INCLUDE_RE.findall(line.code):
                    if name.upper() not in {"SQLCA", "SQLDA"}:
                        found.append((line.number, name.upper()))
        return found

    @cached_property
    def program_id(self) -> str | None:
        for line in self.code_lines[:40]:
            match = _PROGRAM_ID_RE.search(line.code)
            if match:
                return match.group(1).upper()
        return None

    @cached_property
    def upper_code(self) -> str:
        return "\n".join(strip_literals(line.code).upper() for line in self.code_lines)

    @property
    def uses_cics(self) -> bool:
        return "EXEC CICS" in self.upper_code

    @property
    def uses_sql(self) -> bool:
        return "EXEC SQL" in self.upper_code

    @property
    def uses_dli(self) -> bool:
        code = self.upper_code
        return "EXEC DLI" in code or "CBLTDLI" in self.path.read_text("latin-1").upper()

    @property
    def uses_mq(self) -> bool:
        raw = self.path.read_text("latin-1").upper()
        return "'MQOPEN'" in raw or "'MQPUT" in raw or "'MQGET'" in raw

    @property
    def is_batch(self) -> bool:
        return not self.uses_cics


def module_of(path: Path, root: Path = REPO_ROOT) -> str:
    """Return the optional module a path belongs to, or "base" for the core application."""
    parts = path.relative_to(root).parts
    if len(parts) > 1 and parts[0] == "app" and parts[1].startswith("app-"):
        return parts[1]
    return BASE_MODULE


def is_system_copybook(name: str) -> bool:
    return name.upper().startswith(SYSTEM_COPYBOOK_PREFIXES)


class Repository:
    """All mainframe sources under app/ and samples/."""

    def __init__(self, root: Path = REPO_ROOT):
        self.root = root.resolve()

    @cached_property
    def files(self) -> list[SourceFile]:
        found = []
        for top in ("app", "samples"):
            base = self.root / top
            if not base.exists():
                continue
            for path in sorted(base.rglob("*")):
                if path.is_file() and path.suffix.lower() in MAINFRAME_SUFFIXES:
                    found.append(SourceFile(path, self.root))
        return found

    @cached_property
    def programs(self) -> list[SourceFile]:
        return [f for f in self.files if f.suffix in PROGRAM_SUFFIXES and f.rel.startswith("app/")]

    @cached_property
    def copybooks(self) -> dict[str, list[SourceFile]]:
        books: dict[str, list[SourceFile]] = {}
        for f in self.files:
            if f.suffix in COPYBOOK_SUFFIXES and f.rel.startswith("app/"):
                books.setdefault(f.member, []).append(f)
        return books

    def resolve_copybook(self, name: str, module: str) -> SourceFile | None:
        """Resolve a copybook the way the build procedures do: own module first, then base."""
        candidates = self.copybooks.get(name.upper(), [])
        for preferred in (module, BASE_MODULE):
            for candidate in candidates:
                if candidate.module == preferred:
                    return candidate
        return candidates[0] if candidates else None

    def include_dirs(self, module: str) -> list[Path]:
        dirs = []
        for owner in ([module] if module != BASE_MODULE else []) + [BASE_MODULE]:
            base = self.root / "app" if owner == BASE_MODULE else self.root / "app" / owner
            for name in ("cpy", "cpy-bms", "dcl"):
                if (base / name).is_dir():
                    dirs.append(base / name)
        return dirs
