/**
 * s06 Context Compact — C++17
 * Build on s04's agent loop. Adds 3-layer compaction pipeline:
 *   1. snipCompact   — trim middle messages when > 50
 *   2. microCompact  — replace old tool results with "[compacted]"
 *   3. toolResultBudget — persist large results to disk, show preview
 *
 * Single-file, self-contained, compilable with:
 *   g++ -std=c++17 -o s06_context_compact main.cpp
 */

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <functional>
#include <sstream>
#include <fstream>
#include <algorithm>
#include <stdexcept>
#include <filesystem>

namespace fs = std::filesystem;

// =============================================================================
// Compaction Pipeline (NEW in s06)
// =============================================================================

struct Message;

// Forward declare these since they use Message
std::vector<Message> snipCompact(std::vector<Message>& messages, int max_messages = 50);
std::vector<Message> microCompact(std::vector<Message>& messages, int keep_recent = 3);
std::vector<Message> toolResultBudget(std::vector<Message>& messages, int max_chars = 30000);
std::vector<Message> run_compaction_pipeline(std::vector<Message>& messages);


// =============================================================================
// Todo system (from s04)
// =============================================================================

enum class TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED
};

std::string todo_status_to_string(TodoStatus s) {
    switch (s) {
        case TodoStatus::PENDING:    return "pending";
        case TodoStatus::IN_PROGRESS: return "in_progress";
        case TodoStatus::COMPLETED:  return "completed";
    }
    return "pending";
}

TodoStatus todo_status_from_string(const std::string& s) {
    if (s == "in_progress") return TodoStatus::IN_PROGRESS;
    if (s == "completed")  return TodoStatus::COMPLETED;
    return TodoStatus::PENDING;
}

struct TodoItem {
    std::string content;
    TodoStatus status = TodoStatus::PENDING;
};

class TodoManager {
public:
    static constexpr int NAG_THRESHOLD = 3;

    void write_todos(const std::vector<TodoItem>& items) {
        todos_ = items;
        rounds_since_last_update_ = 0;
    }

    void mark_updated() { rounds_since_last_update_ = 0; }

    void tick_round() {
        if (!todos_.empty()) rounds_since_last_update_++;
    }

    bool should_nag() const {
        if (todos_.empty()) return false;
        if (rounds_since_last_update_ < NAG_THRESHOLD) return false;
        for (const auto& t : todos_)
            if (t.status != TodoStatus::COMPLETED) return true;
        return false;
    }

    std::string get_nag_message() const {
        int pending = 0;
        for (const auto& t : todos_)
            if (t.status != TodoStatus::COMPLETED) pending++;
        std::ostringstream oss;
        oss << "[SYSTEM REMINDER] You have " << pending
            << " incomplete task(s). It has been " << rounds_since_last_update_
            << " rounds since your last todo update. Consider calling todo_write to update your plan.";
        return oss.str();
    }

    std::string format_todo_list() const {
        if (todos_.empty()) return "(no tasks)";
        std::ostringstream oss;
        for (size_t i = 0; i < todos_.size(); i++) {
            std::string marker;
            switch (todos_[i].status) {
                case TodoStatus::PENDING:    marker = "[ ]"; break;
                case TodoStatus::IN_PROGRESS: marker = "[~]"; break;
                case TodoStatus::COMPLETED:  marker = "[x]"; break;
            }
            oss << "  " << (i + 1) << ". " << marker << " " << todos_[i].content << "\n";
        }
        return oss.str();
    }

    std::string get_status_summary() const {
        int total = static_cast<int>(todos_.size());
        int done = 0, in_prog = 0;
        for (const auto& t : todos_) {
            if (t.status == TodoStatus::COMPLETED) done++;
            else if (t.status == TodoStatus::IN_PROGRESS) in_prog++;
        }
        int pending = total - done - in_prog;
        std::ostringstream oss;
        oss << "Tasks: " << total << " total, " << done << " done, "
            << in_prog << " in-progress, " << pending << " pending";
        return oss.str();
    }

    int get_rounds_since_last_update() const { return rounds_since_last_update_; }

private:
    std::vector<TodoItem> todos_;
    int rounds_since_last_update_ = 0;
};


// =============================================================================
// Permission system (from s04)
// =============================================================================

enum class PermissionLevel { ALLOW, ASK, DENY };

class PermissionSystem {
public:
    PermissionSystem(bool interactive = false) : interactive_(interactive) {
        rules_["read_file"] = PermissionLevel::ALLOW;
        rules_["write_file"] = PermissionLevel::ASK;
        rules_["execute_command"] = PermissionLevel::ASK;
        rules_["todo_write"] = PermissionLevel::ALLOW;
    }

    PermissionLevel check(const std::string& tool_name) const {
        auto it = rules_.find(tool_name);
        return it != rules_.end() ? it->second : PermissionLevel::ASK;
    }

    bool request_approval(const std::string& tool_name, const std::string& params) {
        PermissionLevel level = check(tool_name);
        if (level == PermissionLevel::ALLOW) {
            std::cout << "  [perm] AUTO-ALLOWED: " << tool_name << "\n";
            return true;
        }
        if (level == PermissionLevel::DENY) {
            std::cout << "  [perm] DENIED: " << tool_name << "\n";
            return false;
        }
        if (!interactive_) {
            std::cout << "  [perm] AUTO-ALLOWED (non-interactive): " << tool_name << "\n";
            return true;
        }
        std::string response;
        std::cout << "  [perm] Allow tool '" << tool_name << "'?\n"
                  << "    params: " << params << "\n"
                  << "    (y/n/a=always): ";
        std::getline(std::cin, response);
        if (response == "a") {
            rules_[tool_name] = PermissionLevel::ALLOW;
            std::cout << "  [perm] '" << tool_name << "' added to allowlist.\n";
            return true;
        }
        return response == "y";
    }

private:
    std::map<std::string, PermissionLevel> rules_;
    bool interactive_;
};


// =============================================================================
// Message representation
// =============================================================================

struct ToolCall {
    std::string id;
    std::string function_name;
    std::string arguments;
};

struct Message {
    std::string role;
    std::string content;
    std::vector<ToolCall> tool_calls;
    std::string tool_call_id;
};


// =============================================================================
// Mock file system
// =============================================================================

std::map<std::string, std::string> MOCK_FS = {
    {"/project/main.py", "print('hello world')\n"},
    {"/project/config.json", "{\"version\": \"1.0\"}\n"},
};


// =============================================================================
// Tool execution
// =============================================================================

std::string safe_read_file(const std::string& path) {
    auto it = MOCK_FS.find(path);
    if (it != MOCK_FS.end())
        return "[read_file result]\n" + it->second;
    if (path == "/project/large_log.txt") {
        // Deliberately large — ~45000 chars to trigger toolResultBudget
        std::string chunk = "LARGE LOG FILE — ";
        std::string large;
        for (int i = 0; i < 3000; i++) large += chunk;
        return "[read_file result]\n" + large;
    }
    return "[read_file error] File not found: " + path;
}

std::string safe_write_file(const std::string& path, const std::string& content) {
    MOCK_FS[path] = content;
    return "[write_file result] Written " + std::to_string(content.size()) + " bytes to " + path;
}

std::string safe_execute_command(const std::string& cmd) {
    if (cmd.find("ls") == 0) {
        if (cmd.find("/project") != std::string::npos)
            return "[execute_command result]\nmain.py\nconfig.json\nlarge_log.txt";
        return "[execute_command result]\nfile1.txt\nfile2.txt";
    }
    return "[execute_command result]\n(executed: " + cmd + ")";
}


// =============================================================================
// Tool registry
// =============================================================================

class ToolRegistry {
public:
    ToolRegistry(TodoManager& tm, PermissionSystem& ps)
        : todo_manager_(tm), perm_sys_(ps) {}

    std::string execute(const std::string& tool_name, const std::string& params_json) {
        auto params = parse_simple_json(params_json);
        if (!perm_sys_.request_approval(tool_name, params_json))
            return "[permission denied]";
        if (tool_name == "read_file")
            return safe_read_file(get_param(params, "path"));
        if (tool_name == "write_file")
            return safe_write_file(get_param(params, "path"), get_param(params, "content"));
        if (tool_name == "execute_command")
            return safe_execute_command(get_param(params, "command"));
        if (tool_name == "todo_write")
            return handle_todo_write(params);
        return "[error] Unknown tool: " + tool_name;
    }

private:
    TodoManager& todo_manager_;
    PermissionSystem& perm_sys_;

    using ParamMap = std::map<std::string, std::string>;

    ParamMap parse_simple_json(const std::string& json) {
        ParamMap result;
        size_t pos = 0;
        while (pos < json.size()) {
            auto key_start = json.find('"', pos);
            if (key_start == std::string::npos) break;
            auto key_end = json.find('"', key_start + 1);
            if (key_end == std::string::npos) break;
            std::string key = json.substr(key_start + 1, key_end - key_start - 1);

            auto val_start = json.find('"', key_end + 1);
            if (val_start == std::string::npos) break;

            char next_non_space = ' ';
            for (size_t i = key_end + 1; i < val_start; i++) {
                if (json[i] != ' ' && json[i] != ':' && json[i] != '\t' && json[i] != '\n') {
                    next_non_space = json[i];
                    break;
                }
            }

            if (next_non_space == '[') {
                auto arr_start = json.find('[', val_start);
                if (arr_start == std::string::npos) break;
                int depth = 0;
                size_t arr_end = arr_start;
                for (size_t i = arr_start; i < json.size(); i++) {
                    if (json[i] == '[') depth++;
                    else if (json[i] == ']') { depth--; if (depth == 0) { arr_end = i; break; } }
                }
                result[key] = json.substr(arr_start, arr_end - arr_start + 1);
                pos = arr_end + 1;
            } else {
                auto val_end = json.find('"', val_start + 1);
                if (val_end == std::string::npos) break;
                result[key] = json.substr(val_start + 1, val_end - val_start - 1);
                pos = val_end + 1;
            }
        }
        return result;
    }

    std::string get_param(const ParamMap& params, const std::string& key) const {
        auto it = params.find(key);
        return it != params.end() ? it->second : "";
    }

    std::string handle_todo_write(const ParamMap& params) {
        auto items_it = params.find("items");
        if (items_it == params.end())
            return "[todo_write error] Missing 'items' parameter";

        std::vector<TodoItem> items = parse_todo_items(items_it->second);
        todo_manager_.write_todos(items);

        std::ostringstream oss;
        oss << "[todo_write result]\nTask list updated:\n"
            << todo_manager_.format_todo_list() << "\n"
            << todo_manager_.get_status_summary();
        return oss.str();
    }

    std::vector<TodoItem> parse_todo_items(const std::string& raw_array) {
        std::vector<TodoItem> items;
        size_t pos = 0;
        while (pos < raw_array.size()) {
            auto content_key = raw_array.find("\"content\"", pos);
            if (content_key == std::string::npos) break;
            auto content_start = raw_array.find('"', content_key + 10);
            if (content_start == std::string::npos) break;
            auto content_end = raw_array.find('"', content_start + 1);
            if (content_end == std::string::npos) break;
            std::string content = raw_array.substr(content_start + 1, content_end - content_start - 1);

            auto status_key = raw_array.find("\"status\"", content_end);
            if (status_key == std::string::npos) break;
            auto status_start = raw_array.find('"', status_key + 9);
            if (status_start == std::string::npos) break;
            auto status_end = raw_array.find('"', status_start + 1);
            if (status_end == std::string::npos) break;
            std::string status_str = raw_array.substr(status_start + 1, status_end - status_start - 1);

            items.push_back({content, todo_status_from_string(status_str)});
            pos = status_end + 1;
        }
        return items;
    }
};


// =============================================================================
// Compaction functions (NEW in s06)
// =============================================================================

const fs::path COMPACT_TMP_DIR = fs::current_path() / ".opencode" / "tmp";

std::vector<Message> snipCompact(std::vector<Message>& messages, int max_messages) {
    if (static_cast<int>(messages.size()) <= max_messages)
        return messages;

    int removed_count = static_cast<int>(messages.size()) - max_messages + 1;

    Message placeholder;
    placeholder.role = "system";
    std::ostringstream oss;
    oss << "[COMPACTED] Trimmed " << removed_count
        << " middle messages to stay under the " << max_messages
        << "-message limit. First 3 and last 47 messages preserved.";
    placeholder.content = oss.str();

    std::vector<Message> result;
    // First 3
    for (int i = 0; i < 3 && i < static_cast<int>(messages.size()); i++)
        result.push_back(messages[i]);
    // Placeholder
    result.push_back(placeholder);
    // Last (max_messages - 4)
    int start = static_cast<int>(messages.size()) - (max_messages - 4);
    for (int i = start; i < static_cast<int>(messages.size()); i++)
        result.push_back(messages[i]);

    std::cout << "  [compact] snipCompact: trimmed " << removed_count
              << " middle messages (" << messages.size() << " \xe2\x86\x92 "
              << result.size() << ")\n";
    return result;
}

std::vector<Message> microCompact(std::vector<Message>& messages, int keep_recent) {
    // Find indices of all tool messages
    std::vector<int> tool_indices;
    for (int i = 0; i < static_cast<int>(messages.size()); i++) {
        if (messages[i].role == "tool")
            tool_indices.push_back(i);
    }

    if (static_cast<int>(tool_indices.size()) <= keep_recent)
        return messages;

    // Protect last keep_recent tool results
    int protected_start = static_cast<int>(tool_indices.size()) - keep_recent;
    int compacted_count = 0;

    for (int j = 0; j < protected_start; j++) {
        int idx = tool_indices[j];
        if (messages[idx].content != "[compacted]") {
            messages[idx].content = "[compacted]";
            compacted_count++;
        }
    }

    if (compacted_count > 0)
        std::cout << "  [compact] microCompact: " << compacted_count
                  << " tool result(s) compacted\n";

    return messages;
}

std::vector<Message> toolResultBudget(std::vector<Message>& messages, int max_chars) {
    std::error_code ec;
    fs::create_directories(COMPACT_TMP_DIR, ec);

    int budgeted_count = 0;

    for (auto& msg : messages) {
        if (msg.role != "tool") continue;
        if (static_cast<int>(msg.content.size()) <= max_chars) continue;

        // Persist to disk
        std::string file_id = "tool_result_" + std::to_string(budgeted_count) + ".txt";
        fs::path filepath = COMPACT_TMP_DIR / file_id;
        std::ofstream f(filepath);
        if (!f.is_open()) continue;
        f << msg.content;
        f.close();

        // Build preview
        std::string head = msg.content.substr(0, 500);
        std::string tail = msg.content.size() > 1000
            ? msg.content.substr(msg.content.size() - 500) : "";

        int line_count = 1;
        std::string combined = head + tail;
        for (char c : combined) if (c == '\n') line_count++;

        std::ostringstream preview;
        preview << "[tool_result budget]\n"
                << "Path: " << filepath.string() << "\n"
                << "Preview (" << line_count << " lines shown, full "
                << msg.content.size() << " chars saved to disk):\n"
                << head << "\n";
        if (!tail.empty())
            preview << "... (trimmed middle) ...\n" << tail << "\n";
        preview << "Size: " << msg.content.size() << " chars (full content saved to disk)";

        msg.content = preview.str();
        budgeted_count++;
    }

    if (budgeted_count > 0)
        std::cout << "  [compact] toolResultBudget: " << budgeted_count
                  << " large result(s) saved to disk\n";

    return messages;
}

std::vector<Message> run_compaction_pipeline(std::vector<Message>& messages) {
    messages = toolResultBudget(messages);
    messages = microCompact(messages);
    messages = snipCompact(messages);
    return messages;
}


// =============================================================================
// Mock LLM conversation (extended for compaction demo)
// =============================================================================

struct ScriptedTurn {
    std::string tool_name;
    std::string params_json;
    bool is_tool_call() const { return !tool_name.empty(); }
};

std::vector<ScriptedTurn> build_conversation() {
    std::vector<ScriptedTurn> turns;

    // Plan
    turns.push_back({"todo_write",
        R"({"items":[{"content":"Read and analyze all project files","status":"pending"},{"content":"Read the large log file for diagnostics","status":"pending"},{"content":"Generate a summary report","status":"pending"},{"content":"Verify everything compiles","status":"pending"}]})"});

    // Many read_file calls — build up >50 messages for snipCompact
    for (int i = 0; i < 20; i++) {
        turns.push_back({"read_file", R"({"path":"/project/main.py"})"});
        if (i % 2 == 0)
            turns.push_back({"read_file", R"({"path":"/project/config.json"})"});
    }

    // Large file read — triggers toolResultBudget
    turns.push_back({"read_file", R"({"path":"/project/large_log.txt"})"});

    // More reads after large file (so microCompact will compact old ones)
    for (int i = 0; i < 5; i++)
        turns.push_back({"read_file", R"({"path":"/project/main.py"})"});

    // Execute commands
    for (int i = 0; i < 4; i++)
        turns.push_back({"execute_command", R"({"command":"ls /project"})"});

    // Update todo
    turns.push_back({"todo_write",
        R"({"items":[{"content":"Read and analyze all project files","status":"completed"},{"content":"Read the large log file for diagnostics","status":"completed"},{"content":"Generate a summary report","status":"completed"},{"content":"Verify everything compiles","status":"completed"}]})"});

    // Final text-only
    turns.push_back({"", ""});

    return turns;
}

std::vector<std::string> TEXT_ONLY_RESPONSES = {
    "Let me check the project files to understand the codebase.",
    "Reading more files to get a complete picture of the project.",
    "I need to examine additional files for context.",
    "Let me continue reading the remaining project files.",
    "The log file is very large. I'll read it for diagnostics.",
    "Continuing my analysis of the project structure.",
    "I have enough context now. Let me generate the summary.",
    "All tasks are complete! The project files have been analyzed.",
};

Message mock_llm_call(const std::vector<ScriptedTurn>& conversation, int turn) {
    Message msg;
    msg.role = "assistant";

    if (turn >= static_cast<int>(conversation.size())) {
        msg.content = "All done! Let me know if you need anything else.";
        return msg;
    }

    const auto& entry = conversation[turn];

    if (!entry.is_tool_call()) {
        int idx = std::min(turn / 4, static_cast<int>(TEXT_ONLY_RESPONSES.size()) - 1);
        msg.content = TEXT_ONLY_RESPONSES[idx];
        return msg;
    }

    msg.content = "I'll use " + entry.tool_name + " to proceed.";
    ToolCall tc;
    tc.id = "call_" + std::to_string(turn);
    tc.function_name = entry.tool_name;
    tc.arguments = entry.params_json;
    msg.tool_calls.push_back(tc);

    return msg;
}


// =============================================================================
// Helper: print divider
// =============================================================================

void print_divider(const std::string& label = "") {
    std::cout << "\n------------------------------ " << label
              << " ------------------------------\n";
}

void print_banner() {
    std::cout << "============================================================\n";
    std::cout << "  s06 Context Compact -- Build Your Own Code CLI\n";
    std::cout << "  Agent with 3-layer compaction pipeline\n";
    std::cout << "============================================================\n\n";
}


// =============================================================================
// Main agent loop
// =============================================================================

int main() {
    print_banner();

    TodoManager todo_manager;
    PermissionSystem perm_sys(false);
    ToolRegistry registry(todo_manager, perm_sys);

    std::vector<Message> messages;

    {
        Message sys;
        sys.role = "system";
        sys.content =
            "You are a coding agent with access to tools. "
            "Use todo_write to plan complex tasks before executing them. "
            "Keep your todo list updated as you work.";
        messages.push_back(sys);
    }

    {
        Message usr;
        usr.role = "user";
        usr.content = "Analyze all project files in /project, including the large log file. Generate a summary.";
        messages.push_back(usr);
    }

    auto conversation = build_conversation();
    int llm_turn = 0;
    const int MAX_TURNS = 50;

    for (int agent_round = 1; agent_round <= MAX_TURNS; agent_round++) {
        std::ostringstream label;
        label << "Round " << agent_round;
        print_divider(label.str());

        // --- Nag injection (from s04) ---
        if (todo_manager.should_nag()) {
            std::string nag = todo_manager.get_nag_message();
            std::cout << "  [nag] INJECTING: " << nag << "\n";
            Message nag_msg;
            nag_msg.role = "system";
            nag_msg.content = nag;
            messages.push_back(nag_msg);
        }

        // ─────────────────────────────────────────
        // s06: The ONLY new line — compaction pipeline
        messages = run_compaction_pipeline(messages);
        // ─────────────────────────────────────────

        // --- Call LLM ---
        std::cout << "  [llm] Calling mock LLM (turn " << llm_turn << ")...\n";
        Message response = mock_llm_call(conversation, llm_turn);
        llm_turn++;

        if (!response.content.empty())
            std::cout << "  [assistant] " << response.content << "\n";
        messages.push_back(response);

        // --- Check for tool calls ---
        if (response.tool_calls.empty()) {
            todo_manager.tick_round();
            std::cout << "  [status] " << todo_manager.get_status_summary() << "\n";
            std::cout << "  [msg_count] " << messages.size() << " messages in history\n";
            continue;
        }

        // --- Execute tool calls ---
        for (const auto& tc : response.tool_calls) {
            std::cout << "  [tool_call] " << tc.function_name << "\n";

            std::string result = registry.execute(tc.function_name, tc.arguments);

            std::string display = result.size() > 200 ? result.substr(0, 200) + "..." : result;
            std::cout << "  [tool_result] " << display << "\n";

            Message tool_msg;
            tool_msg.role = "tool";
            tool_msg.tool_call_id = tc.id;
            tool_msg.content = result;
            messages.push_back(tool_msg);
        }

        todo_manager.tick_round();
        std::cout << "  [status] " << todo_manager.get_status_summary() << "\n";
        std::cout << "  [msg_count] " << messages.size() << " messages in history\n";
    }

    print_divider("Agent finished");
    std::cout << "\nFinal todo list:\n" << todo_manager.format_todo_list() << "\n";
    std::cout << "Final message count: " << messages.size() << "\n";

    return 0;
}
