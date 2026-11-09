#include "voice/piper_files.hpp"

#include "base/fileio.hpp"
#include "base/log.hpp"
#include "base/paths.hpp"
#include "win/cng.hpp"

#include <windows.h>

#include <stdexcept>
#include <string>
#include <system_error>
#include <vector>

namespace swbh::voice {

namespace {

std::filesystem::path marker(const std::filesystem::path& file) {
    std::filesystem::path m = file;
    m += L".verified";
    return m;
}

bool marked(const std::filesystem::path& file, std::uint64_t size, const std::string& sha) {
    std::error_code ec;
    const auto s = std::filesystem::file_size(file, ec);
    if (ec || s != size) {
        return false;
    }
    // Unlesbare Marke = keine Marke: fail-closed, die Datei gilt als fehlend (kraken).
    try {
        const auto m = read_file_if_exists(marker(file), 256);
        return m && *m == sha + "\n";
    } catch (const std::exception& e) {
        log::warn("Prüfmarke nicht lesbar: " + std::string(e.what()));
        return false;
    }
}

// Datei in voller Größe, aber ohne Marke: einmal lokal hashen statt neu laden.
bool adopt_existing(const std::filesystem::path& file, std::uint64_t size, const std::string& sha) {
    std::error_code ec;
    if (std::filesystem::file_size(file, ec) != size || ec) {
        return false;
    }
    const auto h = win::sha256_file_hex(file);
    if (h && *h == sha) {
        write_file_atomic(marker(file), sha + "\n");
        return true;
    }
    return false;
}

struct Part {
    std::string url;
    std::filesystem::path target;
    std::uint64_t size;
    std::string sha;
    std::vector<std::string> hosts;
    bool linked_etag;  // nur LFS-Dateien bei Hugging Face tragen X-Linked-ETag
};

const std::vector<std::string> kHfHosts = {"huggingface.co", "*.huggingface.co", "*.hf.co"};
// GitHub-Release-Assets leiten auf objects/release-assets.githubusercontent.com um.
const std::vector<std::string> kGitHubHosts = {"github.com", "*.githubusercontent.com"};

void fetch(win::HttpTransport& http, const Part& part, const win::ProgressFn& progress, win::CancelToken& cancel) {
    std::error_code ec;
    std::filesystem::remove(marker(part.target), ec);
    win::DownloadRequest req;
    req.url = part.url;
    req.target = part.target;
    req.expected_size = part.size;
    req.expected_sha256 = part.sha;
    req.user_agent = "StreamWizard-Baumeister";
    req.redirects = win::Redirects::HttpsAllowList;
    req.redirect_host_suffixes = part.hosts;
    req.resume = true;
    req.check_linked_etag = part.linked_etag;
    req.finalize = true;
    const win::DownloadResult r = http.download(req, progress, cancel);
    write_file_atomic(marker(r.file), part.sha + "\n");
}

// tar.exe aus System32 ohne Konsolenfenster (bsdtar lehnt „..“ und absolute Pfade ab); wartet auf das Ende.
void extract_zip(const std::filesystem::path& zip, const std::filesystem::path& dest) {
    wchar_t sys[MAX_PATH];
    const UINT n = GetSystemDirectoryW(sys, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) {
        throw std::runtime_error("System32 nicht gefunden");
    }
    const std::filesystem::path tar = std::filesystem::path(sys) / L"tar.exe";
    if (!std::filesystem::exists(tar)) {
        throw std::runtime_error("tar.exe fehlt (Windows 10 1803 oder neuer nötig)");
    }
    std::wstring cmd = L"\"" + tar.wstring() + L"\" -xf \"" + zip.wstring() + L"\" -C \"" + dest.wstring() + L"\"";
    STARTUPINFOW si{};
    si.cb = sizeof si;
    PROCESS_INFORMATION pi{};
    if (!CreateProcessW(tar.c_str(), cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si,
                        &pi)) {
        throw std::runtime_error("tar.exe startet nicht (" + std::to_string(GetLastError()) + ")");
    }
    CloseHandle(pi.hThread);
    if (WaitForSingleObject(pi.hProcess, 120000) != WAIT_OBJECT_0) {
        TerminateProcess(pi.hProcess, 1);
        WaitForSingleObject(pi.hProcess, 5000);
        CloseHandle(pi.hProcess);
        throw std::runtime_error("Entpacken von Piper dauert zu lange (tar nach 120 s beendet)");
    }
    DWORD code = 1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    if (code != 0) {
        throw std::runtime_error("Entpacken von Piper fehlgeschlagen (tar Exit " + std::to_string(code) + ")");
    }
}

std::filesystem::path piper_marker(const VoicePaths& p) { return p.root / L"piper" / L"piper.zip.verified"; }

}  // namespace

std::filesystem::path VoicePaths::piper_exe() const { return root / u8path(piper_spec().exe); }
std::filesystem::path VoicePaths::voice_model(const VoiceSpec& v) const { return root / L"voices" / u8path(v.file); }
std::filesystem::path VoicePaths::work_dir() const { return root / L"tmp"; }

bool piper_ready(const VoicePaths& p) {
    std::error_code ec;
    try {
        const auto m = read_file_if_exists(piper_marker(p), 256);
        return std::filesystem::is_regular_file(p.piper_exe(), ec) && m && *m == piper_spec().sha256 + "\n";
    } catch (const std::exception&) {
        return false;  // unlesbare Marke = nicht bereit (fail-closed)
    }
}

bool voice_ready(const VoicePaths& p, const VoiceSpec& v) {
    std::filesystem::path json = p.voice_model(v);
    json += L".json";
    return marked(p.voice_model(v), v.size, v.sha256) && marked(json, v.json_size, v.json_sha256);
}

void download_piper(win::HttpTransport& http, const VoicePaths& p, const VoiceSpec& v,
                    const win::ProgressFn& progress, win::CancelToken& cancel) {
    ensure_directory(p.root);
    std::filesystem::path json = p.voice_model(v);
    json += L".json";
    std::vector<Part> parts;
    const PiperSpec& pi = piper_spec();
    const std::filesystem::path zip = p.root / L"piper_windows_amd64.zip";
    if (!piper_ready(p)) {
        parts.push_back({pi.url, zip, pi.size, pi.sha256, kGitHubHosts, false});
    }
    if (!marked(p.voice_model(v), v.size, v.sha256) && !adopt_existing(p.voice_model(v), v.size, v.sha256)) {
        parts.push_back({v.onnx_url(), p.voice_model(v), v.size, v.sha256, kHfHosts, true});
    }
    if (!marked(json, v.json_size, v.json_sha256) && !adopt_existing(json, v.json_size, v.json_sha256)) {
        parts.push_back({v.json_url(), json, v.json_size, v.json_sha256, kHfHosts, false});
    }
    std::uint64_t total = 0;
    for (const Part& part : parts) {
        total += part.size;
    }
    std::uint64_t before = 0;
    for (const Part& part : parts) {
        ensure_directory(part.target.parent_path());
        fetch(http, part,
              [&](std::uint64_t done, std::uint64_t) {
                  if (progress) {
                      progress(before + done, total);
                  }
              },
              cancel);
        before += part.size;
    }
    if (!piper_ready(p)) {
        // Erst nach bestandener Hash-Prüfung entpacken; alter Stand wird vorher entfernt.
        std::error_code ec;
        std::filesystem::remove_all(p.root / L"piper", ec);
        extract_zip(zip, p.root);
        if (!std::filesystem::is_regular_file(p.piper_exe(), ec)) {
            throw std::runtime_error("piper.exe fehlt nach dem Entpacken");
        }
        write_file_atomic(piper_marker(p), pi.sha256 + "\n");
        std::filesystem::remove(zip, ec);
        std::filesystem::remove(marker(zip), ec);
        log::info("Piper entpackt nach " + u8str(p.root / L"piper"));
    }
}

}  // namespace swbh::voice
