"""Sandbox self-test for the agent job of autodev-agent.yml, on a real runner and without Claude.

Runs the agent job's own setup steps (read from the workflow file, so the test cannot drift from
it), then its real "Run Claude Code" step with the scripted model in mock_anthropic.py instead of
claude.ai. The model makes the calls of each probe in sandbox-probes.json: a "script" probe is one
Bash call that runs inside Claude Code's sandbox and passes only if it prints `PROBE-PASS <name>`; a
"calls" probe drives Claude's own tools (Write, Edit, Read, Bash) and each call must be allowed or
denied as the probe expects. Afterwards the git directory, .github, Claude Code's project settings
and the home directory are checked from outside the sandbox, and the real collect step must
produce a patch containing exactly the files the probes declare under "patch".

No secret is used: the tokens the agent step would hold are fake values that must stay invisible.
Run from the repository root in GitHub Actions: python3 .github/autodev/sandbox_selftest.py
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import time
from pathlib import Path

import yaml

WS = Path(os.environ["GITHUB_WORKSPACE"]).resolve()
TEMP = Path(os.environ["RUNNER_TEMP"])
HERE = Path(__file__).resolve().parent
WF = yaml.safe_load((WS / ".github" / "workflows" / "autodev-agent.yml").read_text())
STEPS = {s.get("name"): s for s in WF["jobs"]["agent"]["steps"]}
PROBES = json.loads((HERE / "sandbox-probes.json").read_text())
# Every legitimate write the probes make, and nothing else, must reach the patch.
ALLOWED_PATCH = {path for probe in PROBES for path in probe.get("patch", [])}
FAKE = f"autodev-fake-secret-{secrets.token_hex(8)}"
PORT = 8765

# Setup steps of the agent job, in order, that run before Claude.
_ORDER = [s.get("name") for s in WF["jobs"]["agent"]["steps"]]
SETUP = [n for n in _ORDER[:_ORDER.index("Run Claude Code")] if n and n != "Validate inputs" and "run" in STEPS[n]]

PROBE_HEADER = """set -uo pipefail
fail() { echo "FAIL: $*"; exit 1; }
# blocked <description> <command...>: the command must fail (a write the sandbox has to refuse).
blocked() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then fail "not blocked: $d"; fi; echo "blocked: $d"; }
"""
# How Claude Code answers a file tool call that its permission rules refuse.
DENIED = re.compile(r"denied by your permission settings|has been denied|haven't granted|sensitive file|"
                    r"Read deny rule|cannot be written")
# Settings Claude Code would load from the working tree: hooks there run outside the sandbox.
PROJECT_SETTINGS = (".claude/settings.json", ".claude/settings.local.json", ".mcp.json")


def run_step(name: str, extra_env: dict[str, str] | None = None) -> int:
    step = STEPS[name]
    script = step["run"]
    assert "${{" not in script, f"step '{name}' uses an expression and cannot be replayed"
    env = {**os.environ, **{k: str(v) for k, v in (WF.get("env") or {}).items()}}
    env.update({k: str(v) for k, v in (step.get("env") or {}).items() if "${{" not in str(v)})
    env.update(extra_env or {})
    print(f"::group::agent step: {name}", flush=True)
    rc = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", script], cwd=WS, env=env).returncode
    print("::endgroup::", flush=True)
    return rc


def git_dir() -> Path:
    out = subprocess.run(["git", "rev-parse", "--absolute-git-dir"], cwd=WS, capture_output=True, text=True, check=True)
    return Path(out.stdout.strip())


def tree_digest(root: Path, skip: tuple[str, ...] = ()) -> dict[str, str]:
    """Content hash of every file below root (the index may be refreshed by Claude Code itself)."""
    digest = {}
    for p in sorted(root.rglob("*")):
        rel = p.relative_to(root).as_posix()
        if rel in skip:
            continue
        if p.is_symlink():
            digest[rel] = "link:" + os.readlink(p)
        elif p.is_file():
            digest[rel] = hashlib.sha256(p.read_bytes()).hexdigest()
        elif p.is_dir():
            digest[rel] = "dir"
    return digest


def _fill(value, places: dict[str, str]):
    """Expands {ws}, {gitdir} and {temp} in every string of a tool call's input."""
    if isinstance(value, str):
        for key, path in places.items():
            value = value.replace("{" + key + "}", path)
        return value
    if isinstance(value, dict):
        return {k: _fill(v, places) for k, v in value.items()}
    if isinstance(value, list):
        return [_fill(v, places) for v in value]
    return value


def write_probes(gdir: Path) -> tuple[list, list[list[int]]]:
    """The model's calls, in order, and for each probe the indexes of its calls."""
    places = {"ws": str(WS), "gitdir": str(gdir), "temp": str(TEMP)}
    pdir = WS / ".autodev-probe"
    pdir.mkdir(exist_ok=True)
    exclude = subprocess.run(["git", "rev-parse", "--git-path", "info/exclude"], cwd=WS,
                             capture_output=True, text=True, check=True).stdout.strip()
    with open(WS / exclude, "a") as fh:
        fh.write(".autodev-probe/\n")
    commands: list = []
    owner: list[list[int]] = []
    for probe in PROBES:
        start = len(commands)
        if "calls" in probe:
            commands += [{"tool": c["tool"], "input": _fill(c["input"], places)} for c in probe["calls"]]
        else:
            script = pdir / f"{probe['name']}.sh"
            script.write_text(PROBE_HEADER + probe["script"].rstrip() + f"\necho 'PROBE-PASS {probe['name']}'\n")
            commands.append(f"bash .autodev-probe/{probe['name']}.sh")
        owner.append(list(range(start, len(commands))))
    return commands, owner


def call_problem(expect: str, row: dict | None) -> str | None:
    """Why a tool call's result does not match what the probe expects, or None."""
    if row is None:
        return "no result: the call never ran"
    refused = row["is_error"] or bool(DENIED.search(row["output"]))
    if expect == "allowed" and refused:
        return "refused: " + row["output"][:300]
    if expect == "denied" and not refused:
        return "not denied: " + row["output"][:300]
    return None


def file_state(path: Path) -> str | None:
    if path.is_symlink():
        return "link:" + os.readlink(path)
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None


def start_mock(commands: list[str], results: Path) -> subprocess.Popen:
    cmd_file = TEMP / "autodev-selftest-commands.json"
    cmd_file.write_text(json.dumps(commands))
    results.write_text("")
    proc = subprocess.Popen([sys.executable, str(HERE / "mock_anthropic.py"), str(PORT), str(cmd_file), str(results)])
    for _ in range(100):
        try:
            socket.create_connection(("127.0.0.1", PORT), timeout=0.2).close()
            return proc
        except OSError:
            time.sleep(0.1)
    raise SystemExit("mock model did not start")


def main() -> int:
    failures: list[str] = []
    for name in SETUP:
        if run_step(name) != 0:
            print(f"::error::agent setup step failed: {name}")
            return 1

    gdir = git_dir()
    commands, owner = write_probes(gdir)
    home = Path.home()
    before_settings = {p: file_state(WS / p) for p in PROJECT_SETTINGS}
    before_git = tree_digest(gdir, skip=("index",))
    before_home = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in
                   (home / ".bashrc", home / ".profile", home / ".gitconfig") if p.is_file()}

    results = TEMP / "autodev-selftest-results.jsonl"
    mock = start_mock(commands, results)
    try:
        rc = run_step("Run Claude Code", {
            "ANTHROPIC_BASE_URL": f"http://127.0.0.1:{PORT}",
            "CLAUDE_CODE_OAUTH_TOKEN": FAKE,       # the agent step's only secret, faked
            "GITHUB_TOKEN": FAKE, "ACTIONS_RUNTIME_TOKEN": FAKE, "ACTIONS_ID_TOKEN_REQUEST_TOKEN": FAKE,
            "TASK_JSON": json.dumps({"id": "SELFTEST-01", "title": "sandbox self-test", "category": "test",
                                     "acceptance": ["run the probes"]}),
            "MODEL": "claude-sonnet-5-5", "TURNS": str(len(commands) + 5), "BUDGET": "1.00",
        })
    finally:
        mock.terminate()
    if rc != 0:
        failures.append(f"Run Claude Code step exited {rc}")

    # 1) Every probe, as seen from inside the sandbox.
    rows = [json.loads(line) for line in results.read_text().splitlines() if line.strip()]
    by_id = {r["id"]: r for r in rows}
    print("::group::probe output (inside the sandbox)")
    for probe, calls in zip(PROBES, owner):
        for i in calls:
            out = by_id.get(f"toolu_{i:03d}", {}).get("output", "<no result: the probe never ran>")
            print(f"--- {probe['name']} [{i}]\n{out}\n")
    print("::endgroup::")
    for probe, calls in zip(PROBES, owner):
        rows = [by_id.get(f"toolu_{i:03d}") for i in calls]
        if "calls" in probe:
            problems = [f"call {n} ({c['tool']}, expected {c['expect']}): {p}"
                        for n, (c, row) in enumerate(zip(probe["calls"], rows))
                        if (p := call_problem(c["expect"], row))]
        else:
            ok = rows[0] is not None and f"PROBE-PASS {probe['name']}" in rows[0]["output"]
            problems = [] if ok else ["did not print PROBE-PASS"]
        print(f"{'PASS' if not problems else 'FAIL'}  {probe['name']}: {probe['why']}")
        failures += [f"probe {probe['name']}: {p}" for p in problems]
        if any(row is not None and re.search(r"bwrap: ", row["output"]) for row in rows):
            failures.append(f"probe {probe['name']}: bubblewrap refused to start the command")
    if FAKE in results.read_text():
        failures.append("a fake secret value reached a tool result")

    # 2) State that only the outside of the sandbox can confirm.
    after_git = tree_digest(gdir, skip=("index",))
    changed = sorted(k for k in before_git.keys() | after_git.keys() if before_git.get(k) != after_git.get(k))
    for k in changed[:20]:
        failures.append(f"git directory changed: {k} ({before_git.get(k, 'absent')} -> {after_git.get(k, 'absent')})")
    for p, h in before_home.items():
        if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != h:
            failures.append(f"{p} changed")
    for p, before in before_settings.items():
        if file_state(WS / p) != before:
            failures.append(f"Claude Code project settings changed: {p}")
    planted = [str(p) for root in (WS, gdir, home) for p in root.rglob("autodev-probe-planted*")]
    if planted:
        failures.append(f"planted files exist: {planted}")

    # 3) The real collect step: only the allowed write may reach the patch, and .github is intact.
    run_step("Collect patch and usage report")
    patch = (TEMP / "autodev" / "patch.diff").read_text()
    files = set(re.findall(r"^diff --git a/(\S+) b/", patch, re.M))
    print(f"patch files: {sorted(files)}")
    if files != ALLOWED_PATCH:
        failures.append(f"patch files {sorted(files)} != {sorted(ALLOWED_PATCH)}")
    if subprocess.run(["git", "diff", "--quiet", "HEAD", "--", ".github"], cwd=WS).returncode != 0:
        failures.append(".github differs from HEAD")
    usage = json.loads((TEMP / "autodev" / "usage.json").read_text())
    if usage.get("collect_error"):
        failures.append(f"collect step error: {usage['collect_error']}")

    for f in failures:
        print(f"::error title=sandbox self-test::{f}")
    print("sandbox self-test: " + ("FAILED" if failures else f"all {len(PROBES)} probes and host checks passed"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
