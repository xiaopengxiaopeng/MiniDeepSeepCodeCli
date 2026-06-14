"""
s04 Todo Write — Python
Build on s03's permission system. Adds todo_write tool, current_todos list,
and a nag reminder injected after 3 rounds without a todo update.

The agent loop stays the same structure — only adds the todo_write handler
and the nag injection before LLM calls.
"""

import json
import sys
import re
from dataclasses import dataclass, field
from typing import Any, Callable, Optional
from enum import Enum

# =============================================================================
# Todo system (NEW in s04)
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
    """
    Manages the agent's todo list and tracks how many rounds have passed
    without a todo update. After NAG_THRESHOLD rounds, a nag reminder is
    injected into the conversation.
    """

    NAG_THRESHOLD = 3

    def __init__(self):
        self.todos: list[TodoItem] = []
        self.rounds_since_last_update: int = 0

    def write_todos(self, items: list[dict]) -> str:
        """Replace the entire todo list with new items."""
        self.todos = [TodoItem.from_dict(item) for item in items]
        self.rounds_since_last_update = 0
        return self._format_todo_list()

    def mark_updated(self):
        """Call when the agent uses todo_write — resets the nag counter."""
        self.rounds_since_last_update = 0

    def tick_round(self):
        """Called at the end of each agent round. Increments nag counter."""
        if self.todos:
            self.rounds_since_last_update += 1

    def should_nag(self) -> bool:
        if not self.todos:
            return False
        if self.rounds_since_last_update < self.NAG_THRESHOLD:
            return False
        # Only nag if there are incomplete tasks worth updating
        return any(t.status != TodoStatus.COMPLETED for t in self.todos)

    def get_nag_message(self) -> str:
        """Return the nag reminder string to inject before the next LLM call."""
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
# Permission system (from s03)
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
            "todo_write": PermissionLevel.ALLOW,   # Always allow planning
        }
        self.last_answer: Optional[bool] = None

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

        # ASK mode — non-interactive: auto-allow for tutorial smoothness
        # (set interactive=True to enable real permission prompts)
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

@dataclass
class Tool:
    name: str
    description: str
    parameters: dict
    handler: Callable[[dict], str]


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
            "Create or update a structured task list for your current coding session. "
            "Use this to plan complex tasks, track progress, and demonstrate thoroughness. "
            "Each item has a 'content' (description) and 'status' (pending, in_progress, or completed). "
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
                                "description": "Current status of this task"
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

# Mock file system for read_file / write_file
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
    return f"[read_file error] File not found: {path}"

def safe_write_file(params: dict) -> str:
    path = params.get("path", "")
    content = params.get("content", "")
    MOCK_FS[path] = content
    return f"[write_file result] Written {len(content)} bytes to {path}"

def safe_execute_command(params: dict) -> str:
    cmd = params.get("command", "")
    # Very limited mock — for demo only
    if cmd.startswith("ls"):
        if "/project" in cmd:
            return "[execute_command result]\nmain.py\nconfig.json"
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

        # Permission check (from s03)
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
# Mock LLM (supports todo_write tool calls)
# =============================================================================

# A pre-scripted conversation that demonstrates todo_write planning,
# working through items, forgetting to update, then getting nagged.
# Each entry is: (tool_name, params_dict) or None for text-only responses.

MOCK_CONVERSATION = [
    # Turn 1: Agent plans the task
    (
        "todo_write",
        {
            "items": [
                {"content": "Read the main.py file", "status": "pending"},
                {"content": "Read the config.json file", "status": "pending"},
                {"content": "Add a greet() function to main.py", "status": "pending"},
                {"content": "Run the updated script to verify", "status": "pending"},
            ]
        }
    ),
    # Turn 2: Start working — mark first item in_progress, read file
    (
        "todo_write",
        {
            "items": [
                {"content": "Read the main.py file", "status": "in_progress"},
                {"content": "Read the config.json file", "status": "pending"},
                {"content": "Add a greet() function to main.py", "status": "pending"},
                {"content": "Run the updated script to verify", "status": "pending"},
            ]
        }
    ),
    # Turn 3: read_file
    ("read_file", {"path": "/project/main.py"}),
    # Turn 4: Mark first item done, mark second in_progress, read config
    (
        "todo_write",
        {
            "items": [
                {"content": "Read the main.py file", "status": "completed"},
                {"content": "Read the config.json file", "status": "in_progress"},
                {"content": "Add a greet() function to main.py", "status": "pending"},
                {"content": "Run the updated script to verify", "status": "pending"},
            ]
        }
    ),
    # Turn 5: read_file
    ("read_file", {"path": "/project/config.json"}),
    # Turn 6: Mark config done, start editing
    (
        "todo_write",
        {
            "items": [
                {"content": "Read the main.py file", "status": "completed"},
                {"content": "Read the config.json file", "status": "completed"},
                {"content": "Add a greet() function to main.py", "status": "in_progress"},
                {"content": "Run the updated script to verify", "status": "pending"},
            ]
        }
    ),
    # Turn 7: write_file
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
    # Turn 8: AGENT FORGETS to update todo — just textual response
    None,
    # Turn 9: Still forgets — another text-only response
    None,
    # Turn 10: Still forgetting — third text-only response (nag threshold reached)
    None,
    # Turn 11: Nag kicks in — agent updates todo
    (
        "todo_write",
        {
            "items": [
                {"content": "Read the main.py file", "status": "completed"},
                {"content": "Read the config.json file", "status": "completed"},
                {"content": "Add a greet() function to main.py", "status": "completed"},
                {"content": "Run the updated script to verify", "status": "in_progress"},
            ]
        }
    ),
    # Turn 12: execute_command
    ("execute_command", {"command": "python /project/main.py"}),
    # Turn 13: Final update — all done
    (
        "todo_write",
        {
            "items": [
                {"content": "Read the main.py file", "status": "completed"},
                {"content": "Read the config.json file", "status": "completed"},
                {"content": "Add a greet() function to main.py", "status": "completed"},
                {"content": "Run the updated script to verify", "status": "completed"},
            ]
        }
    ),
    # Turn 14: Done — text only
    None,
]

TEXT_ONLY_RESPONSES = [
    "I'll need to read the existing files first to understand the codebase.",
    "Found greet() already exists — let me verify the implementation is correct.",
    "The code looks good. Let me double-check the config to ensure no conflicts.",
    "All tasks are complete! The greet() function has been added and verified.",
]


def mock_llm_call(messages: list[dict], turn: int) -> dict:
    """
    Simulates an LLM response. Uses a pre-scripted conversation.
    Returns a dict with either {"role":"assistant","content":"..."}
    or {"role":"assistant","content":...,"tool_calls":[...]}.
    """
    if turn >= len(MOCK_CONVERSATION):
        return {"role": "assistant", "content": "All done! Let me know if you need anything else."}

    entry = MOCK_CONVERSATION[turn]

    if entry is None:
        # Text-only response
        idx = min(turn // 3, len(TEXT_ONLY_RESPONSES) - 1)
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
    print("  s04 Todo Write — Build Your Own Code CLI")
    print("  Agent with task planning & nag reminder")
    print("=" * 60)
    print()


def print_divider(label: str = ""):
    print(f"\n{'─' * 30} {label} {'─' * 30}")


def extract_tool_calls(response: dict) -> list[dict]:
    """Extract tool_calls from an LLM response dict."""
    return response.get("tool_calls", [])


def run_agent():
    print_banner()

    todo_manager = TodoManager()
    perm_sys = PermissionSystem(interactive=False)
    registry = ToolRegistry(todo_manager, perm_sys)

    # System prompt
    system_prompt = (
        "You are a coding agent with access to tools. "
        "Use todo_write to plan complex tasks before executing them. "
        "Keep your todo list updated as you work. "
        "When the system reminds you about stale todos, update them promptly.\n\n"
        "Available tools:\n" + format_tools_for_llm()
    )

    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": "Add a greet() function to /project/main.py that takes a name and returns a greeting."}
    ]

    MAX_TURNS = 20
    llm_turn = 0

    for agent_round in range(1, MAX_TURNS + 1):
        print_divider(f"Round {agent_round}")

        # --- Nag injection (NEW in s04) ---
        if todo_manager.should_nag():
            nag = todo_manager.get_nag_message()
            print(f"  [nag] INJECTING: {nag}")
            messages.append({"role": "system", "content": nag})

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
            # Text-only response — tick the nag counter and continue
            todo_manager.tick_round()
            print(f"  [status] {todo_manager.get_status_summary()}")
            print(f"  [nag_counter] rounds since last todo update: {todo_manager.rounds_since_last_update}")
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

            # Execute via registry (includes permission check)
            result = registry.execute(tool_name, params)
            print(f"  [tool_result] {result[:200]}{'...' if len(result) > 200 else ''}")

            # Append tool result as a tool message
            messages.append({
                "role": "tool",
                "tool_call_id": tc.get("id", ""),
                "content": result,
            })

        # Tick the nag counter
        todo_manager.tick_round()
        print(f"  [status] {todo_manager.get_status_summary()}")
        print(f"  [nag_counter] rounds since last todo update: {todo_manager.rounds_since_last_update}")

    print_divider("Agent finished")
    print(f"\nFinal todo list:\n{todo_manager._format_todo_list()}")


if __name__ == "__main__":
    run_agent()
