# s02 Tool Use — The Dispatch Map Pattern

## Core Concept

s01 had only one tool: `bash`. Want to add a second tool? You'd be tempted to write `if tool == "read"` inside the loop. Then a third, a fourth… soon the loop becomes spaghetti.

**s02's answer: the dispatch map.**

```python
TOOL_HANDLERS = {
    "bash":      handle_bash,
    "read_file": handle_read_file,
    "write_file": handle_write_file,
    "edit_file": handle_edit_file,
    "glob":      handle_glob,
}
```

Tool execution becomes:

```python
handler = TOOL_HANDLERS[tool_name]
result = handler(args)
```

The loop itself stays **untouched**. Adding a tool = writing one handler function + adding one entry to the dictionary.

## s01 → s02 Changes

| Dimension | s01 | s02 |
|-----------|-----|-----|
| Tool count | 1 (bash) | 5 (bash, read_file, write_file, edit_file, glob) |
| Tool dispatch | Hard-coded `if` | TOOL_HANDLERS dictionary dispatch |
| Path safety | None | Workspace boundary enforcement |
| New concepts | — | Dispatch map, path sandboxing |

## New Tools at a Glance

| Tool | Parameters | Function |
|------|-----------|----------|
| `bash` | `command: string` | Execute shell commands in workspace |
| `read_file` | `path: string`, `offset?: int`, `limit?: int` | Read file contents (with line-number pagination) |
| `write_file` | `path: string`, `content: string` | Create/overwrite files |
| `edit_file` | `path: string`, `old_string: string`, `new_string: string`, `replace_all?: bool` | Exact string replacement |
| `glob` | `pattern: string` | Match files by glob pattern, sorted by modification time |

## Code Walkthrough

### 1. Tool Definitions

Each tool declares its name, description, and parameter schema. This is the interface contract sent to the LLM — the LLM sees these definitions and decides which tool to call and with what arguments.

```python
TOOL_DEFINITIONS = [
    {
        "name": "read_file",
        "description": "Read a file from the workspace...",
        "parameters": {
            "path": {"type": "string", ...},
            "offset": {"type": "integer", ...},
            "limit": {"type": "integer", ...},
        },
    },
    ...
]
```

### 2. Dispatch Map (The Key Pattern)

A dictionary mapping tool names to handler functions. This is the core pattern of this lesson.

```python
TOOL_HANDLERS = {
    "bash":      run_bash,
    "read_file": run_read_file,
    "write_file": run_write_file,
    "edit_file": run_edit_file,
    "glob":      run_glob,
}
```

When executing a tool call:

```python
def execute_tool(tool_call):
    name = tool_call["name"]
    args = tool_call["arguments"]
    handler = TOOL_HANDLERS.get(name)
    if handler is None:
        return f"Unknown tool: {name}"
    return handler(args)
```

### 3. Agent Loop (Identical to s01)

```python
def agent_loop(messages):
    for turn in range(MAX_TURNS):
        response = call_llm(messages, TOOL_DEFINITIONS)
        messages.append({"role": "assistant", "content": response})

        if not response.get("tool_calls"):
            return response["content"]

        # Execute all tool calls
        for tc in response["tool_calls"]:
            result = execute_tool(tc)
            messages.append({"role": "tool", "content": result})

        # Loop back — LLM sees results and continues deciding
```

**Not a single line of loop logic changed.** Tool count went from 1 to 5, and the loop doesn't care at all.

### 4. Path Safety

All file-operation tools pass through `safe_path()`, which ensures the target path stays within the workspace. This prevents the LLM from accessing `../../etc/passwd` or similar path-escaping tricks.

```python
def safe_path(filepath):
    workspace = os.path.abspath(WORKSPACE_DIR)
    target = os.path.abspath(os.path.join(workspace, filepath))
    if not target.startswith(workspace):
        raise ValueError(f"Path escapes workspace: {filepath}")
    return target
```

## Design Principle

> **"Add a tool = add a handler. The loop stays the same."**

This is the foundation for all subsequent steps (permissions, subagents, compaction). Every new mechanism is layered on top without touching the loop.

## Demo Output

```
=== s02: Tool Use (Dispatch Map) ===

> Find all Python files in the project

[glob] **/*.py
src/main.py
src/utils.py
tests/test_main.py

[read_file] src/main.py
1: import sys
2: from utils import greet
3:
4: def main():
...

> Done!
```

See the corresponding directories for complete implementations in all three languages.
