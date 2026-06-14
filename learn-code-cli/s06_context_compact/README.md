# s06 Context Compact — 上下文总会满，你要有腾空间的办法

> "Context always fills up — have a way to make room."

## 概述

s06 在 s04 的 Agent 循环之上，增加了一套**三层上下文压缩管线**。随着对话轮次增加，`messages` 列表不断膨胀——这会导致 LLM 调用成本飙升、延迟变大，甚至超出上下文窗口。压缩管线在每轮 LLM 调用前自动执行，削减冗余信息，让对话保持"苗条"。

### 三层压缩

| 层级 | 名称 | 触发条件 | 策略 | 成本 |
|------|------|----------|------|------|
| **Layer 1** | snipCompact | messages > 50 条 | 保留前 3 + 后 47 条，中间用占位符替代 | 极低（数组切片） |
| **Layer 2** | microCompact | 存在旧 tool 结果 | 将非最近 3 条的 tool 结果替换为 `[compacted]` | 极低（字符串替换） |
| **Layer 3** | toolResultBudget | tool 结果 > 30000 字符 | 持久化到磁盘文件，只保留预览摘要 | 低（文件 I/O） |

> **本节不涉及 LLM 摘要。** LLM 摘要（让 LLM 总结对话历史）成本高昂，留到后续章节。本节的三个层级都是"手工"压缩——廉价、确定性、可预测。

## 架构图

```
每轮 Agent 循环开始
        │
        ▼
┌─────────────────────────────┐
│  messages = run_compaction_ │
│             pipeline(msg)   │  ← 新增：压缩管线
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────┐
│  Layer 1: snipCompact       │
│  如果 > 50 条消息：         │
│    [0,1,2] ... placeholder  │
│    ... [N-47, ..., N-1]     │
└─────────────┬───────────────┘
              │ (消息可能仍然很多，但已削减)
              ▼
┌─────────────────────────────┐
│  Layer 2: microCompact      │
│  遍历所有 tool 消息：       │
│    最近 3 条 → 保留原文     │
│    其余 → "[compacted]"     │
└─────────────┬───────────────┘
              │ (大量旧 tool 结果被压缩)
              ▼
┌─────────────────────────────┐
│  Layer 3: toolResultBudget  │
│  遍历所有 tool 消息：       │
│    内容 > 30000 字符？      │
│      → 存入 .opencode/tmp/  │
│      → 替换为预览摘要       │
└─────────────┬───────────────┘
              │
              ▼
┌─────────────────────────────┐
│  调用 LLM（使用压缩后的     │
│  messages）                 │
└─────────────────────────────┘
```

### 执行顺序的设计逻辑

先 budget（缩体积）→ 再 micro（压旧结果）→ 最后 snip（砍消息）。这样每一步都在前一步基础上进一步压缩，确保管线高效。

## snipCompact 详解

```
原始 messages (60 条):
  [0] system
  [1] user
  [2] assistant
  [3] tool
  ...
  [57] assistant
  [58] tool
  [59] assistant

snipCompact 后 (51 条):
  [0] system           ← 保留
  [1] user             ← 保留
  [2] assistant        ← 保留
  [3] system: "[COMPACTED: trimmed 7 middle messages]"
  [4] tool             ← [53] 原位置
  ...
  [50] assistant       ← [59] 原位置
```

**设计要点：**
- 阈值 50 是经验值——大多数 LLM 在此数量级内工作良好
- 保留前 3 条是为了保证 system prompt + user question + 首个回复不丢失
- 保留后 47 条是为了保证最近的上下文完整
- 插入的占位符是 `system` 角色消息，对 LLM 友好

## microCompact 详解

```
tool 消息序列:
  [tool-1] "read_file result: 5000 chars..."    → "[compacted]"
  [tool-2] "read_file result: 300 chars..."     → "[compacted]"
  [tool-3] "write_file result: 120 chars..."    → "[compacted]"
  [tool-4] "read_file result: 2400 chars..."    → 保留原文 (最近第 3)
  [tool-5] "execute_command result: 80 chars..." → 保留原文 (最近第 2)
  [tool-6] "read_file result: 100 chars..."     → 保留原文 (最近第 1)
```

**设计要点：**
- 只压缩 `role == "tool"` 的消息——assistant 和 user 消息对 LLM 理解上下文很重要
- 保留最近 3 条：如果 LLM 刚读了文件，它需要看到内容才能继续
- `[compacted]` 是一个简单标记——告诉 LLM 这里有旧内容但已被清除
- 不影响 system 消息（包括 nag 注入的提醒）

## toolResultBudget 详解

```
原始 tool 消息内容 (45000 字符 → 触发 budget):

[tool_result budget]
Path: .opencode/tmp/tool_result_001.txt
Preview:
  (前 500 字符)
  line 1: import os
  line 2: import sys
  ...
  (后 500 字符)
  line 895: if __name__ == "__main__":
  line 896:     main()
Size: 45000 chars (full content saved to disk)
```

**设计要点：**
- 阈值 30000 字符——超过此值的文件通常只需摘要
- 保存到 `.opencode/tmp/` 目录——统一的临时文件存储
- 预览展示首尾各 500 字符——足够让 LLM 了解文件结构
- 文件路径包含在消息中——如果 LLM 需要，可以再次读取

## 相对于 s04 的代码变更

s04 提供了：Agent 循环、任务规划与 Nag Reminder、权限系统。

s06 新增：

### 1. 三个压缩函数

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

### 2. 压缩管线

```python
def run_compaction_pipeline(messages):
    messages = toolResultBudget(messages)   # Layer 3 first: shrink big results
    messages = microCompact(messages)       # Layer 2: compact old tool results
    messages = snipCompact(messages)        # Layer 1: trim middle if too many
    return messages
```

### 3. Agent 循环中唯一的新行

```python
# 在 LLM 调用之前（新增）
messages = run_compaction_pipeline(messages)

# LLM 调用
response = mock_llm_call(messages, llm_turn)
```

**这就是全部改动。** Agent 循环的其他部分（tool 执行、nag 注入、权限检查）完全不变。

## 文件结构

```
s06_context_compact/
├── README.md           # 本文件（中文）
├── README.en.md        # 英文版
├── python/
│   └── code.py         # Python 实现（完整可运行）
├── cpp/
│   └── main.cpp        # C++17 实现（单文件编译）
└── java/
    └── Main.java       # Java 17 实现（单文件编译）
```

## 运行

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

## 运行输出示例

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

## 设计启示

1. **分层是应对不确定性的最佳实践**——每一层解决一个独立问题，可以单独开关或调参
2. **便宜的先做**——snipCompact 和 microCompact 不需要 I/O 或 API 调用，放在前面
3. **保留"足够"的上下文**——不是全部删除，而是找到"刚好够用"的平衡点
4. **对 LLM 透明的压缩**——占位符消息用自然语言表达，LLM 能理解发生了什么
5. **压缩是无声的助手**——Agent 和用户都无需关心压缩逻辑，它自动在后台运行

## 下一节

[s07 Error Recovery](../s07_error_recovery/) — 工具调用可能失败，Agent 需要知道如何优雅地恢复。
