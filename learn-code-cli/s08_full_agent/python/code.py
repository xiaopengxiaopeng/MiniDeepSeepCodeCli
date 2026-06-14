"""
s08  Full Agent --- "Many mechanisms, one loop."
=================================================
Combines all 7 mechanisms from s01-s07 into one complete, runnable agent.
This is the capstone reference implementation for "Build Your Own Code CLI".

  s01  Agent Loop       --- while running: observe -> plan -> act
  s02  Multi-Tool       --- bash, read_file, write_file, edit_file, glob
  s03  Permissions      --- deny list, destructive detection, user confirm
  s04  Todo Write       --- task planning with nag reminders
  s05  Subagents        --- clean context delegation
  s06  Compaction       --- snip, micro, budget layers
  s07  Error Recovery   --- retries with exponential backoff

Every mechanism is a thin layer that does NOT change the core loop.
The loop is ONE `while self.running:` --- all mechanisms orbit around it.

Run:  python code.py
"""

import os
import sys
import time
import random
import re
import textwrap
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple


# ============================================================================
# ===  FROM s02: Mock Virtual File System  ==================================
# ============================================================================
# So that read_file, write_file, edit_file, and glob actually work against
# a simulated file-system in memory.  Not part of the agent loop itself,
# just an environment for the tools to operate on.

class VirtualFS:
    """In-memory file system for tool demonstrations."""

    def __init__(self):
        self.files: Dict[str, str] = {
            "demo/file1.txt": (
                "Hello from file1!\n"
                "This is a sample file with multiple lines.\n"
                "Line 3: the agent can read this.\n"
                "Line 4: end of file."
            ),
            "demo/config.json": '{\n  "host": "localhost",\n  "port": 8080,\n  "debug": true\n}',
            "demo/src/main.py": (
                "def main():\n"
                '    print("Hello, World!")\n'
                "\n"
                "if __name__ == '__main__':\n"
                "    main()"
            ),
            "demo/src/utils.py": "def helper():\n    return 42",
            "demo/huge.log": "\n".join(
                [f"[{i:04d}] log entry number {i}" for i in range(120)]
            ),
            "demo/notes.txt": "TODO: buy milk\nTODO: learn Rust\nDONE: breathe",
            "demo/bad.sh": "#!/bin/bash\nrm -rf /important/data",
        }

    def read(self, path: str) -> Optional[str]:
        return self.files.get(path)

    def write(self, path: str, content: str) -> None:
        self.files[path] = content

    def exists(self, path: str) -> bool:
        return path in self.files

    def glob(self, pattern: str) -> List[str]:
        # Simple glob: convert '*' -> '.*'
        regex = "^" + re.escape(pattern).replace(r"\*", ".*") + "$"
        return sorted(p for p in self.files if re.match(regex, p))


# ============================================================================
# ===  FROM s02: Tool Definitions & Dispatch Map  ===========================
# ============================================================================
# Each tool is a simple function that takes params and returns a ToolResult.
# The dispatch map (dict) routes tool names to implementations.
# Adding a new tool = 1 function + 1 entry in the map.  The agent loop never
# changes.

@dataclass
class ToolResult:
    success: bool
    output: str
    tool_name: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)


# -- Tool implementations ---------------------------------------------------

def tool_read_file(fs: VirtualFS, params: dict) -> ToolResult:
    path = params.get("path", "")
    content = fs.read(path)
    if content is None:
        return ToolResult(False, f"ERROR: file not found: {path}", "read_file")
    return ToolResult(True, content, "read_file", {"lines": len(content.splitlines())})


def tool_write_file(fs: VirtualFS, params: dict) -> ToolResult:
    path = params.get("path", "")
    content = params.get("content", "")
    if not path:
        return ToolResult(False, "ERROR: no path provided", "write_file")
    fs.write(path, content)
    sz = len(content)
    return ToolResult(True, f"Wrote {sz} bytes to {path}", "write_file", {"bytes": sz})


def tool_edit_file(fs: VirtualFS, params: dict) -> ToolResult:
    path = params.get("path", "")
    old = params.get("old", "")
    new = params.get("new", "")
    content = fs.read(path)
    if content is None:
        return ToolResult(False, f"ERROR: file not found: {path}", "edit_file")
    if old not in content:
        return ToolResult(
            False,
            f"ERROR: string '{old[:30]}...' not found in {path}",
            "edit_file",
        )
    fs.write(path, content.replace(old, new, 1))
    return ToolResult(True, f"Edited {path}: '{old[:20]}...' -> '{new[:20]}...'", "edit_file")


def tool_glob(fs: VirtualFS, params: dict) -> ToolResult:
    pattern = params.get("pattern", "*")
    matches = fs.glob(pattern)
    out = "\n".join(matches) if matches else "(no matches)"
    return ToolResult(True, out, "glob", {"count": len(matches)})


def tool_bash(params: dict) -> ToolResult:
    """Mock bash: simulates shell execution."""
    cmd = params.get("cmd", "")
    # Simulate a few commands
    if cmd.startswith("echo "):
        return ToolResult(True, cmd[5:].strip(), "bash")
    if cmd == "ls" or cmd.startswith("ls "):
        return ToolResult(True, "file1.txt  config.json  src/", "bash")
    if "error" in cmd.lower() or cmd == "exit 1":
        return ToolResult(False, "Command failed with exit code 1", "bash", {"exit_code": 1})
    if cmd == "pwd":
        return ToolResult(True, "/home/user/project", "bash")
    # Default
    return ToolResult(True, f"(simulated) ran: {cmd}", "bash")


def build_tool_map(fs: VirtualFS) -> Dict[str, Callable]:
    """s02: The dispatch map. Add tools here; the loop stays the same."""
    return {
        "read_file":   lambda p: tool_read_file(fs, p),
        "write_file":  lambda p: tool_write_file(fs, p),
        "edit_file":   lambda p: tool_edit_file(fs, p),
        "glob":        lambda p: tool_glob(fs, p),
        "bash":        lambda p: tool_bash(p),
    }


# ============================================================================
# ===  FROM s03: Permission Pipeline  =======================================
# ============================================================================
# Three layers:  (1) deny-list block, (2) destructive-pattern detection,
# (3) user confirmation for dangerous operations.
# All checks happen BEFORE tool execution.  The agent loop calls
# permissions.check() and only runs the tool if approved.

DENY_LIST = [
    "rm -rf /",
    "format c:",
    "shutdown -h now",
    "del /f /s",
    ":(){ :|:& };:",
]

DESTRUCTIVE_PATTERNS = [
    (r"\brm\b",          "file deletion"),
    (r"\bdel\b",         "file deletion"),
    (r"\bdrop\s+table\b", "database destruction"),
    (r"\bformat\b",      "disk formatting"),
    (r"\bshutdown\b",    "system shutdown"),
    (r"\bchmod\s+777\b", "permissive permissions"),
    (r">\s*/dev/",       "device overwrite"),
]


class PermissionGate:
    """s03: Permission pipeline --- the thin layer that keeps you safe."""

    def __init__(self, auto_approve: bool = True):
        self.auto_approve = auto_approve   # For demo/testing
        self.block_count = 0

    def check(self, tool_name: str, params: dict) -> Tuple[bool, str]:
        """
        Returns (approved: bool, reason: str).
        - False means BLOCKED.
        - True means ALLOWED.
        """
        cmd = params.get("cmd", "") if tool_name == "bash" else ""

        # Layer 1: Deny list (hard block)
        for blocked in DENY_LIST:
            if blocked.lower() in cmd.lower():
                self.block_count += 1
                return False, f"BLOCKED by deny-list: matches '{blocked}'"

        # Layer 2: Destructive pattern detection
        for pattern, description in DESTRUCTIVE_PATTERNS:
            if re.search(pattern, cmd, re.IGNORECASE):
                if self.auto_approve:
                    return True, f"ALLOWED (auto): destructive pattern '{description}'"
                # Layer 3: User confirmation
                print(f"\n  [PERMISSION] Destructive operation detected: {description}")
                print(f"  Command: {cmd[:80]}")
                answer = input("  Allow? (y/N): ").strip().lower()
                if answer != "y":
                    return False, f"DENIED by user for '{description}'"
                return True, f"ALLOWED by user for '{description}'"

        # Safe operation
        return True, "OK"


# ============================================================================
# ===  FROM s04: Todo Write  ================================================
# ============================================================================
# Task planning with persistent todo list and nag reminders.
# When the agent receives a complex task, it breaks it into steps,
# tracks completion, and nags if items are stale.

@dataclass
class TodoItem:
    id: int
    description: str
    done: bool = False
    created_at: float = 0.0
    nag_count: int = 0

    def age_seconds(self) -> float:
        return time.time() - self.created_at


class TodoManager:
    """s04: Task planner with nag reminders."""

    def __init__(self, nag_threshold: float = 30.0):
        self.items: List[TodoItem] = []
        self.next_id = 1
        self.nag_threshold = nag_threshold  # seconds before nag
        self.last_check = time.time()

    def add_task(self, description: str) -> TodoItem:
        item = TodoItem(self.next_id, description, created_at=time.time())
        self.items.append(item)
        self.next_id += 1
        return item

    def add_tasks(self, descriptions: List[str]) -> List[TodoItem]:
        return [self.add_task(d) for d in descriptions]

    def mark_done(self, item_id: int) -> bool:
        for item in self.items:
            if item.id == item_id:
                item.done = True
                return True
        return False

    def mark_done_by_hint(self, hint: str) -> bool:
        """Mark done if any item description contains the hint."""
        for item in self.items:
            if not item.done and hint.lower() in item.description.lower():
                item.done = True
                return True
        return False

    def pending(self) -> List[TodoItem]:
        return [i for i in self.items if not i.done]

    def is_all_done(self) -> bool:
        return len(self.pending()) == 0

    def nag_if_stale(self) -> Optional[str]:
        """s04: Nag the user if items are overdue."""
        now = time.time()
        if now - self.last_check < 5.0:  # Don't nag too often
            return None
        self.last_check = now

        nags = []
        for item in self.pending():
            if item.age_seconds() > self.nag_threshold and item.nag_count < 3:
                item.nag_count += 1
                nags.append(f"  [!] NAG #{item.nag_count}: '{item.description}' "
                            f"is still pending ({item.age_seconds():.0f}s)")
        return "\n".join(nags) if nags else None

    def summary(self) -> str:
        done = sum(1 for i in self.items if i.done)
        total = len(self.items)
        if total == 0:
            return "(no tasks)"
        bar = "[" + "#" * done + "-" * (total - done) + "]"
        return f"Todo {bar} {done}/{total}"
        # breakpoint() # uncomment for debugging


# ============================================================================
# ===  FROM s05: Subagent Spawning  =========================================
# ============================================================================
# A subagent gets a clean context and works on a single subtask.
# It runs a mini agent loop internally, then returns results.
# The main agent collects results and merges them.

@dataclass
class SubagentResult:
    task: str
    success: bool
    output: str
    steps_taken: int

class Subagent:
    """s05: Lightweight subagent with clean context."""

    def __init__(self, task: str, fs: VirtualFS, sub_id: int):
        self.task = task
        self.fs = fs
        self.id = sub_id
        self.context: List[str] = []    # Clean, separate context
        self.steps = 0

    def run(self) -> SubagentResult:
        """Mini agent loop inside the subagent."""
        max_steps = 5
        self.context.append(f"Subagent-{self.id} starting task: {self.task}")

        # Simulate working through steps based on the task
        if "analyze" in self.task.lower() or "read" in self.task.lower():
            # Extract file path from task string
            path_match = re.search(r'(?:file\s+)?([\w./-]+\.\w+)', self.task)
            file_path = path_match.group(1) if path_match else "demo/file1.txt"

            self.steps += 1
            content = self.fs.read(file_path)
            if content:
                lines = len(content.splitlines())
                self.context.append(f"Read {file_path} ({lines} lines)")
                result = f"Analysis complete: {file_path} has {lines} lines, "
                result += f"{len(content)} chars. Preview: {content[:50]}..."
                return SubagentResult(self.task, True, result, self.steps)
            else:
                return SubagentResult(self.task, False,
                                      f"File not found: {file_path}", self.steps)

        elif "search" in self.task.lower() or "find" in self.task.lower():
            self.steps += 1
            matches = self.fs.glob("*.py")
            self.context.append(f"Glob *.py -> {len(matches)} matches")
            return SubagentResult(self.task, True,
                                  f"Found {len(matches)} Python files: {matches}",
                                  self.steps)
        else:
            # Generic subagent task
            self.steps += 1
            self.context.append(f"Processing: {self.task}")
            time.sleep(0.1)  # Simulate work
            return SubagentResult(self.task, True,
                                  f"Subagent-{self.id} completed: {self.task}",
                                  self.steps)


class SubagentManager:
    """s05: Manages subagent lifecycle."""

    def __init__(self, fs: VirtualFS):
        self.fs = fs
        self.running: Dict[int, Subagent] = {}
        self.completed: List[SubagentResult] = []
        self.next_id = 1

    def spawn(self, task: str) -> int:
        sub_id = self.next_id
        self.next_id += 1
        sub = Subagent(task, self.fs, sub_id)
        print(f"  [SUBAGENT] Spawned subagent-{sub_id} for: {task[:50]}...")
        result = sub.run()
        self.completed.append(result)
        return sub_id

    def collect(self) -> List[SubagentResult]:
        """Collect all completed subagent results and clear queue."""
        results = list(self.completed)
        self.completed.clear()
        return results


# ============================================================================
# ===  FROM s06: Context Compaction  ========================================
# ============================================================================
# Three layers: snip, micro, budget.
#   snip    -- remove old history entries (light)
#   micro   -- summarise older entries (medium)
#   budget  -- strict hard limit, summarise everything aggressively (heavy)

@dataclass
class CompactionLayers:
    SNIP   = "snip"     # Light: drop oldest entries
    MICRO  = "micro"    # Medium: summarise old entries
    BUDGET = "budget"   # Heavy: hard limit enforced

class ContextCompactor:
    """s06: Keeps context within budget."""

    def __init__(self, soft_limit: int = 30, hard_limit: int = 50):
        self.soft_limit = soft_limit    # Entries before micro compaction
        self.hard_limit = hard_limit    # Entries before budget compaction
        self.compaction_count = 0
        self.tokens_saved = 0

    def needs_compaction(self, context: dict) -> bool:
        history = context.get("history", deque())
        budget_used = context.get("budget_used", 0)
        return len(history) > self.soft_limit or budget_used > self.hard_limit * 50

    def compact(self, context: dict, layer: str = CompactionLayers.MICRO) -> dict:
        """Compact context at the specified layer."""
        history: deque = context.get("history", deque())
        self.compaction_count += 1

        if layer == CompactionLayers.SNIP:
            # Light: just drop the oldest entries
            removed = 0
            target = max(10, self.soft_limit // 2)
            while len(history) > target:
                history.popleft()
                removed += 1
            self.tokens_saved += removed * 50  # rough estimate
            print(f"  [COMPACT:snip] Dropped {removed} old entries. "
                  f"History now: {len(history)}")

        elif layer == CompactionLayers.MICRO:
            # Medium: summarise the first half
            mid = len(history) // 2
            old_entries = []
            for _ in range(mid):
                old_entries.append(history.popleft())
            summary = f"[Summarised {len(old_entries)} previous interactions: "
            summary += "; ".join(
                str(e)[:40] for e in old_entries[:5]
            )
            summary += f"... and {len(old_entries) - 5} more]"
            history.appendleft(summary)
            self.tokens_saved += len(old_entries) * 40
            print(f"  [COMPACT:micro] Summarised {len(old_entries)} old entries. "
                  f"History now: {len(history)}")

        elif layer == CompactionLayers.BUDGET:
            # Heavy: keep only the most recent 5 entries, summarise everything else
            keep = min(5, len(history))
            recent = []
            for _ in range(keep):
                recent.append(history.pop())
            old_count = len(history)
            history.clear()
            history.append(f"[BUDGET compaction: removed {old_count} entries, "
                           f"keeping {keep} most recent]")
            for r in reversed(recent):
                history.append(r)
            self.tokens_saved += old_count * 60
            print(f"  [COMPACT:budget] Hard compaction! Kept {keep}/{old_count + keep}. "
                  f"History now: {len(history)}")

        context["history"] = history
        context["budget_used"] = len(history) * 30  # rough token estimate
        return context


# ============================================================================
# ===  FROM s07: Error Recovery  ============================================
# ============================================================================
# Retries with exponential backoff + reactive compaction on repeated errors.
# - Max 3 retries
# - Backoff: 0.5s, 1s, 2s
# - On retry exhaustion, triggers budget compaction

@dataclass
class RetryStats:
    attempt: int = 0
    last_error: str = ""
    total_retries: int = 0

class ErrorRecovery:
    """s07: Resilient tool execution with retries and backoff."""

    MAX_RETRIES = 3
    BASE_BACKOFF = 0.5  # seconds

    def __init__(self):
        self.stats = RetryStats()
        self.recent_failures: deque = deque(maxlen=5)

    def execute_with_retry(
        self,
        tool_fn: Callable,
        params: dict,
        tool_name: str,
        compactor: Optional[ContextCompactor] = None,
        context: Optional[dict] = None,
    ) -> ToolResult:
        """s07: Execute a tool with retry logic."""
        last_result: Optional[ToolResult] = None

        for attempt in range(1, self.MAX_RETRIES + 1):
            self.stats.attempt = attempt
            try:
                result = tool_fn(params)
                if result.success:
                    return result
                # Tool reported failure
                last_result = result
                self.stats.last_error = result.output
                self.recent_failures.append((tool_name, result.output[:80]))
            except Exception as e:
                self.stats.last_error = str(e)
                self.recent_failures.append((tool_name, str(e)[:80]))
                last_result = ToolResult(False, f"Exception: {e}", tool_name)

            if attempt < self.MAX_RETRIES:
                backoff = self.BASE_BACKOFF * (2 ** (attempt - 1))
                print(f"  [RETRY] {tool_name} failed (attempt {attempt}/{self.MAX_RETRIES}). "
                      f"Retrying in {backoff:.1f}s...")
                time.sleep(backoff)
                self.stats.total_retries += 1

        # All retries exhausted -> trigger reactive compaction
        print(f"  [RECOVERY] All {self.MAX_RETRIES} retries exhausted for {tool_name}.")
        if compactor and context and compactor.needs_compaction(context):
            print(f"  [RECOVERY] Triggering reactive compaction...")
            compactor.compact(context, CompactionLayers.BUDGET)

        return last_result or ToolResult(False, "All retries exhausted", tool_name)


# ============================================================================
# ===  FROM s01: Mock LLM (the "brain")  ====================================
# ============================================================================
# In a real agent, this is Claude/GPT.  Here it's a simple parser that
# translates human-ish queries into structured tool-call plans.
# The mock LLM demonstrates every tool type and multi-step orchestration.

class MockLLM:
    """s01: Mock LLM that parses queries into tool calls."""

    def parse(self, query: str) -> dict:
        """
        Parse a user query into a structured plan.
        Returns a dict with one of:
          {"tool": "tool_name", "params": {...}}           -- single tool call
          {"plan": True, "steps": [...str], "parallel": [...]} -- multi-step plan
          {"subagent": True, "task": "..."}                 -- delegate to subagent
        """
        q = query.strip()

        # --- Single tool commands ---

        m = re.match(r"^read\s+(\S+)(?:\s+.*)?$", q, re.IGNORECASE)
        if m:
            return {"tool": "read_file", "params": {"path": m.group(1).strip()}}

        m = re.match(r"^write\s+(\S+)\s+(.+)$", q, re.IGNORECASE)
        if m:
            return {"tool": "write_file", "params": {"path": m.group(1), "content": m.group(2)}}

        m = re.match(r"^edit\s+(\S+)\s+(.+?)\s+->\s+(.+)$", q, re.IGNORECASE)
        if m:
            return {"tool": "edit_file", "params": {
                "path": m.group(1), "old": m.group(2), "new": m.group(3)
            }}

        m = re.match(r"^(?:find|glob|search)\s+(.+)$", q, re.IGNORECASE)
        if m:
            return {"tool": "glob", "params": {"pattern": m.group(1).strip()}}

        m = re.match(r"^bash\s+(.+)$", q, re.IGNORECASE)
        if m:
            return {"tool": "bash", "params": {"cmd": m.group(1).strip()}}

        # --- Complex / multi-step commands ---

        if "build" in q.lower() or "create" in q.lower() or "complex" in q.lower():
            # Generate a todo plan with multiple steps
            steps = self._plan_build_task(q)
            parallel_tasks = [s for s in steps if "search" in s.lower() or "find" in s.lower()]
            sequential_tasks = [s for s in steps if s not in parallel_tasks]
            return {
                "plan": True,
                "description": q,
                "steps": steps,
                "parallel": parallel_tasks,
            }

        # --- Subagent delegation ---

        if "subagent" in q.lower():
            task = re.sub(r"^subagent\s+", "", q, flags=re.IGNORECASE)
            return {"subagent": True, "task": task}

        if "analyze" in q.lower():
            return {"subagent": True, "task": q}

        # --- Error recovery demo ---

        if "error" in q.lower() or "fail" in q.lower():
            return {"tool": "bash", "params": {"cmd": "exit 1 --error test"}}

        # --- Default fallback ---

        return {"tool": "bash", "params": {"cmd": f"echo 'echo: {q[:60]}'"}}

    def _plan_build_task(self, query: str) -> List[str]:
        """s04: Generate a multi-step todo plan for a complex task."""
        if "calculator" in query.lower():
            return [
                "Read demo/src/main.py for reference",
                "Write calculator.py with add/subtract/multiply/divide functions",
                "Write test_calculator.py with unit tests",
                "Find *.py to verify all files created",
                "Bash: run python test_calculator.py",
                "Edit calculator.py to add modulo operation",
            ]
        elif "web" in query.lower() or "server" in query.lower():
            return [
                "Read demo/config.json for configuration",
                "Write server.py with HTTP handler",
                "Write routes.py with URL routing",
                "Find *.py to verify structure",
                "Bash: run python server.py --check",
            ]
        else:
            return [
                f"Step 1: Plan architecture for '{query[:30]}...'",
                f"Step 2: Write core implementation",
                f"Step 3: Write tests",
                f"Step 4: Verify with bash command",
                f"Step 5: Edit for polish",
            ]


# ============================================================================
# ===  FROM s01: The Agent Loop (the ONE loop)  =============================
# ============================================================================
# Every mechanism from s02-s07 plugs into this single loop.
# The loop structure never changes; only the modules around it grow.

class FullAgent:
    """
    s08: The complete agent.
    All 7 mechanisms coexist around ONE while-loop.
    """

    def __init__(self, auto_approve: bool = True):
        # s02: Virtual file system + tool dispatch
        self.fs = VirtualFS()
        self.tool_map = build_tool_map(self.fs)

        # s03: Permission gate
        self.permissions = PermissionGate(auto_approve=auto_approve)

        # s04: Todo manager
        self.todo = TodoManager()

        # s05: Subagent manager
        self.subagents = SubagentManager(self.fs)

        # s06: Context compactor
        self.compactor = ContextCompactor()

        # s07: Error recovery
        self.error_handler = ErrorRecovery()

        # s01: Core context
        self.context: Dict[str, Any] = {
            "history": deque(maxlen=100),
            "budget_used": 0,
        }

        # s01: Mock LLM
        self.llm = MockLLM()

        # State
        self.running = True
        self.step_count = 0

    # ------------------------------------------------------------------

    def _execute_one_tool(self, tool_call: dict) -> Optional[ToolResult]:
        """Execute a single tool call through the full pipeline."""
        tool_name = tool_call["tool"]
        params = tool_call.get("params", {})

        # --- s03: Permission check ---
        approved, reason = self.permissions.check(tool_name, params)
        if not approved:
            print(f"  [BLOCKED] {tool_name}: {reason}")
            return ToolResult(False, f"Blocked: {reason}", tool_name)

        tool_fn = self.tool_map.get(tool_name)
        if tool_fn is None:
            return ToolResult(False, f"Unknown tool: {tool_name}", tool_name)

        # --- s07: Execute with retry ---
        result = self.error_handler.execute_with_retry(
            tool_fn, params, tool_name, self.compactor, self.context
        )

        # Track in context
        self.context["history"].append(
            f"[{tool_name}] {'OK' if result.success else 'FAIL'}: {result.output[:80]}"
        )
        self.context["budget_used"] += len(result.output)

        return result

    def _process_plan(self, plan: dict):
        """s04: Execute a multi-step plan with todo tracking."""
        steps = plan.get("steps", [])
        parallel_tasks = plan.get("parallel", [])

        # Create todo items
        print("\n  [TODO] Plan created with {} steps:".format(len(steps)))
        for i, step in enumerate(steps, 1):
            item = self.todo.add_task(step)
            print(f"    {i}. [{item.id}] {step}")

        # s05: Spawn subagents for parallel tasks
        for task in parallel_tasks:
            self.subagents.spawn(task)

        # Execute sequential steps
        for step in steps:
            if step in parallel_tasks:
                continue  # handled by subagent
            # Parse the step through LLM to get tool calls
            sub_plan = self.llm.parse(step)
            if sub_plan.get("tool"):
                print(f"\n  [{self.step_count}] Executing: {step}")
                result = self._execute_one_tool(sub_plan)
                if result and result.success:
                    self.todo.mark_done_by_hint(step)
                self.step_count += 1

        # s05: Collect subagent results
        sub_results = self.subagents.collect()
        for sr in sub_results:
            self.context["history"].append(
                f"[subagent-{self.subagents.next_id - 1}] {sr.output[:80]}"
            )
            self.todo.mark_done_by_hint(sr.task)

    # ------------------------------------------------------------------

    def run(self, queries: List[str]):
        """
        s01: THE agent loop.
        This is the only loop in the entire agent.
        """
        print("=" * 60)
        print("  s08 FULL AGENT --- 'Many mechanisms, one loop.'")
        print("=" * 60)
        print(f"  s01 Agent Loop       | s05 Subagents")
        print(f"  s02 Multi-Tool       | s06 Compaction")
        print(f"  s03 Permissions      | s07 Error Recovery")
        print(f"  s04 Todo Write       |")
        print("=" * 60)
        print(f"  Mock file-system ready: {len(self.fs.files)} files")
        print(f"  Auto-approve: {self.permissions.auto_approve}")
        print()

        # --------------------------------------------------------------
        # === THE ONE LOOP ===  (s01)
        # --------------------------------------------------------------
        for query in queries:
            if not self.running:
                break

            self.step_count += 1

            # --- s06: Check context budget BEFORE processing ---
            if self.compactor.needs_compaction(self.context):
                layer = (
                    CompactionLayers.BUDGET
                    if self.context["budget_used"] > self.compactor.hard_limit * 50
                    else CompactionLayers.MICRO
                )
                self.compactor.compact(self.context, layer)

            # --- s04: Nag about stale todo items ---
            nag = self.todo.nag_if_stale()
            if nag:
                print(f"\n{nag}")

            # --- Display step ---
            print(f"{'─' * 58}")
            print(f"  STEP {self.step_count} | Query: {query[:70]}")
            print(f"{'─' * 58}")

            # --- Parse query through LLM ---
            plan = self.llm.parse(query)

            # --- Route: subagent, plan, or single tool ---
            if plan.get("subagent"):
                # s05: Delegate to subagent
                self.subagents.spawn(plan["task"])
                # Collect immediately for demo
                results = self.subagents.collect()
                for sr in results:
                    print(f"  [SUBAGENT RESULT] {sr.output[:100]}")

            elif plan.get("plan"):
                # s04+s05: Multi-step plan
                self._process_plan(plan)

            else:
                # s02+s03+s07: Single tool execution
                result = self._execute_one_tool(plan)
                if result:
                    status = "OK" if result.success else "FAIL"
                    print(f"  [{status}] {result.tool_name}: {result.output[:100]}")

            # --- Display status after each step ---
            print(f"\n  {self.todo.summary()}")
            print(f"  Context: {len(self.context['history'])} entries, "
                  f"budget ~{self.context['budget_used']} tokens")

        # --- End of loop ---
        print(f"\n{'=' * 60}")
        print(f"  AGENT FINISHED")
        print(f"  Total steps: {self.step_count}")
        print(f"  Permission blocks: {self.permissions.block_count}")
        print(f"  Compactions: {self.compactor.compaction_count}")
        print(f"  Retries: {self.error_handler.stats.total_retries}")
        print(f"  {self.todo.summary()}")
        print(f"{'=' * 60}")


# ============================================================================
# ===  DEMO: Main Entry Point  ==============================================
# ============================================================================

def main():
    """Run the full agent demo with queries that exercise every mechanism."""

    # A sequence of queries demonstrating all 7 mechanisms
    demo_queries = [
        # --- s02: Multi-Tool demonstrations ---
        "read demo/file1.txt",                     # read_file
        "write demo/hello.txt Agent says hello!",  # write_file
        "read demo/hello.txt",                     # verify write
        "edit demo/config.json 8080 -> 9090",      # edit_file
        "read demo/config.json",                   # verify edit
        "find *.py",                               # glob
        "bash echo Build v1.0 complete",           # bash (safe)

        # --- s03: Permission pipeline ---
        "bash rm -rf /important/data",             # deny-list block
        "bash chmod 777 all_the_things",           # destructive pattern

        # --- s04 + s05: Complex task with todo + subagents ---
        "build a calculator app",                  # triggers plan + todo

        # --- s07: Error recovery ---
        "bash exit 1 --error test",                # deliberate failure -> retry
        "bash fail --error test",                  # another failure

        # --- s05: Explicit subagent ---
        "subagent analyze demo/file1.txt",         # subagent for analysis
        "analyze the config file demo/config.json", # implicit subagent

        # --- s06: Trigger compaction ---
        "read demo/huge.log",                      # large file read
        "bash echo 1", "bash echo 2", "bash echo 3",
        "bash echo 4", "bash echo 5", "bash echo 6",
        "bash echo 7", "bash echo 8", "bash echo 9",
        "bash echo 10", "bash echo 11", "bash echo 12",
        "bash echo 13", "bash echo 14", "bash echo 15",
        # These will fill the context and trigger compaction

        # --- Final: another complex task ---
        "build a web server",
    ]

    agent = FullAgent(auto_approve=True)
    agent.run(demo_queries)


if __name__ == "__main__":
    main()
