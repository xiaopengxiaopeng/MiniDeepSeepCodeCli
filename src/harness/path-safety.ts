import * as path from "path";
import * as fs from "fs";

let _workdir: string = process.cwd();

export function getWorkDir(): string {
  return _workdir;
}

export function setWorkDir(dir: string): void {
  _workdir = dir;
}

export function safePath(relativePath: string): string {
  const workdir = getWorkDir();
  const resolved = path.resolve(workdir, relativePath);

  // Normalize to handle Windows paths
  const normalizedWorkdir = path.normalize(workdir).toLowerCase();
  const normalizedResolved = path.normalize(resolved).toLowerCase();

  if (!normalizedResolved.startsWith(normalizedWorkdir + path.sep) &&
      normalizedResolved !== normalizedWorkdir) {
    throw new Error(`Path escapes workspace: ${relativePath}`);
  }
  return resolved;
}

export function ensureParentDir(filePath: string): void {
  const dir = path.dirname(filePath);
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }
}
