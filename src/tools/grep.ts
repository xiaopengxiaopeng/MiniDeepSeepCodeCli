import * as fs from "fs";
import * as path from "path";
import { safePath, getWorkDir } from "../harness/path-safety.js";

export function runGrep(
  pattern: string,
  include?: string,
  filePath?: string,
): string {
  try {
    const workdir = getWorkDir();

    if (filePath) {
      // Grep in a single file
      const resolved = safePath(filePath);
      if (!fs.existsSync(resolved)) {
        return `Error: File not found: ${filePath}`;
      }
      if (fs.statSync(resolved).isDirectory()) {
        return `Error: ${filePath} is a directory`;
      }
      const content = fs.readFileSync(resolved, "utf-8");
      const lines = content.split("\n");
      const regex = safeRegex(pattern);
      const results: string[] = [];

      for (let i = 0; i < lines.length; i++) {
        if (regex.test(lines[i])) {
          results.push(`${i + 1}: ${lines[i].substring(0, 500)}`);
        }
      }

      if (results.length === 0) return "(no matches)";
      return results.join("\n");
    }

    // Grep in directory
    const matches: string[] = [];
    const files = findFiles(workdir, include);

    for (const file of files) {
      try {
        const content = fs.readFileSync(file, "utf-8");
        const lines = content.split("\n");
        const regex = safeRegex(pattern);

        for (let i = 0; i < lines.length; i++) {
          if (regex.test(lines[i])) {
            const relative = path.relative(workdir, file).replace(/\\/g, "/");
            matches.push(`${relative}:${i + 1}: ${lines[i].substring(0, 500)}`);
          }
        }
      } catch {
        // skip unreadable files
      }
    }

    if (matches.length === 0) return "(no matches)";
    return matches.slice(0, 500).join("\n");
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : String(error);
    return `Error: ${msg}`;
  }
}

function safeRegex(pattern: string): RegExp {
  try {
    return new RegExp(pattern, "gi");
  } catch {
    // Escape and try again
    const escaped = pattern.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return new RegExp(escaped, "gi");
  }
}

function findFiles(rootDir: string, includePattern?: string): string[] {
  const entries: string[] = [];
  const skipDirs = new Set(["node_modules", ".git", "dist", ".transcripts", ".tool_outputs", ".tasks"]);
  const MAX_FILES = 20000;

  function walk(dir: string) {
    if (entries.length >= MAX_FILES) return;
    try {
      const items = fs.readdirSync(dir);
      for (const item of items) {
        if (entries.length >= MAX_FILES) return;
        const fullPath = path.join(dir, item);
        try {
          const stat = fs.statSync(fullPath);
          if (stat.isDirectory()) {
            if (!skipDirs.has(item) && !item.startsWith(".")) {
              walk(fullPath);
            }
          } else if (stat.isFile()) {
            if (includePattern) {
              const regex = new RegExp(
                includePattern.replace(/\*/g, ".*").replace(/\{/g, "(").replace(/\}/g, ")").replace(/,/g, "|"),
                "i",
              );
              if (regex.test(item) || regex.test(fullPath)) {
                entries.push(fullPath);
              }
            } else {
              entries.push(fullPath);
            }
          }
        } catch {
          // skip inaccessible
        }
      }
    } catch {
      // skip unreadable dirs
    }
  }

  walk(rootDir);
  return entries;
}
