/**
 * s04 Todo Write — Java 17
 * Build on s03's permission system. Adds todo_write tool, ArrayList of TodoItem,
 * and a nag reminder injected after 3 rounds without a todo update.
 *
 * Single-file, self-contained, compilable with:
 *   javac Main.java && java Main
 */

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

// =============================================================================
// Todo system (NEW in s04)
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

    public void markUpdated() {
        roundsSinceLastUpdate = 0;
    }

    public void tickRound() {
        if (!todos.isEmpty()) {
            roundsSinceLastUpdate++;
        }
    }

    public boolean shouldNag() {
        if (todos.isEmpty()) return false;
        if (roundsSinceLastUpdate < NAG_THRESHOLD) return false;
        // Only nag if there are incomplete tasks worth updating
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
        for (int i = 0; i < todos.size(); i++) {
            sb.append("  ").append(i + 1).append(". ").append(todos.get(i)).append("\n");
        }
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
// Permission system (from s03)
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

        // ASK mode — non-interactive: auto-allow for tutorial smoothness
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
        return files.getOrDefault(path, null);
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
        String content = MockFileSystem.readFile(path);
        if (content != null) {
            return "[read_file result]\n" + content;
        }
        return "[read_file error] File not found: " + path;
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
            if (cmd.contains("/project")) {
                return "[execute_command result]\nmain.py\nconfig.json";
            }
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
        // Permission check
        if (!permSys.requestApproval(toolName, params.toString())) {
            return "[permission denied]";
        }

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
        if (!(itemsObj instanceof List<?> rawList)) {
            return "[todo_write error] Missing 'items' parameter";
        }

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
    String arguments; // JSON string

    ToolCall(String id, String functionName, String arguments) {
        this.id = id;
        this.functionName = functionName;
        this.arguments = arguments;
    }
}

class Message {
    String role;        // "system", "user", "assistant", "tool"
    String content;
    List<ToolCall> toolCalls = new ArrayList<>();
    String toolCallId;  // for tool messages

    Message(String role, String content) {
        this.role = role;
        this.content = content;
    }
}


// =============================================================================
// Mock LLM (pre-scripted conversation)
// =============================================================================

class ScriptedTurn {
    String toolName;   // null means text-only
    String paramsJson; // JSON string for tool call arguments

    ScriptedTurn(String toolName, String paramsJson) {
        this.toolName = toolName;
        this.paramsJson = paramsJson;
    }

    boolean isToolCall() { return toolName != null && !toolName.isEmpty(); }
}

class MockLLM {
    private static final List<String> TEXT_ONLY_RESPONSES = List.of(
        "I'll need to read the existing files first to understand the codebase.",
        "Found greet() already exists — let me verify the implementation is correct.",
        "The code looks good. Let me double-check the config to ensure no conflicts.",
        "All tasks are complete! The greet() function has been added and verified."
    );

    private static final List<ScriptedTurn> CONVERSATION = buildConversation();

    private static List<ScriptedTurn> buildConversation() {
        return List.of(
            // Turn 1: Plan tasks
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"pending\"},{\"content\":\"Read the config.json file\",\"status\":\"pending\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"pending\"},{\"content\":\"Run the updated script to verify\",\"status\":\"pending\"}]}"),

            // Turn 2: Start working on first item
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"in_progress\"},{\"content\":\"Read the config.json file\",\"status\":\"pending\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"pending\"},{\"content\":\"Run the updated script to verify\",\"status\":\"pending\"}]}"),

            // Turn 3: read_file
            new ScriptedTurn("read_file",
                "{\"path\":\"/project/main.py\"}"),

            // Turn 4: Mark first done, start second
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"completed\"},{\"content\":\"Read the config.json file\",\"status\":\"in_progress\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"pending\"},{\"content\":\"Run the updated script to verify\",\"status\":\"pending\"}]}"),

            // Turn 5: read_file
            new ScriptedTurn("read_file",
                "{\"path\":\"/project/config.json\"}"),

            // Turn 6: Mark second done, start editing
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"completed\"},{\"content\":\"Read the config.json file\",\"status\":\"completed\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"in_progress\"},{\"content\":\"Run the updated script to verify\",\"status\":\"pending\"}]}"),

            // Turn 7: write_file
            new ScriptedTurn("write_file",
                "{\"path\":\"/project/main.py\",\"content\":\"def greet(name):\\n    return f'Hello, {name}!'\\n\\nprint(greet('World'))\\n\"}"),

            // Turns 8, 9, 10: Text-only (agent forgets to update todo) — nag triggers after turn 10
            new ScriptedTurn(null, ""),
            new ScriptedTurn(null, ""),
            new ScriptedTurn(null, ""),

            // Turn 11: Nagged — updates todo
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"completed\"},{\"content\":\"Read the config.json file\",\"status\":\"completed\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"completed\"},{\"content\":\"Run the updated script to verify\",\"status\":\"in_progress\"}]}"),

            // Turn 12: execute_command
            new ScriptedTurn("execute_command",
                "{\"command\":\"python /project/main.py\"}"),

            // Turn 13: Final update — all done
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"completed\"},{\"content\":\"Read the config.json file\",\"status\":\"completed\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"completed\"},{\"content\":\"Run the updated script to verify\",\"status\":\"completed\"}]}"),

            // Turn 14: Done — text only
            new ScriptedTurn(null, "")
        );
    }

    public static Message call(int turn) {
        if (turn >= CONVERSATION.size()) {
            return new Message("assistant", "All done! Let me know if you need anything else.");
        }

        ScriptedTurn entry = CONVERSATION.get(turn);

        if (!entry.isToolCall()) {
            int idx = Math.min(turn / 3, TEXT_ONLY_RESPONSES.size() - 1);
            return new Message("assistant", TEXT_ONLY_RESPONSES.get(idx));
        }

        Message msg = new Message("assistant", "I'll use " + entry.toolName + " to proceed.");
        msg.toolCalls.add(new ToolCall("call_" + String.format("%03d", turn), entry.toolName, entry.paramsJson));
        return msg;
    }
}


// =============================================================================
// Simple JSON parser for mock LLM arguments (no external dependencies)
// =============================================================================

class SimpleJsonParser {

    /**
     * Parse a flat JSON object with string and array values into a Map.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        json = json.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) return result;
        json = json.substring(1, json.length() - 1).trim();

        int pos = 0;
        while (pos < json.length()) {
            // Find key
            int keyStart = json.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd = json.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String key = json.substring(keyStart + 1, keyEnd);

            // Find colon
            int colon = json.indexOf(':', keyEnd + 1);
            if (colon < 0) break;

            // Find value start
            int valStart = colon + 1;
            while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\t' || json.charAt(valStart) == '\n')) {
                valStart++;
            }
            if (valStart >= json.length()) break;

            if (json.charAt(valStart) == '"') {
                // String value
                int valEnd = json.indexOf('"', valStart + 1);
                if (valEnd < 0) break;
                String value = json.substring(valStart + 1, valEnd);
                result.put(key, value);
                pos = valEnd + 1;
            } else if (json.charAt(valStart) == '[') {
                // Array value
                int depth = 0;
                int valEnd = valStart;
                for (int i = valStart; i < json.length(); i++) {
                    if (json.charAt(i) == '[') depth++;
                    else if (json.charAt(i) == ']') {
                        depth--;
                        if (depth == 0) { valEnd = i; break; }
                    }
                }
                String arrayStr = json.substring(valStart, valEnd + 1);
                // Parse the inner array of objects
                result.put(key, parseArray(arrayStr));
                pos = valEnd + 1;
            } else {
                // Skip unknown value type
                int valEnd = valStart;
                while (valEnd < json.length() && json.charAt(valEnd) != ',' && json.charAt(valEnd) != '}') {
                    valEnd++;
                }
                pos = valEnd;
            }

            // Skip comma
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
                else if (arrayStr.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) { objEnd = i; break; }
                }
            }

            String objStr = arrayStr.substring(objStart, objEnd + 1);
            items.add(parse(objStr));
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
        System.out.println("  s04 Todo Write -- Build Your Own Code CLI");
        System.out.println("  Agent with task planning & nag reminder");
        System.out.println("============================================================");
        System.out.println();
    }

    public static void main(String[] args) {
        printBanner();

        TodoManager todoManager = new TodoManager();
        PermissionSystem permSys = new PermissionSystem(false);  // non-interactive mode
        ToolRegistry registry = new ToolRegistry(todoManager, permSys);

        List<Message> messages = new ArrayList<>();

        // System prompt
        messages.add(new Message("system",
            "You are a coding agent with access to tools. " +
            "Use todo_write to plan complex tasks before executing them. " +
            "Keep your todo list updated as you work. " +
            "When the system reminds you about stale todos, update them promptly."));

        // User prompt
        messages.add(new Message("user",
            "Add a greet() function to /project/main.py that takes a name and returns a greeting."));

        int llmTurn = 0;
        final int MAX_TURNS = 20;

        for (int agentRound = 1; agentRound <= MAX_TURNS; agentRound++) {
            printDivider("Round " + agentRound);

            // --- Nag injection (NEW in s04) ---
            if (todoManager.shouldNag()) {
                String nag = todoManager.getNagMessage();
                System.out.println("  [nag] INJECTING: " + nag);
                messages.add(new Message("system", nag));
            }

            // --- Call LLM ---
            System.out.println("  [llm] Calling mock LLM (turn " + llmTurn + ")...");
            Message response = MockLLM.call(llmTurn);
            llmTurn++;

            if (response.content != null && !response.content.isEmpty()) {
                System.out.println("  [assistant] " + response.content);
            }
            messages.add(response);

            // --- Check for tool calls ---
            if (response.toolCalls.isEmpty()) {
                todoManager.tickRound();
                System.out.println("  [status] " + todoManager.getStatusSummary());
                System.out.println("  [nag_counter] rounds since last todo update: "
                    + todoManager.getRoundsSinceLastUpdate());
                continue;
            }

            // --- Execute tool calls ---
            for (ToolCall tc : response.toolCalls) {
                System.out.println("  [tool_call] " + tc.functionName);

                Map<String, Object> params = SimpleJsonParser.parse(tc.arguments);
                String result = registry.execute(tc.functionName, params);

                // Truncate for display
                String display = result.length() > 200 ? result.substring(0, 200) + "..." : result;
                System.out.println("  [tool_result] " + display);

                // Append tool result message
                Message toolMsg = new Message("tool", result);
                toolMsg.toolCallId = tc.id;
                messages.add(toolMsg);
            }

            todoManager.tickRound();
            System.out.println("  [status] " + todoManager.getStatusSummary());
            System.out.println("  [nag_counter] rounds since last todo update: "
                + todoManager.getRoundsSinceLastUpdate());
        }

        printDivider("Agent finished");
        System.out.println("\nFinal todo list:\n" + todoManager.formatTodoList());
    }
}
