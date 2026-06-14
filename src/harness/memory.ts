import * as fs from "fs";
import * as path from "path";
import { getWorkDir } from "./path-safety.js";

const MEMORY_DIR = ".memories";

export interface MemoryEntry {
  id: string;
  content: string;
  timestamp: number;
  tags: string[];
}

let memories: MemoryEntry[] = [];

function getMemoryDir(): string {
  return path.join(getWorkDir(), MEMORY_DIR);
}

function getMemoryFile(): string {
  return path.join(getMemoryDir(), "memory.json");
}

export function loadMemories(): void {
  try {
    const filePath = getMemoryFile();
    if (fs.existsSync(filePath)) {
      const data = fs.readFileSync(filePath, "utf-8");
      memories = JSON.parse(data);
    }
  } catch {
    memories = [];
  }
}

export function saveMemories(): void {
  try {
    const dir = getMemoryDir();
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    fs.writeFileSync(getMemoryFile(), JSON.stringify(memories, null, 2), "utf-8");
  } catch {
    // silent fail
  }
}

export function addMemory(content: string, tags: string[] = []): MemoryEntry {
  const entry: MemoryEntry = {
    id: `mem_${Date.now()}_${Math.random().toString(36).substring(2, 8)}`,
    content,
    timestamp: Date.now(),
    tags,
  };
  memories.push(entry);
  saveMemories();
  return entry;
}

export function getRelevantMemories(query?: string, maxResults: number = 5): MemoryEntry[] {
  if (memories.length === 0) return [];

  if (!query) {
    return memories.slice(-maxResults).reverse();
  }

  // Simple keyword matching
  const keywords = query.toLowerCase().split(/\s+/);
  const scored = memories.map((m) => {
    const lowerContent = m.content.toLowerCase();
    const tagMatch = m.tags.some((t) => keywords.some((k) => t.toLowerCase().includes(k)));
    const contentMatch = keywords.filter((k) => lowerContent.includes(k)).length;
    const score = contentMatch * 10 + (tagMatch ? 5 : 0);
    return { entry: m, score };
  });

  return scored
    .filter((s) => s.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, maxResults)
    .map((s) => s.entry);
}

export function formatMemories(entries: MemoryEntry[]): string {
  if (entries.length === 0) return "";
  return entries
    .map((m) => `- [${m.tags.join(", ")}] ${m.content}`)
    .join("\n");
}

export function clearMemories(): void {
  memories = [];
  saveMemories();
}

// Initialize on load
loadMemories();
