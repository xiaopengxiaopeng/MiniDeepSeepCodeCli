"""
s01 Agent Loop — Python Implementation
======================================
The core agent loop: while (finish_reason == "tool_calls"):
    response = LLM(messages, tools)
    execute tools → append results → loop

Run: python code.py
"""

import subprocess
import json
import os
import sys
import re

# ═══════════════════════════════════════════════════════════════
# ANSI Color Helpers
# ═══════════════════════════════════════════════════════════════

class C:
    R = '\033[91m'
    G = '\033[92m'
    Y = '\033[93m'
    B = '\033[94m'
    M = '\033[95m'
    C = '\033[96m'
    W = '\033[97m'
    BOLD = '\033[1m'
    DIM = '\033[2m'
    X = '\033[0m'


# ═══════════════════════════════════════════════════════════════
# Tool Definition: bash
# ═══════════════════════════════════════════════════════════════

TOOLS = [{
    "type": "function",
    "function": {
        "name": "bash",
        "description": "Execute a shell command and return the output",
        "parameters": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "The shell command to execute"
                }
            },
            "required": ["command"]
        }
    }
}]


# ═══════════════════════════════════════════════════════════════
# Bash Execution
# ═══════════════════════════════════════════════════════════════

def execute_bash(command):
    """Execute a shell command and return stdout + stderr."""
    try:
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=30,
            cwd=os.getcwd()
        )
        output = result.stdout
        if result.stderr:
            output += "\n" + result.stderr
        if not output.strip():
            output = "(command executed successfully, no output)"
        return output.strip()
    except subprocess.TimeoutExpired:
        return "Error: Command timed out after 30 seconds"
    except Exception as e:
        return f"Error: {e}"


# ═══════════════════════════════════════════════════════════════
# Mock LLM
# ═══════════════════════════════════════════════════════════════

def mock_llm(messages, tools):
    """
    Simulates an LLM API call for demonstration purposes.

    REPLACE WITH ACTUAL API CALL:
    ┌─────────────────────────────────────────────────────────┐
    │ import requests                                         │
    │                                                         │
    │ def llm(messages, tools):                               │
    │     r = requests.post(                                  │
    │         "https://api.deepseek.com/v1/chat/completions", │
    │         headers={"Authorization": f"Bearer {API_KEY}"}, │
    │         json={                                          │
    │             "model": "deepseek-chat",                   │
    │             "messages": messages,                       │
    │             "tools": tools                              │
    │         }                                               │
    │     )                                                   │
    │     return r.json()["choices"][0]                       │
    └─────────────────────────────────────────────────────────┘
    """
    # Find the last user message
    last_user = ""
    for msg in reversed(messages):
        if msg["role"] == "user":
            last_user = msg["content"].lower()
            break

    # Count how many tool results are already in the conversation
    tool_count = sum(1 for m in messages if m["role"] == "tool")

    # If we already have tool results, return a final text answer
    if tool_count > 0:
        return {
            "finish_reason": "stop",
            "message": {
                "role": "assistant",
                "content": _get_final_answer(last_user)
            }
        }

    # Otherwise, determine which tools to call
    tool_calls = _get_tool_calls(last_user)
    if tool_calls:
        return {
            "finish_reason": "tool_calls",
            "message": {
                "role": "assistant",
                "content": None,
                "tool_calls": tool_calls
            }
        }

    # No tools needed — return a direct answer
    return {
        "finish_reason": "stop",
        "message": {
            "role": "assistant",
            "content": _get_final_answer(last_user)
        }
    }


def _get_tool_calls(query):
    """Decide which bash commands to run based on the user's query."""
    call_id = 0
    commands = []
    is_win = sys.platform == "win32"

    def add(cmd):
        nonlocal call_id
        commands.append(cmd)
        call_id += 1

    # ── File listing ──
    if any(w in query for w in ["list", "show", "ls", "dir", "files", "what's in", "what is in"]):
        if any(w in query for w in ["all", "hidden", "-a", "-la"]):
            add("ls -la")
        else:
            add("ls")

    # ── File creation ──
    if (any(w in query for w in ["create", "make", "new", "write"])
            and any(w in query for w in ["file", "txt", "document"])):
        add('echo "Hello, World! This file was created by the AI agent." > demo.txt && echo "Created demo.txt"')

    # ── System info ──
    if any(w in query for w in ["system", "os", "uname", "kernel", "version"]):
        if is_win:
            add('systeminfo | findstr /B /C:"OS Name" /C:"OS Version"')
        else:
            add("uname -a")

    # ── Disk space ──
    if any(w in query for w in ["disk", "space", "df", "storage"]):
        if is_win:
            add("wmic logicaldisk get size,freespace,caption")
        else:
            add("df -h")

    # ── Memory ──
    if any(w in query for w in ["memory", "ram", "mem", "free"]):
        if is_win:
            add('systeminfo | findstr /C:"Total Physical Memory" /C:"Available Physical Memory"')
        else:
            add("free -h")

    # ── Current directory ──
    if any(w in query for w in ["pwd", "current directory", "where am i", "working directory", "cwd"]):
        add("cd" if is_win else "pwd")

    # ── Echo ──
    if any(w in query for w in ["echo", "say", "print"]) and "system" not in query:
        m = re.search(r'(?:echo|say|print)\s+"([^"]*)"', query)
        if m:
            add(f'echo {m.group(1)}')
        else:
            add('echo "Hello from the AI agent!"')

    # ── Date/Time ──
    if any(w in query for w in ["date", "time", "today", "now"]):
        add("date /t && time /t" if is_win else "date")

    # ── Processes ──
    if any(w in query for w in ["process", "ps", "task", "running", "top"]):
        add("tasklist" if is_win else "ps aux")

    # ── Default fallback ──
    if not commands:
        add("echo 'No specific command matched. Try: list files, system info, disk space, memory, create file, date'")

    # Build tool_calls array in OpenAI format
    return [{
        "id": f"call_{i:04d}",
        "type": "function",
        "function": {
            "name": "bash",
            "arguments": json.dumps({"command": cmd})
        }
    } for i, cmd in enumerate(commands)]


def _get_final_answer(query):
    """Generate a mock final answer based on keywords found in the query."""
    q = query.lower()
    if any(w in q for w in ["list", "file", "show", "ls", "dir"]):
        return "Here are the files in the current directory. The agent used bash to run the listing command."
    if any(w in q for w in ["system", "os", "kernel", "version", "info"]):
        return "Here's your system information. I ran the appropriate system info command to retrieve it."
    if any(w in q for w in ["disk", "space", "storage"]):
        return "Here's the disk usage information. I queried the system for storage details."
    if any(w in q for w in ["memory", "ram", "mem", "free"]):
        return "Here's the memory information. This shows your current RAM usage and availability."
    if any(w in q for w in ["create", "make", "new", "write"]):
        return "I've created the file using a bash command. It's been written to disk."
    if any(w in q for w in ["date", "time", "today", "now"]):
        return "Here's the current date and time from the system clock."
    if any(w in q for w in ["process", "running", "task"]):
        return "Here are the currently running processes on your system."
    if any(w in q for w in ["pwd", "directory", "where"]):
        return "Here's your current working directory path."
    return "I've executed the appropriate shell commands. Check the output above for results."


# ═══════════════════════════════════════════════════════════════
# Terminal Output Helpers
# ═══════════════════════════════════════════════════════════════

def _banner():
    print(f"{C.C}{C.BOLD}")
    print("╔══════════════════════════════════════════════╗")
    print("║       🛠  AI Coding Agent — Lesson 01        ║")
    print("║          The Core Agent Loop                 ║")
    print("╚══════════════════════════════════════════════╝")
    print(f"{C.X}")
    print(f"{C.DIM}Type 'quit' or 'exit' to leave. Try: list files, system info, create file{C.X}")
    print()


def _step_header(step, title):
    print(f"\n{C.Y}{C.BOLD}[Step {step}] {title}{C.X}")
    print(f"{C.Y}{'─' * 50}{C.X}")


# ═══════════════════════════════════════════════════════════════
# MAIN: The Agent Loop
# ═══════════════════════════════════════════════════════════════

def main():
    _banner()

    # ── Initialize conversation with system prompt ──
    messages = [{
        "role": "system",
        "content": (
            "You are a helpful coding assistant with access to a bash shell. "
            "Use the bash tool to run shell commands and help the user. "
            "Always think step by step and use tools when needed."
        )
    }]

    while True:
        # ── Get user input ──
        try:
            user_input = input(f"{C.G}{C.BOLD}You:{C.X} ").strip()
        except (EOFError, KeyboardInterrupt):
            print(f"\n{C.DIM}Goodbye!{C.X}")
            break

        if user_input.lower() in ("quit", "exit", "q"):
            print(f"{C.DIM}Goodbye!{C.X}")
            break
        if not user_input:
            continue

        messages.append({"role": "user", "content": user_input})
        turn_step = 0

        # ═══════════════════════════════════════════════════════
        # THE CORE AGENT LOOP
        # ═══════════════════════════════════════════════════════
        while True:
            turn_step += 1
            _step_header(turn_step, "Calling LLM...")

            # ── Call the LLM (mock or real) ──
            choice = mock_llm(messages, TOOLS)

            finish_reason = choice["finish_reason"]
            assistant_msg = choice["message"]

            # ── Branch: tool_calls ──
            if finish_reason == "tool_calls":
                tool_calls = assistant_msg["tool_calls"]
                print(f"{C.M}{C.BOLD}  LLM decided to call {len(tool_calls)} tool(s){C.X}")

                messages.append(assistant_msg)

                for i, tc in enumerate(tool_calls):
                    func_name = tc["function"]["name"]
                    args = json.loads(tc["function"]["arguments"])
                    cmd = args.get("command", "")

                    print(f"{C.B}  Tool: {func_name}{C.X}")
                    print(f"{C.DIM}  Command: {cmd}{C.X}")

                    _step_header(turn_step, f"Executing: {cmd}")
                    result = execute_bash(cmd)

                    # Print result (first 20 lines)
                    lines = result.split('\n')
                    print(f"{C.G}  Result ({len(lines)} lines):{C.X}")
                    for line in lines[:20]:
                        print(f"{C.DIM}    | {line}{C.X}")
                    if len(lines) > 20:
                        print(f"{C.DIM}    | ... ({len(lines)} total lines){C.X}")

                    messages.append({
                        "role": "tool",
                        "tool_call_id": tc["id"],
                        "content": result
                    })

                print(f"{C.Y}  Looping back to LLM with results...{C.X}")

            # ── Branch: stop ──
            else:
                content = assistant_msg.get("content", "")
                print(f"\n{C.G}{C.BOLD}Agent:{C.X} {C.W}{content}{C.X}")
                print()
                messages.append(assistant_msg)
                break
            # ═══════════════════════════════════════════════════
            # END CORE AGENT LOOP
            # ═══════════════════════════════════════════════════


if __name__ == "__main__":
    main()
