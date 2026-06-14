#!/usr/bin/env node

import * as readline from "readline";
import * as fs from "fs";
import * as path from "path";
import * as dotenv from "dotenv";
import { agentLoop } from "./agent.js";
import { ChatMessage } from "./api-client.js";
import { triggerHooks } from "./harness/hooks.js";
import { setWorkDir } from "./harness/path-safety.js";
import { formatMemories, getRelevantMemories } from "./harness/memory.js";
import { setMemory } from "./harness/system-prompt.js";

// Load .env
const envPath = path.join(process.cwd(), ".env");
if (fs.existsSync(envPath)) {
  dotenv.config({ path: envPath });
} else {
  dotenv.config();
}

// ANSI color helpers
const CYAN = "\x1b[36m";
const YELLOW = "\x1b[33m";
const GREEN = "\x1b[32m";
const GRAY = "\x1b[90m";
const RESET = "\x1b[0m";
const BOLD = "\x1b[1m";

function printBanner(): void {
  console.log(`
${CYAN}${BOLD}╔══════════════════════════════════════════╗
║       DeepSeek Code CLI v1.0.0          ║
║   AI-powered coding agent in your CLI   ║
╚══════════════════════════════════════════╝${RESET}
`);
  console.log(`${GRAY}Model: ${process.env.DEEPSEEK_MODEL || "deepseek-chat"}${RESET}`);
  console.log(`${GRAY}Workspace: ${process.cwd()}${RESET}`);
  console.log(`${GRAY}Type /help for commands, /quit to exit${RESET}\n`);
}

function printHelp(): void {
  console.log(`
${BOLD}Commands:${RESET}
  ${CYAN}/help${RESET}      - Show this help
  ${CYAN}/quit${RESET}      - Exit the CLI
  ${CYAN}/exit${RESET}      - Exit the CLI
  ${CYAN}/clear${RESET}     - Clear conversation history
  ${CYAN}/memory${RESET}    - Show relevant memories
  ${CYAN}/compact${RESET}   - Manually compact conversation
  ${CYAN}/model${RESET}     - Show current model info
  ${CYAN}/workspace${RESET} - Show workspace directory

${BOLD}Features:${RESET}
  - Multi-tool agent loop (bash, read, write, edit, glob, grep)
  - Permission pipeline (deny list, destructive warnings)
  - Todo task planning and tracking
  - Subagent spawning for complex subtasks
  - Context compaction (snip, micro, budget, auto)
  - Memory persistence and recall
  - Error recovery with retries

${BOLD}Tips:${RESET}
  - Use ${YELLOW}todo_write${RESET} to plan before multi-step tasks
  - Subagents via ${YELLOW}task${RESET} tool for isolated complex work
  - The agent will auto-compact when context is full
`);
}

async function processCommand(
  input: string,
  history: ChatMessage[],
): Promise<{ shouldContinue: boolean; shouldReset: boolean }> {
  const trimmed = input.trim();

  if (trimmed.toLowerCase() === "/quit" || trimmed.toLowerCase() === "/exit") {
    return { shouldContinue: false, shouldReset: false };
  }

  if (trimmed === "/help") {
    printHelp();
    return { shouldContinue: true, shouldReset: false };
  }

  if (trimmed === "/clear") {
    return { shouldContinue: true, shouldReset: true };
  }

  if (trimmed === "/memory") {
    const memories = getRelevantMemories();
    if (memories.length > 0) {
      console.log(`\n${YELLOW}Recent Memories:${RESET}`);
      console.log(formatMemories(memories));
    } else {
      console.log(`${GRAY}No memories stored yet.${RESET}`);
    }
    return { shouldContinue: true, shouldReset: false };
  }

  if (trimmed === "/model") {
    console.log(`\n${GRAY}Model: ${process.env.DEEPSEEK_MODEL || "deepseek-chat"}${RESET}`);
    console.log(`${GRAY}API URL: ${process.env.DEEPSEEK_BASE_URL || "https://api.deepseek.com/v1"}${RESET}`);
    console.log(`${GRAY}Max tokens: ${process.env.MAX_TOKENS || "8000"}${RESET}`);
    return { shouldContinue: true, shouldReset: false };
  }

  if (trimmed === "/workspace") {
    console.log(`\n${GRAY}Workspace: ${process.cwd()}${RESET}`);
    return { shouldContinue: true, shouldReset: false };
  }

  // Empty input
  if (!trimmed) {
    return { shouldContinue: true, shouldReset: false };
  }

  // Normal user query
  triggerHooks("UserPromptSubmit", trimmed);

  // Inject relevant memories
  const relevantMemories = getRelevantMemories(trimmed);
  if (relevantMemories.length > 0) {
    const memoryText = formatMemories(relevantMemories);
    setMemory(memoryText);
  }

  history.push({ role: "user", content: trimmed });

  try {
    const result = await agentLoop(history);
    // Print final response
    if (result && result !== "Maximum turns reached. Please continue with a new query.") {
      console.log(`\n${result}`);
    } else if (result) {
      console.log(`\n${YELLOW}${result}${RESET}`);
    }
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : String(error);
    console.log(`\n${YELLOW}Error: ${msg}${RESET}`);
    console.log(`${GRAY}You can continue - the conversation history is preserved.${RESET}`);
  }

  return { shouldContinue: true, shouldReset: false };
}

async function main(): Promise<void> {
  // Parse args
  const args = process.argv.slice(2);
  const workdirArg = args.find((a) => a.startsWith("--workdir="));
  if (workdirArg) {
    const dir = workdirArg.split("=")[1];
    if (fs.existsSync(dir)) {
      setWorkDir(dir);
      process.chdir(dir);
    }
  }

  // Check API key
  if (!process.env.DEEPSEEK_API_KEY) {
    console.log(
      `${YELLOW}Warning: DEEPSEEK_API_KEY not set.${RESET}`,
    );
    console.log(
      `Create a .env file with: ${CYAN}DEEPSEEK_API_KEY=sk-your-key${RESET}`,
    );
  }

  printBanner();

  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    prompt: `${CYAN}> ${RESET}`,
    terminal: true,
  });

  let history: ChatMessage[] = [];

  // Handle non-interactive: single query from args
  const queryArg = args.find((a) => !a.startsWith("--"));
  if (queryArg) {
    history.push({ role: "user", content: queryArg });
    try {
      const result = await agentLoop(history);
      console.log(result);
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : String(error);
      console.log(`Error: ${msg}`);
    }
    process.exit(0);
  }

  // Interactive mode
  rl.prompt();

  rl.on("line", async (line) => {
    const result = await processCommand(line, history);
    if (result.shouldReset) {
      history = [];
      console.log(`${GRAY}Conversation history cleared.${RESET}`);
    }
    if (!result.shouldContinue) {
      console.log(`${GRAY}Goodbye!${RESET}`);
      rl.close();
      return;
    }
    rl.prompt();
  });

  rl.on("close", () => {
    console.log(`\n${GRAY}Goodbye!${RESET}`);
    process.exit(0);
  });
}

main().catch((error) => {
  console.error(`Fatal error: ${error.message}`);
  process.exit(1);
});
