/*
 * s01 Agent Loop — Java 17 Implementation
 * ========================================
 * The core agent loop: while (finish_reason == "tool_calls"):
 *     response = LLM(messages, tools)
 *     execute tools -> append results -> loop
 *
 * Compile: javac Main.java
 * Run:     java Main
 *
 * No external dependencies. Uses only standard library.
 */

import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * AI Coding Agent — Lesson 01: The Core Agent Loop.
 *
 * This demonstrates the fundamental pattern behind all coding agents:
 * a tight loop that calls the LLM, executes any requested tools (bash),
 * feeds results back, and repeats until the LLM produces a final answer.
 */
public class Main {

    // ═══════════════════════════════════════════════════════════
    // ANSI Color Constants
    // ═══════════════════════════════════════════════════════════

    static final String R = "\033[91m";
    static final String G = "\033[92m";
    static final String Y = "\033[93m";
    static final String B = "\033[94m";
    static final String M = "\033[95m";
    static final String C = "\033[96m";
    static final String W = "\033[97m";
    static final String BOLD = "\033[1m";
    static final String DIM = "\033[2m";
    static final String X = "\033[0m";


    // ═══════════════════════════════════════════════════════════
    // Data Structures
    // ═══════════════════════════════════════════════════════════

    static class Message {
        String role;       // "system", "user", "assistant", "tool"
        String content;
        String toolCallId; // only for role "tool"

        Message(String role, String content) {
            this(role, content, "");
        }

        Message(String role, String content, String toolCallId) {
            this.role = role;
            this.content = content;
            this.toolCallId = toolCallId;
        }
    }

    static class ToolCall {
        String id;
        String functionName;
        String arguments;  // JSON: {"command":"..."}

        ToolCall(String id, String functionName, String arguments) {
            this.id = id;
            this.functionName = functionName;
            this.arguments = arguments;
        }
    }

    static class LLMChoice {
        String finishReason;     // "stop" or "tool_calls"
        String content;          // used when finishReason == "stop"
        List<ToolCall> toolCalls = new ArrayList<>();  // used when "tool_calls"

        static LLMChoice stop(String content) {
            LLMChoice c = new LLMChoice();
            c.finishReason = "stop";
            c.content = content;
            return c;
        }

        static LLMChoice toolCalls(List<ToolCall> calls) {
            LLMChoice c = new LLMChoice();
            c.finishReason = "tool_calls";
            c.toolCalls = calls;
            return c;
        }
    }


    // ═══════════════════════════════════════════════════════════
    // Bash Execution — uses ProcessBuilder
    // ═══════════════════════════════════════════════════════════

    static String executeBash(String command) {
        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor();

            String result = output.toString().trim();
            return result.isEmpty() ? "(command executed successfully, no output)" : result;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }


    // ═══════════════════════════════════════════════════════════
    // Minimal JSON Helpers (manual — for production use org.json)
    // ═══════════════════════════════════════════════════════════

    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\t': sb.append("\\t");  break;
                case '\r': sb.append("\\r");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    static String jsonUnescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                switch (s.charAt(i + 1)) {
                    case '"':  sb.append('"');  i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'n':  sb.append('\n'); i++; break;
                    case 't':  sb.append('\t'); i++; break;
                    case 'r':  sb.append('\r'); i++; break;
                    default:   sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Extract a string value from a simple JSON object: {"key":"value"} */
    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return "";
        start += search.length();
        int end = json.indexOf('"', start);
        if (end == -1) return "";
        return jsonUnescape(json.substring(start, end));
    }


    // ═══════════════════════════════════════════════════════════
    // Mock LLM
    // ═══════════════════════════════════════════════════════════

    /*
     * REPLACE WITH ACTUAL API CALL:
     * ┌────────────────────────────────────────────────────────────┐
     * │ Use java.net.http.HttpClient (Java 11+):                  │
     * │                                                            │
     * │ HttpClient client = HttpClient.newHttpClient();           │
     * │ HttpRequest request = HttpRequest.newBuilder()            │
     * │     .uri(URI.create(                                      │
     * │       "https://api.deepseek.com/v1/chat/completions"))    │
     * │     .header("Authorization", "Bearer " + API_KEY)         │
     * │     .header("Content-Type", "application/json")           │
     * │     .POST(HttpRequest.BodyPublishers.ofString(jsonBody))  │
     * │     .build();                                              │
     * │ HttpResponse<String> resp = client.send(request,          │
     * │     HttpResponse.BodyHandlers.ofString());                │
     * │ // Parse resp.body() with org.json or Jackson             │
     * └────────────────────────────────────────────────────────────┘
     */

    static boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

    static List<ToolCall> getToolCalls(String query) {
        List<ToolCall> calls = new ArrayList<>();
        String q = query.toLowerCase();
        int[] idx = {0};

        java.util.function.Consumer<String> addCall = (cmd) -> {
            String json = "{\"command\":\"" + jsonEscape(cmd) + "\"}";
            calls.add(new ToolCall("call_" + (++idx[0]), "bash", json));
        };

        // File listing
        if (q.contains("list") || q.contains("show") || q.contains("ls") ||
            q.contains("dir") || q.contains("files")) {
            if (q.contains("all") || q.contains("hidden") || q.contains("-a") || q.contains("-la")) {
                addCall.accept("ls -la");
            } else {
                addCall.accept("ls");
            }
        }

        // File creation
        if ((q.contains("create") || q.contains("make") || q.contains("new") || q.contains("write")) &&
            (q.contains("file") || q.contains("txt") || q.contains("document"))) {
            addCall.accept("echo \"Hello, World! This file was created by the AI agent.\" > demo.txt && echo \"Created demo.txt\"");
        }

        // System info
        if (q.contains("system") || q.contains("os") || q.contains("uname") ||
            q.contains("kernel") || q.contains("version")) {
            addCall.accept(isWin
                ? "systeminfo | findstr /B /C:\"OS Name\" /C:\"OS Version\""
                : "uname -a");
        }

        // Disk space
        if (q.contains("disk") || q.contains("space") || q.contains("df") || q.contains("storage")) {
            addCall.accept(isWin
                ? "wmic logicaldisk get size,freespace,caption"
                : "df -h");
        }

        // Memory
        if (q.contains("memory") || q.contains("ram") || q.contains("mem") || q.contains("free")) {
            addCall.accept(isWin
                ? "systeminfo | findstr /C:\"Total Physical Memory\" /C:\"Available Physical Memory\""
                : "free -h");
        }

        // Current directory
        if (q.contains("pwd") || q.contains("current directory") ||
            q.contains("where am i") || q.contains("working directory") || q.contains("cwd")) {
            addCall.accept(isWin ? "cd" : "pwd");
        }

        // Echo
        if ((q.contains("echo") || q.contains("say") || q.contains("print")) && !q.contains("system")) {
            addCall.accept("echo \"Hello from the AI agent!\"");
        }

        // Date / Time
        if (q.contains("date") || q.contains("time") || q.contains("today") || q.contains("now")) {
            addCall.accept(isWin ? "date /t && time /t" : "date");
        }

        // Processes
        if (q.contains("process") || q.contains("ps") || q.contains("task") ||
            q.contains("running") || q.contains("top")) {
            addCall.accept(isWin ? "tasklist" : "ps aux");
        }

        // Default fallback
        if (calls.isEmpty()) {
            addCall.accept("echo 'No specific command matched. Try: list files, system info, disk space, memory, create file, date'");
        }

        return calls;
    }

    static String getFinalAnswer(String query) {
        String q = query.toLowerCase();
        if (q.contains("list") || q.contains("file") || q.contains("show") || q.contains("ls") || q.contains("dir"))
            return "Here are the files in the current directory. The agent used bash to run the listing command.";
        if (q.contains("system") || q.contains("os") || q.contains("kernel") || q.contains("info") || q.contains("version"))
            return "Here's your system information. I ran the appropriate system info command to retrieve it.";
        if (q.contains("disk") || q.contains("space") || q.contains("storage"))
            return "Here's the disk usage information. I queried the system for storage details.";
        if (q.contains("memory") || q.contains("ram") || q.contains("mem") || q.contains("free"))
            return "Here's the memory information. This shows your current RAM usage and availability.";
        if (q.contains("create") || q.contains("make") || q.contains("new") || q.contains("write"))
            return "I've created the file using a bash command. It's been written to disk.";
        if (q.contains("date") || q.contains("time") || q.contains("today") || q.contains("now"))
            return "Here's the current date and time from the system clock.";
        if (q.contains("process") || q.contains("running") || q.contains("task"))
            return "Here are the currently running processes on your system.";
        if (q.contains("pwd") || q.contains("directory") || q.contains("where"))
            return "Here's your current working directory path.";
        return "I've executed the appropriate shell commands. Check the output above for results.";
    }

    /**
     * Mock LLM that simulates tool calls for demonstration.
     *
     * Replace with a real HTTP call to the DeepSeek/OpenAI API
     * (see comment block above). The rest of the code needs no changes.
     */
    static LLMChoice mockLLM(List<Message> messages) {
        // Find the last user message
        String lastUser = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role.equals("user")) {
                lastUser = messages.get(i).content;
                break;
            }
        }

        // Count tool results already in the conversation
        int toolCount = 0;
        for (Message m : messages) {
            if (m.role.equals("tool")) toolCount++;
        }

        // If we already have tool results, return final answer
        if (toolCount > 0) {
            return LLMChoice.stop(getFinalAnswer(lastUser));
        }

        // Otherwise, determine tools to call
        List<ToolCall> toolCalls = getToolCalls(lastUser);
        if (!toolCalls.isEmpty()) {
            return LLMChoice.toolCalls(toolCalls);
        }

        // No tools needed — return direct answer
        return LLMChoice.stop(getFinalAnswer(lastUser));
    }


    // ═══════════════════════════════════════════════════════════
    // Terminal Output Helpers
    // ═══════════════════════════════════════════════════════════

    static void printBanner() {
        System.out.println(C + BOLD);
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║       🛠  AI Coding Agent — Lesson 01        ║");
        System.out.println("║          The Core Agent Loop                 ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println(X);
        System.out.println(DIM + "Type 'quit' or 'exit' to leave. Try: list files, system info, create file" + X);
        System.out.println();
    }

    static void stepHeader(int step, String title) {
        System.out.println("\n" + Y + BOLD + "[Step " + step + "] " + title + X);
        System.out.println(Y + "──────────────────────────────────────────────────" + X);
    }


    // ═══════════════════════════════════════════════════════════
    // MAIN: The Agent Loop
    // ═══════════════════════════════════════════════════════════

    public static void main(String[] args) {
        printBanner();

        // Initialize conversation with system prompt
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system",
            "You are a helpful coding assistant with access to a bash shell. " +
            "Use the bash tool to run shell commands and help the user. " +
            "Always think step by step and use tools when needed."
        ));

        Scanner scanner = new Scanner(System.in);

        while (true) {
            // Get user input
            System.out.print(G + BOLD + "You:" + X + " ");
            String userInput = scanner.nextLine().trim();

            if (userInput.isEmpty()) continue;
            if (userInput.equalsIgnoreCase("quit") ||
                userInput.equalsIgnoreCase("exit") ||
                userInput.equalsIgnoreCase("q")) {
                System.out.println(DIM + "Goodbye!" + X);
                break;
            }

            messages.add(new Message("user", userInput));
            int turnStep = 0;

            // ═══════════════════════════════════════════════════
            // THE CORE AGENT LOOP
            // ═══════════════════════════════════════════════════
            while (true) {
                turnStep++;
                stepHeader(turnStep, "Calling LLM...");

                // Call the LLM (mock or real)
                LLMChoice choice = mockLLM(messages);

                // Branch: tool_calls
                if (choice.finishReason.equals("tool_calls")) {
                    System.out.println(M + BOLD + "  LLM decided to call " +
                        choice.toolCalls.size() + " tool(s)" + X);

                    messages.add(new Message("assistant", ""));

                    for (ToolCall tc : choice.toolCalls) {
                        String cmd = extractJsonString(tc.arguments, "command");

                        System.out.println(B + "  Tool: " + tc.functionName + X);
                        System.out.println(DIM + "  Command: " + cmd + X);

                        stepHeader(turnStep, "Executing: " + cmd);
                        String result = executeBash(cmd);

                        // Print result (first 20 lines)
                        String[] lines = result.split("\n");
                        int limit = Math.min(lines.length, 20);
                        System.out.println(G + "  Result (" + lines.length + " lines):" + X);
                        for (int i = 0; i < limit; i++) {
                            System.out.println(DIM + "    | " + lines[i] + X);
                        }
                        if (lines.length > 20) {
                            System.out.println(DIM + "    | ... (" + lines.length + " total lines)" + X);
                        }

                        messages.add(new Message("tool", result, tc.id));
                    }

                    System.out.println(Y + "  Looping back to LLM with results..." + X);

                // Branch: stop
                } else {
                    System.out.println("\n" + G + BOLD + "Agent:" + X + " " +
                        W + choice.content + X);
                    System.out.println();
                    messages.add(new Message("assistant", choice.content));
                    break;
                }
            }
            // ═══════════════════════════════════════════════════
            // END CORE AGENT LOOP
            // ═══════════════════════════════════════════════════
        }

        scanner.close();
    }
}
