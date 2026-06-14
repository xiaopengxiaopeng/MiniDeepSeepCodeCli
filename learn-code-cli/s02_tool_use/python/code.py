#!/usr/bin/env python3
"""
s02: Tool Use — Dispatch Map Pattern
====================================
Builds on s01's single bash tool. Adds read_file, write_file, edit_file, glob.
Introduces the TOOL_HANDLERS dispatch map — the loop never changes.

Usage: python code.py
"""

import os
import sys
import json
import glob as globmod
from typing import Any

# ── Colors ──────────────────────────────────────────────────────────────────
C_RESET = "\033[0m"
C_DIM = "\033[2m"
C_BOLD = "\033[1m"
C_CYAN = "\033[36m"
C_GREEN = "\033[32m"
C_YELLOW = "\033[33m"
C_RED = "\033[31m"
C_MAGENTA = "\033[35m"


def dim(s: str) -> str:
    return f"{C_DIM}{s}{C_RESET}"


def bold(s: str) -> str:
    return f"{C_BOLD}{s}{C_RESET}"


def cyan(s: str) -> str:
    return f"{C_CYAN}{s}{C_RESET}"


def green(s: str) -> str:
    return f"{C_GREEN}{s}{C_RESET}"


def yellow(s: str) -> str:
    return f"{C_YELLOW}{s}{C_RESET}"


def red(s: str) -> str:
    return f"{C_RED}{s}{C_RESET}"


def magenta(s: str) -> str:
    return f"{C_MAGENTA}{s}{C_RESET}"


# ── Configuration ───────────────────────────────────────────────────────────
WORKSPACE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "demo_workspace"))
MAX_TURNS = 10


# ── Path Safety ─────────────────────────────────────────────────────────────
def safe_path(filepath: str) -> str:
    workspace = os.path.abspath(WORKSPACE_DIR)
    target = os.path.abspath(os.path.join(workspace, filepath))
    if not target.startswith(workspace + os.sep) and target != workspace:
        raise ValueError(f"Path escapes workspace: {filepath}")
    return target


def ensure_parent(filepath: str) -> None:
    parent = os.path.dirname(filepath)
    if parent and not os.path.exists(parent):
        os.makedirs(parent, exist_ok=True)


# ── Tools ───────────────────────────────────────────────────────────────────

def run_bash(args: dict) -> str:
    """Execute a shell command in the workspace directory."""
    command = args.get("command", "")
    dangerous = [
        "rm -rf /", "sudo", "shutdown", "reboot",
        "mkfs", "dd if=", "> /dev/sda", "format c:", "del /f /s",
    ]
    for pattern in dangerous:
        if pattern.lower() in command.lower():
            return f"Error: Dangerous command blocked ('{pattern}')"
    try:
        import subprocess
        result = subprocess.run(
            command,
            shell=True,
            cwd=WORKSPACE_DIR,
            capture_output=True,
            text=True,
            timeout=120,
        )
        output = (result.stdout + result.stderr).strip()
        return output if output else "(no output)"
    except subprocess.TimeoutExpired:
        return "Error: Timeout (120s)"
    except Exception as e:
        return f"Error: {e}"


def run_read_file(args: dict) -> str:
    """Read a file from the workspace with optional offset and limit."""
    filepath = args.get("path", "")
    offset = args.get("offset", 0)
    limit = args.get("limit", None)
    try:
        resolved = safe_path(filepath)
        if not os.path.exists(resolved):
            return f"Error: File not found: {filepath}"
        if os.path.isdir(resolved):
            entries = os.listdir(resolved)
            return "\n".join(
                f"{e}/" if os.path.isdir(os.path.join(resolved, e)) else e
                for e in entries
            )
        with open(resolved, "r", encoding="utf-8") as f:
            lines = f.read().split("\n")
        selected = lines[offset:]
        if limit is not None and limit < len(selected):
            remaining = len(selected) - limit
            selected = selected[:limit] + [f"... ({remaining} more lines)"]
        return "\n".join(selected)
    except ValueError as e:
        return f"Error: {e}"
    except Exception as e:
        return f"Error: {e}"


def run_write_file(args: dict) -> str:
    """Write content to a file. Creates parent directories if needed."""
    filepath = args.get("path", "")
    content = args.get("content", "")
    try:
        resolved = safe_path(filepath)
        ensure_parent(resolved)
        with open(resolved, "w", encoding="utf-8") as f:
            f.write(content)
        return f"Wrote {len(content)} bytes to {filepath}"
    except ValueError as e:
        return f"Error: {e}"
    except Exception as e:
        return f"Error: {e}"


def run_edit_file(args: dict) -> str:
    """Replace exact text in a file."""
    filepath = args.get("path", "")
    old_string = args.get("old_string", "")
    new_string = args.get("new_string", "")
    replace_all = args.get("replace_all", False)
    try:
        resolved = safe_path(filepath)
        if not os.path.exists(resolved):
            return f"Error: File not found: {filepath}"
        with open(resolved, "r", encoding="utf-8") as f:
            content = f.read()
        count = content.count(old_string)
        if count == 0:
            return f"Error: text not found in {filepath}"
        if not replace_all and count > 1:
            return (
                f"Error: Found {count} matches for oldString. "
                "Provide more surrounding lines or use replace_all."
            )
        new_content = content.replace(old_string, new_string) if replace_all else content.replace(old_string, new_string, 1)
        with open(resolved, "w", encoding="utf-8") as f:
            f.write(new_content)
        return f"Edited {filepath} ({count if replace_all else 1} replacement(s))"
    except ValueError as e:
        return f"Error: {e}"
    except Exception as e:
        return f"Error: {e}"


def run_glob(args: dict) -> str:
    """Find files matching a glob pattern, sorted by modification time."""
    pattern = args.get("pattern", "*")
    try:
        full_pattern = os.path.join(WORKSPACE_DIR, pattern)
        matches = globmod.glob(full_pattern, recursive=True)
        if not matches:
            return "(no matches)"
        relative = [os.path.relpath(m, WORKSPACE_DIR) for m in matches]
        ignored = {"node_modules", ".git", "__pycache__"}
        filtered = [
            m for m in relative
            if not any(part in ignored for part in m.replace("\\", "/").split("/"))
        ]
        if not filtered:
            return "(no matches after filtering ignored dirs)"
        filtered.sort(key=lambda m: os.path.getmtime(os.path.join(WORKSPACE_DIR, m)), reverse=True)
        return "\n".join(filtered)
    except Exception as e:
        return f"Error: {e}"


# ── Dispatch Map ────────────────────────────────────────────────────────────
TOOL_HANDLERS = {
    "bash": run_bash,
    "read_file": run_read_file,
    "write_file": run_write_file,
    "edit_file": run_edit_file,
    "glob": run_glob,
}

# ── Tool Definitions (sent to LLM) ──────────────────────────────────────────
TOOL_DEFINITIONS = [
    {
        "name": "bash",
        "description": "Execute a shell command in the workspace directory.",
        "parameters": {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "The shell command to execute"},
            },
            "required": ["command"],
        },
    },
    {
        "name": "read_file",
        "description": "Read a file from the workspace with optional offset and limit.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path relative to workspace"},
                "offset": {"type": "integer", "description": "Line number to start from (0-indexed)"},
                "limit": {"type": "integer", "description": "Maximum lines to read"},
            },
            "required": ["path"],
        },
    },
    {
        "name": "write_file",
        "description": "Write content to a file. Creates parent directories if needed.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path relative to workspace"},
                "content": {"type": "string", "description": "Content to write to the file"},
            },
            "required": ["path", "content"],
        },
    },
    {
        "name": "edit_file",
        "description": "Replace exact text in a file. If old_string is not unique, provide more context or use replace_all.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path"},
                "old_string": {"type": "string", "description": "Exact text to find and replace"},
                "new_string": {"type": "string", "description": "Replacement text"},
                "replace_all": {"type": "boolean", "description": "Replace all occurrences (default false)"},
            },
            "required": ["path", "old_string", "new_string"],
        },
    },
    {
        "name": "glob",
        "description": "Find files matching a glob pattern. Returns matches sorted by modification time.",
        "parameters": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Glob pattern (e.g. 'src/**/*.ts', '*.js')"},
            },
            "required": ["pattern"],
        },
    },
]


# ── Tool Executor ───────────────────────────────────────────────────────────
def execute_tool(tool_call: dict) -> str:
    name = tool_call.get("name", "")
    args = tool_call.get("arguments", {})
    handler = TOOL_HANDLERS.get(name)
    if handler is None:
        return f"Error: Unknown tool '{name}'"
    try:
        return handler(args)
    except Exception as e:
        return f"Error: {e}"


# ── Mock LLM ────────────────────────────────────────────────────────────────
def create_mock_llm():
    """
    Returns a generator that simulates LLM responses.
    Each yield is a dict with 'content' and optional 'tool_calls'.
    The demo walks through all 5 tools.
    """
    responses = [
        # Turn 1: Greet, then glob to see what's in the project
        {
            "content": "Let me explore the workspace first.",
            "tool_calls": [{"name": "glob", "arguments": {"pattern": "**/*"}}],
        },
        # Turn 2: Read the greeting file found by glob
        {
            "content": "Found some files. Let me read the greeting.",
            "tool_calls": [{"name": "read_file", "arguments": {"path": "hello.txt"}}],
        },
        # Turn 3: Edit the greeting (old + new)
        {
            "content": "I see it says Hello. Let me update it.",
            "tool_calls": [
                {
                    "name": "edit_file",
                    "arguments": {
                        "path": "hello.txt",
                        "old_string": "Hello, World!",
                        "new_string": "Hello, s02 Dispatch Map!",
                        "replace_all": False,
                    },
                }
            ],
        },
        # Turn 4: Write a new file
        {
            "content": "Let me create a new file to demonstrate write_file.",
            "tool_calls": [
                {
                    "name": "write_file",
                    "arguments": {
                        "path": "tools.txt",
                        "content": "bash\nread_file\nwrite_file\nedit_file\nglob\n",
                    },
                }
            ],
        },
        # Turn 5: Use bash to list files and show final state
        {
            "content": "Let me verify the workspace state.",
            "tool_calls": [{"name": "bash", "arguments": {
                "command": "dir /b 2>nul || ls; echo ---; type hello.txt 2>nul || cat hello.txt; echo ---; type tools.txt 2>nul || cat tools.txt"
            }}],
        },
        # Turn 6: Final summary (no tool calls — loop terminates)
        {
            "content": "All tools demonstrated successfully:\n"
            "- glob: found project files\n"
            "- read_file: read hello.txt\n"
            "- edit_file: updated greeting text\n"
            "- write_file: created tools.txt\n"
            "- bash: verified workspace state\n\n"
            "The agent loop never changed — each tool has a handler in TOOL_HANDLERS.",
            "tool_calls": [],
        },
    ]
    for r in responses:
        yield r


# ── Agent Loop ──────────────────────────────────────────────────────────────
def agent_loop(llm_generator) -> str:
    """Core agent loop. Identical structure to s01, just more tools."""
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": "You are a coding assistant. Use tools to help the user."},
        {"role": "user", "content": "Please demonstrate all available tools on the workspace."},
    ]

    for turn in range(MAX_TURNS):
        # ─── Call LLM ───
        response = next(llm_generator, None)
        if response is None:
            print(yellow("\n[Mock LLM exhausted]"))
            break

        assistant_text = response.get("content", "")
        tool_calls = response.get("tool_calls", [])

        # ─── Print assistant text ───
        if assistant_text:
            print(f"\n{C_CYAN}{C_BOLD}Assistant:{C_RESET} {assistant_text}")

        # ─── Append assistant message ───
        messages.append({"role": "assistant", "content": assistant_text})

        # ─── Check if done ───
        if not tool_calls:
            return assistant_text

        # ─── Execute tool calls ───
        for tc in tool_calls:
            name = tc["name"]
            args = tc["arguments"]
            arg_display = ", ".join(f"{k}={repr(v)}" for k, v in args.items())
            print(f"\n  {yellow(f'[{name}]')} {dim(f'({arg_display})')}")

            result = execute_tool(tc)
            print(f"  {green('→')} {result.split(chr(10))[0]}")
            if "\n" in result:
                for line in result.split("\n")[1:5]:
                    print(f"    {dim(line)}")
                if len(result.split("\n")) > 5:
                    print(f"    {dim('...')}")

            messages.append({
                "role": "tool",
                "tool_name": name,
                "content": result,
            })

        # ─── Loop back ───
        print(dim(f"\n  [turn {turn + 1}/{MAX_TURNS} complete, looping back...]"))

    return "Maximum turns reached."


# ── Setup Demo Workspace ────────────────────────────────────────────────────
def setup_workspace():
    os.makedirs(WORKSPACE_DIR, exist_ok=True)
    hello_path = os.path.join(WORKSPACE_DIR, "hello.txt")
    readme_path = os.path.join(WORKSPACE_DIR, "README.txt")
    with open(hello_path, "w", encoding="utf-8") as f:
        f.write("Hello, World!\nWelcome to s02.\n")
    with open(readme_path, "w", encoding="utf-8") as f:
        f.write("This is the demo workspace for s02 Tool Use.\n")


# ── Main ────────────────────────────────────────────────────────────────────
def main():
    print(bold(cyan("\n╔══════════════════════════════════════════════╗")))
    print(bold(cyan("║  s02: Tool Use — The Dispatch Map Pattern    ║")))
    print(bold(cyan("╚══════════════════════════════════════════════╝")))
    print(dim(f"Workspace: {WORKSPACE_DIR}"))
    print(dim(f"Max turns: {MAX_TURNS}"))
    print()

    setup_workspace()

    print(magenta(f"TOOL_HANDLERS registered: {', '.join(TOOL_HANDLERS.keys())}"))
    print()

    llm = create_mock_llm()
    final = agent_loop(llm)

    print(f"\n{bold(green('=== Agent loop finished ==='))}")
    print(f"Final response: {final}")


if __name__ == "__main__":
    main()
