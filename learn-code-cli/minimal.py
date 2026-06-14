"""
minimal.py — the smallest possible coding agent
================================================
One loop. One tool (bash). ~50 lines. That's it.

Run:    python minimal.py
Type queries, see the agent call bash, loop until done.
"""

import subprocess, json, os

WORKDIR = os.getcwd()

def llm(query, called):
    """Mock LLM — returns {"content": "..."} or {"tool_calls": [...]}."""
    q = query.lower()
    if called:  # already ran a tool — stop
        return {"content": "Output shown above."}
    if "list" in q or "file" in q or "dir" in q:
        cmd = "dir" if os.name == "nt" else "ls"
        return {"tool_calls": [{"id": "t1", "function": {"name": "bash", "arguments": json.dumps({"cmd": cmd})}}]}
    if "hi" in q or "hello" in q:
        return {"content": "Hello! Try: list files"}
    return {"tool_calls": [{"id": "t1", "function": {"name": "bash", "arguments": json.dumps({"cmd": query})}}]}

def bash(cmd):
    try:
        r = subprocess.run(cmd, shell=True, cwd=WORKDIR, capture_output=True, text=True, timeout=10)
        return (r.stdout + r.stderr).strip()[:3000] or "(no output)"
    except: return "Error"

TOOLS = {"bash": bash}
print("Mini Agent (q to quit)\n")

while True:
    q = input("> ").strip()
    if q.lower() in ("q", "exit", ""): break
    messages = [{"role": "user", "content": q}]

    for _ in range(3):
        called = any(m["role"] == "assistant" for m in messages)
        r = llm(q, called)
        if "tool_calls" not in r:
            print(r.get("content", ""))
            messages.append({"role": "assistant", "content": r.get("content", "")})
            break
        for tc in r["tool_calls"]:
            args = json.loads(tc["function"]["arguments"])
            out = TOOLS[tc["function"]["name"]](**args)
            print(f"[bash] {args['cmd']}\n{out[:300]}")
            messages.append({"role": "assistant", "tool_calls": r["tool_calls"]})
            messages.append({"role": "tool", "tool_call_id": tc["id"], "content": out})
    print()
