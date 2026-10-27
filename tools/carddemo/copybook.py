"""Compute record lengths from COBOL data description entries.

Used to check that sample data files and the README match the copybooks.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass, field

from .source import SourceFile, strip_literals

_ENTRY_RE = re.compile(r"^(\d{1,2})\s+([A-Z0-9-]+|FILLER)?\b(.*)$", re.IGNORECASE)
_PIC_RE = re.compile(r"\bPIC(?:TURE)?\s+(?:IS\s+)?(\S+)", re.IGNORECASE)
_OCCURS_RE = re.compile(r"\bOCCURS\s+(?:\d+\s+TO\s+)?(\d+)", re.IGNORECASE)
_REDEFINES_RE = re.compile(r"\bREDEFINES\b", re.IGNORECASE)
_USAGE_RE = re.compile(
    r"\b(COMP-3|COMPUTATIONAL-3|PACKED-DECIMAL|COMP-1|COMP-2|COMP-5|COMP-4|COMPUTATIONAL|COMP|BINARY)\b",
    re.IGNORECASE,
)


def picture_digits(picture: str) -> tuple[int, int]:
    """Return (character positions, digit positions) described by a PICTURE string."""
    pic = picture.upper().rstrip(".")
    expanded = re.sub(r"(.)\((\d+)\)", lambda m: m.group(1) * int(m.group(2)), pic)
    digits = sum(1 for ch in expanded if ch in "9PZ*")
    positions = sum(1 for ch in expanded if ch not in "SVP")
    return positions, digits


def item_length(picture: str, usage: str | None) -> int:
    positions, digits = picture_digits(picture)
    usage = (usage or "").upper()
    if usage in {"COMP-3", "COMPUTATIONAL-3", "PACKED-DECIMAL"}:
        return math.ceil((digits + 1) / 2)
    if usage in {"COMP", "COMPUTATIONAL", "BINARY", "COMP-4", "COMP-5"}:
        if digits <= 4:
            return 2
        if digits <= 9:
            return 4
        return 8
    if usage == "COMP-1":
        return 4
    if usage == "COMP-2":
        return 8
    return positions


@dataclass
class DataItem:
    level: int
    name: str
    picture: str | None = None
    usage: str | None = None
    occurs: int = 1
    redefines: bool = False
    children: list[DataItem] = field(default_factory=list)

    @property
    def length(self) -> int:
        if self.picture:
            own = item_length(self.picture, self.usage)
        else:
            own = sum(child.length for child in self.children if not child.redefines)
        return own * self.occurs


def _statements(source: SourceFile) -> list[str]:
    """Join continuation lines into period-terminated data description entries."""
    statements, current = [], ""
    for line in source.code_lines:
        current += " " + strip_literals(line.code).strip()
        if current.rstrip().endswith("."):
            statements.append(current.strip())
            current = ""
    if current.strip():
        statements.append(current.strip())
    return statements


def parse_records(source: SourceFile) -> list[DataItem]:
    """Parse the 01-level records in a copybook into item trees."""
    records: list[DataItem] = []
    stack: list[DataItem] = []
    inherited_usage: dict[int, str] = {}
    for statement in _statements(source):
        match = _ENTRY_RE.match(statement)
        if not match:
            continue
        level = int(match.group(1))
        if level in (66, 77, 88):
            continue
        rest = match.group(3)
        pic = _PIC_RE.search(rest)
        usage = _USAGE_RE.search(rest)
        occurs = _OCCURS_RE.search(rest)
        item = DataItem(
            level=level,
            name=(match.group(2) or "FILLER").upper(),
            picture=pic.group(1) if pic else None,
            usage=usage.group(1) if usage else None,
            occurs=int(occurs.group(1)) if occurs else 1,
            redefines=bool(_REDEFINES_RE.search(rest)),
        )
        while stack and stack[-1].level >= level:
            stack.pop()
        if item.usage is None:
            for parent in reversed(stack):
                if parent.usage:
                    item.usage = parent.usage
                    break
        if level == 1 or not stack:
            records.append(item)
        else:
            stack[-1].children.append(item)
        stack.append(item)
        inherited_usage[level] = item.usage or ""
    return records


def record_length(source: SourceFile, record: str | None = None) -> int:
    records = parse_records(source)
    if record:
        records = [r for r in records if r.name == record.upper()]
    if not records:
        raise ValueError(f"no 01-level record found in {source.rel}")
    return records[0].length
