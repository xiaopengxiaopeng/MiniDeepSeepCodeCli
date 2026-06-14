# 从零构建你的 Code CLI

> 一份 8 节渐进式教程，教你从零构建 AI 编程智能体 CLI —— 配有 TypeScript 参考实现，可直接对接 DeepSeek API。

学的是思想，不是复制。每个机制一句箴言。每节课提供 Python、C++、Java 三种实现。

智能来自模型，框架赋予手脚。本仓库教你造这双手脚。

---

## 从这里开始：8 节渐进课程

每节课只加**一个**机制。按顺序阅读。选你喜欢的语言上手。

| # | 课程 | 箴言 | 核心概念 |
|---|------|------|----------|
| [s01](learn-code-cli/s01_agent_loop/) | Agent 循环 | "一个循环 + bash，就是全部" | `while (tool_calls)` / 停止判断 |
| [s02](learn-code-cli/s02_tool_use/) | 工具使用 | "加一个工具，加一个 handler" | 分发映射 / 路径安全 |
| [s03](learn-code-cli/s03_permission/) | 权限系统 | "先设边界，再给自由" | 三道闸门：拦截 → 警告 → 确认 |
| [s04](learn-code-cli/s04_todo_write/) | 任务规划 | "没有计划的 Agent 会迷失" | 任务列表 / 催促提醒 |
| [s05](learn-code-cli/s05_subagent/) | 子智能体 | "大事化小，干净上下文" | 上下文隔离 / 工具限制 |
| [s06](learn-code-cli/s06_context_compact/) | 上下文压缩 | "上下文总会满 —— 腾空间" | snip → micro → budget |
| [s07](learn-code-cli/s07_error_recovery/) | 错误恢复 | "错误是重试的起点" | 指数退避 / 反应式压缩 |
| [s08](learn-code-cli/s08_full_agent/) | 完整智能体 | "无数机制，一个循环" | 七大机制合一参考实现 |

**学习路径：** 行动 → 处理复杂 → 记忆 → 恢复 → 组装。

```
s01 ──→ s02 ──→ s03 ──→ s04 ──→ s05 ──→ s06 ──→ s07 ──→ s08
循环    工具    权限    规划    派生    压缩    恢复    完整
```

### 怎么读

每节课是一个独立文件夹：

```
s01_agent_loop/
  README.md          # 中文教程（完整叙述 + 内联代码）
  README.en.md       # 英文教程
  python/code.py     # Python 3.x — 可直接运行
  cpp/main.cpp       # C++17 — 单文件编译
  java/Main.java     # Java 17 — 单文件编译
```

1. 打开 README —— 理解概念，记住箴言
2. 读代码 —— 每段都标注了来源（`继承自 s01`、`本节新增`）
3. 跑起来：`python code.py` / `g++ -std=c++17 main.cpp && ./a.out` / `javac Main.java && java Main`

所有实现都使用**模拟 LLM** —— 不需要 API Key。循环是真的，工具是真的，只有模型回复是模拟的，让你立刻看到每个机制的运行效果。

### 核心模式

无论什么语言、什么机制，Agent 循环不超过 10 行：

```
while (finish_reason == "tool_calls"):
    response = LLM(messages, tools)
    messages.append(assistant_msg)
    for tc in response.tool_calls:
        result = TOOL_HANDLERS[tc.name](**tc.args)
        messages.append(tool_result(tc.id, result))
```

每节课在这 10 行之外叠加一层机制。循环本身，永不修改。

---

## 参考实现：DeepSeek Code CLI

`src/` 目录是一个**可直接使用**的 TypeScript CLI，对接真实的 DeepSeek API，七大机制全部在线。它就是教程要教你造的那个东西。

```
src/
  index.ts              # CLI 入口（交互 + 非交互模式）
  agent.ts              # 核心 Agent 循环（流式输出）
  api-client.ts         # DeepSeek API 封装（OpenAI 兼容协议）
  tool-registry.ts      # 工具定义 + 分发映射
  tools/
    bash.ts             # Shell 执行（拦截 rm -rf /、sudo 等）
    read-file.ts        # 文件读取，支持分页
    write-file.ts       # 文件写入，自动创建目录
    edit-file.ts        # 精确字符串替换 + replaceAll
    glob.ts             # Glob 模式匹配，按修改时间排序
    grep.ts             # 正则搜索，支持文件类型过滤
    todo-write.ts       # 任务规划，状态追踪
    task.ts             # 子智能体派生，干净上下文
  harness/
    hooks.ts            # PreToolUse / PostToolUse / Stop 钩子
    compaction.ts       # 四层上下文压缩管线
    memory.ts           # 磁盘持久化关键词匹配记忆
    system-prompt.ts    # 运行时提示词组装
    path-safety.ts      # 工作区边界安全
```

### 快速开始

```bash
git clone https://github.com/xiaopengxiaopeng/MiniDeepSeepCodeCli.git
cd MiniDeepSeepCodeCli
npm install && npm run build

# 设置 API Key
echo "DEEPSEEK_API_KEY=sk-your-key" > .env

# 交互模式
npm start

# 单次问答
npm start "解释一下这个项目的架构"

# 开发模式
npm run dev
```

### 交互命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助 |
| `/quit`、`/exit` | 退出 |
| `/clear` | 清空对话历史 |
| `/memory` | 查看相关记忆 |
| `/compact` | 手动压缩对话 |
| `/model` | 查看当前模型 |
| `/workspace` | 查看工作目录 |

### 工具速查

| 工具 | 功能 |
|------|------|
| `bash` | 在工作目录执行 shell 命令 |
| `read_file` | 读取文件，支持 `path`/`offset`/`limit` |
| `write_file` | 创建或覆盖文件 |
| `edit_file` | 替换 `old_string` 为 `new_string`（支持 `replace_all`） |
| `glob` | 按模式查找文件，按修改时间排序 |
| `grep` | 正则搜索，支持 `include` 过滤文件类型 |
| `todo_write` | 创建/更新任务列表：`[{content, status}]` |
| `task` | 派生子智能体处理复杂子任务 |
| `compact` | 压缩对话历史释放上下文 |

---

## 设计原则

1. **模型决策，框架执行** — LLM 决定调什么工具；harness 只负责执行，绝不越位。

2. **一个循环，无数机制** — 工具、权限、钩子、压缩全在一个 `while (tool_calls)` 循环上叠加。

3. **先便宜后贵** — 上下文压缩先走 budget → snip → micro（零 API 调用），不行再上 LLM 摘要。

4. **加工具，加 handler** — 新工具注册到分发映射表就完事，循环不用改。

5. **宁可多问，不可乱动** — 危险操作必须过权限管线。安全是设计，不是补丁。

## License

MIT
