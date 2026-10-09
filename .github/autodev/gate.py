"""Static quality gate for an agent-produced patch.

Runs in the publish job without executing any repository code: it only reads the
unified diff. Exit code 0 = publishable (low/medium risk), 3 = needs human
approval (high risk), 1 = rejected.

    python -m autodev.gate patch.diff --json
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
import sys
from dataclasses import dataclass, field

FORBIDDEN_PATHS = [
    ".github/workflows/*",
    ".github/actions/*",
    ".github/autodev/*",
    ".git/*",
    ".claude/settings*.json",
    ".env",
    ".env.*",
    "*.pem",
    "*.key",
    "id_rsa*",
    "*.p12",
]
APPROVAL_PATHS = [
    "CLAUDE.md",
    "*/CLAUDE.md",
    "LICENSE*",
    ".github/CODEOWNERS",
    "*SecurityConfig*.java",
    "config/initializers/content_security_policy.rb",
    "docker-compose*.yml",
    "Dockerfile*",
]
DEPENDENCY_FILES = ["pom.xml", "package.json", "package-lock.json", "Gemfile", "Gemfile.lock", "pyproject.toml", "requirements*.txt"]
MIGRATION_PATHS = ["*/db/migration/*.sql", "db/migrate/*.rb"]
DESTRUCTIVE_SQL = re.compile(
    r"\b(DROP\s+(TABLE|COLUMN|INDEX|SCHEMA|DATABASE)|TRUNCATE|DELETE\s+FROM|ALTER\s+TABLE\s+\S+\s+DROP"
    r"|remove_column|drop_table|remove_index|change_column)\b",
    re.IGNORECASE,
)
SECRET_PATTERNS = [
    re.compile(r"sk-ant-[A-Za-z0-9_\-]{20,}"),
    re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{30,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"AGE-SECRET-KEY-1"),
    re.compile(r"xox[baprs]-[A-Za-z0-9\-]{10,}"),
    re.compile(r"https://discord(?:app)?\.com/api/webhooks/\d+/"),
]
# Private coordinator material and the session briefing must never be committed to a product repository.
PRIVATE_CONTENT = re.compile(r"autodev-private|AUTODEV-BRIEF", re.IGNORECASE)
MAX_CHANGED_LINES = 1500
MAX_FILES = 40
MAX_DELETED_LINES_HIGH_RISK = 300


@dataclass
class FileChange:
    path: str
    added: list[str] = field(default_factory=list)
    removed: list[str] = field(default_factory=list)
    deleted_file: bool = False
    binary: bool = False


@dataclass
class Verdict:
    decision: str  # publish | approval | reject
    risk: str
    reasons: list[str]
    files: int
    added: int
    removed: int

    @property
    def exit_code(self) -> int:
        return {"publish": 0, "approval": 3, "reject": 1}[self.decision]


def parse_diff(text: str) -> list[FileChange]:
    files: list[FileChange] = []
    cur: FileChange | None = None
    for line in text.splitlines():
        if line.startswith("diff --git "):
            m = re.match(r"diff --git a/(.+?) b/(.+)$", line)
            cur = FileChange(path=m.group(2) if m else line)
            files.append(cur)
        elif cur is None:
            continue
        elif line.startswith("deleted file mode"):
            cur.deleted_file = True
        elif line.startswith("Binary files") or line.startswith("GIT binary patch"):
            cur.binary = True
        elif line.startswith("+++") or line.startswith("---"):
            continue
        elif line.startswith("+"):
            cur.added.append(line[1:])
        elif line.startswith("-"):
            cur.removed.append(line[1:])
    return files


def _match(path: str, patterns: list[str]) -> bool:
    name = path.rsplit("/", 1)[-1]
    return any(fnmatch.fnmatch(path, p) or fnmatch.fnmatch(name, p) for p in patterns)


def evaluate(diff_text: str, extra_secrets: list[str] | None = None) -> Verdict:
    files = parse_diff(diff_text)
    added = sum(len(f.added) for f in files)
    removed = sum(len(f.removed) for f in files)
    reject: list[str] = []
    approval: list[str] = []
    medium: list[str] = []

    if not files:
        return Verdict("reject", "low", ["empty diff: nothing to publish"], 0, 0, 0)

    for f in files:
        if _match(f.path, FORBIDDEN_PATHS) or f.path.startswith(".github/workflows/"):
            reject.append(f"forbidden path: {f.path}")
        if _match(f.path, APPROVAL_PATHS):
            approval.append(f"sensitive path: {f.path}")
        if _match(f.path, DEPENDENCY_FILES):
            medium.append(f"dependency change: {f.path}")
        if f.binary and f.path.split(".")[-1].lower() not in ("png", "jpg", "jpeg", "webp", "avif", "svg", "ico"):
            approval.append(f"unexpected binary: {f.path}")
        if f.deleted_file:
            approval.append(f"deleted file: {f.path}")
        if _match(f.path, MIGRATION_PATHS):
            if any(DESTRUCTIVE_SQL.search(line) for line in f.added):
                approval.append(f"destructive migration: {f.path}")
            else:
                medium.append(f"additive migration: {f.path}")
        if any(PRIVATE_CONTENT.search(line) for line in f.added):
            reject.append(f"private coordinator content in {f.path}")
        for line in f.added:
            if any(p.search(line) for p in SECRET_PATTERNS):
                reject.append(f"possible secret in {f.path}")
                break
            if extra_secrets and any(s and s in line for s in extra_secrets):
                reject.append(f"environment credential leaked in {f.path}")
                break

    if len(files) > MAX_FILES or added + removed > MAX_CHANGED_LINES:
        reject.append(f"change too large ({len(files)} files, {added + removed} lines)")
    if removed > MAX_DELETED_LINES_HIGH_RISK:
        approval.append(f"mass deletion ({removed} lines)")

    if reject:
        return Verdict("reject", "high", reject + approval, len(files), added, removed)
    if approval:
        return Verdict("approval", "high", approval + medium, len(files), added, removed)
    return Verdict("publish", "medium" if medium else "low", medium, len(files), added, removed)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Static gate for an autodev patch")
    ap.add_argument("patch")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    with open(args.patch, encoding="utf-8", errors="replace") as fh:
        verdict = evaluate(fh.read())
    payload = verdict.__dict__ | {"exit_code": verdict.exit_code}
    print(json.dumps(payload, ensure_ascii=False, indent=2) if args.json else f"{verdict.decision}: {verdict.reasons}")
    return verdict.exit_code


if __name__ == "__main__":
    sys.exit(main())
