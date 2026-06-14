// s03_permission — 3-Gate Permission Pipeline
// Builds on s02's dispatch-map agent loop.
// Adds permission checking before bash tool execution.
// C++17, single file, self-contained. Mock LLM for demonstration.
//
// Build:  g++ -std=c++17 main.cpp -o s03
//   or:   cl /std:c++17 main.cpp /Fe:s03.exe
// Run:    s03                  (interactive)
//         s03 --auto           (non-interactive, Gate 3 auto-approves)

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iostream>
#include <map>
#include <memory>
#include <regex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;

// ============================================================
// JSON helpers (minimal — just enough for the demo)
// ============================================================
struct JsonValue {
    std::string str_val;
    std::map<std::string, JsonValue> obj_val;
    std::vector<JsonValue> arr_val;

    std::string dump() const {
        if (!obj_val.empty()) {
            std::string s = "{";
            bool first = true;
            for (const auto& [k, v] : obj_val) {
                if (!first) s += ", ";
                s += "\"" + k + "\": " + v.dump();
                first = false;
            }
            s += "}";
            return s;
        }
        if (!arr_val.empty()) {
            std::string s = "[";
            for (size_t i = 0; i < arr_val.size(); ++i) {
                if (i > 0) s += ", ";
                s += arr_val[i].dump();
            }
            s += "]";
            return s;
        }
        return "\"" + str_val + "\"";
    }
};

// ============================================================
// Tool call structure
// ============================================================
struct ToolCall {
    std::string name;
    std::map<std::string, std::string> arguments;
};

struct LLMResponse {
    std::string content;
    std::vector<ToolCall> tool_calls;
};

// ============================================================
// Configuration
// ============================================================
const fs::path WORKSPACE_DIR = fs::current_path() / "workspace";

// ============================================================
// Path safety (from s02)
// ============================================================
fs::path safe_path(const std::string& filepath) {
    fs::path workspace = fs::absolute(WORKSPACE_DIR);
    fs::path target = fs::absolute(workspace / filepath);

    auto [wbeg, wend] = std::mismatch(workspace.begin(), workspace.end(),
                                       target.begin(), target.end());
    if (wbeg != workspace.end()) {
        throw std::runtime_error("Path escapes workspace: " + filepath);
    }
    return target;
}

// ============================================================
// Tool handlers (from s02)
// ============================================================
std::string run_bash(const std::map<std::string, std::string>& args) {
    std::string command = args.at("command");
    std::string full_cmd = "cd /d \"" + WORKSPACE_DIR.string() +
                           "\" && " + command + " 2>&1";
#ifdef _WIN32
    full_cmd = "cmd /c \"" + full_cmd + "\"";
#endif
    std::array<char, 4096> buffer{};
    std::string result;
#ifdef _WIN32
    std::unique_ptr<FILE, decltype(&_pclose)> pipe(
        _popen(full_cmd.c_str(), "r"), _pclose);
#else
    std::unique_ptr<FILE, decltype(&pclose)> pipe(
        popen(full_cmd.c_str(), "r"), pclose);
#endif
    if (!pipe) return "bash error: failed to execute command";
    while (fgets(buffer.data(), static_cast<int>(buffer.size()), pipe.get()) != nullptr) {
        result += buffer.data();
    }
    if (result.empty()) return "(no output)";
    // trim trailing newline
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r'))
        result.pop_back();
    return result;
}

std::string run_read_file(const std::map<std::string, std::string>& args) {
    fs::path path = safe_path(args.at("path"));
    int offset = 0;
    int limit = 2000;
    auto it = args.find("offset");
    if (it != args.end()) offset = std::stoi(it->second);
    it = args.find("limit");
    if (it != args.end()) limit = std::stoi(it->second);

    std::ifstream f(path);
    if (!f.is_open()) return "File not found: " + path.string();

    std::string line;
    std::string result;
    int lineno = 0;
    while (std::getline(f, line)) {
        ++lineno;
        if (offset > 0 && lineno < offset) continue;
        if (limit > 0 && lineno >= offset + limit) {
            result += "... (truncated)\n";
            break;
        }
        result += std::to_string(lineno) + ": " + line + "\n";
    }
    if (result.empty()) return "(empty file)";
    // trim trailing newline
    while (!result.empty() && result.back() == '\n')
        result.pop_back();
    return result;
}

std::string run_write_file(const std::map<std::string, std::string>& args) {
    fs::path path = safe_path(args.at("path"));
    std::string content = args.at("content");

    fs::create_directories(path.parent_path());
    std::ofstream f(path);
    if (!f.is_open()) return "write_file error: cannot open " + path.string();
    f << content;
    return "Written " + std::to_string(content.size()) + " bytes to " + args.at("path");
}

std::string run_glob(const std::map<std::string, std::string>& args) {
    std::string pattern = args.at("pattern");

    // Simple glob: support * and ? within a single path segment
    auto match = [](const std::string& name, const std::string& pat) -> bool {
        std::string regex_pat;
        for (char c : pat) {
            if (c == '*') regex_pat += ".*";
            else if (c == '?') regex_pat += '.';
            else if (c == '.') regex_pat += "\\.";
            else regex_pat += c;
        }
        return std::regex_match(name, std::regex(regex_pat));
    };

    struct Entry {
        std::string path;
        std::filesystem::file_time_type mtime;
    };
    std::vector<Entry> entries;

    try {
        for (const auto& entry : fs::recursive_directory_iterator(WORKSPACE_DIR)) {
            std::string rel = fs::relative(entry.path(), WORKSPACE_DIR).string();
            // Normalize backslashes
            std::replace(rel.begin(), rel.end(), '\\', '/');
            if (match(rel, pattern) || match(entry.path().filename().string(), pattern)) {
                entries.push_back({rel, entry.last_write_time()});
            }
        }
    } catch (...) {}

    if (entries.empty()) return "No files matched pattern: " + pattern;

    std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) {
        return a.mtime > b.mtime;
    });

    std::string result;
    for (const auto& e : entries)
        result += e.path + "\n";
    if (!result.empty()) result.pop_back();
    return result;
}

// s02 dispatch map
using ToolHandler = std::function<std::string(const std::map<std::string, std::string>&)>;
const std::map<std::string, ToolHandler> TOOL_HANDLERS = {
    {"bash", run_bash},
    {"read_file", run_read_file},
    {"write_file", run_write_file},
    {"glob", run_glob},
};

std::string execute_tool(const ToolCall& tc) {
    auto it = TOOL_HANDLERS.find(tc.name);
    if (it == TOOL_HANDLERS.end()) return "Unknown tool: " + tc.name;
    try {
        return it->second(tc.arguments);
    } catch (const std::exception& e) {
        return std::string("Tool '") + tc.name + "' error: " + e.what();
    }
}

// ============================================================
// Permission System (s03 — the only new code)
// ============================================================
struct PermissionResult {
    bool allowed;
    std::string reason;
};

// Gate 1: Hard deny patterns
const std::vector<std::pair<std::string, std::string>> HARD_DENY_PATTERNS = {
    {"rm\\s+-rf\\s+/", "rm -rf / (recursive force-delete root)"},
    {"rm\\s+-rf\\s+--no-preserve-root", "rm -rf --no-preserve-root"},
    {"sudo\\s+", "sudo (privilege escalation)"},
    {">\\s*/dev/sda", "overwrite /dev/sda (raw disk write)"},
    {">\\s*/dev/nvme", "overwrite NVMe device"},
    {"mkfs\\.", "mkfs (format filesystem)"},
    {"dd\\s+if=", "dd (raw disk copy)"},
    {":\\(\\)\\s*\\{\\s*:\\|:&\\s*\\};:", "fork bomb"},
    {"chmod\\s+-R\\s+777\\s+/", "chmod -R 777 /"},
    {"wget\\s+.*\\|\\s*sh", "wget piped to sh"},
    {"curl\\s+.*\\|\\s*bash", "curl piped to bash"},
    {"shutdown", "system shutdown"},
    {"reboot", "system reboot"},
    {"halt", "system halt"},
    {"poweroff", "system poweroff"},
    {"Remove-Item.*-Recurse.*-Force.*C:\\\\", "recursive force-delete on C:\\"},
};

// Gate 2: Destructive detection patterns
const std::vector<std::pair<std::string, std::string>> DESTRUCTIVE_PATTERNS = {
    {"\\brm\\b", "rm - removes files/directories"},
    {"\\bmv\\b", "mv - moves/renames files"},
    {"\\bdel\\b", "del - deletes files (Windows)"},
    {"\\berase\\b", "erase - deletes files (Windows)"},
    {"\\brmdir\\b", "rmdir - removes directories"},
    {"\\bformat\\b", "format - formats a disk"},
    {">\\s*\\S", "redirect (>) - overwrites file content"},
    {"\\bchmod\\b", "chmod - changes file permissions"},
    {"\\bchown\\b", "chown - changes file ownership"},
    {"\\bicacls\\b", "icacls - modifies ACLs (Windows)"},
    {"Remove-Item", "Remove-Item - deletes files (PowerShell)"},
    {"New-Item.*-Force", "Force flag - may overwrite existing resource"},
    {"Clear-Content", "Clear-Content - empties file contents"},
};

PermissionResult check_permission(const std::string& command, bool auto_confirm = false) {
    // ── Gate 1: Hard Deny ────────────────────────────────────
    for (const auto& [pattern, description] : HARD_DENY_PATTERNS) {
        if (std::regex_search(command, std::regex(pattern, std::regex::icase))) {
            return {false, "HARD DENY: " + description};
        }
    }

    // ── Gate 2: Destructive Detection ────────────────────────
    std::string destructive_reason;
    for (const auto& [pattern, description] : DESTRUCTIVE_PATTERNS) {
        if (std::regex_search(command, std::regex(pattern, std::regex::icase))) {
            destructive_reason = description;
            break;
        }
    }

    if (destructive_reason.empty()) {
        return {true, "OK"};
    }

    // ── Gate 3: User Confirmation ───────────────────────────
    if (auto_confirm) {
        return {true, "AUTO-APPROVED: " + destructive_reason};
    }

    std::cout << "\n============================================================\n";
    std::cout << "  DESTRUCTIVE COMMAND DETECTED: " << destructive_reason << "\n";
    std::cout << "  Command: " << command << "\n";
    std::cout << "============================================================\n";
    std::cout << "  Allow this command? [y/N]: ";

    std::string response;
    std::getline(std::cin, response);
    // Trim trailing \r (Windows)
    if (!response.empty() && response.back() == '\r') response.pop_back();

    if (response == "y" || response == "Y" || response == "yes" || response == "YES") {
        return {true, "USER CONFIRMED: " + destructive_reason};
    }
    return {false, "USER DENIED: " + destructive_reason};
}

// ============================================================
// execute_with_permission — s03 wrapper around s02's execute_tool
// ============================================================
std::string execute_with_permission(const ToolCall& tc, bool auto_confirm = false) {
    // Only bash commands go through the permission pipeline.
    if (tc.name == "bash") {
        auto it_cmd = tc.arguments.find("command");
        if (it_cmd != tc.arguments.end()) {
            auto [allowed, reason] = check_permission(it_cmd->second, auto_confirm);
            std::cout << "  Permission: " << reason << "\n";
            if (!allowed) {
                return "";
            }
        }
    }
    return execute_tool(tc);
}

// ============================================================
// Mock LLM (for demonstration)
// ============================================================
class MockLLM {
    struct ScriptedResponse {
        std::string content;
        std::vector<ToolCall> tool_calls;
    };

    std::vector<ScriptedResponse> responses;
    int call_count = 0;

public:
    MockLLM() {
        responses = {
            // Turn 1: Safe bash command — passes all gates
            {
                "Let me check what's in the temp directory.",
                {{"bash", {{"command", "echo temp-file.log  cache.db  old.dat"}}}},
            },
            // Turn 2: Destructive bash command — triggers Gate 2 + 3
            {
                "Found temp files. I'll remove the unnecessary log file.",
                {{"bash", {{"command", "rm temp-file.log"}}}},
            },
            // Turn 3: Hard-denied command — blocked at Gate 1
            {
                "To be thorough, I should clear the system cache too.",
                {{"bash", {{"command", "sudo rm -rf /var/cache"}}}},
            },
            // Turn 4: Non-bash tool — no permission check needed
            {
                "Let me save a cleanup report.",
                {{"write_file", {{"path", "cleanup_report.txt"},
                                 {"content", "Cleanup completed. Removed temp-file.log."}}}},
            },
        };
    }

    LLMResponse chat() {
        if (call_count >= static_cast<int>(responses.size())) {
            return {"Task completed.", {}};
        }
        auto& r = responses[call_count];
        ++call_count;
        return {r.content, r.tool_calls};
    }
};

// ============================================================
// Agent Loop (from s02 — UNCHANGED except the marked line)
// ============================================================
void agent_loop(const std::string& user_input, bool auto_confirm = false) {
    const int MAX_TURNS = 10;
    MockLLM llm;

    std::cout << "============================================================\n";
    std::cout << "  s03: Permission Pipeline -- 3-Gate Demo\n";
    std::cout << "============================================================\n";
    std::cout << "\n  User: " << user_input << "\n\n";

    for (int turn = 0; turn < MAX_TURNS; ++turn) {
        std::cout << "--- Turn " << (turn + 1) << " ---\n";

        auto response = llm.chat();

        if (response.tool_calls.empty()) {
            std::cout << "  Agent: " << response.content << "\n";
            break;
        }

        for (const auto& tc : response.tool_calls) {
            std::cout << "  [" << tc.name << "] {";
            bool first = true;
            for (const auto& [k, v] : tc.arguments) {
                if (!first) std::cout << ", ";
                std::cout << "\"" << k << "\": \"" << v << "\"";
                first = false;
            }
            std::cout << "}\n";

            // ─────────────────────────────────────────────────────
            // s03: The ONLY change from s02's agent loop
            // execute_tool(tc) → execute_with_permission(tc)
            // ─────────────────────────────────────────────────────
            std::string result = execute_with_permission(tc, auto_confirm);
            // ─────────────────────────────────────────────────────

            std::cout << "  Result: " << result << "\n\n";
        }
    }

    std::cout << "============================================================\n";
    std::cout << "  Agent loop finished.\n";
    std::cout << "============================================================\n";
}

// ============================================================
// Main
// ============================================================
int main(int argc, char* argv[]) {
    bool auto_confirm = false;
    std::string user_input;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--auto") {
            auto_confirm = true;
        } else {
            if (!user_input.empty()) user_input += " ";
            user_input += arg;
        }
    }

    if (user_input.empty()) {
        user_input = "clean up temp files and generate a cleanup report";
    }

    // Ensure workspace exists
    std::error_code ec;
    fs::create_directories(WORKSPACE_DIR, ec);

    agent_loop(user_input, auto_confirm);
    return 0;
}
