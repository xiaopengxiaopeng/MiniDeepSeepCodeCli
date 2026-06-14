# s01 Agent Loop — 核心循环

> **核心理念：一个循环 + 一个 bash 工具 = 你就拥有了一个编程智能体。**

## 架构图

```
┌─────────────────────────────────────────────────────┐
│                   AGENT LOOP                        │
│                                                     │
│   用户输入 ──▶ messages[] ──▶ LLM (大模型)          │
│                                   │                 │
│                      finish_reason?│                │
│                 ┌─────────────────┴──────────┐      │
│                 ▼                             ▼     │
│          "tool_calls"                     "stop"    │
│                 │                             │     │
│                 ▼                             ▼     │
│         执行 bash 命令                  输出最终回复 │
│                 │                                     │
│                 ▼                                     │
│         将结果追加到 messages[]                       │
│                 │                                     │
│                 └────── 循环回去 ─────────────────┘   │
└─────────────────────────────────────────────────────┘
```

## 伪代码

```python
messages = [系统提示, 用户输入]

while finish_reason == "tool_calls":
    response = LLM(messages, tools)
    finish_reason = response.finish_reason

    if finish_reason == "tool_calls":
        执行每个工具调用 (bash 命令)
        将执行结果以 tool 角色追加到 messages
    else:
        打印 response.content  # 最终回复
```

**这就是全部。** 不到 10 行的核心逻辑，构成了一个能运行命令、读写文件、分析代码的智能体。

## 代码结构

```
s01_agent_loop/
├── README.md          ← 你在这里 (中文)
├── README.en.md       ← 英文版
├── python/
│   └── code.py        ← Python 实现 (subprocess)
├── cpp/
│   └── main.cpp       ← C++17 实现 (popen)
└── java/
    └── Main.java      ← Java 17 实现 (ProcessBuilder)
```

每个实现都包含：

| 组件 | 说明 |
|------|------|
| `mock_llm()` | 模拟大模型调用，根据关键词返回工具调用或最终回复 |
| `execute_bash()` | 执行 shell 命令并捕获输出 |
| **主循环** | `while finish_reason == "tool_calls"` — 教程的核心 |
| 彩色终端输出 | 用 ANSI 转义码展示循环的每一步 |

## 如何运行

### Python

```bash
cd s01_agent_loop/python
python code.py
```

### C++

```bash
cd s01_agent_loop/cpp
# Windows (MSVC)
cl /EHsc /std:c++17 main.cpp && main.exe
# 或 MinGW
g++ -std=c++17 main.cpp -o main && main
# Linux/macOS
g++ -std=c++17 main.cpp -o main && ./main
```

### Java

```bash
cd s01_agent_loop/java
javac Main.java
java Main
```

### 试试这些输入

```
list files           → 调用 ls 列出文件
system info          → 调用 systeminfo / uname
disk space           → 调用 df / wmic
memory               → 调用 free / systeminfo
create a file        → 调用 echo 创建 demo.txt
echo "hello world"   → 调用 echo 打印
current directory    → 调用 pwd / cd
date                 → 调用 date
```

## 深入理解

### 为什么只需要一个 bash 工具？

因为 `bash` 是**图灵完备**的。通过 bash，智能体可以：

- 📂 浏览文件系统 (`ls`, `cd`, `find`)
- 📝 读写文件 (`cat`, `echo`, `>>`)
- 🔧 调用任何命令行工具 (`git`, `npm`, `gcc`, `python`)
- 📦 安装依赖 (`pip install`, `npm install`)
- 🔍 搜索代码 (`grep`, `rg`)

**一个工具，无限能力。**

### 为什么用模拟大模型？

1. **无需 API Key** — 任何人都能立刻运行
2. **可控行为** — 演示清晰，不会出现意外输出
3. **关注架构** — 不被 API 细节分散注意力

将 `mock_llm()` 替换为真实的 DeepSeek/OpenAI API 调用后，代码**无需其他改动**即可工作。

### 循环的威力

这个简单循环的美妙之处在于：

- LLM 可以**连续调用**多个工具
- 每次工具调用的结果都会**进入上下文**，LLM 能看到
- LLM 根据结果**决定下一步**，形成了"思考-行动-观察"的闭环
- 整个对话历史都被保留，实现了**多轮交互**

这就是 ChatGPT、Claude Code、Cursor 等工具背后的核心机制。

## 代码要点

### Python (`code.py`)

```python
while True:
    choice = mock_llm(messages, TOOLS)
    if choice["finish_reason"] == "tool_calls":
        messages.append(choice["message"])
        for tc in choice["message"]["tool_calls"]:
            result = execute_bash(cmd)
            messages.append({"role": "tool", ...})
    else:
        print(choice["message"]["content"])
        break
```

### C++ (`main.cpp`)

```cpp
while (true) {
    LLMChoice choice = mock_llm(messages);
    if (choice.finish_reason == "tool_calls") {
        for (auto& tc : choice.tool_calls) {
            string result = execute_bash(command);
            messages.push_back({"tool", result, tc.id, ""});
        }
    } else {
        cout << choice.content << endl;
        break;
    }
}
```

### Java (`Main.java`)

```java
while (true) {
    LLMChoice choice = mockLLM(messages);
    if (choice.finishReason.equals("tool_calls")) {
        for (ToolCall tc : choice.toolCalls) {
            String result = executeBash(cmd);
            messages.add(new Message("tool", result, tc.id));
        }
    } else {
        System.out.println(choice.content);
        break;
    }
}
```

## 替换为真实 API

将三个语言中的 `mock_llm()` 替换为：

```python
# Python
import requests
def llm(messages, tools):
    r = requests.post("https://api.deepseek.com/v1/chat/completions",
        headers={"Authorization": f"Bearer {API_KEY}"},
        json={"model": "deepseek-chat", "messages": messages, "tools": tools})
    return r.json()["choices"][0]
```

```cpp
// C++: 使用 libcurl
// POST https://api.deepseek.com/v1/chat/completions
// 解析 JSON 响应（建议使用 nlohmann/json）
```

```java
// Java: 使用 java.net.http.HttpClient (Java 11+)
// POST https://api.deepseek.com/v1/chat/completions
// 解析 JSON 响应（建议使用 org.json 或 Jackson）
```

替换后，循环逻辑**完全不变**。

## 下一课预告

`s02_tools_and_routing` — 添加更多工具（读文件、写文件、搜索代码），学习工具路由和错误处理。
