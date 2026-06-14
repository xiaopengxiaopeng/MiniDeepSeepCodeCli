import { chatCompletion, hasToolCalls, extractToolCalls, toAssistantMessage, ChatMessage, ToolDefinition } from "../api-client.js";
import { assembleSystemPrompt } from "../harness/system-prompt.js";
import { runBash } from "./bash.js";
import { runReadFile } from "./read-file.js";
import { runWriteFile } from "./write-file.js";
import { runEditFile } from "./edit-file.js";
import { runGlob } from "./glob.js";
import { runGrep } from "./grep.js";
import { triggerHooks } from "../harness/hooks.js";

const SUB_SYSTEM_PROMPT = `You are a coding subagent. Complete the assigned task using the available tools, then return a concise final summary. Do not spawn more agents. Do not explain unnecessarily. Be thorough but concise in your final answer.`;

const SUB_TOOLS: ToolDefinition[] = [
  {
    type: "function",
    function: {
      name: "bash",
      description: "Run a shell command.",
      parameters: {
        type: "object",
        properties: { command: { type: "string", description: "The command to execute" } },
        required: ["command"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "read_file",
      description: "Read file contents.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string", description: "File path relative to workspace" },
          limit: { type: "integer", description: "Max lines to read" },
          offset: { type: "integer", description: "Line number to start from (0-indexed)" },
        },
        required: ["path"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "write_file",
      description: "Write content to a file.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string", description: "File path" },
          content: { type: "string", description: "Content to write" },
        },
        required: ["path", "content"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "edit_file",
      description: "Replace exact text in a file.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string" },
          old_string: { type: "string" },
          new_string: { type: "string" },
          replace_all: { type: "boolean", description: "Replace all occurrences" },
        },
        required: ["path", "old_string", "new_string"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "glob",
      description: "Find files matching a glob pattern.",
      parameters: {
        type: "object",
        properties: { pattern: { type: "string" } },
        required: ["pattern"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "grep",
      description: "Search file contents with regex.",
      parameters: {
        type: "object",
        properties: {
          pattern: { type: "string", description: "Regex pattern" },
          include: { type: "string", description: "File pattern filter (e.g. *.ts)" },
          path: { type: "string", description: "Specific file to search" },
        },
        required: ["pattern"],
      },
    },
  },
];

export async function spawnSubagent(description: string): Promise<string> {
  console.log(`\n[Subagent spawned]`);
  const messages: ChatMessage[] = [{ role: "user", content: description }];
  const maxTurns = 30;

  for (let turn = 0; turn < maxTurns; turn++) {
    const systemPrompt = SUB_SYSTEM_PROMPT;
    const response = await chatCompletion(
      messages,
      SUB_TOOLS,
      systemPrompt,
      8000,
    );

    messages.push(toAssistantMessage(response));

    if (!hasToolCalls(response)) break;

    const toolCalls = extractToolCalls(response);
    const toolResults: ChatMessage[] = [];

    for (const tc of toolCalls) {
      // Check permission hooks
      const blocked = triggerHooks("PreToolUse", tc);
      if (blocked) {
        toolResults.push({
          role: "tool",
          tool_call_id: tc.id,
          content: String(blocked),
        });
        continue;
      }

      let output = "";
      try {
        const args = JSON.parse(tc.function.arguments);
        switch (tc.function.name) {
          case "bash":
            output = runBash(args.command);
            break;
          case "read_file":
            output = runReadFile(args.path, args.limit, args.offset);
            break;
          case "write_file":
            output = runWriteFile(args.path, args.content);
            break;
          case "edit_file":
            output = runEditFile(args.path, args.old_string, args.new_string, args.replace_all);
            break;
          case "glob":
            output = await runGlob(args.pattern);
            break;
          case "grep":
            output = runGrep(args.pattern, args.include, args.path);
            break;
          default:
            output = `Unknown tool: ${tc.function.name}`;
        }
      } catch {
        output = "Error: Invalid tool arguments";
      }

      triggerHooks("PostToolUse", tc, output);
      console.log(`  [sub] ${tc.function.name}: ${output.substring(0, 100)}`);
      toolResults.push({
        role: "tool",
        tool_call_id: tc.id,
        content: output,
      });
    }

    messages.push(...toolResults);
  }

  // Extract final text
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === "assistant" && messages[i].content) {
      console.log(`[Subagent done]`);
      return messages[i].content!;
    }
  }

  return "Subagent finished without a text summary.";
}
