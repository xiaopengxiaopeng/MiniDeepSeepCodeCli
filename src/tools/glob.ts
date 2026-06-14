import { glob as globLib } from "glob";
import { getWorkDir } from "../harness/path-safety.js";
import * as path from "path";

export async function runGlob(pattern: string): Promise<string> {
  try {
    const workdir = getWorkDir();
    const normalizedPattern = pattern.replace(/\\/g, "/");

    const matches = await globLib(normalizedPattern, {
      cwd: workdir,
      nodir: false,
      dot: true,
      ignore: ["node_modules/**", ".git/**"],
    });

    if (matches.length === 0) return "(no matches)";

    // Sort by modification time
    const withStats = await Promise.all(
      matches.map(async (m) => {
        const fullPath = path.join(workdir, m);
        try {
          const stat = await import("fs").then((fs) =>
            fs.promises.stat(fullPath),
          );
          return { path: m, mtime: stat.mtimeMs };
        } catch {
          return { path: m, mtime: 0 };
        }
      }),
    );

    withStats.sort((a, b) => b.mtime - a.mtime);
    return withStats.map((m) => m.path).join("\n");
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : String(error);
    return `Error: ${msg}`;
  }
}
