# s03 权限系统 — 三道门权限管线

## 核心概念

s02 的 Agent 已经有了 5 个工具，什么都能干。但"能干"和"该干"是两回事。你总不能让 LLM 在用户机器上 `rm -rf /` 吧？

**s03 的答案：三道门权限管线。先划边界，再给自由。**

```
用户请求 → Agent 循环
              │
              ▼
         ┌──────────────┐
         │  LLM 决策     │  ← 完全不变
         │  返回工具调用  │
         └──────┬───────┘
                │
                ▼
         ┌──────────────────────────────────────┐
         │       三道门权限管线（s03 新增）        │
         │                                      │
         │  ┌─────────────────────────────────┐ │
         │  │  Gate 1: 硬禁止名单              │ │
         │  │  rm -rf /, sudo, mkfs, fork bomb│ │
         │  │  → 直接 BLOCK，不问用户          │ │
         │  └─────────────┬───────────────────┘ │
         │                ▼                      │
         │  ┌─────────────────────────────────┐ │
         │  │  Gate 2: 破坏性命令检测          │ │
         │  │  rm, mv, chmod, Remove-Item...  │ │
         │  │  → 标记为"需要确认"              │ │
         │  └─────────────┬───────────────────┘ │
         │                ▼                      │
         │  ┌─────────────────────────────────┐ │
         │  │  Gate 3: 用户确认                │ │
         │  │  "Allow this command? [y/N]"    │ │
         │  │  → 用户说了算                    │ │
         │  └─────────────┬───────────────────┘ │
         │                │                      │
         └────────────────┼──────────────────────┘
                          │
                          ▼
                   ┌──────────────┐
                   │  执行工具     │  ← 通过 / 被阻止
                   └──────────────┘
```

三道门的设计理念：

- **Gate 1（硬禁止）**：绝对不行的事。匹配到就直接阻止，**不浪费用户的注意力**。
- **Gate 2（检测）**：可能有风险的事。匹配到就标记，**让用户知情决策**。
- **Gate 3（确认）**：用户最终把关。默认拒绝（`[y/N]`），**防止误操作**。

## s02 → s03 变化

| 维度 | s02 | s03 |
|------|-----|-----|
| 工具数量 | 5 | 5（不变） |
| Agent 循环 | 不变 | **不变** |
| 工具执行 | 直接执行 | 执行前经过三道门检查 |
| 新增概念 | — | 权限管线、硬禁止、破坏性检测、用户确认 |
| 代码改动量 | — | 仅 `execute_tool()` 包装一层 |

## 代码走读

### 1. 权限管线（Permission Pipeline）

核心函数 `check_permission(command)` 实现三道门：

```python
def check_permission(command: str) -> tuple[bool, str]:
    # Gate 1: 硬禁止名单 — 正则匹配，直接拒绝
    for pattern in HARD_DENY_PATTERNS:
        if re.search(pattern, command, re.IGNORECASE):
            return False, f"BLOCKED: 匹配硬禁止规则 '{pattern}'"

    # Gate 2: 破坏性命令检测 — 匹配到就标记
    destructive_reason = None
    for pattern, desc in DESTRUCTIVE_PATTERNS:
        if re.search(pattern, command, re.IGNORECASE):
            destructive_reason = desc
            break

    if destructive_reason is None:
        return True, "OK"  # 安全命令，直接放行

    # Gate 3: 用户确认 — 默认拒绝
    response = input(f"⚠ 破坏性命令: {destructive_reason}\n允许? [y/N]: ")
    if response.lower() in ('y', 'yes'):
        return True, "用户确认"
    return False, "用户拒绝"
```

### 2. 硬禁止名单（Hard Deny List）

**绝对不允许**的命令模式。只要匹配到任何一条，直接 `BLOCK`，不会到达 Gate 2 或 Gate 3。

```python
HARD_DENY_PATTERNS = [
    r'rm\s+-rf\s+/',               # rm -rf / 及其变体
    r'rm\s+-rf\s+--no-preserve-root',
    r'sudo\s+',                    # 任何 sudo 命令
    r'>\s*/dev/sda',               # 直接写磁盘
    r'mkfs\.',                     # 格式化文件系统
    r'dd\s+if=',                   # dd 磁盘操作
    r':\(\)\s*\{\s*:\|:&\s*\};:',  # fork bomb
    r'chmod\s+-R\s+777\s+/',      # 权限全开根目录
    r'wget\s+.*\|\s*sh',          # 管道到 shell
    r'curl\s+.*\|\s*bash',        # 管道到 bash
    r'shutdown',                   # 关机
    r'reboot',                     # 重启
]
```

### 3. 破坏性命令检测（Destructive Detection）

**可能有风险**的命令模式。匹配到后需要用户确认。

```python
DESTRUCTIVE_PATTERNS = [
    (r'\brm\b',           "rm - 删除文件/目录"),
    (r'\bmv\b',           "mv - 移动/重命名文件"),
    (r'\bdel\b',          "del - 删除文件 (Windows)"),
    (r'\brmdir\b',        "rmdir - 删除目录"),
    (r'>\s*\S',           "重定向覆盖 - 覆盖文件内容"),
    (r'\bchmod\b',        "chmod - 修改权限"),
    (r'\bchown\b',        "chown - 修改所有者"),
    (r'Remove-Item',      "Remove-Item - 删除文件 (PowerShell)"),
    (r'New-Item.*-Force', "Force 标志 - 可能覆盖"),
]
```

### 4. 插入点：Agent 循环中唯一改动的行

Agent 循环本身**一字不改**。唯一变化在 `execute_tool()` 的调用处——在外面包一层权限检查：

```python
# s02 原始代码：
for tc in response["tool_calls"]:
    result = execute_tool(tc)           # 直接执行
    messages.append({"role": "tool", "content": result})

# s03 新增一行包装：
for tc in response["tool_calls"]:
    result = execute_with_permission(tc) # ← 唯一的改动
    messages.append({"role": "tool", "content": result})
```

包装函数 `execute_with_permission`：

```python
def execute_with_permission(tc):
    """在 s02 的 execute_tool 外加一层权限检查。"""
    if tc["name"] == "bash":
        command = tc["arguments"]["command"]
        allowed, reason = check_permission(command)
        if not allowed:
            return f"PERMISSION DENIED: {reason}"
    return execute_tool(tc)
```

**关键设计决策**：只有 `bash` 工具走权限管线。`read_file`、`write_file`、`glob` 等文件操作工具的安全由 s02 的 `safe_path()` 路径沙箱保证。

### 5. 完整 Agent 循环（不变部分）

```python
def agent_loop(messages):
    for turn in range(MAX_TURNS):
        response = call_llm(messages, TOOL_DEFINITIONS)
        messages.append({"role": "assistant", "content": response})

        if not response.get("tool_calls"):
            return response["content"]

        for tc in response["tool_calls"]:
            result = execute_with_permission(tc)  # s03: 唯一改动
            messages.append({"role": "tool", "content": result})
```

## 设计原则

> **"先划边界，再给自由。"**

权限管线不是一个"开关"，而是一个层层递减的漏斗：

- Gate 1 拦住绝对不行的（省心）
- Gate 2 标记需要小心的（省力）
- Gate 3 让用户最终决定（负责）

Agent 循环完全不知道权限系统的存在——它只管"给我一个工具，我给你一个结果"。这正是 s02 调度映射模式带来的好处：**每次扩展只需要在正确的层级插入逻辑，不动核心循环。**

## 运行示例

交互模式（需要用户在 Gate 3 输入）：

```
=== s03: Permission Pipeline ===

User: 清理临时文件并检查磁盘

--- Turn 1 ---
[bash] ls /tmp/cache
  权限: OK (not destructive)
  Result: cache.log  temp.dat  old.sql

--- Turn 2 ---
[bash] rm /tmp/cache/cache.log
  权限: ⚠ DESTRUCTIVE: rm - 删除文件/目录
  Allow this command? [y/N]: y
  Result: (no output)

--- Turn 3 ---
[bash] sudo rm -rf /var/cache
  权限: BLOCKED by hard deny list

--- Turn 4 ---
[write_file] cleanup_report.txt
  Result: Written to cleanup_report.txt

--- Turn 5 ---
Agent finished (no tool calls).
```

非交互模式（`--auto` 参数）：

```
python code.py --auto "清理临时文件并检查磁盘"
# Gate 3 自动批准，适合自动化测试
```

三种语言的完整实现见对应目录。
