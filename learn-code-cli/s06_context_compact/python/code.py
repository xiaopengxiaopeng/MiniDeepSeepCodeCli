"""
s06 Context Compact — Python
Build on s04's agent loop. Adds 3-layer compaction pipeline:
  1. snipCompact  — trim middle messages when > 50
  2. microCompact — replace old tool results with "[compacted]"
  3. toolResultBudget — persist large results to disk, show preview

The agent loop gets ONE new line: messages = run_compaction_pipeline(messages)
"""

import json
import os
import sys
import re
from dataclasses import dataclass, field
from typing import Any, Callable, Optional
from enum import Enum

# =============================================================================
# Compaction Pipeline (NEW in s06)
# =============================================================================

COMPACT_TMP_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".opencode", "tmp")


def snipCompact(messages: list[dict], max_messages: int = 50) -> list[dict]:
    """
    Layer 1: When messages exceed max_messages, keep the first 3 and last 47,
    replacing the middle with a placeholder.
    Cheap — O(1) array slice + 1 insert.
    """
    if len(messages) <= max_messages:
        return messages

    removed_count = len(messages) - max_messages + 1  # +1 for the placeholder
    placeholder = {
        "role": "system",
        "content": f"[COMPACTED] Trimmed {removed_count} middle messages to stay under the {max_messages}-message limit. "
                   f"First 3 and last 47 messages preserved."
    }

    # Keep first 3, insert placeholder, then keep last 47
    result = messages[:3] + [placeholder] + messages[-(max_messages - 4):]
    print(f"  [compact] snipCompact: trimmed {removed_count} middle messages "
          f"({len(messages)} → {len(result)})")
    return result


def microCompact(messages: list[dict], keep_recent: int = 3) -> list[dict]:
    """
    Layer 2: Replace old tool results with "[compacted]", keeping only the
    keep_recent most recent tool results intact.
    Cheap — one pass over messages, O(n).
    """
    # Find indices of all tool messages
    tool_indices = [i for i, m in enumerate(messages) if m.get("role") == "tool"]

    if len(tool_indices) <= keep_recent:
        return messages

    # Preserve the last keep_recent tool results
    protected = set(tool_indices[-keep_recent:])
    compacted_count = 0

    for i in tool_indices:
        if i not in protected:
            old_content = messages[i].get("content", "")
            if old_content != "[compacted]":
                messages[i]["content"] = "[compacted]"
                compacted_count += 1

    if compacted_count > 0:
        print(f"  [compact] microCompact: {compacted_count} tool result(s) compacted")

    return messages


def toolResultBudget(messages: list[dict], max_chars: int = 30000) -> list[dict]:
    """
    Layer 3: When a tool result exceeds max_chars, persist the full content
    to disk and replace with a preview (first + last 500 chars).
    Low cost — file I/O only for oversized results.
    """
    os.makedirs(COMPACT_TMP_DIR, exist_ok=True)
    budgeted_count = 0

    for msg in messages:
        if msg.get("role") != "tool":
            continue
        content = msg.get("content", "")
        if len(content) <= max_chars:
            continue

        # Persist to disk
        file_id = f"tool_result_{abs(hash(content)) % 1000000:06d}.txt"
        filepath = os.path.join(COMPACT_TMP_DIR, file_id)
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)

        # Build preview
        preview_head = content[:500]
        preview_tail = content[-500:] if len(content) > 1000 else ""
        lines = (preview_head + preview_tail).count("\n") + 1

        preview = (
            f"[tool_result budget]\n"
            f"Path: {filepath}\n"
            f"Preview ({lines} lines shown, full {len(content)} chars saved to disk):\n"
            f"{preview_head}\n"
        )
        if preview_tail:
            preview += f"... (trimmed middle) ...\n{preview_tail}\n"
        preview += f"Size: {len(content)} chars (full content saved to disk)"

        msg["content"] = preview
        budgeted_count += 1

    if budgeted_count > 0:
        print(f"  [compact] toolResultBudget: {budgeted_count} large result(s) saved to disk")

    return messages


def run_compaction_pipeline(messages: list[dict]) -> list[dict]:
    """
    Run all three compaction layers in order:
    3 → 2 → 1 (most impactful first, structural trim last).
    """
    messages = toolResultBudget(messages)
    messages = microCompact(messages)
    messages = snipCompact(messages)
    return messages


# =============================================================================
# Todo system (from s04)
# =============================================================================

class TodoStatus(Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"

    def __str__(self):
        return self.value


@dataclass
class TodoItem:
    content: str
    status: TodoStatus = TodoStatus.PENDING

    def to_dict(self) -> dict:
        return {"content": self.content, "status": self.status.value}

    @staticmethod
    def from_dict(d: dict) -> "TodoItem":
        status_str = d.get("status", "pending")
        try:
            status = TodoStatus(status_str)
        except ValueError:
            status = TodoStatus.PENDING
        return TodoItem(content=d["content"], status=status)


class TodoManager:
    NAG_THRESHOLD = 3

    def __init__(self):
        self.todos: list[TodoItem] = []
        self.rounds_since_last_update: int = 0

    def write_todos(self, items: list[dict]) -> str:
        self.todos = [TodoItem.from_dict(item) for item in items]
        self.rounds_since_last_update = 0
        return self._format_todo_list()

    def mark_updated(self):
        self.rounds_since_last_update = 0

    def tick_round(self):
        if self.todos:
            self.rounds_since_last_update += 1

    def should_nag(self) -> bool:
        if not self.todos:
            return False
        if self.rounds_since_last_update < self.NAG_THRESHOLD:
            return False
        return any(t.status != TodoStatus.COMPLETED for t in self.todos)

    def get_nag_message(self) -> str:
        pending = sum(1 for t in self.todos if t.status != TodoStatus.COMPLETED)
        return (
            f"[SYSTEM REMINDER] You have {pending} incomplete task(s). "
            f"It has been {self.rounds_since_last_update} rounds since your last "
            f"todo update. Consider calling todo_write to update your plan."
        )

    def _format_todo_list(self) -> str:
        if not self.todos:
            return "(no tasks)"
        lines = []
        for i, item in enumerate(self.todos):
            marker = {"pending": "[ ]", "in_progress": "[~]", "completed": "[x]"}[item.status.value]
            lines.append(f"  {i+1}. {marker} {item.content}")
        return "\n".join(lines)

    def get_status_summary(self) -> str:
        total = len(self.todos)
        done = sum(1 for t in self.todos if t.status == TodoStatus.COMPLETED)
        in_prog = sum(1 for t in self.todos if t.status == TodoStatus.IN_PROGRESS)
        pending = total - done - in_prog
        return f"Tasks: {total} total, {done} done, {in_prog} in-progress, {pending} pending"


# =============================================================================
# Permission system (from s04)
# =============================================================================

class PermissionLevel(Enum):
    ALLOW = "allow"
    ASK = "ask"
    DENY = "deny"


@dataclass
class PermissionRule:
    tool_name: str
    level: PermissionLevel = PermissionLevel.ASK


class PermissionSystem:
    def __init__(self, interactive: bool = False):
        self.interactive = interactive
        self.rules: dict[str, PermissionLevel] = {
            "read_file": PermissionLevel.ALLOW,
            "write_file": PermissionLevel.ASK,
            "execute_command": PermissionLevel.ASK,
            "todo_write": PermissionLevel.ALLOW,
        }

    def check(self, tool_name: str) -> PermissionLevel:
        return self.rules.get(tool_name, PermissionLevel.ASK)

    def request_approval(self, tool_name: str, params: dict) -> bool:
        level = self.check(tool_name)
        if level == PermissionLevel.ALLOW:
            print(f"  [perm] AUTO-ALLOWED: {tool_name}")
            return True
        if level == PermissionLevel.DENY:
            print(f"  [perm] DENIED: {tool_name}")
            return False

        if not self.interactive:
            print(f"  [perm] AUTO-ALLOWED (non-interactive): {tool_name}")
            return True

        param_str = json.dumps(params, ensure_ascii=False, indent=2)
        try:
            response = input(
                f"  [perm] Allow tool '{tool_name}'?\n"
                f"    params: {param_str}\n"
                f"    (y/n/a=always): "
            ).strip().lower()
        except EOFError:
            print(f"  [perm] EOF — denied: {tool_name}")
            return False

        if response == "a":
            self.rules[tool_name] = PermissionLevel.ALLOW
            print(f"  [perm] '{tool_name}' added to allowlist.")
            return True
        return response == "y"


# =============================================================================
# Tool definitions
# =============================================================================

TOOL_DEFS = [
    {
        "name": "read_file",
        "description": "Read contents of a file at the given path.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path to read"}
            },
            "required": ["path"]
        }
    },
    {
        "name": "write_file",
        "description": "Write content to a file. Creates or overwrites.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "File path to write to"},
                "content": {"type": "string", "description": "Content to write"}
            },
            "required": ["path", "content"]
        }
    },
    {
        "name": "execute_command",
        "description": "Run a shell command and return its output.",
        "parameters": {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Shell command to execute"}
            },
            "required": ["command"]
        }
    },
    {
        "name": "todo_write",
        "description": (
            "Create or update a structured task list. "
            "Provide the FULL list — this REPLACES the previous list entirely."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "items": {
                    "type": "array",
                    "description": "The complete task list.",
                    "items": {
                        "type": "object",
                        "properties": {
                            "content": {"type": "string", "description": "Task description"},
                            "status": {
                                "type": "string",
                                "enum": ["pending", "in_progress", "completed"],
                            }
                        },
                        "required": ["content", "status"]
                    }
                }
            },
            "required": ["items"]
        }
    },
]

# Mock file system
MOCK_FS: dict[str, str] = {
    "/project/main.py": "print('hello world')\n",
    "/project/config.json": '{"version": "1.0"}\n',
}


# =============================================================================
# Tool execution
# =============================================================================

def safe_read_file(params: dict) -> str:
    path = params.get("path", "")
    if path in MOCK_FS:
        return f"[read_file result]\n{MOCK_FS[path]}"
    if path == "/project/large_log.txt":
        # Deliberately large result to trigger toolResultBudget
        large = "LARGE LOG FILE — " * 3000  # ~45000 chars
        return f"[read_file result]\n{large}"
    return f"[read_file error] File not found: {path}"


def safe_write_file(params: dict) -> str:
    path = params.get("path", "")
    content = params.get("content", "")
    MOCK_FS[path] = content
    return f"[write_file result] Written {len(content)} bytes to {path}"


def safe_execute_command(params: dict) -> str:
    cmd = params.get("command", "")
    if cmd.startswith("ls"):
        if "/project" in cmd:
            return "[execute_command result]\nmain.py\nconfig.json\nlarge_log.txt"
        return "[execute_command result]\nfile1.txt\nfile2.txt"
    return f"[execute_command result]\n(executed: {cmd})"


# =============================================================================
# Tool registry
# =============================================================================

class ToolRegistry:
    def __init__(self, todo_manager: TodoManager, perm_sys: PermissionSystem):
        self.todo_manager = todo_manager
        self.perm_sys = perm_sys
        self.handlers: dict[str, Callable[[dict], str]] = {
            "read_file": safe_read_file,
            "write_file": safe_write_file,
            "execute_command": safe_execute_command,
            "todo_write": self._handle_todo_write,
        }

    def execute(self, tool_name: str, params: dict) -> str:
        if tool_name not in self.handlers:
            return f"[error] Unknown tool: {tool_name}"
        if not self.perm_sys.request_approval(tool_name, params):
            return "[permission denied]"
        return self.handlers[tool_name](params)

    def _handle_todo_write(self, params: dict) -> str:
        items = params.get("items", [])
        result = self.todo_manager.write_todos(items)
        return (
            f"[todo_write result]\n"
            f"Task list updated:\n{result}\n"
            f"{self.todo_manager.get_status_summary()}"
        )


# =============================================================================
# Mock LLM — Extended conversation to demonstrate all 3 compaction layers
# =============================================================================

# Build a long conversation: ~30 turns of tool calls to accumulate >50 messages.
# Each turn with a tool call adds 2 messages (assistant + tool), plus system + user = 2.
# So 25 tool-call turns = 52+ messages → snipCompact triggers.
# One turn reads a deliberately large file → toolResultBudget triggers.
# Older tool results will be compacted by microCompact.

def build_long_conversation() -> list:
    """
    Returns a list of (tool_name, params_dict) or None for text-only responses.
    Designed to generate >50 messages in the conversation.
    """
    # Start with a plan
    turns = [
        ("todo_write", {
            "items": [
                {"content": "Read and analyze all project files", "status": "pending"},
                {"content": "Read the large log file for diagnostics", "status": "pending"},
                {"content": "Generate a summary report", "status": "pending"},
                {"content": "Verify everything compiles", "status": "pending"},
            ]
        }),
    ]

    # Many repetitive read_file operations to build up message count
    # Each adds 2 messages (assistant + tool_result) to the list
    files_to_read = [
        "/project/main.py",
        "/project/config.json",
        "/project/config.json",
        "/project/main.py",
        "/project/config.json",
        "/project/main.py",
        "/project/config.json",
        "/project/main.py",
        "/project/config.json",
        "/project/main.py",
        "/project/config.json",
        "/project/main.py",
        "/project/config.json",
        "/project/main.py",
        "/project/config.json",
        "/project/main.py",
        "/project/config.json",
        "/project/main.py",
        "/project/config.json",
        "/project/main.py",
        # Turn ~22: Read the deliberately large log file → triggers toolResultBudget
        "/project/large_log.txt",
        # More reads after the large one
        "/project/main.py",
        "/project/config.json",
    ]

    for f in files_to_read:
        turns.append(("read_file", {"path": f}))

    # Some execute_command calls
    for _ in range(4):
        turns.append(("execute_command", {"command": "ls /project"}))

    # Update todo to mark things done
    turns.append(("todo_write", {
        "items": [
            {"content": "Read and analyze all project files", "status": "completed"},
            {"content": "Read the large log file for diagnostics", "status": "completed"},
            {"content": "Generate a summary report", "status": "in_progress"},
            {"content": "Verify everything compiles", "status": "pending"},
        ]
    }))

    # More operations
    for _ in range(5):
        turns.append(("read_file", {"path": "/project/main.py"}))

    # Final todo update — all done
    turns.append(("todo_write", {
        "items": [
            {"content": "Read and analyze all project files", "status": "completed"},
            {"content": "Read the large log file for diagnostics", "status": "completed"},
            {"content": "Generate a summary report", "status": "completed"},
            {"content": "Verify everything compiles", "status": "completed"},
        ]
    }))

    turns.append(None)  # Text-only final response

    return turns


MOCK_CONVERSATION = build_long_conversation()

TEXT_ONLY_RESPONSES = [
    "Let me check the project files to understand the codebase.",
    "Reading more files to get a complete picture of the project.",
    "I need to examine additional files for context.",
    "Let me continue reading the remaining project files.",
    "The log file is very large. I'll read it for diagnostics.",
    "Continuing my analysis of the project structure.",
    "I have enough context now. Let me generate the summary.",
    "All tasks are complete. The project files have been analyzed.",
]


def mock_llm_call(messages: list[dict], turn: int) -> dict:
    """
    Returns a dict with either {"role":"assistant","content":"..."}
    or a dict with tool_calls.
    """
    if turn >= len(MOCK_CONVERSATION):
        return {"role": "assistant", "content": "All done! Let me know if you need anything else."}

    entry = MOCK_CONVERSATION[turn]

    if entry is None:
        idx = min(turn // 4, len(TEXT_ONLY_RESPONSES) - 1)
        return {"role": "assistant", "content": TEXT_ONLY_RESPONSES[idx]}

    tool_name, params = entry
    return {
        "role": "assistant",
        "content": f"I'll use {tool_name} to proceed.",
        "tool_calls": [
            {
                "id": f"call_{turn:03d}",
                "type": "function",
                "function": {
                    "name": tool_name,
                    "arguments": json.dumps(params, ensure_ascii=False)
                }
            }
        ]
    }


# =============================================================================
# Main agent loop
# =============================================================================

def format_tools_for_llm() -> str:
    return json.dumps(TOOL_DEFS, ensure_ascii=False, indent=2)


def print_banner():
    print("=" * 60)
    print("  s06 Context Compact — Build Your Own Code CLI")
    print("  Agent with 3-layer compaction pipeline")
    print("=" * 60)
    print()


def print_divider(label: str = ""):
    print(f"\n{'─' * 30} {label} {'─' * 30}")


def extract_tool_calls(response: dict) -> list[dict]:
    return response.get("tool_calls", [])


def run_agent():
    print_banner()

    todo_manager = TodoManager()
    perm_sys = PermissionSystem(interactive=False)
    registry = ToolRegistry(todo_manager, perm_sys)

    system_prompt = (
        "You are a coding agent with access to tools. "
        "Use todo_write to plan complex tasks before executing them. "
        "Keep your todo list updated as you work.\n\n"
        "Available tools:\n" + format_tools_for_llm()
    )

    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": "Analyze all project files in /project, including the large log file. Generate a summary."}
    ]

    MAX_TURNS = 50
    llm_turn = 0

    for agent_round in range(1, MAX_TURNS + 1):
        print_divider(f"Round {agent_round}")

        # --- Nag injection (from s04) ---
        if todo_manager.should_nag():
            nag = todo_manager.get_nag_message()
            print(f"  [nag] INJECTING: {nag}")
            messages.append({"role": "system", "content": nag})

        # ─────────────────────────────────────────
        # s06: The ONLY new line — compaction pipeline
        messages = run_compaction_pipeline(messages)
        # ─────────────────────────────────────────

        # --- Call LLM ---
        print(f"  [llm] Calling mock LLM (turn {llm_turn})...")
        response = mock_llm_call(messages, llm_turn)
        llm_turn += 1

        content = response.get("content", "")
        if content:
            print(f"  [assistant] {content}")

        messages.append(response)

        # --- Check for tool calls ---
        tool_calls = extract_tool_calls(response)
        if not tool_calls:
            todo_manager.tick_round()
            print(f"  [status] {todo_manager.get_status_summary()}")
            print(f"  [msg_count] {len(messages)} messages in history")
            continue

        # --- Execute tool calls ---
        for tc in tool_calls:
            func = tc.get("function", {})
            tool_name = func.get("name", "")
            try:
                params = json.loads(func.get("arguments", "{}"))
            except json.JSONDecodeError:
                params = {}

            print(f"  [tool_call] {tool_name}")

            result = registry.execute(tool_name, params)
            display = result[:200] + ("..." if len(result) > 200 else "")
            print(f"  [tool_result] {display}")

            messages.append({
                "role": "tool",
                "tool_call_id": tc.get("id", ""),
                "content": result,
            })

        todo_manager.tick_round()
        print(f"  [status] {todo_manager.get_status_summary()}")
        print(f"  [msg_count] {len(messages)} messages in history")

    print_divider("Agent finished")
    print(f"\nFinal todo list:\n{todo_manager._format_todo_list()}")
    print(f"\nFinal message count: {len(messages)}")


if __name__ == "__main__":
    run_agent()
