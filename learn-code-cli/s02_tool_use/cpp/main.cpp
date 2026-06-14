/*
 * s02: Tool Use — Dispatch Map Pattern
 * ====================================
 * Builds on s01's single bash tool. Adds read_file, write_file, edit_file, glob.
 * Introduces the TOOL_HANDLERS dispatch map (std::unordered_map) — the loop never changes.
 *
 * Compile: g++ -std=c++17 -o s02 main.cpp   (or use cl.exe /std:c++17)
 * Run:     ./s02
 */

#include <cctype>
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <functional>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>
#include <algorithm>
#include <filesystem>

namespace fs = std::filesystem;

// ── Colors ──────────────────────────────────────────────────────────────────
namespace color {
    const char* RESET   = "\033[0m";
    const char* DIM     = "\033[2m";
    const char* BOLD    = "\033[1m";
    const char* CYAN    = "\033[36m";
    const char* GREEN   = "\033[32m";
    const char* YELLOW  = "\033[33m";
    const char* RED     = "\033[31m";
    const char* MAGENTA = "\033[35m";
}

static std::string dim(const std::string& s)   { return color::DIM + s + color::RESET; }
static std::string bold(const std::string& s)  { return color::BOLD + s + color::RESET; }
static std::string cyan(const std::string& s)  { return color::CYAN + s + color::RESET; }
static std::string green(const std::string& s) { return color::GREEN + s + color::RESET; }
static std::string yellow(const std::string& s){ return color::YELLOW + s + color::RESET; }
static std::string red(const std::string& s)   { return color::RED + s + color::RESET; }
static std::string magenta(const std::string& s){ return color::MAGENTA + s + color::RESET; }

// ── Configuration ───────────────────────────────────────────────────────────
const fs::path WORKSPACE_DIR = fs::absolute(fs::path("demo_workspace"));
const int MAX_TURNS = 10;

// ── Data Types ──────────────────────────────────────────────────────────────
using Args = std::map<std::string, std::string>;
using ToolHandler = std::function<std::string(const Args&)>;

struct ToolCall {
    std::string name;
    Args arguments;
};

struct LLMResponse {
    std::string content;
    std::vector<ToolCall> tool_calls;
    bool has_tools() const { return !tool_calls.empty(); }
};

struct Message {
    std::string role;
    std::string content;
};

// ── Path Safety ─────────────────────────────────────────────────────────────
std::string safe_path(const std::string& filepath) {
    fs::path workspace = fs::absolute(WORKSPACE_DIR);
    fs::path target = fs::absolute(workspace / filepath);
    std::string ws_str = workspace.string();
    std::string tgt_str = target.string();
    // Allow the workspace root itself, otherwise must start with workspace + separator
    if (tgt_str != ws_str && tgt_str.rfind(ws_str + fs::path::preferred_separator, 0) != 0) {
        throw std::runtime_error("Path escapes workspace: " + filepath);
    }
    return target.string();
}

void ensure_parent(const std::string& filepath) {
    fs::path p(filepath);
    fs::path parent = p.parent_path();
    if (!parent.empty() && !fs::exists(parent)) {
        fs::create_directories(parent);
    }
}

// ── Tools ───────────────────────────────────────────────────────────────────
std::string run_bash(const Args& args) {
    std::string cmd;
    auto it = args.find("command");
    if (it != args.end()) cmd = it->second;

    // Quick safety check
    std::string lower = cmd;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    std::vector<std::string> dangerous = {
        "rm -rf /", "sudo", "shutdown", "reboot",
        "mkfs", "dd if=", "> /dev/sda", "format c:", "del /f /s"
    };
    for (const auto& d : dangerous) {
        if (lower.find(d) != std::string::npos) {
            return "Error: Dangerous command blocked ('" + d + "')";
        }
    }

    std::string full_cmd = "cd /d \"" + WORKSPACE_DIR.string() + "\" && " + cmd + " 2>&1";
    FILE* pipe = _popen(full_cmd.c_str(), "r");
    if (!pipe) return "Error: Failed to execute command";
    std::string result;
    char buf[4096];
    while (fgets(buf, sizeof(buf), pipe)) {
        result += buf;
    }
    int rc = _pclose(pipe);
    if (result.empty()) return "(no output)";
    if (result.back() == '\n') result.pop_back();
    if (!result.empty() && result.back() == '\r') result.pop_back();
    return result.empty() ? ("Exit code: " + std::to_string(rc)) : result;
}

std::string run_read_file(const Args& args) {
    std::string filepath;
    auto it_p = args.find("path");
    if (it_p != args.end()) filepath = it_p->second;

    int offset = 0;
    auto it_o = args.find("offset");
    if (it_o != args.end()) offset = std::stoi(it_o->second);

    int limit = -1;
    auto it_l = args.find("limit");
    if (it_l != args.end()) limit = std::stoi(it_l->second);

    try {
        std::string resolved = safe_path(filepath);
        if (!fs::exists(resolved)) return "Error: File not found: " + filepath;

        if (fs::is_directory(resolved)) {
            std::string out;
            for (const auto& e : fs::directory_iterator(resolved)) {
                if (!out.empty()) out += "\n";
                out += e.path().filename().string();
                if (e.is_directory()) out += "/";
            }
            return out;
        }

        std::ifstream f(resolved);
        std::vector<std::string> lines;
        std::string line;
        while (std::getline(f, line)) lines.push_back(line);

        std::string out;
        int end = (limit == -1) ? static_cast<int>(lines.size()) : std::min(offset + limit, static_cast<int>(lines.size()));
        for (int i = offset; i < end; ++i) {
            if (!out.empty()) out += "\n";
            out += lines[i];
        }
        if (limit != -1 && end < static_cast<int>(lines.size())) {
            out += "\n... (" + std::to_string(lines.size() - end) + " more lines)";
        }
        return out;
    } catch (const std::exception& e) {
        return std::string("Error: ") + e.what();
    }
}

std::string run_write_file(const Args& args) {
    std::string filepath, content;
    auto it_p = args.find("path");
    if (it_p != args.end()) filepath = it_p->second;
    auto it_c = args.find("content");
    if (it_c != args.end()) content = it_c->second;

    try {
        std::string resolved = safe_path(filepath);
        ensure_parent(resolved);
        std::ofstream f(resolved, std::ios::trunc);
        f << content;
        f.close();
        return "Wrote " + std::to_string(content.size()) + " bytes to " + filepath;
    } catch (const std::exception& e) {
        return std::string("Error: ") + e.what();
    }
}

std::string run_edit_file(const Args& args) {
    std::string filepath, old_string, new_string;
    bool replace_all = false;

    auto it_p = args.find("path");
    if (it_p != args.end()) filepath = it_p->second;
    auto it_o = args.find("old_string");
    if (it_o != args.end()) old_string = it_o->second;
    auto it_n = args.find("new_string");
    if (it_n != args.end()) new_string = it_n->second;
    auto it_r = args.find("replace_all");
    if (it_r != args.end()) replace_all = (it_r->second == "true" || it_r->second == "1");

    try {
        std::string resolved = safe_path(filepath);
        if (!fs::exists(resolved)) return "Error: File not found: " + filepath;

        std::ifstream in(resolved, std::ios::binary);
        std::string content((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        in.close();

        // Count occurrences
        int count = 0;
        size_t pos = 0;
        while ((pos = content.find(old_string, pos)) != std::string::npos) {
            ++count;
            pos += old_string.size();
        }

        if (count == 0) return "Error: text not found in " + filepath;
        if (!replace_all && count > 1) {
            return "Error: Found " + std::to_string(count) + " matches for old_string. Provide more context or use replace_all.";
        }

        if (replace_all) {
            size_t p = 0;
            while ((p = content.find(old_string, p)) != std::string::npos) {
                content.replace(p, old_string.size(), new_string);
                p += new_string.size();
            }
        } else {
            content.replace(content.find(old_string), old_string.size(), new_string);
        }

        std::ofstream out(resolved, std::ios::binary | std::ios::trunc);
        out << content;
        out.close();

        return "Edited " + filepath + " (" + std::to_string(replace_all ? count : 1) + " replacement(s))";
    } catch (const std::exception& e) {
        return std::string("Error: ") + e.what();
    }
}

std::string run_glob(const Args& args) {
    std::string pattern;
    auto it = args.find("pattern");
    if (it != args.end()) pattern = it->second;

    try {
        std::string full_pattern = (WORKSPACE_DIR / pattern).string();
        // Simple glob: collect all files recursively matching the pattern
        std::vector<std::pair<std::string, std::chrono::system_clock::time_point>> matches;

        // Walk the workspace directory
        for (const auto& entry : fs::recursive_directory_iterator(WORKSPACE_DIR)) {
            if (entry.is_regular_file()) {
                std::string rel_path = fs::relative(entry.path(), WORKSPACE_DIR).string();
                // Replace backslashes with forward slashes for cross-platform consistency
                std::replace(rel_path.begin(), rel_path.end(), '\\', '/');

                // Simple glob matching: treat * as wildcard
                auto match_path = [&]() -> bool {
                    // Check each path component against the pattern
                    std::string pat = pattern;
                    std::replace(pat.begin(), pat.end(), '\\', '/');
                    // Basic ** and * matching
                    size_t pi = 0, si = 0;
                    size_t pstar = std::string::npos, sstar = 0;
                    while (si < rel_path.size()) {
                        if (pi < pat.size() && (pat[pi] == rel_path[si] || pat[pi] == '?')) {
                            ++pi; ++si;
                        } else if (pi < pat.size() && pat[pi] == '*') {
                            pstar = pi;
                            sstar = si;
                            ++pi;
                        } else if (pstar != std::string::npos) {
                            pi = static_cast<size_t>(pstar) + 1;
                            sstar = ++si;
                        } else {
                            return false;
                        }
                    }
                    while (pi < pat.size() && pat[pi] == '*') ++pi;
                    return pi == pat.size();
                };

                if (match_path()) {
                    matches.emplace_back(rel_path, fs::last_write_time(entry.path()));
                }
            }
        }

        if (matches.empty()) return "(no matches)";

        // Sort by modification time (newest first)
        std::sort(matches.begin(), matches.end(),
            [](const auto& a, const auto& b) { return a.second > b.second; });

        // Filter ignored directories
        std::string result;
        for (const auto& m : matches) {
            if (m.first.find("node_modules") != std::string::npos) continue;
            if (m.first.find(".git/") != std::string::npos) continue;
            if (!result.empty()) result += "\n";
            result += m.first;
        }
        return result.empty() ? "(no matches after filtering)" : result;
    } catch (const std::exception& e) {
        return std::string("Error: ") + e.what();
    }
}

// ── Dispatch Map ────────────────────────────────────────────────────────────
const std::unordered_map<std::string, ToolHandler> TOOL_HANDLERS = {
    {"bash",      run_bash},
    {"read_file", run_read_file},
    {"write_file",run_write_file},
    {"edit_file", run_edit_file},
    {"glob",      run_glob},
};

// ── Tool Executor ───────────────────────────────────────────────────────────
std::string execute_tool(const ToolCall& tc) {
    auto it = TOOL_HANDLERS.find(tc.name);
    if (it == TOOL_HANDLERS.end()) {
        return "Error: Unknown tool '" + tc.name + "'";
    }
    try {
        return it->second(tc.arguments);
    } catch (const std::exception& e) {
        return std::string("Error: ") + e.what();
    }
}

// ── Mock LLM ────────────────────────────────────────────────────────────────
class MockLLM {
    std::vector<LLMResponse> responses_;
    size_t idx_ = 0;

public:
    MockLLM() {
        // Turn 1: glob
        {
            LLMResponse r;
            r.content = "Let me explore the workspace first.";
            r.tool_calls.push_back({"glob", {{"pattern", "**/*"}}});
            responses_.push_back(r);
        }
        // Turn 2: read_file
        {
            LLMResponse r;
            r.content = "Found some files. Let me read the greeting.";
            r.tool_calls.push_back({"read_file", {{"path", "hello.txt"}}});
            responses_.push_back(r);
        }
        // Turn 3: edit_file
        {
            LLMResponse r;
            r.content = "I see it says Hello. Let me update it.";
            r.tool_calls.push_back({"edit_file", {
                {"path", "hello.txt"},
                {"old_string", "Hello, World!"},
                {"new_string", "Hello, s02 Dispatch Map!"},
                {"replace_all", "false"},
            }});
            responses_.push_back(r);
        }
        // Turn 4: write_file
        {
            LLMResponse r;
            r.content = "Let me create a new file to demonstrate write_file.";
            r.tool_calls.push_back({"write_file", {
                {"path", "tools.txt"},
                {"content", "bash\nread_file\nwrite_file\nedit_file\nglob\n"},
            }});
            responses_.push_back(r);
        }
        // Turn 5: bash to verify
        {
            LLMResponse r;
            r.content = "Let me verify the workspace state.";
            r.tool_calls.push_back({"bash", {{"command", "echo Files: && dir /b && echo --- && type hello.txt && echo --- && type tools.txt"}}});
            responses_.push_back(r);
        }
        // Turn 6: final (no tool calls)
        {
            LLMResponse r;
            r.content = "All tools demonstrated successfully!\n"
                        "- glob: found project files\n"
                        "- read_file: read hello.txt\n"
                        "- edit_file: updated greeting text\n"
                        "- write_file: created tools.txt\n"
                        "- bash: verified workspace state\n\n"
                        "The agent loop never changed — each tool has a handler in TOOL_HANDLERS.";
            responses_.push_back(r);
        }
    }

    bool has_next() const { return idx_ < responses_.size(); }
    const LLMResponse& next() { return responses_[idx_++]; }
};

// ── Agent Loop ──────────────────────────────────────────────────────────────
std::string agent_loop(MockLLM& llm) {
    std::vector<Message> messages;
    messages.push_back({"system", "You are a coding assistant. Use tools to help the user."});
    messages.push_back({"user", "Please demonstrate all available tools on the workspace."});

    for (int turn = 0; turn < MAX_TURNS; ++turn) {
        if (!llm.has_next()) {
            std::cout << yellow("\n[Mock LLM exhausted]\n");
            break;
        }

        auto response = llm.next();

        // ─── Print assistant text ───
        if (!response.content.empty()) {
            std::cout << "\n" << cyan(bold("Assistant: ")) << response.content << "\n";
        }

        // ─── Append assistant message ───
        messages.push_back({"assistant", response.content});

        // ─── Check if done ───
        if (!response.has_tools()) {
            return response.content;
        }

        // ─── Execute tool calls ───
        for (const auto& tc : response.tool_calls) {
            std::string arg_disp;
            for (const auto& [k, v] : tc.arguments) {
                if (!arg_disp.empty()) arg_disp += ", ";
                arg_disp += k + "=" + v;
            }
            std::cout << "\n  " << yellow("[" + tc.name + "]") << " " << dim("(" + arg_disp + ")") << "\n";

            std::string result = execute_tool(tc);
            std::cout << "  " << green("> ") << result.substr(0, result.find('\n')) << "\n";

            // Show a few extra lines if available
            size_t count = 0;
            size_t pos = 0;
            while ((pos = result.find('\n', pos)) != std::string::npos) {
                ++count;
                ++pos;
                if (count > 0 && count < 4) {
                    size_t end = result.find('\n', pos);
                    std::string line = (end != std::string::npos) ? result.substr(pos, end - pos) : result.substr(pos);
                    std::cout << "    " << dim(line) << "\n";
                }
            }

            messages.push_back({"tool", result});
        }

        std::cout << dim("\n  [turn " + std::to_string(turn + 1) + "/" + std::to_string(MAX_TURNS) + " complete, looping back...]") << "\n";
    }
    return "Maximum turns reached.";
}

// ── Setup Demo Workspace ────────────────────────────────────────────────────
void setup_workspace() {
    fs::create_directories(WORKSPACE_DIR);
    {
        std::ofstream f(WORKSPACE_DIR / "hello.txt");
        f << "Hello, World!\nWelcome to s02.\n";
    }
    {
        std::ofstream f(WORKSPACE_DIR / "README.txt");
        f << "This is the demo workspace for s02 Tool Use.\n";
    }
}

// ── Main ────────────────────────────────────────────────────────────────────
int main() {
    // Enable ANSI colors on Windows
    #ifdef _WIN32
    system(""); // Enables virtual terminal processing
    #endif

    std::cout << bold(cyan("\n"
        "+==================================================+\n"
        "|  s02: Tool Use -- The Dispatch Map Pattern        |\n"
        "+==================================================+\n")) << "\n";
    std::cout << dim("Workspace: " + WORKSPACE_DIR.string()) << "\n";
    std::cout << dim("Max turns: " + std::to_string(MAX_TURNS)) << "\n";
    std::cout << "\n";

    setup_workspace();

    std::string tools_list;
    for (const auto& [name, _] : TOOL_HANDLERS) {
        if (!tools_list.empty()) tools_list += ", ";
        tools_list += name;
    }
    std::cout << magenta("TOOL_HANDLERS registered: " + tools_list) << "\n\n";

    MockLLM llm;
    std::string final = agent_loop(llm);

    std::cout << "\n" << bold(green("=== Agent loop finished ===")) << "\n";
    std::cout << "Final response: " << final << "\n";

    return 0;
}
