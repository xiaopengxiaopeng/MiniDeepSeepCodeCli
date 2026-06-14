/*
 * s08  Full Agent --- "Many mechanisms, one loop."
 * =================================================
 * C++17  single-file reference implementation.
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
 * Compile:  g++ -std=c++17 -Wall -O2 main.cpp -o agent
 * Run:      ./agent        (or agent.exe on Windows)
 */

#include <algorithm>
#include <chrono>
#include <deque>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <regex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

using namespace std::chrono_literals;

// ============================================================================
// ===  FROM s02: Mock Virtual File System  ==================================
// ============================================================================

class VirtualFS {
public:
    VirtualFS() {
        files_["demo/file1.txt"] =
            "Hello from file1!\n"
            "This is a sample file with multiple lines.\n"
            "Line 3: the agent can read this.\n"
            "Line 4: end of file.";
        files_["demo/config.json"] =
            "{\n  \"host\": \"localhost\",\n  \"port\": 8080,\n  \"debug\": true\n}";
        files_["demo/src/main.py"] =
            "def main():\n"
            "    print(\"Hello, World!\")\n\n"
            "if __name__ == '__main__':\n"
            "    main()";
        files_["demo/src/utils.py"] = "def helper():\n    return 42";
        files_["demo/huge.log"] = []() {
            std::string s;
            for (int i = 0; i < 120; ++i)
                s += "[" + std::to_string(i) + "] log entry number " +
                     std::to_string(i) + "\n";
            return s;
        }();
        files_["demo/notes.txt"] =
            "TODO: buy milk\nTODO: learn C++\nDONE: breathe";
        files_["demo/bad.sh"] = "#!/bin/bash\nrm -rf /important/data";
    }

    std::string read(const std::string& path) const {
        auto it = files_.find(path);
        return (it != files_.end()) ? it->second : "";
    }

    bool exists(const std::string& path) const {
        return files_.count(path) > 0;
    }

    void write(const std::string& path, const std::string& content) {
        files_[path] = content;
    }

    std::vector<std::string> glob(const std::string& pattern) const {
        std::vector<std::string> matches;
        // Simple glob: * -> .*
        std::string re_str = "^";
        for (char c : pattern) {
            if (c == '*') re_str += ".*";
            else if (c == '.') re_str += "\\.";
            else re_str += c;
        }
        re_str += "$";
        std::regex re(re_str);
        for (const auto& [path, _] : files_) {
            if (std::regex_match(path, re))
                matches.push_back(path);
        }
        std::sort(matches.begin(), matches.end());
        return matches;
    }

    size_t file_count() const { return files_.size(); }

private:
    std::map<std::string, std::string> files_;
};


// ============================================================================
// ===  FROM s02: Tool Definitions & Dispatch Map  ===========================
// ============================================================================

struct ToolResult {
    bool   success;
    std::string output;
    std::string tool_name;
    std::map<std::string, std::string> metadata;
};

using ToolParams = std::map<std::string, std::string>;
using ToolFn    = std::function<ToolResult(const ToolParams&)>;

// -- Tool implementations ---------------------------------------------------

ToolResult tool_read_file(VirtualFS& fs, const ToolParams& p) {
    std::string path = p.count("path") ? p.at("path") : "";
    std::string content = fs.read(path);
    if (content.empty() && !fs.exists(path))
        return {false, "ERROR: file not found: " + path, "read_file", {}};
    auto lines = std::count(content.begin(), content.end(), '\n') + 1;
    return {true, content, "read_file", {{"lines", std::to_string(lines)}}};
}

ToolResult tool_write_file(VirtualFS& fs, const ToolParams& p) {
    std::string path = p.count("path") ? p.at("path") : "";
    std::string content = p.count("content") ? p.at("content") : "";
    if (path.empty()) return {false, "ERROR: no path", "write_file", {}};
    fs.write(path, content);
    return {true, "Wrote " + std::to_string(content.size()) + " bytes to " + path,
            "write_file", {{"bytes", std::to_string(content.size())}}};
}

ToolResult tool_edit_file(VirtualFS& fs, const ToolParams& p) {
    std::string path = p.count("path") ? p.at("path") : "";
    std::string old_str = p.count("old") ? p.at("old") : "";
    std::string new_str = p.count("new") ? p.at("new") : "";
    std::string content = fs.read(path);
    if (!fs.exists(path))
        return {false, "ERROR: file not found: " + path, "edit_file", {}};
    auto pos = content.find(old_str);
    if (pos == std::string::npos)
        return {false, "ERROR: string not found in " + path, "edit_file", {}};
    content.replace(pos, old_str.size(), new_str);
    fs.write(path, content);
    return {true, "Edited " + path, "edit_file", {}};
}

ToolResult tool_glob(VirtualFS& fs, const ToolParams& p) {
    std::string pattern = p.count("pattern") ? p.at("pattern") : "*";
    auto matches = fs.glob(pattern);
    std::string out;
    for (const auto& m : matches) out += m + "\n";
    if (out.empty()) out = "(no matches)\n";
    return {true, out, "glob", {{"count", std::to_string(matches.size())}}};
}

ToolResult tool_bash(const ToolParams& p) {
    std::string cmd = p.count("cmd") ? p.at("cmd") : "";
    if (cmd.rfind("echo ", 0) == 0)
        return {true, cmd.substr(5), "bash", {}};
    if (cmd == "ls" || cmd.rfind("ls ", 0) == 0)
        return {true, "file1.txt  config.json  src/", "bash", {}};
    if (cmd.find("error") != std::string::npos || cmd == "exit 1")
        return {false, "Command failed with exit code 1", "bash", {{"exit_code", "1"}}};
    if (cmd == "pwd")
        return {true, "/home/user/project", "bash", {}};
    return {true, "(simulated) ran: " + cmd, "bash", {}};
}

// s02: The dispatch map
std::map<std::string, ToolFn> build_tool_map(VirtualFS& fs) {
    return {
        {"read_file",  [&](const ToolParams& p) { return tool_read_file(fs, p); }},
        {"write_file", [&](const ToolParams& p) { return tool_write_file(fs, p); }},
        {"edit_file",  [&](const ToolParams& p) { return tool_edit_file(fs, p); }},
        {"glob",       [&](const ToolParams& p) { return tool_glob(fs, p); }},
        {"bash",       [&](const ToolParams& p) { return tool_bash(p); }},
    };
}


// ============================================================================
// ===  FROM s03: Permission Pipeline  =======================================
// ============================================================================

class PermissionGate {
public:
    explicit PermissionGate(bool auto_approve = true)
        : auto_approve_(auto_approve), block_count_(0) {}

    struct CheckResult {
        bool approved;
        std::string reason;
    };

    CheckResult check(const std::string& tool_name, const ToolParams& params) {
        std::string cmd = (tool_name == "bash" && params.count("cmd"))
                              ? params.at("cmd") : "";

        // Layer 1: Deny list (hard block)
        for (const auto& blocked : deny_list_) {
            if (cmd.find(blocked) != std::string::npos) {
                ++block_count_;
                return {false, "BLOCKED by deny-list: matches '" + blocked + "'"};
            }
        }

        // Layer 2: Destructive pattern detection
        for (const auto& [pattern, desc] : destructive_patterns_) {
            if (std::regex_search(cmd, std::regex(pattern, std::regex::icase))) {
                if (auto_approve_)
                    return {true, "ALLOWED (auto): " + desc};
                // Layer 3: User confirmation
                std::cout << "\n  [PERMISSION] Destructive: " << desc << "\n";
                std::cout << "  Command: " << cmd.substr(0, 80) << "\n";
                std::cout << "  Allow? (y/N): ";
                std::string answer;
                std::getline(std::cin, answer);
                if (answer != "y" && answer != "Y")
                    return {false, "DENIED by user"};
                return {true, "ALLOWED by user"};
            }
        }
        return {true, "OK"};
    }

    int block_count() const { return block_count_; }

private:
    bool auto_approve_;
    int  block_count_;
    std::vector<std::string> deny_list_ = {
        "rm -rf /", "format c:", "shutdown -h", "del /f", ":(){ :|:& };:"
    };
    std::vector<std::pair<std::string, std::string>> destructive_patterns_ = {
        {R"(\brm\b)",            "file deletion"},
        {R"(\bdel\b)",           "file deletion"},
        {R"(\bdrop\s+table\b)",  "database destruction"},
        {R"(\bformat\b)",        "disk formatting"},
        {R"(\bshutdown\b)",      "system shutdown"},
        {R"(\bchmod\s+777\b)",   "permissive permissions"},
        {R"(>\s*/dev/)",         "device overwrite"},
    };
};


// ============================================================================
// ===  FROM s04: Todo Write  ================================================
// ============================================================================

struct TodoItem {
    int         id;
    std::string description;
    bool        done = false;
    double      created_at = 0.0;  // seconds since epoch
    int         nag_count = 0;
};

class TodoManager {
public:
    explicit TodoManager(double nag_sec = 30.0)
        : nag_threshold_(nag_sec), next_id_(1), last_check_(now_sec()) {}

    TodoItem& add_task(const std::string& desc) {
        items_.push_back({next_id_++, desc, false, now_sec(), 0});
        return items_.back();
    }

    void add_tasks(const std::vector<std::string>& descs) {
        for (const auto& d : descs) add_task(d);
    }

    bool mark_done(int id) {
        for (auto& item : items_)
            if (item.id == id) { item.done = true; return true; }
        return false;
    }

    bool mark_done_by_hint(const std::string& hint) {
        for (auto& item : items_)
            if (!item.done &&
                item.description.find(hint) != std::string::npos) {
                item.done = true;
                return true;
            }
        return false;
    }

    std::vector<TodoItem> pending() const {
        std::vector<TodoItem> p;
        for (const auto& item : items_)
            if (!item.done) p.push_back(item);
        return p;
    }

    bool is_all_done() const { return pending().empty(); }

    std::string nag_if_stale() {
        double now = now_sec();
        if (now - last_check_ < 5.0) return "";
        last_check_ = now;

        std::string nags;
        for (auto& item : items_) {
            if (!item.done && (now - item.created_at) > nag_threshold_
                && item.nag_count < 3) {
                ++item.nag_count;
                nags += "  [!] NAG #" + std::to_string(item.nag_count) +
                        ": '" + item.description + "' is still pending ("
                        + std::to_string(static_cast<int>(now - item.created_at))
                        + "s)\n";
            }
        }
        return nags;
    }

    std::string summary() const {
        size_t done = std::count_if(items_.begin(), items_.end(),
                                     [](const auto& i) { return i.done; });
        size_t total = items_.size();
        if (total == 0) return "(no tasks)";
        return "Todo [" + std::string(done, '#') + std::string(total - done, '-')
               + "] " + std::to_string(done) + "/" + std::to_string(total);
    }

private:
    static double now_sec() {
        using namespace std::chrono;
        return duration<double>(steady_clock::now().time_since_epoch()).count();
    }

    std::vector<TodoItem> items_;
    double nag_threshold_;
    int    next_id_;
    double last_check_;
};


// ============================================================================
// ===  FROM s05: Subagent Spawning  =========================================
// ============================================================================

struct SubagentResult {
    std::string task;
    bool        success;
    std::string output;
    int         steps_taken;
};

class Subagent {
public:
    Subagent(const std::string& task, VirtualFS& fs, int id)
        : task_(task), fs_(fs), id_(id), steps_(0) {}

    SubagentResult run() {
        context_.push_back("Subagent-" + std::to_string(id_) +
                           " starting: " + task_);

        if (task_.find("analyze") != std::string::npos ||
            task_.find("read")   != std::string::npos) {
            // Extract file path
            std::regex path_re(R"(([\w./-]+\.\w+))");
            std::smatch m;
            std::string file_path = "demo/file1.txt";
            if (std::regex_search(task_, m, path_re))
                file_path = m[1].str();

            ++steps_;
            std::string content = fs_.read(file_path);
            if (!content.empty()) {
                int lines = std::count(content.begin(), content.end(), '\n') + 1;
                context_.push_back("Read " + file_path + " (" +
                                   std::to_string(lines) + " lines)");
                return {task_, true,
                        "Analysis: " + file_path + " has " + std::to_string(lines)
                        + " lines, " + std::to_string(content.size()) + " chars.",
                        steps_};
            }
            return {task_, false, "File not found: " + file_path, steps_};
        }
        else if (task_.find("search") != std::string::npos ||
                 task_.find("find")  != std::string::npos) {
            ++steps_;
            auto matches = fs_.glob("*.py");
            return {task_, true,
                    "Found " + std::to_string(matches.size()) + " Python files",
                    steps_};
        }
        // Generic
        ++steps_;
        context_.push_back("Processing: " + task_);
        std::this_thread::sleep_for(0.1s);
        return {task_, true,
                "Subagent-" + std::to_string(id_) + " completed: " + task_, steps_};
    }

private:
    std::string task_;
    VirtualFS&  fs_;
    int         id_;
    int         steps_;
    std::vector<std::string> context_;
};

class SubagentManager {
public:
    explicit SubagentManager(VirtualFS& fs) : fs_(fs), next_id_(1) {}

    int spawn(const std::string& task) {
        int id = next_id_++;
        Subagent sub(task, fs_, id);
        std::cout << "  [SUBAGENT] Spawned subagent-" << id
                  << " for: " << task.substr(0, 50) << "...\n";
        auto result = sub.run();
        completed_.push_back(result);
        return id;
    }

    std::vector<SubagentResult> collect() {
        auto results = std::move(completed_);
        completed_.clear();
        return results;
    }

private:
    VirtualFS& fs_;
    int next_id_;
    std::vector<SubagentResult> completed_;
};


// ============================================================================
// ===  FROM s06: Context Compaction  ========================================
// ============================================================================

class ContextCompactor {
public:
    ContextCompactor(size_t soft_limit = 30, size_t hard_limit = 50)
        : soft_limit_(soft_limit), hard_limit_(hard_limit),
          compaction_count_(0), tokens_saved_(0) {}

    bool needs_compaction(const std::deque<std::string>& history,
                          size_t budget_used) const {
        return history.size() > soft_limit_ || budget_used > hard_limit_ * 50;
    }

    void compact(std::deque<std::string>& history, const std::string& layer) {
        ++compaction_count_;
        if (layer == "snip") {
            size_t target = std::max(size_t(10), soft_limit_ / 2);
            size_t removed = 0;
            while (history.size() > target) {
                history.pop_front();
                ++removed;
            }
            tokens_saved_ += removed * 50;
            std::cout << "  [COMPACT:snip] Dropped " << removed
                      << " entries. History: " << history.size() << "\n";
        }
        else if (layer == "micro") {
            size_t mid = history.size() / 2;
            std::vector<std::string> old;
            for (size_t i = 0; i < mid; ++i) {
                old.push_back(history.front());
                history.pop_front();
            }
            std::string summary = "[Summarised " + std::to_string(old.size())
                    + " entries: ";
            for (size_t i = 0; i < std::min(size_t(3), old.size()); ++i)
                summary += old[i].substr(0, 40) + "; ";
            summary += "]";
            history.push_front(summary);
            tokens_saved_ += old.size() * 40;
            std::cout << "  [COMPACT:micro] Summarised " << old.size()
                      << " entries. History: " << history.size() << "\n";
        }
        else if (layer == "budget") {
            size_t keep = std::min(size_t(5), history.size());
            std::vector<std::string> recent;
            for (size_t i = 0; i < keep; ++i) {
                recent.push_back(history.back());
                history.pop_back();
            }
            size_t old_count = history.size();
            history.clear();
            history.push_back("[BUDGET: removed " + std::to_string(old_count)
                              + " entries, keeping " + std::to_string(keep) + "]");
            for (auto it = recent.rbegin(); it != recent.rend(); ++it)
                history.push_back(*it);
            tokens_saved_ += old_count * 60;
            std::cout << "  [COMPACT:budget] Hard compaction! History: "
                      << history.size() << "\n";
        }
    }

    int compaction_count() const { return compaction_count_; }

private:
    size_t soft_limit_;
    size_t hard_limit_;
    int    compaction_count_;
    size_t tokens_saved_;
};


// ============================================================================
// ===  FROM s07: Error Recovery  ============================================
// ============================================================================

class ErrorRecovery {
public:
    static constexpr int MAX_RETRIES = 3;
    static constexpr double BASE_BACKOFF = 0.5;  // seconds

    ErrorRecovery() : total_retries_(0) {}

    ToolResult execute_with_retry(
        const ToolFn& tool_fn,
        const ToolParams& params,
        const std::string& tool_name,
        ContextCompactor* compactor = nullptr,
        std::deque<std::string>* history = nullptr)
    {
        ToolResult last_result{false, "", tool_name, {}};

        for (int attempt = 1; attempt <= MAX_RETRIES; ++attempt) {
            try {
                auto result = tool_fn(params);
                if (result.success) return result;
                last_result = result;
                recent_failures_.push_back(tool_name + ": " +
                                           result.output.substr(0, 80));
            } catch (const std::exception& e) {
                last_result = {false, std::string("Exception: ") + e.what(),
                               tool_name, {}};
                recent_failures_.push_back(tool_name + ": " + std::string(e.what()));
            }

            if (attempt < MAX_RETRIES) {
                double backoff = BASE_BACKOFF * (1 << (attempt - 1));
                std::cout << "  [RETRY] " << tool_name << " failed (attempt "
                          << attempt << "/" << MAX_RETRIES << "). "
                          << "Retrying in " << backoff << "s...\n";
                std::this_thread::sleep_for(
                    std::chrono::duration<double>(backoff));
                ++total_retries_;
            }
        }

        // All retries exhausted
        std::cout << "  [RECOVERY] All " << MAX_RETRIES
                  << " retries exhausted for " << tool_name << ".\n";
        if (compactor && history) {
            std::cout << "  [RECOVERY] Triggering reactive compaction...\n";
            compactor->compact(*history, "budget");
        }
        return last_result;
    }

    int total_retries() const { return total_retries_; }

private:
    int total_retries_;
    std::deque<std::string> recent_failures_;
};


// ============================================================================
// ===  FROM s01: Mock LLM (the "brain")  ====================================
// ============================================================================

struct AgentPlan {
    bool single_tool = false;
    std::string tool_name;
    ToolParams  params;

    bool is_plan = false;
    std::string description;
    std::vector<std::string> steps;
    std::vector<std::string> parallel;

    bool is_subagent = false;
    std::string subagent_task;
};

class MockLLM {
public:
    AgentPlan parse(const std::string& query) {
        AgentPlan plan;
        std::smatch m;

        // read <path>
        if (std::regex_match(query, m, std::regex(R"(read\s+(\S+)(?:\s+.*)?)", std::regex::icase))) {
            plan.single_tool = true;
            plan.tool_name = "read_file";
            plan.params["path"] = m[1].str();
            return plan;
        }

        // write <path> <content>
        if (std::regex_match(query, m,
                std::regex(R"(write\s+(\S+)\s+(.+))", std::regex::icase))) {
            plan.single_tool = true;
            plan.tool_name = "write_file";
            plan.params["path"] = m[1].str();
            plan.params["content"] = m[2].str();
            return plan;
        }

        // edit <path> <old> -> <new>
        if (std::regex_match(query, m,
                std::regex(R"(edit\s+(\S+)\s+(.+?)\s+->\s+(.+))", std::regex::icase))) {
            plan.single_tool = true;
            plan.tool_name = "edit_file";
            plan.params["path"] = m[1].str();
            plan.params["old"]  = m[2].str();
            plan.params["new"]  = m[3].str();
            return plan;
        }

        // find/glob/search <pattern>
        if (std::regex_match(query, m,
                std::regex(R"((?:find|glob|search)\s+(.+))", std::regex::icase))) {
            plan.single_tool = true;
            plan.tool_name = "glob";
            plan.params["pattern"] = m[1].str();
            return plan;
        }

        // bash <cmd>
        if (std::regex_match(query, m,
                std::regex(R"(bash\s+(.+))", std::regex::icase))) {
            plan.single_tool = true;
            plan.tool_name = "bash";
            plan.params["cmd"] = m[1].str();
            return plan;
        }

        // Complex multi-step task
        std::string q_lower = query;
        std::transform(q_lower.begin(), q_lower.end(), q_lower.begin(), ::tolower);
        if (q_lower.find("build") != std::string::npos ||
            q_lower.find("create") != std::string::npos ||
            q_lower.find("complex") != std::string::npos) {
            plan.is_plan = true;
            plan.description = query;
            plan.steps = plan_build_task(query);
            for (const auto& s : plan.steps) {
                std::string sl = s;
                std::transform(sl.begin(), sl.end(), sl.begin(), ::tolower);
                if (sl.find("search") != std::string::npos ||
                    sl.find("find") != std::string::npos ||
                    sl.find("glob") != std::string::npos)
                    plan.parallel.push_back(s);
            }
            return plan;
        }

        // Subagent
        std::string ql = query;
        std::transform(ql.begin(), ql.end(), ql.begin(), ::tolower);
        if (ql.find("subagent") != std::string::npos ||
            ql.find("analyze") != std::string::npos) {
            plan.is_subagent = true;
            plan.subagent_task = std::regex_replace(query,
                std::regex(R"(^subagent\s+)", std::regex::icase), "");
            return plan;
        }

        // Error recovery demo
        if (ql.find("error") != std::string::npos ||
            ql.find("fail") != std::string::npos) {
            plan.single_tool = true;
            plan.tool_name = "bash";
            plan.params["cmd"] = "exit 1 --error test";
            return plan;
        }

        // Default
        plan.single_tool = true;
        plan.tool_name = "bash";
        plan.params["cmd"] = "echo 'echo: " + query.substr(0, 60) + "'";
        return plan;
    }

private:
    static std::vector<std::string> plan_build_task(const std::string& query) {
        std::string ql = query;
        std::transform(ql.begin(), ql.end(), ql.begin(), ::tolower);
        if (ql.find("calculator") != std::string::npos) {
            return {
                "Read demo/src/main.py for reference",
                "Write calculator.cpp with add/subtract/multiply/divide",
                "Write test_calculator.cpp with unit tests",
                "Find *.cpp to verify all files created",
                "Bash: g++ calculator.cpp -o calculator",
                "Edit calculator.cpp to add modulo operation",
            };
        } else if (ql.find("web") != std::string::npos ||
                   ql.find("server") != std::string::npos) {
            return {
                "Read demo/config.json for configuration",
                "Write server.cpp with HTTP handler",
                "Write routes.cpp with URL routing",
                "Find *.cpp to verify structure",
                "Bash: g++ server.cpp routes.cpp -o server",
            };
        }
        return {
            "Step 1: Plan architecture for '" + query.substr(0, 30) + "...'",
            "Step 2: Write core implementation",
            "Step 3: Write tests",
            "Step 4: Verify with bash command",
            "Step 5: Edit for polish",
        };
    }
};


// ============================================================================
// ===  FROM s01: Full Agent (the ONE loop)  =================================
// ============================================================================

class FullAgent {
public:
    FullAgent(bool auto_approve = true)
        : permissions_(auto_approve), subagents_(fs_)
    {
        tool_map_ = build_tool_map(fs_);
    }

    void run(const std::vector<std::string>& queries) {
        std::cout << "============================================================\n";
        std::cout << "  s08 FULL AGENT --- 'Many mechanisms, one loop.'\n";
        std::cout << "============================================================\n";
        std::cout << "  s01 Agent Loop       | s05 Subagents\n";
        std::cout << "  s02 Multi-Tool       | s06 Compaction\n";
        std::cout << "  s03 Permissions      | s07 Error Recovery\n";
        std::cout << "  s04 Todo Write       |\n";
        std::cout << "============================================================\n";
        std::cout << "  Mock file-system: " << fs_.file_count() << " files\n";
        std::cout << "  Auto-approve: " << (permissions_.auto_approve_ ? "yes" : "no")
                  << "\n\n";

        // ========== THE ONE LOOP (s01) ==========
        for (const auto& query : queries) {
            ++step_count_;

            // s06: Check context budget
            if (compactor_.needs_compaction(history_, budget_used_)) {
                std::string layer = (budget_used_ > 50 * 50) ? "budget" : "micro";
                compactor_.compact(history_, layer);
            }

            // s04: Nag about stale todos
            auto nag = todo_.nag_if_stale();
            if (!nag.empty()) std::cout << "\n" << nag;

            // Display step
            std::cout << "------------------------------------------------------------\n";
            std::cout << "  STEP " << step_count_ << " | Query: "
                      << query.substr(0, 70) << "\n";
            std::cout << "------------------------------------------------------------\n";

            // Parse through LLM
            auto plan = llm_.parse(query);

            if (plan.is_subagent) {
                // s05: Subagent delegation
                subagents_.spawn(plan.subagent_task);
                auto results = subagents_.collect();
                for (const auto& r : results) {
                    history_.push_back("[subagent] " + r.output.substr(0, 80));
                    budget_used_ += r.output.size();
                    std::cout << "  [SUBAGENT RESULT] "
                              << r.output.substr(0, 100) << "\n";
                }
            }
            else if (plan.is_plan) {
                // s04+s05: Multi-step plan with todo
                process_plan(plan);
            }
            else if (plan.single_tool) {
                // s02+s03+s07: Single tool
                execute_one_tool(plan);
            }

            std::cout << "\n  " << todo_.summary() << "\n";
            std::cout << "  Context: " << history_.size() << " entries, "
                      << "budget ~" << budget_used_ << " tokens\n";
        }

        // Summary
        std::cout << "\n============================================================\n";
        std::cout << "  AGENT FINISHED\n";
        std::cout << "  Total steps: " << step_count_ << "\n";
        std::cout << "  Permission blocks: " << permissions_.block_count() << "\n";
        std::cout << "  Compactions: " << compactor_.compaction_count() << "\n";
        std::cout << "  Retries: " << error_handler_.total_retries() << "\n";
        std::cout << "  " << todo_.summary() << "\n";
        std::cout << "============================================================\n";
    }

private:
    void execute_one_tool(const AgentPlan& plan) {
        // s03: Permission check
        auto [approved, reason] = permissions_.check(plan.tool_name, plan.params);
        if (!approved) {
            std::cout << "  [BLOCKED] " << plan.tool_name << ": " << reason << "\n";
            return;
        }

        auto it = tool_map_.find(plan.tool_name);
        if (it == tool_map_.end()) {
            std::cout << "  [ERROR] Unknown tool: " << plan.tool_name << "\n";
            return;
        }

        // s07: Execute with retry
        auto result = error_handler_.execute_with_retry(
            it->second, plan.params, plan.tool_name, &compactor_, &history_);

        std::string status = result.success ? "OK" : "FAIL";
        std::cout << "  [" << status << "] " << result.tool_name
                  << ": " << result.output.substr(0, 100) << "\n";

        history_.push_back("[" + result.tool_name + "] " +
                           (result.success ? "OK" : "FAIL") + ": " +
                           result.output.substr(0, 80));
        budget_used_ += result.output.size();
    }

    void process_plan(const AgentPlan& plan) {
        // s04: Create todo items
        std::cout << "\n  [TODO] Plan created with "
                  << plan.steps.size() << " steps:\n";
        for (size_t i = 0; i < plan.steps.size(); ++i) {
            auto& item = todo_.add_task(plan.steps[i]);
            std::cout << "    " << (i + 1) << ". [" << item.id << "] "
                      << plan.steps[i] << "\n";
        }

        // s05: Spawn subagents for parallel tasks
        for (const auto& task : plan.parallel)
            subagents_.spawn(task);

        // Execute sequential steps
        for (const auto& step : plan.steps) {
            // Skip parallel tasks (handled by subagents)
            if (std::find(plan.parallel.begin(), plan.parallel.end(), step)
                != plan.parallel.end())
                continue;

            auto sub_plan = llm_.parse(step);
            if (sub_plan.single_tool) {
                std::cout << "\n  Executing: " << step << "\n";
                execute_one_tool(sub_plan);
                todo_.mark_done_by_hint(step);
            }
        }

        // s05: Collect subagent results
        auto results = subagents_.collect();
        for (const auto& r : results) {
            history_.push_back("[subagent] " + r.output.substr(0, 80));
            budget_used_ += r.output.size();
            todo_.mark_done_by_hint(r.task);
        }

        // s06: Check if compaction needed after plan execution
        if (compactor_.needs_compaction(history_, budget_used_))
            compactor_.compact(history_, "micro");
    }

    // --- Components ---
    VirtualFS         fs_;                      // s02: mock file system
    std::map<std::string, ToolFn> tool_map_;    // s02: tool dispatch
    PermissionGate    permissions_;             // s03: permission pipeline
    TodoManager       todo_;                    // s04: task planner
    SubagentManager   subagents_;              // s05: subagent manager
    ContextCompactor  compactor_;              // s06: context compactor
    ErrorRecovery     error_handler_;          // s07: error recovery
    MockLLM           llm_;                    // s01: mock LLM

    // --- Context ---
    std::deque<std::string> history_;
    size_t budget_used_ = 0;
    int    step_count_  = 0;
};


// ============================================================================
// ===  DEMO: Main Entry Point  ==============================================
// ============================================================================

int main() {
    std::vector<std::string> demo_queries = {
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
        "build a web server",
    };

    FullAgent agent(true);  // auto-approve for demo
    agent.run(demo_queries);
    return 0;
}
