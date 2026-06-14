/*
 * s01 Agent Loop — C++17 Implementation
 * ======================================
 * The core agent loop: while (finish_reason == "tool_calls"):
 *     response = LLM(messages, tools)
 *     execute tools -> append results -> loop
 *
 * Compile (Windows MSVC):  cl /EHsc /std:c++17 main.cpp
 * Compile (MinGW):         g++ -std=c++17 main.cpp -o main
 * Compile (Linux/macOS):   g++ -std=c++17 main.cpp -o main
 * Run:                     main (or ./main on Unix)
 */

#include <iostream>
#include <string>
#include <vector>
#include <cstdio>
#include <memory>
#include <algorithm>
#include <cctype>
#include <sstream>

// ═══════════════════════════════════════════════════════════════
// ANSI Color Macros
// ═══════════════════════════════════════════════════════════════

#define R    "\033[91m"
#define G    "\033[92m"
#define Y    "\033[93m"
#define B    "\033[94m"
#define M    "\033[95m"
#define C    "\033[96m"
#define W    "\033[97m"
#define BOLD "\033[1m"
#define DIM  "\033[2m"
#define X    "\033[0m"

using std::string;
using std::vector;


// ═══════════════════════════════════════════════════════════════
// Data Structures
// ═══════════════════════════════════════════════════════════════

struct Message {
    string role;        // "system", "user", "assistant", "tool"
    string content;
    string tool_call_id;
};

struct ToolCall {
    string id;
    string func_name;
    string arguments;   // JSON string: {"command":"..."}
};

struct LLMChoice {
    string finish_reason;   // "stop" or "tool_calls"
    string content;
    vector<ToolCall> tool_calls;
};


// ═══════════════════════════════════════════════════════════════
// Bash Execution — calls popen / _popen
// ═══════════════════════════════════════════════════════════════

string execute_bash(const string& command) {
    string result;

#ifdef _WIN32
    FILE* pipe = _popen(command.c_str(), "r");
#else
    FILE* pipe = popen(command.c_str(), "r");
#endif

    if (!pipe) return "Error: Failed to run command";

    char buffer[256];
    while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        result += buffer;
    }

#ifdef _WIN32
    int status = _pclose(pipe);
#else
    int status = pclose(pipe);
#endif
    (void)status; // suppress unused warning

    if (result.empty()) return "(command executed successfully, no output)";

    // Trim trailing newlines
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r'))
        result.pop_back();

    return result;
}


// ═══════════════════════════════════════════════════════════════
// String Helpers
// ═══════════════════════════════════════════════════════════════

string to_lower(string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return std::tolower(c); });
    return s;
}

bool str_contains(const string& haystack, const string& needle) {
    return to_lower(haystack).find(to_lower(needle)) != string::npos;
}

string json_escape(const string& s) {
    string out;
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\t': out += "\\t";  break;
            case '\r': out += "\\r";  break;
            default:   out += c;
        }
    }
    return out;
}

// Extract "command" value from {"command":"..."} (simple manual parse)
string extract_command(const string& json_args) {
    string search = "\"command\":\"";
    size_t start = json_args.find(search);
    if (start == string::npos) return "";
    start += search.length();
    size_t end = json_args.find('"', start);
    if (end == string::npos) return "";
    string raw = json_args.substr(start, end - start);

    // Unescape
    string out;
    for (size_t i = 0; i < raw.length(); i++) {
        if (raw[i] == '\\' && i + 1 < raw.length()) {
            switch (raw[i + 1]) {
                case '"':  out += '"';  i++; break;
                case '\\': out += '\\'; i++; break;
                case 'n':  out += '\n'; i++; break;
                case 't':  out += '\t'; i++; break;
                case 'r':  out += '\r'; i++; break;
                default:   out += raw[i];
            }
        } else {
            out += raw[i];
        }
    }
    return out;
}


// ═══════════════════════════════════════════════════════════════
// Mock LLM
// ═══════════════════════════════════════════════════════════════

/*
 * REPLACE WITH ACTUAL API CALL:
 * ┌────────────────────────────────────────────────────────────┐
 * │ Use libcurl to POST to:                                   │
 * │   https://api.deepseek.com/v1/chat/completions            │
 * │ Headers:                                                  │
 * │   Authorization: Bearer <API_KEY>                         │
 * │   Content-Type: application/json                          │
 * │ Body:                                                     │
 * │   { "model": "deepseek-chat",                             │
 * │     "messages": [...], "tools": [...] }                   │
 * │ Parse response JSON (recommend nlohmann/json)             │
 * └────────────────────────────────────────────────────────────┘
 */

static bool is_win() {
#ifdef _WIN32
    return true;
#else
    return false;
#endif
}

vector<ToolCall> _get_tool_calls(const string& query) {
    vector<ToolCall> calls;
    int call_idx = 0;

    auto add_call = [&](const string& cmd) {
        ToolCall tc;
        tc.id = "call_" + std::to_string(++call_idx);
        tc.func_name = "bash";
        tc.arguments = "{\"command\":\"" + json_escape(cmd) + "\"}";
        calls.push_back(tc);
    };

    // File listing
    if (str_contains(query, "list") || str_contains(query, "show") ||
        str_contains(query, "ls") || str_contains(query, "dir") ||
        str_contains(query, "files")) {
        if (str_contains(query, "all") || str_contains(query, "hidden") ||
            str_contains(query, "-a") || str_contains(query, "-la")) {
            add_call("ls -la");
        } else {
            add_call("ls");
        }
    }

    // File creation
    if ((str_contains(query, "create") || str_contains(query, "make") ||
         str_contains(query, "new") || str_contains(query, "write")) &&
        (str_contains(query, "file") || str_contains(query, "txt") ||
         str_contains(query, "document"))) {
        add_call("echo \"Hello, World! This file was created by the AI agent.\" > demo.txt && echo \"Created demo.txt\"");
    }

    // System info
    if (str_contains(query, "system") || str_contains(query, "os") ||
        str_contains(query, "uname") || str_contains(query, "kernel") ||
        str_contains(query, "version")) {
        if (is_win()) {
            add_call("systeminfo | findstr /B /C:\"OS Name\" /C:\"OS Version\"");
        } else {
            add_call("uname -a");
        }
    }

    // Disk space
    if (str_contains(query, "disk") || str_contains(query, "space") ||
        str_contains(query, "df") || str_contains(query, "storage")) {
        if (is_win()) {
            add_call("wmic logicaldisk get size,freespace,caption");
        } else {
            add_call("df -h");
        }
    }

    // Memory
    if (str_contains(query, "memory") || str_contains(query, "ram") ||
        str_contains(query, "mem") || str_contains(query, "free")) {
        if (is_win()) {
            add_call("systeminfo | findstr /C:\"Total Physical Memory\" /C:\"Available Physical Memory\"");
        } else {
            add_call("free -h");
        }
    }

    // Current directory
    if (str_contains(query, "pwd") || str_contains(query, "current directory") ||
        str_contains(query, "where am i") || str_contains(query, "working directory") ||
        str_contains(query, "cwd")) {
        add_call(is_win() ? "cd" : "pwd");
    }

    // Echo
    if ((str_contains(query, "echo") || str_contains(query, "say") ||
         str_contains(query, "print")) && !str_contains(query, "system")) {
        add_call("echo \"Hello from the AI agent!\"");
    }

    // Date / Time
    if (str_contains(query, "date") || str_contains(query, "time") ||
        str_contains(query, "today") || str_contains(query, "now")) {
        add_call(is_win() ? "date /t && time /t" : "date");
    }

    // Processes
    if (str_contains(query, "process") || str_contains(query, "ps") ||
        str_contains(query, "task") || str_contains(query, "running") ||
        str_contains(query, "top")) {
        add_call(is_win() ? "tasklist" : "ps aux");
    }

    // Default fallback
    if (calls.empty()) {
        add_call("echo 'No specific command matched. Try: list files, system info, disk space, memory, create file, date'");
    }

    return calls;
}

string _get_final_answer(const string& query) {
    string q = to_lower(query);
    if (q.find("list") != string::npos || q.find("file") != string::npos ||
        q.find("show") != string::npos || q.find("ls") != string::npos ||
        q.find("dir") != string::npos)
        return "Here are the files in the current directory. The agent used bash to run the listing command.";
    if (q.find("system") != string::npos || q.find("os") != string::npos ||
        q.find("kernel") != string::npos || q.find("info") != string::npos ||
        q.find("version") != string::npos)
        return "Here's your system information. I ran the appropriate system info command to retrieve it.";
    if (q.find("disk") != string::npos || q.find("space") != string::npos ||
        q.find("storage") != string::npos)
        return "Here's the disk usage information. I queried the system for storage details.";
    if (q.find("memory") != string::npos || q.find("ram") != string::npos ||
        q.find("mem") != string::npos || q.find("free") != string::npos)
        return "Here's the memory information. This shows your current RAM usage and availability.";
    if (q.find("create") != string::npos || q.find("make") != string::npos ||
        q.find("new") != string::npos || q.find("write") != string::npos)
        return "I've created the file using a bash command. It's been written to disk.";
    if (q.find("date") != string::npos || q.find("time") != string::npos ||
        q.find("today") != string::npos || q.find("now") != string::npos)
        return "Here's the current date and time from the system clock.";
    if (q.find("process") != string::npos || q.find("running") != string::npos ||
        q.find("task") != string::npos)
        return "Here are the currently running processes on your system.";
    if (q.find("pwd") != string::npos || q.find("directory") != string::npos ||
        q.find("where") != string::npos)
        return "Here's your current working directory path.";
    return "I've executed the appropriate shell commands. Check the output above for results.";
}

LLMChoice mock_llm(const vector<Message>& messages) {
    // Find the last user message
    string last_user;
    for (auto it = messages.rbegin(); it != messages.rend(); ++it) {
        if (it->role == "user") {
            last_user = it->content;
            break;
        }
    }

    // Count tool results in conversation
    int tool_count = 0;
    for (const auto& m : messages) {
        if (m.role == "tool") tool_count++;
    }

    // If we already have tool results, return final answer
    if (tool_count > 0) {
        LLMChoice c;
        c.finish_reason = "stop";
        c.content = _get_final_answer(last_user);
        return c;
    }

    // Otherwise, determine tools to call
    vector<ToolCall> calls = _get_tool_calls(last_user);
    if (!calls.empty()) {
        LLMChoice c;
        c.finish_reason = "tool_calls";
        c.tool_calls = calls;
        return c;
    }

    // No tools needed
    LLMChoice c;
    c.finish_reason = "stop";
    c.content = _get_final_answer(last_user);
    return c;
}


// ═══════════════════════════════════════════════════════════════
// Terminal Output Helpers
// ═══════════════════════════════════════════════════════════════

void print_banner() {
    std::cout << C << BOLD << "\n";
    std::cout << "╔══════════════════════════════════════════════╗\n";
    std::cout << "║       🛠  AI Coding Agent — Lesson 01        ║\n";
    std::cout << "║          The Core Agent Loop                 ║\n";
    std::cout << "╚══════════════════════════════════════════════╝\n";
    std::cout << X;
    std::cout << DIM << "Type 'quit' or 'exit' to leave. Try: list files, system info, create file" << X << "\n\n";
}

void step_header(int step, const string& title) {
    std::cout << "\n" << Y << BOLD << "[Step " << step << "] " << title << X << "\n";
    std::cout << Y << "──────────────────────────────────────────────────" << X << "\n";
}


// ═══════════════════════════════════════════════════════════════
// MAIN: The Agent Loop
// ═══════════════════════════════════════════════════════════════

int main() {
    print_banner();

    // Initialize conversation with system prompt
    vector<Message> messages;
    messages.push_back({
        "system",
        "You are a helpful coding assistant with access to a bash shell. "
        "Use the bash tool to run shell commands and help the user. "
        "Always think step by step and use tools when needed.",
        ""
    });

    string user_input;
    while (true) {
        // Get user input
        std::cout << G << BOLD << "You:" << X << " ";
        std::getline(std::cin, user_input);

        if (user_input.empty()) continue;
        if (user_input == "quit" || user_input == "exit" || user_input == "q") {
            std::cout << DIM << "Goodbye!" << X << "\n";
            break;
        }

        messages.push_back({"user", user_input, ""});
        int turn_step = 0;

        // ═══════════════════════════════════════════════════════
        // THE CORE AGENT LOOP
        // ═══════════════════════════════════════════════════════
        while (true) {
            turn_step++;
            step_header(turn_step, "Calling LLM...");

            // Call the LLM (mock or real)
            LLMChoice choice = mock_llm(messages);

            // Branch: tool_calls
            if (choice.finish_reason == "tool_calls") {
                std::cout << M << BOLD << "  LLM decided to call "
                          << choice.tool_calls.size() << " tool(s)" << X << "\n";

                messages.push_back({"assistant", "", ""});

                for (size_t i = 0; i < choice.tool_calls.size(); i++) {
                    const auto& tc = choice.tool_calls[i];
                    string cmd = extract_command(tc.arguments);

                    std::cout << B << "  Tool: " << tc.func_name << X << "\n";
                    std::cout << DIM << "  Command: " << cmd << X << "\n";

                    step_header(turn_step, "Executing: " + cmd);
                    string result = execute_bash(cmd);

                    // Print result (first 20 lines)
                    std::cout << G << "  Result:" << X << "\n";
                    std::istringstream stream(result);
                    string line;
                    int line_count = 0;
                    int max_lines = 20;
                    while (std::getline(stream, line) && line_count < max_lines) {
                        std::cout << DIM << "    | " << line << X << "\n";
                        line_count++;
                    }
                    // Count remaining lines
                    int total = line_count;
                    while (std::getline(stream, line)) total++;
                    if (total > max_lines) {
                        std::cout << DIM << "    | ... (" << total << " total lines)" << X << "\n";
                    }

                    messages.push_back({"tool", result, tc.id});
                }

                std::cout << Y << "  Looping back to LLM with results..." << X << "\n";

            // Branch: stop
            } else {
                std::cout << "\n" << G << BOLD << "Agent:" << X << " "
                          << W << choice.content << X << "\n\n";
                messages.push_back({"assistant", choice.content, ""});
                break;
            }
        }
        // ═══════════════════════════════════════════════════════
        // END CORE AGENT LOOP
        // ═══════════════════════════════════════════════════════
    }

    return 0;
}
