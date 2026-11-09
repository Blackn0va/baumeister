#include "base/errors.hpp"

#include "base/utf8.hpp"

#include <windows.h>

namespace swbh {

DownloadCancelled::DownloadCancelled() : std::runtime_error(std::string(kDownloadCancelledText)) {}

std::string error_code_text(const std::error_code& ec) {
    if (ec.category() == std::system_category()) {
        return system_error_text(static_cast<unsigned long>(ec.value()));
    }
    return sanitize_utf8(ec.message());
}

std::string system_error_text(unsigned long win32_error) {
    wchar_t* buffer = nullptr;
    // FORMAT_MESSAGE_IGNORE_INSERTS: Texte mit %1 sollen nicht versuchen, Argumente zu lesen.
    const DWORD length = FormatMessageW(
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr, win32_error, 0, reinterpret_cast<wchar_t*>(&buffer), 0, nullptr);
    std::string text;
    if (length > 0 && buffer != nullptr) {
        std::wstring_view view(buffer, length);
        // Windows-Texte enden mit "\r\n" und oft mit einem Punkt; beides stört in
        // zusammengesetzten Meldungen wie "Datei nicht lesbar: <pfad>: <text>".
        while (!view.empty() && (view.back() == L'\r' || view.back() == L'\n' || view.back() == L' ' ||
                                 view.back() == L'.')) {
            view.remove_suffix(1);
        }
        text = narrow(view);
    }
    if (buffer != nullptr) {
        LocalFree(buffer);
    }
    if (text.empty()) {
        return "Windows-Fehler " + std::to_string(win32_error);
    }
    return text + " (Fehler " + std::to_string(win32_error) + ")";
}

}  // namespace swbh
