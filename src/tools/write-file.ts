import * as fs from "fs";
import { safePath, ensureParentDir } from "../harness/path-safety.js";

export function runWriteFile(filePath: string, content: string): string {
  try {
    const resolved = safePath(filePath);
    ensureParentDir(resolved);
    fs.writeFileSync(resolved, content, "utf-8");
    return `Wrote ${content.length} bytes to ${filePath}`;
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : String(error);
    return `Error: ${msg}`;
  }
}
