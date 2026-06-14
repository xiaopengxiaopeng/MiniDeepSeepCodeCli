/**
 * s05 Subagent — C++17
 * Build on s04's todo system. Adds `task` tool that spawns a subagent
 * with clean context isolation. The subagent runs its own agent loop with
 * a limited tool set, cannot recurse, and returns only its final text summary.
 *
 * Single-file, self-contained, compilable with:
 *   g++ -std=c++17 -o s05_subagent main.cpp
 */

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <functional>
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <stdexcept>
#include <memory>

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
        for (const auto& t : todos_) {
            if (t.status != TodoStatus::COMPLETED) return true;
        }
        return false;
    }

    std::string get_nag_message() const {
        int pending = 0;
        for (const auto& t : todos_) {
            if (t.status != TodoStatus::COMPLETED) pending++;
        }
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
// Permission system (from s03)
// =============================================================================

enum class PermissionLevel { ALLOW, ASK, DENY };

class PermissionSystem {
public:
    PermissionSystem(bool interactive = false) : interactive_(interactive) {
        rules_["read_file"] = PermissionLevel::ALLOW;
        rules_["write_file"] = PermissionLevel::ASK;
        rules_["execute_command"] = PermissionLevel::ASK;
        rules_["todo_write"] = PermissionLevel::ALLOW;
        rules_["task"] = PermissionLevel::ALLOW;  // delegation always allowed
    }

    PermissionLevel check(const std::string& tool_name) const {
        auto it = rules_.find(tool_name);
        if (it != rules_.end()) return it->second;
        return PermissionLevel::ASK;
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
// Mock file system
// =============================================================================

std::map<std::string, std::string> MOCK_FS = {
    {"/project/main.py", "print('hello world')\n"},
    {"/project/config.json", "{\"version\": \"1.0\"}\n"},
    {"/project/utils.py", "def add(a, b):\n    return a + b\n"},
};


// =============================================================================
// Tool execution (shared between main and subagent)
// =============================================================================

std::string safe_read_file(const std::string& path) {
    auto it = MOCK_FS.find(path);
    if (it != MOCK_FS.end()) return "[read_file result]\n" + it->second;
    return "[read_file error] File not found: " + path;
}

std::string safe_write_file(const std::string& path, const std::string& content) {
    MOCK_FS[path] = content;
    return "[write_file result] Written " + std::to_string(content.size()) + " bytes to " + path;
}

std::string safe_execute_command(const std::string& cmd) {
    if (cmd.find("ls") == 0) {
        if (cmd.find("/project") != std::string::npos) {
            return "[execute_command result]\nmain.py\nconfig.json\nutils.py";
        }
        return "[execute_command result]\nfile1.txt\nfile2.txt";
    }
    if (cmd.find("python") != std::string::npos && cmd.find("main.py") != std::string::npos) {
        return "[execute_command result]\nhello world";
    }
    return "[execute_command result]\n(executed: " + cmd + ")";
}

std::string safe_glob(const std::string& pattern) {
    if (pattern.find("*.py") != std::string::npos) {
        return "[glob result]\nmain.py\nutils.py";
    }
    if (pattern.find("*.json") != std::string::npos) {
        return "[glob result]\nconfig.json";
    }
    return "[glob result]\n(no matches)";
}

std::string safe_edit(const std::string& file_path,
                      const std::string& old_string,
                      const std::string& new_string) {
    auto it = MOCK_FS.find(file_path);
    if (it == MOCK_FS.end()) return "[edit error] File not found: " + file_path;

    size_t pos = it->second.find(old_string);
    if (pos == std::string::npos) return "[edit error] old_string not found in " + file_path;

    it->second.replace(pos, old_string.size(), new_string);
    return "[edit result] Applied edit to " + file_path;
}


// =============================================================================
// Simple JSON parsing (handles string values and arrays)
// =============================================================================

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

std::string get_param(const ParamMap& params, const std::string& key) {
    auto it = params.find(key);
    return it != params.end() ? it->second : "";
}

std::vector<TodoItem> parse_todo_items(const std::string& raw_array) {
    std::vector<TodoItem> items;
    size_t pos = 0;
    while (pos < raw_array.size()) {
        auto content_key = raw_array.find("\"content\"", pos);
        if (content_key == std::string::npos) break;
        auto content_val_start = raw_array.find('"', content_key + 10);
        if (content_val_start == std::string::npos) break;
        auto content_val_end = raw_array.find('"', content_val_start + 1);
        if (content_val_end == std::string::npos) break;
        std::string content = raw_array.substr(content_val_start + 1, content_val_end - content_val_start - 1);

        auto status_key = raw_array.find("\"status\"", content_val_end);
        if (status_key == std::string::npos) break;
        auto status_val_start = raw_array.find('"', status_key + 9);
        if (status_val_start == std::string::npos) break;
        auto status_val_end = raw_array.find('"', status_val_start + 1);
        if (status_val_end == std::string::npos) break;
        std::string status_str = raw_array.substr(status_val_start + 1, status_val_end - status_val_start - 1);

        TodoItem item;
        item.content = content;
        item.status = todo_status_from_string(status_str);
        items.push_back(item);
        pos = status_val_end + 1;
    }
    return items;
}


// =============================================================================
// Message and tool call representation
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
// Mock LLM (pre-scripted)
// =============================================================================

struct ScriptedTurn {
    std::string tool_name;
    std::string params_json;
    bool is_tool_call() const { return !tool_name.empty(); }
};

using Conversation = std::vector<ScriptedTurn>;

// Main agent conversation
Conversation build_main_conversation() {
    return {
        // Turn 1: Plan
        {"todo_write",
         R"({"items":[{"content":"Analyze the /project directory structure","status":"pending"},{"content":"Refactor utils.py: add multiply function","status":"pending"}]})"},
        // Turn 2: Mark first in_progress
        {"todo_write",
         R"({"items":[{"content":"Analyze the /project directory structure","status":"in_progress"},{"content":"Refactor utils.py: add multiply function","status":"pending"}]})"},
        // Turn 3: Delegate "analyze directory" to subagent
        {"task",
         R"({"description":"Analyze project structure","prompt":"List all Python files in /project, read each one, and summarize what functions they contain."})"},
        // Turn 4: Mark analysis done, start refactor
        {"todo_write",
         R"({"items":[{"content":"Analyze the /project directory structure","status":"completed"},{"content":"Refactor utils.py: add multiply function","status":"in_progress"}]})"},
        // Turn 5: Delegate refactor to subagent
        {"task",
         R"({"description":"Add multiply to utils","prompt":"Read /project/utils.py, then use edit to add a multiply(a, b) function that returns a * b."})"},
        // Turn 6: All done
        {"todo_write",
         R"({"items":[{"content":"Analyze the /project directory structure","status":"completed"},{"content":"Refactor utils.py: add multiply function","status":"completed"}]})"},
        // Turn 7: Done
        {"", ""},
    };
}

std::vector<std::string> MAIN_TEXT_RESPONSES = {
    "Let me plan this first.",
    "I'll start by understanding the project structure.",
    "Good, the subagent found main.py and utils.py. Now let me refactor utils.py.",
    "Both subagents completed successfully. Let me verify the results.",
    "All tasks are complete!",
};

// Subagent conversations
Conversation build_subagent_conversation(const std::string& description) {
    if (description == "Analyze project structure") {
        return {
            {"glob", R"({"pattern":"*.py"})"},
            {"read_file", R"({"path":"/project/main.py"})"},
            {"read_file", R"({"path":"/project/utils.py"})"},
            {"", ""},  // final text summary
        };
    }
    if (description == "Add multiply to utils") {
        return {
            {"read_file", R"({"path":"/project/utils.py"})"},
            {"edit",
             R"({"file_path":"/project/utils.py","old_string":"def add(a, b):\n    return a + b","new_string":"def add(a, b):\n    return a + b\n\ndef multiply(a, b):\n    return a * b"})"},
            {"write_file",
             R"({"path":"/project/utils.py","content":"def add(a, b):\n    return a + b\n\ndef multiply(a, b):\n    return a * b\n"})"},
            {"", ""},  // final text summary
        };
    }
    return {{"", ""}};
}

std::vector<std::string> SUBAGENT_TEXT_RESPONSES = {
    "Let me find all Python files first.",
    "Reading the file contents now.",
    "I'll add the multiply function to utils.py.",
    "Task complete.",
    "Here's a summary of what I did.",
};

std::map<std::string, std::string> SUBAGENT_FINAL_RESPONSES = {
    {"Analyze project structure",
     "[subagent summary] Found 2 Python files in /project:\n"
     "  - main.py: contains print('hello world')\n"
     "  - utils.py: contains function add(a, b)"},
    {"Add multiply to utils",
     "[subagent summary] Added multiply(a, b) function to /project/utils.py. "
     "File now contains add() and multiply() functions."},
};

Message mock_llm_call(const Conversation& conv, int turn,
                      const std::vector<std::string>& text_responses) {
    Message msg;
    msg.role = "assistant";

    if (turn >= static_cast<int>(conv.size())) {
        msg.content = "All done! Let me know if you need anything else.";
        return msg;
    }

    const auto& entry = conv[turn];

    if (!entry.is_tool_call()) {
        int idx = std::min(turn / 3, static_cast<int>(text_responses.size()) - 1);
        msg.content = text_responses[idx];
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
// Subagent loop (NEW in s05)
// =============================================================================

std::string run_subagent(const std::string& description, const std::string& prompt) {
    const int SUBAGENT_MAX_TURNS = 15;
    auto conversation = build_subagent_conversation(description);

    std::vector<Message> messages;

    Message sys;
    sys.role = "system";
    sys.content =
        "You are a subagent. You have access to a limited set of tools "
        "(read_file, write_file, execute_command, glob, edit). "
        "Work efficiently — you have limited turns. "
        "When your task is complete, provide a concise text summary of your results.";
    messages.push_back(sys);

    Message usr;
    usr.role = "user";
    usr.content = "Task: " + prompt;
    messages.push_back(usr);

    int sub_turn = 0;

    for (int agent_round = 1; agent_round <= SUBAGENT_MAX_TURNS; agent_round++) {
        Message response = mock_llm_call(conversation, sub_turn, SUBAGENT_TEXT_RESPONSES);
        sub_turn++;

        if (!response.content.empty()) {
            std::cout << "  [subagent] " << response.content << "\n";
        }
        messages.push_back(response);

        if (response.tool_calls.empty()) {
            // Text-only response — final summary
            auto it = SUBAGENT_FINAL_RESPONSES.find(description);
            if (it != SUBAGENT_FINAL_RESPONSES.end()) return it->second;
            return response.content;
        }

        // Execute tool calls
        for (const auto& tc : response.tool_calls) {
            std::cout << "  [subagent:tool] " << tc.function_name << "\n";

            auto params = parse_simple_json(tc.arguments);
            std::string result;

            if (tc.function_name == "read_file") {
                result = safe_read_file(get_param(params, "path"));
            } else if (tc.function_name == "write_file") {
                result = safe_write_file(get_param(params, "path"), get_param(params, "content"));
            } else if (tc.function_name == "execute_command") {
                result = safe_execute_command(get_param(params, "command"));
            } else if (tc.function_name == "glob") {
                result = safe_glob(get_param(params, "pattern"));
            } else if (tc.function_name == "edit") {
                result = safe_edit(get_param(params, "file_path"),
                                   get_param(params, "old_string"),
                                   get_param(params, "new_string"));
            } else {
                result = "[error] Subagent cannot use tool: " + tc.function_name;
            }

            Message tool_msg;
            tool_msg.role = "tool";
            tool_msg.tool_call_id = tc.id;
            tool_msg.content = result;
            messages.push_back(tool_msg);
        }
    }

    // Exceeded max turns
    for (auto it = messages.rbegin(); it != messages.rend(); ++it) {
        if (it->role == "assistant" && !it->content.empty()) return it->content;
    }
    return "[subagent error] Exceeded max turns with no output";
}


// =============================================================================
// Tool registry (with task handler)
// =============================================================================

class ToolRegistry {
public:
    ToolRegistry(TodoManager& tm, PermissionSystem& ps,
                 std::function<std::string(const std::string&, const std::string&)> spawn)
        : todo_manager_(tm), perm_sys_(ps), spawn_subagent_(spawn) {}

    std::string execute(const std::string& tool_name, const std::string& params_json) {
        auto params = parse_simple_json(params_json);

        if (!perm_sys_.request_approval(tool_name, params_json)) {
            return "[permission denied]";
        }

        if (tool_name == "read_file") {
            return safe_read_file(get_param(params, "path"));
        }
        if (tool_name == "write_file") {
            return safe_write_file(get_param(params, "path"), get_param(params, "content"));
        }
        if (tool_name == "execute_command") {
            return safe_execute_command(get_param(params, "command"));
        }
        if (tool_name == "todo_write") {
            return handle_todo_write(params);
        }
        if (tool_name == "task") {
            return handle_task(params);
        }
        return "[error] Unknown tool: " + tool_name;
    }

private:
    TodoManager& todo_manager_;
    PermissionSystem& perm_sys_;
    std::function<std::string(const std::string&, const std::string&)> spawn_subagent_;

    std::string handle_todo_write(const ParamMap& params) {
        auto items_it = params.find("items");
        if (items_it == params.end()) return "[todo_write error] Missing 'items' parameter";

        std::vector<TodoItem> items = parse_todo_items(items_it->second);
        todo_manager_.write_todos(items);

        std::ostringstream oss;
        oss << "[todo_write result]\nTask list updated:\n"
            << todo_manager_.format_todo_list() << "\n"
            << todo_manager_.get_status_summary();
        return oss.str();
    }

    std::string handle_task(const ParamMap& params) {
        std::string description = get_param(params, "description");
        std::string prompt = get_param(params, "prompt");

        std::cout << "\n  ========================================\n";
        std::cout << "  [subagent:spawn] Description: " << description << "\n";
        std::cout << "  [subagent:spawn] Prompt: " << prompt.substr(0, 100) <<
            (prompt.size() > 100 ? "..." : "") << "\n";

        std::string result = spawn_subagent_(description, prompt);

        std::cout << "  [subagent:done] Returned " << result.size() << " chars\n";
        std::cout << "  ========================================\n\n";

        return result;
    }
};


// =============================================================================
// Helper functions
// =============================================================================

void print_divider(const std::string& label = "") {
    std::cout << "\n------------------------------ " << label
              << " ------------------------------\n";
}

void print_banner() {
    std::cout << "============================================================\n";
    std::cout << "  s05 Subagent -- Build Your Own Code CLI\n";
    std::cout << "  Agent delegates to isolated subagents\n";
    std::cout << "============================================================\n\n";
}


// =============================================================================
// Main agent loop
// =============================================================================

int main() {
    print_banner();

    TodoManager todo_manager;
    PermissionSystem perm_sys(false);

    auto spawn_fn = [](const std::string& desc, const std::string& prompt) -> std::string {
        return run_subagent(desc, prompt);
    };

    ToolRegistry registry(todo_manager, perm_sys, spawn_fn);

    std::vector<Message> messages;

    Message sys;
    sys.role = "system";
    sys.content =
        "You are a coding agent with access to tools. "
        "Use todo_write to plan complex tasks before executing them. "
        "Keep your todo list updated as you work. "
        "For complex multi-step subtasks, use the task tool to spawn a subagent. "
        "The subagent will work independently and return a summary.";
    messages.push_back(sys);

    Message usr;
    usr.role = "user";
    usr.content = "Analyze the /project directory and add a multiply() function to utils.py. "
                   "Use subagents where appropriate.";
    messages.push_back(usr);

    auto main_conv = build_main_conversation();
    int llm_turn = 0;
    const int MAX_TURNS = 15;

    for (int agent_round = 1; agent_round <= MAX_TURNS; agent_round++) {
        std::ostringstream label;
        label << "Round " << agent_round;
        print_divider(label.str());

        // Nag injection (from s04)
        if (todo_manager.should_nag()) {
            std::string nag = todo_manager.get_nag_message();
            std::cout << "  [nag] INJECTING: " << nag << "\n";
            Message nag_msg;
            nag_msg.role = "system";
            nag_msg.content = nag;
            messages.push_back(nag_msg);
        }

        // Call LLM
        std::cout << "  [llm] Calling mock LLM (turn " << llm_turn << ")...\n";
        Message response = mock_llm_call(main_conv, llm_turn, MAIN_TEXT_RESPONSES);
        llm_turn++;

        if (!response.content.empty()) {
            std::cout << "  [assistant] " << response.content << "\n";
        }
        messages.push_back(response);

        // Check for tool calls
        if (response.tool_calls.empty()) {
            todo_manager.tick_round();
            std::cout << "  [status] " << todo_manager.get_status_summary() << "\n";
            continue;
        }

        // Execute tool calls
        for (const auto& tc : response.tool_calls) {
            std::cout << "  [tool_call] " << tc.function_name << "\n";

            std::string result = registry.execute(tc.function_name, tc.arguments);

            std::string display = result.substr(0, 250);
            if (result.size() > 250) display += "...";
            std::cout << "  [tool_result] " << display << "\n";

            Message tool_msg;
            tool_msg.role = "tool";
            tool_msg.tool_call_id = tc.id;
            tool_msg.content = result;
            messages.push_back(tool_msg);
        }

        todo_manager.tick_round();
        std::cout << "  [status] " << todo_manager.get_status_summary() << "\n";
    }

    print_divider("Agent finished");
    std::cout << "\nFinal todo list:\n" << todo_manager.format_todo_list() << "\n";

    std::cout << "\nFinal filesystem state:\n";
    for (const auto& [path, content] : MOCK_FS) {
        std::cout << "  " << path << ":\n" << content << "\n";
    }

    return 0;
}
