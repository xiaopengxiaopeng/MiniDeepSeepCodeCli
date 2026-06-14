import * as fs from "fs";
import * as path from "path";
import { client, MODEL, MAX_TOKENS, isPromptTooLongError, ChatMessage } from "../api-client.js";
import { getWorkDir } from "./path-safety.js";

const CONTEXT_LIMIT = parseInt(process.env.CONTEXT_LIMIT || "50000", 10);
const KEEP_RECENT = 3;
const PERSIST_THRESHOLD = 30000;

// ── Layer 3: toolResultBudget ──
export function toolResultBudget(messages: ChatMessage[]): ChatMessage[] {
  if (messages.length === 0) return messages;

  const last = messages[messages.length - 1];
  if (last.role !== "tool") return messages;

  const content = last.content || "";
  if (content.length <= PERSIST_THRESHOLD) return messages;

  const transcriptDir = path.join(getWorkDir(), ".tool_outputs");
  if (!fs.existsSync(transcriptDir)) {
    fs.mkdirSync(transcriptDir, { recursive: true });
  }

  const toolCallId = last.tool_call_id || "unknown";
  const outputPath = path.join(transcriptDir, `${toolCallId}.txt`);
  if (!fs.existsSync(outputPath)) {
    fs.writeFileSync(outputPath, content, "utf-8");
  }

  last.content = `<persisted-output>
Full output: ${outputPath}
Preview:
${content.substring(0, 2000)}
</persisted-output>`;

  return messages;
}

// ── Layer 1: snip_compact ──
export function snipCompact(messages: ChatMessage[], maxMessages: number = 50): ChatMessage[] {
  if (messages.length <= maxMessages) return messages;

  const headEnd = 3;
  const tailStart = messages.length - (maxMessages - 3);

  if (headEnd >= tailStart) return messages;

  // Preserve tool call/tool result pairs
  let adjustedHeadEnd = headEnd;
  if (adjustedHeadEnd > 0 && messages[adjustedHeadEnd - 1]?.role === "assistant" && messages[adjustedHeadEnd - 1]?.tool_calls) {
    while (adjustedHeadEnd < messages.length && messages[adjustedHeadEnd]?.role === "tool") {
      adjustedHeadEnd++;
    }
  }

  let adjustedTailStart = tailStart;
  if (adjustedTailStart > 0 && adjustedTailStart < messages.length && messages[adjustedTailStart]?.role === "tool") {
    adjustedTailStart--;
  }

  if (adjustedHeadEnd >= adjustedTailStart) return messages;

  const snipped = adjustedTailStart - adjustedHeadEnd;
  return [
    ...messages.slice(0, adjustedHeadEnd),
    { role: "user", content: `[snipped ${snipped} messages]` },
    ...messages.slice(adjustedTailStart),
  ];
}

// ── Layer 2: micro_compact ──
export function microCompact(messages: ChatMessage[]): ChatMessage[] {
  const toolIndices: number[] = [];
  for (let i = 0; i < messages.length; i++) {
    if (messages[i].role === "tool") toolIndices.push(i);
  }

  if (toolIndices.length <= KEEP_RECENT) return messages;

  for (let i = 0; i < toolIndices.length - KEEP_RECENT; i++) {
    const idx = toolIndices[i];
    if ((messages[idx].content?.length || 0) > 120) {
      messages[idx] = {
        ...messages[idx],
        content: "[Earlier tool result compacted. Re-run if needed.]",
      };
    }
  }

  return messages;
}

// ── Estimate total size ──
export function estimateSize(messages: ChatMessage[]): number {
  return JSON.stringify(messages).length;
}

// ── Layer 4: auto_compact ──
async function summarizeHistory(messages: ChatMessage[]): Promise<string> {
  const conversation = JSON.stringify(messages).substring(0, 80000);
  const prompt = `Summarize this coding-agent conversation so work can continue.
Preserve:
1. Current goal and task
2. Key findings and decisions
3. Files that were read, changed, or created
4. Remaining work to be done
5. User constraints and preferences

Conversation:
${conversation}

Provide a compact but concrete summary.`;

  try {
    const response = await client.chat.completions.create({
      model: MODEL,
      messages: [{ role: "user", content: prompt }],
      max_tokens: 2000,
      temperature: 0,
    });
    return response.choices[0]?.message?.content || "(empty summary)";
  } catch {
    return "(summarization failed)";
  }
}

function writeTranscript(messages: ChatMessage[]): string {
  const transcriptDir = path.join(getWorkDir(), ".transcripts");
  if (!fs.existsSync(transcriptDir)) {
    fs.mkdirSync(transcriptDir, { recursive: true });
  }
  const filePath = path.join(transcriptDir, `transcript_${Date.now()}.json`);
  fs.writeFileSync(filePath, JSON.stringify(messages, null, 2), "utf-8");
  return filePath;
}

export async function compactHistory(messages: ChatMessage[]): Promise<ChatMessage[]> {
  const transcript = writeTranscript(messages);
  console.log(`  [compact] transcript saved: ${transcript}`);
  const summary = await summarizeHistory(messages);
  return [{ role: "user", content: `[Compacted]\n\n${summary}` }];
}

export async function reactiveCompact(messages: ChatMessage[]): Promise<ChatMessage[]> {
  const transcript = writeTranscript(messages);
  console.log(`  [reactive compact] transcript saved: ${transcript}`);

  let summary: string;
  try {
    summary = await summarizeHistory(messages);
  } catch {
    summary = "Earlier conversation was trimmed after a context-length error.";
  }

  const tailStart = Math.max(0, messages.length - 5);
  return [
    { role: "user", content: `[Reactive compact]\n\n${summary}` },
    ...messages.slice(tailStart),
  ];
}

// ── Run the full compaction pipeline ──
export async function runCompactionPipeline(
  messages: ChatMessage[],
): Promise<ChatMessage[]> {
  // L3 → L1 → L2 → L4 (cheap first, expensive last)
  messages = toolResultBudget(messages);
  messages = snipCompact(messages);
  messages = microCompact(messages);

  if (estimateSize(messages) > CONTEXT_LIMIT) {
    console.log("  [auto compact]");
    messages = await compactHistory(messages);
  }

  return messages;
}

export function isContextTooLongError(error: unknown): boolean {
  return isPromptTooLongError(error);
}
