"""Sandbox self-test for the agent job of autodev-agent.yml, on a real runner and without Claude.

Runs the agent job's own setup steps (read from the workflow file, so the test cannot drift from
it), then its real "Run Claude Code" step with the scripted model in mock_anthropic.py instead of
claude.ai. The model asks for one Bash call per probe in sandbox-probes.json; each probe runs inside
Claude Code's sandbox and passes only if it prints `PROBE-PASS <name>`. Afterwards the git
directory, .github and the home directory are checked from outside the sandbox, and the real
collect step must produce a patch containing only the probe's allowed write.

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
ALLOWED_PATCH = {"autodev-probe-allowed.txt"}  # written by the workspace-write probe
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


def write_probes() -> list[str]:
    pdir = WS / ".autodev-probe"
    pdir.mkdir(exist_ok=True)
    exclude = subprocess.run(["git", "rev-parse", "--git-path", "info/exclude"], cwd=WS,
                             capture_output=True, text=True, check=True).stdout.strip()
    with open(WS / exclude, "a") as fh:
        fh.write(".autodev-probe/\n")
    commands = []
    for probe in PROBES:
        script = pdir / f"{probe['name']}.sh"
        script.write_text(PROBE_HEADER + probe["script"].rstrip() + f"\necho 'PROBE-PASS {probe['name']}'\n")
        commands.append(f"bash .autodev-probe/{probe['name']}.sh")
    return commands


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
    commands = write_probes()
    home = Path.home()
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
    for i, probe in enumerate(PROBES):
        out = by_id.get(f"toolu_{i:03d}", {}).get("output", "<no result: the probe never ran>")
        print(f"--- {probe['name']}\n{out}\n")
    print("::endgroup::")
    for i, probe in enumerate(PROBES):
        row = by_id.get(f"toolu_{i:03d}")
        ok = row is not None and f"PROBE-PASS {probe['name']}" in row["output"]
        print(f"{'PASS' if ok else 'FAIL'}  {probe['name']}: {probe['why']}")
        if not ok:
            failures.append(f"probe {probe['name']}")
        if row is not None and re.search(r"bwrap: ", row["output"]):
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
