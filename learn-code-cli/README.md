# Learn Code CLI — Build Your Own Coding Agent from Scratch

> A progressive tutorial teaching you to build a coding agent CLI — from a single bash loop to a full-featured agent harness. Uses Python, C++, and Java, with DeepSeek Code CLI as the reference implementation.

## 核心理念

```
智能来自模型训练，而非代码编排。

Agent = 模型（大脑）+ Harness（手脚）

本教程不教你怎么写 AI，而是教你怎么给 AI 造一辆车 —— 
工具、权限、上下文管理、任务规划…… 
让模型在你的代码里跑起来。
```

## 8 节渐进课程

每节课只加一个机制。每个机制都有一句箴言。

| 章节 | 主题 | 箴言 | 核心概念 |
|------|------|------|----------|
| [s01](s01_agent_loop/) | Agent Loop | "One loop & bash is all you need" | `while (tool_calls)` / 消息数组 / 停止判断 |
| [s02](s02_tool_use/) | Tool Use | "加一个工具，加一个 handler" | 分发映射 / 路径安全 / 多工具共存 |
| [s03](s03_permission/) | Permission | "先设边界，再给自由" | 三道闸门 / 黑名单 / 用户确认 |
| [s04](s04_todo_write/) | Todo Write | "没有计划的 Agent 会迷失" | 任务列表 / 催促提醒 / 状态管理 |
| [s05](s05_subagent/) | Subagent | "大事化小，每个子任务有干净上下文" | 上下文隔离 / 工具限制 / 结果汇总 |
| [s06](s06_context_compact/) | Context Compact | "上下文总会满 —— 得有腾空间的办法" | 三层压缩 / 先便宜后贵 / 磁盘持久化 |
| [s07](s07_error_recovery/) | Error Recovery | "错误不是终点，是重试的起点" | 指数退避 / 反应式压缩 / 错误分类 |
| [s08](s08_full_agent/) | Full Agent | "无数机制，一个循环" | 全部组合 / 终极参考实现 |

## 学习路径

```
s01 Agent Loop ──→ s02 多工具 ──→ s03 权限
       │                │              │
       └────────────────┴──────────────┘
                      │
              s04 任务规划 ──→ s05 子智能体
                      │              │
              s06 上下文压缩 ←────────┘
                      │
              s07 错误恢复
                      │
              s08 完整智能体
```

## 如何使用

每节课是一个独立文件夹：

```
s01_agent_loop/
  README.md          # 中文教程（完整叙述 + 内联代码）
  README.en.md       # English tutorial
  python/
    code.py          # Python 实现（完整可运行）
  cpp/
    main.cpp         # C++17 实现（单文件）
  java/
    Main.java        # Java 17 实现（单文件）
```

1. 按 s01 → s08 顺序阅读
2. 先读 README 理解概念
3. 再看代码 —— 每节课代码都标注了"继承自上一节"和"本节新增"
4. 运行看效果：Python 直接 `python code.py`，C++ 编译 `g++ -std=c++17 main.cpp`，Java 编译 `javac Main.java && java Main`

## 三种语言，同一种模式

| 语言 | 适用场景 | 特点 |
|------|----------|------|
| **Python** | 快速原型、教学演示 | 代码最简洁，最易理解 |
| **C++** | 性能敏感、嵌入式 | 零依赖，系统级控制 |
| **Java** | 企业开发、跨平台 | 强类型，结构清晰 |

三种语言的 Agent 循环结构完全一致，学会一种即可理解全部。

## 核心模式

无论哪种语言，核心循环不超过 10 行：

```
while (finish_reason == "tool_calls"):
    response = LLM(messages, tools)
    messages.append(assistant_message)
    for each tool_call:
        result = TOOL_HANDLERS[tool_call.name](**tool_call.args)
        messages.append(tool_result)
```

每节课都在这 10 行之外叠加机制，循环本身永远不变。

---

> **Bash is all you need. 智能来自模型，框架只是手脚。**
