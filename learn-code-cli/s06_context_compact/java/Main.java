/**
 * s06 Context Compact — Java 17
 * Build on s04's agent loop. Adds 3-layer compaction pipeline:
 *   1. snipCompact    — trim middle messages when > 50
 *   2. microCompact   — replace old tool results with "[compacted]"
 *   3. toolResultBudget — persist large results to disk, show preview
 *
 * Single-file, self-contained, compilable with:
 *   javac Main.java && java Main
 */

import java.util.*;
import java.io.*;
import java.nio.file.*;

// =============================================================================
// Compaction Pipeline (NEW in s06)
// =============================================================================

class CompactionPipeline {

    private static final Path COMPACT_TMP_DIR = Path.of(".opencode", "tmp").toAbsolutePath();

    /**
     * Layer 1: When messages exceed maxMessages, keep first 3 + last 47,
     * replacing middle with a placeholder.
     */
    static List<Message> snipCompact(List<Message> messages, int maxMessages) {
        if (messages.size() <= maxMessages) return messages;

        int removedCount = messages.size() - maxMessages + 1;

        Message placeholder = new Message("system",
            "[COMPACTED] Trimmed " + removedCount + " middle messages to stay under the " +
            maxMessages + "-message limit. First 3 and last 47 messages preserved.");

        List<Message> result = new ArrayList<>();
        // First 3
        for (int i = 0; i < Math.min(3, messages.size()); i++)
            result.add(messages.get(i));
        // Placeholder
        result.add(placeholder);
        // Last (maxMessages - 4)
        int start = messages.size() - (maxMessages - 4);
        for (int i = start; i < messages.size(); i++)
            result.add(messages.get(i));

        System.out.println("  [compact] snipCompact: trimmed " + removedCount +
            " middle messages (" + messages.size() + " \u2192 " + result.size() + ")");
        return result;
    }

    /**
     * Layer 2: Replace old tool results with "[compacted]", keeping only
     * the keepRecent most recent tool results intact.
     */
    static List<Message> microCompact(List<Message> messages, int keepRecent) {
        // Find indices of all tool messages
        List<Integer> toolIndices = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("tool".equals(messages.get(i).role))
                toolIndices.add(i);
        }

        if (toolIndices.size() <= keepRecent) return messages;

        int protectedStart = toolIndices.size() - keepRecent;
        int compactedCount = 0;

        for (int j = 0; j < protectedStart; j++) {
            int idx = toolIndices.get(j);
            if (!"[compacted]".equals(messages.get(idx).content)) {
                messages.get(idx).content = "[compacted]";
                compactedCount++;
            }
        }

        if (compactedCount > 0)
            System.out.println("  [compact] microCompact: " + compactedCount +
                " tool result(s) compacted");

        return messages;
    }

    /**
     * Layer 3: When a tool result exceeds maxChars, persist the full content
     * to disk and replace with a preview (first + last 500 chars).
     */
    static List<Message> toolResultBudget(List<Message> messages, int maxChars) {
        try {
            Files.createDirectories(COMPACT_TMP_DIR);
        } catch (IOException e) {
            // non-fatal
        }

        int budgetedCount = 0;

        for (Message msg : messages) {
            if (!"tool".equals(msg.role)) continue;
            if (msg.content.length() <= maxChars) continue;

            // Persist to disk
            String fileId = "tool_result_" + budgetedCount + ".txt";
            Path filepath = COMPACT_TMP_DIR.resolve(fileId);
            try {
                Files.writeString(filepath, msg.content);
            } catch (IOException e) {
                continue;
            }

            // Build preview
            String head = msg.content.substring(0, Math.min(500, msg.content.length()));
            String tail = msg.content.length() > 1000
                ? msg.content.substring(msg.content.length() - 500) : "";

            int lineCount = 1;
            for (char c : (head + tail).toCharArray())
                if (c == '\n') lineCount++;

            String preview = "[tool_result budget]\n" +
                "Path: " + filepath.toString() + "\n" +
                "Preview (" + lineCount + " lines shown, full " + msg.content.length() +
                " chars saved to disk):\n" +
                head + "\n";
            if (!tail.isEmpty())
                preview += "... (trimmed middle) ...\n" + tail + "\n";
            preview += "Size: " + msg.content.length() + " chars (full content saved to disk)";

            msg.content = preview;
            budgetedCount++;
        }

        if (budgetedCount > 0)
            System.out.println("  [compact] toolResultBudget: " + budgetedCount +
                " large result(s) saved to disk");

        return messages;
    }

    /**
     * Run all three compaction layers. Order: budget → micro → snip.
     */
    static List<Message> runPipeline(List<Message> messages) {
        messages = toolResultBudget(messages, 30000);
        messages = microCompact(messages, 3);
        messages = snipCompact(messages, 50);
        return messages;
    }
}


// =============================================================================
// Todo system (from s04)
// =============================================================================

enum TodoStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    private final String label;
    TodoStatus(String label) { this.label = label; }
    public String getLabel() { return label; }

    public static TodoStatus fromString(String s) {
        return switch (s) {
            case "in_progress" -> IN_PROGRESS;
            case "completed"  -> COMPLETED;
            default           -> PENDING;
        };
    }
}

class TodoItem {
    String content;
    TodoStatus status;

    TodoItem(String content, TodoStatus status) {
        this.content = content;
        this.status = status;
    }

    @Override
    public String toString() {
        String marker = switch (status) {
            case PENDING     -> "[ ]";
            case IN_PROGRESS -> "[~]";
            case COMPLETED   -> "[x]";
        };
        return "  " + marker + " " + content;
    }
}

class TodoManager {
    private static final int NAG_THRESHOLD = 3;
    private final List<TodoItem> todos = new ArrayList<>();
    private int roundsSinceLastUpdate = 0;

    public void writeTodos(List<TodoItem> items) {
        todos.clear();
        todos.addAll(items);
        roundsSinceLastUpdate = 0;
    }

    public void markUpdated() { roundsSinceLastUpdate = 0; }

    public void tickRound() {
        if (!todos.isEmpty()) roundsSinceLastUpdate++;
    }

    public boolean shouldNag() {
        if (todos.isEmpty()) return false;
        if (roundsSinceLastUpdate < NAG_THRESHOLD) return false;
        return todos.stream().anyMatch(t -> t.status != TodoStatus.COMPLETED);
    }

    public String getNagMessage() {
        long pending = todos.stream().filter(t -> t.status != TodoStatus.COMPLETED).count();
        return String.format(
            "[SYSTEM REMINDER] You have %d incomplete task(s). It has been %d rounds since your last todo update. Consider calling todo_write to update your plan.",
            pending, roundsSinceLastUpdate
        );
    }

    public String formatTodoList() {
        if (todos.isEmpty()) return "(no tasks)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < todos.size(); i++)
            sb.append("  ").append(i + 1).append(". ").append(todos.get(i)).append("\n");
        return sb.toString();
    }

    public String getStatusSummary() {
        long total = todos.size();
        long done = todos.stream().filter(t -> t.status == TodoStatus.COMPLETED).count();
        long inProg = todos.stream().filter(t -> t.status == TodoStatus.IN_PROGRESS).count();
        long pending = total - done - inProg;
        return String.format("Tasks: %d total, %d done, %d in-progress, %d pending",
            total, done, inProg, pending);
    }

    public int getRoundsSinceLastUpdate() { return roundsSinceLastUpdate; }
}


// =============================================================================
// Permission system (from s04)
// =============================================================================

enum PermissionLevel { ALLOW, ASK, DENY }

class PermissionSystem {
    private final Map<String, PermissionLevel> rules = new HashMap<>();
    private final Scanner scanner;
    private final boolean interactive;

    PermissionSystem(boolean interactive) {
        this.interactive = interactive;
        this.scanner = interactive ? new Scanner(System.in) : null;
        rules.put("read_file", PermissionLevel.ALLOW);
        rules.put("write_file", PermissionLevel.ASK);
        rules.put("execute_command", PermissionLevel.ASK);
        rules.put("todo_write", PermissionLevel.ALLOW);
    }

    public PermissionLevel check(String toolName) {
        return rules.getOrDefault(toolName, PermissionLevel.ASK);
    }

    public boolean requestApproval(String toolName, String params) {
        PermissionLevel level = check(toolName);

        if (level == PermissionLevel.ALLOW) {
            System.out.println("  [perm] AUTO-ALLOWED: " + toolName);
            return true;
        }
        if (level == PermissionLevel.DENY) {
            System.out.println("  [perm] DENIED: " + toolName);
            return false;
        }

        if (!interactive) {
            System.out.println("  [perm] AUTO-ALLOWED (non-interactive): " + toolName);
            return true;
        }

        System.out.println("  [perm] Allow tool '" + toolName + "'?");
        System.out.println("    params: " + params);
        System.out.print("    (y/n/a=always): ");
        String response = scanner.nextLine().trim().toLowerCase();

        if ("a".equals(response)) {
            rules.put(toolName, PermissionLevel.ALLOW);
            System.out.println("  [perm] '" + toolName + "' added to allowlist.");
            return true;
        }
        return "y".equals(response);
    }
}


// =============================================================================
// Mock file system
// =============================================================================

class MockFileSystem {
    private static final Map<String, String> files = new HashMap<>();

    static {
        files.put("/project/main.py", "print('hello world')\n");
        files.put("/project/config.json", "{\"version\": \"1.0\"}\n");
    }

    public static String readFile(String path) {
        if (files.containsKey(path))
            return "[read_file result]\n" + files.get(path);
        if ("/project/large_log.txt".equals(path)) {
            // Deliberately large — ~45000 chars to trigger toolResultBudget
            String chunk = "LARGE LOG FILE — ";
            StringBuilder large = new StringBuilder();
            for (int i = 0; i < 3000; i++) large.append(chunk);
            return "[read_file result]\n" + large.toString();
        }
        return "[read_file error] File not found: " + path;
    }

    public static void writeFile(String path, String content) {
        files.put(path, content);
    }
}


// =============================================================================
// Tool execution
// =============================================================================

class ToolExecutor {
    public static String safeReadFile(Map<String, Object> params) {
        String path = (String) params.getOrDefault("path", "");
        return MockFileSystem.readFile(path);
    }

    public static String safeWriteFile(Map<String, Object> params) {
        String path = (String) params.getOrDefault("path", "");
        String content = (String) params.getOrDefault("content", "");
        MockFileSystem.writeFile(path, content);
        return "[write_file result] Written " + content.length() + " bytes to " + path;
    }

    public static String safeExecuteCommand(Map<String, Object> params) {
        String cmd = (String) params.getOrDefault("command", "");
        if (cmd.startsWith("ls")) {
            if (cmd.contains("/project"))
                return "[execute_command result]\nmain.py\nconfig.json\nlarge_log.txt";
            return "[execute_command result]\nfile1.txt\nfile2.txt";
        }
        return "[execute_command result]\n(executed: " + cmd + ")";
    }
}


// =============================================================================
// Tool registry
// =============================================================================

class ToolRegistry {
    private final TodoManager todoManager;
    private final PermissionSystem permSys;

    ToolRegistry(TodoManager tm, PermissionSystem ps) {
        this.todoManager = tm;
        this.permSys = ps;
    }

    public String execute(String toolName, Map<String, Object> params) {
        if (!permSys.requestApproval(toolName, params.toString()))
            return "[permission denied]";

        return switch (toolName) {
            case "read_file"       -> ToolExecutor.safeReadFile(params);
            case "write_file"      -> ToolExecutor.safeWriteFile(params);
            case "execute_command" -> ToolExecutor.safeExecuteCommand(params);
            case "todo_write"      -> handleTodoWrite(params);
            default                -> "[error] Unknown tool: " + toolName;
        };
    }

    @SuppressWarnings("unchecked")
    private String handleTodoWrite(Map<String, Object> params) {
        Object itemsObj = params.get("items");
        if (!(itemsObj instanceof List<?> rawList))
            return "[todo_write error] Missing 'items' parameter";

        List<TodoItem> items = new ArrayList<>();
        for (Object obj : rawList) {
            if (obj instanceof Map<?, ?> m) {
                Map<String, Object> itemMap = (Map<String, Object>) m;
                String content = (String) itemMap.getOrDefault("content", "");
                String statusStr = (String) itemMap.getOrDefault("status", "pending");
                items.add(new TodoItem(content, TodoStatus.fromString(statusStr)));
            }
        }

        todoManager.writeTodos(items);

        return "[todo_write result]\nTask list updated:\n"
            + todoManager.formatTodoList() + "\n"
            + todoManager.getStatusSummary();
    }
}


// =============================================================================
// Message types
// =============================================================================

class ToolCall {
    String id;
    String functionName;
    String arguments;

    ToolCall(String id, String fn, String args) {
        this.id = id;
        this.functionName = fn;
        this.arguments = args;
    }
}

class Message {
    String role;
    String content;
    List<ToolCall> toolCalls = new ArrayList<>();
    String toolCallId;

    Message(String role, String content) {
        this.role = role;
        this.content = content;
    }
}


// =============================================================================
// Mock LLM (extended conversation for compaction demo)
// =============================================================================

class ScriptedTurn {
    String toolName;   // null means text-only
    String paramsJson;

    ScriptedTurn(String tn, String pj) {
        this.toolName = tn;
        this.paramsJson = pj;
    }

    boolean isToolCall() { return toolName != null && !toolName.isEmpty(); }
}

class MockLLM {
    private static final List<String> TEXT_ONLY = List.of(
        "Let me check the project files to understand the codebase.",
        "Reading more files to get a complete picture of the project.",
        "I need to examine additional files for context.",
        "Let me continue reading the remaining project files.",
        "The log file is very large. I'll read it for diagnostics.",
        "Continuing my analysis of the project structure.",
        "I have enough context now. Let me generate the summary.",
        "All tasks are complete! The project files have been analyzed."
    );

    private static final List<ScriptedTurn> CONVERSATION = buildConversation();

    private static List<ScriptedTurn> buildConversation() {
        List<ScriptedTurn> turns = new ArrayList<>();

        // Plan
        turns.add(new ScriptedTurn("todo_write",
            "{\"items\":[" +
            "{\"content\":\"Read and analyze all project files\",\"status\":\"pending\"}," +
            "{\"content\":\"Read the large log file for diagnostics\",\"status\":\"pending\"}," +
            "{\"content\":\"Generate a summary report\",\"status\":\"pending\"}," +
            "{\"content\":\"Verify everything compiles\",\"status\":\"pending\"}" +
            "]}"));

        // Many read_file calls — build up >50 messages for snipCompact
        for (int i = 0; i < 20; i++) {
            turns.add(new ScriptedTurn("read_file", "{\"path\":\"/project/main.py\"}"));
            if (i % 2 == 0)
                turns.add(new ScriptedTurn("read_file", "{\"path\":\"/project/config.json\"}"));
        }

        // Large file read — triggers toolResultBudget
        turns.add(new ScriptedTurn("read_file", "{\"path\":\"/project/large_log.txt\"}"));

        // More reads after large file (so microCompact will compact old ones)
        for (int i = 0; i < 5; i++)
            turns.add(new ScriptedTurn("read_file", "{\"path\":\"/project/main.py\"}"));

        // Execute commands
        for (int i = 0; i < 4; i++)
            turns.add(new ScriptedTurn("execute_command", "{\"command\":\"ls /project\"}"));

        // Update todo
        turns.add(new ScriptedTurn("todo_write",
            "{\"items\":[" +
            "{\"content\":\"Read and analyze all project files\",\"status\":\"completed\"}," +
            "{\"content\":\"Read the large log file for diagnostics\",\"status\":\"completed\"}," +
            "{\"content\":\"Generate a summary report\",\"status\":\"completed\"}," +
            "{\"content\":\"Verify everything compiles\",\"status\":\"completed\"}" +
            "]}"));

        // Final text-only
        turns.add(new ScriptedTurn(null, ""));

        return turns;
    }

    public static Message call(int turn) {
        if (turn >= CONVERSATION.size())
            return new Message("assistant", "All done! Let me know if you need anything else.");

        ScriptedTurn entry = CONVERSATION.get(turn);

        if (!entry.isToolCall()) {
            int idx = Math.min(turn / 4, TEXT_ONLY.size() - 1);
            return new Message("assistant", TEXT_ONLY.get(idx));
        }

        Message msg = new Message("assistant", "I'll use " + entry.toolName + " to proceed.");
        msg.toolCalls.add(new ToolCall("call_" + String.format("%03d", turn), entry.toolName, entry.paramsJson));
        return msg;
    }
}


// =============================================================================
// Simple JSON parser (no external dependencies)
// =============================================================================

class SimpleJsonParser {

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return result;
        json = json.substring(1, json.length() - 1).trim();

        int pos = 0;
        while (pos < json.length()) {
            int keyStart = json.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd = json.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String key = json.substring(keyStart + 1, keyEnd);

            int colon = json.indexOf(':', keyEnd + 1);
            if (colon < 0) break;

            int valStart = colon + 1;
            while (valStart < json.length() &&
                (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\t' || json.charAt(valStart) == '\n'))
                valStart++;
            if (valStart >= json.length()) break;

            if (json.charAt(valStart) == '"') {
                int valEnd = json.indexOf('"', valStart + 1);
                if (valEnd < 0) break;
                result.put(key, json.substring(valStart + 1, valEnd));
                pos = valEnd + 1;
            } else if (json.charAt(valStart) == '[') {
                int depth = 0;
                int valEnd = valStart;
                for (int i = valStart; i < json.length(); i++) {
                    if (json.charAt(i) == '[') depth++;
                    else if (json.charAt(i) == ']') { depth--; if (depth == 0) { valEnd = i; break; } }
                }
                result.put(key, parseArray(json.substring(valStart, valEnd + 1)));
                pos = valEnd + 1;
            } else {
                int valEnd = valStart;
                while (valEnd < json.length() && json.charAt(valEnd) != ',' && json.charAt(valEnd) != '}')
                    valEnd++;
                pos = valEnd;
            }

            while (pos < json.length() && json.charAt(pos) != ',') pos++;
            if (pos < json.length() && json.charAt(pos) == ',') pos++;
        }

        return result;
    }

    private static List<Map<String, Object>> parseArray(String arrayStr) {
        List<Map<String, Object>> items = new ArrayList<>();
        arrayStr = arrayStr.trim();
        if (!arrayStr.startsWith("[") || !arrayStr.endsWith("]")) return items;
        arrayStr = arrayStr.substring(1, arrayStr.length() - 1).trim();

        int pos = 0;
        while (pos < arrayStr.length()) {
            int objStart = arrayStr.indexOf('{', pos);
            if (objStart < 0) break;

            int depth = 0;
            int objEnd = objStart;
            for (int i = objStart; i < arrayStr.length(); i++) {
                if (arrayStr.charAt(i) == '{') depth++;
                else if (arrayStr.charAt(i) == '}') { depth--; if (depth == 0) { objEnd = i; break; } }
            }

            items.add(parse(arrayStr.substring(objStart, objEnd + 1)));
            pos = objEnd + 1;

            while (pos < arrayStr.length() && arrayStr.charAt(pos) != ',') pos++;
            if (pos < arrayStr.length() && arrayStr.charAt(pos) == ',') pos++;
        }

        return items;
    }
}


// =============================================================================
// Main agent loop
// =============================================================================

public class Main {

    private static void printDivider(String label) {
        System.out.println("\n------------------------------ " + label + " ------------------------------");
    }

    private static void printBanner() {
        System.out.println("============================================================");
        System.out.println("  s06 Context Compact -- Build Your Own Code CLI");
        System.out.println("  Agent with 3-layer compaction pipeline");
        System.out.println("============================================================");
        System.out.println();
    }

    public static void main(String[] args) {
        printBanner();

        TodoManager todoManager = new TodoManager();
        PermissionSystem permSys = new PermissionSystem(false);
        ToolRegistry registry = new ToolRegistry(todoManager, permSys);

        List<Message> messages = new ArrayList<>();

        messages.add(new Message("system",
            "You are a coding agent with access to tools. " +
            "Use todo_write to plan complex tasks before executing them. " +
            "Keep your todo list updated as you work."));

        messages.add(new Message("user",
            "Analyze all project files in /project, including the large log file. Generate a summary."));

        int llmTurn = 0;
        final int MAX_TURNS = 50;

        for (int agentRound = 1; agentRound <= MAX_TURNS; agentRound++) {
            printDivider("Round " + agentRound);

            // --- Nag injection (from s04) ---
            if (todoManager.shouldNag()) {
                String nag = todoManager.getNagMessage();
                System.out.println("  [nag] INJECTING: " + nag);
                messages.add(new Message("system", nag));
            }

            // ─────────────────────────────────────────
            // s06: The ONLY new line — compaction pipeline
            messages = CompactionPipeline.runPipeline(messages);
            // ─────────────────────────────────────────

            // --- Call LLM ---
            System.out.println("  [llm] Calling mock LLM (turn " + llmTurn + ")...");
            Message response = MockLLM.call(llmTurn);
            llmTurn++;

            if (response.content != null && !response.content.isEmpty())
                System.out.println("  [assistant] " + response.content);
            messages.add(response);

            // --- Check for tool calls ---
            if (response.toolCalls.isEmpty()) {
                todoManager.tickRound();
                System.out.println("  [status] " + todoManager.getStatusSummary());
                System.out.println("  [msg_count] " + messages.size() + " messages in history");
                continue;
            }

            // --- Execute tool calls ---
            for (ToolCall tc : response.toolCalls) {
                System.out.println("  [tool_call] " + tc.functionName);

                Map<String, Object> params = SimpleJsonParser.parse(tc.arguments);
                String result = registry.execute(tc.functionName, params);

                String display = result.length() > 200
                    ? result.substring(0, 200) + "..." : result;
                System.out.println("  [tool_result] " + display);

                Message toolMsg = new Message("tool", result);
                toolMsg.toolCallId = tc.id;
                messages.add(toolMsg);
            }

            todoManager.tickRound();
            System.out.println("  [status] " + todoManager.getStatusSummary());
            System.out.println("  [msg_count] " + messages.size() + " messages in history");
        }

        printDivider("Agent finished");
        System.out.println("\nFinal todo list:\n" + todoManager.formatTodoList());
        System.out.println("Final message count: " + messages.size());
    }
}
