/**
 * s04 Todo Write — C++17
 * Build on s03's permission system. Adds todo_write tool, vector<TodoItem>,
 * and a nag reminder injected after 3 rounds without a todo update.
 *
 * Single-file, self-contained, compilable with:
 *   g++ -std=c++17 -o s04_todo_write main.cpp
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

// =============================================================================
// Todo system (NEW in s04)
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

    void mark_updated() {
        rounds_since_last_update_ = 0;
    }

    void tick_round() {
        if (!todos_.empty()) {
            rounds_since_last_update_++;
        }
    }

    bool should_nag() const {
        if (todos_.empty()) return false;
        if (rounds_since_last_update_ < NAG_THRESHOLD) return false;
        // Only nag if there are incomplete tasks worth updating
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

enum class PermissionLevel {
    ALLOW,
    ASK,
    DENY
};

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

        // ASK mode — non-interactive: auto-allow for tutorial smoothness
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
};


// =============================================================================
// Tool execution
// =============================================================================

std::string safe_read_file(const std::string& path) {
    auto it = MOCK_FS.find(path);
    if (it != MOCK_FS.end()) {
        return "[read_file result]\n" + it->second;
    }
    return "[read_file error] File not found: " + path;
}

std::string safe_write_file(const std::string& path, const std::string& content) {
    MOCK_FS[path] = content;
    return "[write_file result] Written " + std::to_string(content.size()) + " bytes to " + path;
}

std::string safe_execute_command(const std::string& cmd) {
    if (cmd.find("ls") == 0) {
        if (cmd.find("/project") != std::string::npos) {
            return "[execute_command result]\nmain.py\nconfig.json";
        }
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
        // Parse simple JSON manually (no library dependency)
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
        return "[error] Unknown tool: " + tool_name;
    }

private:
    TodoManager& todo_manager_;
    PermissionSystem& perm_sys_;

    // Minimal JSON parser for flat objects (string values only)
    using ParamMap = std::map<std::string, std::string>;

    ParamMap parse_simple_json(const std::string& json) {
        ParamMap result;
        // Extract "key": "value" pairs (handles string values and arrays for items)
        size_t pos = 0;
        while (pos < json.size()) {
            // Find next key
            auto key_start = json.find('"', pos);
            if (key_start == std::string::npos) break;
            auto key_end = json.find('"', key_start + 1);
            if (key_end == std::string::npos) break;
            std::string key = json.substr(key_start + 1, key_end - key_start - 1);

            // Find value
            auto val_start = json.find('"', key_end + 1);
            if (val_start == std::string::npos) break;

            // Check if value is a string or an array
            char next_non_space = ' ';
            for (size_t i = key_end + 1; i < val_start; i++) {
                if (json[i] != ' ' && json[i] != ':' && json[i] != '\t' && json[i] != '\n') {
                    next_non_space = json[i];
                    break;
                }
            }

            if (next_non_space == '[') {
                // Array value — capture everything between [ and ]
                auto arr_start = json.find('[', val_start);
                if (arr_start == std::string::npos) break;
                int depth = 0;
                size_t arr_end = arr_start;
                for (size_t i = arr_start; i < json.size(); i++) {
                    if (json[i] == '[') depth++;
                    else if (json[i] == ']') {
                        depth--;
                        if (depth == 0) { arr_end = i; break; }
                    }
                }
                result[key] = json.substr(arr_start, arr_end - arr_start + 1);
                pos = arr_end + 1;
            } else {
                // String value
                auto val_end = json.find('"', val_start + 1);
                if (val_end == std::string::npos) break;
                std::string value = json.substr(val_start + 1, val_end - val_start - 1);
                result[key] = value;
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
        // Parse items array from the raw JSON string stored in params
        auto items_it = params.find("items");
        if (items_it == params.end()) {
            return "[todo_write error] Missing 'items' parameter";
        }

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

        // Parse individual objects from the array: {"content":"...","status":"..."}
        size_t pos = 0;
        while (pos < raw_array.size()) {
            auto content_key = raw_array.find("\"content\"", pos);
            if (content_key == std::string::npos) break;

            // content value
            auto content_val_start = raw_array.find('"', content_key + 10);
            if (content_val_start == std::string::npos) break;
            auto content_val_end = raw_array.find('"', content_val_start + 1);
            if (content_val_end == std::string::npos) break;

            std::string content = raw_array.substr(
                content_val_start + 1, content_val_end - content_val_start - 1);

            // status value
            auto status_key = raw_array.find("\"status\"", content_val_end);
            if (status_key == std::string::npos) break;
            auto status_val_start = raw_array.find('"', status_key + 9);
            if (status_val_start == std::string::npos) break;
            auto status_val_end = raw_array.find('"', status_val_start + 1);
            if (status_val_end == std::string::npos) break;

            std::string status_str = raw_array.substr(
                status_val_start + 1, status_val_end - status_val_start - 1);

            TodoItem item;
            item.content = content;
            item.status = todo_status_from_string(status_str);
            items.push_back(item);

            pos = status_val_end + 1;
        }

        return items;
    }
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
    std::string role;   // "system", "user", "assistant", "tool"
    std::string content;
    std::vector<ToolCall> tool_calls;
    std::string tool_call_id;  // for tool messages
};


// =============================================================================
// Mock LLM conversation (pre-scripted)
// =============================================================================

// Each turn is either a tool call or text-only (content + empty tool_calls)
struct ScriptedTurn {
    std::string tool_name;
    std::string params_json;
    bool is_tool_call() const { return !tool_name.empty(); }
};

// The params_json fields use backslash-escaped quotes for C++ string literals.
// For simplicity, we'll build them programmatically.

std::vector<ScriptedTurn> build_conversation() {
    return {
        // Turn 1: Plan tasks
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"pending"},{"content":"Read the config.json file","status":"pending"},{"content":"Add a greet() function to main.py","status":"pending"},{"content":"Run the updated script to verify","status":"pending"}]})"},

        // Turn 2: Start working on first item
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"in_progress"},{"content":"Read the config.json file","status":"pending"},{"content":"Add a greet() function to main.py","status":"pending"},{"content":"Run the updated script to verify","status":"pending"}]})"},

        // Turn 3: read_file
        {"read_file", R"({"path":"/project/main.py"})"},

        // Turn 4: Mark first done, start second
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"completed"},{"content":"Read the config.json file","status":"in_progress"},{"content":"Add a greet() function to main.py","status":"pending"},{"content":"Run the updated script to verify","status":"pending"}]})"},

        // Turn 5: read_file
        {"read_file", R"({"path":"/project/config.json"})"},

        // Turn 6: Mark second done, start editing
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"completed"},{"content":"Read the config.json file","status":"completed"},{"content":"Add a greet() function to main.py","status":"in_progress"},{"content":"Run the updated script to verify","status":"pending"}]})"},

        // Turn 7: write_file
        {"write_file",
         R"({"path":"/project/main.py","content":"def greet(name):\n    return f'Hello, {name}!'\n\nprint(greet('World'))\n"})"},

        // Turns 8, 9, 10: Text-only (agent forgets to update todo)
        {"", ""},
        {"", ""},
        {"", ""},

        // Turn 11: Nagged — updates todo
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"completed"},{"content":"Read the config.json file","status":"completed"},{"content":"Add a greet() function to main.py","status":"completed"},{"content":"Run the updated script to verify","status":"in_progress"}]})"},

        // Turn 12: execute_command
        {"execute_command", R"({"command":"python /project/main.py"})"},

        // Turn 13: Final update — all done
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"completed"},{"content":"Read the config.json file","status":"completed"},{"content":"Add a greet() function to main.py","status":"completed"},{"content":"Run the updated script to verify","status":"completed"}]})"},

        // Turn 14: Done — text only
        {"", ""},
    };
}

std::vector<std::string> TEXT_ONLY_RESPONSES = {
    "I'll need to read the existing files first to understand the codebase.",
    "Found greet() already exists — let me verify the implementation is correct.",
    "The code looks good. Let me double-check the config to ensure no conflicts.",
    "All tasks are complete! The greet() function has been added and verified.",
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
        int idx = std::min(turn / 3, static_cast<int>(TEXT_ONLY_RESPONSES.size()) - 1);
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
    std::cout << "  s04 Todo Write -- Build Your Own Code CLI\n";
    std::cout << "  Agent with task planning & nag reminder\n";
    std::cout << "============================================================\n\n";
}


// =============================================================================
// Main agent loop
// =============================================================================

int main() {
    print_banner();

    TodoManager todo_manager;
    PermissionSystem perm_sys(false);  // non-interactive mode
    ToolRegistry registry(todo_manager, perm_sys);

    std::vector<Message> messages;

    // System prompt
    {
        Message sys;
        sys.role = "system";
        sys.content =
            "You are a coding agent with access to tools. "
            "Use todo_write to plan complex tasks before executing them. "
            "Keep your todo list updated as you work. "
            "When the system reminds you about stale todos, update them promptly.";
        messages.push_back(sys);
    }

    // User prompt
    {
        Message usr;
        usr.role = "user";
        usr.content = "Add a greet() function to /project/main.py that takes a name and returns a greeting.";
        messages.push_back(usr);
    }

    auto conversation = build_conversation();
    int llm_turn = 0;
    const int MAX_TURNS = 20;

    for (int agent_round = 1; agent_round <= MAX_TURNS; agent_round++) {
        std::ostringstream label;
        label << "Round " << agent_round;
        print_divider(label.str());

        // --- Nag injection (NEW in s04) ---
        if (todo_manager.should_nag()) {
            std::string nag = todo_manager.get_nag_message();
            std::cout << "  [nag] INJECTING: " << nag << "\n";
            Message nag_msg;
            nag_msg.role = "system";
            nag_msg.content = nag;
            messages.push_back(nag_msg);
        }

        // --- Call LLM ---
        std::cout << "  [llm] Calling mock LLM (turn " << llm_turn << ")...\n";
        Message response = mock_llm_call(conversation, llm_turn);
        llm_turn++;

        if (!response.content.empty()) {
            std::cout << "  [assistant] " << response.content << "\n";
        }
        messages.push_back(response);

        // --- Check for tool calls ---
        if (response.tool_calls.empty()) {
            todo_manager.tick_round();
            std::cout << "  [status] " << todo_manager.get_status_summary() << "\n";
            std::cout << "  [nag_counter] rounds since last todo update: "
                      << todo_manager.get_rounds_since_last_update() << "\n";
            continue;
        }

        // --- Execute tool calls ---
        for (const auto& tc : response.tool_calls) {
            std::cout << "  [tool_call] " << tc.function_name << "\n";

            std::string result = registry.execute(tc.function_name, tc.arguments);

            // Truncate for display
            std::string display = result.substr(0, 200);
            if (result.size() > 200) display += "...";
            std::cout << "  [tool_result] " << display << "\n";

            // Append tool result message
            Message tool_msg;
            tool_msg.role = "tool";
            tool_msg.tool_call_id = tc.id;
            tool_msg.content = result;
            messages.push_back(tool_msg);
        }

        todo_manager.tick_round();
        std::cout << "  [status] " << todo_manager.get_status_summary() << "\n";
        std::cout << "  [nag_counter] rounds since last todo update: "
                  << todo_manager.get_rounds_since_last_update() << "\n";
    }

    print_divider("Agent finished");
    std::cout << "\nFinal todo list:\n" << todo_manager.format_todo_list() << "\n";

    return 0;
}
