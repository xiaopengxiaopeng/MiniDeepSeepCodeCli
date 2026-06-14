import * as child_process from "child_process";
import { getWorkDir } from "../harness/path-safety.js";

export function runBash(command: string): string {
  const dangerous = [
    "rm -rf /",
    "sudo",
    "shutdown",
    "reboot",
    "mkfs",
    "dd if=",
    "> /dev/sda",
    "format c:",
    "del /f /s",
  ];

  for (const pattern of dangerous) {
    if (command.toLowerCase().includes(pattern.toLowerCase())) {
      return `Error: Dangerous command blocked ('${pattern}')`;
    }
  }

  try {
    const result = child_process.spawnSync(command, {
      shell: true,
      cwd: getWorkDir(),
      timeout: 120000,
      encoding: "utf-8",
      maxBuffer: 50 * 1024 * 1024,
    });

    const output = (result.stdout || "") + (result.stderr || "");
    const trimmed = output.trim();
    if (!trimmed) return "(no output)";
    return trimmed.slice(0, 50000);
  } catch (error: unknown) {
    const msg = error instanceof Error ? error.message : String(error);
    if (msg.includes("timeout")) return "Error: Timeout (120s)";
    return `Error: ${msg}`;
  }
}
