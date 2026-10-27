"""Paragraph-level analysis of the PROCEDURE DIVISION.

Builds a control-flow graph from PERFORM, GO TO, THRU ranges, CICS HANDLE
labels, and fall-through between consecutive paragraphs, then derives
per-paragraph cyclomatic complexity and reachability from the entry point.
"""

from __future__ import annotations

import re
from collections.abc import Iterator
from dataclasses import dataclass, field
from functools import cached_property

from .source import Repository, SourceFile, SourceLine, strip_literals

_HEADER_RE = re.compile(r"^([A-Z0-9][A-Z0-9-]*)(\s+SECTION)?\s*\.(.*)$", re.IGNORECASE)
_NOT_PARAGRAPHS = {"EXIT", "GOBACK", "CONTINUE", "ELSE", "STOP", "DECLARATIVES", "END"}
_PERFORM_RE = re.compile(r"(?<![\w-])PERFORM\s+([A-Z0-9][A-Z0-9-]*)(?:\s+(?:THRU|THROUGH)\s+([A-Z0-9][A-Z0-9-]*))?")
_GO_TO_RE = re.compile(r"(?<![\w-])GO\s+TO\s+((?:[A-Z0-9][A-Z0-9-]*[\s,]*)+)")
_TOKEN_RE = re.compile(r"[A-Z0-9][A-Z0-9-]*")
# Lookarounds keep END-IF, IF-FLAG, and similar hyphenated words from counting as decisions.
_DECISION_RE = re.compile(r"(?<![\w-])(IF|WHEN|UNTIL|AND|OR)(?![\w-])")
_WHEN_OTHER_RE = re.compile(r"(?<![\w-])WHEN\s+OTHER(?![\w-])")
_TERMINATOR_RE = re.compile(r"^(GOBACK|STOP\s+RUN|EXIT\s+PROGRAM|GO\s+TO\b|EXEC\s+CICS\s+(RETURN|XCTL)\b)")

MAX_COPY_DEPTH = 5


@dataclass
class Paragraph:
    name: str
    line: int
    is_section: bool
    origin: str
    body: list[str] = field(default_factory=list)

    @cached_property
    def text(self) -> str:
        return "\n".join(self.body)

    @cached_property
    def complexity(self) -> int:
        """McCabe complexity: one plus each IF, WHEN (except WHEN OTHER), UNTIL, AND, OR."""
        decisions = len(_DECISION_RE.findall(self.text)) - len(_WHEN_OTHER_RE.findall(self.text))
        return 1 + decisions

    @cached_property
    def falls_through(self) -> bool:
        statements = [line.strip().rstrip(".").strip() for line in self.body if line.strip()]
        if not statements:
            return True
        last = statements[-1]
        if last.startswith("END-EXEC") and len(statements) > 1:
            joined = " ".join(statements[-6:])
            return not re.search(r"EXEC\s+CICS\s+(RETURN|XCTL)\b", joined)
        return not _TERMINATOR_RE.match(last)


@dataclass
class ProcedureModel:
    source: SourceFile
    entry: Paragraph
    paragraphs: list[Paragraph]

    @cached_property
    def names(self) -> dict[str, Paragraph]:
        return {p.name: p for p in self.paragraphs}

    @cached_property
    def ordered(self) -> list[Paragraph]:
        return [self.entry, *self.paragraphs]

    @cached_property
    def reachable(self) -> set[str]:
        """Names reachable from the entry point.

        Each work item carries the paragraph where control returns to a
        PERFORM caller. A paragraph entered by PERFORM X (or X THRU Y) does
        not fall through past X (or Y); one entered sequentially or by GO TO
        falls into the next paragraph unless it ends the program or jumps.
        """
        index = {p.name: i for i, p in enumerate(self.ordered)}
        seen: set[tuple[str, str | None]] = set()
        stack: list[tuple[str, str | None]] = [(self.entry.name, None)]
        while stack:
            name, stop = stack.pop()
            if (name, stop) in seen:
                continue
            seen.add((name, stop))
            paragraph = self.entry if name == self.entry.name else self.names[name]
            stack.extend(self._targets(paragraph, stop))
            i = index[name]
            if name != stop and paragraph.falls_through and i + 1 < len(self.ordered):
                stack.append((self.ordered[i + 1].name, stop))
        return {name for name, _ in seen}

    def _targets(self, paragraph: Paragraph, stop: str | None) -> list[tuple[str, str | None]]:
        text = paragraph.text
        targets: list[tuple[str, str | None]] = []
        performed: set[str] = set()
        for start, end in _PERFORM_RE.findall(text):
            if start not in self.names:
                continue
            performed.add(start)
            if end and end in self.names:
                performed.add(end)
                targets.append((start, end))
            else:
                targets.append((start, self._range_end(start)))
        jumped: set[str] = set()
        for group in _GO_TO_RE.findall(text):
            for name in _TOKEN_RE.findall(group):
                if name in self.names:
                    jumped.add(name)
                    targets.append((name, stop))
        for token in set(_TOKEN_RE.findall(text)) - performed - jumped:
            # Any other mention, such as CICS HANDLE CONDITION labels, may transfer control.
            if token in self.names and token != paragraph.name:
                targets.append((token, None))
        return targets

    def _range_end(self, name: str) -> str:
        """PERFORM of a SECTION returns after the section's last paragraph."""
        target = self.names[name]
        if not target.is_section:
            return name
        members = self._section_members(target)
        return members[-1] if members else name

    def _section_members(self, section: Paragraph) -> list[str]:
        members, inside = [], False
        for p in self.paragraphs:
            if p is section:
                inside = True
                continue
            if inside:
                if p.is_section:
                    break
                members.append(p.name)
        return members

    def unreachable(self) -> list[Paragraph]:
        return [p for p in self.paragraphs if p.origin == self.source.rel and p.name not in self.reachable]


def _expanded_lines(repo: Repository, source: SourceFile, depth: int = 0) -> Iterator[tuple[str, SourceLine]]:
    """Yield (origin, line) for code lines, inlining COPY members found in the repository."""
    copies = dict(source.copies)
    for line in source.code_lines:
        name = copies.get(line.number)
        if name and depth < MAX_COPY_DEPTH and line.code.strip().upper().startswith("COPY"):
            book = repo.resolve_copybook(name, source.module)
            if book is not None:
                yield from _expanded_lines(repo, book, depth + 1)
                continue
        yield source.rel, line


def build(repo: Repository, source: SourceFile) -> ProcedureModel | None:
    in_procedure = False
    entry = Paragraph("<ENTRY>", 0, False, source.rel)
    current = entry
    paragraphs: list[Paragraph] = []
    for origin, line in _expanded_lines(repo, source):
        code = strip_literals(line.code).upper()
        if not in_procedure:
            if "PROCEDURE DIVISION" in code:
                in_procedure = True
            continue
        header = _header(line, code)
        if header:
            name, is_section, rest = header
            current = Paragraph(name, line.number, is_section, origin)
            paragraphs.append(current)
            if rest.strip():
                current.body.append(rest)
            continue
        current.body.append(code)
    if not in_procedure:
        return None
    return ProcedureModel(source, entry, paragraphs)


def _header(line: SourceLine, code: str) -> tuple[str, bool, str] | None:
    if not line.starts_in_area_a:
        return None
    match = _HEADER_RE.match(code.strip())
    if not match:
        return None
    name = match.group(1)
    if name in _NOT_PARAGRAPHS or name.startswith("END-") or name.isdigit():
        return None
    return name, bool(match.group(2)), match.group(3)
