# s07 Error Recovery — Errors Aren't the End, They're the Start of a Retry

> "Errors aren't the end, they're the start of a retry."

## Overview

s07 builds on s04's agent foundation by adding **error recovery** to LLM calls. Real-world APIs are unreliable: rate limits, server overloads, context overflow — these failures happen constantly. s07 equips the agent with three strategies to weather the storm:

| Strategy | Target Error | Mechanism |
|----------|-------------|-----------|
| **Exponential Backoff** | 429 Rate Limit / 503 Server Overload | Wait time doubles: 0.5s → 1s → 2s → 4s |
| **Reactive Compaction** | Context Too Long | When API signals context overflow, aggressively trim message history before retrying |
| **Max Retry Limit** | All error types | Cap at 5 retries, then fail gracefully |

### Why s07 comes after s06

s06 teaches **proactive compaction** — trimming context *before* hitting the limit. s07 teaches **reactive compaction** — trimming context *after* the API rejects the call. They complement each other:
- **s06 = prevention** (before): context near threshold → compact
- **s07 = cure** (after): API returns context_too_long → compact + retry

## Architecture

```
                    ┌──────────────────────────────────────────────────┐
                    │              Agent Loop (per round)              │
                    │                                                  │
                    │  1. Nag check (from s04)                        │
                    │  2. call_llm_with_retry()  ← s07 wraps this!   │
                    │  3. Handle response / tool calls                │
                    │  4. Tick nag counter                            │
                    └──────────────────────┬───────────────────────────┘
                                           │
                                           ▼
                    ┌──────────────────────────────────────────────────┐
                    │           call_llm_with_retry()                  │
                    │                                                  │
                    │  for attempt in 0..MAX_RETRIES:                  │
                    │    │                                             │
                    │    response = mock_llm.call(messages, turn,      │
                    │                               attempt)          │
                    │    │                                             │
                    │    if is_error(response):                        │
                    │      │                                           │
                    │      ├── RATE_LIMIT? ───────────────┐           │
                    │      │   → exponential backoff      │           │
                    │      │   → retry with same messages │           │
                    │      │                               │           │
                    │      ├── SERVER_OVERLOAD? ─────────┤           │
                    │      │   → exponential backoff      │           │
                    │      │   → retry with same messages │           │ 
                    │      │                               │           │
                    │      ├── CONTEXT_TOO_LONG? ────────┤           │
                    │      │   → compact_messages()       │           │
                    │      │   → retry with compacted msgs│           │
                    │      │                               │           │
                    │      └── max retries exceeded? ─────┘           │
                    │          → raise LLMError (fatal)                │
                    │                                                  │
                    │  return (response, messages)                     │
                    └──────────────────────────────────────────────────┘
```

## What Changed from s04

s04 provided: agent loop, todo_write tool, nag reminder, permission system.

s07 adds:

### 1. Error Type System

```python
class ErrorType(Enum):
    RATE_LIMIT = "rate_limit"          # HTTP 429 — too many requests
    SERVER_OVERLOAD = "server_overload" # HTTP 503 — server is busy
    CONTEXT_TOO_LONG = "context_too_long" # exceeds token limit
```

Each error type has a distinct recovery strategy:
- `RATE_LIMIT`: wait for the server-specified `retry_after`, or use exponential backoff
- `SERVER_OVERLOAD`: exponential backoff, wait for the server to recover
- `CONTEXT_TOO_LONG`: compact message history first, then retry

### 2. Exponential Backoff Retry

```python
MAX_RETRIES = 5
BASE_DELAY = 0.5  # seconds

def call_llm_with_retry(messages, turn, mock_llm):
    for attempt in range(MAX_RETRIES + 1):
        response = mock_llm.call(messages, turn, attempt)

        if is_error(response):
            if attempt >= MAX_RETRIES:
                raise LLMError("Max retries exceeded")

            # Exponential backoff: 0.5s → 1.0s → 2.0s → 4.0s → 8.0s
            delay = BASE_DELAY * (2 ** attempt)
            if retry_after > 0:
                delay = max(delay, retry_after)

            # Reactive compaction for context-length errors
            if error_type == CONTEXT_TOO_LONG:
                messages = compact_messages_aggressively(messages)

            time.sleep(delay)
            continue

        return response, messages
```

**Design notes:**
- Wait time doubles on each retry to avoid hammering an already-stressed server
- If the server provides a `retry_after` header (common with 429), prefer that value
- Context-length errors skip the wait — compact immediately and retry

### 3. Reactive Compaction

```python
def compact_messages_aggressively(messages):
    """When context is too long, keep only system prompt + last 6 messages."""
    system_msgs = [m for m in messages if m["role"] == "system"]
    other_msgs = [m for m in messages if m["role"] != "system"]

    KEEP_RECENT = 6
    if len(other_msgs) > KEEP_RECENT:
        dropped = len(other_msgs) - KEEP_RECENT
        marker = {
            "role": "system",
            "content": f"[CONTEXT COMPACTED] {dropped} messages removed."
        }
        return system_msgs + [marker] + other_msgs[-KEEP_RECENT:]

    return messages
```

Contrast with s06's proactive compaction:
- s06: **gradual** compaction as context approaches the limit (summarizes early messages)
- s07: **aggressive** compaction after API rejection (drops middle messages, keeps only recent ones)

### 4. Agent Loop Change

s04's LLM call was a single line:
```python
response = mock_llm_call(messages, llm_turn)
```

s07 wraps it:
```python
try:
    response, messages = call_llm_with_retry(messages, llm_turn, mock_llm)
except LLMError as e:
    print(f"[FATAL] LLM call failed after all retries: {e.message}")
    break
```

Everything else in the agent loop remains unchanged — this is the power of layered architecture.

### 5. Mock LLM Error Injection

To demonstrate error recovery, the mock LLM injects errors at specific turns:

```python
ERROR_PATTERN = {
    2: (ErrorType.RATE_LIMIT, 1),         # Turn 2: 1 rate-limit error then success
    5: (ErrorType.CONTEXT_TOO_LONG, 1),   # Turn 5: 1 context-overflow then success
    7: (ErrorType.SERVER_OVERLOAD, 2),    # Turn 7: 2 overload errors then success
}
```

The `attempt` parameter controls which call returns an error (attempt 0 = first call fails, attempt 1+ = retry succeeds).

## File Structure

```
s07_error_recovery/
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
cd s07_error_recovery/python
python code.py
```

### C++

```bash
cd s07_error_recovery/cpp
g++ -std=c++17 -o s07_error_recovery main.cpp
./s07_error_recovery
```

### Java

```bash
cd s07_error_recovery/java
javac Main.java
java Main
```

## Sample Output

```
============================================================
  s07 Error Recovery -- Build Your Own Code CLI
  Retry + Backoff + Reactive Compaction
============================================================

------------------------------ Round 1 ------------------------------
  [llm] Calling mock LLM (turn 0)...
  [assistant] I'll use todo_write to proceed.
  [perm] AUTO-ALLOWED: todo_write
  [tool_call] todo_write
  [tool_result] Task list updated:
  1. [ ] Read the main.py file
  2. [ ] Read the config.json file
  3. [ ] Add a greet() function to main.py
  4. [ ] Run the updated script to verify
  [status] Tasks: 4 total, 0 done, 0 in-progress, 4 pending

...

------------------------------ Round 3 ------------------------------
  [llm] Calling mock LLM (turn 2)...          ← ← ← RATE LIMIT HIT!
  [retry] rate_limit: Rate limit exceeded.     ← 2.0s backoff, then retry
           -- backoff 2.0s (attempt 1/5)...
  [assistant] I'll use read_file to proceed.   ← Retry succeeded!
  [tool_call] read_file

...

------------------------------ Round 6 ------------------------------
  [llm] Calling mock LLM (turn 5)...          ← ← ← CONTEXT TOO LONG!
  [compact] Context too long! Compacted 10 -> 7 messages
  [retry] context_too_long -> retrying with
           compacted context (attempt 1/5)...
  [assistant] I'll use write_file to proceed.  ← Retry with compacted context succeeded!
  [tool_call] write_file

...

------------------------------ Round 8 ------------------------------
  [llm] Calling mock LLM (turn 7)...          ← ← ← SERVER OVERLOAD!
  [retry] server_overload: Server overloaded.  ← 0.5s backoff, retry 1 fails
           -- backoff 0.5s (attempt 1/5)...
  [retry] server_overload: Server overloaded.  ← 1.0s backoff, retry 2 fails
           -- backoff 1.0s (attempt 2/5)...
  [assistant] I'll use execute_command...      ← Attempt 3 succeeds!
  [tool_call] execute_command

...

------------------------------ Agent finished ------------------------------

Final todo list:
  1. [x] Read the main.py file
  2. [x] Read the config.json file
  3. [x] Add a greet() function to main.py
  4. [x] Run the updated script to verify

  Error Recovery Stats:
    Total errors recovered:   4
    Rate limits handled:      1
    Server overloads handled: 2
    Context compactions:      1
```

## Design Lessons

1. **Error classification drives recovery strategy** — not all errors should be retried (400 Bad Request won't fix itself), and not all retries should work the same way. Rate limits need waiting, context overflow needs compaction.

2. **Exponential backoff protects both sides** — the client doesn't waste resources on doomed retries, and the server isn't crushed by a retry storm.

3. **Reactive compaction is a safety net** — even if proactive compaction (s06) misses something, the API-level error catches it.

4. **Max retries prevent infinite loops** — `MAX_RETRIES = 5` ensures the agent doesn't get stuck on persistent errors. After the limit, it exits gracefully so the user can intervene.

5. **The `attempt` parameter decouples testing from production** — the mock LLM uses `attempt` to decide whether to return an error or a real response, allowing the error recovery logic to be fully tested without a real API.

## Next

[s08 Full Agent](../s08_full_agent/) — Assemble all modules into a complete coding agent with multi-turn conversation and full workflow support.
