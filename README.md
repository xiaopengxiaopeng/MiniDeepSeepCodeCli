# Build Your Own Code CLI

> An 8-lesson progressive tutorial to build a coding agent CLI from scratch — with a production-grade TypeScript reference implementation for DeepSeek.

Learning to build, not learning to copy. Every mechanism has a motto. Every lesson has Python, C++, and Java.

Intelligence comes from the model. The harness gives it hands. This repo teaches you to build the hands.

---

## Start Here: 8 Progressive Lessons

Each lesson adds exactly **one** harness mechanism. Read in order. Code in your language of choice.

| # | Lesson | Motto | Key Concept |
|---|--------|-------|-------------|
| [s01](learn-code-cli/s01_agent_loop/) | Agent Loop | "One loop & bash is all you need" | `while (tool_calls)` / stop reason |
| [s02](learn-code-cli/s02_tool_use/) | Tool Use | "Add a tool, add a handler" | Dispatch map / path safety |
| [s03](learn-code-cli/s03_permission/) | Permission | "Set boundaries first, then grant freedom" | 3-gate deny → warn → confirm |
| [s04](learn-code-cli/s04_todo_write/) | Todo Write | "An agent without a plan drifts" | Task list / nag reminders |
| [s05](learn-code-cli/s05_subagent/) | Subagent | "Big tasks split small, clean context" | Context isolation / tool limits |
| [s06](learn-code-cli/s06_context_compact/) | Context Compact | "Context fills up — make room" | snip → micro → budget |
| [s07](learn-code-cli/s07_error_recovery/) | Error Recovery | "Errors start a retry" | Exponential backoff / reactive compact |
| [s08](learn-code-cli/s08_full_agent/) | Full Agent | "Many mechanisms, one loop" | All 7 combined in one reference |

**Learning path:** act → handle complexity → remember → recover → assemble.

```
s01 ──→ s02 ──→ s03 ──→ s04 ──→ s05 ──→ s06 ──→ s07 ──→ s08
loop    tools   perms   plan    spawn   compact  recover  full
```

### How to Read

Each lesson folder contains:

```
s01_agent_loop/
  README.md          # Chinese tutorial (full narrative + inline code)
  README.en.md       # English tutorial
  python/code.py     # Python 3.x — runnable with mock LLM
  cpp/main.cpp       # C++17 — compilable single file
  java/Main.java     # Java 17 — compilable single file
```

1. Open the lesson README — understand the concept and the motto
2. Read the code — every section is labeled (`FROM s01`, `NEW in s02`)
3. Run it: `python code.py` / `g++ -std=c++17 main.cpp && ./a.out` / `javac Main.java && java Main`

All implementations use a **mock LLM** — no API key needed. The loop is real. The tools are real. Only the model response is simulated so you can see every mechanism in action instantly.

### The Core Pattern

No matter the language, no matter the mechanism — the agent loop never exceeds 10 lines:

```
while (finish_reason == "tool_calls"):
    response = LLM(messages, tools)
    messages.append(assistant_msg)
    for tc in response.tool_calls:
        result = TOOL_HANDLERS[tc.name](**tc.args)
        messages.append(tool_result(tc.id, result))
```

Every lesson layers one more mechanism on top. The loop itself is untouchable.

---

## Reference Implementation: DeepSeek Code CLI

The `src/` directory contains a **production-grade TypeScript CLI** that connects to the real DeepSeek API with all 7 mechanisms active. It's what the tutorial teaches you to build.

```
src/
  index.ts              # CLI entry (interactive + non-interactive modes)
  agent.ts              # Core agent loop with streaming
  api-client.ts         # DeepSeek API wrapper (OpenAI-compatible)
  tool-registry.ts      # Tool definitions + dispatch map
  tools/
    bash.ts             # Shell execution (blocked: rm -rf /, sudo, ...)
    read-file.ts        # File reading with offset/limit
    write-file.ts       # File writing with mkdir -p
    edit-file.ts        # Exact string replace + replaceAll
    glob.ts             # Glob pattern matching, sorted by mtime
    grep.ts             # Regex content search with file filters
    todo-write.ts       # Task planning with status tracking
    task.ts             # Subagent spawning with clean context
  harness/
    hooks.ts            # PreToolUse / PostToolUse / Stop hooks
    compaction.ts       # 4-layer compaction pipeline
    memory.ts           # Disk-persisted keyword-matching memory
    system-prompt.ts    # Runtime prompt section assembly
    path-safety.ts      # Workspace boundary enforcement
```

### Quick Start (Reference CLI)

```bash
git clone https://github.com/xiaopengxiaopeng/MiniDeepSeepCodeCli.git
cd MiniDeepSeepCodeCli
npm install && npm run build

# Set your API key
echo "DEEPSEEK_API_KEY=sk-your-key" > .env

# Interactive mode
npm start

# Single query
npm start "Explain the codebase structure"

# Development
npm run dev
```

### Commands (Interactive Mode)

| Command | Description |
|---------|-------------|
| `/help` | Show help |
| `/quit`, `/exit` | Exit the CLI |
| `/clear` | Clear conversation history |
| `/memory` | View relevant memories |
| `/compact` | Manually compact conversation |
| `/model` | Show current model info |
| `/workspace` | Show workspace directory |

### Tools Reference

| Tool | What it does |
|------|-------------|
| `bash` | Execute shell commands in workspace |
| `read_file` | Read files with `path`, `offset`, `limit` |
| `write_file` | Create or overwrite files |
| `edit_file` | Replace `old_string` with `new_string` (supports `replace_all`) |
| `glob` | Find files by pattern, sorted by modification time |
| `grep` | Search with regex, filter by `include` pattern |
| `todo_write` | Create/update task list: `[{content, status}]` |
| `task` | Spawn subagent for complex subtasks |
| `compact` | Compress conversation history |

---

## Design Principles

1. **The model decides, the harness executes** — The LLM chooses tools; the harness carries out those decisions. Never the reverse.

2. **One loop, many mechanisms** — Tools, permissions, hooks, compaction all layer around the same `while (tool_calls)` loop.

3. **Cheap first, expensive last** — Compaction runs budget → snip → micro (zero API calls) before falling back to LLM summarization.

4. **Add a tool, add a handler** — New tools register into the dispatch map. The loop never changes.

5. **Ask before breaking** — Destructive operations must pass through the permission pipeline. Safety is design, not afterthought.

## License

MIT
