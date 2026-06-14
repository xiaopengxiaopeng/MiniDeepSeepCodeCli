# s02 工具调用 — 调度映射模式

## 核心概念

s01 只有一个 `bash` 工具。要加第二个工具？你会忍不住在循环里写 `if tool == "read"`，然后第三个，第四个……很快循环就变成意大利面条。

**s02 的答案：调度映射（dispatch map）。**

```python
TOOL_HANDLERS = {
    "bash":      handle_bash,
    "read_file": handle_read_file,
    "write_file": handle_write_file,
    "edit_file": handle_edit_file,
    "glob":      handle_glob,
}
```

工具执行变成了：

```python
handler = TOOL_HANDLERS[tool_name]
result = handler(args)
```

循环本身**一行不改**。加一个工具 = 写一个 handler 函数 + 在字典里加一行映射。

## s01 → s02 变化

| 维度 | s01 | s02 |
|------|-----|-----|
| 工具数量 | 1（bash） | 5（bash, read_file, write_file, edit_file, glob） |
| 工具分发 | 硬编码 `if` | TOOL_HANDLERS 字典调度 |
| 路径安全 | 无 | workspace 边界检查 |
| 新增概念 | — | 调度映射、路径沙箱 |

## 新增工具一览

| 工具 | 参数 | 功能 |
|------|------|------|
| `bash` | `command: string` | 在 workspace 中执行 shell 命令 |
| `read_file` | `path: string`, `offset?: int`, `limit?: int` | 读取文件内容（带行号分页） |
| `write_file` | `path: string`, `content: string` | 创建/覆盖文件 |
| `edit_file` | `path: string`, `old_string: string`, `new_string: string`, `replace_all?: bool` | 精确字符串替换 |
| `glob` | `pattern: string` | 按 glob 模式匹配文件，按修改时间排序 |

## 代码走读

### 1. 工具定义（Tool Definitions）

每个工具都声明其名称、描述和参数 schema。这是发送给 LLM 的接口契约——LLM 看到这些定义后决定调用哪个工具、传什么参数。

```python
TOOL_DEFINITIONS = [
    {
        "name": "read_file",
        "description": "Read a file from the workspace...",
        "parameters": {
            "path": {"type": "string", ...},
            "offset": {"type": "integer", ...},
            "limit": {"type": "integer", ...},
        },
    },
    ...
]
```

### 2. 调度映射（Dispatch Map）

这是本文的核心模式。一个字典，键是工具名，值是对应的处理函数。

```python
TOOL_HANDLERS = {
    "bash":      run_bash,
    "read_file": run_read_file,
    "write_file": run_write_file,
    "edit_file": run_edit_file,
    "glob":      run_glob,
}
```

执行工具时：

```python
def execute_tool(tool_call):
    name = tool_call["name"]
    args = tool_call["arguments"]
    handler = TOOL_HANDLERS.get(name)
    if handler is None:
        return f"Unknown tool: {name}"
    return handler(args)
```

### 3. Agent 循环（与 s01 完全一致）

```python
def agent_loop(messages):
    for turn in range(MAX_TURNS):
        response = call_llm(messages, TOOL_DEFINITIONS)
        messages.append({"role": "assistant", "content": response})

        if not response.get("tool_calls"):
            return response["content"]

        # 执行所有工具调用
        for tc in response["tool_calls"]:
            result = execute_tool(tc)
            messages.append({"role": "tool", "content": result})

        # 回到循环顶部，让 LLM 看到工具结果后继续决策
```

**循环逻辑一个字没动。** 工具数量从 1 变成 5，循环完全无感。

### 4. 路径安全

所有文件操作工具都通过 `safe_path()` 函数确保目标路径在 workspace 范围内，防止 LLM 越权访问 `../../etc/passwd` 之类的路径。

```python
def safe_path(filepath):
    workspace = os.path.abspath(WORKSPACE_DIR)
    target = os.path.abspath(os.path.join(workspace, filepath))
    if not target.startswith(workspace):
        raise ValueError(f"Path escapes workspace: {filepath}")
    return target
```

## 设计原则

> **"加一个工具 = 加一个 handler。循环不动。"**

这是后续所有步骤（权限系统、subagent、compaction）的基础。每个新机制都通过"不碰循环"的方式叠加上去。

## 运行示例

```
=== s02: Tool Use (Dispatch Map) ===

> 请帮我找一下项目里的 Python 文件

[glob] **/*.py
src/main.py
src/utils.py
tests/test_main.py

[read_file] src/main.py
1: import sys
2: from utils import greet
3:
4: def main():
...

> 完成！
```

三种语言的完整实现见对应目录。
