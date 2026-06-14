"""
s03_permission — 3-Gate Permission Pipeline
Builds on s02's dispatch-map agent loop.
Adds permission checking before bash tool execution.
Mock LLM for demonstration.

Run:  python code.py              # interactive (Gate 3 prompts for confirmation)
     python code.py --auto        # non-interactive (Gate 3 auto-approves)
     python code.py "your prompt" # custom user prompt
"""

import os
import re
import sys
import json
from typing import Any, Optional

# ============================================================
# Configuration
# ============================================================
WORKSPACE_DIR = os.path.join(os.path.dirname(__file__) or ".", "workspace")
MAX_TURNS = 10

os.makedirs(WORKSPACE_DIR, exist_ok=True)


# ============================================================
# Tool Definitions (from s02) — the interface contract with the LLM
# ============================================================
TOOL_DEFINITIONS = [
    {
        "name": "bash",
        "description": "Execute a shell command inside the workspace directory.",
        "parameters": {
            "command": {"type": "string", "description": "The shell command to execute."},
        },
    },
    {
        "name": "read_file",
        "description": "Read file contents (with line numbers).",
        "parameters": {
            "path": {"type": "string", "description": "File path relative to workspace."},
            "offset": {"type": "integer", "description": "Start line (1-indexed)."},
            "limit": {"type": "integer", "description": "Max lines to return."},
        },
    },
    {
        "name": "write_file",
        "description": "Create or overwrite a file.",
        "parameters": {
            "path": {"type": "string", "description": "File path relative to workspace."},
            "content": {"type": "string", "description": "Content to write."},
        },
    },
    {
        "name": "glob",
        "description": "Match files by glob pattern, sorted by modification time.",
        "parameters": {
            "pattern": {"type": "string", "description": "Glob pattern (e.g. '**/*.py')."},
        },
    },
]

TOOL_DESCRIPTION_TEXTS = {
    "bash": "Execute a shell command in the workspace.",
    "read_file": "Read file contents with line-number pagination.",
    "write_file": "Create or overwrite a file.",
    "glob": "Match files by glob pattern, sorted by mtime.",
}


# ============================================================
# Path safety (from s02) — prevent workspace escape
# ============================================================
def safe_path(filepath: str) -> str:
    workspace = os.path.abspath(WORKSPACE_DIR)
    target = os.path.abspath(os.path.join(workspace, filepath))
    if not target.startswith(workspace + os.sep) and target != workspace:
        raise ValueError(f"Path escapes workspace: {filepath}")
    return target


# ============================================================
# Tool handlers (from s02)
# ============================================================
def run_bash(args: dict) -> str:
    command = args["command"]
    try:
        if os.name == "nt":
            result = os.popen(f'cmd /c "cd /d {WORKSPACE_DIR} && {command} 2>&1"').read()
        else:
            result = os.popen(f"cd {WORKSPACE_DIR} && {command} 2>&1").read()
        return result.strip() or "(no output)"
    except Exception as e:
        return f"bash error: {e}"


def run_read_file(args: dict) -> str:
    path = safe_path(args["path"])
    offset = args.get("offset", 0)
    limit = args.get("limit", 2000)
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
        total = len(lines)
        if offset > 0:
            lines = lines[offset - 1:]
        if limit > 0:
            lines = lines[:limit]
        numbered = "".join(f"{i + max(offset, 1)}: {l}" for i, l in enumerate(lines))
        if limit and len(lines) == limit and total > offset + limit:
            numbered += f"\n... (truncated, {total} lines total)"
        return numbered.rstrip() or "(empty file)"
    except FileNotFoundError:
        return f"File not found: {path}"
    except Exception as e:
        return f"read_file error: {e}"


def run_write_file(args: dict) -> str:
    path = safe_path(args["path"])
    content = args["content"]
    try:
        os.makedirs(os.path.dirname(path) or WORKSPACE_DIR, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        return f"Written {len(content)} bytes to {args['path']}"
    except Exception as e:
        return f"write_file error: {e}"


def run_glob(args: dict) -> str:
    pattern = args["pattern"]
    import fnmatch as _fnmatch
    results = []
    for root, dirs, files in os.walk(WORKSPACE_DIR):
        for name in dirs + files:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, WORKSPACE_DIR).replace("\\", "/")
            if _fnmatch.fnmatch(rel, pattern) or _fnmatch.fnmatch(name, pattern):
                mtime = os.path.getmtime(full)
                results.append((mtime, rel))
    results.sort(key=lambda x: x[0], reverse=True)
    if not results:
        return f"No files matched pattern: {pattern}"
    return "\n".join(f[1] for f in results)


# s02 dispatch map
TOOL_HANDLERS = {
    "bash": run_bash,
    "read_file": run_read_file,
    "write_file": run_write_file,
    "glob": run_glob,
}


def execute_tool(tc: dict) -> str:
    """Dispatch a tool call to its handler (s02 core)."""
    name = tc["name"]
    args = tc.get("arguments", {})
    handler = TOOL_HANDLERS.get(name)
    if handler is None:
        return f"Unknown tool: {name}"
    try:
        return handler(args)
    except Exception as e:
        return f"Tool '{name}' error: {e}"


# ============================================================
# Permission System (s03 — the only new code)
# ============================================================

# Gate 1: Hard deny list — regex patterns that are ALWAYS blocked
HARD_DENY_PATTERNS = [
    (r"rm\s+-rf\s+/", "rm -rf / (recursive force-delete root)"),
    (r"rm\s+-rf\s+--no-preserve-root", "rm -rf --no-preserve-root"),
    (r"sudo\s+", "sudo (privilege escalation)"),
    (r">\s*/dev/sda", "overwrite /dev/sda (raw disk write)"),
    (r">\s*/dev/nvme", "overwrite NVMe device"),
    (r"mkfs\.", "mkfs (format filesystem)"),
    (r"dd\s+if=", "dd (raw disk copy)"),
    (r":\(\)\s*\{\s*:\|:&\s*\};:", "fork bomb"),
    (r"chmod\s+-R\s+777\s+/", "chmod -R 777 /"),
    (r"wget\s+.*\|\s*sh", "wget piped to sh"),
    (r"curl\s+.*\|\s*bash", "curl piped to bash"),
    (r"\bshutdown\b", "system shutdown"),
    (r"\breboot\b", "system reboot"),
    (r"\bhalt\b", "system halt"),
    (r"\bpoweroff\b", "system poweroff"),
    (r"Remove-Item.*-Recurse.*-Force.*C:\\", "recursive force-delete on C:\\"),
]

# Gate 2: Destructive command detection — patterns that NEED user confirmation
DESTRUCTIVE_PATTERNS = [
    (r"\brm\b", "rm - removes files/directories"),
    (r"\bmv\b", "mv - moves/renames files"),
    (r"\bdel\b", "del - deletes files (Windows)"),
    (r"\berase\b", "erase - deletes files (Windows)"),
    (r"\brmdir\b", "rmdir - removes directories"),
    (r"\bformat\b", "format - formats a disk"),
    (r">\s*\S", "redirect (>) - overwrites file content"),
    (r"\bchmod\b", "chmod - changes file permissions"),
    (r"\bchown\b", "chown - changes file ownership"),
    (r"\bicacls\b", "icacls - modifies ACLs"),
    (r"Remove-Item", "Remove-Item - deletes files (PowerShell)"),
    (r"New-Item.*-Force", "Force flag - may overwrite existing resource"),
    (r"Clear-Content", "Clear-Content - empties file contents"),
]


def check_permission(command: str, auto_confirm: bool = False) -> tuple[bool, str]:
    """
    The 3-gate permission pipeline.

    Gate 1: Hard deny list — absolute block, no appeal.
    Gate 2: Destructive detection — flag for user awareness.
    Gate 3: User confirmation — default-deny interactive prompt.

    Returns:
        (allowed: bool, reason: str)
    """
    # ── Gate 1: Hard Deny ────────────────────────────────────
    for pattern, description in HARD_DENY_PATTERNS:
        if re.search(pattern, command, re.IGNORECASE):
            return False, f"HARD DENY: {description}"

    # ── Gate 2: Destructive Detection ────────────────────────
    destructive_reason = None
    for pattern, description in DESTRUCTIVE_PATTERNS:
        if re.search(pattern, command, re.IGNORECASE):
            destructive_reason = description
            break

    if destructive_reason is None:
        return True, "OK"

    # ── Gate 3: User Confirmation ───────────────────────────
    if auto_confirm:
        return True, f"AUTO-APPROVED: {destructive_reason}"

    print()
    print("=" * 60)
    print(f"  DESTRUCTIVE COMMAND DETECTED: {destructive_reason}")
    print(f"  Command: {command}")
    print("=" * 60)
    response = input("  Allow this command? [y/N]: ").strip().lower()

    if response in ("y", "yes"):
        return True, f"USER CONFIRMED: {destructive_reason}"
    return False, f"USER DENIED: {destructive_reason}"


# ============================================================
# execute_with_permission — s03 wrapper around s02's execute_tool
# ============================================================
def execute_with_permission(tc: dict, auto_confirm: bool = False) -> str:
    """
    Wraps execute_tool() with permission checking.
    This is the ONLY change from s02 to s03 in the agent loop.
    """
    name = tc["name"]

    # Only bash commands go through the permission pipeline.
    # File tools are secured by safe_path() from s02.
    if name == "bash":
        command = tc.get("arguments", {}).get("command", "")
        allowed, reason = check_permission(command, auto_confirm)
        print(f"  Permission: {reason}")
        if not allowed:
            return ""

    return execute_tool(tc)


# ============================================================
# Mock LLM (for demonstration)
# ============================================================
class MockLLM:
    """
    Simulates an LLM producing tool calls.
    The responses are designed to demonstrate all 3 permission gates.
    """

    def __init__(self):
        self.call_count = 0
        self.responses = [
            # Turn 1: Safe bash command — passes all gates
            {
                "content": "Let me check what's in the temp directory.",
                "tool_calls": [
                    {"name": "bash", "arguments": {"command": "echo 'temp-file.log  cache.db  old.dat'"}}
                ],
            },
            # Turn 2: Destructive bash command — triggers Gate 2 + 3
            {
                "content": "Found temp files. I'll remove the unnecessary log file.",
                "tool_calls": [
                    {"name": "bash", "arguments": {"command": "rm temp-file.log"}}
                ],
            },
            # Turn 3: Hard-denied command — blocked at Gate 1
            {
                "content": "To be thorough, I should clear the system cache too.",
                "tool_calls": [
                    {"name": "bash", "arguments": {"command": "sudo rm -rf /var/cache"}}
                ],
            },
            # Turn 4: Non-bash tool — no permission check needed
            {
                "content": "Let me save a cleanup report.",
                "tool_calls": [
                    {"name": "write_file", "arguments": {"path": "cleanup_report.txt", "content": "Cleanup completed. Removed temp-file.log."}}
                ],
            },
        ]

    def chat(self, messages: list[dict]) -> dict:
        """
        Return the next mock LLM response.
        After all pre-scripted responses are exhausted, the agent finishes.
        """
        if self.call_count >= len(self.responses):
            return {"content": "Task completed.", "tool_calls": []}
        resp = self.responses[self.call_count]
        self.call_count += 1
        return resp


# ============================================================
# Agent Loop (from s02 — UNCHANGED except the marked line)
# ============================================================
def agent_loop(user_input: str, auto_confirm: bool = False):
    """
    Main agent loop. The structure is identical to s02.
    The ONLY difference: execute_tool → execute_with_permission.
    """
    llm = MockLLM()

    system_prompt = (
        "You are a coding assistant. You have access to these tools:\n"
        + "\n".join(f"- {n}: {d}" for n, d in TOOL_DESCRIPTION_TEXTS.items())
        + "\nRespond with exactly ONE tool call in JSON format."
    )

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_input},
    ]

    print("=" * 60)
    print("  s03: Permission Pipeline — 3-Gate Demo")
    print("=" * 60)
    print(f"\n  User: {user_input}\n")

    for turn in range(MAX_TURNS):
        print(f"--- Turn {turn + 1} ---")

        # Call LLM (mocked)
        response = llm.chat(messages)
        messages.append({"role": "assistant", "content": json.dumps(response)})

        tool_calls = response.get("tool_calls", [])
        if not tool_calls:
            print(f"  Agent: {response.get('content', 'Done.')}")
            break

        for tc in tool_calls:
            name = tc["name"]
            args = tc.get("arguments", {})
            print(f"  [{name}] {json.dumps(args)}")

            # ───────────────────────────────────────────────
            # s03: The ONLY change from s02's agent loop
            # execute_tool(tc) → execute_with_permission(tc)
            # ───────────────────────────────────────────────
            result = execute_with_permission(tc, auto_confirm)
            # ───────────────────────────────────────────────

            print(f"  Result: {result}\n")
            messages.append({"role": "tool", "content": result})

    print("=" * 60)
    print("  Agent loop finished.")
    print("=" * 60)


# ============================================================
# Main
# ============================================================
def main():
    auto_confirm = "--auto" in sys.argv
    args = [a for a in sys.argv[1:] if a != "--auto"]
    user_input = " ".join(args) if args else "clean up temp files and generate a cleanup report"
    agent_loop(user_input, auto_confirm)


if __name__ == "__main__":
    main()
