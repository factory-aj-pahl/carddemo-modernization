"""Check that commands and paths referenced in AGENTS.md still exist."""

from __future__ import annotations

import re
from pathlib import Path

from .source import REPO_ROOT

AGENTS_MD = REPO_ROOT / "AGENTS.md"

_MAKE_RE = re.compile(r"\bmake\s+([a-z][a-z0-9-]*)")
_SUBCOMMAND_RE = re.compile(r"python3 -m tools\.carddemo\s+([a-z][a-z0-9-]*)")
_CODE_SPAN_RE = re.compile(r"`([^`\s]+)`")
_TARGET_RE = re.compile(r"^([a-z][a-z0-9-]*):", re.MULTILINE)
_PATH_HINT_RE = re.compile(r"^(app|docs|tools|tests|scripts|samples|diagrams|\.github|\.factory|\.devcontainer)/")


def make_targets(makefile: Path = REPO_ROOT / "Makefile") -> set[str]:
    return set(_TARGET_RE.findall(makefile.read_text())) if makefile.exists() else set()


def validate(subcommands: set[str], path: Path = AGENTS_MD, root: Path = REPO_ROOT) -> list[str]:
    if not path.exists():
        return [f"{path.name} is missing"]
    text = path.read_text()
    problems = []
    targets = make_targets(root / "Makefile")
    for target in sorted(set(_MAKE_RE.findall(text))):
        if target not in targets:
            problems.append(f"`make {target}` is documented but the Makefile has no such target")
    for sub in sorted(set(_SUBCOMMAND_RE.findall(text))):
        if sub not in subcommands:
            problems.append(f"`python3 -m tools.carddemo {sub}` is documented but no such subcommand exists")
    for span in sorted(set(_CODE_SPAN_RE.findall(text))):
        candidate = span.rstrip("/").split(":")[0]
        if (
            _PATH_HINT_RE.match(candidate)
            and "*" not in candidate
            and "<" not in candidate
            and not (root / candidate).exists()
        ):
            problems.append(f"path `{candidate}` is referenced but does not exist")
    return problems
