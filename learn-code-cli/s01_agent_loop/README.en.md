# s01 Agent Loop — The Core Loop

> **Core Philosophy: One loop + one bash tool = you have a working coding agent.**

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   AGENT LOOP                        │
│                                                     │
│   User Input ──▶ messages[] ──▶ LLM (Model)        │
│                                   │                 │
│                      finish_reason?│                │
│                 ┌─────────────────┴──────────┐      │
│                 ▼                             ▼     │
│          "tool_calls"                     "stop"    │
│                 │                             │     │
│                 ▼                             ▼     │
│         Execute bash cmd              Output reply  │
│                 │                                     │
│                 ▼                                     │
│         Append result to messages[]                  │
│                 │                                     │
│                 └────── Loop back ───────────────┘   │
└─────────────────────────────────────────────────────┘
```

## Pseudocode

```python
messages = [system_prompt, user_input]

while finish_reason == "tool_calls":
    response = LLM(messages, tools)
    finish_reason = response.finish_reason

    if finish_reason == "tool_calls":
        Execute each tool call (bash command)
        Append results to messages with role "tool"
    else:
        print(response.content)  # final answer
```

**That's it.** Less than 10 lines of core logic power an agent that can run commands, read/write files, and analyze code.

## Project Structure

```
s01_agent_loop/
├── README.md          ← Chinese version
├── README.en.md       ← You are here (English)
├── python/
│   └── code.py        ← Python implementation (subprocess)
├── cpp/
│   └── main.cpp       ← C++17 implementation (popen)
└── java/
    └── Main.java      ← Java 17 implementation (ProcessBuilder)
```

Each implementation contains:

| Component | Description |
|-----------|-------------|
| `mock_llm()` | Simulates the LLM — returns tool calls or final reply based on keywords |
| `execute_bash()` | Runs a shell command and captures output |
| **Main loop** | `while finish_reason == "tool_calls"` — the heart of the tutorial |
| Color output | ANSI escape codes to visualize each step of the loop |

## How to Run

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
# Or MinGW
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

### Try these inputs

```
list files           → calls ls to list files
system info          → calls systeminfo / uname
disk space           → calls df / wmic
memory               → calls free / systeminfo
create a file        → calls echo to create demo.txt
echo "hello world"   → calls echo to print
current directory    → calls pwd / cd
date                 → calls date
```

## Deep Dive

### Why just one bash tool?

Because `bash` is **Turing-complete**. With bash, the agent can:

- 📂 Browse filesystem (`ls`, `cd`, `find`)
- 📝 Read and write files (`cat`, `echo`, `>>`)
- 🔧 Invoke any CLI tool (`git`, `npm`, `gcc`, `python`)
- 📦 Install dependencies (`pip install`, `npm install`)
- 🔍 Search code (`grep`, `rg`)

**One tool. Infinite capability.**

### Why use a mock LLM?

1. **No API key needed** — anyone can run it immediately
2. **Predictable behavior** — clean demo with no surprises
3. **Focus on architecture** — not distracted by API details

Replace `mock_llm()` with a real DeepSeek/OpenAI API call, and the code works **without any other changes**.

### The Power of the Loop

What makes this simple loop beautiful:

- The LLM can **chain multiple tool calls** consecutively
- Each tool result is **fed back into context** for the LLM to see
- The LLM **decides the next step** based on results — a "think-act-observe" cycle
- The entire conversation history is preserved, enabling **multi-turn interaction**

This is the core mechanism behind ChatGPT, Claude Code, Cursor, and similar tools.

## Key Code Snippets

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

## Replacing with a Real API

Replace `mock_llm()` in each language with:

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
// C++: Use libcurl
// POST https://api.deepseek.com/v1/chat/completions
// Parse JSON response (recommend nlohmann/json)
```

```java
// Java: Use java.net.http.HttpClient (Java 11+)
// POST https://api.deepseek.com/v1/chat/completions
// Parse JSON response (recommend org.json or Jackson)
```

After replacement, the loop logic remains **completely unchanged**.

## Next Lesson

`s02_tools_and_routing` — Add more tools (read file, write file, search code), learn tool routing and error handling.
