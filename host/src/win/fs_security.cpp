// Pfad- und Rechteprüfungen für das Selbst-Update (siehe fs_security.hpp).

#include "win/fs_security.hpp"

#include <windows.h>
#include <aclapi.h>
#include <shlobj_core.h>

#include <string>
#include <vector>

namespace swbh::win {
namespace {

void set_error(unsigned long* out, unsigned long value) {
    if (out != nullptr) {
        *out = value;
    }
}

std::wstring strip_verbatim_prefix(std::wstring s) {
    // GetFinalPathNameByHandleW liefert immer die \\?\-Form; Vergleiche und
    // Kommandozeilen arbeiten mit der gewohnten Form.
    constexpr std::wstring_view kUnc = L"\\\\?\\UNC\\";
    constexpr std::wstring_view kLocal = L"\\\\?\\";
    if (s.rfind(kUnc, 0) == 0) {
        return L"\\\\" + s.substr(kUnc.size());
    }
    if (s.rfind(kLocal, 0) == 0) {
        return s.substr(kLocal.size());
    }
    return s;
}

std::wstring comparable(const std::filesystem::path& p) {
    std::wstring s = p.lexically_normal().native();
    while (s.size() > 3 && (s.back() == L'\\' || s.back() == L'/')) {
        s.pop_back();
    }
    for (wchar_t& c : s) {
        if (c == L'/') {
            c = L'\\';
        }
    }
    return s;
}

bool ordinal_equal_ignore_case(std::wstring_view a, std::wstring_view b) {
    if (a.size() != b.size()) {
        return false;
    }
    if (a.empty()) {
        return true;
    }
    return CompareStringOrdinal(a.data(), static_cast<int>(a.size()), b.data(), static_cast<int>(b.size()), TRUE) ==
           CSTR_EQUAL;
}

class Handle {
public:
    explicit Handle(HANDLE h = nullptr) : h_(h) {}
    ~Handle() {
        if (h_ != nullptr && h_ != INVALID_HANDLE_VALUE) {
            CloseHandle(h_);
        }
    }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    HANDLE get() const { return h_; }
    HANDLE* put() { return &h_; }

private:
    HANDLE h_;
};

std::optional<std::filesystem::path> known_folder(REFKNOWNFOLDERID id) {
    PWSTR raw = nullptr;
    const HRESULT hr = SHGetKnownFolderPath(id, KF_FLAG_DEFAULT, nullptr, &raw);
    std::optional<std::filesystem::path> result;
    if (SUCCEEDED(hr) && raw != nullptr && raw[0] != L'\0') {
        result = std::filesystem::path(raw);
    }
    if (raw != nullptr) {
        CoTaskMemFree(raw);
    }
    return result;
}

}  // namespace

ReparseCheck check_no_reparse_points(const std::filesystem::path& absolute_path) {
    ReparseCheck result;
    const std::filesystem::path normal = absolute_path.lexically_normal();
    const std::wstring root_name = normal.root_name().native();
    // Nur „X:\…“: UNC-Freigaben und \\?\-Pfade haben Wurzeln, die wir nicht
    // Teil für Teil prüfen können – fail-closed.
    const bool drive_root = root_name.size() == 2 && root_name[1] == L':' &&
                            ((root_name[0] >= L'A' && root_name[0] <= L'Z') ||
                             (root_name[0] >= L'a' && root_name[0] <= L'z'));
    if (!drive_root || !normal.has_root_directory()) {
        result.offending = absolute_path;
        result.win32_error = ERROR_BAD_PATHNAME;
        return result;
    }
    // „.“ und „..“ hat lexically_normal schon aufgelöst – genauso rein lexikalisch,
    // wie Windows selbst sie vor dem Dateisystem auflöst (RtlGetFullPathName).
    std::filesystem::path current = normal.root_path();
    for (const std::filesystem::path& part : normal.relative_path()) {
        if (part.empty()) {
            continue;  // abschließender Trenner
        }
        current /= part;
        // GetFileAttributesW folgt Verknüpfungen NICHT: Eine Junction oder ein
        // Symlink meldet hier selbst FILE_ATTRIBUTE_REPARSE_POINT.
        const DWORD attrs = GetFileAttributesW(current.c_str());
        if (attrs == INVALID_FILE_ATTRIBUTES) {
            result.offending = current;
            result.win32_error = GetLastError();
            return result;
        }
        if ((attrs & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            result.offending = current;
            result.win32_error = 0;
            return result;
        }
    }
    result.ok = true;
    return result;
}

std::optional<std::filesystem::path> final_path_of_handle(void* handle, unsigned long* win32_error) {
    set_error(win32_error, 0);
    std::wstring buffer(512, L'\0');
    for (int attempt = 0; attempt < 4; ++attempt) {
        const DWORD len = GetFinalPathNameByHandleW(static_cast<HANDLE>(handle), buffer.data(),
                                                    static_cast<DWORD>(buffer.size()),
                                                    FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
        if (len == 0) {
            set_error(win32_error, GetLastError());
            return std::nullopt;
        }
        if (len < buffer.size()) {
            buffer.resize(len);
            return std::filesystem::path(strip_verbatim_prefix(std::move(buffer)));
        }
        // len ist die nötige Größe samt Nullbyte.
        buffer.assign(static_cast<std::size_t>(len) + 1, L'\0');
    }
    set_error(win32_error, ERROR_INSUFFICIENT_BUFFER);
    return std::nullopt;
}

std::optional<std::filesystem::path> final_path_of(const std::filesystem::path& path, unsigned long* win32_error) {
    set_error(win32_error, 0);
    // Nur Attributrechte und volle Freigabe: stört keine anderen Handles und
    // sperrt nichts. FILE_FLAG_BACKUP_SEMANTICS öffnet auch Ordner.
    Handle h(CreateFileW(path.c_str(), FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                         nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, nullptr));
    if (h.get() == INVALID_HANDLE_VALUE) {
        set_error(win32_error, GetLastError());
        return std::nullopt;
    }
    return final_path_of_handle(h.get(), win32_error);
}

bool path_is_below(const std::filesystem::path& path, const std::filesystem::path& root) {
    const std::wstring p = comparable(path);
    std::wstring r = comparable(root);
    if (r.empty() || p.empty()) {
        return false;
    }
    if (r.back() != L'\\') {
        r.push_back(L'\\');
    }
    if (p.size() <= r.size()) {
        return false;
    }
    return ordinal_equal_ignore_case(std::wstring_view(p).substr(0, r.size()), r);
}

std::optional<bool> directory_writable_by_current_user(const std::filesystem::path& dir, unsigned long* win32_error) {
    set_error(win32_error, 0);
    PSECURITY_DESCRIPTOR sd = nullptr;
    const DWORD rc = GetNamedSecurityInfoW(dir.c_str(), SE_FILE_OBJECT,
                                           OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION |
                                               DACL_SECURITY_INFORMATION,
                                           nullptr, nullptr, nullptr, nullptr, &sd);
    if (rc != ERROR_SUCCESS) {
        set_error(win32_error, rc);
        return std::nullopt;
    }
    struct SdFree {
        PSECURITY_DESCRIPTOR p;
        ~SdFree() { LocalFree(p); }
    } sd_free{sd};

    Handle token;
    if (!OpenThreadToken(GetCurrentThread(), TOKEN_QUERY | TOKEN_DUPLICATE, TRUE, token.put())) {
        const DWORD e = GetLastError();
        if (e != ERROR_NO_TOKEN || !OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY | TOKEN_DUPLICATE, token.put())) {
            set_error(win32_error, e != ERROR_NO_TOKEN ? e : GetLastError());
            return std::nullopt;
        }
    }
    // AccessCheck verlangt ein Impersonations-Token.
    Handle impersonation;
    if (!DuplicateToken(token.get(), SecurityImpersonation, impersonation.put())) {
        set_error(win32_error, GetLastError());
        return std::nullopt;
    }
    GENERIC_MAPPING mapping{FILE_GENERIC_READ, FILE_GENERIC_WRITE, FILE_GENERIC_EXECUTE, FILE_ALL_ACCESS};
    // „Beschreibbar“ heißt: Dateien UND Unterordner anlegen. Genau das bräuchte
    // jemand, der eine DLL neben Baumeister-Host ablegt.
    DWORD desired = FILE_ADD_FILE | FILE_ADD_SUBDIRECTORY;
    MapGenericMask(&desired, &mapping);
    alignas(PRIVILEGE_SET) BYTE privileges[256] = {};
    DWORD privileges_len = sizeof(privileges);
    DWORD granted = 0;
    BOOL status = FALSE;
    if (!AccessCheck(sd, impersonation.get(), desired, &mapping, reinterpret_cast<PPRIVILEGE_SET>(privileges),
                     &privileges_len, &granted, &status)) {
        set_error(win32_error, GetLastError());
        return std::nullopt;
    }
    return status != FALSE && (granted & desired) == desired;
}

std::vector<std::filesystem::path> program_files_roots() {
    std::vector<std::filesystem::path> roots;
    for (const KNOWNFOLDERID* id : {&FOLDERID_ProgramFilesX64, &FOLDERID_ProgramFiles, &FOLDERID_ProgramFilesX86}) {
        const std::optional<std::filesystem::path> raw = known_folder(*id);
        if (!raw) {
            continue;
        }
        // Endgültiger Pfad, damit ein Programmordner, der über einen anderen
        // Schreibweg (Kurzname, Groß-/Kleinschreibung) erreicht wurde, gleich aussieht.
        const std::optional<std::filesystem::path> final_root = final_path_of(*raw);
        const std::filesystem::path root = final_root ? *final_root : *raw;
        bool duplicate = false;
        for (const auto& existing : roots) {
            duplicate = duplicate || ordinal_equal_ignore_case(comparable(existing), comparable(root));
        }
        if (!duplicate) {
            roots.push_back(root);
        }
    }
    return roots;
}

std::optional<bool> current_process_elevated() {
    Handle token;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, token.put())) {
        return std::nullopt;
    }
    TOKEN_ELEVATION elevation{};
    DWORD len = 0;
    if (!GetTokenInformation(token.get(), TokenElevation, &elevation, sizeof(elevation), &len)) {
        return std::nullopt;
    }
    return elevation.TokenIsElevated != 0;
}

ElevationKind elevation_kind_from(bool token_is_elevated, int token_elevation_type) {
    if (!token_is_elevated) {
        return ElevationKind::NotElevated;
    }
    switch (token_elevation_type) {
        case TokenElevationTypeFull:
            return ElevationKind::ElevatedSplitToken;
        case TokenElevationTypeDefault:
            return ElevationKind::ElevatedNoSplitToken;
        default:
            // Erhöht UND „eingeschränkt“ widerspricht sich – nicht raten.
            return ElevationKind::Unknown;
    }
}

ElevationKind current_process_elevation() {
    Handle token;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, token.put())) {
        return ElevationKind::Unknown;
    }
    TOKEN_ELEVATION elevation{};
    DWORD len = 0;
    if (!GetTokenInformation(token.get(), TokenElevation, &elevation, sizeof(elevation), &len)) {
        return ElevationKind::Unknown;
    }
    TOKEN_ELEVATION_TYPE type = TokenElevationTypeDefault;
    if (!GetTokenInformation(token.get(), TokenElevationType, &type, sizeof(type), &len)) {
        return ElevationKind::Unknown;
    }
    return elevation_kind_from(elevation.TokenIsElevated != 0, static_cast<int>(type));
}

const char* elevation_kind_name(ElevationKind kind) {
    switch (kind) {
        case ElevationKind::NotElevated:
            return "normal";
        case ElevationKind::ElevatedSplitToken:
            return "erhöht (als Administrator gestartet)";
        case ElevationKind::ElevatedNoSplitToken:
            return "erhöht ohne getrenntes Token (UAC aus bzw. eingebauter Administrator)";
        case ElevationKind::Unknown:
            break;
    }
    return "unbestimmbar";
}

}  // namespace swbh::win
