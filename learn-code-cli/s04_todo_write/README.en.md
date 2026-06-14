# s04 Todo Write — An Agent Without a Plan Drifts

> "An agent without a plan drifts."

## Overview

s04 builds on s03's permission system by adding **task planning and tracking** capabilities to the agent. The new `todo_write` tool lets the agent manage a structured task list, and a **Nag Reminder** mechanism ensures the agent doesn't forget to update its plan.

### Three Core Concepts

| Concept | Description |
|---------|-------------|
| **todo_write tool** | Agent-callable tool that takes an `items` array (each with `content` + `status`), replacing the entire task list |
| **Nag Reminder** | After 3 consecutive rounds without a `todo_write` call, a system reminder is injected before the next LLM call |
| **External State Management** | The task list lives in the agent process (not in LLM context); the LLM reads/writes state via tool calls |

### Task States

```
pending     → [ ] Not started
in_progress → [~] Working on it
completed   → [x] Done
```

## The Nag Reminder Mechanism

```
Round 1: todo_write → creates plan          nag_counter = 0
Round 2: read_file  → executes tool         nag_counter = 1
Round 3: write_file → executes tool         nag_counter = 2
Round 4: (text response, forgets to update) nag_counter = 3 → nag triggers!
Round 5: [SYSTEM REMINDER] You have N incomplete tasks...
         todo_write → updates task list      nag_counter = 0 (reset)
```

**Design notes:**
- Counter increments at the end of each round (if the task list is non-empty)
- When `nag_counter >= 3`, a nag message is injected **before** the next LLM call
- Calling `todo_write` immediately resets the counter to 0
- No nag when the task list is empty (nothing to track)

## What Changed from s03

s03 provided: agent loop, tool definitions and execution, permission system (allow/ask/deny).

s04 adds:

### 1. TodoManager Class (State Management)

```python
class TodoManager:
    NAG_THRESHOLD = 3  # nag after 3 rounds without update

    def __init__(self):
        self.todos: list[TodoItem] = []           # NEW: task list
        self.rounds_since_last_update: int = 0    # NEW: nag counter

    def write_todos(self, items): ...   # NEW: replace entire task list
    def should_nag(self) -> bool: ...   # NEW: check if nag needed
    def get_nag_message(self) -> str: ... # NEW: build reminder text
    def tick_round(self): ...           # NEW: called at end of each round
```

### 2. New todo_write Tool Definition

```python
{
    "name": "todo_write",
    "description": "Create or update a structured task list...",
    "parameters": {
        "items": {
            "type": "array",
            "items": {
                "content": {"type": "string"},
                "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]}
            }
        }
    }
}
```

### 3. Nag Injection in Agent Loop

```python
# BEFORE LLM call (NEW)
if todo_manager.should_nag():
    nag = todo_manager.get_nag_message()
    messages.append({"role": "system", "content": nag})

# LLM call
response = mock_llm_call(messages, llm_turn)

# AFTER each round (NEW)
todo_manager.tick_round()
```

### 4. Permission Default

s04 sets `todo_write` permission to `ALLOW` — planning tools are always permitted without user confirmation.

```python
rules["todo_write"] = PermissionLevel.ALLOW
```

## File Structure

```
s04_todo_write/
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
cd s04_todo_write/python
python code.py
```

### C++

```bash
cd s04_todo_write/cpp
g++ -std=c++17 -o s04_todo_write main.cpp
./s04_todo_write
```

### Java

```bash
cd s04_todo_write/java
javac Main.java
java Main
```

## Sample Output

```
============================================================
  s04 Todo Write — Build Your Own Code CLI
  Agent with task planning & nag reminder
============================================================

------------------------------ Round 1 ------------------------------
  [llm] Calling mock LLM (turn 0)...
  [assistant] I'll use todo_write to proceed.
  [tool_call] todo_write
  [perm] AUTO-ALLOWED: todo_write
  [tool_result] Task list updated:
    1. [ ] Read the main.py file
    2. [ ] Read the config.json file
    3. [ ] Add a greet() function to main.py
    4. [ ] Run the updated script to verify
  Tasks: 4 total, 0 done, 0 in-progress, 4 pending
  [nag_counter] rounds since last todo update: 0

...

------------------------------ Round 10 ------------------------------
  [nag] INJECTING: [SYSTEM REMINDER] You have 4 incomplete task(s)...

------------------------------ Round 11 ------------------------------
  [tool_call] todo_write
  ...
  [nag_counter] rounds since last todo update: 0  ← reset

...

------------------------------ Agent finished ------------------------------

Final todo list:
  1. [x] Read the main.py file
  2. [x] Read the config.json file
  3. [x] Add a greet() function to main.py
  4. [x] Run the updated script to verify
```

## Design Lessons

1. **Plans reduce divergence risk on long tasks** — with a task list, each action has a clear purpose
2. **State is separate from the LLM** — the LLM doesn't "remember" progress; it reads/writes external state via tools
3. **Nag injection is gentle guidance** — it doesn't interrupt the agent loop, just adds a system message to the next round's input
4. **The nag threshold is an empirical parameter** — too short is noisy, too long is useless; 3 rounds is a pragmatic default

## Next

[s05 Subagent](../s05_subagent/) — Let the agent spawn child agents for complex tasks, introducing hierarchical task delegation.
