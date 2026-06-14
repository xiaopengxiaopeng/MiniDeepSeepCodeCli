/**
 * s07 Error Recovery — Java 17
 * Builds on s04's todo_write + permission system. Wraps the LLM call in a retry
 * loop with exponential backoff for rate limiting and server overload. Adds
 * reactive compaction on context-length errors.
 *
 * Single-file, self-contained, compilable with:
 *   javac Main.java && java Main
 */

import java.util.*;
import java.util.stream.Collectors;

// =============================================================================
// Error types (NEW in s07)
// =============================================================================

enum ErrorType {
    RATE_LIMIT("rate_limit"),
    SERVER_OVERLOAD("server_overload"),
    CONTEXT_TOO_LONG("context_too_long");

    private final String label;
    ErrorType(String label) { this.label = label; }
    public String getLabel() { return label; }

    public static ErrorType fromString(String s) {
        return switch (s) {
            case "server_overload" -> SERVER_OVERLOAD;
            case "context_too_long" -> CONTEXT_TOO_LONG;
            default -> RATE_LIMIT;
        };
    }
}

class LLMError extends Exception {
    final ErrorType errorType;
    final double retryAfter;

    LLMError(ErrorType errorType, String message, double retryAfter) {
        super(message);
        this.errorType = errorType;
        this.retryAfter = retryAfter;
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
            case "completed" -> COMPLETED;
            default -> PENDING;
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
            case PENDING -> "[ ]";
            case IN_PROGRESS -> "[~]";
            case COMPLETED -> "[x]";
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
// Permission system (from s04)
// =============================================================================

enum PermissionLevel { ALLOW, ASK, DENY }

class PermissionSystem {
    private final Map<String, PermissionLevel> rules = new HashMap<>();
    private final boolean interactive;

    PermissionSystem(boolean interactive) {
        this.interactive = interactive;
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
        System.out.println("  [perm] AUTO-ALLOWED (non-interactive): " + toolName);
        return true;
    }
}

// =============================================================================
// Mock file system (from s04)
// =============================================================================

class MockFileSystem {
    private static final Map<String, String> files = new HashMap<>();
    static {
        files.put("/project/main.py", "print('hello world')\n");
        files.put("/project/config.json", "{\"version\": \"1.0\"}\n");
    }
    public static String readFile(String path) { return files.getOrDefault(path, null); }
    public static void writeFile(String path, String content) { files.put(path, content); }
}

// =============================================================================
// Tool execution (from s04)
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
            if (cmd.contains("/project"))
                return "[execute_command result]\nmain.py\nconfig.json";
            return "[execute_command result]\nfile1.txt\nfile2.txt";
        }
        return "[execute_command result]\n(executed: " + cmd + ")";
    }
}

// =============================================================================
// Tool registry (from s04)
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
            case "read_file" -> ToolExecutor.safeReadFile(params);
            case "write_file" -> ToolExecutor.safeWriteFile(params);
            case "execute_command" -> ToolExecutor.safeExecuteCommand(params);
            case "todo_write" -> handleTodoWrite(params);
            default -> "[error] Unknown tool: " + toolName;
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
    boolean isError = false;
    String errorType = "";
    double retryAfter = 0.0;

    Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    Message() { this.role = ""; this.content = ""; }
}

// =============================================================================
// Context compaction (NEW in s07)
// =============================================================================

class ContextCompactor {

    public static int estimateTokenCount(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            if (m.content != null) total += m.content.length();
            for (ToolCall tc : m.toolCalls)
                if (tc.arguments != null) total += tc.arguments.length();
        }
        return total / 4;  // ~4 chars per token
    }

    public static List<Message> compactAggressively(List<Message> messages) {
        if (messages.size() <= 4) return new ArrayList<>(messages);

        List<Message> systemMsgs = new ArrayList<>();
        List<Message> otherMsgs = new ArrayList<>();
        for (Message m : messages) {
            if ("system".equals(m.role))
                systemMsgs.add(m);
            else
                otherMsgs.add(m);
        }

        final int KEEP_RECENT = 6;
        if (otherMsgs.size() > KEEP_RECENT) {
            int dropped = otherMsgs.size() - KEEP_RECENT;

            List<Message> result = new ArrayList<>(systemMsgs);

            Message marker = new Message("system",
                "[CONTEXT COMPACTED] " + dropped
                + " earlier messages were removed to recover from context-length error. "
                + "Continue from the remaining context.");
            result.add(marker);

            for (int i = otherMsgs.size() - KEEP_RECENT; i < otherMsgs.size(); i++)
                result.add(otherMsgs.get(i));

            return result;
        }

        return new ArrayList<>(messages);
    }
}

// =============================================================================
// Mock LLM with error injection (NEW in s07)
// =============================================================================

class ScriptedTurn {
    String toolName;
    String paramsJson;

    ScriptedTurn(String toolName, String paramsJson) {
        this.toolName = toolName;
        this.paramsJson = paramsJson;
    }

    boolean isToolCall() { return toolName != null && !toolName.isEmpty(); }
}

class ErrorPattern {
    ErrorType errorType;
    int maxFailures;       // how many attempts fail before success
    String message;
    double retryAfter;     // seconds

    ErrorPattern(ErrorType et, int maxFailures, String message, double retryAfter) {
        this.errorType = et;
        this.maxFailures = maxFailures;
        this.message = message;
        this.retryAfter = retryAfter;
    }
}

class MockLLM {

    private static final List<String> TEXT_ONLY_RESPONSES = List.of(
        "Let me plan this out first.",
        "Starting with reading main.py to understand the current code.",
        "Now let me read the config to check for any settings I should preserve.",
        "Good. The config is minimal — I can proceed with adding greet().",
        "Writing the updated main.py with the greet() function.",
        "Let me verify the script runs correctly.",
        "The script executed successfully. All tasks are now complete."
    );

    // Error patterns: turn -> (errorType, maxFailures, message, retryAfter)
    private static final Map<Integer, ErrorPattern> ERROR_PATTERNS = Map.of(
        2, new ErrorPattern(ErrorType.RATE_LIMIT, 1,
            "Rate limit exceeded. Please retry after 2 seconds.", 2.0),
        5, new ErrorPattern(ErrorType.CONTEXT_TOO_LONG, 1,
            "Context length exceeds the model's maximum of 4096 tokens.", 0.0),
        7, new ErrorPattern(ErrorType.SERVER_OVERLOAD, 2,
            "Server is overloaded. Please try again later.", 0.0)
    );

    private static final List<ScriptedTurn> CONVERSATION = buildConversation();

    private static List<ScriptedTurn> buildConversation() {
        return List.of(
            // Turn 0: Plan
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"pending\"},{\"content\":\"Read the config.json file\",\"status\":\"pending\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"pending\"},{\"content\":\"Run the updated script to verify\",\"status\":\"pending\"}]}"),

            // Turn 1: Update plan
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"in_progress\"},{\"content\":\"Read the config.json file\",\"status\":\"pending\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"pending\"},{\"content\":\"Run the updated script to verify\",\"status\":\"pending\"}]}"),

            // Turn 2: read_file — will trigger RATE_LIMIT!
            new ScriptedTurn("read_file",
                "{\"path\":\"/project/main.py\"}"),

            // Turn 3: Mark first done
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"completed\"},{\"content\":\"Read the config.json file\",\"status\":\"in_progress\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"pending\"},{\"content\":\"Run the updated script to verify\",\"status\":\"pending\"}]}"),

            // Turn 4: read_file config
            new ScriptedTurn("read_file",
                "{\"path\":\"/project/config.json\"}"),

            // Turn 5: write_file — will trigger CONTEXT_TOO_LONG!
            new ScriptedTurn("write_file",
                "{\"path\":\"/project/main.py\",\"content\":\"def greet(name):\\n    return f'Hello, {name}!'\\n\\nprint(greet('World'))\\n\"}"),

            // Turn 6: Mark write done
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"completed\"},{\"content\":\"Read the config.json file\",\"status\":\"completed\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"completed\"},{\"content\":\"Run the updated script to verify\",\"status\":\"in_progress\"}]}"),

            // Turn 7: execute_command — will trigger SERVER_OVERLOAD twice!
            new ScriptedTurn("execute_command",
                "{\"command\":\"python /project/main.py\"}"),

            // Turn 8: All done
            new ScriptedTurn("todo_write",
                "{\"items\":[{\"content\":\"Read the main.py file\",\"status\":\"completed\"},{\"content\":\"Read the config.json file\",\"status\":\"completed\"},{\"content\":\"Add a greet() function to main.py\",\"status\":\"completed\"},{\"content\":\"Run the updated script to verify\",\"status\":\"completed\"}]}"),

            // Turn 9: Text only
            new ScriptedTurn(null, "")
        );
    }

    /**
     * Return either an error message or the real LLM response for this turn.
     * On attempt 0, check if the turn has an error pattern; if so, return error.
     * On subsequent attempts (when attempt >= maxFailures), return the real response.
     */
    public Message call(List<Message> messages, int turn, int attempt) {
        // Check error patterns
        ErrorPattern ep = ERROR_PATTERNS.get(turn);
        if (ep != null && attempt < ep.maxFailures) {
            return makeError(ep.errorType, ep.message, ep.retryAfter);
        }
        return makeRealResponse(turn);
    }

    public static boolean isError(Message msg) {
        return msg != null && msg.isError;
    }

    public static ErrorType getErrorType(Message msg) {
        return ErrorType.fromString(msg.errorType);
    }

    public static String getErrorMessage(Message msg) {
        return msg.content;
    }

    public static double getRetryAfter(Message msg) {
        return msg.retryAfter;
    }

    private Message makeError(ErrorType et, String msg, double retryAfter) {
        Message m = new Message("assistant", msg);
        m.isError = true;
        m.errorType = et.getLabel();
        m.retryAfter = retryAfter;
        return m;
    }

    private Message makeRealResponse(int turn) {
        if (turn >= CONVERSATION.size())
            return new Message("assistant", "All tasks are complete!");

        ScriptedTurn entry = CONVERSATION.get(turn);

        if (!entry.isToolCall()) {
            int idx = Math.min(turn / 2, TEXT_ONLY_RESPONSES.size() - 1);
            return new Message("assistant", TEXT_ONLY_RESPONSES.get(idx));
        }

        Message msg = new Message("assistant", "I'll use " + entry.toolName + " to proceed.");
        msg.toolCalls.add(new ToolCall(
            "call_" + String.format("%03d", turn),
            entry.toolName,
            entry.paramsJson
        ));
        return msg;
    }
}

// =============================================================================
// Retry wrapper (NEW in s07)
// =============================================================================

class RetryResult {
    Message response;
    List<Message> messages;
    boolean success;

    RetryResult(Message resp, List<Message> msgs, boolean ok) {
        this.response = resp;
        this.messages = msgs;
        this.success = ok;
    }
}

class LLMRetryWrapper {
    private static final int MAX_RETRIES = 5;
    private static final double BASE_DELAY = 0.5; // seconds

    public static RetryResult callWithRetry(List<Message> messages, int turn, MockLLM mockLLM) {
        int attempt = 0;

        while (attempt <= MAX_RETRIES) {
            attempt++;
            Message response = mockLLM.call(messages, turn, attempt - 1);

            if (!MockLLM.isError(response))
                return new RetryResult(response, messages, true);

            ErrorType et = MockLLM.getErrorType(response);
            String msg = MockLLM.getErrorMessage(response);
            double retryAfter = MockLLM.getRetryAfter(response);

            if (attempt > MAX_RETRIES) {
                System.err.println("  [FATAL] Max retries (" + MAX_RETRIES
                    + ") exceeded: " + msg);
                return new RetryResult(response, messages, false);
            }

            double delay = BASE_DELAY * Math.pow(2.0, attempt - 1);
            if (retryAfter > 0.0) delay = Math.max(delay, retryAfter);

            if (et == ErrorType.CONTEXT_TOO_LONG) {
                int oldCount = messages.size();
                messages = ContextCompactor.compactAggressively(messages);
                int newCount = messages.size();
                System.out.println("  [compact] Context too long! Compacted "
                    + oldCount + " -> " + newCount + " messages");
                System.out.println("  [retry] " + et.getLabel()
                    + " -> retrying with compacted context (attempt "
                    + attempt + "/" + MAX_RETRIES + ")...");
            } else {
                System.out.printf("  [retry] %s: %s -- backoff %.1fs (attempt %d/%d)...%n",
                    et.getLabel(), msg, delay, attempt, MAX_RETRIES);
            }

            try {
                Thread.sleep((long) (delay * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return new RetryResult(null, messages, false);
    }
}

// =============================================================================
// Simple JSON parser (from s04)
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
                int depth = 0, valEnd = valStart;
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
            int depth = 0, objEnd = objStart;
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
        System.out.println("  s07 Error Recovery -- Build Your Own Code CLI");
        System.out.println("  Retry + Backoff + Reactive Compaction");
        System.out.println("============================================================");
        System.out.println();
    }

    public static void main(String[] args) {
        printBanner();

        TodoManager todoManager = new TodoManager();
        PermissionSystem permSys = new PermissionSystem(false);
        ToolRegistry registry = new ToolRegistry(todoManager, permSys);
        MockLLM mockLLM = new MockLLM();

        List<Message> messages = new ArrayList<>();

        messages.add(new Message("system",
            "You are a coding agent with access to tools. " +
            "Use todo_write to plan complex tasks before executing them. " +
            "Keep your todo list updated as you work."));

        messages.add(new Message("user",
            "Add a greet() function to /project/main.py that takes a name and returns a greeting."));

        int llmTurn = 0;
        final int MAX_TURNS = 20;

        int statsTotalErrors = 0;
        int statsRateLimits = 0;
        int statsServerOverloads = 0;
        int statsContextCompactions = 0;

        for (int agentRound = 1; agentRound <= MAX_TURNS; agentRound++) {
            printDivider("Round " + agentRound);

            // Nag injection (from s04)
            if (todoManager.shouldNag()) {
                String nag = todoManager.getNagMessage();
                System.out.println("  [nag] INJECTING: " + nag);
                messages.add(new Message("system", nag));
            }

            // Call LLM with retry (NEW in s07)
            System.out.println("  [llm] Calling mock LLM (turn " + llmTurn + ")...");

            RetryResult result = LLMRetryWrapper.callWithRetry(messages, llmTurn, mockLLM);
            if (!result.success) {
                System.err.println("  [FATAL] LLM call failed after all retries.");
                break;
            }

            Message response = result.response;
            messages = result.messages;
            llmTurn++;

            if (response.content != null && !response.content.isEmpty()) {
                System.out.println("  [assistant] " + response.content);
            }
            messages.add(response);

            // Check for tool calls
            if (response.toolCalls.isEmpty()) {
                todoManager.tickRound();
                System.out.println("  [status] " + todoManager.getStatusSummary());
                System.out.println("  [nag_counter] rounds since last todo update: "
                    + todoManager.getRoundsSinceLastUpdate());
                continue;
            }

            // Execute tool calls
            for (ToolCall tc : response.toolCalls) {
                System.out.println("  [tool_call] " + tc.functionName);

                Map<String, Object> params = SimpleJsonParser.parse(tc.arguments);
                String execResult = registry.execute(tc.functionName, params);

                String display = execResult.length() > 200
                    ? execResult.substring(0, 200) + "..." : execResult;
                System.out.println("  [tool_result] " + display);

                Message toolMsg = new Message("tool", execResult);
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

        System.out.println("\n  Error Recovery Stats:");
        System.out.println("    Total errors recovered:   " + statsTotalErrors);
        System.out.println("    Rate limits handled:      " + statsRateLimits);
        System.out.println("    Server overloads handled: " + statsServerOverloads);
        System.out.println("    Context compactions:      " + statsContextCompactions);
    }
}
