#include "components/store.hpp"

#include "base/errors.hpp"
#include "base/fileio.hpp"
#include "base/json.hpp"
#include "base/log.hpp"
#include "base/paths.hpp"
#include "win/cng.hpp"
#include "win/fs_security.hpp"

#include <windows.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <system_error>
#include <thread>

namespace swbh::components {

namespace {

constexpr wchar_t kInstalledFile[] = L"installed.json";
// GitHub-Release-Assets leiten auf objects/release-assets.githubusercontent.com um (wie voice/files.cpp).
const std::vector<std::string> kGitHubHosts = {"github.com", "*.githubusercontent.com"};

std::filesystem::path special(const std::filesystem::path& root, std::wstring_view prefix, const std::string& id) {
    return root / (std::wstring(prefix) + u8path(id).wstring());
}

bool present(const std::filesystem::path& p) {
    std::error_code ec;
    return std::filesystem::exists(p, ec) && !ec;
}

void remove_tree(const std::filesystem::path& p) {
    std::error_code ec;
    std::filesystem::remove_all(p, ec);
}

struct InstalledInfo {
    std::string version;
    std::vector<std::string> terms_accepted;
};

std::optional<InstalledInfo> read_installed(const std::filesystem::path& dir) {
    // Vorher lief ein FileError (Zugriff verweigert, Sperre durch den Virenscanner, Datei über 64 KiB) ungefangen aus
    // status() und pin_files() heraus – entgegen store.hpp („werfen nicht“, „nullopt bei jedem Zweifel“) – und riss
    // über prepare_compute den Ladethread bei JEDEM Start mit (std::terminate). Jetzt zählt eine unlesbare Datei wie
    // eine fehlende: status meldet Damaged, pin_files lehnt fail-closed ab, weil die Zustimmung nicht belegt ist
    // (ASVS V4.1.5). Nur FileError: ein Programmfehler soll weiter sichtbar abbrechen. Wer das zurückdreht, macht aus
    // einer kaputten Rechteliste einen Absturz bei jedem Start.
    std::optional<std::string> bytes;
    try {
        bytes = read_file_if_exists(dir / kInstalledFile, 64 * 1024);
    } catch (const FileError& e) {
        log::warn("Komponente: installed.json nicht lesbar: " + std::string(e.what()));
        return std::nullopt;
    }
    if (!bytes) {
        return std::nullopt;
    }
    const auto j = try_parse_utf8(*bytes);
    if (!j || !j->is_object()) {
        return std::nullopt;
    }
    InstalledInfo info;
    if (const Json* v = json_find(*j, "version"); v != nullptr && v->is_string()) {
        info.version = v->get<std::string>();
    }
    if (const Json* t = json_find(*j, "terms_accepted"); t != nullptr && t->is_array()) {
        for (const Json& x : *t) {
            if (x.is_string()) {
                info.terms_accepted.push_back(x.get<std::string>());
            }
        }
    }
    return info;
}

bool accepted(const InstalledInfo& info, const std::string& terms) {
    return terms.empty() ||
           std::find(info.terms_accepted.begin(), info.terms_accepted.end(), terms) != info.terms_accepted.end();
}

std::string utc_now_text() {
    SYSTEMTIME st{};
    GetSystemTime(&st);
    char buf[32];
    std::snprintf(buf, sizeof buf, "%04u-%02u-%02uT%02u:%02u:%02uZ", st.wYear, st.wMonth, st.wDay, st.wHour,
                  st.wMinute, st.wSecond);
    return buf;
}

std::string mb(std::uint64_t bytes) { return std::to_string((bytes + 512 * 1024) / (1024 * 1024)) + " MB"; }

// SHA-256 über ein offenes Handle (ab Dateianfang), Größe in bytes_read.
std::optional<std::string> sha256_handle(HANDLE h, std::uint64_t& bytes_read) {
    LARGE_INTEGER zero{};
    if (!SetFilePointerEx(h, zero, nullptr, FILE_BEGIN)) {
        return std::nullopt;
    }
    win::Sha256Stream stream;
    std::vector<std::uint8_t> buf(1 << 20);
    bytes_read = 0;
    for (;;) {
        DWORD got = 0;
        if (!ReadFile(h, buf.data(), static_cast<DWORD>(buf.size()), &got, nullptr)) {
            return std::nullopt;
        }
        if (got == 0) {
            break;
        }
        stream.update(std::span<const std::uint8_t>(buf.data(), got));
        bytes_read += got;
    }
    const auto digest = stream.finish();
    static constexpr char kHex[] = "0123456789abcdef";
    std::string hex;
    hex.reserve(64);
    for (const std::uint8_t b : digest) {
        hex += kHex[b >> 4];
        hex += kHex[b & 0x0F];
    }
    return hex;
}

// Datei ohne Umleitung öffnen: Freigabe nur Lesen (kein Schreiben, Löschen oder Umbenennen, solange offen), ein
// Reparse-Punkt wird selbst geöffnet statt verfolgt und dann abgelehnt.
HANDLE open_pinned(const std::filesystem::path& p, std::string& error) {
    HANDLE h = CreateFileW(p.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL | FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        error = u8str(p.filename()) + " nicht lesbar: " + system_error_text(GetLastError());
        return nullptr;
    }
    BY_HANDLE_FILE_INFORMATION info{};
    if (!GetFileInformationByHandle(h, &info)) {
        error = u8str(p.filename()) + ": " + system_error_text(GetLastError());
        CloseHandle(h);
        return nullptr;
    }
    if ((info.dwFileAttributes & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DIRECTORY)) != 0) {
        error = u8str(p.filename()) + " ist keine gewöhnliche Datei (Verknüpfung oder Ordner)";
        CloseHandle(h);
        return nullptr;
    }
    return h;
}

}  // namespace

const char* state_name(State s) {
    switch (s) {
        case State::NotInstalled:
            return "nicht installiert";
        case State::Installed:
            return "installiert";
        case State::Outdated:
            return "veraltet";
        case State::Damaged:
            return "beschädigt";
        case State::PendingRestart:
            return "wartet auf Neustart";
        case State::RemovalPending:
            return "wird entfernt";
    }
    return "?";
}

std::filesystem::path file_path(const std::filesystem::path& dir, const std::string& name) {
    std::filesystem::path p = dir;
    std::size_t start = 0;
    for (;;) {
        const std::size_t slash = name.find('/', start);
        const std::size_t len = slash == std::string::npos ? std::string::npos : slash - start;
        p /= u8path(std::string_view(name).substr(start, len));
        if (slash == std::string::npos) {
            break;
        }
        start = slash + 1;
    }
    return p;
}

// ---- PinnedFiles ----------------------------------------------------------------------------------------------

PinnedFiles::~PinnedFiles() {
    for (void* h : handles_) {
        CloseHandle(static_cast<HANDLE>(h));
    }
}

PinnedFiles::PinnedFiles(PinnedFiles&& other) noexcept
    : dir(std::move(other.dir)), dll(std::move(other.dll)), bytes(other.bytes), seconds(other.seconds),
      handles_(std::move(other.handles_)) {
    other.handles_.clear();
}

PinnedFiles& PinnedFiles::operator=(PinnedFiles&& other) noexcept {
    if (this != &other) {
        for (void* h : handles_) {
            CloseHandle(static_cast<HANDLE>(h));
        }
        dir = std::move(other.dir);
        dll = std::move(other.dll);
        bytes = other.bytes;
        seconds = other.seconds;
        handles_ = std::move(other.handles_);
        other.handles_.clear();
    }
    return *this;
}

std::optional<PinnedFiles> pin_files(const std::filesystem::path& component_dir, const Component& c,
                                     std::string& error) {
    const auto t0 = std::chrono::steady_clock::now();
    if (!component_dir.is_absolute()) {
        error = "Ordner des Kerns ist nicht absolut: " + u8str(component_dir);
        return std::nullopt;
    }
    const win::ReparseCheck rc = win::check_no_reparse_points(component_dir);
    if (!rc.ok) {
        error = "Ordner des Kerns enthält eine Verknüpfung oder ist nicht lesbar: " + u8str(rc.offending);
        return std::nullopt;
    }
    if (!c.terms.empty()) {
        // Zweite Schicht nach dem Dialog: Ohne festgehaltene Zustimmung wird der Kern nie geladen (wie NvidiaGate in
        // CaptionBridge) – auch wenn die Dateien von Hand in den Ordner kopiert wurden.
        const auto info = read_installed(component_dir);
        if (!info || !accepted(*info, c.terms)) {
            error = "Zustimmung zu den Lizenzbedingungen fehlt (" + c.terms + ") – " + c.title + " wird nicht geladen.";
            return std::nullopt;
        }
    }
    struct Item {
        std::filesystem::path path;
        const ExtractFile* spec = nullptr;
        HANDLE handle = nullptr;
        std::optional<std::string> sha;
        std::uint64_t read = 0;
    };
    std::vector<Item> items;
    for (const Archive& a : c.archives) {
        for (const ExtractFile& f : a.extract) {
            items.push_back(Item{file_path(component_dir, f.name), &f});
        }
    }
    PinnedFiles pinned;
    pinned.dir = component_dir;
    pinned.dll = file_path(component_dir, c.dll);
    for (Item& it : items) {
        it.handle = open_pinned(it.path, error);
        if (it.handle == nullptr) {
            for (Item& x : items) {
                if (x.handle != nullptr) {
                    CloseHandle(x.handle);
                }
            }
            return std::nullopt;
        }
    }
    // Parallel hashen: cublasLt64_13.dll allein hat 460 MB; vier Fäden verteilen die übrigen Dateien daneben.
    std::atomic<std::size_t> next{0};
    const unsigned workers = std::min<unsigned>(4, static_cast<unsigned>(items.size()));
    std::vector<std::thread> pool;
    for (unsigned w = 0; w < workers; ++w) {
        pool.emplace_back([&] {
            for (std::size_t i = next++; i < items.size(); i = next++) {
                items[i].sha = sha256_handle(items[i].handle, items[i].read);
            }
        });
    }
    for (std::thread& t : pool) {
        t.join();
    }
    std::string bad;
    for (const Item& it : items) {
        if (!it.sha) {
            bad = u8str(it.path.filename()) + " nicht vollständig lesbar";
        } else if (it.read != it.spec->size) {
            bad = u8str(it.path.filename()) + " hat " + std::to_string(it.read) + " statt " +
                  std::to_string(it.spec->size) + " Bytes";
        } else if (*it.sha != it.spec->sha256) {
            bad = u8str(it.path.filename()) + ": SHA-256 weicht vom Katalog ab";
        }
        if (!bad.empty()) {
            break;
        }
    }
    if (!bad.empty()) {
        for (Item& x : items) {
            CloseHandle(x.handle);
        }
        error = "Kern " + c.title + " beschädigt oder verändert (" + bad + ") – bitte neu installieren.";
        return std::nullopt;
    }
    for (Item& it : items) {
        pinned.handles_.push_back(it.handle);
        pinned.bytes += it.read;
    }
    pinned.seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    return pinned;
}

// ---- Entpacken -------------------------------------------------------------------------------------------------

void extract_members(const std::filesystem::path& zip, const std::filesystem::path& dest,
                     const std::vector<std::string>& members) {
    wchar_t sys[MAX_PATH];
    const UINT n = GetSystemDirectoryW(sys, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) {
        throw UserError("System32 nicht gefunden");
    }
    const std::filesystem::path tar = std::filesystem::path(sys) / L"tar.exe";
    if (!present(tar)) {
        throw UserError("tar.exe fehlt (Windows 10 1803 oder neuer nötig)");
    }
    std::wstring cmd = L"\"" + tar.wstring() + L"\" -xf \"" + zip.wstring() + L"\" -C \"" + dest.wstring() + L"\"";
    for (const std::string& m : members) {
        // safe_relative_name: keine Anführungszeichen, kein führender „-“ (sonst läse tar eine Option).
        if (!safe_relative_name(m)) {
            throw UserError("Unsicherer Name im Katalog: " + m);
        }
        cmd += L" \"" + u8path(m).wstring() + L"\"";
    }
    STARTUPINFOW si{};
    si.cb = sizeof si;
    PROCESS_INFORMATION pi{};
    if (!CreateProcessW(tar.c_str(), cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si,
                        &pi)) {
        throw UserError("tar.exe startet nicht: " + system_error_text(GetLastError()));
    }
    CloseHandle(pi.hThread);
    // ROCm entpackt über 1 GB – großzügig, aber nicht endlos.
    if (WaitForSingleObject(pi.hProcess, 600000) != WAIT_OBJECT_0) {
        TerminateProcess(pi.hProcess, 1);
        WaitForSingleObject(pi.hProcess, 5000);
        CloseHandle(pi.hProcess);
        throw UserError("Entpacken dauert zu lange (tar nach 600 s beendet)");
    }
    DWORD code = 1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    if (code != 0) {
        throw UserError("Entpacken fehlgeschlagen (tar Exit " + std::to_string(code) + ")");
    }
}

// ---- Store -----------------------------------------------------------------------------------------------------

Store::Store(std::filesystem::path root) : root_(std::move(root)) {}

std::filesystem::path Store::dir(const Component& c) const { return root_ / u8path(c.id); }

Status Store::status(const Component& c) const {
    Status s;
    const std::filesystem::path d = dir(c);
    const auto info = read_installed(d);
    if (info) {
        s.installed_version = info->version;
        s.terms_accepted = accepted(*info, c.terms) && !c.terms.empty();
    }
    if (present(special(root_, L".remove-", c.id))) {
        s.state = State::RemovalPending;
        s.detail = "Wird beim nächsten Start entfernt.";
        return s;
    }
    if (present(special(root_, L".pending-", c.id))) {
        s.state = State::PendingRestart;
        s.detail = "Neue Fassung liegt bereit – wird beim nächsten Start eingesetzt.";
        return s;
    }
    if (!present(d)) {
        s.state = State::NotInstalled;
        return s;
    }
    if (!info) {
        s.state = State::Damaged;
        s.detail = "installed.json fehlt oder ist unlesbar";
        return s;
    }
    if (info->version != c.version) {
        s.state = State::Outdated;
        s.detail = "Installiert: " + info->version + ", zu dieser App passt: " + c.version;
        return s;
    }
    for (const Archive& a : c.archives) {
        for (const ExtractFile& f : a.extract) {
            std::error_code ec;
            const auto size = std::filesystem::file_size(file_path(d, f.name), ec);
            if (ec || size != f.size) {
                s.state = State::Damaged;
                s.detail = ec ? f.name + " fehlt" : f.name + " hat die falsche Größe";
                return s;
            }
        }
    }
    s.state = State::Installed;
    return s;
}

ChangeResult Store::install(win::HttpTransport& http, const Component& c, bool terms_accepted, bool in_use,
                            const win::ProgressFn& progress, win::CancelToken& cancel) {
    if (!c.terms.empty() && !terms_accepted) {
        throw UserError(c.title + ": ohne Zustimmung zu den Lizenzbedingungen wird nichts geladen.");
    }
    ensure_directory(root_);
    const win::ReparseCheck rc = win::check_no_reparse_points(root_);
    if (!rc.ok) {
        throw UserError("Komponentenordner enthält eine Verknüpfung oder ist nicht lesbar: " + u8str(rc.offending));
    }
    const std::filesystem::path downloads = root_ / L".download";
    ensure_directory(downloads);

    // 1. Archive laden (bzw. eine vollständige Datei vom letzten Versuch nach Hashprüfung übernehmen).
    const std::uint64_t total = c.download_size();
    std::uint64_t offset = 0;
    std::vector<std::filesystem::path> zips;
    for (const Archive& a : c.archives) {
        const std::filesystem::path target = downloads / u8path(a.file);
        std::error_code ec;
        const bool complete = std::filesystem::file_size(target, ec) == a.size && !ec &&
                              win::sha256_file_hex(target) == std::optional<std::string>(a.sha256);
        if (!complete) {
            win::DownloadRequest req;
            req.url = a.url;
            req.target = target;
            req.expected_size = a.size;
            req.expected_sha256 = a.sha256;
            req.user_agent = "StreamWizard-Baumeister";
            req.redirects = win::Redirects::HttpsAllowList;
            req.redirect_host_suffixes = kGitHubHosts;
            req.resume = true;
            req.finalize = true;
            log::info("Komponente " + c.id + ": lade " + a.file + " (" + mb(a.size) + ")");
            const std::uint64_t base = offset;
            http.download(
                req,
                [&](std::uint64_t done, std::uint64_t) {
                    if (progress) {
                        progress(base + done, total);
                    }
                },
                cancel);
        }
        offset += a.size;
        if (progress) {
            progress(offset, total);
        }
        zips.push_back(target);
    }

    // 2. Nur die Katalogdateien entpacken und jede einzeln prüfen.
    const std::filesystem::path staging = special(root_, L".staging-", c.id);
    remove_tree(staging);
    ensure_directory(staging);
    std::size_t expected_files = 0;
    for (std::size_t i = 0; i < c.archives.size(); ++i) {
        std::vector<std::string> names;
        for (const ExtractFile& f : c.archives[i].extract) {
            names.push_back(f.name);
        }
        expected_files += names.size();
        extract_members(zips[i], staging, names);
    }
    for (const Archive& a : c.archives) {
        for (const ExtractFile& f : a.extract) {
            const std::filesystem::path p = file_path(staging, f.name);
            std::string err;
            HANDLE h = open_pinned(p, err);
            if (h == nullptr) {
                remove_tree(staging);
                throw UserError(c.title + ": " + err);
            }
            std::uint64_t read = 0;
            const auto sha = sha256_handle(h, read);
            CloseHandle(h);
            if (!sha || read != f.size || *sha != f.sha256) {
                remove_tree(staging);
                throw UserError(c.title + ": " + f.name + " weicht nach dem Entpacken vom Katalog ab – verworfen.");
            }
        }
    }
    // Keine Datei mehr als bestellt (tar entpackt nur die Namen; doppelt hält besser, die Prüfung kostet nichts).
    std::size_t found = 0;
    std::error_code ec;
    for (auto it = std::filesystem::recursive_directory_iterator(staging, ec);
         !ec && it != std::filesystem::recursive_directory_iterator(); it.increment(ec)) {
        if (it->is_regular_file(ec)) {
            ++found;
        }
    }
    if (ec || found != expected_files) {
        remove_tree(staging);
        throw UserError(c.title + ": unerwarteter Inhalt nach dem Entpacken – verworfen.");
    }
    Json info = Json::object();
    info["id"] = c.id;
    info["version"] = c.version;
    info["terms_accepted"] = Json::array();
    if (!c.terms.empty()) {
        info["terms_accepted"].push_back(c.terms);
    }
    info["installed_utc"] = utc_now_text();
    write_file_atomic(staging / kInstalledFile, dump_python_indent2(info) + "\n");

    // 3. Einsetzen. Ist der alte Kern geladen, lässt Windows den Ordner weder umbenennen noch löschen
    //    (PinnedFiles sperrt jede Datei) – dann beim nächsten Start (cleanup).
    ChangeResult result;
    const std::filesystem::path target = dir(c);
    std::filesystem::remove(special(root_, L".remove-", c.id), ec);
    if (in_use) {
        const std::filesystem::path pending = special(root_, L".pending-", c.id);
        remove_tree(pending);
        std::filesystem::rename(staging, pending, ec);
        if (ec) {
            remove_tree(staging);
            throw UserError(c.title + ": konnte nicht bereitgelegt werden: " + error_code_text(ec));
        }
        result.needs_restart = true;
        result.message = c.title + " ist geprüft und wird beim nächsten Start eingesetzt.";
    } else {
        const std::filesystem::path old = special(root_, L".old-", c.id);
        remove_tree(old);
        if (present(target)) {
            std::filesystem::rename(target, old, ec);
            if (ec) {
                remove_tree(staging);
                throw UserError(c.title + ": alter Stand lässt sich nicht ersetzen: " + error_code_text(ec));
            }
        }
        std::filesystem::rename(staging, target, ec);
        if (ec) {
            // Alten Stand zurück, damit nichts halb fehlt.
            std::error_code ec2;
            std::filesystem::rename(old, target, ec2);
            remove_tree(staging);
            throw UserError(c.title + ": konnte nicht eingesetzt werden: " + error_code_text(ec));
        }
        remove_tree(old);
        result.message = c.title + " ist installiert (" + mb(c.installed_size()) + ").";
    }
    for (const std::filesystem::path& z : zips) {
        std::filesystem::remove(z, ec);
    }
    log::info("Komponente " + c.id + ": " + result.message);
    return result;
}

ChangeResult Store::remove(const Component& c, bool in_use) {
    ChangeResult result;
    std::error_code ec;
    remove_tree(special(root_, L".pending-", c.id));
    if (in_use) {
        try {
            write_file_atomic(special(root_, L".remove-", c.id), "remove\n");
        } catch (const std::exception& e) {
            result.message = c.title + ": Entfernen nicht vorgemerkt: " + e.what();
            return result;
        }
        result.needs_restart = true;
        result.message = c.title + " wird beim nächsten Start entfernt.";
        return result;
    }
    std::filesystem::remove_all(dir(c), ec);
    result.message = ec ? c.title + ": nicht vollständig entfernt: " + error_code_text(ec) : c.title + " ist entfernt.";
    return result;
}

ChangeResult Store::keep(const Component& c) {
    ChangeResult result;
    std::error_code ec;
    std::filesystem::remove(special(root_, L".remove-", c.id), ec);
    result.message = ec ? c.title + ": Vormerkung nicht entfernbar: " + error_code_text(ec)
                        : c.title + " bleibt installiert.";
    return result;
}

std::vector<std::string> Store::cleanup(const Catalog& catalog) {
    std::vector<std::string> lines;
    std::error_code ec;
    if (!present(root_)) {
        return lines;
    }
    std::vector<std::filesystem::path> entries;
    for (auto it = std::filesystem::directory_iterator(root_, ec);
         !ec && it != std::filesystem::directory_iterator(); it.increment(ec)) {
        entries.push_back(it->path());
    }
    for (const std::filesystem::path& p : entries) {
        const std::string name = u8str(p.filename());
        if (name.starts_with(".staging-") || name.starts_with(".old-")) {
            remove_tree(p);
            continue;
        }
        const bool pending = name.starts_with(".pending-");
        const bool removal = name.starts_with(".remove-");
        if (!pending && !removal) {
            continue;
        }
        const std::string id = name.substr(pending ? 9 : 8);
        const Component* c = catalog.find(id);
        if (c == nullptr) {
            remove_tree(p);  // unbekannter Kern (aus einer älteren App-Fassung): nicht einsetzen
            continue;
        }
        const std::filesystem::path target = dir(*c);
        if (removal) {
            std::filesystem::remove_all(target, ec);
            std::filesystem::remove(p, ec);
            lines.push_back("Komponente " + id + " entfernt (vorgemerkt)");
            continue;
        }
        std::filesystem::remove_all(target, ec);
        if (ec) {
            lines.push_back("Komponente " + id + ": alter Stand nicht löschbar (" + error_code_text(ec) +
                            ") – neue Fassung bleibt bereit");
            continue;
        }
        std::filesystem::rename(p, target, ec);
        lines.push_back(ec ? "Komponente " + id + ": Einsetzen gescheitert (" + error_code_text(ec) + ")"
                           : "Komponente " + id + ": neue Fassung eingesetzt");
    }
    return lines;
}

}  // namespace swbh::components
