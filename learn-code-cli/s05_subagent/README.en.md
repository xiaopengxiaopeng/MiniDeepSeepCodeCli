# s05 Subagent — Big Tasks Split Small, Each Subtask Gets Clean Context

> "Big tasks split small, each subtask gets clean context."

## Overview

s05 builds on s04's task planning system by introducing **subagent delegation**. The main agent can use the `task` tool to delegate complex work to independent subagents, each with **fully isolated conversation context**, returning only a concise text summary.

### The Core Idea: Context Isolation

In real AI coding tools, conversation history grows with each turn. If the agent does lots of small searches, reads, and edits itself, the context quickly becomes bloated and confusing.

The **subagent pattern** solves this by:
- Splitting complex tasks into subtasks
- Running each subtask in a "clean room" — fresh message list, fresh system prompt
- Giving subagents **limited tools** (no `task` tool — prevents infinite recursion)
- Subagents return only their **final conclusion**, not internal details

### Three Core Concepts

| Concept | Description |
|---------|-------------|
| **task tool** | Main-agent-callable tool that takes `description` and `prompt`, launches a subagent |
| **Context isolation** | Subagent has its own `messages[]`, `system prompt`, completely isolated from the main agent |
| **Limited tool set** | Subagent can only use `read_file`, `write_file`, `execute_command`, `glob`, `edit` — no `task` (prevents recursion), no `todo_write` (not needed) |

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    Main Agent                           │
│                                                         │
│  messages[] ─────────────────────────────────────────   │
│  ┌─ system: "You are the main agent..."                │
│  ├─ user: "Analyze dir and refactor utils.py"          │
│  ├─ assistant: (calls task) ───────────┐               │
│  ├─ tool: [subagent summary] ◄─────────┤               │
│  ├─ assistant: (calls task) ───────────┤               │
│  ├─ tool: [subagent summary] ◄─────────┤               │
│  └─ assistant: "All done"              │               │
│                                         │               │
│  Tools: task, todo_write, read_file,    │               │
│         write_file, execute_command     │               │
└─────────────────────────────────────────┼───────────────┘
                                          │
          ┌───────────────────────────────┼───────────────────────────────┐
          │                               │                               │
          ▼                               ▼                               │
┌──────────────────────┐     ┌──────────────────────┐                    │
│   Subagent A          │     │   Subagent B          │                    │
│   "Analyze structure" │     │   "Refactor utils.py" │                    │
│                      │     │                      │                    │
│  messages[] (fresh)   │     │  messages[] (fresh)   │                    │
│  ┌─ system: "...sub  │     │  ┌─ system: "...sub  │                    │
│  │  agent, 15 turns" │     │  │  agent, 15 turns" │                    │
│  ├─ user: "list .py  │     │  ├─ user: "read      │                    │
│  │  files, summarize"│     │  │  utils.py, add fn"│                    │
│  ├─ assistant: glob  │     │  ├─ assistant: read  │                    │
│  ├─ tool: [result]   │     │  ├─ tool: [content]  │                    │
│  ├─ assistant: read  │     │  ├─ assistant: edit  │                    │
│  ├─ tool: [content]  │     │  ├─ tool: [done]     │                    │
│  ├─ assistant: read  │     │  └─ assistant: "added│                    │
│  ├─ tool: [content]  │     │     multiply() to    │                    │
│  └─ assistant:       │     │     utils.py"        │                    │
│     "Found 2 .py     │     │                      │                    │
│     files: main.py   │     │  Returns: text summary│                   │
│     has print()...   │     │  "[subagent summary]  │                    │
│     utils.py has     │     │   Added multiply(a,b) │                    │
│     add()"           │     │   to utils.py"        │                    │
│                      │     │                      │                    │
│  Returns: text       │     │  Available tools:     │                    │
│    summary           │     │  read_file, write_file│                    │
│                      │     │  glob, edit, exec_cmd │                    │
│  Available tools:    │     │  (no task!)           │                    │
│  read_file, write,   │     │                      │                    │
│  glob, edit, exec    │     │                      │                    │
│  (no task!)          │     │                      │                    │
└──────────────────────┘     └──────────────────────┘
```

### Subagent Constraints

| Constraint | Reason |
|------------|--------|
| Cannot call `task` | Prevents recursive spawning and infinite loops |
| Cannot call `todo_write` | Subagents don't need long-term planning |
| Max 15 turns | Prevents infinite loops; complex tasks should be split at the main agent level |
| No permission system | Subagents run in a controlled environment, no user interaction needed |
| Returns text summary only | Keeps the main agent's context clean |

## What Changed from s04

s04 provided: task planning (todo_write), nag reminder, permission system.

s05 adds:

### 1. task Tool Definition

```python
{
    "name": "task",
    "description": "Launch a new agent to handle complex, multi-step tasks autonomously...",
    "parameters": {
        "description": {"type": "string"},
        "prompt": {"type": "string"}
    }
}
```

### 2. run_subagent() Function (NEW)

```python
def run_subagent(description: str, prompt: str) -> str:
    """
    Spawn a subagent with its own clean context.
    Returns only the final assistant text.

    - Has its own system prompt
    - Has its own messages[] (fresh, isolated)
    - Limited tool set (NO task → no recursion)
    - Lower max turn limit (15)
    """
    messages = [
        {"role": "system", "content": "You are a subagent..."},
        {"role": "user", "content": f"Task: {prompt}"}
    ]

    for agent_round in range(1, SUBAGENT_MAX_TURNS + 1):
        response = mock_llm_call(...)
        if not response.get("tool_calls"):
            return FINAL_RESPONSES[description]  # text = final
        # Execute limited tools...

    return last_assistant_text
```

### 3. Subagent Tool Definitions (limited to 5)

```python
SUBAGENT_TOOL_DEFS = [
    "read_file", "write_file", "execute_command", "glob", "edit"
]
# Note: no task, no todo_write
```

### 4. ToolRegistry Integration

```python
class ToolRegistry:
    def __init__(self, todo_manager, perm_sys, spawn_subagent=None):
        if spawn_subagent:
            self.handlers["task"] = self._handle_task

    def _handle_task(self, params):
        result = self.spawn_subagent(description, prompt)
        return result  # subagent's text summary
```

### 5. Permission Default

The `task` tool permission is set to `ALLOW` — delegation is always permitted:

```python
rules["task"] = PermissionLevel.ALLOW
```

## File Structure

```
s05_subagent/
├── README.md          # This file (Chinese)
├── README.en.md       # This file (English)
├── python/
│   └── code.py        # Python implementation (complete & runnable)
├── cpp/
│   └── main.cpp       # C++17 implementation (single-file compilation)
└── java/
    └── Main.java      # Java 17 implementation (single-file compilation)
```

## Running

### Python

```bash
cd s05_subagent/python
python code.py
```

### C++

```bash
cd s05_subagent/cpp
g++ -std=c++17 -o s05_subagent main.cpp
./s05_subagent
```

### Java

```bash
cd s05_subagent/java
javac Main.java
java Main
```

## Sample Output

```
============================================================
  s05 Subagent -- Build Your Own Code CLI
  Agent delegates to isolated subagents
============================================================

------------------------------ Round 1 ------------------------------
  [llm] Calling mock LLM (turn 0)...
  [assistant] I'll use todo_write to proceed.
  [tool_call] todo_write
  [tool_result] Task list updated:
    1. [ ] Analyze the /project directory structure
    2. [ ] Refactor utils.py: add multiply function

------------------------------ Round 2 ------------------------------
  [tool_call] todo_write
  ...

------------------------------ Round 3 ------------------------------
  [tool_call] task

  ========================================
  [subagent:spawn] Description: Analyze project structure
  [subagent:spawn] Prompt: List all Python files in /project...
  [subagent] Let me find all Python files first.
  [subagent:tool] glob
  [subagent] Reading the file contents now.
  [subagent:tool] read_file
  [subagent:tool] read_file
  [subagent] Task complete.
  [subagent:done] Returned 154 chars
  ========================================

  [tool_result] [subagent summary] Found 2 Python files in /project:
    - main.py: contains print('hello world')
    - utils.py: contains function add(a, b)

------------------------------ Round 5 ------------------------------
  [tool_call] task

  ========================================
  [subagent:spawn] Description: Add multiply to utils
  [subagent] Reading the file contents now.
  [subagent:tool] read_file
  [subagent:tool] edit
  [subagent:tool] write_file
  [subagent:done] Returned 139 chars
  ========================================

  [tool_result] [subagent summary] Added multiply(a, b) function to
    /project/utils.py...

------------------------------ Agent finished ------------------------------

Final filesystem state:
  /project/utils.py:
    def add(a, b):
        return a + b

    def multiply(a, b):
        return a * b
```

## Design Lessons

1. **Context isolation is the cure for context bloat** — a subagent's internal details (searches, trial and error) never pollute the main agent's history
2. **Tool subsets are security boundaries** — subagents can't call `task`, eliminating infinite recursion at the root
3. **Returning only summaries is information compression** — the main agent doesn't need to know how many files were searched or what approaches were tried, just the conclusion
4. **Max turns is an empirical parameter** — subagent tasks should be "small and clear"; if it takes more than 15 turns, the task decomposition granularity needs refinement
5. **Main agent's message list stays clean** — the main history contains only `task` tool calls and their summary results, like calling a "smart function"

## Next

[s06 Context Compact](../s06_context_compact/) — What happens when context gets too long? Introducing context compaction.
