# s03 Permission System — The 3-Gate Pipeline

## Core Concept

s02's agent has 5 tools and can do anything. But "can do" and "should do" are very different. You can't let an LLM run `rm -rf /` on a user's machine.

**s03's answer: the 3-gate permission pipeline. Set boundaries first, then grant freedom.**

```
User Request → Agent Loop
                 │
                 ▼
            ┌──────────────┐
            │   LLM decides │  ← Completely unchanged
            │  returns tool │
            └──────┬───────┘
                   │
                   ▼
            ┌──────────────────────────────────────┐
            │      3-Gate Permission Pipeline      │
            │         (s03 addition)               │
            │                                      │
            │  ┌─────────────────────────────────┐ │
            │  │  Gate 1: Hard Deny List         │ │
            │  │  rm -rf /, sudo, mkfs, fork bomb│ │
            │  │  → BLOCK immediately, no prompt │ │
            │  └─────────────┬───────────────────┘ │
            │                ▼                      │
            │  ┌─────────────────────────────────┐ │
            │  │  Gate 2: Destructive Detection  │ │
            │  │  rm, mv, chmod, Remove-Item...  │ │
            │  │  → Flag as "needs confirmation" │ │
            │  └─────────────┬───────────────────┘ │
            │                ▼                      │
            │  ┌─────────────────────────────────┐ │
            │  │  Gate 3: User Confirmation      │ │
            │  │  "Allow this command? [y/N]"    │ │
            │  │  → User has the final say       │ │
            │  └─────────────┬───────────────────┘ │
            │                │                      │
            └────────────────┼──────────────────────┘
                             │
                             ▼
                      ┌──────────────┐
                      │ Execute Tool │  ← Allowed or blocked
                      └──────────────┘
```

The philosophy behind the three gates:

- **Gate 1 (Hard Deny)**：Things that must never happen. Match → block immediately. **Don't waste the user's attention.**
- **Gate 2 (Detection)**：Things that might be risky. Match → flag. **Let the user make an informed decision.**
- **Gate 3 (Confirmation)**：The user is the final gate. Default to deny (`[y/N]`). **Prevent accidents.**

## s02 → s03 Changes

| Dimension | s02 | s03 |
|-----------|-----|-----|
| Tool count | 5 | 5 (unchanged) |
| Agent loop | Unchanged | **Unchanged** |
| Tool execution | Direct execution | Passes through 3-gate check before execution |
| New concepts | — | Permission pipeline, hard deny, destructive detection, user confirmation |
| Code change volume | — | Only one wrapper function around `execute_tool()` |

## Code Walkthrough

### 1. The Permission Pipeline

The core function `check_permission(command)` implements all three gates:

```python
def check_permission(command: str) -> tuple[bool, str]:
    # Gate 1: Hard deny list — regex match → direct rejection
    for pattern in HARD_DENY_PATTERNS:
        if re.search(pattern, command, re.IGNORECASE):
            return False, f"BLOCKED: matches hard-deny pattern '{pattern}'"

    # Gate 2: Destructive command detection — match → flag
    destructive_reason = None
    for pattern, desc in DESTRUCTIVE_PATTERNS:
        if re.search(pattern, command, re.IGNORECASE):
            destructive_reason = desc
            break

    if destructive_reason is None:
        return True, "OK"  # Safe command, allow through

    # Gate 3: User confirmation — default deny
    response = input(f"⚠ Destructive: {destructive_reason}\nAllow? [y/N]: ")
    if response.lower() in ('y', 'yes'):
        return True, "confirmed by user"
    return False, "denied by user"
```

### 2. Hard Deny List

Patterns that are **absolutely forbidden**. Any match → direct BLOCK. Never reaches Gate 2 or Gate 3.

```python
HARD_DENY_PATTERNS = [
    r'rm\s+-rf\s+/',               # rm -rf / and variants
    r'rm\s+-rf\s+--no-preserve-root',
    r'sudo\s+',                    # any sudo command
    r'>\s*/dev/sda',               # direct disk write
    r'mkfs\.',                     # filesystem formatting
    r'dd\s+if=',                   # dd disk operations
    r':\(\)\s*\{\s*:\|:&\s*\};:',  # fork bomb
    r'chmod\s+-R\s+777\s+/',      # world-writable root
    r'wget\s+.*\|\s*sh',          # pipe to shell
    r'curl\s+.*\|\s*bash',        # pipe to bash
    r'shutdown',                   # system shutdown
    r'reboot',                     # system reboot
]
```

### 3. Destructive Command Detection

Patterns that are **potentially risky**. Match → user must confirm.

```python
DESTRUCTIVE_PATTERNS = [
    (r'\brm\b',           "rm - removes files/directories"),
    (r'\bmv\b',           "mv - moves/renames files"),
    (r'\bdel\b',          "del - deletes files (Windows)"),
    (r'\brmdir\b',        "rmdir - removes directories"),
    (r'>\s*\S',           "redirect overwrite - overwrites file content"),
    (r'\bchmod\b',        "chmod - changes permissions"),
    (r'\bchown\b',        "chown - changes ownership"),
    (r'Remove-Item',      "Remove-Item - deletes files (PowerShell)"),
    (r'New-Item.*-Force', "Force flag - may overwrite"),
]
```

### 4. Insertion Point: The Only Changed Line in the Agent Loop

The agent loop itself is **untouched**. The only change is wrapping the call to `execute_tool()`:

```python
# s02 original:
for tc in response["tool_calls"]:
    result = execute_tool(tc)             # Direct execution
    messages.append({"role": "tool", "content": result})

# s03 — one line wraps the call:
for tc in response["tool_calls"]:
    result = execute_with_permission(tc)  # ← The ONLY change
    messages.append({"role": "tool", "content": result})
```

The wrapper function `execute_with_permission`:

```python
def execute_with_permission(tc):
    """Wrap s02's execute_tool with permission checking."""
    if tc["name"] == "bash":
        command = tc["arguments"]["command"]
        allowed, reason = check_permission(command)
        if not allowed:
            return f"PERMISSION DENIED: {reason}"
    return execute_tool(tc)
```

**Key design decision**: Only the `bash` tool goes through the permission pipeline. File tools (`read_file`, `write_file`, `glob`) are secured by s02's `safe_path()` path sandboxing.

### 5. Complete Agent Loop (Unchanged Portion)

```python
def agent_loop(messages):
    for turn in range(MAX_TURNS):
        response = call_llm(messages, TOOL_DEFINITIONS)
        messages.append({"role": "assistant", "content": response})

        if not response.get("tool_calls"):
            return response["content"]

        for tc in response["tool_calls"]:
            result = execute_with_permission(tc)  # s03: the only change
            messages.append({"role": "tool", "content": result})
```

## Design Principle

> **"Set boundaries first, then grant freedom."**

The permission pipeline isn't an on/off switch — it's a progressively narrowing funnel:

- Gate 1 catches the absolutely forbidden (saves attention)
- Gate 2 marks the potentially risky (saves effort)
- Gate 3 lets the user decide (takes responsibility)

The agent loop is completely unaware that permissions exist — it just says "give me a tool, I'll give you a result." This is the payoff from s02's dispatch map pattern: **every extension is inserted at the correct abstraction layer, leaving the core loop untouched.**

## Demo Output

Interactive mode (user must respond at Gate 3):

```
=== s03: Permission Pipeline ===

User: Clean up temp files and check disk

--- Turn 1 ---
[bash] ls /tmp/cache
  Permission: OK (not destructive)
  Result: cache.log  temp.dat  old.sql

--- Turn 2 ---
[bash] rm /tmp/cache/cache.log
  Permission: ⚠ DESTRUCTIVE: rm - removes files/directories
  Allow this command? [y/N]: y
  Result: (no output)

--- Turn 3 ---
[bash] sudo rm -rf /var/cache
  Permission: BLOCKED by hard deny list

--- Turn 4 ---
[write_file] cleanup_report.txt
  Result: Written to cleanup_report.txt

--- Turn 5 ---
Agent finished (no tool calls).
```

Non-interactive mode (`--auto` flag):

```
python code.py --auto "Clean up temp files and check disk"
# Gate 3 auto-approves, suitable for automated testing
```

See the corresponding directories for complete implementations in all three languages.
