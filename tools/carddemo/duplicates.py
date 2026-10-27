"""Copy-paste detection for COBOL programs and copybooks.

Compares windows of normalized code lines (columns 8-72, comments and blank
lines removed, whitespace collapsed) and merges overlapping matches into
duplicated blocks.
"""

from __future__ import annotations

import hashlib
from collections import defaultdict

from .findings import Finding
from .source import Repository, SourceFile

WINDOW = 30
MAX_OCCURRENCES = 8
KEY_TEXT_CHARS = 32


def _normalized(source: SourceFile) -> list[tuple[int, str]]:
    return [(line.number, " ".join(line.code.upper().split())) for line in source.code_lines]


def _candidates(repo: Repository) -> list[SourceFile]:
    return [
        f for f in repo.files if f.rel.startswith("app/") and f.suffix in {".cbl", ".cpy"} and "/cpy-bms/" not in f.rel
    ]


def find_duplicates(repo: Repository, window: int = WINDOW) -> list[Finding]:
    sources = _candidates(repo)
    normalized = {f.rel: _normalized(f) for f in sources}
    windows: dict[str, list[tuple[str, int]]] = defaultdict(list)
    for rel, lines in normalized.items():
        for i in range(len(lines) - window + 1):
            text = "\n".join(t for _, t in lines[i : i + window])
            windows[hashlib.sha1(text.encode("latin-1")).hexdigest()].append((rel, i))

    diagonals: dict[tuple[str, str, int], list[tuple[int, str]]] = defaultdict(list)
    for digest, places in windows.items():
        if len(places) < 2 or len(places) > MAX_OCCURRENCES:
            continue
        for a in range(len(places)):
            for b in range(a + 1, len(places)):
                (ra, ia), (rb, ib) = sorted([places[a], places[b]])
                if ra == rb and abs(ia - ib) < window:
                    continue
                diagonals[(ra, rb, ib - ia)].append((ia, digest))

    findings = []
    for (ra, rb, offset), starts in sorted(diagonals.items()):
        starts.sort()
        run_start, prev = starts[0][0], starts[0][0]
        for idx in [*(i for i, _ in starts[1:]), None]:
            if idx is not None and idx == prev + 1:
                prev = idx
                continue
            length = prev - run_start + window
            block = normalized[ra][run_start : run_start + length]
            line_a = block[0][0]
            line_b = normalized[rb][run_start + offset][0]
            findings.append(
                Finding(
                    "CD050",
                    ra,
                    line_a,
                    f"{length} code lines duplicated in {rb}:{line_b}; extract a shared copybook or paragraph",
                    key=f"{rb}:{_block_key(block)}",
                    value=length,
                )
            )
            if idx is not None:
                run_start, prev = idx, idx
    return findings


def _block_key(block: list[tuple[int, str]]) -> str:
    """Identify a duplicated block by its first and last code lines, which survive edits elsewhere in the file."""
    return f"{block[0][1][:KEY_TEXT_CHARS]} .. {block[-1][1][:KEY_TEXT_CHARS]}"
