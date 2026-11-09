#include "tunnel/tunnel.hpp"

#include "base/log.hpp"
#include "base/paths.hpp"
#include "base/utf8.hpp"

#include <windows.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cwctype>
#include <vector>

namespace swbh::tunnel {

namespace {

bool valid_port(int p) { return p >= 1 && p <= 65535; }

// Kurzlebige Passwortdatei für plink -pwfile (Begründung in tunnel.hpp, TunnelConfig::password). Neu angelegt
// (CREATE_NEW – eine untergeschobene Datei gleichen Namens wird nie benutzt), Rechte vom Datenordner geerbt (nur dieses
// Konto). remove() überschreibt den Inhalt vor dem Löschen; der Destruktor räumt auch auf jedem Fehlerweg auf.
class PasswordFile final {
public:
    PasswordFile() = default;
    ~PasswordFile() { remove(); }
    PasswordFile(const PasswordFile&) = delete;
    PasswordFile& operator=(const PasswordFile&) = delete;

    bool create(const std::filesystem::path& dir, const std::string& password) {
        std::error_code ec;
        std::filesystem::create_directories(dir, ec);
        static std::atomic<unsigned> counter{0};
        const std::filesystem::path file =
            dir / (L"plink-" + std::to_wstring(GetCurrentProcessId()) + L"-" + std::to_wstring(GetTickCount64()) + L"-" +
                   std::to_wstring(++counter) + L".tmp");
        HANDLE h = CreateFileW(file.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
                               FILE_ATTRIBUTE_TEMPORARY | FILE_ATTRIBUTE_HIDDEN, nullptr);
        if (h == INVALID_HANDLE_VALUE) {
            return false;
        }
        DWORD written = 0;
        const BOOL ok = WriteFile(h, password.data(), static_cast<DWORD>(password.size()), &written, nullptr);
        CloseHandle(h);
        path_ = file;
        size_ = password.size();
        if (!ok || written != password.size()) {
            remove();
            return false;
        }
        return true;
    }

    const std::filesystem::path& path() const { return path_; }

    void remove() {
        if (path_.empty()) {
            return;
        }
        HANDLE h = CreateFileW(path_.c_str(), GENERIC_WRITE, 0, nullptr, OPEN_EXISTING, 0, nullptr);
        if (h != INVALID_HANDLE_VALUE) {
            const std::string zeros(size_, '\0');
            DWORD written = 0;
            WriteFile(h, zeros.data(), static_cast<DWORD>(zeros.size()), &written, nullptr);
            CloseHandle(h);
        }
        std::error_code ec;
        std::filesystem::remove(path_, ec);
        if (ec) {
            log::warn("Tunnel: Passwortdatei nicht löschbar: " + u8str(path_));
        }
        path_.clear();
    }

private:
    std::filesystem::path path_;
    std::size_t size_ = 0;
};

// benutzer@host: Benutzer [A-Za-z0-9._-], Host [A-Za-z0-9.-]. Kein Leerzeichen, kein Anführungszeichen, kein „-“
// vorn – sonst ließe sich ein weiterer plink-Schalter unterschieben.
bool valid_target(std::string_view t) {
    const std::size_t at = t.find('@');
    if (at == std::string_view::npos || at == 0 || at + 1 >= t.size() || t.find('@', at + 1) != std::string_view::npos ||
        t.front() == '-' || t.size() > 128) {
        return false;
    }
    const auto user_ok = [](unsigned char c) { return std::isalnum(c) != 0 || c == '.' || c == '_' || c == '-'; };
    const auto host_ok = [](unsigned char c) { return std::isalnum(c) != 0 || c == '.' || c == '-'; };
    return std::all_of(t.begin(), t.begin() + static_cast<std::ptrdiff_t>(at), user_ok) &&
           std::all_of(t.begin() + static_cast<std::ptrdiff_t>(at) + 1, t.end(), host_ok) && t[at + 1] != '-';
}

// Fingerabdruck wie ihn PuTTY ausgibt: "SHA256:<base64>" oder hex mit Doppelpunkten.
bool valid_hostkey(std::string_view k) {
    if (k.empty() || k.size() > 128) {
        return false;
    }
    return std::all_of(k.begin(), k.end(), [](unsigned char c) {
        return std::isalnum(c) != 0 || c == ':' || c == '+' || c == '/' || c == '=';
    });
}

bool contains(std::string_view hay, std::string_view needle) { return hay.find(needle) != std::string_view::npos; }

void close_handle(void*& h) {
    if (h != nullptr) {
        CloseHandle(h);
        h = nullptr;
    }
}

}  // namespace

std::optional<std::string> validate(const TunnelConfig& c) {
    if (c.plink.empty() || !c.plink.is_absolute()) {
        return "plink-Pfad muss absolut sein (z. B. C:\\Program Files\\PuTTY\\plink.exe)";
    }
    std::wstring name = c.plink.filename().wstring();
    std::transform(name.begin(), name.end(), name.begin(), [](wchar_t ch) { return static_cast<wchar_t>(towlower(ch)); });
    if (name != L"plink.exe") {
        return "plink-Pfad muss auf plink.exe zeigen";
    }
    if (c.plink.native().find(L'"') != std::wstring::npos) {
        return "plink-Pfad enthält ein Anführungszeichen";
    }
    if (c.target.empty()) {
        return "Kein Minecraft-Server eingetragen – Einstellungen → Server: Adresse und Benutzer eintragen";
    }
    if (!valid_target(c.target)) {
        return "Ziel muss die Form benutzer@host haben (nur Buchstaben, Ziffern, . _ -)";
    }
    if (!valid_port(c.ssh_port) || !valid_port(c.remote_port) || !valid_port(c.local_port)) {
        return "Ports müssen zwischen 1 und 65535 liegen";
    }
    if (!c.hostkey.empty() && !valid_hostkey(c.hostkey)) {
        return "Hostschlüssel-Fingerabdruck enthält unzulässige Zeichen";
    }
    if (!c.password.empty()) {
        // plink liest nur die erste Zeile der Datei – ein Zeilenumbruch im Passwort würde still abgeschnitten.
        if (c.password.find_first_of("\r\n") != std::string::npos) {
            return "Passwort darf keinen Zeilenumbruch enthalten";
        }
        if (c.secret_dir.empty() || !c.secret_dir.is_absolute() ||
            c.secret_dir.native().find(L'"') != std::wstring::npos) {
            return "Ordner für die Passwortdatei fehlt (intern)";
        }
    }
    std::error_code ec;
    if (!std::filesystem::is_regular_file(c.plink, ec)) {
        return "plink.exe nicht gefunden: " + u8str(c.plink) + " (PuTTY installieren oder Pfad in den Einstellungen)";
    }
    return std::nullopt;
}

std::wstring command_line(const TunnelConfig& c, const std::filesystem::path& password_file) {
    std::wstring cmd = L"\"" + c.plink.wstring() + L"\" -batch -v -N -R 127.0.0.1:" +
                       std::to_wstring(c.remote_port) + L":127.0.0.1:" + std::to_wstring(c.local_port);
    if (c.ssh_port != 22) {
        cmd += L" -P " + std::to_wstring(c.ssh_port);
    }
    if (!c.hostkey.empty()) {
        cmd += L" -hostkey " + widen(c.hostkey);
    }
    if (!password_file.empty()) {
        cmd += L" -pwfile \"" + password_file.wstring() + L"\"";
    }
    cmd += L" " + widen(c.target);
    return cmd;
}

Signal classify_line(std::string_view line) {
    // Texte aus der ausführlichen Ausgabe von plink (PuTTY 0.7x/0.8x, ssh/portfwd.c, ssh/sharing.c, pageant.c).
    if (contains(line, "Remote port forwarding from") && contains(line, "enabled")) {
        return Signal::Forwarding;
    }
    if (contains(line, "Remote port forwarding from") && (contains(line, "refused") || contains(line, "failed"))) {
        return Signal::ForwardRefused;
    }
    if (contains(line, "Pageant has 0 SSH-2 keys") || contains(line, "Pageant has 0 keys")) {
        return Signal::PageantNoKeys;
    }
    if (contains(line, "Pageant is not running") || contains(line, "No Pageant")) {
        return Signal::PageantMissing;
    }
    if (contains(line, "host key is not cached") || contains(line, "Cannot confirm a host key in batch mode") ||
        contains(line, "does not match the configured key") || contains(line, "HOST KEY DOES NOT MATCH")) {
        return Signal::HostKeyUnknown;
    }
    // „Cannot answer interactive prompts“: Ohne Schlüssel aus Pageant fällt plink auf Passwort/keyboard-interactive
    // zurück und darf im Batch-Modus nicht fragen (gemessen auf dem GPU-PC 2026-10-28, Pageant ohne dev01-Schlüssel).
    if (contains(line, "No supported authentication methods") || contains(line, "Server refused our key") ||
        contains(line, "Access denied") || contains(line, "Cannot answer interactive prompts")) {
        return Signal::AuthFailed;
    }
    if (contains(line, "Network error") || contains(line, "Connection refused") || contains(line, "timed out") ||
        contains(line, "Host does not exist") || contains(line, "Network is unreachable")) {
        return Signal::Unreachable;
    }
    return Signal::None;
}

std::string_view signal_text(Signal s) {
    switch (s) {
    case Signal::ForwardRefused:
        return "Der Server lehnt die Weiterleitung ab – Port belegt (läuft dort noch ein alter Tunnel, z. B. start-llm.ps1?)";
    case Signal::PageantMissing: return "Pageant läuft nicht – Pageant starten und den Schlüssel für den Server laden";
    case Signal::PageantNoKeys: return "Pageant hat 0 Schlüssel – den Schlüssel für den Server in Pageant laden";
    case Signal::AuthFailed:
        return "Der Server verlangt eine Anmeldung, die plink ohne Schlüssel nicht geben kann – Pageant starten und den "
               "Schlüssel (.ppk) laden (Rechtsklick auf das Pageant-Symbol → Add Key)";
    case Signal::HostKeyUnknown:
        return "Hostschlüssel des Servers nicht bestätigt – einmal mit PuTTY verbinden oder Fingerabdruck eintragen";
    case Signal::Unreachable: return "Server nicht erreichbar (Netzwerk)";
    case Signal::None:
    case Signal::Forwarding: break;
    }
    return {};
}

std::chrono::seconds backoff(int attempt) {
    int s = 10;
    for (int i = 1; i < attempt && s < 60; ++i) {
        s *= 2;
    }
    return std::chrono::seconds(std::min(s, 60));
}

bool is_pageant_pipe(std::wstring_view name) {
    constexpr std::wstring_view prefix = L"pageant.";
    if (name.size() <= prefix.size()) {
        return false;
    }
    for (std::size_t i = 0; i < prefix.size(); ++i) {
        if (std::towlower(name[i]) != prefix[i]) {
            return false;
        }
    }
    return true;
}

// Vorher nur FindWindowW("Pageant"). App-Log GPU-PC 2026-11-05 17:27: „Pageant läuft nicht – Pageant starten …“ und
// eine Zeile später „Tunnel: verbunden“ – der Schlüssel kam von einem Agenten ohne dieses Fenster (YubiKey über
// gpg-agent bzw. Pageant über die benannte Pipe \\.\pipe\pageant.<Nutzer>.<Hash>, die plink seit 0.75 bevorzugt).
// Jetzt zählt auch die Pipe. Wer das zurückdreht, bekommt den Fehlalarm bei jedem Tunnelstart wieder.
bool pageant_running() {
    if (FindWindowW(L"Pageant", L"Pageant") != nullptr) {
        return true;
    }
    WIN32_FIND_DATAW fd{};
    HANDLE h = FindFirstFileW(L"\\\\.\\pipe\\*", &fd);
    if (h == INVALID_HANDLE_VALUE) {
        return false;
    }
    bool found = false;
    do {
        if (is_pageant_pipe(fd.cFileName)) {
            found = true;
            break;
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return found;
}

Tunnel::Tunnel(EventFn on_event) : on_event_(std::move(on_event)) {}

Tunnel::~Tunnel() { stop(); }

State Tunnel::state() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return state_;
}

void Tunnel::emit(State s, std::string text) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_ = s;
    }
    log::info("Tunnel: " + text);
    if (on_event_) {
        on_event_(Event{s, std::move(text)});
    }
}

bool Tunnel::start(const TunnelConfig& config, std::string& error) {
    if (thread_.joinable()) {
        return true;
    }
    if (const auto problem = validate(config)) {
        error = *problem;
        emit(State::Failed, error);
        return false;
    }
    job_ = CreateJobObjectW(nullptr, nullptr);
    if (job_ == nullptr) {
        error = "Job-Objekt nicht anlegbar: " + std::to_string(GetLastError());
        return false;
    }
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    SetInformationJobObject(job_, JobObjectExtendedLimitInformation, &limits, sizeof limits);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stop_requested_ = false;
    }
    thread_ = std::thread([this, config] { run(config); });
    return true;
}

void Tunnel::stop() {
    if (!thread_.joinable()) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stop_requested_ = true;
        if (process_ != nullptr) {
            TerminateProcess(process_, 0);  // Lese-Thread bekommt EOF, run() endet
        }
    }
    wake_.notify_all();
    thread_.join();
    close_handle(job_);  // KILL_ON_JOB_CLOSE räumt Reste ab
    emit(State::Stopped, "gestoppt");
}

void Tunnel::run(TunnelConfig config) {
    int attempt = 0;
    while (true) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stop_requested_) {
                return;
            }
        }
        // In die Konsole statt nur ins Protokoll: Auf dem GPU-PC sah man sonst nur plinks Folgefehler (2026-10-28).
        if (!pageant_running()) {
            emit(State::Starting, std::string(kPageantNotSeen));
        }
        // Ausgabe-Pipe: nur das Schreib-Ende wird vererbt, und nur dieses eine Handle (PROC_THREAD_ATTRIBUTE_HANDLE_LIST)
        // – sonst erbte plink jedes vererbbare Handle der App.
        SECURITY_ATTRIBUTES sa{sizeof sa, nullptr, TRUE};
        HANDLE read_end = nullptr;
        HANDLE write_end = nullptr;
        if (!CreatePipe(&read_end, &write_end, &sa, 0)) {
            emit(State::Failed, "Pipe für plink nicht anlegbar");
            return;
        }
        SetHandleInformation(read_end, HANDLE_FLAG_INHERIT, 0);
        SIZE_T attr_size = 0;
        InitializeProcThreadAttributeList(nullptr, 1, 0, &attr_size);
        std::vector<unsigned char> attr_buf(attr_size);
        auto* attrs = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attr_buf.data());
        InitializeProcThreadAttributeList(attrs, 1, 0, &attr_size);
        UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, &write_end, sizeof write_end, nullptr,
                                  nullptr);
        STARTUPINFOEXW si{};
        si.StartupInfo.cb = sizeof si;
        si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
        si.StartupInfo.hStdInput = nullptr;
        si.StartupInfo.hStdOutput = write_end;
        si.StartupInfo.hStdError = write_end;
        si.lpAttributeList = attrs;
        PROCESS_INFORMATION pi{};
        PasswordFile pwfile;
        if (!config.password.empty() && !pwfile.create(config.secret_dir, config.password)) {
            CloseHandle(read_end);
            CloseHandle(write_end);
            DeleteProcThreadAttributeList(attrs);
            emit(State::Failed, "Passwortdatei für plink nicht anlegbar (" + u8str(config.secret_dir) + ")");
            return;
        }
        std::wstring cmd = command_line(config, pwfile.path());
        const std::wstring dir = config.plink.parent_path().wstring();
        // CREATE_SUSPENDED: erst ins Job-Objekt, dann laufen lassen – sonst könnte plink vor der Zuordnung enden.
        const BOOL ok = CreateProcessW(config.plink.c_str(), cmd.data(), nullptr, nullptr, TRUE,
                                       CREATE_NO_WINDOW | CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT, nullptr,
                                       dir.c_str(), &si.StartupInfo, &pi);
        const DWORD create_error = ok ? 0 : GetLastError();
        DeleteProcThreadAttributeList(attrs);
        CloseHandle(write_end);
        if (!ok) {
            CloseHandle(read_end);
            emit(State::Failed, "plink nicht startbar (Fehler " + std::to_string(create_error) + ")");
            return;
        }
        if (!AssignProcessToJobObject(job_, pi.hProcess)) {
            log::warn("Tunnel: plink nicht im Job-Objekt (Fehler " + std::to_string(GetLastError()) +
                      ") – endet die App abrupt, läuft plink weiter");
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            process_ = pi.hProcess;
            if (stop_requested_) {
                TerminateProcess(pi.hProcess, 0);
            }
        }
        ResumeThread(pi.hThread);
        CloseHandle(pi.hThread);
        ++attempt;
        emit(State::Starting, "plink gestartet (Versuch " + std::to_string(attempt) + "): " + config.target + ", " +
                                  "Server 127.0.0.1:" + std::to_string(config.remote_port) + " → hier 127.0.0.1:" +
                                  std::to_string(config.local_port));

        // Ausgabe zeilenweise lesen, bis plink endet (EOF).
        std::string pending;
        Signal last_problem = Signal::None;
        // Letzte nicht gedeutete Zeile: Vorher meldete die App bei unbekannten Fehlern nur „plink beendet (Exit 1)“ –
        // plinks eigentlicher Grund ging verloren (gemeldet vom GPU-PC 2026-10-28, Versuche 6–11).
        std::string last_other;
        bool connected = false;
        auto connected_since = std::chrono::steady_clock::now();
        char buf[1024];
        DWORD n = 0;
        while (ReadFile(read_end, buf, sizeof buf, &n, nullptr) && n > 0) {
            // Erste Ausgabe = plink hat die Befehlszeile samt -pwfile gelesen (gemessen, siehe tunnel.hpp) → weg damit.
            pwfile.remove();
            pending.append(buf, n);
            std::size_t nl = 0;
            while ((nl = pending.find('\n')) != std::string::npos) {
                std::string line = pending.substr(0, nl);
                pending.erase(0, nl + 1);
                while (!line.empty() && (line.back() == '\r' || line.back() == '\n')) {
                    line.pop_back();
                }
                if (line.empty()) {
                    continue;
                }
                const Signal sig = classify_line(line);
                if (sig == Signal::Forwarding && !connected) {
                    connected = true;
                    connected_since = std::chrono::steady_clock::now();
                    emit(State::Connected, "verbunden – das Plugin erreicht diese App über Server 127.0.0.1:" +
                                               std::to_string(config.remote_port));
                } else if (sig != Signal::None && sig != Signal::Forwarding) {
                    // Dieselbe Ursache nur einmal je Versuch melden (plink schreibt den Hostschlüssel-Hinweis über
                    // mehrere Zeilen – vorher stand er viermal hintereinander in der Konsole).
                    if (sig != last_problem) {
                        emit(State::Starting, std::string(signal_text(sig)));
                    }
                    last_problem = sig;
                    if (sig == Signal::ForwardRefused) {
                        // plink -N läuft ohne Weiterleitung weiter (kein ExitOnForwardFailure) – beenden und neu
                        // versuchen, sonst stünde „läuft“ ohne Tunnel da.
                        TerminateProcess(pi.hProcess, 1);
                    }
                } else {
                    log::info("plink: " + log::safe(line, 200));
                    if (line.find("Looking up host") == std::string::npos &&
                        line.find("Connecting to") == std::string::npos) {
                        last_other = line;
                    }
                }
            }
        }
        // Letzte Zeile ohne Zeilenumbruch (plink endet z. B. mit „Connection abandoned.“ ohne Umbruch) – vorher
        // verworfen.
        while (!pending.empty() && (pending.back() == '\r' || pending.back() == '\n' || pending.back() == ' ')) {
            pending.pop_back();
        }
        if (!pending.empty()) {
            log::info("plink: " + log::safe(pending, 200));
            const Signal sig = classify_line(pending);
            if (sig != Signal::None && sig != Signal::Forwarding) {
                last_problem = sig;
            } else {
                last_other = pending;
            }
        }
        CloseHandle(read_end);
        pwfile.remove();  // auch wenn plink nichts geschrieben hat
        WaitForSingleObject(pi.hProcess, 5000);
        DWORD exit_code = 0;
        GetExitCodeProcess(pi.hProcess, &exit_code);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            process_ = nullptr;
        }
        CloseHandle(pi.hProcess);

        std::unique_lock<std::mutex> lock(mutex_);
        if (stop_requested_) {
            return;
        }
        // Nach mindestens 60 s stabiler Verbindung beginnt das Backoff von vorn (Router-Neustart o. Ä.).
        if (connected && std::chrono::steady_clock::now() - connected_since > std::chrono::seconds(60)) {
            attempt = 1;
        }
        const auto wait = backoff(attempt);
        std::string why = last_problem != Signal::None ? std::string(signal_text(last_problem))
                                                       : "plink beendet (Exit " + std::to_string(exit_code) + ")" +
                                                             (last_other.empty() ? std::string()
                                                                                 : ": " + log::safe(last_other, 160));
        lock.unlock();
        emit(State::Waiting, why + " – neuer Versuch in " + std::to_string(wait.count()) + " s");
        lock.lock();
        wake_.wait_for(lock, wait, [this] { return stop_requested_; });
    }
}

}  // namespace swbh::tunnel
