export interface TodoItem {
  content: string;
  status: "pending" | "in_progress" | "completed";
}

let currentTodos: TodoItem[] = [];

export function getCurrentTodos(): TodoItem[] {
  return currentTodos;
}

export function runTodoWrite(todos: TodoItem[] | string): string {
  try {
    let parsed: unknown;
    if (typeof todos === "string") {
      parsed = JSON.parse(todos);
    } else {
      parsed = todos;
    }

    if (!Array.isArray(parsed)) {
      return "Error: todos must be an array";
    }

    const items: TodoItem[] = [];
    for (let i = 0; i < parsed.length; i++) {
      const item = parsed[i] as Record<string, unknown>;
      if (!item || typeof item.content !== "string" || typeof item.status !== "string") {
        return `Error: todos[${i}] missing 'content' or 'status'`;
      }
      const status = item.status as string;
      if (!["pending", "in_progress", "completed"].includes(status)) {
        return `Error: todos[${i}] has invalid status '${status}'`;
      }
      items.push({
        content: item.content as string,
        status: status as TodoItem["status"],
      });
    }

    currentTodos = items;

    const lines: string[] = ["\n## Current Tasks"];
    for (const t of currentTodos) {
      const icon =
        t.status === "completed"
          ? "[x]"
          : t.status === "in_progress"
            ? "[>]"
            : "[ ]";
      lines.push(`  ${icon} ${t.content}`);
    }
    console.log(lines.join("\n"));
    return `Updated ${currentTodos.length} tasks`;
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : String(error);
    return `Error: ${msg}`;
  }
}
