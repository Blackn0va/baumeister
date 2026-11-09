#include "gui/app_args.hpp"

#include "base/errors.hpp"
#include "base/utf8.hpp"

#include <windows.h>

#include <shellapi.h>

namespace swbh::gui {

AppArgs parse_args(const std::vector<std::wstring>& argv) {
    AppArgs out;
    out.qt_args.push_back(argv.empty() ? std::string("BaumeisterHost") : narrow(argv[0]));
    for (std::size_t i = 1; i < argv.size(); ++i) {
        if (argv[i] == L"--data-dir" && i + 1 < argv.size()) {
            out.data_dir = std::filesystem::path(argv[++i]);
        } else if (argv[i] == L"--screenshot" && i + 1 < argv.size()) {
            out.screenshot = std::filesystem::path(argv[++i]);
        } else if (argv[i] == kBackgroundSwitch) {
            out.background = true;
        } else {
            out.qt_args.push_back(narrow(argv[i]));
        }
    }
    return out;
}

std::vector<std::wstring> split_command_line(std::wstring_view command_line) {
    const std::wstring line(command_line);
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(line.c_str(), &argc);
    if (argv == nullptr) {
        throw UserError("Kommandozeile nicht lesbar");
    }
    std::vector<std::wstring> out(argv, argv + argc);
    LocalFree(argv);
    return out;
}

AppArgs parse_command_line() { return parse_args(split_command_line(GetCommandLineW())); }

}  // namespace swbh::gui
