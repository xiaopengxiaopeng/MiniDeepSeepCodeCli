# s08 Full Agent — "Many mechanisms, one loop."

The final lesson (lesson 8) of the "Build Your Own Code CLI" tutorial series, combining all 7 previous mechanisms into one complete, runnable agent.

---

## Series Recap: Eight Lessons in Review

| Lesson | Topic | Core Mechanism | Lines Added |
|--------|-------|---------------|-------------|
| s01 | **Agent Loop** | `while running:` — observe, plan, act, repeat | Baseline |
| s02 | **Multi-Tool** | Dispatch map: bash, read_file, write_file, edit_file, glob | +40 |
| s03 | **Permissions** | Deny list + destructive detection + user confirm | +30 |
| s04 | **Todo Write** | Task planning + stale nag reminders | +40 |
| s05 | **Subagents** | Clean-context task delegation | +35 |
| s06 | **Compaction** | Snip, micro, budget — three compression layers | +40 |
| s07 | **Error Recovery** | Exponential backoff retries + reactive compaction | +30 |

Each layer adds only ~30-40 lines and **does not change the core loop**.

---

## Architecture: One Loop, Seven Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                    s01: THE ONE AGENT LOOP                      │
│                                                                 │
│   while running:                                                │
│                                                                 │
│     ┌──────────────────────────────────────────────┐            │
│     │  s06: Context Compaction (before each step)  │            │
│     │  ┌──────┐  ┌───────┐  ┌────────┐            │            │
│     │  │ snip │→ │ micro │→ │ budget │            │            │
│     │  └──────┘  └───────┘  └────────┘            │            │
│     └──────────────────────────────────────────────┘            │
│                         ↓                                       │
│     ┌──────────────────────────────────────────────┐            │
│     │  s04: Nag Check (stale todo reminders)       │            │
│     └──────────────────────────────────────────────┘            │
│                         ↓                                       │
│     ┌──────────────────┐                                       │
│     │  Mock LLM.parse  │  ← parses user query                   │
│     └──────┬───────────┘                                       │
│            ↓                                                    │
│     ┌──────┴──────────────────────────────────┐                │
│     │         Route: three paths               │                │
│     │                                          │                │
│     │  ┌──────────┐  ┌────────┐  ┌─────────┐  │                │
│     │  │ Subagent │  │ Multi- │  │ Single  │  │                │
│     │  │ (s05)    │  │ step   │  │ tool    │  │                │
│     │  └────┬─────┘  └───┬────┘  └───┬─────┘  │                │
│     └───────┼────────────┼───────────┼────────┘                │
│             ↓            ↓           ↓                          │
│     ┌──────────────────────────────────────────────┐            │
│     │  s03: Permission Gate (before ANY tool)      │            │
│     │  ┌──────────┐  ┌─────────────┐  ┌─────────┐ │            │
│     │  │ Deny List│→ │ Destructive │→ │ User    │ │            │
│     │  │          │  │ Detection   │  │ Confirm │ │            │
│     │  └──────────┘  └─────────────┘  └─────────┘ │            │
│     └──────────────────────────────────────────────┘            │
│                         ↓                                       │
│     ┌──────────────────────────────────────────────┐            │
│     │  s02: Tool Dispatch Map                      │            │
│     │  ┌──────┐ ┌───────────┐ ┌──────────┐        │            │
│     │  │ bash │ │ read_file │ │ edit_file │  ...   │            │
│     │  └──────┘ └───────────┘ └──────────┘        │            │
│     └──────────────────────────────────────────────┘            │
│                         ↓                                       │
│     ┌──────────────────────────────────────────────┐            │
│     │  s07: Error Recovery (retry wrapper)         │            │
│     │  ┌────────────────┐  ┌──────────────────┐    │            │
│     │  │ Backoff × 3    │→ │ Reactive budget   │    │
│     │  │                │  │ compaction        │    │
│     │  └────────────────┘  └──────────────────┘    │            │
│     └──────────────────────────────────────────────┘            │
│                         ↓                                       │
│     ┌──────────────────────────────────────────────────┐        │
│     │  Observe → update context → mark todo → repeat   │        │
│     └──────────────────────────────────────────────────┘        │
│                                                                 │
│     s05: Collect subagent results → merge into context           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Core Design Principles

### 1. Loop Invariance
The core loop is `while True:` (or equivalent). From s01 through s08, this loop **has never changed**. Each mechanism layer simply inserts a thin checkpoint into this loop.

### 2. Thin Layers
Each mechanism is an independent module, called at a specific moment in the loop:
- **Pre-step**: s06 (compaction), s04 (nag)
- **Pre-execution**: s03 (permission check)
- **During execution**: s02 (tool dispatch), s07 (error retry)
- **Post-execution**: s05 (collect subagent results)

### 3. Composition Over Inheritance
No monolithic base class. Each component is standalone, held by the agent through composition:

```python
class FullAgent:
    def __init__(self):
        self.fs = VirtualFS()              # s02
        self.tool_map = build_tools()      # s02
        self.permissions = PermissionGate() # s03
        self.todo = TodoManager()          # s04
        self.subagents = SubagentManager() # s05
        self.compactor = ContextCompactor() # s06
        self.error_handler = ErrorRecovery() # s07
        self.llm = MockLLM()               # s01
```

---

## File Structure

```
s08_full_agent/
├── README.md          ← This file (Chinese)
├── README.en.md       ← English version
├── python/
│   └── code.py        ← Python reference implementation (~500 lines)
├── cpp/
│   └── main.cpp       ← C++17 implementation (~600 lines)
└── java/
    └── Main.java      ← Java 17 implementation (~600 lines)
```

---

## Running the Demo

### Python
```bash
cd s08_full_agent/python
python code.py
```

### C++
```bash
cd s08_full_agent/cpp
g++ -std=c++17 -Wall -O2 main.cpp -o agent
./agent
```

### Java
```bash
cd s08_full_agent/java
javac Main.java
java Main
```

---

## Demo Query Sequence

Each implementation's `main()` runs the same 25-query sequence, demonstrating every mechanism:

| # | Query | Mechanism Demonstrated |
|---|-------|----------------------|
| 1-7 | `read`,`write`,`edit`,`find`,`bash` | s02: Multi-tool dispatch |
| 8-9 | `bash rm -rf ...`, `bash chmod 777` | s03: Permission pipeline |
| 10 | `build a calculator app` | s04: Todo planning + s05: Subagents |
| 11-12 | `bash exit 1`, `bash fail` | s07: Error recovery (3 retries) |
| 13-14 | `subagent analyze ...` | s05: Subagent delegation |
| 15-27 | `bash echo 1..15` | s06: Context compaction trigger |
| 28 | `build a web server` | s04+s05: Another complex task |

---

## Mechanism Details

### s01: Agent Loop
```python
for query in queries:        # The main loop
    # s06: compaction check
    # s04: nag check
    plan = llm.parse(query)   # LLM parsing
    # Route: subagent / plan / single tool
    # Observe + update context
```
The loop itself is **8 lines**. Everything else is called from within.

### s02: Multi-Tool Dispatch
```python
tool_map = {
    "read_file":  lambda p: tool_read_file(fs, p),
    "write_file": lambda p: tool_write_file(fs, p),
    "edit_file":  lambda p: tool_edit_file(fs, p),
    "glob":       lambda p: tool_glob(fs, p),
    "bash":       lambda p: tool_bash(p),
}
```
Adding a new tool = 1 function + 1 dict entry. Loop unchanged.

### s03: Permission Pipeline
Three serial layers:
1. **Deny list** — hard-block matches (e.g., `rm -rf /`)
2. **Destructive detection** — regex pattern matching (e.g., `\brm\b`)
3. **User confirmation** — only triggered when auto-approve is off

### s04: Todo Write
- `add_task()` — break down complex plans
- `mark_done_by_hint()` — auto-complete after step execution
- `nag_if_stale()` — print reminders if tasks linger past threshold

### s05: Subagents
- `Subagent.run()` — mini agent loop with clean context
- `SubagentManager.collect()` — batch-collect results
- Parallel tasks automatically spawn subagents

### s06: Context Compaction
| Layer | Trigger | Action |
|-------|---------|--------|
| snip | History > 60 entries | Drop first half |
| micro | History > 30 entries | Summarize first half |
| budget | Budget > hard limit | Keep only last 5 entries |

### s07: Error Recovery
```
Attempt 1 ──fail──→ wait 0.5s
Attempt 2 ──fail──→ wait 1.0s
Attempt 3 ──fail──→ wait 2.0s
─────────────────→ trigger budget compaction
```

---

## Learning Path

1. **Read the code**: Start with `python/code.py`, it has the most detailed comments
2. **Trace a single query**: Watch how `read demo/file1.txt` flows through all 7 layers
3. **Trace a complex query**: Watch how `build a calculator app` triggers todo, subagents, and multi-step execution
4. **Modify the toolset**: Add a tool (e.g., `grep`) to `build_tool_map`
5. **Modify permissions**: Add your own patterns to `DENY_LIST`
6. **Compare languages**: See how the same architecture expresses in Python, C++, and Java

---

## Key Takeaway

> **"Many mechanisms, one loop."**
>
> The power of a good agent lies not in complex control flow but in thin layers that compose cleanly. Each mechanism solves one specific concern — safety, memory, reliability — without changing the core observe → plan → act loop.
>
> If you can understand this single-loop architecture, you can build any AI-powered development tool.

---

*"Build Your Own Code CLI" Tutorial Series — Lesson 8 of 8*
