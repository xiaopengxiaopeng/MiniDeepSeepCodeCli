"""
s07 Error Recovery — Python
Builds on s04's todo_write + permission system. Wraps the LLM call in a retry loop
with exponential backoff for rate limiting (429) and server overload (503).
Adds reactive compaction when the API responds with "context too long".

Error recovery patterns:
  1. Exponential backoff: retry with increasing delays (BASE_DELAY * 2^attempt)
  2. Reactive compaction: on context-length errors, aggressively truncate messages
  3. Max retry limits with graceful fallback

The mock LLM injects occasional errors to demonstrate recovery in action.
"""

import json
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Optional
from enum import Enum

# =============================================================================
# Error types (NEW in s07)
# =============================================================================

class ErrorType(Enum):
    RATE_LIMIT = "rate_limit"          # HTTP 429
    SERVER_OVERLOAD = "server_overload" # HTTP 503
    CONTEXT_TOO_LONG = "context_too_long" # Context exceeds token limit

@dataclass
class LLMError(Exception):
    error_type: ErrorType
    message: str
    retry_after: float = 0.0  # Seconds the server told us to wait

# =============================================================================
# Context compaction (NEW in s07)
# =============================================================================

def estimate_token_count(messages: list[dict]) -> int:
    """Rough estimate: ~4 chars per token."""
    return sum(len(json.dumps(m, ensure_ascii=False)) for m in messages) // 4

def compact_messages_aggressively(messages: list[dict]) -> list[dict]:
    """
    When context is too long, aggressively truncate to keep only:
      - System prompt (always keep)
      - Last N messages (keep recent context)
    Drops middle messages that are less essential.
    """
    if len(messages) <= 4:
        return messages  # Not enough to compact meaningfully

    system_msgs = [m for m in messages if m["role"] == "system"]
    other_msgs = [m for m in messages if m["role"] != "system"]

    # Keep system prompt + last 6 non-system messages
    KEEP_RECENT = 6
    if len(other_msgs) > KEEP_RECENT:
        dropped = len(other_msgs) - KEEP_RECENT
        compacted = system_msgs + other_msgs[-KEEP_RECENT:]

        # Insert a compaction marker message
        marker = {
            "role": "system",
            "content": f"[CONTEXT COMPACTED] {dropped} earlier messages were removed "
                       f"to recover from context-length error. Continue from the remaining context."
        }
        # Insert after the system prompt but before recent messages
        result = system_msgs + [marker] + other_msgs[-KEEP_RECENT:]
        return result

    return messages

# =============================================================================
# Todo system (from s04)
# =============================================================================

class TodoStatus(Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    COMPLETED = "completed"

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
        return True  # simplified for demo

# =============================================================================
# Tool definitions (from s04)
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
            "Each item has 'content' and 'status' (pending, in_progress, completed). "
            "Provide the FULL list — this REPLACES the previous list entirely."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "items": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "content": {"type": "string"},
                            "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]}
                        },
                        "required": ["content", "status"]
                    }
                }
            },
            "required": ["items"]
        }
    },
]

MOCK_FS: dict[str, str] = {
    "/project/main.py": "print('hello world')\n",
    "/project/config.json": '{"version": "1.0"}\n',
}

# =============================================================================
# Tool handlers (from s04)
# =============================================================================

def safe_read_file(params: dict) -> str:
    path = params.get("path", "")
    if path in MOCK_FS:
        return f"[read_file result]\n{MOCK_FS[path]}"
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
            return "[execute_command result]\nmain.py\nconfig.json"
        return "[execute_command result]\nfile1.txt\nfile2.txt"
    return f"[execute_command result]\n(executed: {cmd})"

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
# Retry logic (NEW in s07)
# =============================================================================

MAX_RETRIES = 5
BASE_DELAY = 0.5  # seconds

def call_llm_with_retry(
    messages: list[dict],
    turn: int,
    mock_llm: "MockLLM",
) -> tuple[dict, list[dict], dict[str, int]]:
    """
    Wrap the LLM call in a retry loop with exponential backoff.
    On CONTEXT_TOO_LONG errors, trigger reactive compaction before retrying.
    Returns (response, possibly_compacted_messages, error_stats).
    """
    total_attempts = 0
    stats = {"total_errors": 0, "rate_limits": 0, "server_overloads": 0, "context_compactions": 0}

    while total_attempts <= MAX_RETRIES:
        total_attempts += 1
        response = mock_llm.call(messages, turn, total_attempts - 1)

        if not mock_llm.is_error(response):
            return response, messages, stats

        error_type, msg, retry_after = mock_llm.get_error_info(response)
        stats["total_errors"] += 1

        if error_type == ErrorType.RATE_LIMIT:
            stats["rate_limits"] += 1
        elif error_type == ErrorType.SERVER_OVERLOAD:
            stats["server_overloads"] += 1
        elif error_type == ErrorType.CONTEXT_TOO_LONG:
            stats["context_compactions"] += 1

        if total_attempts > MAX_RETRIES:
            raise LLMError(
                error_type,
                f"Max retries ({MAX_RETRIES}) exceeded: {msg}"
            )

        delay = BASE_DELAY * (2 ** (total_attempts - 1))
        if retry_after > 0:
            delay = max(delay, retry_after)

        if error_type == ErrorType.CONTEXT_TOO_LONG:
            old_count = len(messages)
            messages = compact_messages_aggressively(messages)
            new_count = len(messages)
            print(f"  [compact] Context too long! Compacted {old_count} -> {new_count} messages")
            print(f"  [retry] {error_type.value} -> retrying with compacted context "
                  f"(attempt {total_attempts}/{MAX_RETRIES})...")
            time.sleep(delay)
            continue

        print(f"  [retry] {error_type.value}: {msg} — "
              f"backoff {delay:.1f}s (attempt {total_attempts}/{MAX_RETRIES})...")
        time.sleep(delay)

    raise LLMError(ErrorType.RATE_LIMIT, "Unreachable — retry loop exhausted")

# =============================================================================
# Mock LLM with error injection (NEW in s07)
# =============================================================================

# Conversation script: (tool_name, params_dict) or None for text-only
# Error patterns injected at specific turns to demonstrate recovery.

ERROR_PATTERN: dict[int, tuple[ErrorType, int, str]] = {
    # turn: (error_type, failures_before_success, message)
    2: (ErrorType.RATE_LIMIT, 1,
        "Rate limit exceeded. Please retry after 2 seconds."),
    5: (ErrorType.CONTEXT_TOO_LONG, 1,
        "Context length exceeds the model's maximum of 4096 tokens."),
    7: (ErrorType.SERVER_OVERLOAD, 2,
        "Server is overloaded. Please try again later."),
}

MOCK_CONVERSATION = [
    # Turn 0: todo_write — plan
    (
        "todo_write",
        {"items": [
            {"content": "Read the main.py file", "status": "pending"},
            {"content": "Read the config.json file", "status": "pending"},
            {"content": "Add a greet() function to main.py", "status": "pending"},
            {"content": "Run the updated script to verify", "status": "pending"},
        ]}
    ),
    # Turn 1: todo_write — mark first in_progress
    (
        "todo_write",
        {"items": [
            {"content": "Read the main.py file", "status": "in_progress"},
            {"content": "Read the config.json file", "status": "pending"},
            {"content": "Add a greet() function to main.py", "status": "pending"},
            {"content": "Run the updated script to verify", "status": "pending"},
        ]}
    ),
    # Turn 2: read_file — will trigger RATE_LIMIT on first attempt!
    ("read_file", {"path": "/project/main.py"}),

    # Turn 3: todo_write — first done, start config
    (
        "todo_write",
        {"items": [
            {"content": "Read the main.py file", "status": "completed"},
            {"content": "Read the config.json file", "status": "in_progress"},
            {"content": "Add a greet() function to main.py", "status": "pending"},
            {"content": "Run the updated script to verify", "status": "pending"},
        ]}
    ),
    # Turn 4: read_file config
    ("read_file", {"path": "/project/config.json"}),

    # Turn 5: write_file — will trigger CONTEXT_TOO_LONG on first attempt!
    (
        "write_file",
        {
            "path": "/project/main.py",
            "content": (
                "def greet(name):\n"
                "    return f'Hello, {name}!'\n\n"
                "print(greet('World'))\n"
            )
        }
    ),

    # Turn 6: todo_write — mark write done, start verify
    (
        "todo_write",
        {"items": [
            {"content": "Read the main.py file", "status": "completed"},
            {"content": "Read the config.json file", "status": "completed"},
            {"content": "Add a greet() function to main.py", "status": "completed"},
            {"content": "Run the updated script to verify", "status": "in_progress"},
        ]}
    ),

    # Turn 7: execute_command — will trigger SERVER_OVERLOAD twice!
    ("execute_command", {"command": "python /project/main.py"}),

    # Turn 8: todo_write — all done
    (
        "todo_write",
        {"items": [
            {"content": "Read the main.py file", "status": "completed"},
            {"content": "Read the config.json file", "status": "completed"},
            {"content": "Add a greet() function to main.py", "status": "completed"},
            {"content": "Run the updated script to verify", "status": "completed"},
        ]}
    ),
    # Turn 9: text only — job done
    None,
]

TEXT_ONLY_RESPONSES = [
    "Let me plan this out first.",
    "Starting with reading main.py to understand the current code.",
    "Now let me read the config to check for any settings I should preserve.",
    "Good. The config is minimal — I can proceed with adding greet().",
    "Writing the updated main.py with the greet() function.",
    "Let me verify the script runs correctly.",
    "The script executed successfully. All tasks are now complete.",
]


class MockLLM:
    """
    Mock LLM that produces pre-scripted tool calls AND can inject errors
    at specific turns to demonstrate error recovery.

    Error pattern:
      - Turn 2: rate_limit (fails 1 time, then succeeds)
      - Turn 5: context_too_long (fails 1 time, triggers compaction, then succeeds)
      - Turn 7: server_overload (fails 2 times, then succeeds)

    The 'attempt' parameter controls how many times this same turn has been
    called. On attempt 0, return the error. On subsequent attempts, return
    the real response.
    """

    def __init__(self):
        self.error_attempts: dict[int, int] = {}  # turn -> errors returned so far

    def call(self, messages: list[dict], turn: int, attempt: int) -> dict:
        """Return either an error response or the real LLM response for this turn."""

        # Check if this turn should produce an error on this attempt
        if turn in ERROR_PATTERN:
            error_type, max_failures, message = ERROR_PATTERN[turn]
            if attempt < max_failures:
                retry_after = 2.0 if error_type == ErrorType.RATE_LIMIT else 0.0
                return self._make_error(error_type, message, retry_after)

        # Return the real response
        return self._make_real_response(turn)

    def is_error(self, response: dict) -> bool:
        return response.get("__error__", False)

    def get_error_info(self, response: dict) -> tuple[ErrorType, str, float]:
        return (
            ErrorType(response.get("error_type", "rate_limit")),
            response.get("content", "Unknown error"),
            response.get("retry_after", 0.0),
        )

    def _make_error(self, error_type: ErrorType, message: str, retry_after: float = 0.0) -> dict:
        return {
            "role": "assistant",
            "content": message,
            "__error__": True,
            "error_type": error_type.value,
            "retry_after": retry_after,
            "tool_calls": [],
        }

    def _make_real_response(self, turn: int) -> dict:
        if turn >= len(MOCK_CONVERSATION):
            return {
                "role": "assistant",
                "content": "All tasks are complete!",
                "__error__": False,
            }

        entry = MOCK_CONVERSATION[turn]

        if entry is None:
            idx = min(turn // 2, len(TEXT_ONLY_RESPONSES) - 1)
            return {
                "role": "assistant",
                "content": TEXT_ONLY_RESPONSES[idx],
                "__error__": False,
            }

        tool_name, params = entry
        return {
            "role": "assistant",
            "content": f"I'll use {tool_name} to proceed.",
            "__error__": False,
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
# Print helpers
# =============================================================================

def print_banner():
    print("=" * 60)
    print("  s07 Error Recovery — Build Your Own Code CLI")
    print("  Retry + Backoff + Reactive Compaction")
    print("=" * 60)
    print()

def print_divider(label: str = ""):
    print(f"\n{'─' * 30} {label} {'─' * 30}")

def extract_tool_calls(response: dict) -> list[dict]:
    return response.get("tool_calls", [])

# =============================================================================
# Main agent loop (with retry wrapper — NEW in s07)
# =============================================================================

def format_tools_for_llm() -> str:
    return json.dumps(TOOL_DEFS, ensure_ascii=False, indent=2)

def run_agent():
    print_banner()

    todo_manager = TodoManager()
    perm_sys = PermissionSystem(interactive=False)
    registry = ToolRegistry(todo_manager, perm_sys)
    mock_llm = MockLLM()

    system_prompt = (
        "You are a coding agent with access to tools. "
        "Use todo_write to plan complex tasks before executing them. "
        "Keep your todo list updated as you work.\n\n"
        "Available tools:\n" + format_tools_for_llm()
    )

    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": "Add a greet() function to /project/main.py that takes a name and returns a greeting."}
    ]

    MAX_TURNS = 20
    llm_turn = 0
    stats = {"total_errors": 0, "rate_limits": 0, "server_overloads": 0, "context_compactions": 0}
    text_only_count = 0  # Consecutive text-only responses for early exit

    for agent_round in range(1, MAX_TURNS + 1):
        print_divider(f"Round {agent_round}")

        # --- Nag injection (from s04) ---
        if todo_manager.should_nag():
            nag = todo_manager.get_nag_message()
            print(f"  [nag] INJECTING: {nag}")
            messages.append({"role": "system", "content": nag})

        # --- Call LLM with retry (NEW in s07) ---
        print(f"  [llm] Calling mock LLM (turn {llm_turn})...")

        try:
            response, messages, round_stats = call_llm_with_retry(messages, llm_turn, mock_llm)
        except LLMError as e:
            print(f"  [FATAL] LLM call failed after all retries: {e.message}")
            print(f"  [FATAL] Error type: {e.error_type.value}")
            break

        for k in stats:
            stats[k] += round_stats[k]
        llm_turn += 1

        content = response.get("content", "")
        if content:
            print(f"  [assistant] {content}")

        messages.append(response)

        # --- Check for tool calls ---
        tool_calls = extract_tool_calls(response)
        if not tool_calls:
            text_only_count += 1
            todo_manager.tick_round()
            print(f"  [status] {todo_manager.get_status_summary()}")
            # Early exit: 2 consecutive text-only + all tasks done = finished
            all_done = all(t.status == TodoStatus.COMPLETED for t in todo_manager.todos)
            if all_done and text_only_count >= 2:
                print(f"  [done] All tasks completed, agent stopping.")
                break
            continue
        else:
            text_only_count = 0

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
            print(f"  [tool_result] {result[:200]}{'...' if len(result) > 200 else ''}")

            messages.append({
                "role": "tool",
                "tool_call_id": tc.get("id", ""),
                "content": result,
            })

        todo_manager.tick_round()
        print(f"  [status] {todo_manager.get_status_summary()}")

    print_divider("Agent finished")
    print(f"\nFinal todo list:\n{todo_manager._format_todo_list()}")
    print(f"\n  Error Recovery Stats:")
    print(f"    Total errors recovered:   {stats['total_errors']}")
    print(f"    Rate limits handled:      {stats['rate_limits']}")
    print(f"    Server overloads handled: {stats['server_overloads']}")
    print(f"    Context compactions:      {stats['context_compactions']}")

if __name__ == "__main__":
    run_agent()
