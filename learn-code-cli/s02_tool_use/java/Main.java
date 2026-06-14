/*
 * s02: Tool Use — Dispatch Map Pattern
 * ====================================
 * Builds on s01's single bash tool. Adds read_file, write_file, edit_file, glob.
 * Introduces the TOOL_HANDLERS dispatch map (HashMap) — the loop never changes.
 *
 * Compile: javac Main.java
 * Run:     java Main
 */

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Main {

    // ── Colors ──────────────────────────────────────────────────────────────
    static final String RESET   = "\033[0m";
    static final String DIM     = "\033[2m";
    static final String BOLD    = "\033[1m";
    static final String CYAN    = "\033[36m";
    static final String GREEN   = "\033[32m";
    static final String YELLOW  = "\033[33m";
    static final String RED     = "\033[31m";
    static final String MAGENTA = "\033[35m";

    static String dim(String s)     { return DIM + s + RESET; }
    static String bold(String s)    { return BOLD + s + RESET; }
    static String cyan(String s)    { return CYAN + s + RESET; }
    static String green(String s)   { return GREEN + s + RESET; }
    static String yellow(String s)  { return YELLOW + s + RESET; }
    static String red(String s)     { return RED + s + RESET; }
    static String magenta(String s) { return MAGENTA + s + RESET; }

    // ── Configuration ───────────────────────────────────────────────────────
    static final Path WORKSPACE_DIR = Paths.get("demo_workspace").toAbsolutePath();
    static final int MAX_TURNS = 10;

    // ── Path Safety ─────────────────────────────────────────────────────────
    static Path safePath(String filepath) {
        Path workspace = WORKSPACE_DIR.toAbsolutePath().normalize();
        Path target = workspace.resolve(filepath).toAbsolutePath().normalize();
        if (!target.startsWith(workspace)) {
            throw new RuntimeException("Path escapes workspace: " + filepath);
        }
        return target;
    }

    static void ensureParent(Path filepath) throws IOException {
        Path parent = filepath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    // ── Tools ───────────────────────────────────────────────────────────────

    static String runBash(Map<String, String> args) {
        String command = args.getOrDefault("command", "");

        // Quick safety check
        String lower = command.toLowerCase();
        String[] dangerous = {"rm -rf /", "sudo", "shutdown", "reboot",
            "mkfs", "dd if=", "> /dev/sda", "format c:", "del /f /s"};
        for (String d : dangerous) {
            if (lower.contains(d.toLowerCase())) {
                return "Error: Dangerous command blocked ('" + d + "')";
            }
        }

        try {
            ProcessBuilder pb;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
                pb.directory(WORKSPACE_DIR.toFile());
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
                pb.directory(WORKSPACE_DIR.toFile());
            }
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() > 0) out.append("\n");
                    out.append(line);
                }
            }
            proc.waitFor();
            String result = out.toString().trim();
            return result.isEmpty() ? "(no output)" : result;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    static String runReadFile(Map<String, String> args) {
        String filepath = args.getOrDefault("path", "");
        int offset = Integer.parseInt(args.getOrDefault("offset", "0"));
        int limit = args.containsKey("limit") ? Integer.parseInt(args.get("limit")) : -1;

        try {
            Path resolved = safePath(filepath);
            if (!Files.exists(resolved)) return "Error: File not found: " + filepath;
            if (Files.isDirectory(resolved)) {
                return Files.list(resolved)
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return Files.isDirectory(p) ? name + "/" : name;
                    })
                    .collect(Collectors.joining("\n"));
            }

            List<String> lines = Files.readAllLines(resolved);
            StringBuilder out = new StringBuilder();
            int end = (limit == -1) ? lines.size() : Math.min(offset + limit, lines.size());
            for (int i = offset; i < end; i++) {
                if (i > offset) out.append("\n");
                out.append(lines.get(i));
            }
            if (limit != -1 && end < lines.size()) {
                out.append("\n... (").append(lines.size() - end).append(" more lines)");
            }
            return out.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    static String runWriteFile(Map<String, String> args) {
        String filepath = args.getOrDefault("path", "");
        String content = args.getOrDefault("content", "");

        try {
            Path resolved = safePath(filepath);
            ensureParent(resolved);
            Files.writeString(resolved, content);
            return "Wrote " + content.length() + " bytes to " + filepath;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    static String runEditFile(Map<String, String> args) {
        String filepath = args.getOrDefault("path", "");
        String oldString = args.getOrDefault("old_string", "");
        String newString = args.getOrDefault("new_string", "");
        boolean replaceAll = "true".equals(args.getOrDefault("replace_all", "false"));

        try {
            Path resolved = safePath(filepath);
            if (!Files.exists(resolved)) return "Error: File not found: " + filepath;

            String content = Files.readString(resolved);

            // Count occurrences
            int count = 0;
            int idx = 0;
            while ((idx = content.indexOf(oldString, idx)) != -1) {
                count++;
                idx += oldString.length();
            }

            if (count == 0) return "Error: text not found in " + filepath;
            if (!replaceAll && count > 1) {
                return "Error: Found " + count + " matches for oldString. Provide more context or use replace_all.";
            }

            String newContent;
            if (replaceAll) {
                newContent = content.replace(oldString, newString);
            } else {
                newContent = content.replaceFirst(
                    java.util.regex.Pattern.quote(oldString),
                    java.util.regex.Matcher.quoteReplacement(newString));
            }

            Files.writeString(resolved, newContent);
            return "Edited " + filepath + " (" + (replaceAll ? count : 1) + " replacement(s))";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    static String runGlob(Map<String, String> args) {
        String pattern = args.getOrDefault("pattern", "*");

        try {
            // Walk the workspace and match with simple wildcard logic
            List<Path> matches = new ArrayList<>();
            Files.walkFileTree(WORKSPACE_DIR, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String rel = WORKSPACE_DIR.relativize(file).toString().replace('\\', '/');
                    if (wildcardMatch(pattern.replace('\\', '/'), rel)) {
                        matches.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (matches.isEmpty()) return "(no matches)";

            // Sort by modification time (newest first)
            matches.sort((a, b) -> {
                try {
                    return Long.compare(Files.getLastModifiedTime(b).toMillis(),
                                        Files.getLastModifiedTime(a).toMillis());
                } catch (IOException e) {
                    return 0;
                }
            });

            // Filter ignored dirs and build result
            StringBuilder result = new StringBuilder();
            for (Path m : matches) {
                String rel = WORKSPACE_DIR.relativize(m).toString().replace('\\', '/');
                if (rel.contains("node_modules") || rel.contains(".git/")) continue;
                if (result.length() > 0) result.append("\n");
                result.append(rel);
            }
            return result.length() == 0 ? "(no matches after filtering)" : result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /** Simple wildcard matching supporting * and ? */
    static boolean wildcardMatch(String pattern, String str) {
        int pi = 0, si = 0;
        int pstar = -1, sstar = 0;
        while (si < str.length()) {
            if (pi < pattern.length() && (pattern.charAt(pi) == str.charAt(si) || pattern.charAt(pi) == '?')) {
                pi++; si++;
            } else if (pi < pattern.length() && pattern.charAt(pi) == '*') {
                pstar = pi;
                sstar = si;
                pi++;
            } else if (pstar != -1) {
                pi = pstar + 1;
                sstar = ++si;
                si = sstar;
            } else {
                return false;
            }
        }
        while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
        return pi == pattern.length();
    }

    // ── Dispatch Map ────────────────────────────────────────────────────────
    static final Map<String, Function<Map<String, String>, String>> TOOL_HANDLERS = new HashMap<>();

    static {
        TOOL_HANDLERS.put("bash",      Main::runBash);
        TOOL_HANDLERS.put("read_file", Main::runReadFile);
        TOOL_HANDLERS.put("write_file",Main::runWriteFile);
        TOOL_HANDLERS.put("edit_file", Main::runEditFile);
        TOOL_HANDLERS.put("glob",      Main::runGlob);
    }

    // ── Tool Executor ───────────────────────────────────────────────────────
    static String executeTool(String name, Map<String, String> args) {
        Function<Map<String, String>, String> handler = TOOL_HANDLERS.get(name);
        if (handler == null) return "Error: Unknown tool '" + name + "'";
        try {
            return handler.apply(args);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ── Mock LLM ────────────────────────────────────────────────────────────
    static class MockLLM {
        private final List<LLMResponse> responses = new ArrayList<>();
        private int idx = 0;

        static class LLMResponse {
            String content;
            List<ToolCall> toolCalls = new ArrayList<>();
        }

        static class ToolCall {
            String name;
            Map<String, String> arguments = new LinkedHashMap<>();
        }

        MockLLM() {
            // Turn 1: glob
            responses.add(makeResponse("Let me explore the workspace first.",
                tc("glob", Map.of("pattern", "**/*"))));

            // Turn 2: read_file
            responses.add(makeResponse("Found some files. Let me read the greeting.",
                tc("read_file", mapOf("path", "hello.txt"))));

            // Turn 3: edit_file
            responses.add(makeResponse("I see it says Hello. Let me update it.",
                tc("edit_file", mapOf(
                    "path", "hello.txt",
                    "old_string", "Hello, World!",
                    "new_string", "Hello, s02 Dispatch Map!",
                    "replace_all", "false"))));

            // Turn 4: write_file
            responses.add(makeResponse("Let me create a new file to demonstrate write_file.",
                tc("write_file", mapOf(
                    "path", "tools.txt",
                    "content", "bash\nread_file\nwrite_file\nedit_file\nglob\n"))));

            // Turn 5: bash to verify
            String os = System.getProperty("os.name").toLowerCase();
            String listCmd = os.contains("win") ? "dir /b" : "ls";
            String catCmd = os.contains("win") ? "type" : "cat";
            responses.add(makeResponse("Let me verify the workspace state.",
                tc("bash", Map.of("command",
                    "echo Files: && " + listCmd + " && echo --- && " + catCmd + " hello.txt && echo --- && " + catCmd + " tools.txt"))));

            // Turn 6: final (no tool calls)
            LLMResponse done = new LLMResponse();
            done.content = "All tools demonstrated successfully!\n"
                + "- glob: found project files\n"
                + "- read_file: read hello.txt\n"
                + "- edit_file: updated greeting text\n"
                + "- write_file: created tools.txt\n"
                + "- bash: verified workspace state\n\n"
                + "The agent loop never changed -- each tool has a handler in TOOL_HANDLERS.";
            responses.add(done);
        }

        @SafeVarargs
        private static Map<String, String> mapOf(Map.Entry<String, String>... entries) {
            Map<String, String> m = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : entries) m.put(e.getKey(), e.getValue());
            return m;
        }

        private static Map<String, String> mapOf(String k1, String v1) {
            return mapOf(Map.entry(k1, v1));
        }

        private static Map<String, String> mapOf(String k1, String v1, String k2, String v2,
                                                  String k3, String v3, String k4, String v4) {
            return mapOf(Map.entry(k1, v1), Map.entry(k2, v2), Map.entry(k3, v3), Map.entry(k4, v4));
        }

        private static LLMResponse makeResponse(String content, ToolCall... calls) {
            LLMResponse r = new LLMResponse();
            r.content = content;
            Collections.addAll(r.toolCalls, calls);
            return r;
        }

        private static ToolCall tc(String name, Map<String, String> args) {
            ToolCall t = new ToolCall();
            t.name = name;
            t.arguments = args;
            return t;
        }

        boolean hasNext() { return idx < responses.size(); }
        LLMResponse next() { return responses.get(idx++); }
    }

    // ── Agent Loop ──────────────────────────────────────────────────────────
    static String agentLoop(MockLLM llm) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", "You are a coding assistant. Use tools to help the user."));
        messages.add(new Message("user", "Please demonstrate all available tools on the workspace."));

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            if (!llm.hasNext()) {
                System.out.println(yellow("\n[Mock LLM exhausted]"));
                break;
            }

            MockLLM.LLMResponse response = llm.next();

            // Print assistant text
            if (!response.content.isEmpty()) {
                System.out.println("\n" + cyan(bold("Assistant: ")) + response.content);
            }

            // Append assistant message
            messages.add(new Message("assistant", response.content));

            // Check if done
            if (response.toolCalls.isEmpty()) return response.content;

            // Execute tool calls
            for (MockLLM.ToolCall tc : response.toolCalls) {
                String argDisp = tc.arguments.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
                System.out.println("\n  " + yellow("[" + tc.name + "]") + " " + dim("(" + argDisp + ")"));

                String result = executeTool(tc.name, tc.arguments);

                // Print first line
                int firstNewline = result.indexOf('\n');
                String firstLine = (firstNewline == -1) ? result : result.substring(0, firstNewline);
                System.out.println("  " + green("> ") + firstLine);

                // Print a few more lines
                int linesShown = 0;
                int pos = firstNewline;
                while (pos != -1 && pos < result.length() && linesShown < 4) {
                    pos++;
                    int next = result.indexOf('\n', pos);
                    String line = (next == -1) ? result.substring(pos) : result.substring(pos, next);
                    if (!line.isEmpty()) {
                        System.out.println("    " + dim(line));
                        linesShown++;
                    }
                    pos = next;
                }

                messages.add(new Message("tool", result));
            }

            System.out.println(dim("\n  [turn " + (turn + 1) + "/" + MAX_TURNS + " complete, looping back...]"));
        }
        return "Maximum turns reached.";
    }

    // ── Message ─────────────────────────────────────────────────────────────
    static class Message {
        String role;
        String content;
        Message(String r, String c) { role = r; content = c; }
    }

    // ── Setup Demo Workspace ────────────────────────────────────────────────
    static void setupWorkspace() throws IOException {
        Files.createDirectories(WORKSPACE_DIR);
        Files.writeString(WORKSPACE_DIR.resolve("hello.txt"), "Hello, World!\nWelcome to s02.\n");
        Files.writeString(WORKSPACE_DIR.resolve("README.txt"), "This is the demo workspace for s02 Tool Use.\n");
    }

    // ── Main ────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        // Enable ANSI on Windows
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            try {
                // Trigger virtual terminal processing
                new ProcessBuilder("cmd", "/c", "").inheritIO().start().waitFor();
            } catch (Exception ignored) {}
        }

        System.out.println(bold(cyan("\n"
            + "+==================================================+\n"
            + "|  s02: Tool Use -- The Dispatch Map Pattern        |\n"
            + "+==================================================+")));
        System.out.println(dim("Workspace: " + WORKSPACE_DIR));
        System.out.println(dim("Max turns: " + MAX_TURNS));
        System.out.println();

        setupWorkspace();

        System.out.println(magenta("TOOL_HANDLERS registered: " + String.join(", ", TOOL_HANDLERS.keySet())));
        System.out.println();

        MockLLM llm = new MockLLM();
        String finalOutput = agentLoop(llm);

        System.out.println("\n" + bold(green("=== Agent loop finished ===")));
        System.out.println("Final response: " + finalOutput);
    }
}
