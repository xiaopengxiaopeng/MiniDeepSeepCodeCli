# s04 Todo Write — 没有计划的 Agent 只会漂流

> "An agent without a plan drifts."

## 概述

s04 在 s03 的权限系统之上，为 Agent 增加了**任务规划与追踪**能力。新增 `todo_write` 工具允许 Agent 创建结构化的任务列表，并通过**催促提醒（Nag Reminder）**机制确保 Agent 不会忘记更新计划。

### 三个核心概念

| 概念 | 说明 |
|------|------|
| **todo_write 工具** | Agent 可调用的工具，接收 `items` 数组（每项含 `content` + `status`），完整替换当前任务列表 |
| **Nag Reminder** | 当连续 3 轮对话未调用 `todo_write` 时，在 LLM 调用前自动注入系统提醒消息 |
| **状态外置管理** | 任务列表维护在 Agent 进程中（非 LLM 上下文中），LLM 通过工具调用读写状态 |

### 任务状态

```
pending     → [ ] 待完成
in_progress → [~] 进行中
completed   → [x] 已完成
```

## Nag Reminder 机制

```
Round 1: todo_write → 创建计划         nag_counter = 0
Round 2: read_file  → 执行工具         nag_counter = 1
Round 3: write_file → 执行工具         nag_counter = 2
Round 4: (文本回复, 忘记更新 todo)      nag_counter = 3 → 触发 nag!
Round 5: [SYSTEM REMINDER] 你有 N 个未完成任务...
         todo_write → 更新任务列表      nag_counter = 0 (重置)
```

**设计要点：**
- 计数器在每轮结束时递增（如果任务列表非空）
- 当 `nag_counter >= 3` 时，在下一轮 LLM 调用**之前**注入 nag 消息
- 调用 `todo_write` 立即将计数器重置为 0
- 任务列表为空时不触发 nag（无事可追）

## 相对于 s03 的代码变更

s03 提供了：Agent 循环、工具定义与执行、权限系统（allow/ask/deny）。

s04 新增：

### 1. TodoManager 类（状态管理）

```python
class TodoManager:
    NAG_THRESHOLD = 3  # 3轮未更新触发提醒

    def __init__(self):
        self.todos: list[TodoItem] = []      # 新增：任务列表
        self.rounds_since_last_update: int = 0  # 新增：nag 计数器

    def write_todos(self, items): ...   # 新增：替换整个任务列表
    def should_nag(self) -> bool: ...   # 新增：是否应注入提醒
    def get_nag_message(self) -> str: ... # 新增：生成提醒文本
    def tick_round(self): ...           # 新增：每轮结束调用
```

### 2. 工具定义中新增 todo_write

```python
{
    "name": "todo_write",
    "description": "Create or update a structured task list...",
    "parameters": {
        "type": "object",
        "properties": {
            "items": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "content": {"type": "string"},
                        "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]}
                    }
                }
            }
        }
    }
}
```

### 3. Agent 循环中注入 Nag

```python
# 在 LLM 调用之前（新增）
if todo_manager.should_nag():
    nag = todo_manager.get_nag_message()
    print(f"  [nag] INJECTING: {nag}")
    messages.append({"role": "system", "content": nag})

# LLM 调用
response = mock_llm_call(messages, llm_turn)

# 每轮结束后（新增）
todo_manager.tick_round()
```

### 4. 权限配置

`s04` 将 `todo_write` 的权限设为 `ALLOW`——规划工具总是允许的，不需要用户确认。

```python
rules["todo_write"] = PermissionLevel.ALLOW
```

## 文件结构

```
s04_todo_write/
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

## 运行输出示例

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
  [status] Tasks: 4 total, 0 done, 0 in-progress, 4 pending
  [nag_counter] rounds since last todo update: 0

...

------------------------------ Round 10 ------------------------------
  [nag] INJECTING: [SYSTEM REMINDER] You have 4 incomplete task(s)...
  [llm] Calling mock LLM (turn 9)...

------------------------------ Round 11 ------------------------------
  [tool_call] todo_write
  ...
  [nag_counter] rounds since last todo update: 0  ← 重置

...

------------------------------ Agent finished ------------------------------

Final todo list:
  1. [x] Read the main.py file
  2. [x] Read the config.json file
  3. [x] Add a greet() function to main.py
  4. [x] Run the updated script to verify
```

## 设计启示

1. **计划降低长任务的发散风险**——Agent 有了任务列表后，每步操作都有明确目标
2. **状态与 LLM 分离**——LLM 不负责"记住"进度，它通过工具读写外部状态
3. **提醒注入是一种温和的引导**——不打断 Agent 循环，只是在下一轮输入中增加一条系统消息
4. **Nag 阈值的取值是经验参数**——太短造成干扰，太长失去意义；3 轮是一个务实的起点

## 下一节

[s05 Subagent](../s05_subagent/) — 让 Agent 派生子 Agent 处理复杂任务，引入层次化任务委派。
