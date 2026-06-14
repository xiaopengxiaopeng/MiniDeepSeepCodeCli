# s06 Context Compact — Context Always Fills Up, Have a Way to Make Room

> "Context always fills up — have a way to make room."

## Overview

s06 builds on s04's agent loop by adding a **3-layer context compaction pipeline**. As the conversation grows, the `messages` list balloons — this increases LLM costs, latency, and can exceed context windows. The compaction pipeline runs automatically before every LLM call, pruning redundant information to keep conversations lean.

### Three Compaction Layers

| Layer | Name | Trigger | Strategy | Cost |
|-------|------|---------|----------|------|
| **Layer 1** | snipCompact | messages > 50 | Keep first 3 + last 47, replace middle with placeholder | Very low (array slicing) |
| **Layer 2** | microCompact | old tool results exist | Replace tool results (except 3 most recent) with `[compacted]` | Very low (string replace) |
| **Layer 3** | toolResultBudget | tool result > 30000 chars | Persist to disk, show preview only | Low (file I/O) |

> **No LLM summarization in this lesson.** LLM-based summarization (asking an LLM to summarize conversation history) is expensive and covered in later chapters. The three layers here are all "manual" compaction — cheap, deterministic, and predictable.

## Architecture Diagram

```
Each agent loop round starts
        │
        ▼
┌─────────────────────────────┐
│  messages = run_compaction_ │
│             pipeline(msg)   │  ← NEW: compaction pipeline
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────┐
│  Layer 3: toolResultBudget  │
│  For each tool message:     │
│    content > 30000 chars?   │
│      → save to .opencode/   │
│        tmp/                 │
│      → replace with preview │
└─────────────┬───────────────┘
              │ (large results now preview-only)
              ▼
┌─────────────────────────────┐
│  Layer 2: microCompact      │
│  For each tool message:     │
│    among the last 3?        │
│      → keep as-is           │
│    otherwise → "[compacted]"│
└─────────────┬───────────────┘
              │ (old tool results compressed)
              ▼
┌─────────────────────────────┐
│  Layer 1: snipCompact       │
│  If > 50 messages:          │
│    [0,1,2] ... placeholder  │
│    ... [N-47, ..., N-1]     │
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────┐
│  Call LLM (with compacted   │
│  messages)                  │
└─────────────────────────────┘
```

### Execution Order Rationale

Budget first (shrink individual message sizes) → microCompact next (compress old tool results) → snipCompact last (trim message count). Each step builds on the previous, maximizing compression efficiency.

## snipCompact in Detail

```
Original messages (60 items):
  [0] system
  [1] user
  [2] assistant
  [3] tool
  ...
  [57] assistant
  [58] tool
  [59] assistant

After snipCompact (51 items):
  [0] system           ← kept
  [1] user             ← kept
  [2] assistant        ← kept
  [3] system: "[COMPACTED: trimmed 7 middle messages]"
  [4] tool             ← was [53]
  ...
  [50] assistant       ← was [59]
```

**Design notes:**
- Threshold of 50 is empirical — most LLMs work well within this range
- Keeping the first 3 messages preserves system prompt + user question + first response
- Keeping the last 47 messages preserves recent context
- The placeholder is a `system`-role message, making it LLM-friendly

## microCompact in Detail

```
Tool message sequence:
  [tool-1] "read_file result: 5000 chars..."    → "[compacted]"
  [tool-2] "read_file result: 300 chars..."     → "[compacted]"
  [tool-3] "write_file result: 120 chars..."    → "[compacted]"
  [tool-4] "read_file result: 2400 chars..."    → kept (3rd most recent)
  [tool-5] "execute_command result: 80 chars..." → kept (2nd most recent)
  [tool-6] "read_file result: 100 chars..."     → kept (most recent)
```

**Design notes:**
- Only compacts `role == "tool"` messages — assistant and user messages matter for LLM understanding
- Keeps the 3 most recent tool results: if the LLM just read a file, it needs the content to continue
- `[compacted]` is a simple marker — tells the LLM there was old content but it's been cleared
- Does not affect system messages (including nag reminders)

## toolResultBudget in Detail

```
Original tool message (45000 chars → triggers budget):

[tool_result budget]
Path: .opencode/tmp/tool_result_001.txt
Preview:
  (first 500 chars)
  line 1: import os
  line 2: import sys
  ...
  (last 500 chars)
  line 895: if __name__ == "__main__":
  line 896:     main()
Size: 45000 chars (full content saved to disk)
```

**Design notes:**
- Threshold of 30000 chars — files larger than this only need a summary
- Saved to `.opencode/tmp/` — a unified temporary file directory
- Preview shows the first and last 500 chars — enough for the LLM to grasp the file structure
- File path is included in the message — the LLM can re-read if needed

## What Changed from s04

s04 provided: agent loop, task planning with Nag Reminder, permission system.

s06 adds:

### 1. Three Compaction Functions

```python
def snipCompact(messages, max_messages=50):
    """Layer 1: Trim middle messages when list grows too long."""
    ...

def microCompact(messages, keep_recent=3):
    """Layer 2: Replace old tool results with '[compacted]'."""
    ...

def toolResultBudget(messages, max_chars=30000):
    """Layer 3: Persist large tool results to disk."""
    ...
```

### 2. Compaction Pipeline

```python
def run_compaction_pipeline(messages):
    messages = toolResultBudget(messages)   # Layer 3 first: shrink big results
    messages = microCompact(messages)       # Layer 2: compact old tool results
    messages = snipCompact(messages)        # Layer 1: trim middle if too many
    return messages
```

### 3. The Only New Line in Agent Loop

```python
# BEFORE the LLM call (NEW)
messages = run_compaction_pipeline(messages)

# LLM call
response = mock_llm_call(messages, llm_turn)
```

**That's the entire change.** The rest of the agent loop (tool execution, nag injection, permission checks) remains untouched.

## File Structure

```
s06_context_compact/
├── README.md           # This file (Chinese)
├── README.en.md        # This file (English)
├── python/
│   └── code.py         # Python implementation (complete & runnable)
├── cpp/
│   └── main.cpp        # C++17 implementation (single-file compilation)
└── java/
    └── Main.java       # Java 17 implementation (single-file compilation)
```

## Running

### Python

```bash
cd s06_context_compact/python
python code.py
```

### C++

```bash
cd s06_context_compact/cpp
g++ -std=c++17 -o s06_context_compact main.cpp
./s06_context_compact
```

### Java

```bash
cd s06_context_compact/java
javac Main.java
java Main
```

## Sample Output

```
============================================================
  s06 Context Compact -- Build Your Own Code CLI
  Agent with 3-layer compaction pipeline
============================================================

------------------------------ Round 1 ------------------------------
  [llm] Calling mock LLM (turn 0)...
  [assistant] I'll start by planning the task.

...

------------------------------ Round 10 ------------------------------
  [compact] toolResultBudget: 1 large result(s) saved to disk
  [compact] microCompact: 5 tool result(s) compacted
  [compact] snipCompact: trimmed 12 middle messages (60 → 51)

  [llm] Calling mock LLM (turn 9)...
  ...
```

## Design Lessons

1. **Layering is the best practice for dealing with uncertainty** — each layer solves one independent problem; layers can be toggled or tuned individually
2. **Cheap first** — snipCompact and microCompact don't need I/O or API calls, so they run first
3. **Keep "just enough" context** — don't delete everything; find the sweet spot where the LLM still has what it needs
4. **Compaction is transparent to the LLM** — placeholder messages use natural language, so the LLM understands what happened
5. **Compaction is a silent assistant** — neither the agent nor the user needs to care about compaction logic; it runs automatically in the background

## Next

[s07 Error Recovery](../s07_error_recovery/) — Tool calls can fail; the agent needs to know how to recover gracefully.
