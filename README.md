# DeepSeek Code CLI

> An AI coding agent CLI purpose-built for DeepSeek

An interactive terminal tool that turns DeepSeek into an AI-powered coding agent. It can read, write, and edit files, run shell commands, search codebases, manage tasks, spawn subagents — all driven by a core agent loop where DeepSeek autonomously decides which tools to call and when.

Intelligence comes from the model. The harness gives it hands.

## Core Philosophy

```
Intelligence ≠ Prompt Orchestration

True agent capability comes from model training, not from external code workflows.
Drag-and-drop builders, no-code "AI Agent" platforms, prompt-chain libraries —
these are essentially glue code with if-else branches. They are not agents.

Agent Product = Model (Brain) + Harness (Hands)

The model handles perception, reasoning, and decision-making. The harness handles execution:
  - Tools: file I/O, shell, search
  - Knowledge: docs, style guides, API specs
  - Observation: git diff, error logs, code state
  - Permissions: sandboxing, approval flows, trust boundaries

DeepSeek is the driver. This project is the vehicle.
```

## Architecture

```
                     Agent Loop
                     ===========

    User --> messages[] --> DeepSeek API --> response
                                     |
                           finish_reason == "tool_calls"?
                          /                            \
                        yes                             no
                         |                               |
                   execute tools                    return text
                   append results
                   loop back ------------------> messages[]

    + context compaction pipeline (snip → micro → budget → auto)
    + permission hooks (deny list + destructive warnings)
    + todo task planning + nag reminders
    + subagent spawning (clean context isolation)
    + memory persistence and recall
    + error recovery (429/503 retry + reactive compaction on context overflow)
```

**The core pattern in 6 lines:**

```
while (finish_reason === "tool_calls") {
    response = DeepSeek(messages, tools)
    messages.push(assistant_message)
    execute tools → collect results
    messages.push(tool_results)
}
```

Every new feature layers on top of the loop. The loop itself never changes.

## Quick Start

### Prerequisites

- Node.js 18+
- [DeepSeek API Key](https://platform.deepseek.com/api_keys)

### Install

```bash
git clone <your-repo-url>
cd deepseek-code-cli
npm install
npm run build
```

### Configure

Create a `.env` file:

```env
DEEPSEEK_API_KEY=sk-your-api-key-here
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
DEEPSEEK_MODEL=deepseek-chat
MAX_TOKENS=8000
CONTEXT_LIMIT=50000
```

### Run

```bash
# Interactive mode
npm start

# Single query (non-interactive)
npm start "What files are in src/?"

# Custom workspace
npm start -- --workdir=/path/to/project "Explain the architecture"

# Development mode (hot-reload with tsx)
npm run dev
```

## Features

### Tools

| Tool | Description |
|------|-------------|
| `bash` | Execute shell commands in workspace |
| `read_file` | Read file contents with offset/limit pagination |
| `write_file` | Create or overwrite files |
| `edit_file` | Precise string replacement with replaceAll support |
| `glob` | Find files by glob pattern, sorted by modification time |
| `grep` | Search file contents with regex patterns |
| `todo_write` | Plan and track multi-step tasks |
| `task` | Spawn subagents for complex subtasks |
| `compact` | Compact conversation history to free context space |

### Harness Components

- **Permission Pipeline (3 gates)** — Hard deny list → destructive command detection → workspace boundary enforcement
- **Hook System** — PreToolUse / PostToolUse / Stop / UserPromptSubmit extension points
- **Context Compaction (4 layers)** — toolResultBudget → snipCompact → microCompact → autoCompact (from 0 API calls to LLM summary, escalating gradually)
- **Todo Tracking** — Plan-then-execute workflow with auto-nag after 3 rounds without updates
- **Subagent System** — Independent context for complex side tasks, returns only the final conclusion
- **Memory System** — Disk-persisted storage with keyword-matching recall
- **Error Recovery** — 429 (rate limit) exponential backoff, 503 (overloaded) backoff, reactive compaction on context-length errors

### Commands

In interactive mode:

| Command | Description |
|---------|-------------|
| `/help` | Show help |
| `/quit`, `/exit` | Exit the CLI |
| `/clear` | Clear conversation history |
| `/memory` | View relevant memories |
| `/compact` | Manually compact conversation |
| `/model` | Show current model info |
| `/workspace` | Show workspace directory |

## Project Structure

```
MiniDeepSeekCodeCli/
  src/                    # TypeScript CLI source
    index.ts              # CLI entry (interactive + non-interactive modes)
    agent.ts              # Core agent loop
    api-client.ts         # DeepSeek API wrapper (OpenAI-compatible protocol)
    tool-registry.ts      # Tool definitions and dispatch map
    tools/
      bash.ts             # Shell command execution
      read-file.ts        # File reading (paginated)
      write-file.ts       # File writing
      edit-file.ts        # Exact string replacement
      glob.ts             # Glob pattern matching
      grep.ts             # Regex content search
      todo-write.ts       # Task planning and tracking
      task.ts             # Subagent spawning
    harness/
      hooks.ts            # Hook registration and triggering
      compaction.ts       # Context compaction pipeline
      memory.ts           # Persistent memory storage
      system-prompt.ts    # Runtime prompt assembly
      path-safety.ts      # Workspace boundary safety
  learn-code-cli/         # Progressive tutorial (C++ / Python / Java)
    s01_agent_loop/       # Agent loop + bash
    s02_tool_use/         # Multi-tool dispatch map
    s03_permission/       # Permission pipeline
    s04_todo_write/       # Task planning
    s05_subagent/         # Subagent spawning
    s06_context_compact/  # Context compaction
    s07_error_recovery/   # Error recovery
    s08_full_agent/       # Complete agent
```

## Tutorial: Build Your Own Code CLI

The `learn-code-cli/` directory is an 8-lesson progressive tutorial that extracts the fundamental patterns behind this project and teaches you to build your own coding agent from scratch — in **Python**, **C++**, and **Java**.

Each lesson adds exactly one mechanism. Each mechanism has a motto.

| # | Lesson | Motto | Key Concept |
|---|--------|-------|-------------|
| s01 | Agent Loop | "One loop & bash is all you need" | `while (tool_calls)` |
| s02 | Tool Use | "Add a tool, add a handler" | Dispatch map |
| s03 | Permission | "Set boundaries first, then grant freedom" | 3-gate pipeline |
| s04 | Todo Write | "An agent without a plan drifts" | Task planning + nag |
| s05 | Subagent | "Big tasks split small, clean context" | Context isolation |
| s06 | Context Compact | "Context fills up — make room" | 3-layer compaction |
| s07 | Error Recovery | "Errors start a retry" | Exponential backoff |
| s08 | Full Agent | "Many mechanisms, one loop" | All combined |

Start reading at [`learn-code-cli/README.md`](learn-code-cli/README.md).

## Design Principles

1. **The model decides, the harness executes** — DeepSeek chooses which tools to call and when; the harness only carries out those decisions.

2. **One loop, many mechanisms** — The core agent loop never changes. Tools, permissions, hooks, compaction all layer on top without touching the loop.

3. **Cheap first, expensive last** — Compaction starts with zero API calls (budget → snip → micro), only falling back to LLM-summarized auto-compact when necessary.

4. **Add a tool, add a handler** — New tools register into the dispatch map; the loop stays untouched.

5. **Ask before breaking** — Destructive operations must pass through the permission pipeline; safety boundaries are part of the design, not an afterthought.

## License

MIT
