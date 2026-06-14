---
marp: true
theme: default
paginate: true
---

# 从零构建你的第一个 AI 编程 Agent

## —— 用 55 行 Python 代码理解 Agent 核心模式

---

## 一个问题

```
     用户：帮我看看项目里有哪些文件

     ┌─────────────────────────────────┐
     │                                 │
     │    AI 要怎么"看到"你的文件？     │
     │                                 │
     │    它不能。它只是一段文本。      │
     │                                 │
     │    除非……你给它装上手。          │
     │                                 │
     └─────────────────────────────────┘
```

---

## Agent 的核心秘密

```
Agent = 模型（大脑） + Harness（手脚）

大脑（LLM）               手脚（你的代码）
─────────────            ─────────────────
理解意图                  执行命令
选择工具                  读写文件
决定停止                  返回结果
```

**智能来自模型训练，但手脚是你写的。**

---

## 55 行代码拆解

```python
# 第 1 部分：导入 + 工具实现
import subprocess, json, os

def bash(cmd):
    """唯一需要的工具：执行 shell 命令"""
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return r.stdout or "(no output)"
```

**一个工具就够了。** Bash 可以读文件、列目录、搜索内容。

---

## 55 行代码拆解

```python
# 第 2 部分：模拟 LLM（换成真实 API 就是完整 Agent）
def llm(query, called):
    """模型决定：返回文本 or 调用工具"""
    if called:  
        return {"content": "Done."}       # 已调用过工具，停止
    if "list" in query or "file" in query:
        return {"tool_calls": [...]}       # 调用 bash
    if "hi" in query:
        return {"content": "Hello!"}       # 纯文本回复
    return {"tool_calls": [...]}           # 默认执行用户输入
```

**关键：** 两种返回 —— `content`（文本） or `tool_calls`（调用工具）

---

## 55 行代码拆解

```python
# 第 3 部分：Agent 循环 —— 整个项目的灵魂

TOOLS = {"bash": bash}

while True:
    q = input("> ")                        # 用户输入
    messages = [{"role": "user", "content": q}]

    for _ in range(3):                     # 最多 3 轮
        r = llm(q, called)                 # 问模型

        if "tool_calls" not in r:          # 模型说完了
            print(r["content"])
            break

        for tc in r["tool_calls"]:         # 模型要调用工具
            out = TOOLS[tc["name"]](**tc["args"])
            print(f"[bash] {out[:300]}")   # 显示结果
```

**6 行核心逻辑。这就是全部。**

---

## 这个循环为什么牛

```
   User: "list files"
          │
          ▼
   ┌─────────────┐
   │   LLM 决策   │ ← 模型说：调用 bash("dir")
   └──────┬──────┘
          │ tool_calls
          ▼
   ┌─────────────┐
   │  执行工具    │ ← bash("dir") → 文件列表
   └──────┬──────┘
          │ 结果
          ▼
   ┌─────────────┐
   │   LLM 决策   │ ← 模型说：已完成，返回文本
   └──────┬──────┘
          │ content
          ▼
     "Done."
```

---

## 三种消息角色

```
messages = [
    {"role": "user",      "content": "list files"},         # 用户说
    {"role": "assistant", "tool_calls": [bash("dir")]},     # 模型调用工具
    {"role": "tool",      "content": "file1.py\nfile2.py"}, # 工具返回
    {"role": "assistant", "content": "Done."}               # 模型总结
]
```

**User → Assistant ↔ Tool → Assistant → 结束**

这是 OpenAI/DeepSeek 的标准消息协议。

---

## 模型的两种决策

```python
# 决策 A：回答问题（停止循环）
{"content": "Hello! Try: list files"}

# 决策 B：调用工具（继续循环）  
{"tool_calls": [
    {"id": "t1", "function": {
        "name": "bash", 
        "arguments": '{"cmd": "dir"}'
    }}
]}
```

| 决策 | 循环行为 |
|------|----------|
| `content` 存在 | **break** — 输出文本，等待下一个用户输入 |
| `tool_calls` 存在 | **continue** — 执行工具，结果喂回模型 |

---

## 加一个工具 = 加 3 行代码

```python
# 当前只有 1 个工具
TOOLS = {"bash": bash}

# 加一个读文件工具
def read_file(path):
    return open(path).read()

TOOLS = {
    "bash": bash,
    "read_file": read_file,       # +1 行
}
```

**循环不用改。** 工具注册到 `TOOLS` 字典，`llm()` 返回对应的工具名即可。

---

## 从 55 行到生产级

```
minimal.py (55 行)
   │
   ├─ + 分发映射 ──────────→ 多工具共存
   ├─ + 权限检查 ──────────→ 安全执行
   ├─ + 任务规划 ──────────→ 多步骤推理
   ├─ + 上下文压缩 ────────→ 无限对话
   ├─ + 错误恢复 ──────────→ 自动重试
   ├─ + 子智能体 ──────────→ 并行处理
   │
   ▼
  src/  (1800+ 行 TypeScript，对接真实 API)
```

**所有机制都叠加在同一个循环上。循环永远不变。**

---

## 跑起来

```bash
# 1. 复制这 55 行代码
python minimal.py

# 2. 试试这些输入
> list files          # 触发 bash("dir")
> hi                  # 纯文本回复  
> echo hello world    # 任何命令都行

# 3. 换成真实 API —— 改 llm() 函数即可
def llm(query, called):
    return openai.chat.completions.create(
        model="deepseek-chat",
        messages=messages,
        tools=TOOL_DEFINITIONS
    )
```

---

## 记住这 6 行

```python
while True:
    r = llm(query)                         # 1. 问模型
    if "tool_calls" not in r: break        # 2. 没工具调用就停
    for tc in r["tool_calls"]:             # 3. 遍历工具调用
        out = TOOLS[tc["name"]](**args)     # 4. 执行工具
    # 5. 结果自动纳入上下文
    # 6. 循环继续
```

---

## 总结

| 概念 | 对应代码 |
|------|----------|
| **大脑** | `llm()` — 决定说话还是调用工具 |
| **手脚** | `bash()` — 执行系统命令 |
| **循环** | `while True` + `for _ in range(3)` |
| **协议** | `messages[]` — user / assistant / tool |
| **分发** | `TOOLS` 字典 — 工具名 → 函数 |

> **Bash is all you need.  
> 智能来自模型。框架只是手脚。  
> 55 行代码，你就懂了。**

---

## 下一步

```
learn-code-cli/
  minimal.py     ← 你现在在这里（55 行入门）
  s01~s08/       ← 8 节渐进课程（Python / C++ / Java）
  ../src/        ← TypeScript 生产级参考实现
```

**`git clone` → `python minimal.py` → 开始你的 Agent 之旅**
