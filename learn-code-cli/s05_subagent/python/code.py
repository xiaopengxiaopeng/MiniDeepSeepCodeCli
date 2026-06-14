"""
s05 Subagent — Python
Build on s04's todo system. Adds `task` tool that spawns a subagent
with clean context isolation. The subagent runs its own agent_loop with
a limited tool set (read_file, write_file, execute_command, glob, edit),
cannot recurse (no task tool), and returns only its final text summary.

Key design:
  - subagent has its own system prompt and messages[]
  - subagent tools are a subset (bash, read, write, edit, glob) — NO task
  - subagent has a lower max_turn limit (15)
  - when done, extracts last assistant text and returns it to main agent
  - main agent never sees subagent's internal conversation
"""

import json
import sys
import re
from dataclasses import dataclass, field
from typing import Any, Callable, Optional
from enum import Enum

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
            "todo_write": PermissionLevel.ALLOW,
            "task": PermissionLevel.ALLOW,   # Delegation is always allowed
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

# Full tool set for main agent (includes task and todo_write)
MAIN_TOOL_DEFS = [
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
    {
        "name": "task",
        "description": (
            "Launch a new agent to handle complex, multi-step tasks autonomously. "
            "The subagent has access to read_file, write_file, execute_command, glob, and edit tools. "
            "It runs in an isolated context and returns only its final text summary. "
            "Use this for tasks that would otherwise bloat the main conversation with details."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "description": {
                    "type": "string",
                    "description": "A short (3-5 word) description of the task"
                },
                "prompt": {
                    "type": "string",
                    "description": "The task for the subagent to perform"
                }
            },
            "required": ["description", "prompt"]
        }
    },
]

# Subagent tools: a strict subset — NO todo_write, NO task (prevents recursion)
SUBAGENT_TOOL_DEFS = [
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
        "name": "glob",
        "description": "Find files matching a glob pattern.",
        "parameters": {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Glob pattern to match"}
            },
            "required": ["pattern"]
        }
    },
    {
        "name": "edit",
        "description": "Perform exact string replacement in a file.",
        "parameters": {
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "Path to the file to edit"},
                "old_string": {"type": "string", "description": "Text to replace"},
                "new_string": {"type": "string", "description": "Replacement text"}
            },
            "required": ["file_path", "old_string", "new_string"]
        }
    },
]

# Mock file system
MOCK_FS: dict[str, str] = {
    "/project/main.py": "print('hello world')\n",
    "/project/config.json": '{"version": "1.0"}\n',
    "/project/utils.py": "def add(a, b):\n    return a + b\n",
}


# =============================================================================
# Tool execution (shared between main and subagent)
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
            return "[execute_command result]\nmain.py\nconfig.json\nutils.py"
        return "[execute_command result]\nfile1.txt\nfile2.txt"
    if cmd.startswith("python") and "main.py" in cmd:
        return "[execute_command result]\nhello world"
    return f"[execute_command result]\n(executed: {cmd})"

def safe_glob(params: dict) -> str:
    pattern = params.get("pattern", "")
    if "*.py" in pattern:
        return "[glob result]\nmain.py\nutils.py"
    if "*.json" in pattern:
        return "[glob result]\nconfig.json"
    return "[glob result]\n(no matches)"

def safe_edit(params: dict) -> str:
    file_path = params.get("file_path", "")
    old_string = params.get("old_string", "")
    new_string = params.get("new_string", "")

    if file_path not in MOCK_FS:
        return f"[edit error] File not found: {file_path}"

    content = MOCK_FS[file_path]
    if old_string not in content:
        return f"[edit error] old_string not found in {file_path}"

    MOCK_FS[file_path] = content.replace(old_string, new_string, 1)
    return f"[edit result] Applied edit to {file_path}"


# =============================================================================
# Tool registry
# =============================================================================

class ToolRegistry:
    def __init__(self, todo_manager: TodoManager, perm_sys: PermissionSystem,
                 spawn_subagent: Optional[Callable] = None):
        self.todo_manager = todo_manager
        self.perm_sys = perm_sys
        self.spawn_subagent = spawn_subagent
        self.handlers: dict[str, Callable[[dict], str]] = {
            "read_file": safe_read_file,
            "write_file": safe_write_file,
            "execute_command": safe_execute_command,
            "todo_write": self._handle_todo_write,
            "glob": safe_glob,
            "edit": safe_edit,
        }
        if spawn_subagent:
            self.handlers["task"] = self._handle_task

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

    def _handle_task(self, params: dict) -> str:
        if not self.spawn_subagent:
            return "[task error] Subagent spawning not configured"
        description = params.get("description", "subtask")
        prompt = params.get("prompt", "")
        print(f"\n  {'='*40}")
        print(f"  [subagent:spawn] Description: {description}")
        print(f"  [subagent:spawn] Prompt: {prompt[:100]}{'...' if len(prompt) > 100 else ''}")
        result = self.spawn_subagent(description, prompt)
        print(f"  [subagent:done] Returned {len(result)} chars")
        print(f"  {'='*40}\n")
        return result


# =============================================================================
# Mock LLM
# =============================================================================

# Main agent conversation: plans with todo_write, then delegates to subagent
MAIN_CONVERSATION = [
    # Turn 1: Plan
    (
        "todo_write",
        {
            "items": [
                {"content": "Analyze the /project directory structure", "status": "pending"},
                {"content": "Refactor utils.py: add multiply function", "status": "pending"},
            ]
        }
    ),
    # Turn 2: Mark first in_progress
    (
        "todo_write",
        {
            "items": [
                {"content": "Analyze the /project directory structure", "status": "in_progress"},
                {"content": "Refactor utils.py: add multiply function", "status": "pending"},
            ]
        }
    ),
    # Turn 3: Delegate "analyze directory" to subagent via task tool
    (
        "task",
        {
            "description": "Analyze project structure",
            "prompt": "List all Python files in /project, read each one, and summarize what functions they contain."
        }
    ),
    # Turn 4: After subagent result, mark analysis done, start refactor
    (
        "todo_write",
        {
            "items": [
                {"content": "Analyze the /project directory structure", "status": "completed"},
                {"content": "Refactor utils.py: add multiply function", "status": "in_progress"},
            ]
        }
    ),
    # Turn 5: Delegate refactor to subagent
    (
        "task",
        {
            "description": "Add multiply to utils",
            "prompt": "Read /project/utils.py, then use edit to add a multiply(a, b) function that returns a * b."
        }
    ),
    # Turn 6: Final update — all done
    (
        "todo_write",
        {
            "items": [
                {"content": "Analyze the /project directory structure", "status": "completed"},
                {"content": "Refactor utils.py: add multiply function", "status": "completed"},
            ]
        }
    ),
    # Turn 7: Done
    None,
]

MAIN_TEXT_RESPONSES = [
    "Let me plan this first.",
    "I'll start by understanding the project structure.",
    "Good, the subagent found main.py and utils.py. Now let me refactor utils.py.",
    "Both subagents completed successfully. Let me verify the results.",
    "All tasks are complete!",
]

# Subagent mock conversations — keyed by description prefix
# Each entry is a list of scripted turns (same format as main conversation)
SUBAGENT_CONVERSATIONS = {
    "Analyze project structure": [
        # glob for *.py
        ("glob", {"pattern": "*.py"}),
        # read main.py
        ("read_file", {"path": "/project/main.py"}),
        # read utils.py
        ("read_file", {"path": "/project/utils.py"}),
        # Final summary (text-only)
        None,
    ],
    "Add multiply to utils": [
        # read utils.py
        ("read_file", {"path": "/project/utils.py"}),
        # edit to add multiply
        (
            "edit",
            {
                "file_path": "/project/utils.py",
                "old_string": "def add(a, b):\n    return a + b",
                "new_string": "def add(a, b):\n    return a + b\n\ndef multiply(a, b):\n    return a * b"
            }
        ),
        # write_file as fallback confirmation
        (
            "write_file",
            {
                "path": "/project/utils.py",
                "content": "def add(a, b):\n    return a + b\n\ndef multiply(a, b):\n    return a * b\n"
            }
        ),
        # Final summary (text-only)
        None,
    ],
}

SUBAGENT_TEXT_RESPONSES = [
    "Let me find all Python files first.",
    "Reading the file contents now.",
    "I'll add the multiply function to utils.py.",
    "Task complete.",
    "Here's a summary of what I did.",
]

SUBAGENT_FINAL_RESPONSES = {
    "Analyze project structure": (
        "[subagent summary] Found 2 Python files in /project:\n"
        "  - main.py: contains print('hello world')\n"
        "  - utils.py: contains function add(a, b)"
    ),
    "Add multiply to utils": (
        "[subagent summary] Added multiply(a, b) function to /project/utils.py. "
        "File now contains add() and multiply() functions."
    ),
}


def mock_llm_call(messages: list[dict], turn: int,
                  conversation: list = None,
                  text_responses: list = None) -> dict:
    """Unified mock LLM. Uses provided conversation list or defaults."""

    if conversation is None:
        conversation = MAIN_CONVERSATION
    if text_responses is None:
        text_responses = MAIN_TEXT_RESPONSES

    if turn >= len(conversation):
        return {"role": "assistant", "content": "All done! Let me know if you need anything else."}

    entry = conversation[turn]

    if entry is None:
        idx = min(turn // 3, len(text_responses) - 1)
        return {"role": "assistant", "content": text_responses[idx]}

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
# Subagent loop (NEW in s05)
# =============================================================================

def run_subagent(description: str, prompt: str) -> str:
    """
    Spawn a subagent with its own clean context.
    Returns only the final assistant text (not the full conversation).

    The subagent:
      - Has its own system prompt
      - Has its own messages[] (fresh, isolated)
      - Has a limited tool set (no task tool → no recursion)
      - Has a lower max turn limit (15)
    """
    # Select the mock conversation for this subagent
    conversation = SUBAGENT_CONVERSATIONS.get(description, [None])

    # Build subagent's isolated messages
    system_prompt = (
        "You are a subagent. You have access to a limited set of tools "
        "(read_file, write_file, execute_command, glob, edit). "
        "Work efficiently — you have limited turns. "
        "When your task is complete, provide a concise text summary of your results."
    )

    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"Task: {prompt}"}
    ]

    SUBAGENT_MAX_TURNS = 15
    sub_turn = 0

    for agent_round in range(1, SUBAGENT_MAX_TURNS + 1):
        # Call LLM with subagent's own conversation and text responses
        response = mock_llm_call(
            messages, sub_turn,
            conversation=conversation,
            text_responses=SUBAGENT_TEXT_RESPONSES
        )
        sub_turn += 1

        content = response.get("content", "")
        if content:
            print(f"  [subagent] {content}")

        messages.append(response)

        # Check for tool calls
        tool_calls = response.get("tool_calls", [])
        if not tool_calls:
            # Text-only response — this is the final summary, return it
            final = SUBAGENT_FINAL_RESPONSES.get(description, content)
            return final

        # Execute tool calls
        for tc in tool_calls:
            func = tc.get("function", {})
            tool_name = func.get("name", "")
            try:
                params = json.loads(func.get("arguments", "{}"))
            except json.JSONDecodeError:
                params = {}

            print(f"  [subagent:tool] {tool_name}")

            # Execute (always allowed in subagent, no permission system)
            if tool_name == "read_file":
                result = safe_read_file(params)
            elif tool_name == "write_file":
                result = safe_write_file(params)
            elif tool_name == "execute_command":
                result = safe_execute_command(params)
            elif tool_name == "glob":
                result = safe_glob(params)
            elif tool_name == "edit":
                result = safe_edit(params)
            else:
                result = f"[error] Subagent cannot use tool: {tool_name}"

            messages.append({
                "role": "tool",
                "tool_call_id": tc.get("id", ""),
                "content": result,
            })

    # Exceeded max turns — return last assistant content
    for msg in reversed(messages):
        if msg.get("role") == "assistant" and msg.get("content"):
            return msg["content"]

    return "[subagent error] Exceeded max turns with no output"


# =============================================================================
# Main agent loop (from s04, enhanced with task/subagent)
# =============================================================================

def format_tools_for_llm() -> str:
    return json.dumps(MAIN_TOOL_DEFS, ensure_ascii=False, indent=2)


def print_banner():
    print("=" * 60)
    print("  s05 Subagent — Build Your Own Code CLI")
    print("  Agent delegates to isolated subagents")
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

    # We need a forward reference to the registry for the task handler
    registry_ref = {"registry": None}

    def spawn_subagent(description: str, prompt: str) -> str:
        return run_subagent(description, prompt)

    registry = ToolRegistry(todo_manager, perm_sys, spawn_subagent=spawn_subagent)

    system_prompt = (
        "You are a coding agent with access to tools. "
        "Use todo_write to plan complex tasks before executing them. "
        "Keep your todo list updated as you work. "
        "For complex multi-step subtasks, use the task tool to spawn a subagent. "
        "The subagent will work independently and return a summary.\n\n"
        "Available tools:\n" + format_tools_for_llm()
    )

    messages: list[dict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": "Analyze the /project directory and add a multiply() function to utils.py. Use subagents where appropriate."}
    ]

    MAX_TURNS = 15
    llm_turn = 0

    for agent_round in range(1, MAX_TURNS + 1):
        print_divider(f"Round {agent_round}")

        # Nag injection (from s04)
        if todo_manager.should_nag():
            nag = todo_manager.get_nag_message()
            print(f"  [nag] INJECTING: {nag}")
            messages.append({"role": "system", "content": nag})

        # Call LLM
        print(f"  [llm] Calling mock LLM (turn {llm_turn})...")
        response = mock_llm_call(messages, llm_turn)
        llm_turn += 1

        content = response.get("content", "")
        if content:
            print(f"  [assistant] {content}")

        messages.append(response)

        # Check for tool calls
        tool_calls = extract_tool_calls(response)
        if not tool_calls:
            todo_manager.tick_round()
            print(f"  [status] {todo_manager.get_status_summary()}")
            continue

        # Execute tool calls
        for tc in tool_calls:
            func = tc.get("function", {})
            tool_name = func.get("name", "")
            try:
                params = json.loads(func.get("arguments", "{}"))
            except json.JSONDecodeError:
                params = {}

            print(f"  [tool_call] {tool_name}")

            result = registry.execute(tool_name, params)
            display = result[:250]
            if len(result) > 250:
                display += "..."
            print(f"  [tool_result] {display}")

            messages.append({
                "role": "tool",
                "tool_call_id": tc.get("id", ""),
                "content": result,
            })

        todo_manager.tick_round()
        print(f"  [status] {todo_manager.get_status_summary()}")

    print_divider("Agent finished")
    print(f"\nFinal todo list:\n{todo_manager._format_todo_list()}")
    print(f"\nFinal filesystem state:")
    for path, content in sorted(MOCK_FS.items()):
        print(f"  {path}:\n{content}")


if __name__ == "__main__":
    run_agent()
