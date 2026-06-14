import { ToolDefinition, ToolCall, ChatMessage } from "./api-client.js";
import { runBash } from "./tools/bash.js";
import { runReadFile } from "./tools/read-file.js";
import { runWriteFile } from "./tools/write-file.js";
import { runEditFile } from "./tools/edit-file.js";
import { runGlob } from "./tools/glob.js";
import { runGrep } from "./tools/grep.js";
import { runTodoWrite } from "./tools/todo-write.js";
import type { TodoItem } from "./tools/todo-write.js";
import { spawnSubagent } from "./tools/task.js";
import { compactHistory } from "./harness/compaction.js";
import type { ChatMessage as CMsg } from "./api-client.js";

export const TOOL_DEFINITIONS: ToolDefinition[] = [
  {
    type: "function",
    function: {
      name: "bash",
      description: "Run a shell command in the workspace directory.",
      parameters: {
        type: "object",
        properties: {
          command: { type: "string", description: "The shell command to execute" },
        },
        required: ["command"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "read_file",
      description: "Read a file from the workspace. Returns contents with line numbers.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string", description: "File path relative to workspace" },
          limit: { type: "integer", description: "Maximum lines to read" },
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
      description: "Write content to a file. Creates parent directories if needed.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string", description: "File path relative to workspace" },
          content: { type: "string", description: "Content to write to the file" },
        },
        required: ["path", "content"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "edit_file",
      description:
        "Replace exact text in a file. If old_string is not unique, the edit will fail - provide more surrounding context to make it unique. Use replace_all to replace all occurrences.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string", description: "File path" },
          old_string: { type: "string", description: "Exact text to find and replace" },
          new_string: { type: "string", description: "Replacement text" },
          replace_all: { type: "boolean", description: "Replace all occurrences (default false)" },
        },
        required: ["path", "old_string", "new_string"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "glob",
      description: "Find files matching a glob pattern. Returns matches sorted by modification time.",
      parameters: {
        type: "object",
        properties: {
          pattern: { type: "string", description: "Glob pattern (e.g. 'src/**/*.ts', '*.js')" },
        },
        required: ["pattern"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "grep",
      description: "Search file contents using regex patterns. Returns matching lines with file paths and line numbers.",
      parameters: {
        type: "object",
        properties: {
          pattern: { type: "string", description: "Regex pattern to search for" },
          include: { type: "string", description: "File pattern filter (e.g. '*.ts', '*.{js,ts}')" },
          path: { type: "string", description: "Specific file to search instead of directory" },
        },
        required: ["pattern"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "todo_write",
      description:
        "Create and manage a task list for your current session. Use before starting multi-step tasks. Update status as you progress. Statuses: pending, in_progress, completed.",
      parameters: {
        type: "object",
        properties: {
          todos: {
            type: "array",
            description: "JSON string of todo items array",
            items: {
              type: "object",
              properties: {
                content: { type: "string", description: "Task description" },
                status: {
                  type: "string",
                  enum: ["pending", "in_progress", "completed"],
                  description: "Task status",
                },
              },
              required: ["content", "status"],
            },
          },
        },
        required: ["todos"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "task",
      description:
        "Launch a subagent to handle a complex subtask with clean context. The subagent works independently and returns only its final conclusion.",
      parameters: {
        type: "object",
        properties: {
          description: { type: "string", description: "Detailed task description for the subagent" },
        },
        required: ["description"],
      },
    },
  },
  {
    type: "function",
    function: {
      name: "compact",
      description: "Compact/summarize the conversation history to free context space. Call when the conversation is getting long.",
      parameters: {
        type: "object",
        properties: {},
        required: [],
      },
    },
  },
];

export type ToolHandler = (args: Record<string, unknown>) => Promise<string> | string;

export const TOOL_HANDLERS: Record<string, ToolHandler> = {
  bash: (args) => runBash(args.command as string),
  read_file: (args) => runReadFile(args.path as string, args.limit as number | undefined, args.offset as number | undefined),
  write_file: (args) => runWriteFile(args.path as string, args.content as string),
  edit_file: (args) =>
    runEditFile(
      args.path as string,
      args.old_string as string,
      args.new_string as string,
      args.replace_all as boolean | undefined,
    ),
  glob: async (args) => runGlob(args.pattern as string),
  grep: (args) =>
    runGrep(
      args.pattern as string,
      args.include as string | undefined,
      args.path as string | undefined,
    ),
  todo_write: (args) => runTodoWrite(args.todos as string | TodoItem[]),
  task: async (args) => spawnSubagent(args.description as string),
  compact: async (_args, context?: ChatMessage[]) => {
    if (context) {
      await compactHistory(context);
    }
    return "Conversation history has been compacted. You can continue working with refreshed context.";
  },
};

export async function executeToolCall(
  toolCall: ToolCall,
): Promise<string> {
  const handler = TOOL_HANDLERS[toolCall.function.name];
  if (!handler) {
    return `Error: Unknown tool '${toolCall.function.name}'`;
  }

  try {
    const args = JSON.parse(toolCall.function.arguments);
    const result = await handler(args);
    return String(result);
  } catch {
    return "Error: Invalid tool call arguments";
  }
}
