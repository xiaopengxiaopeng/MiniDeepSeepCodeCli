# s07 Error Recovery — 错误不是终点，是重试的起点

> "Errors aren't the end, they're the start of a retry."

## 概述

s07 在 s04 的 Agent 基础之上，为 LLM 调用增加了**错误恢复**能力。真实世界的 API 调用不可靠：限流、过载、上下文超长……这些错误随时可能发生。s07 通过三种策略让 Agent 在风雨中不倒：

| 策略 | 针对错误 | 机制 |
|------|----------|------|
| **指数退避重试** | 429 Rate Limit / 503 Server Overload | 等待时间翻倍：0.5s → 1s → 2s → 4s |
| **响应式压缩** | Context Too Long | 检测到上下文超长时，主动裁剪消息历史后重试 |
| **最大重试限制** | 所有错误类型 | 最多重试 5 次，失败后优雅降级 |

### 为什么 s07 放在 s06 之后

s06 教了"主动压缩"——在上下文接近限制前预防性地压缩。s07 教"被动压缩"——当 API 已经返回错误时才压缩。两者互补：
- **s06 = 预防**（事前）：上下文接近阈值 → 压缩
- **s07 = 治疗**（事后）：API 报错 context_too_long → 压缩 + 重试

## 架构图

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

## 相对于 s04 的代码变更

s04 提供了：Agent 循环、todo_write 工具、Nag 提醒、权限系统。

s07 新增：

### 1. 错误类型系统（ErrorType）

```python
class ErrorType(Enum):
    RATE_LIMIT = "rate_limit"          # HTTP 429 — 请求太频繁
    SERVER_OVERLOAD = "server_overload" # HTTP 503 — 服务器忙
    CONTEXT_TOO_LONG = "context_too_long" # 上下文超过 token 限制
```

每种错误类型有不同的恢复策略：
- `RATE_LIMIT`：等待服务器指定的 `retry_after` 时间，或使用指数退避
- `SERVER_OVERLOAD`：指数退避重试，等待服务器恢复
- `CONTEXT_TOO_LONG`：先压缩消息历史，再重试

### 2. 指数退避重试（Exponential Backoff）

```python
MAX_RETRIES = 5
BASE_DELAY = 0.5  # 秒

def call_llm_with_retry(messages, turn, mock_llm):
    for attempt in range(MAX_RETRIES + 1):
        response = mock_llm.call(messages, turn, attempt)

        if mock_llm.is_error(response):
            error_type, msg, retry_after = mock_llm.get_error_info(response)

            if attempt >= MAX_RETRIES:
                raise LLMError(error_type, f"Max retries exceeded: {msg}")

            # 指数退避：0.5s → 1.0s → 2.0s → 4.0s → 8.0s
            delay = BASE_DELAY * (2 ** attempt)
            if retry_after > 0:
                delay = max(delay, retry_after)  # 尊重服务器给的等待时间

            # 上下文超长 → 响应式压缩
            if error_type == ErrorType.CONTEXT_TOO_LONG:
                messages = compact_messages_aggressively(messages)

            time.sleep(delay)  # 等待后重试
            continue

        return response, messages  # 成功！
```

**设计要点：**
- 每次重试的等待时间翻倍，避免给过载的服务器雪上加霜
- 如果服务器在响应中指定了 `retry_after`（常见于 429 响应），优先使用该值
- 上下文超长错误不等待——立即压缩后重试

### 3. 响应式压缩（Reactive Compaction）

```python
def compact_messages_aggressively(messages):
    """当上下文超长时，只保留系统提示 + 最近 6 条消息。"""
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

与 s06 的主动压缩不同：
- s06：在上下文接近阈值时**渐进式**压缩（摘要化早期消息）
- s07：在 API 报错后**激进式**压缩（直接丢弃中间消息，只留最近的）

### 4. Agent 循环中的变更

s04 的 LLM 调用是一行：
```python
response = mock_llm_call(messages, llm_turn)
```

s07 将其包装为：
```python
try:
    response, messages = call_llm_with_retry(messages, llm_turn, mock_llm)
except LLMError as e:
    print(f"[FATAL] LLM call failed after all retries: {e.message}")
    break
```

Agent 循环的其他部分完全不变——这正是分层架构的好处。

### 5. Mock LLM 的错误注入

为了在 demo 中展示错误恢复，Mock LLM 在特定轮次注入错误：

```python
ERROR_PATTERN = {
    2: (ErrorType.RATE_LIMIT, 1),         # 第2轮：1次限流错误
    5: (ErrorType.CONTEXT_TOO_LONG, 1),   # 第5轮：1次上下文超长
    7: (ErrorType.SERVER_OVERLOAD, 2),    # 第7轮：2次服务器过载
}
```

`attempt` 参数控制第几次调用返回错误（attempt 0 = 首次调用失败，attempt 1+ = 重试成功）。

## 文件结构

```
s07_error_recovery/
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

## 运行输出示例

```
============================================================
  s07 Error Recovery — Build Your Own Code CLI
  Retry + Backoff + Reactive Compaction
============================================================

------------------------------ Round 1 ------------------------------
  [llm] Calling mock LLM (turn 0)...
  [assistant] I'll use todo_write to proceed.
  [perm] AUTO-ALLOWED: todo_write
  [tool_call] todo_write
  [tool_result] [todo_write result]
Task list updated:
  1. [ ] Read the main.py file
  2. [ ] Read the config.json file
  3. [ ] Add a greet() function to main.py
  4. [ ] Run the updated script to verify
...
  [status] Tasks: 4 total, 0 done, 0 in-progress, 4 pending
  [nag_counter] rounds since last todo update: 0

------------------------------ Round 2 ------------------------------
  [llm] Calling mock LLM (turn 1)...
  [assistant] I'll use todo_write to proceed.
  [tool_call] todo_write
  ...

------------------------------ Round 3 ------------------------------
  [llm] Calling mock LLM (turn 2)...          ← ← ← RATE LIMIT HIT!
  [retry] rate_limit: Rate limit exceeded. Please retry after 2s.
           — backoff 2.0s (attempt 1/5)...    ← 等待 2 秒
  [assistant] I'll use read_file to proceed.   ← 重试成功！
  [tool_call] read_file
  ...

------------------------------ Round 4 ------------------------------
  ...

------------------------------ Round 6 ------------------------------
  [llm] Calling mock LLM (turn 5)...          ← ← ← CONTEXT TOO LONG!
  [compact] Context too long! Compacted 10 -> 7 messages
  [retry] context_too_long -> retrying with compacted context (attempt 1/5)...
  [assistant] I'll use write_file to proceed.  ← 压缩后重试成功！
  [tool_call] write_file
  ...

------------------------------ Round 8 ------------------------------
  [llm] Calling mock LLM (turn 7)...          ← ← ← SERVER OVERLOAD!
  [retry] server_overload: Server is overloaded.
           — backoff 0.5s (attempt 1/5)...    ← 第1次重试失败
  [retry] server_overload: Server is overloaded.
           — backoff 1.0s (attempt 2/5)...    ← 第2次重试失败
  [assistant] I'll use execute_command...      ← 第3次成功！
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

## 设计启示

1. **错误分类决定恢复策略**——不是所有错误都应该重试（400 Bad Request 重试没用），也不是所有错误都应该用同样的方式重试。限流要等待，上下文超长要压缩。

2. **指数退避保护双方**——客户端不会疯狂重试耗尽自己的资源，服务端不会被重试风暴压垮。

3. **响应式压缩是安全网**——即使主动压缩（s06）漏掉了什么，API 层的错误也能兜底。

4. **重试上限防止无限循环**——`MAX_RETRIES = 5` 确保 Agent 不会因为持续性错误而永远卡住。超过上限后优雅退出，让用户处理。

5. **`attempt` 参数解耦测试和生产**——Mock LLM 用 `attempt` 参数决定返回错误还是真实响应，使得错误恢复逻辑可以在无真实 API 的情况下完整测试。

## 下一节

[s08 Full Agent](../s08_full_agent/) — 将所有模块组装成一个完整的编码 Agent，支持多轮对话和完整工作流。
