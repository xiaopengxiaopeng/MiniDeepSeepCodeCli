// s03_permission — 3-Gate Permission Pipeline
// Builds on s02's dispatch-map agent loop.
// Adds permission checking before bash tool execution.
// Java 17, single file, self-contained. Mock LLM for demonstration.
//
// Compile: javac Main.java
// Run:     java Main              (interactive)
//          java Main --auto       (non-interactive, Gate 3 auto-approves)

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.function.*;

public class Main {

    // ============================================================
    // Configuration
    // ============================================================
    static final Path WORKSPACE_DIR = Path.of(".").toAbsolutePath().resolve("workspace");
    static final int MAX_TURNS = 10;

    // ============================================================
    // Path safety (from s02) — prevent workspace escape
    // ============================================================
    static Path safePath(String filepath) {
        Path workspace = WORKSPACE_DIR.toAbsolutePath().normalize();
        Path target = workspace.resolve(filepath).toAbsolutePath().normalize();
        if (!target.startsWith(workspace)) {
            throw new IllegalArgumentException("Path escapes workspace: " + filepath);
        }
        return target;
    }

    // ============================================================
    // Tool handlers (from s02)
    // ============================================================
    static String runBash(Map<String, String> args) throws Exception {
        String command = args.get("command");
        ProcessBuilder pb;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", "cd /d \"" + WORKSPACE_DIR + "\" && " + command);
        } else {
            pb = new ProcessBuilder("bash", "-c", "cd \"" + WORKSPACE_DIR + "\" && " + command);
        }
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String result = new String(proc.getInputStream().readAllBytes()).trim();
        proc.waitFor();
        return result.isEmpty() ? "(no output)" : result;
    }

    static String runReadFile(Map<String, String> args) throws Exception {
        Path path = safePath(args.get("path"));
        int offset = args.containsKey("offset") ? Integer.parseInt(args.get("offset")) : 0;
        int limit = args.containsKey("limit") ? Integer.parseInt(args.get("limit")) : 2000;

        if (!Files.exists(path)) return "File not found: " + path;

        List<String> lines = Files.readAllLines(path);
        int total = lines.size();
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, offset > 0 ? offset - 1 : 0);
        int end = Math.min(total, limit > 0 ? start + limit : total);
        for (int i = start; i < end; i++) {
            sb.append(i + 1).append(": ").append(lines.get(i)).append("\n");
        }
        if (limit > 0 && end < total) {
            sb.append("... (truncated, ").append(total).append(" lines total)");
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? "(empty file)" : result;
    }

    static String runWriteFile(Map<String, String> args) throws Exception {
        Path path = safePath(args.get("path"));
        String content = args.get("content");
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return "Written " + content.length() + " bytes to " + args.get("path");
    }

    static String runGlob(Map<String, String> args) throws Exception {
        String pattern = args.get("pattern");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        record Entry(Path path, FileTime time) {}
        List<Entry> entries = new ArrayList<>();

        try (var stream = Files.walk(WORKSPACE_DIR)) {
            stream.forEach(p -> {
                Path rel = WORKSPACE_DIR.relativize(p);
                if (matcher.matches(rel) || matcher.matches(p.getFileName())) {
                    try {
                        entries.add(new Entry(rel, Files.getLastModifiedTime(p)));
                    } catch (IOException ignored) {}
                }
            });
        }

        if (entries.isEmpty()) return "No files matched pattern: " + pattern;

        entries.sort((a, b) -> b.time.compareTo(a.time));
        return entries.stream()
                .map(e -> e.path.toString().replace('\\', '/'))
                .collect(Collectors.joining("\n"));
    }

    // s02 dispatch map
    static final Map<String, Function<Map<String, String>, String>> TOOL_HANDLERS = Map.of(
        "bash", args -> { try { return runBash(args); } catch (Exception e) { return "bash error: " + e.getMessage(); } },
        "read_file", args -> { try { return runReadFile(args); } catch (Exception e) { return "read_file error: " + e.getMessage(); } },
        "write_file", args -> { try { return runWriteFile(args); } catch (Exception e) { return "write_file error: " + e.getMessage(); } },
        "glob", args -> { try { return runGlob(args); } catch (Exception e) { return "glob error: " + e.getMessage(); } }
    );

    static String executeTool(ToolCall tc) {
        var handler = TOOL_HANDLERS.get(tc.name);
        if (handler == null) return "Unknown tool: " + tc.name;
        try {
            return handler.apply(tc.arguments);
        } catch (Exception e) {
            return "Tool '" + tc.name + "' error: " + e.getMessage();
        }
    }

    // ============================================================
    // Permission System (s03 — the only new code)
    // ============================================================

    // Gate 1: Hard deny patterns — ALWAYS blocked
    record DenyPattern(Pattern pattern, String description) {}
    static final List<DenyPattern> HARD_DENY = List.of(
        new DenyPattern(Pattern.compile("rm\\s+-rf\\s+/", Pattern.CASE_INSENSITIVE), "rm -rf / (recursive force-delete root)"),
        new DenyPattern(Pattern.compile("rm\\s+-rf\\s+--no-preserve-root", Pattern.CASE_INSENSITIVE), "rm -rf --no-preserve-root"),
        new DenyPattern(Pattern.compile("sudo\\s+", Pattern.CASE_INSENSITIVE), "sudo (privilege escalation)"),
        new DenyPattern(Pattern.compile(">\\s*/dev/sda", Pattern.CASE_INSENSITIVE), "overwrite /dev/sda (raw disk write)"),
        new DenyPattern(Pattern.compile(">\\s*/dev/nvme", Pattern.CASE_INSENSITIVE), "overwrite NVMe device"),
        new DenyPattern(Pattern.compile("mkfs\\.", Pattern.CASE_INSENSITIVE), "mkfs (format filesystem)"),
        new DenyPattern(Pattern.compile("dd\\s+if=", Pattern.CASE_INSENSITIVE), "dd (raw disk copy)"),
        new DenyPattern(Pattern.compile(":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:", Pattern.CASE_INSENSITIVE), "fork bomb"),
        new DenyPattern(Pattern.compile("chmod\\s+-R\\s+777\\s+/", Pattern.CASE_INSENSITIVE), "chmod -R 777 /"),
        new DenyPattern(Pattern.compile("wget\\s+.*\\|\\s*sh", Pattern.CASE_INSENSITIVE), "wget piped to sh"),
        new DenyPattern(Pattern.compile("curl\\s+.*\\|\\s*bash", Pattern.CASE_INSENSITIVE), "curl piped to bash"),
        new DenyPattern(Pattern.compile("shutdown", Pattern.CASE_INSENSITIVE), "system shutdown"),
        new DenyPattern(Pattern.compile("reboot", Pattern.CASE_INSENSITIVE), "system reboot"),
        new DenyPattern(Pattern.compile("halt", Pattern.CASE_INSENSITIVE), "system halt"),
        new DenyPattern(Pattern.compile("poweroff", Pattern.CASE_INSENSITIVE), "system poweroff"),
        new DenyPattern(Pattern.compile("Remove-Item.*-Recurse.*-Force.*C:\\\\", Pattern.CASE_INSENSITIVE), "recursive force-delete on C:\\")
    );

    // Gate 2: Destructive detection — NEEDS user confirmation
    static final List<DenyPattern> DESTRUCTIVE = List.of(
        new DenyPattern(Pattern.compile("\\brm\\b", Pattern.CASE_INSENSITIVE), "rm - removes files/directories"),
        new DenyPattern(Pattern.compile("\\bmv\\b", Pattern.CASE_INSENSITIVE), "mv - moves/renames files"),
        new DenyPattern(Pattern.compile("\\bdel\\b", Pattern.CASE_INSENSITIVE), "del - deletes files (Windows)"),
        new DenyPattern(Pattern.compile("\\berase\\b", Pattern.CASE_INSENSITIVE), "erase - deletes files (Windows)"),
        new DenyPattern(Pattern.compile("\\brmdir\\b", Pattern.CASE_INSENSITIVE), "rmdir - removes directories"),
        new DenyPattern(Pattern.compile("\\bformat\\b", Pattern.CASE_INSENSITIVE), "format - formats a disk"),
        new DenyPattern(Pattern.compile(">\\s*\\S", Pattern.CASE_INSENSITIVE), "redirect (>) - overwrites file content"),
        new DenyPattern(Pattern.compile("\\bchmod\\b", Pattern.CASE_INSENSITIVE), "chmod - changes file permissions"),
        new DenyPattern(Pattern.compile("\\bchown\\b", Pattern.CASE_INSENSITIVE), "chown - changes file ownership"),
        new DenyPattern(Pattern.compile("\\bicacls\\b", Pattern.CASE_INSENSITIVE), "icacls - modifies ACLs (Windows)"),
        new DenyPattern(Pattern.compile("Remove-Item", Pattern.CASE_INSENSITIVE), "Remove-Item - deletes files (PowerShell)"),
        new DenyPattern(Pattern.compile("New-Item.*-Force", Pattern.CASE_INSENSITIVE), "Force flag - may overwrite existing resource"),
        new DenyPattern(Pattern.compile("Clear-Content", Pattern.CASE_INSENSITIVE), "Clear-Content - empties file contents")
    );

    record PermissionCheck(boolean allowed, String reason) {}

    static PermissionCheck checkPermission(String command, boolean autoConfirm) {
        // ── Gate 1: Hard Deny ────────────────────────────────────
        for (var dp : HARD_DENY) {
            if (dp.pattern.matcher(command).find()) {
                return new PermissionCheck(false, "HARD DENY: " + dp.description);
            }
        }

        // ── Gate 2: Destructive Detection ────────────────────────
        String destructiveReason = null;
        for (var dp : DESTRUCTIVE) {
            if (dp.pattern.matcher(command).find()) {
                destructiveReason = dp.description;
                break;
            }
        }

        if (destructiveReason == null) {
            return new PermissionCheck(true, "OK");
        }

        // ── Gate 3: User Confirmation ───────────────────────────
        if (autoConfirm) {
            return new PermissionCheck(true, "AUTO-APPROVED: " + destructiveReason);
        }

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("  DESTRUCTIVE COMMAND DETECTED: " + destructiveReason);
        System.out.println("  Command: " + command);
        System.out.println("=".repeat(60));
        System.out.print("  Allow this command? [y/N]: ");

        try {
            String response = new BufferedReader(new InputStreamReader(System.in))
                    .readLine().trim().toLowerCase();
            if (response.equals("y") || response.equals("yes")) {
                return new PermissionCheck(true, "USER CONFIRMED: " + destructiveReason);
            }
        } catch (IOException e) {
            return new PermissionCheck(false, "USER INPUT ERROR");
        }
        return new PermissionCheck(false, "USER DENIED: " + destructiveReason);
    }

    // ============================================================
    // executeWithPermission — s03 wrapper around s02's executeTool
    // ============================================================
    static String executeWithPermission(ToolCall tc, boolean autoConfirm) {
        // Only bash commands go through the permission pipeline.
        if (tc.name.equals("bash")) {
            String command = tc.arguments.getOrDefault("command", "");
            var pc = checkPermission(command, autoConfirm);
            System.out.println("  Permission: " + pc.reason);
            if (!pc.allowed) {
                return "";
            }
        }
        return executeTool(tc);
    }

    // ============================================================
    // Support types
    // ============================================================
    record ToolCall(String name, Map<String, String> arguments) {}
    record LLMResponse(String content, List<ToolCall> toolCalls) {}

    // ============================================================
    // Mock LLM (for demonstration)
    // ============================================================
    static class MockLLM {
        private final List<LLMResponse> responses;
        private int callCount = 0;

        MockLLM() {
            responses = List.of(
                // Turn 1: Safe bash command — passes all gates
                new LLMResponse(
                    "Let me check what's in the temp directory.",
                    List.of(new ToolCall("bash", Map.of("command", "echo temp-file.log  cache.db  old.dat")))
                ),
                // Turn 2: Destructive bash command — triggers Gate 2 + 3
                new LLMResponse(
                    "Found temp files. I'll remove the unnecessary log file.",
                    List.of(new ToolCall("bash", Map.of("command", "rm temp-file.log")))
                ),
                // Turn 3: Hard-denied command — blocked at Gate 1
                new LLMResponse(
                    "To be thorough, I should clear the system cache too.",
                    List.of(new ToolCall("bash", Map.of("command", "sudo rm -rf /var/cache")))
                ),
                // Turn 4: Non-bash tool — no permission check needed
                new LLMResponse(
                    "Let me save a cleanup report.",
                    List.of(new ToolCall("write_file", Map.of("path", "cleanup_report.txt",
                            "content", "Cleanup completed. Removed temp-file.log.")))
                )
            );
        }

        LLMResponse chat() {
            if (callCount >= responses.size()) {
                return new LLMResponse("Task completed.", List.of());
            }
            return responses.get(callCount++);
        }
    }

    // ============================================================
    // Agent Loop (from s02 — UNCHANGED except the marked line)
    // ============================================================
    static void agentLoop(String userInput, boolean autoConfirm) {
        MockLLM llm = new MockLLM();

        System.out.println("=".repeat(60));
        System.out.println("  s03: Permission Pipeline — 3-Gate Demo");
        System.out.println("=".repeat(60));
        System.out.println("\n  User: " + userInput + "\n");

        for (int turn = 0; turn < MAX_TURNS; turn++) {
            System.out.println("--- Turn " + (turn + 1) + " ---");

            LLMResponse response = llm.chat();

            if (response.toolCalls.isEmpty()) {
                System.out.println("  Agent: " + response.content);
                break;
            }

            for (ToolCall tc : response.toolCalls) {
                System.out.println("  [" + tc.name + "] " + tc.arguments);

                // ─────────────────────────────────────────────────────
                // s03: The ONLY change from s02's agent loop
                // executeTool(tc) → executeWithPermission(tc)
                // ─────────────────────────────────────────────────────
                String result = executeWithPermission(tc, autoConfirm);
                // ─────────────────────────────────────────────────────

                System.out.println("  Result: " + result + "\n");
            }
        }

        System.out.println("=".repeat(60));
        System.out.println("  Agent loop finished.");
        System.out.println("=".repeat(60));
    }

    // ============================================================
    // Main
    // ============================================================
    public static void main(String[] args) throws Exception {
        boolean autoConfirm = false;
        StringBuilder userInput = new StringBuilder();

        for (String arg : args) {
            if (arg.equals("--auto")) {
                autoConfirm = true;
            } else {
                if (userInput.length() > 0) userInput.append(" ");
                userInput.append(arg);
            }
        }

        String prompt = userInput.length() > 0
                ? userInput.toString()
                : "clean up temp files and generate a cleanup report";

        Files.createDirectories(WORKSPACE_DIR);
        agentLoop(prompt, autoConfirm);
    }
}
