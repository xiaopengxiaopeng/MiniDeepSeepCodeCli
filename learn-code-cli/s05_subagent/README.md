# s05 Subagent — 大事化小，每个子任务有干净的上下文

> "Big tasks split small, each subtask gets clean context."

## 概述

s05 在 s04 的任务规划系统之上，引入了**子 Agent 委派（Subagent Delegation）**机制。主 Agent 可以通过 `task` 工具将复杂任务派发给独立的子 Agent，每个子 Agent 拥有**完全隔离的对话上下文**，最后只返回一段精简的文本摘要。

### 核心思想：上下文隔离

在真实的 AI 编程工具中，Agent 的对话历史会随着对话轮数不断膨胀。如果让 Agent 自己去执行大量琐碎的查找、读取、替换操作，上下文很快就会变得混乱且庞大。

**子 Agent 模式**的解决方案是：
- 将复杂任务拆分成多个子任务
- 每个子任务在一个"清洁的房间"中执行——全新的消息列表、全新的系统提示
- 子 Agent 只携带**有限的工具**（不能调用 `task` 本身，防止无限递归）
- 子 Agent 只返回**最终结论**，不把内部细节带回主对话

### 三个核心概念

| 概念 | 说明 |
|------|------|
| **task 工具** | 主 Agent 可调用的工具，接收 `description` 和 `prompt`，启动子 Agent |
| **上下文隔离** | 子 Agent 拥有自己独立的 `messages[]`、`system prompt`，与主 Agent 完全隔离 |
| **有限工具集** | 子 Agent 只能使用 `read_file`、`write_file`、`execute_command`、`glob`、`edit`——没有 `task`（防递归）、没有 `todo_write`（不需要） |

### 架构图

```
┌─────────────────────────────────────────────────────────┐
│                     主 Agent (Main Agent)                │
│                                                         │
│  messages[] ──────────────────────────────────────┐      │
│  ┌─ system: "你是主 Agent..."                     │      │
│  ├─ user: "分析目录并重构 utils.py"               │      │
│  ├─ assistant: (调用 task) ───────────┐           │      │
│  ├─ tool: [子 Agent 摘要] ◄────────────┤           │      │
│  ├─ assistant: (调用 task) ───────────┤           │      │
│  ├─ tool: [子 Agent 摘要] ◄────────────┤           │      │
│  └─ assistant: "所有任务完成"            │           │      │
│                                         │           │      │
│  工具: task, todo_write, read_file,     │           │      │
│        write_file, execute_command      │           │      │
└─────────────────────────────────────────┼───────────┘
                                          │
          ┌───────────────────────────────┼───────────────────────────────┐
          │                               │                               │
          ▼                               ▼                               │
┌──────────────────────┐     ┌──────────────────────┐                    │
│   子 Agent A          │     │   子 Agent B          │                    │
│   "分析目录结构"       │     │   "重构 utils.py"     │                    │
│                      │     │                      │                    │
│  messages[] (隔离)    │     │  messages[] (隔离)    │                    │
│  ┌─ system: "...子   │     │  ┌─ system: "...子   │                    │
│  │  Agent, 15轮限制" │     │  │  Agent, 15轮限制" │                    │
│  ├─ user: "列出所有  │     │  ├─ user: "读 utils  │                    │
│  │  .py 文件并总结"  │     │  │  .py 并加函数"    │                    │
│  ├─ assistant: glob  │     │  ├─ assistant: read  │                    │
│  ├─ tool: [结果]     │     │  ├─ tool: [内容]     │                    │
│  ├─ assistant: read  │     │  ├─ assistant: edit  │                    │
│  ├─ tool: [内容]     │     │  ├─ tool: [完成]     │                    │
│  ├─ assistant: read  │     │  └─ assistant: "已添 │                    │
│  ├─ tool: [内容]     │     │     加 multiply()"   │                    │
│  └─ assistant:       │     │                      │                    │
│     "发现 2 个 .py   │     │  返回: 文本摘要       │
│     文件：main.py    │     │  "[subagent summary]  │
│     含 print()...    │     │   Added multiply(a,b) │
│     utils.py 含      │     │   to utils.py"        │
│     add()"           │     │                      │
│                      │     │  可用工具: read_file, │
│  返回: 文本摘要       │     │  write_file, glob,   │
│  "[subagent summary] │     │  edit, execute_cmd   │
│   Found 2 Python     │     │  (没有 task!)        │
│   files..."          │     │                      │
│                      │     │                      │
│  可用工具: read_file, │     │                      │
│  write_file, glob,   │     │                      │
│  edit, execute_cmd   │     │                      │
│  (没有 task!)        │     │                      │
└──────────────────────┘     └──────────────────────┘
```

### 子 Agent 的限制

| 限制 | 原因 |
|------|------|
| 不能调用 `task` | 防止递归派发导致无限循环 |
| 不能调用 `todo_write` | 子 Agent 不需要做长期规划 |
| 最多 15 轮 | 防止子 Agent 陷入死循环；复杂任务应该在主 Agent 层拆分 |
| 无权限系统 | 子 Agent 在受控环境中运行，无需用户交互许可 |
| 只返回文本摘要 | 保持主 Agent 上下文的简洁性 |

## 相对于 s04 的代码变更

s04 提供了：任务规划（todo_write）、催促提醒（Nag Reminder）、权限系统。

s05 新增：

### 1. task 工具定义

```python
{
    "name": "task",
    "description": "Launch a new agent to handle complex, multi-step tasks autonomously...",
    "parameters": {
        "type": "object",
        "properties": {
            "description": {"type": "string", "description": "A short (3-5 word) description"},
            "prompt": {"type": "string", "description": "The task for the subagent to perform"}
        },
        "required": ["description", "prompt"]
    }
}
```

### 2. run_subagent() 函数（全新）

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
        messages.append(response)
        if not response.get("tool_calls"):
            return FINAL_RESPONSES[description]  # text-only = final summary
        # Execute tools from limited set...

    return last_assistant_text
```

### 3. 子 Agent 工具定义（受限于 5 个工具）

```python
SUBAGENT_TOOL_DEFS = [
    "read_file", "write_file", "execute_command", "glob", "edit"
]
# 注意：没有 task，没有 todo_write
```

### 4. ToolRegistry 集成 task 处理器

```python
class ToolRegistry:
    def __init__(self, todo_manager, perm_sys, spawn_subagent=None):
        ...
        if spawn_subagent:
            self.handlers["task"] = self._handle_task

    def _handle_task(self, params):
        result = self.spawn_subagent(description, prompt)
        return result  # 子 Agent 的文本摘要
```

### 5. 权限配置

`task` 工具的权限设为 `ALLOW`——委派总是允许的：

```python
rules["task"] = PermissionLevel.ALLOW
```

## 文件结构

```
s05_subagent/
├── README.md          # 本文件（中文）
├── README.en.md       # 英文版
├── python/
│   └── code.py        # Python 实现（完整可运行）
├── cpp/
│   └── main.cpp       # C++17 实现（单文件编译）
└── java/
    └── Main.java      # Java 17 实现（单文件编译）
```

## 运行

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

## 运行输出示例

```
============================================================
  s05 Subagent — Build Your Own Code CLI
  Agent delegates to isolated subagents
============================================================

------------------------------ Round 1 ------------------------------
  [llm] Calling mock LLM (turn 0)...
  [assistant] I'll use todo_write to proceed.
  [tool_call] todo_write
  [perm] AUTO-ALLOWED: todo_write
  [tool_result] Task list updated:
    1. [ ] Analyze the /project directory structure
    2. [ ] Refactor utils.py: add multiply function
  [status] Tasks: 2 total, 0 done, 0 in-progress, 2 pending

------------------------------ Round 2 ------------------------------
  [llm] Calling mock LLM (turn 1)...
  [assistant] I'll use todo_write to proceed.
  [tool_call] todo_write
  [status] Tasks: 2 total, 0 done, 1 in-progress, 1 pending

------------------------------ Round 3 ------------------------------
  [llm] Calling mock LLM (turn 2)...
  [assistant] I'll use task to proceed.
  [tool_call] task
  [perm] AUTO-ALLOWED: task

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

------------------------------ Round 4 ------------------------------
  [tool_call] todo_write
  ...

------------------------------ Round 5 ------------------------------
  [tool_call] task

  ========================================
  [subagent:spawn] Description: Add multiply to utils
  [subagent:spawn] Prompt: Read /project/utils.py, then use edit...
  [subagent] Reading the file contents now.
  [subagent:tool] read_file
  [subagent] I'll add the multiply function to utils.py.
  [subagent:tool] edit
  [subagent:tool] write_file
  [subagent] Task complete.
  [subagent:done] Returned 139 chars
  ========================================

  [tool_result] [subagent summary] Added multiply(a, b) function to
    /project/utils.py. File now contains add() and multiply() functions.

------------------------------ Agent finished ------------------------------

Final todo list:
  1. [x] Analyze the /project directory structure
  2. [x] Refactor utils.py: add multiply function

Final filesystem state:
  /project/config.json:
    {"version": "1.0"}
  /project/main.py:
    print('hello world')
  /project/utils.py:
    def add(a, b):
        return a + b

    def multiply(a, b):
        return a * b
```

## 设计启示

1. **上下文隔离是解决上下文膨胀的利器**——子 Agent 的内部对话细节（查找、试错）不会污染主 Agent 的历史记录
2. **工具子集是一种安全边界**——子 Agent 不能调用 `task`，从根本上杜绝了无限递归的可能性
3. **只返回摘要是一种信息压缩**——主 Agent 不需要知道子 Agent 翻了多少文件、试了多少方法，只需要知道最终结论
4. **Max turns 是经验参数**——子 Agent 的任务应该是"小而明确"的；如果超过 15 轮还没完成，说明任务分解粒度不够细
5. **主 Agent 消息列表保持简洁**——主 Agent 的历史中只有 `task` 工具调用和对应的摘要结果，就像调了一个"智能函数"

## 下一节

[s06 Context Compact](../s06_context_compact/) — 当上下文太长时该怎么办？引入上下文压缩机制。
