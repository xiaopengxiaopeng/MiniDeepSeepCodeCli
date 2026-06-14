/*
 * s08  Full Agent --- "Many mechanisms, one loop."
 * =================================================
 * Java 17  single-file reference implementation.
 * Combines all 7 mechanisms from s01-s07 into one complete agent.
 *
 *   s01  Agent Loop       --- while (running) { observe -> plan -> act }
 *   s02  Multi-Tool       --- bash, read_file, write_file, edit_file, glob
 *   s03  Permissions      --- deny list, destructive detection, user confirm
 *   s04  Todo Write       --- task planning with nag reminders
 *   s05  Subagents        --- clean context delegation
 *   s06  Compaction       --- snip, micro, budget layers
 *   s07  Error Recovery   --- retries with exponential backoff
 *
 * Compile:  javac Main.java
 * Run:      java Main
 */

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// ============================================================================
// ===  FROM s02: Tool Result  ===============================================
// ============================================================================

class ToolResult {
    boolean success;
    String  output;
    String  toolName;
    Map<String, String> metadata;

    ToolResult(boolean s, String o, String n) {
        this(s, o, n, Map.of());
    }
    ToolResult(boolean s, String o, String n, Map<String, String> m) {
        success = s; output = o; toolName = n; metadata = m;
    }
}


// ============================================================================
// ===  FROM s02: Mock Virtual File System  ==================================
// ============================================================================

class VirtualFS {
    private final Map<String, String> files = new LinkedHashMap<>();

    VirtualFS() {
        files.put("demo/file1.txt", """
            Hello from file1!
            This is a sample file with multiple lines.
            Line 3: the agent can read this.
            Line 4: end of file.""");

        files.put("demo/config.json", """
            {
              "host": "localhost",
              "port": 8080,
              "debug": true
            }""");

        files.put("demo/src/main.py", """
            def main():
                print("Hello, World!")

            if __name__ == '__main__':
                main()""");

        files.put("demo/src/utils.py", "def helper():\n    return 42");

        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 120; i++)
            log.append("[").append(i).append("] log entry number ")
               .append(i).append("\n");
        files.put("demo/huge.log", log.toString());

        files.put("demo/notes.txt",
            "TODO: buy milk\nTODO: learn Java\nDONE: breathe");
        files.put("demo/bad.sh",
            "#!/bin/bash\nrm -rf /important/data");
    }

    String read(String path) {
        return files.getOrDefault(path, "");
    }

    boolean exists(String path) {
        return files.containsKey(path);
    }

    void write(String path, String content) {
        files.put(path, content);
    }

    List<String> glob(String pattern) {
        // Simple glob: * -> .*
        String reStr = "^" + Pattern.quote(pattern).replace("*", "\\E.*\\Q") + "$";
        Pattern re = Pattern.compile(reStr);
        return files.keySet().stream()
            .filter(p -> re.matcher(p).matches())
            .sorted()
            .collect(Collectors.toList());
    }

    int fileCount() { return files.size(); }
}


// ============================================================================
// ===  FROM s02: Tool Dispatch  =============================================
// ============================================================================

@FunctionalInterface
interface ToolFn {
    ToolResult apply(Map<String, String> params);
}

class ToolSet {
    static ToolResult readFile(VirtualFS fs, Map<String, String> p) {
        String path = p.getOrDefault("path", "");
        String content = fs.read(path);
        if (content.isEmpty() && !fs.exists(path))
            return new ToolResult(false, "ERROR: file not found: " + path, "read_file");
        long lines = content.lines().count();
        return new ToolResult(true, content, "read_file",
            Map.of("lines", String.valueOf(lines)));
    }

    static ToolResult writeFile(VirtualFS fs, Map<String, String> p) {
        String path = p.getOrDefault("path", "");
        String content = p.getOrDefault("content", "");
        if (path.isEmpty())
            return new ToolResult(false, "ERROR: no path provided", "write_file");
        fs.write(path, content);
        return new ToolResult(true,
            "Wrote " + content.length() + " bytes to " + path, "write_file",
            Map.of("bytes", String.valueOf(content.length())));
    }

    static ToolResult editFile(VirtualFS fs, Map<String, String> p) {
        String path = p.getOrDefault("path", "");
        String oldStr = p.getOrDefault("old", "");
        String newStr = p.getOrDefault("new", "");
        String content = fs.read(path);
        if (!fs.exists(path))
            return new ToolResult(false, "ERROR: file not found: " + path, "edit_file");
        if (!content.contains(oldStr))
            return new ToolResult(false,
                "ERROR: string not found in " + path, "edit_file");
        fs.write(path, content.replace(oldStr, newStr));
        return new ToolResult(true, "Edited " + path, "edit_file");
    }

    static ToolResult glob(VirtualFS fs, Map<String, String> p) {
        String pattern = p.getOrDefault("pattern", "*");
        List<String> matches = fs.glob(pattern);
        String out = matches.isEmpty() ? "(no matches)"
                   : String.join("\n", matches);
        return new ToolResult(true, out, "glob",
            Map.of("count", String.valueOf(matches.size())));
    }

    static ToolResult bash(Map<String, String> p) {
        String cmd = p.getOrDefault("cmd", "");
        if (cmd.startsWith("echo "))
            return new ToolResult(true, cmd.substring(5).trim(), "bash");
        if (cmd.equals("ls") || cmd.startsWith("ls "))
            return new ToolResult(true, "file1.txt  config.json  src/", "bash");
        if (cmd.toLowerCase().contains("error") || cmd.equals("exit 1"))
            return new ToolResult(false, "Command failed with exit code 1",
                "bash", Map.of("exit_code", "1"));
        if (cmd.equals("pwd"))
            return new ToolResult(true, "/home/user/project", "bash");
        return new ToolResult(true, "(simulated) ran: " + cmd, "bash");
    }

    // s02: The dispatch map
    static Map<String, ToolFn> buildToolMap(VirtualFS fs) {
        Map<String, ToolFn> map = new LinkedHashMap<>();
        map.put("read_file",  p -> readFile(fs, p));
        map.put("write_file", p -> writeFile(fs, p));
        map.put("edit_file",  p -> editFile(fs, p));
        map.put("glob",       p -> glob(fs, p));
        map.put("bash",       ToolSet::bash);
        return map;
    }
}


// ============================================================================
// ===  FROM s03: Permission Pipeline  =======================================
// ============================================================================

class PermissionGate {
    private final boolean autoApprove;
    private int blockCount = 0;

    private static final List<String> DENY_LIST = List.of(
        "rm -rf /", "format c:", "shutdown -h", "del /f", ":(){ :|:& };:"
    );

    private static final List<Map.Entry<String, String>> DESTRUCTIVE = List.of(
        Map.entry("\\brm\\b",           "file deletion"),
        Map.entry("\\bdel\\b",          "file deletion"),
        Map.entry("\\bdrop\\s+table\\b","database destruction"),
        Map.entry("\\bformat\\b",       "disk formatting"),
        Map.entry("\\bshutdown\\b",     "system shutdown"),
        Map.entry("\\bchmod\\s+777\\b", "permissive permissions"),
        Map.entry(">\\s*/dev/",         "device overwrite")
    );

    PermissionGate(boolean autoApprove) { this.autoApprove = autoApprove; }

    record CheckResult(boolean approved, String reason) {}

    CheckResult check(String toolName, Map<String, String> params) {
        String cmd = "bash".equals(toolName) ? params.getOrDefault("cmd", "") : "";
        String cmdLower = cmd.toLowerCase();

        // Layer 1: Deny list
        for (String blocked : DENY_LIST) {
            if (cmdLower.contains(blocked.toLowerCase())) {
                blockCount++;
                return new CheckResult(false, "BLOCKED by deny-list: matches '" + blocked + "'");
            }
        }

        // Layer 2: Destructive pattern detection
        for (var entry : DESTRUCTIVE) {
            Pattern p = Pattern.compile(entry.getKey(), Pattern.CASE_INSENSITIVE);
            if (p.matcher(cmd).find()) {
                if (autoApprove)
                    return new CheckResult(true, "ALLOWED (auto): " + entry.getValue());
                // Layer 3: User confirm
                System.out.println("\n  [PERMISSION] Destructive: " + entry.getValue());
                System.out.println("  Command: " + cmd.substring(0, Math.min(80, cmd.length())));
                System.out.print("  Allow? (y/N): ");
                String answer = new Scanner(System.in).nextLine().trim().toLowerCase();
                if (!answer.equals("y"))
                    return new CheckResult(false, "DENIED by user");
                return new CheckResult(true, "ALLOWED by user");
            }
        }
        return new CheckResult(true, "OK");
    }

    int blockCount() { return blockCount; }
}


// ============================================================================
// ===  FROM s04: Todo Write  ================================================
// ============================================================================

class TodoItem {
    int    id;
    String description;
    boolean done;
    long   createdAt;  // millis
    int    nagCount;

    TodoItem(int id, String desc) {
        this.id = id; description = desc; done = false;
        createdAt = System.currentTimeMillis(); nagCount = 0;
    }
}

class TodoManager {
    private final List<TodoItem> items = new ArrayList<>();
    private int nextId = 1;
    private final long nagThresholdMs;
    private long lastCheckMs;

    TodoManager(double nagSec) {
        nagThresholdMs = (long)(nagSec * 1000);
        lastCheckMs = System.currentTimeMillis();
    }
    TodoManager() { this(30.0); }

    TodoItem addTask(String desc) {
        TodoItem item = new TodoItem(nextId++, desc);
        items.add(item);
        return item;
    }

    void addTasks(List<String> descs) {
        descs.forEach(this::addTask);
    }

    void markDone(int id) {
        items.stream().filter(i -> i.id == id).findFirst()
             .ifPresent(i -> i.done = true);
    }

    void markDoneByHint(String hint) {
        items.stream()
            .filter(i -> !i.done && i.description.toLowerCase()
                        .contains(hint.toLowerCase()))
            .findFirst()
            .ifPresent(i -> i.done = true);
    }

    List<TodoItem> pending() {
        return items.stream().filter(i -> !i.done).collect(Collectors.toList());
    }

    boolean isAllDone() { return pending().isEmpty(); }

    String nagIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < 5000) return "";
        lastCheckMs = now;

        StringBuilder sb = new StringBuilder();
        for (TodoItem item : items) {
            long ageMs = now - item.createdAt;
            if (!item.done && ageMs > nagThresholdMs && item.nagCount < 3) {
                item.nagCount++;
                sb.append("  [!] NAG #").append(item.nagCount)
                  .append(": '").append(item.description)
                  .append("' still pending (")
                  .append(ageMs / 1000).append("s)\n");
            }
        }
        return sb.toString();
    }

    String summary() {
        long done = items.stream().filter(i -> i.done).count();
        long total = items.size();
        if (total == 0) return "(no tasks)";
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < total; i++) bar.append(i < done ? '#' : '-');
        bar.append("]");
        return "Todo " + bar + " " + done + "/" + total;
    }
}


// ============================================================================
// ===  FROM s05: Subagent Spawning  =========================================
// ============================================================================

record SubagentResult(String task, boolean success, String output, int stepsTaken) {}

class Subagent {
    private final String  task;
    private final VirtualFS fs;
    private final int     id;
    private int           steps = 0;

    Subagent(String task, VirtualFS fs, int id) {
        this.task = task; this.fs = fs; this.id = id;
    }

    SubagentResult run() {
        String taskLower = task.toLowerCase();

        if (taskLower.contains("analyze") || taskLower.contains("read")) {
            Matcher m = Pattern.compile("([\\w./-]+\\.\\w+)").matcher(task);
            String filePath = m.find() ? m.group(1) : "demo/file1.txt";
            steps++;
            String content = fs.read(filePath);
            if (!content.isEmpty()) {
                long lines = content.lines().count();
                return new SubagentResult(task, true,
                    "Analysis: " + filePath + " has " + lines + " lines, "
                    + content.length() + " chars. Preview: "
                    + content.substring(0, Math.min(50, content.length())) + "...",
                    steps);
            }
            return new SubagentResult(task, false, "File not found: " + filePath, steps);
        }
        else if (taskLower.contains("search") || taskLower.contains("find")) {
            steps++;
            var matches = fs.glob("*.py");
            return new SubagentResult(task, true,
                "Found " + matches.size() + " Python files", steps);
        }
        // Generic
        steps++;
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        return new SubagentResult(task, true,
            "Subagent-" + id + " completed: " + task, steps);
    }
}

class SubagentManager {
    private final VirtualFS fs;
    private int nextId = 1;
    private final List<SubagentResult> completed = new ArrayList<>();

    SubagentManager(VirtualFS fs) { this.fs = fs; }

    int spawn(String task) {
        int id = nextId++;
        System.out.println("  [SUBAGENT] Spawned subagent-" + id +
            " for: " + task.substring(0, Math.min(50, task.length())) + "...");
        Subagent sub = new Subagent(task, fs, id);
        completed.add(sub.run());
        return id;
    }

    List<SubagentResult> collect() {
        var results = List.copyOf(completed);
        completed.clear();
        return results;
    }
}


// ============================================================================
// ===  FROM s06: Context Compaction  ========================================
// ============================================================================

class ContextCompactor {
    private final int softLimit;
    private final int hardLimit;
    private int compactionCount = 0;
    private int tokensSaved = 0;

    ContextCompactor(int softLimit, int hardLimit) {
        this.softLimit = softLimit; this.hardLimit = hardLimit;
    }
    ContextCompactor() { this(30, 50); }

    boolean needsCompaction(Deque<String> history, int budgetUsed) {
        return history.size() > softLimit || budgetUsed > hardLimit * 50;
    }

    void compact(Deque<String> history, String layer) {
        compactionCount++;
        final int MAX_PREVIEW = 40;

        switch (layer) {
            case "snip" -> {
                int target = Math.max(10, softLimit / 2);
                int removed = 0;
                while (history.size() > target) { history.pollFirst(); removed++; }
                tokensSaved += removed * 50;
                System.out.println("  [COMPACT:snip] Dropped " + removed
                    + " entries. History: " + history.size());
            }
            case "micro" -> {
                int mid = history.size() / 2;
                var old = new ArrayList<String>();
                for (int i = 0; i < mid; i++) old.add(history.pollFirst());
                StringBuilder summary = new StringBuilder("[Summarised ");
                summary.append(old.size()).append(" entries: ");
                for (int i = 0; i < Math.min(3, old.size()); i++)
                    summary.append(old.get(i).substring(0,
                        Math.min(MAX_PREVIEW, old.get(i).length()))).append("; ");
                summary.append("]");
                history.addFirst(summary.toString());
                tokensSaved += old.size() * 40;
                System.out.println("  [COMPACT:micro] Summarised " + old.size()
                    + " entries. History: " + history.size());
            }
            case "budget" -> {
                int keep = Math.min(5, history.size());
                var recent = new ArrayList<String>();
                for (int i = 0; i < keep; i++) recent.add(history.pollLast());
                int oldCount = history.size();
                history.clear();
                history.add("[BUDGET: removed " + oldCount
                    + " entries, keeping " + keep + "]");
                for (int i = recent.size() - 1; i >= 0; i--)
                    history.add(recent.get(i));
                tokensSaved += oldCount * 60;
                System.out.println("  [COMPACT:budget] Hard compaction! History: "
                    + history.size());
            }
        }
    }

    int compactionCount() { return compactionCount; }
}


// ============================================================================
// ===  FROM s07: Error Recovery  ============================================
// ============================================================================

class ErrorRecovery {
    static final int MAX_RETRIES = 3;
    static final double BASE_BACKOFF = 0.5;
    private int totalRetries = 0;

    ToolResult executeWithRetry(
            ToolFn toolFn, Map<String, String> params, String toolName,
            ContextCompactor compactor, Deque<String> history) {
        ToolResult lastResult = new ToolResult(false, "", toolName);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ToolResult result = toolFn.apply(params);
                if (result.success) return result;
                lastResult = result;
            } catch (Exception e) {
                lastResult = new ToolResult(false,
                    "Exception: " + e.getMessage(), toolName);
            }

            if (attempt < MAX_RETRIES) {
                double backoff = BASE_BACKOFF * Math.pow(2, attempt - 1);
                System.out.println("  [RETRY] " + toolName + " failed (attempt "
                    + attempt + "/" + MAX_RETRIES + "). Retrying in "
                    + String.format("%.1f", backoff) + "s...");
                try { Thread.sleep((long)(backoff * 1000)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                totalRetries++;
            }
        }

        System.out.println("  [RECOVERY] All " + MAX_RETRIES
            + " retries exhausted for " + toolName + ".");
        if (compactor != null && history != null) {
            System.out.println("  [RECOVERY] Triggering reactive compaction...");
            compactor.compact(history, "budget");
        }
        return lastResult;
    }

    int totalRetries() { return totalRetries; }
}


// ============================================================================
// ===  FROM s01: Mock LLM (the "brain")  ====================================
// ============================================================================

class AgentPlan {
    // Single tool
    boolean singleTool = false;
    String  toolName = "";
    Map<String, String> params = Map.of();

    // Multi-step plan
    boolean isPlan = false;
    String  description = "";
    List<String> steps = List.of();
    List<String> parallel = List.of();

    // Subagent
    boolean isSubagent = false;
    String  subagentTask = "";

    static AgentPlan single(String name, Map<String, String> p) {
        AgentPlan ap = new AgentPlan();
        ap.singleTool = true; ap.toolName = name; ap.params = p;
        return ap;
    }
    static AgentPlan plan(String desc, List<String> s, List<String> par) {
        AgentPlan ap = new AgentPlan();
        ap.isPlan = true; ap.description = desc; ap.steps = s; ap.parallel = par;
        return ap;
    }
    static AgentPlan subagent(String task) {
        AgentPlan ap = new AgentPlan();
        ap.isSubagent = true; ap.subagentTask = task;
        return ap;
    }
}

class MockLLM {
    AgentPlan parse(String query) {
        String q = query.trim();

        Matcher m;
        if ((m = Pattern.compile("^read\\s+(\\S+)(?:\\s+.*)?$", Pattern.CASE_INSENSITIVE).matcher(q)).matches())
            return AgentPlan.single("read_file", Map.of("path", m.group(1).trim()));

        if ((m = Pattern.compile("^write\\s+(\\S+)\\s+(.+)$", Pattern.CASE_INSENSITIVE).matcher(q)).matches())
            return AgentPlan.single("write_file",
                Map.of("path", m.group(1), "content", m.group(2)));

        if ((m = Pattern.compile("^edit\\s+(\\S+)\\s+(.+?)\\s+->\\s+(.+)$",
                Pattern.CASE_INSENSITIVE).matcher(q)).matches())
            return AgentPlan.single("edit_file",
                Map.of("path", m.group(1), "old", m.group(2), "new", m.group(3)));

        if ((m = Pattern.compile("^(?:find|glob|search)\\s+(.+)$",
                Pattern.CASE_INSENSITIVE).matcher(q)).matches())
            return AgentPlan.single("glob", Map.of("pattern", m.group(1).trim()));

        if ((m = Pattern.compile("^bash\\s+(.+)$",
                Pattern.CASE_INSENSITIVE).matcher(q)).matches())
            return AgentPlan.single("bash", Map.of("cmd", m.group(1).trim()));

        String ql = q.toLowerCase();

        if (ql.contains("build") || ql.contains("create") || ql.contains("complex")) {
            var steps = planBuildTask(q);
            var parallel = steps.stream()
                .filter(s -> s.toLowerCase().contains("find")
                          || s.toLowerCase().contains("search"))
                .collect(Collectors.toList());
            return AgentPlan.plan(q, steps, parallel);
        }

        if (ql.contains("subagent") || ql.contains("analyze")) {
            String task = q.replaceAll("(?i)^subagent\\s+", "");
            return AgentPlan.subagent(task);
        }

        if (ql.contains("error") || ql.contains("fail"))
            return AgentPlan.single("bash", Map.of("cmd", "exit 1 --error test"));

        return AgentPlan.single("bash",
            Map.of("cmd", "echo 'echo: " + q.substring(0, Math.min(60, q.length())) + "'"));
    }

    private static List<String> planBuildTask(String query) {
        String ql = query.toLowerCase();
        if (ql.contains("calculator"))
            return List.of(
                "Read demo/src/main.py for reference",
                "Write Calculator.java with add/subtract/multiply/divide",
                "Write TestCalculator.java with unit tests",
                "Find *.java to verify all files created",
                "Bash: javac Calculator.java",
                "Edit Calculator.java to add modulo operation"
            );
        if (ql.contains("web") || ql.contains("server"))
            return List.of(
                "Read demo/config.json for configuration",
                "Write Server.java with HTTP handler",
                "Write Routes.java with URL routing",
                "Find *.java to verify structure",
                "Bash: javac Server.java Routes.java"
            );
        return List.of(
            "Step 1: Plan architecture for '" + query.substring(0, Math.min(30, query.length())) + "...'",
            "Step 2: Write core implementation",
            "Step 3: Write tests",
            "Step 4: Verify with bash command",
            "Step 5: Edit for polish"
        );
    }
}


// ============================================================================
// ===  FROM s01: Full Agent (the ONE loop)  =================================
// ============================================================================

class FullAgent {
    // s02: Components
    private final VirtualFS         fs = new VirtualFS();
    private final Map<String, ToolFn> toolMap;
    // s03
    private final PermissionGate    permissions;
    // s04
    private final TodoManager       todo = new TodoManager();
    // s05
    private final SubagentManager   subagents = new SubagentManager(fs);
    // s06
    private final ContextCompactor  compactor = new ContextCompactor();
    // s07
    private final ErrorRecovery     errorHandler = new ErrorRecovery();
    // s01
    private final MockLLM           llm = new MockLLM();

    // Context state
    private final Deque<String> history = new ArrayDeque<>();
    private int budgetUsed = 0;
    private int stepCount  = 0;

    FullAgent(boolean autoApprove) {
        permissions = new PermissionGate(autoApprove);
        toolMap = ToolSet.buildToolMap(fs);
    }

    void run(List<String> queries) {
        System.out.println("=" .repeat(60));
        System.out.println("  s08 FULL AGENT --- 'Many mechanisms, one loop.'");
        System.out.println("=" .repeat(60));
        System.out.println("  s01 Agent Loop       | s05 Subagents");
        System.out.println("  s02 Multi-Tool       | s06 Compaction");
        System.out.println("  s03 Permissions      | s07 Error Recovery");
        System.out.println("  s04 Todo Write       |");
        System.out.println("=" .repeat(60));
        System.out.println("  Mock file-system: " + fs.fileCount() + " files");
        System.out.println("  Auto-approve: yes\n");

        // ========== THE ONE LOOP (s01) ==========
        for (String query : queries) {
            stepCount++;

            // s06: Check context budget
            if (compactor.needsCompaction(history, budgetUsed)) {
                String layer = budgetUsed > 50 * 50 ? "budget" : "micro";
                compactor.compact(history, layer);
            }

            // s04: Nag about stale todos
            String nag = todo.nagIfStale();
            if (!nag.isEmpty()) System.out.println("\n" + nag);

            System.out.println("-".repeat(58));
            System.out.println("  STEP " + stepCount + " | Query: "
                + query.substring(0, Math.min(70, query.length())));
            System.out.println("-".repeat(58));

            // Parse through LLM
            AgentPlan plan = llm.parse(query);

            if (plan.isSubagent) {
                // s05: Subagent
                subagents.spawn(plan.subagentTask);
                for (var r : subagents.collect()) {
                    history.add("[subagent] " + r.output().substring(0,
                        Math.min(80, r.output().length())));
                    budgetUsed += r.output().length();
                    System.out.println("  [SUBAGENT RESULT] " + trunc(r.output(), 100));
                }
            }
            else if (plan.isPlan) {
                processPlan(plan);
            }
            else if (plan.singleTool) {
                executeOneTool(plan);
            }

            System.out.println("\n  " + todo.summary());
            System.out.println("  Context: " + history.size()
                + " entries, budget ~" + budgetUsed + " tokens");
        }

        // Summary
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  AGENT FINISHED");
        System.out.println("  Total steps: " + stepCount);
        System.out.println("  Permission blocks: " + permissions.blockCount());
        System.out.println("  Compactions: " + compactor.compactionCount());
        System.out.println("  Retries: " + errorHandler.totalRetries());
        System.out.println("  " + todo.summary());
        System.out.println("=".repeat(60));
    }

    // ---------------------------------------------------------------
    private void executeOneTool(AgentPlan plan) {
        // s03: Permission check
        var check = permissions.check(plan.toolName, plan.params);
        if (!check.approved()) {
            System.out.println("  [BLOCKED] " + plan.toolName + ": " + check.reason());
            return;
        }

        ToolFn fn = toolMap.get(plan.toolName);
        if (fn == null) {
            System.out.println("  [ERROR] Unknown tool: " + plan.toolName);
            return;
        }

        // s07: Execute with retry
        ToolResult result = errorHandler.executeWithRetry(
            fn, plan.params, plan.toolName, compactor, history);

        String status = result.success ? "OK" : "FAIL";
        System.out.println("  [" + status + "] " + result.toolName
            + ": " + trunc(result.output, 100));

        history.add("[" + result.toolName + "] "
            + (result.success ? "OK" : "FAIL") + ": "
            + trunc(result.output, 80));
        budgetUsed += result.output.length();
    }

    private void processPlan(AgentPlan plan) {
        // s04: Create todo items
        System.out.println("\n  [TODO] Plan created with "
            + plan.steps.size() + " steps:");
        for (int i = 0; i < plan.steps.size(); i++) {
            var item = todo.addTask(plan.steps.get(i));
            System.out.println("    " + (i + 1) + ". [" + item.id + "] "
                + plan.steps.get(i));
        }

        // s05: Spawn subagents for parallel tasks
        for (String task : plan.parallel)
            subagents.spawn(task);

        // Execute sequential steps
        for (String step : plan.steps) {
            if (plan.parallel.contains(step)) continue;
            var subPlan = llm.parse(step);
            if (subPlan.singleTool) {
                System.out.println("\n  Executing: " + step);
                executeOneTool(subPlan);
                todo.markDoneByHint(step);
            }
        }

        // s05: Collect subagent results
        for (var r : subagents.collect()) {
            history.add("[subagent] " + trunc(r.output(), 80));
            budgetUsed += r.output().length();
            todo.markDoneByHint(r.task());
        }

        // s06: Check compaction after plan
        if (compactor.needsCompaction(history, budgetUsed))
            compactor.compact(history, "micro");
    }

    private static String trunc(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}


// ============================================================================
// ===  DEMO: Main Entry Point  ==============================================
// ============================================================================

public class Main {
    public static void main(String[] args) {
        List<String> demoQueries = List.of(
            // s02: Multi-Tool
            "read demo/file1.txt",
            "write demo/hello.txt Agent says hello!",
            "read demo/hello.txt",
            "edit demo/config.json 8080 -> 9090",
            "read demo/config.json",
            "find *.py",
            "bash echo Build v1.0 complete",

            // s03: Permissions
            "bash rm -rf /important/data",
            "bash chmod 777 all_the_things",

            // s04+s05: Complex task
            "build a calculator app",

            // s07: Error recovery
            "bash exit 1 --error test",
            "bash fail --error test",

            // s05: Subagent
            "subagent analyze demo/file1.txt",
            "analyze the config file demo/config.json",

            // s06: Trigger compaction
            "read demo/huge.log",
            "bash echo 1",  "bash echo 2",  "bash echo 3",
            "bash echo 4",  "bash echo 5",  "bash echo 6",
            "bash echo 7",  "bash echo 8",  "bash echo 9",
            "bash echo 10", "bash echo 11", "bash echo 12",
            "bash echo 13", "bash echo 14", "bash echo 15",

            // Final complex task
            "build a web server"
        );

        FullAgent agent = new FullAgent(true);
        agent.run(demoQueries);
    }
}
