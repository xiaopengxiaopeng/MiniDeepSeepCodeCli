"""
minimal.py — the smallest possible coding agent (REAL API)
===========================================================
One loop. One tool (bash). ~60 lines. With real DeepSeek API.

Setup:  pip install openai python-dotenv
        echo DEEPSEEK_API_KEY=sk-xxx > .env
Run:    python minimal.py
"""

import subprocess, json, os
from openai import OpenAI
from dotenv import load_dotenv

load_dotenv()
WORKDIR = os.getcwd()

client = OpenAI(
    base_url=os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1"),
    api_key=os.getenv("DEEPSEEK_API_KEY"),
)
MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")

TOOL_DEFS = [{"type": "function", "function": {
    "name": "bash", "description": "Run a shell command.",
    "parameters": {"type": "object", "properties": {"cmd": {"type": "string"}}, "required": ["cmd"]}
}}]

SYSTEM = f"You are a coding agent. Use bash to solve tasks at {WORKDIR}. Be concise."

def bash(cmd):
    try:
        r = subprocess.run(cmd, shell=True, cwd=WORKDIR, capture_output=True, text=True, timeout=30)
        return (r.stdout + r.stderr).strip()[:50000] or "(no output)"
    except: return "Error"

TOOLS = {"bash": bash}

def llm(messages):
    """Real DeepSeek API call — returns {"content": str} or {"tool_calls": [...]}"""
    r = client.chat.completions.create(
        model=MODEL, messages=messages, tools=TOOL_DEFS, temperature=0, max_tokens=8000
    )
    msg = r.choices[0].message
    if msg.tool_calls:
        return {"tool_calls": [{"id": t.id, "function": {"name": t.function.name, "arguments": t.function.arguments}} for t in msg.tool_calls]}
    return {"content": msg.content or "Done."}

print(f"DeepSeek Agent ({MODEL}) — q to quit\n")

while True:
    q = input("> ").strip()
    if q.lower() in ("q", "exit", ""): break
    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": q}
    ]

    for _ in range(10):
        r = llm(messages)
        if "tool_calls" not in r:
            print(r["content"])
            messages.append({"role": "assistant", "content": r["content"]})
            break
        for tc in r["tool_calls"]:
            args = json.loads(tc["function"]["arguments"])
            out = TOOLS[tc["function"]["name"]](**args)
            cmd = args.get("cmd", tc["function"]["name"])
            print(f"[bash] {cmd}\n{out[:500]}")
            messages.append({"role": "assistant", "tool_calls": [tc], "content": None})
            messages.append({"role": "tool", "tool_call_id": tc["id"], "content": out})
    print()
