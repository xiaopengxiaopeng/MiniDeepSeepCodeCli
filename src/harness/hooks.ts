import { ToolCall } from "../api-client.js";

export type HookEvent = "PreToolUse" | "PostToolUse" | "Stop" | "UserPromptSubmit";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type HookCallback = (...args: any[]) => string | null | void;

const hooks: Record<HookEvent, HookCallback[]> = {
  PreToolUse: [],
  PostToolUse: [],
  Stop: [],
  UserPromptSubmit: [],
};

export function registerHook(event: HookEvent, callback: HookCallback): void {
  hooks[event].push(callback);
}

export function triggerHooks(event: HookEvent, ...args: unknown[]): string | null {
  for (const callback of hooks[event]) {
    const result = callback(...args);
    if (result !== null && result !== undefined) {
      return result as string;
    }
  }
  return null;
}

// ── Built-in hooks ──

const DENY_LIST = [
  "rm -rf /",
  "sudo",
  "shutdown",
  "reboot",
  "mkfs",
  "dd if=",
  "> /dev/sda",
  "format c:",
  "del /f /s c:",
];

const DESTRUCTIVE_PATTERNS = [
  "rm ",
  "> /etc/",
  "chmod 777",
  "chmod -R 777",
];

function permissionHook(block: ToolCall): string | null {
  if (block.function.name === "bash") {
    let command = "";
    try {
      const args = JSON.parse(block.function.arguments);
      command = args.command || "";
    } catch {
      return null;
    }
    const lowerCmd = command.toLowerCase();

    for (const pattern of DENY_LIST) {
      if (lowerCmd.includes(pattern.toLowerCase())) {
        console.log(`\n[permission] BLOCKED: '${pattern}' is on deny list`);
        return `Permission denied: '${pattern}' is on the deny list`;
      }
    }

    for (const pattern of DESTRUCTIVE_PATTERNS) {
      if (lowerCmd.includes(pattern.toLowerCase())) {
        console.log(`\n[permission] Destructive command detected:`);
        console.log(`  ${command}`);
        // In non-interactive mode, block; interactive would ask
        return `Permission required: this appears to be a destructive command. Use --allow-dangerous flag or confirm manually.`;
      }
    }
  }

  if (block.function.name === "write_file" || block.function.name === "edit_file") {
    let filePath = "";
    try {
      const args = JSON.parse(block.function.arguments);
      filePath = args.path || "";
    } catch {
      return null;
    }
    // Check path safety is handled in the tool itself
  }

  return null;
}

function logHook(block: ToolCall): null {
  console.log(`\n[Hook] ${block.function.name}`);
  return null;
}

function summaryHook(messages: unknown[]): null {
  let toolCount = 0;
  for (const msg of messages) {
    if (
      typeof msg === "object" &&
      msg !== null &&
      "role" in msg &&
      (msg as { role: string }).role === "tool"
    ) {
      toolCount++;
    }
  }
  console.log(`[Hook] Stop: session used ${toolCount} tool results`);
  return null;
}

// Register built-in hooks
registerHook("PreToolUse", permissionHook);
registerHook("PreToolUse", logHook);
registerHook("Stop", summaryHook as HookCallback);
