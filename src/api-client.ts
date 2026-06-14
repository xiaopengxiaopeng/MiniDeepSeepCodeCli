import OpenAI from "openai";
import * as dotenv from "dotenv";

dotenv.config();

export interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string | null;
  tool_calls?: ToolCall[];
  tool_call_id?: string;
  name?: string;
}

export interface ToolCall {
  id: string;
  type: "function";
  function: {
    name: string;
    arguments: string;
  };
}

export interface ToolDefinition {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: {
      type: "object";
      properties: Record<string, unknown>;
      required: string[];
    };
  };
}

export interface ToolResult {
  tool_call_id: string;
  role: "tool";
  content: string;
}

const BASE_URL = process.env.DEEPSEEK_BASE_URL || "https://api.deepseek.com/v1";
const API_KEY = process.env.DEEPSEEK_API_KEY || "";
export const MODEL = process.env.DEEPSEEK_MODEL || "deepseek-chat";
export const MAX_TOKENS = parseInt(process.env.MAX_TOKENS || "8000", 10);
export const CONTEXT_LIMIT = parseInt(process.env.CONTEXT_LIMIT || "50000", 10);

export const client = new OpenAI({
  baseURL: BASE_URL,
  apiKey: API_KEY,
});

function buildMessages(
  messages: ChatMessage[],
  systemPrompt: string,
): OpenAI.Chat.Completions.ChatCompletionMessageParam[] {
  return [
    { role: "system", content: systemPrompt },
    ...messages.map((m) => {
      if (m.role === "tool") {
        return {
          role: "tool" as const,
          tool_call_id: m.tool_call_id!,
          content: m.content || "",
        };
      }
      if (m.role === "assistant" && m.tool_calls) {
        return {
          role: "assistant" as const,
          content: m.content,
          tool_calls: m.tool_calls.map((tc) => ({
            id: tc.id,
            type: "function" as const,
            function: {
              name: tc.function.name,
              arguments: tc.function.arguments,
            },
          })),
        };
      }
      return {
        role: m.role as "user" | "assistant",
        content: m.content || "",
      };
    }) as OpenAI.Chat.Completions.ChatCompletionMessageParam[],
  ];
}

export async function chatCompletion(
  messages: ChatMessage[],
  tools: ToolDefinition[],
  systemPrompt: string,
  maxTokens: number = MAX_TOKENS,
): Promise<OpenAI.Chat.Completions.ChatCompletion> {
  return client.chat.completions.create({
    model: MODEL,
    messages: buildMessages(messages, systemPrompt),
    tools: tools as OpenAI.Chat.Completions.ChatCompletionTool[],
    max_tokens: maxTokens,
    temperature: 0,
  });
}

export interface StreamedResponse {
  content: string;
  toolCalls: ToolCall[];
}

export async function chatCompletionStream(
  messages: ChatMessage[],
  tools: ToolDefinition[],
  systemPrompt: string,
  maxTokens: number = MAX_TOKENS,
): Promise<StreamedResponse> {
  const stream = await client.chat.completions.create({
    model: MODEL,
    messages: buildMessages(messages, systemPrompt),
    tools: tools as OpenAI.Chat.Completions.ChatCompletionTool[],
    max_tokens: maxTokens,
    temperature: 0,
    stream: true,
  });

  let content = "";
  const toolCallMap = new Map<number, { id: string; name: string; arguments: string }>();

  for await (const chunk of stream) {
    const delta = chunk.choices[0]?.delta;
    if (!delta) continue;

    if (delta.content) {
      content += delta.content;
      process.stdout.write(delta.content);
    }

    if (delta.tool_calls) {
      for (const tc of delta.tool_calls) {
        const idx = tc.index;
        if (!toolCallMap.has(idx)) {
          toolCallMap.set(idx, {
            id: tc.id || "",
            name: tc.function?.name || "",
            arguments: "",
          });
        }
        const entry = toolCallMap.get(idx)!;
        if (tc.id) entry.id = tc.id;
        if (tc.function?.name) entry.name = tc.function.name;
        if (tc.function?.arguments) entry.arguments += tc.function.arguments;
      }
    }
  }

  const toolCalls: ToolCall[] = Array.from(toolCallMap.values())
    .sort((a, b) => {
      const idxA = Array.from(toolCallMap.values()).indexOf(a);
      const idxB = Array.from(toolCallMap.values()).indexOf(b);
      return idxA - idxB;
    })
    .map((tc) => ({
      id: tc.id,
      type: "function" as const,
      function: {
        name: tc.name,
        arguments: tc.arguments,
      },
    }));

  return { content, toolCalls };
}

export function hasToolCalls(
  response: OpenAI.Chat.Completions.ChatCompletion,
): boolean {
  const choice = response.choices[0];
  return !!(
    choice &&
    choice.finish_reason === "tool_calls" &&
    choice.message.tool_calls &&
    choice.message.tool_calls.length > 0
  );
}

export function extractToolCalls(
  response: OpenAI.Chat.Completions.ChatCompletion,
): ToolCall[] {
  const choice = response.choices[0];
  if (!choice || !choice.message.tool_calls) return [];
  return choice.message.tool_calls.map((tc) => ({
    id: tc.id,
    type: "function" as const,
    function: {
      name: tc.function.name,
      arguments: tc.function.arguments,
    },
  }));
}

export function extractText(
  response: OpenAI.Chat.Completions.ChatCompletion,
): string {
  return response.choices[0]?.message?.content || "";
}

export function toAssistantMessage(
  response: OpenAI.Chat.Completions.ChatCompletion,
): ChatMessage {
  const choice = response.choices[0];
  const msg = choice.message;
  return {
    role: "assistant",
    content: msg.content,
    tool_calls: msg.tool_calls
      ? msg.tool_calls.map((tc) => ({
          id: tc.id,
          type: "function" as const,
          function: {
            name: tc.function.name,
            arguments: tc.function.arguments,
          },
        }))
      : undefined,
  };
}

export function isPromptTooLongError(error: unknown): boolean {
  const msg = String(error).toLowerCase();
  return (
    msg.includes("context length") ||
    msg.includes("too long") ||
    msg.includes("max context") ||
    msg.includes("token limit") ||
    msg.includes("4003") ||
    msg.includes("max_tokens")
  );
}

export function isRateLimitError(error: unknown): boolean {
  const msg = String(error).toLowerCase();
  return msg.includes("429") || msg.includes("rate limit");
}

export function isOverloadedError(error: unknown): boolean {
  const msg = String(error).toLowerCase();
  return msg.includes("503") || msg.includes("overloaded");
}
