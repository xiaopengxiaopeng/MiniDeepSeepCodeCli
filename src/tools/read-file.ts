import * as fs from "fs";
import { safePath } from "../harness/path-safety.js";

export function runReadFile(
  filePath: string,
  limit?: number,
  offset?: number,
): string {
  try {
    const resolved = safePath(filePath);
    if (!fs.existsSync(resolved)) {
      return `Error: File not found: ${filePath}`;
    }
    const stat = fs.statSync(resolved);
    if (stat.isDirectory()) {
      const entries = fs.readdirSync(resolved, { withFileTypes: true });
      return entries
        .map((e) => e.name + (e.isDirectory() ? "/" : ""))
        .join("\n");
    }

    let content = fs.readFileSync(resolved, "utf-8");
    const lines = content.split("\n");

    const start = offset || 0;
    let selected = lines.slice(start);

    if (limit && limit < selected.length) {
      const remaining = selected.length - limit;
      selected = [...selected.slice(0, limit), `... (${remaining} more lines)`];
    }

    return selected.join("\n");
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : String(error);
    return `Error: ${msg}`;
  }
}
