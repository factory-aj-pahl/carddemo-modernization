#!/usr/bin/env python3
"""Completeness and consistency check for the structured specification in modernization/spec.

Owed sets come from the COBOL tree at the start commit, not from the spec itself:
programs listed in docs/INVENTORY.md, copybooks in app/cpy and module cpy/dcl directories, JCL
members in app/jcl and module jcl directories, and the user-visible message literals that online
programs MOVE to their message fields or declare as 88-level values of those fields. Exits 1 on any gap. Standard library only.

A token counts as a rule-ID reference only when its member part (BR-<member>-nn) is a namespace that
some defined ID uses, and as a job reference only when it names a JCL member or inventory job; this
keeps COBOL data names such as ERR-CODE-1 or JOB-DATA from being read as IDs.
"""
from __future__ import annotations

import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app"
SPEC = ROOT / "modernization" / "spec"
START = "a3e87a4"

ID_BODY = r"(?:BR|VR|NAV|ERR|Q|TS)-[A-Z0-9]+(?:-[A-Z0-9]+)*-\d+"
BOLD_ID = re.compile(r"\*\*(" + ID_BODY + r")\*\*")
ANY_ID = re.compile(r"(?<![A-Za-z0-9-])(" + ID_BODY + r")(?![A-Za-z0-9])")
ENT_REF = re.compile(r"(?<![A-Za-z0-9-])(ENT-[A-Z][A-Z0-9]*(?:-[A-Z][A-Z0-9]*)*)(?![A-Za-z0-9-])")
JOB_REF = re.compile(r"(?<![A-Za-z0-9-])(JOB-[A-Z][A-Z0-9]*)(?![A-Za-z0-9-])")
MSG_TARGET = re.compile(r"^(WS-MESSAGE|WS-RETURN-MSG|ERRMSGO|.*-MSG)$")
MOVE_LIT = re.compile(r"\bMOVE\s+(['\"])(.*?)\1\s+TO\s+([A-Z0-9][A-Z0-9-]*)", re.IGNORECASE)
COND_VALUE = re.compile(r"\b88\s+[A-Z0-9][A-Z0-9-]*\s+VALUES?\s+(?:IS\s+|ARE\s+)?(['\"])(.*?)\1", re.IGNORECASE)


def git_show(path: str) -> str:
    return subprocess.run(["git", "-C", str(ROOT), "show", f"{START}:{path}"], check=True,
                          capture_output=True, text=True).stdout


def owed_programs() -> list[str]:
    text = git_show("docs/INVENTORY.md")
    section = text.split("## Programs", 1)[1].split("\n## ", 1)[0]
    return re.findall(r"^\| \[([A-Z0-9]+)\]\(", section, re.MULTILINE)


def owed_copybooks() -> list[str]:
    dirs = [APP / "cpy"] + sorted(APP.glob("app-*/cpy")) + sorted(APP.glob("app-*/dcl"))
    return sorted({p.stem.upper() for d in dirs for p in d.iterdir() if p.is_file()})


def owed_jobs() -> list[str]:
    dirs = [APP / "jcl"] + sorted(APP.glob("app-*/jcl"))
    return sorted({p.stem.upper() for d in dirs for p in d.iterdir() if p.is_file()})


def logical_source(path: Path) -> str:
    """Fixed-format COBOL code area (columns 8-72) with comments dropped and continuations joined."""
    out: list[str] = []
    for raw in path.read_text(encoding="latin-1").splitlines():
        if len(raw) < 7 or raw[6] in "*/":
            continue
        code = raw[7:72]
        if raw[6] == "-" and out:
            body = code.lstrip()
            out[-1] = out[-1].ljust(65) + (body[1:] if body[:1] in "'\"" else body)
        else:
            out.append(code)
    return " ".join(line.rstrip() if i < len(out) - 1 else line for i, line in enumerate(out))


def condition_messages(source: str) -> list[str]:
    """88-level VALUE literals under a message field (set with SET ... TO TRUE instead of MOVE)."""
    out = []
    parent = ""
    for m in re.finditer(r"\b(\d\d)\s+([A-Z0-9][A-Z0-9-]*)", source, re.IGNORECASE):
        level, name = m.group(1), m.group(2).upper()
        if level == "88":
            if MSG_TARGET.match(parent):
                v = COND_VALUE.match(source, m.start())
                if v:
                    out.append(v.group(2))
        elif level.isdigit() and 1 <= int(level) <= 49:
            parent = name
    return out


def owed_messages() -> dict[str, list[str]]:
    files = sorted(APP.glob("cbl/C[OS]*")) + sorted(APP.glob("app-*/cbl/C[OS]*"))
    found: dict[str, list[str]] = {}
    for f in files:
        msgs = []
        source = logical_source(f)
        literals = [lit for _, lit, target in MOVE_LIT.findall(source) if MSG_TARGET.match(target.upper())]
        literals += condition_messages(source)
        for lit in literals:
            lit = lit.rstrip()
            if len(lit) >= 8 and re.search(r"[A-Za-z]", lit):
                if lit not in msgs:
                    msgs.append(lit)
        if msgs:
            found[f.stem.upper()] = msgs
    return found


def unwrap(text: str) -> str:
    return re.sub(r"\n[ \t]*(?:[-*] |> )?", " ", text)


def main() -> int:
    problems: list[str] = []
    files = sorted(SPEC.glob("*.md")) + sorted((SPEC / "programs").glob("*.md"))
    texts = {p: p.read_text() for p in files}
    all_flat = {p: unwrap(t) for p, t in texts.items()}
    report: list[str] = []

    # Programs
    programs = owed_programs()
    present = [m for m in programs if (SPEC / "programs" / f"{m}.md").exists()
               and texts[SPEC / "programs" / f"{m}.md"].startswith(f"# {m}:")]
    missing = sorted(set(programs) - set(present))
    report.append(f"programs: {len(present)}/{len(programs)} present")
    problems += [f"program {m}: no spec/programs/{m}.md with heading '# {m}:'" for m in missing]

    # Copybooks
    corpus = "\n".join(texts.values())
    cps = owed_copybooks()
    cp_missing = [c for c in cps if not re.search(rf"(?<![A-Z0-9]){re.escape(c)}(?![A-Z0-9])", corpus)]
    report.append(f"copybooks: {len(cps) - len(cp_missing)}/{len(cps)} present")
    problems += [f"copybook {c}: not described anywhere in spec/" for c in cp_missing]

    # Jobs
    jobs_text = texts[SPEC / "jobs.md"]
    inventory = jobs_text.split("## 1. Inventory", 1)[1].split("\n## ", 1)[0]
    rows = {}
    for line in inventory.splitlines():
        m = re.match(r"^\| (JOB-[A-Z0-9]+) \| ([A-Z0-9]+)", line)
        if m:
            rows[m.group(2)] = m.group(1)
    job_headings = set()
    for line in jobs_text.splitlines():
        if line.startswith("### "):
            job_headings.update(JOB_REF.findall(line))
    owed = owed_jobs()
    job_missing = []
    for member in owed:
        jid = rows.get(member)
        if jid is None:
            job_missing.append(f"JCL member {member}: no inventory row in jobs.md section 1")
        elif jid not in job_headings:
            job_missing.append(f"JCL member {member}: {jid} has no job entry heading")
    report.append(f"jobs: {len(owed) - len(job_missing)}/{len(owed)} present")
    problems += job_missing

    # IDs: defined once, references resolve
    defined = Counter()
    where_defined = defaultdict(list)
    for p, t in texts.items():
        for i in BOLD_ID.findall(t):
            defined[i] += 1
            where_defined[i].append(p.name)
    dups = sorted(i for i, n in defined.items() if n > 1)
    problems += [f"ID {i} defined {defined[i]} times ({', '.join(where_defined[i])})" for i in dups]
    ent_defs = set(re.findall(r"^### (ENT-[A-Z0-9-]+)\s*$", texts[SPEC / "entities.md"], re.MULTILINE))
    namespaces = {i.split("-", 1)[1].rsplit("-", 1)[0] for i in defined}
    job_names = set(owed) | {j.split("-", 1)[1] for j in rows.values()} | {j.split("-", 1)[1] for j in job_headings}
    dangling = Counter()
    dangling_where = defaultdict(set)
    for p, t in texts.items():
        for ref in ANY_ID.findall(t):
            if ref.split("-", 1)[1].rsplit("-", 1)[0] not in namespaces:
                continue
            if ref not in defined:
                dangling[ref] += 1
                dangling_where[ref].add(p.name)
        for ref in ENT_REF.findall(t):
            if ref not in ent_defs:
                dangling[ref] += 1
                dangling_where[ref].add(p.name)
        for ref in JOB_REF.findall(t):
            if ref.split("-", 1)[1] in job_names and ref not in job_headings:
                dangling[ref] += 1
                dangling_where[ref].add(p.name)
    problems += [f"reference {r} does not resolve ({', '.join(sorted(dangling_where[r]))})" for r in sorted(dangling)]
    by_type = Counter(i.split("-")[0] for i in defined)
    report.append("defined IDs: " + ", ".join(f"{k} {by_type[k]}" for k in ("BR", "VR", "NAV", "ERR", "Q", "TS"))
                  + f"; duplicates {len(dups)}; dangling references {len(dangling)}")

    # Messages
    messages = owed_messages()
    total = sum(len(v) for v in messages.values())
    msg_missing = []
    for member, msgs in sorted(messages.items()):
        own = all_flat.get(SPEC / "programs" / f"{member}.md", "")
        for lit in msgs:
            if lit in own or any(lit in t for t in all_flat.values()):
                continue
            msg_missing.append(f"message of {member} not verbatim in spec: {lit!r}")
    report.append(f"messages: {total - len(msg_missing)}/{total} present verbatim ({len(messages)} programs)")
    problems += msg_missing

    # Bounded contexts
    comp = texts[SPEC / "components.md"]
    section = comp.split("## 3. Bounded contexts", 1)[1].split("\n## ", 1)[0]
    placed = Counter()
    for line in section.splitlines():
        c = [x.strip() for x in line.strip().strip("|").split("|")]
        if len(c) >= 3 and re.match(r"^BC-\d+$", c[0]):
            for m in set(re.findall(r"\b([A-Z][A-Z0-9]{3,7})\b", c[2])):
                if m in programs:
                    placed[m] += 1
    bad = [m for m in programs if placed[m] != 1]
    report.append(f"bounded contexts: {len(programs) - len(bad)}/{len(programs)} programs in exactly one context")
    problems += [f"program {m} appears in {placed[m]} bounded contexts" for m in bad]

    print("\n".join(report))
    if problems:
        print(f"FAILED: {len(problems)} problem(s)")
        for p in problems:
            print(f"  {p}")
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
