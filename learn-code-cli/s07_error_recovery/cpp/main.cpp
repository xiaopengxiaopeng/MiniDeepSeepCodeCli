/**
 * s07 Error Recovery — C++17
 * Builds on s04's todo_write + permission system. Wraps the LLM call in a retry
 * loop with exponential backoff for rate limiting and server overload. Adds
 * reactive compaction on context-length errors.
 *
 * Single-file, self-contained, compilable with:
 *   g++ -std=c++17 -o s07_error_recovery main.cpp
 */

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <functional>
#include <sstream>
#include <algorithm>
#include <stdexcept>
#include <chrono>
#include <thread>
#include <cmath>

// =============================================================================
// Error types (NEW in s07)
// =============================================================================

enum class ErrorType {
    RATE_LIMIT,
    SERVER_OVERLOAD,
    CONTEXT_TOO_LONG
};

std::string error_type_to_string(ErrorType e) {
    switch (e) {
        case ErrorType::RATE_LIMIT:      return "rate_limit";
        case ErrorType::SERVER_OVERLOAD: return "server_overload";
        case ErrorType::CONTEXT_TOO_LONG: return "context_too_long";
    }
    return "unknown";
}

ErrorType error_type_from_string(const std::string& s) {
    if (s == "server_overload") return ErrorType::SERVER_OVERLOAD;
    if (s == "context_too_long") return ErrorType::CONTEXT_TOO_LONG;
    return ErrorType::RATE_LIMIT;
}

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
        case TodoStatus::PENDING:     return "pending";
        case TodoStatus::IN_PROGRESS: return "in_progress";
        case TodoStatus::COMPLETED:   return "completed";
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

    void tick_round() {
        if (!todos_.empty())
            rounds_since_last_update_++;
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
                case TodoStatus::PENDING:     marker = "[ ]"; break;
                case TodoStatus::IN_PROGRESS: marker = "[~]"; break;
                case TodoStatus::COMPLETED:   marker = "[x]"; break;
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
        std::cout << "  [perm] AUTO-ALLOWED (non-interactive): " << tool_name << "\n";
        return true;
    }

private:
    std::map<std::string, PermissionLevel> rules_;
    bool interactive_;
};

// =============================================================================
// Mock file system (from s04)
// =============================================================================

std::map<std::string, std::string> MOCK_FS = {
    {"/project/main.py", "print('hello world')\n"},
    {"/project/config.json", "{\"version\": \"1.0\"}\n"},
};

std::string safe_read_file(const std::string& path) {
    auto it = MOCK_FS.find(path);
    if (it != MOCK_FS.end())
        return "[read_file result]\n" + it->second;
    return "[read_file error] File not found: " + path;
}

std::string safe_write_file(const std::string& path, const std::string& content) {
    MOCK_FS[path] = content;
    return "[write_file result] Written " + std::to_string(content.size()) + " bytes to " + path;
}

std::string safe_execute_command(const std::string& cmd) {
    if (cmd.find("ls") == 0) {
        if (cmd.find("/project") != std::string::npos)
            return "[execute_command result]\nmain.py\nconfig.json";
        return "[execute_command result]\nfile1.txt\nfile2.txt";
    }
    return "[execute_command result]\n(executed: " + cmd + ")";
}

// =============================================================================
// Tool registry (from s04)
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
    bool is_error = false;
    std::string error_type;
    double retry_after = 0.0;
};

// =============================================================================
// Context compaction (NEW in s07)
// =============================================================================

int estimate_token_count(const std::vector<Message>& messages) {
    int total = 0;
    for (const auto& m : messages) {
        total += static_cast<int>(m.content.size());
        for (const auto& tc : m.tool_calls)
            total += static_cast<int>(tc.arguments.size());
    }
    return total / 4;  // ~4 chars per token
}

std::vector<Message> compact_messages_aggressively(const std::vector<Message>& messages) {
    if (messages.size() <= 4) return messages;

    // Separate system messages from others
    std::vector<Message> system_msgs;
    std::vector<Message> other_msgs;
    for (const auto& m : messages) {
        if (m.role == "system")
            system_msgs.push_back(m);
        else
            other_msgs.push_back(m);
    }

    const int KEEP_RECENT = 6;
    if (static_cast<int>(other_msgs.size()) > KEEP_RECENT) {
        int dropped = static_cast<int>(other_msgs.size()) - KEEP_RECENT;

        std::vector<Message> result = system_msgs;

        Message marker;
        marker.role = "system";
        std::ostringstream oss;
        oss << "[CONTEXT COMPACTED] " << dropped
            << " earlier messages were removed to recover from context-length error. "
            << "Continue from the remaining context.";
        marker.content = oss.str();
        result.push_back(marker);

        for (int i = static_cast<int>(other_msgs.size()) - KEEP_RECENT;
             i < static_cast<int>(other_msgs.size()); i++)
            result.push_back(other_msgs[i]);

        return result;
    }

    return messages;
}

// =============================================================================
// Mock LLM with error injection (NEW in s07)
// =============================================================================

struct ScriptedTurn {
    std::string tool_name;
    std::string params_json;
    bool is_tool_call() const { return !tool_name.empty(); }
};

struct ErrorPattern {
    ErrorType error_type;
    int max_failures;    // how many attempts fail before success
    std::string message;
    double retry_after;  // seconds
};

// Error patterns: turn -> pattern
std::map<int, ErrorPattern> ERROR_PATTERNS = {
    {2, {ErrorType::RATE_LIMIT, 1,
         "Rate limit exceeded. Please retry after 2 seconds.", 2.0}},
    {5, {ErrorType::CONTEXT_TOO_LONG, 1,
         "Context length exceeds the model's maximum of 4096 tokens.", 0.0}},
    {7, {ErrorType::SERVER_OVERLOAD, 2,
         "Server is overloaded. Please try again later.", 0.0}},
};

std::vector<ScriptedTurn> build_conversation() {
    return {
        // Turn 0: Plan
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"pending"},{"content":"Read the config.json file","status":"pending"},{"content":"Add a greet() function to main.py","status":"pending"},{"content":"Run the updated script to verify","status":"pending"}]})"},

        // Turn 1: Update plan
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"in_progress"},{"content":"Read the config.json file","status":"pending"},{"content":"Add a greet() function to main.py","status":"pending"},{"content":"Run the updated script to verify","status":"pending"}]})"},

        // Turn 2: read_file — will trigger RATE_LIMIT!
        {"read_file", R"({"path":"/project/main.py"})"},

        // Turn 3: Mark first done
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"completed"},{"content":"Read the config.json file","status":"in_progress"},{"content":"Add a greet() function to main.py","status":"pending"},{"content":"Run the updated script to verify","status":"pending"}]})"},

        // Turn 4: read_file config
        {"read_file", R"({"path":"/project/config.json"})"},

        // Turn 5: write_file — will trigger CONTEXT_TOO_LONG!
        {"write_file",
         R"({"path":"/project/main.py","content":"def greet(name):\n    return f'Hello, {name}!'\n\nprint(greet('World'))\n"})"},

        // Turn 6: Mark write done
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"completed"},{"content":"Read the config.json file","status":"completed"},{"content":"Add a greet() function to main.py","status":"completed"},{"content":"Run the updated script to verify","status":"in_progress"}]})"},

        // Turn 7: execute_command — will trigger SERVER_OVERLOAD twice!
        {"execute_command", R"({"command":"python /project/main.py"})"},

        // Turn 8: All done
        {"todo_write",
         R"({"items":[{"content":"Read the main.py file","status":"completed"},{"content":"Read the config.json file","status":"completed"},{"content":"Add a greet() function to main.py","status":"completed"},{"content":"Run the updated script to verify","status":"completed"}]})"},

        // Turn 9: Text only
        {"", ""},
    };
}

std::vector<std::string> TEXT_ONLY_RESPONSES = {
    "Let me plan this out first.",
    "Starting with reading main.py to understand the current code.",
    "Now let me read the config to check for any settings I should preserve.",
    "Good. The config is minimal — I can proceed with adding greet().",
    "Writing the updated main.py with the greet() function.",
    "Let me verify the script runs correctly.",
    "The script executed successfully. All tasks are now complete.",
};

class MockLLM {
public:
    MockLLM() : conversation_(build_conversation()) {}

    Message call(const std::vector<Message>& messages, int turn, int attempt) {
        // Check if this turn should produce an error
        auto ep_it = ERROR_PATTERNS.find(turn);
        if (ep_it != ERROR_PATTERNS.end()) {
            const auto& ep = ep_it->second;
            if (attempt < ep.max_failures) {
                return make_error(ep.error_type, ep.message, ep.retry_after);
            }
        }
        return make_real_response(turn);
    }

    static bool is_error(const Message& msg) { return msg.is_error; }

    static ErrorType get_error_type(const Message& msg) {
        return error_type_from_string(msg.error_type);
    }

    static std::string get_error_message(const Message& msg) { return msg.content; }

    static double get_retry_after(const Message& msg) { return msg.retry_after; }

private:
    std::vector<ScriptedTurn> conversation_;

    Message make_error(ErrorType et, const std::string& message, double retry_after) {
        Message msg;
        msg.role = "assistant";
        msg.content = message;
        msg.is_error = true;
        msg.error_type = error_type_to_string(et);
        msg.retry_after = retry_after;
        return msg;
    }

    Message make_real_response(int turn) {
        Message msg;
        msg.role = "assistant";

        if (turn >= static_cast<int>(conversation_.size())) {
            msg.content = "All tasks are complete!";
            return msg;
        }

        const auto& entry = conversation_[turn];

        if (!entry.is_tool_call()) {
            int idx = std::min(turn / 2, static_cast<int>(TEXT_ONLY_RESPONSES.size()) - 1);
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
};

// =============================================================================
// Retry wrapper (NEW in s07)
// =============================================================================

const int MAX_RETRIES = 5;
const double BASE_DELAY = 0.5;  // seconds

struct RetryResult {
    Message response;
    std::vector<Message> messages;  // possibly compacted
    bool success;
};

RetryResult call_llm_with_retry(std::vector<Message> messages, int turn, MockLLM& mock_llm) {
    int attempt = 0;

    while (attempt <= MAX_RETRIES) {
        attempt++;
        Message response = mock_llm.call(messages, turn, attempt - 1);

        if (!MockLLM::is_error(response)) {
            return {response, messages, true};
        }

        ErrorType et = MockLLM::get_error_type(response);
        std::string msg = MockLLM::get_error_message(response);
        double retry_after = MockLLM::get_retry_after(response);

        if (attempt > MAX_RETRIES) {
            std::ostringstream oss;
            oss << "Max retries (" << MAX_RETRIES << ") exceeded: " << msg;
            std::cerr << "  [FATAL] " << oss.str() << "\n";
            return {response, messages, false};
        }

        double delay = BASE_DELAY * std::pow(2.0, attempt - 1);
        if (retry_after > 0.0) delay = std::max(delay, retry_after);

        // Reactive compaction on context-length errors
        if (et == ErrorType::CONTEXT_TOO_LONG) {
            size_t old_count = messages.size();
            messages = compact_messages_aggressively(messages);
            size_t new_count = messages.size();
            std::cout << "  [compact] Context too long! Compacted "
                      << old_count << " -> " << new_count << " messages\n";
            std::cout << "  [retry] " << error_type_to_string(et)
                      << " -> retrying with compacted context (attempt "
                      << attempt << "/" << MAX_RETRIES << ")...\n";
        } else {
            std::cout << "  [retry] " << error_type_to_string(et) << ": " << msg
                      << " -- backoff " << delay << "s (attempt "
                      << attempt << "/" << MAX_RETRIES << ")...\n";
        }

        std::this_thread::sleep_for(
            std::chrono::milliseconds(static_cast<int>(delay * 1000)));
    }

    return {Message(), messages, false};  // unreachable
}

// =============================================================================
// Main agent loop
// =============================================================================

void print_divider(const std::string& label = "") {
    std::cout << "\n------------------------------ " << label
              << " ------------------------------\n";
}

void print_banner() {
    std::cout << "============================================================\n";
    std::cout << "  s07 Error Recovery -- Build Your Own Code CLI\n";
    std::cout << "  Retry + Backoff + Reactive Compaction\n";
    std::cout << "============================================================\n\n";
}

int main() {
    print_banner();

    TodoManager todo_manager;
    PermissionSystem perm_sys(false);
    ToolRegistry registry(todo_manager, perm_sys);
    MockLLM mock_llm;

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
        usr.content = "Add a greet() function to /project/main.py that takes a name and returns a greeting.";
        messages.push_back(usr);
    }

    int llm_turn = 0;
    const int MAX_TURNS = 20;

    int stats_total_errors = 0;
    int stats_rate_limits = 0;
    int stats_server_overloads = 0;
    int stats_context_compactions = 0;

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

        // Call LLM with retry (NEW in s07)
        std::cout << "  [llm] Calling mock LLM (turn " << llm_turn << ")...\n";

        RetryResult result = call_llm_with_retry(messages, llm_turn, mock_llm);
        if (!result.success) {
            std::cerr << "  [FATAL] LLM call failed after all retries.\n";
            break;
        }

        Message response = result.response;
        messages = result.messages;
        llm_turn++;

        if (!response.content.empty()) {
            std::cout << "  [assistant] " << response.content << "\n";
        }
        messages.push_back(response);

        // Check for tool calls
        if (response.tool_calls.empty()) {
            todo_manager.tick_round();
            std::cout << "  [status] " << todo_manager.get_status_summary() << "\n";
            std::cout << "  [nag_counter] rounds since last todo update: "
                      << todo_manager.get_rounds_since_last_update() << "\n";
            continue;
        }

        // Execute tool calls
        for (const auto& tc : response.tool_calls) {
            std::cout << "  [tool_call] " << tc.function_name << "\n";

            std::string exec_result = registry.execute(tc.function_name, tc.arguments);

            std::string display = exec_result.substr(0, 200);
            if (exec_result.size() > 200) display += "...";
            std::cout << "  [tool_result] " << display << "\n";

            Message tool_msg;
            tool_msg.role = "tool";
            tool_msg.tool_call_id = tc.id;
            tool_msg.content = exec_result;
            messages.push_back(tool_msg);
        }

        todo_manager.tick_round();
        std::cout << "  [status] " << todo_manager.get_status_summary() << "\n";
        std::cout << "  [nag_counter] rounds since last todo update: "
                  << todo_manager.get_rounds_since_last_update() << "\n";
    }

    print_divider("Agent finished");
    std::cout << "\nFinal todo list:\n" << todo_manager.format_todo_list() << "\n";

    std::cout << "\n  Error Recovery Stats:\n";
    std::cout << "    Total errors recovered:   " << stats_total_errors << "\n";
    std::cout << "    Rate limits handled:      " << stats_rate_limits << "\n";
    std::cout << "    Server overloads handled: " << stats_server_overloads << "\n";
    std::cout << "    Context compactions:      " << stats_context_compactions << "\n";

    return 0;
}
