import * as fs from "fs";
import { safePath } from "../harness/path-safety.js";

export function runEditFile(
  filePath: string,
  oldString: string,
  newString: string,
  replaceAll: boolean = false,
): string {
  try {
    const resolved = safePath(filePath);
    if (!fs.existsSync(resolved)) {
      return `Error: File not found: ${filePath}`;
    }

    const content = fs.readFileSync(resolved, "utf-8");

    const count = content.split(oldString).length - 1;
    if (count === 0) {
      return `Error: text not found in ${filePath}`;
    }

    if (!replaceAll && count > 1) {
      return `Error: Found ${count} matches for oldString. Provide more surrounding lines or use replaceAll.`;
    }

    const newContent = replaceAll
      ? content.split(oldString).join(newString)
      : content.replace(oldString, newString);

    fs.writeFileSync(resolved, newContent, "utf-8");
    return `Edited ${filePath} (${replaceAll ? count : 1} replacement(s))`;
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : String(error);
    return `Error: ${msg}`;
  }
}
