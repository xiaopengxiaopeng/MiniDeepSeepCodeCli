import {
  chatCompletionStream,
  ChatMessage,
  isPromptTooLongError,
  isRateLimitError,
  isOverloadedError,
  MAX_TOKENS,
  ToolCall,
} from "./api-client.js";
import { TOOL_DEFINITIONS, executeToolCall } from "./tool-registry.js";
import { assembleSystemPrompt } from "./harness/system-prompt.js";
import { triggerHooks } from "./harness/hooks.js";
import {
  runCompactionPipeline,
  reactiveCompact,
} from "./harness/compaction.js";

const MAX_TURNS = 100;
const MAX_REACTIVE_RETRIES = 2;
const MAX_RETRIES = 3;
const BASE_DELAY_MS = 500;

let roundsSinceTodo = 0;

async function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function agentLoop(messages: ChatMessage[]): Promise<string> {
  let reactiveRetries = 0;

  for (let turn = 0; turn < MAX_TURNS; turn++) {
    // ── Todo nag reminder ──
    if (roundsSinceTodo >= 3 && messages.length > 0) {
      messages.push({
        role: "user",
        content: "<reminder>Update your todo list with todo_write. It's been several turns since your last update.</reminder>",
      });
      roundsSinceTodo = 0;
    }

    // ── Compaction pipeline ──
    messages = await runCompactionPipeline(messages as ChatMessage[]);

    // ── Assemble system prompt ──
    const systemPrompt = assembleSystemPrompt();

    // ── Call DeepSeek API with streaming + retries ──
    let streamResult: { content: string; toolCalls: ToolCall[] };
    let attempt = 0;

    while (true) {
      try {
        process.stdout.write("\n");
        streamResult = await chatCompletionStream(
          messages,
          TOOL_DEFINITIONS,
          systemPrompt,
          MAX_TOKENS,
        );
        reactiveRetries = 0;
        break;
      } catch (error: unknown) {
        // Reactive compact on context-length errors
        if (
          isPromptTooLongError(error) &&
          reactiveRetries < MAX_REACTIVE_RETRIES
        ) {
          console.log("  [reactive compact]");
          messages = await reactiveCompact(messages as ChatMessage[]);
          reactiveRetries++;
          continue;
        }

        // Rate limit retry
        if (isRateLimitError(error) && attempt < MAX_RETRIES) {
          const waitMs = BASE_DELAY_MS * Math.pow(2, attempt) + Math.random() * 500;
          console.log(`  [429] retry ${attempt + 1}/${MAX_RETRIES} after ${(waitMs / 1000).toFixed(1)}s`);
          await delay(waitMs);
          attempt++;
          continue;
        }

        // Overloaded retry
        if (isOverloadedError(error) && attempt < MAX_RETRIES) {
          const waitMs = BASE_DELAY_MS * Math.pow(2, attempt) + Math.random() * 500;
          console.log(`  [503] retry ${attempt + 1}/${MAX_RETRIES} after ${(waitMs / 1000).toFixed(1)}s`);
          await delay(waitMs);
          attempt++;
          continue;
        }

        throw error;
      }
    }

    // ── Append assistant message ──
    const hasTools = streamResult.toolCalls.length > 0;
    messages.push({
      role: "assistant",
      content: streamResult.content || null,
      tool_calls: hasTools ? streamResult.toolCalls : undefined,
    });

    // ── Check if done ──
    if (!hasTools) {
      triggerHooks("Stop", messages);
      return streamResult.content;
    }

    // ── Execute tool calls ──
    roundsSinceTodo++;
    const toolCalls = streamResult.toolCalls;
    const toolResults: ChatMessage[] = [];
    let calledCompact = false;

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

      // Display tool call
      const args = (() => {
        try { return JSON.parse(tc.function.arguments); } catch { return {}; }
      })();
      const argStr =
        tc.function.name === "bash"
          ? (args.command as string || "")
          : tc.function.name === "write_file" || tc.function.name === "edit_file"
            ? (args.path as string || "")
            : "";

      console.log(`\n[${tc.function.name}] ${argStr}`.trim());

      // Handle compact tool specially
      if (tc.function.name === "compact") {
        calledCompact = true;
        const msg: ChatMessage = {
          role: "tool",
          tool_call_id: tc.id,
          content: "Conversation history has been compacted.",
        };
        toolResults.push(msg);
        continue;
      }

      // Execute tool
      const output = await executeToolCall(tc);

      // Display truncated output
      const displayOutput = output.length > 400 ? output.substring(0, 400) + "..." : output;
      if (displayOutput) {
        console.log(displayOutput.split("\n").slice(0, 5).join("\n"));
      }

      // Trigger post-tool hooks
      triggerHooks("PostToolUse", tc, output);

      // Reset nag on todo_write
      if (tc.function.name === "todo_write") {
        roundsSinceTodo = 0;
      }

      toolResults.push({
        role: "tool",
        tool_call_id: tc.id,
        content: output,
      });
    }

    // Append tool results
    messages.push(...toolResults);

    if (calledCompact) {
      // After compact, replace conversation with compacted version
      const { compactHistory } = await import("./harness/compaction.js");
      messages = await compactHistory(messages as ChatMessage[]);
    }
  }

  return "Maximum turns reached. Please continue with a new query.";
}
