/**
 * s05 Subagent — Java 17
 * Build on s04's todo system. Adds `task` tool that spawns a subagent
 * with clean context isolation. The subagent runs its own agent loop with
 * a limited tool set, cannot recurse, and returns only its final text summary.
 *
 * Single-file, self-contained, compilable with:
 *   javac Main.java && java Main
 */

import java.util.*;
import java.util.function.BiFunction;

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
        rules.put("task", PermissionLevel.ALLOW);  // delegation always allowed
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
    private static final Map<String, String> files = new LinkedHashMap<>();
    static {
        files.put("/project/main.py", "print('hello world')\n");
        files.put("/project/config.json", "{\"version\": \"1.0\"}\n");
        files.put("/project/utils.py", "def add(a, b):\n    return a + b\n");
    }

    public static String readFile(String path) { return files.getOrDefault(path, null); }

    public static void writeFile(String path, String content) { files.put(path, content); }

    public static Map<String, String> getFiles() { return files; }
}


// =============================================================================
// Tool execution (shared between main and subagent)
// =============================================================================

class ToolExecutor {
    public static String safeReadFile(Map<String, Object> params) {
        String path = (String) params.getOrDefault("path", "");
        String content = MockFileSystem.readFile(path);
        if (content != null) return "[read_file result]\n" + content;
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
                return "[execute_command result]\nmain.py\nconfig.json\nutils.py";
            }
            return "[execute_command result]\nfile1.txt\nfile2.txt";
        }
        if (cmd.startsWith("python") && cmd.contains("main.py")) {
            return "[execute_command result]\nhello world";
        }
        return "[execute_command result]\n(executed: " + cmd + ")";
    }

    public static String safeGlob(Map<String, Object> params) {
        String pattern = (String) params.getOrDefault("pattern", "");
        if (pattern.contains("*.py")) return "[glob result]\nmain.py\nutils.py";
        if (pattern.contains("*.json")) return "[glob result]\nconfig.json";
        return "[glob result]\n(no matches)";
    }

    public static String safeEdit(Map<String, Object> params) {
        String filePath = (String) params.getOrDefault("file_path", "");
        String oldString = (String) params.getOrDefault("old_string", "");
        String newString = (String) params.getOrDefault("new_string", "");

        String content = MockFileSystem.readFile(filePath);
        if (content == null) return "[edit error] File not found: " + filePath;
        if (!content.contains(oldString)) return "[edit error] old_string not found in " + filePath;

        MockFileSystem.writeFile(filePath, content.replace(oldString, newString));
        return "[edit result] Applied edit to " + filePath;
    }
}


// =============================================================================
// Simple JSON parser
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
            while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\t' || json.charAt(valStart) == '\n'))
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
                String arrayStr = json.substring(valStart, valEnd + 1);
                result.put(key, parseArray(arrayStr));
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
// Message types
// =============================================================================

class ToolCall {
    String id;
    String functionName;
    String arguments;

    ToolCall(String id, String functionName, String arguments) {
        this.id = id;
        this.functionName = functionName;
        this.arguments = arguments;
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
// Mock LLM (pre-scripted)
// =============================================================================

class ScriptedTurn {
    String toolName;   // null means text-only
    String paramsJson;

    ScriptedTurn(String toolName, String paramsJson) {
        this.toolName = toolName;
        this.paramsJson = paramsJson;
    }

    boolean isToolCall() { return toolName != null && !toolName.isEmpty(); }
}

class MockLLM {
    private static final List<String> MAIN_TEXT_RESPONSES = List.of(
        "Let me plan this first.",
        "I'll start by understanding the project structure.",
        "Good, the subagent found main.py and utils.py. Now let me refactor utils.py.",
        "Both subagents completed successfully. Let me verify the results.",
        "All tasks are complete!"
    );

    private static final List<String> SUBAGENT_TEXT_RESPONSES = List.of(
        "Let me find all Python files first.",
        "Reading the file contents now.",
        "I'll add the multiply function to utils.py.",
        "Task complete.",
        "Here's a summary of what I did."
    );

    private static final Map<String, String> SUBAGENT_FINAL_RESPONSES = Map.of(
        "Analyze project structure",
        "[subagent summary] Found 2 Python files in /project:\n" +
        "  - main.py: contains print('hello world')\n" +
        "  - utils.py: contains function add(a, b)",
        "Add multiply to utils",
        "[subagent summary] Added multiply(a, b) function to /project/utils.py. " +
        "File now contains add() and multiply() functions."
    );

    private static final List<ScriptedTurn> MAIN_CONVERSATION = List.of(
        // Turn 1: Plan
        new ScriptedTurn("todo_write",
            "{\"items\":[{\"content\":\"Analyze the /project directory structure\",\"status\":\"pending\"},{\"content\":\"Refactor utils.py: add multiply function\",\"status\":\"pending\"}]}"),
        // Turn 2: Mark first in_progress
        new ScriptedTurn("todo_write",
            "{\"items\":[{\"content\":\"Analyze the /project directory structure\",\"status\":\"in_progress\"},{\"content\":\"Refactor utils.py: add multiply function\",\"status\":\"pending\"}]}"),
        // Turn 3: Delegate to subagent
        new ScriptedTurn("task",
            "{\"description\":\"Analyze project structure\",\"prompt\":\"List all Python files in /project, read each one, and summarize what functions they contain.\"}"),
        // Turn 4: Mark analysis done, start refactor
        new ScriptedTurn("todo_write",
            "{\"items\":[{\"content\":\"Analyze the /project directory structure\",\"status\":\"completed\"},{\"content\":\"Refactor utils.py: add multiply function\",\"status\":\"in_progress\"}]}"),
        // Turn 5: Delegate refactor to subagent
        new ScriptedTurn("task",
            "{\"description\":\"Add multiply to utils\",\"prompt\":\"Read /project/utils.py, then use edit to add a multiply(a, b) function that returns a * b.\"}"),
        // Turn 6: All done
        new ScriptedTurn("todo_write",
            "{\"items\":[{\"content\":\"Analyze the /project directory structure\",\"status\":\"completed\"},{\"content\":\"Refactor utils.py: add multiply function\",\"status\":\"completed\"}]}"),
        // Turn 7: Done
        new ScriptedTurn(null, "")
    );

    public static Message call(int turn, List<ScriptedTurn> conversation, List<String> textResponses) {
        if (turn >= conversation.size()) {
            return new Message("assistant", "All done! Let me know if you need anything else.");
        }

        ScriptedTurn entry = conversation.get(turn);
        if (!entry.isToolCall()) {
            int idx = Math.min(turn / 3, textResponses.size() - 1);
            return new Message("assistant", textResponses.get(idx));
        }

        Message msg = new Message("assistant", "I'll use " + entry.toolName + " to proceed.");
        msg.toolCalls.add(new ToolCall(
            "call_" + String.format("%03d", turn), entry.toolName, entry.paramsJson));
        return msg;
    }

    public static Message mainCall(int turn) {
        return call(turn, MAIN_CONVERSATION, MAIN_TEXT_RESPONSES);
    }

    public static Message subagentCall(int turn, List<ScriptedTurn> conversation) {
        return call(turn, conversation, SUBAGENT_TEXT_RESPONSES);
    }

    public static String getSubagentFinal(String description) {
        return SUBAGENT_FINAL_RESPONSES.getOrDefault(description, "Task completed.");
    }

    public static List<ScriptedTurn> getSubagentConversation(String description) {
        return switch (description) {
            case "Analyze project structure" -> List.of(
                new ScriptedTurn("glob", "{\"pattern\":\"*.py\"}"),
                new ScriptedTurn("read_file", "{\"path\":\"/project/main.py\"}"),
                new ScriptedTurn("read_file", "{\"path\":\"/project/utils.py\"}"),
                new ScriptedTurn(null, "")
            );
            case "Add multiply to utils" -> List.of(
                new ScriptedTurn("read_file", "{\"path\":\"/project/utils.py\"}"),
                new ScriptedTurn("edit",
                    "{\"file_path\":\"/project/utils.py\",\"old_string\":\"def add(a, b):\\n    return a + b\",\"new_string\":\"def add(a, b):\\n    return a + b\\n\\ndef multiply(a, b):\\n    return a * b\"}"),
                new ScriptedTurn("write_file",
                    "{\"path\":\"/project/utils.py\",\"content\":\"def add(a, b):\\n    return a + b\\n\\ndef multiply(a, b):\\n    return a * b\\n\"}"),
                new ScriptedTurn(null, "")
            );
            default -> List.of(new ScriptedTurn(null, ""));
        };
    }
}


// =============================================================================
// Subagent loop (NEW in s05)
// =============================================================================

class Subagent {
    /**
     * Spawn a subagent with isolated context. Returns only final text summary.
     */
    public static String run(String description, String prompt) {
        final int SUBAGENT_MAX_TURNS = 15;
        var conversation = MockLLM.getSubagentConversation(description);

        List<Message> messages = new ArrayList<>();

        messages.add(new Message("system",
            "You are a subagent. You have access to a limited set of tools " +
            "(read_file, write_file, execute_command, glob, edit). " +
            "Work efficiently — you have limited turns. " +
            "When your task is complete, provide a concise text summary of your results."));

        messages.add(new Message("user", "Task: " + prompt));

        int subTurn = 0;

        for (int agentRound = 1; agentRound <= SUBAGENT_MAX_TURNS; agentRound++) {
            Message response = MockLLM.subagentCall(subTurn, conversation);
            subTurn++;

            if (response.content != null && !response.content.isEmpty()) {
                System.out.println("  [subagent] " + response.content);
            }
            messages.add(response);

            // Check for tool calls
            if (response.toolCalls.isEmpty()) {
                return MockLLM.getSubagentFinal(description);
            }

            // Execute tool calls
            for (ToolCall tc : response.toolCalls) {
                System.out.println("  [subagent:tool] " + tc.functionName);

                Map<String, Object> params = SimpleJsonParser.parse(tc.arguments);
                String result = switch (tc.functionName) {
                    case "read_file"       -> ToolExecutor.safeReadFile(params);
                    case "write_file"      -> ToolExecutor.safeWriteFile(params);
                    case "execute_command" -> ToolExecutor.safeExecuteCommand(params);
                    case "glob"            -> ToolExecutor.safeGlob(params);
                    case "edit"            -> ToolExecutor.safeEdit(params);
                    default                -> "[error] Subagent cannot use tool: " + tc.functionName;
                };

                Message toolMsg = new Message("tool", result);
                toolMsg.toolCallId = tc.id;
                messages.add(toolMsg);
            }
        }

        // Exceeded max turns — return last assistant text
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if ("assistant".equals(msg.role) && msg.content != null && !msg.content.isEmpty()) {
                return msg.content;
            }
        }
        return "[subagent error] Exceeded max turns with no output";
    }
}


// =============================================================================
// Tool registry
// =============================================================================

class ToolRegistry {
    private final TodoManager todoManager;
    private final PermissionSystem permSys;
    private final BiFunction<String, String, String> spawnSubagent;

    ToolRegistry(TodoManager tm, PermissionSystem ps,
                 BiFunction<String, String, String> spawn) {
        this.todoManager = tm;
        this.permSys = ps;
        this.spawnSubagent = spawn;
    }

    public String execute(String toolName, Map<String, Object> params) {
        if (!permSys.requestApproval(toolName, params.toString())) {
            return "[permission denied]";
        }

        return switch (toolName) {
            case "read_file"       -> ToolExecutor.safeReadFile(params);
            case "write_file"      -> ToolExecutor.safeWriteFile(params);
            case "execute_command" -> ToolExecutor.safeExecuteCommand(params);
            case "todo_write"      -> handleTodoWrite(params);
            case "task"            -> handleTask(params);
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

    private String handleTask(Map<String, Object> params) {
        String description = (String) params.getOrDefault("description", "subtask");
        String prompt = (String) params.getOrDefault("prompt", "");

        System.out.println();
        System.out.println("  ========================================");
        System.out.println("  [subagent:spawn] Description: " + description);
        System.out.println("  [subagent:spawn] Prompt: " +
            (prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt));

        String result = spawnSubagent.apply(description, prompt);

        System.out.println("  [subagent:done] Returned " + result.length() + " chars");
        System.out.println("  ========================================");
        System.out.println();

        return result;
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
        System.out.println("  s05 Subagent -- Build Your Own Code CLI");
        System.out.println("  Agent delegates to isolated subagents");
        System.out.println("============================================================");
        System.out.println();
    }

    public static void main(String[] args) {
        printBanner();

        TodoManager todoManager = new TodoManager();
        PermissionSystem permSys = new PermissionSystem(false);

        ToolRegistry registry = new ToolRegistry(todoManager, permSys, Subagent::run);

        List<Message> messages = new ArrayList<>();

        messages.add(new Message("system",
            "You are a coding agent with access to tools. " +
            "Use todo_write to plan complex tasks before executing them. " +
            "Keep your todo list updated as you work. " +
            "For complex multi-step subtasks, use the task tool to spawn a subagent. " +
            "The subagent will work independently and return a summary."));

        messages.add(new Message("user",
            "Analyze the /project directory and add a multiply() function to utils.py. " +
            "Use subagents where appropriate."));

        int llmTurn = 0;
        final int MAX_TURNS = 15;

        for (int agentRound = 1; agentRound <= MAX_TURNS; agentRound++) {
            printDivider("Round " + agentRound);

            // Nag injection (from s04)
            if (todoManager.shouldNag()) {
                String nag = todoManager.getNagMessage();
                System.out.println("  [nag] INJECTING: " + nag);
                messages.add(new Message("system", nag));
            }

            // Call LLM
            System.out.println("  [llm] Calling mock LLM (turn " + llmTurn + ")...");
            Message response = MockLLM.mainCall(llmTurn);
            llmTurn++;

            if (response.content != null && !response.content.isEmpty()) {
                System.out.println("  [assistant] " + response.content);
            }
            messages.add(response);

            // Check for tool calls
            if (response.toolCalls.isEmpty()) {
                todoManager.tickRound();
                System.out.println("  [status] " + todoManager.getStatusSummary());
                continue;
            }

            // Execute tool calls
            for (ToolCall tc : response.toolCalls) {
                System.out.println("  [tool_call] " + tc.functionName);

                Map<String, Object> params = SimpleJsonParser.parse(tc.arguments);
                String result = registry.execute(tc.functionName, params);

                String display = result.length() > 250 ? result.substring(0, 250) + "..." : result;
                System.out.println("  [tool_result] " + display);

                Message toolMsg = new Message("tool", result);
                toolMsg.toolCallId = tc.id;
                messages.add(toolMsg);
            }

            todoManager.tickRound();
            System.out.println("  [status] " + todoManager.getStatusSummary());
        }

        printDivider("Agent finished");
        System.out.println("\nFinal todo list:\n" + todoManager.formatTodoList());

        System.out.println("\nFinal filesystem state:");
        for (var entry : MockFileSystem.getFiles().entrySet()) {
            System.out.println("  " + entry.getKey() + ":\n" + entry.getValue());
        }
    }
}
