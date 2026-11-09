#include "voice/piper.hpp"

#include "base/fileio.hpp"
#include "base/json.hpp"
#include "base/log.hpp"
#include "base/paths.hpp"
#include "base/utf8.hpp"

#include <windows.h>

#include <atomic>
#include <cstdio>
#include <mutex>
#include <system_error>
#include <vector>

namespace swbh::voice {

namespace {

unsigned next_instance() {
    static std::atomic<unsigned> n{0};
    return ++n;
}

bool is_ascii(std::wstring_view w) {
    for (wchar_t c : w) {
        if (c < 0x20 || c > 0x7E) {
            return false;
        }
    }
    return true;
}

struct Handle {
    HANDLE h = nullptr;
    Handle() = default;
    explicit Handle(HANDLE v) : h(v) {}
    ~Handle() { reset(); }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    void reset(HANDLE v = nullptr) {
        if (h != nullptr && h != INVALID_HANDLE_VALUE) {
            CloseHandle(h);
        }
        h = v;
    }
};

}  // namespace

std::optional<std::string> ascii_path(const std::filesystem::path& p) {
    const std::wstring w = p.wstring();
    if (is_ascii(w)) {
        return narrow(w);
    }
    const DWORD need = GetShortPathNameW(w.c_str(), nullptr, 0);
    if (need == 0) {
        return std::nullopt;
    }
    std::wstring s(need, L'\0');
    const DWORD got = GetShortPathNameW(w.c_str(), s.data(), need);
    if (got == 0 || got >= need) {
        return std::nullopt;
    }
    s.resize(got);
    if (!is_ascii(s)) {
        return std::nullopt;  // 8.3-Namen auf dem Laufwerk abgeschaltet
    }
    return narrow(s);
}

std::string piper_line(std::string_view text, std::string_view output_file) {
    Json j = Json::object();
    j["text"] = std::string(text);
    j["output_file"] = std::string(output_file);
    return dump_compact(j) + "\n";
}

struct Piper::Impl {
    Handle process;
    Handle job;
    Handle stdin_write;
    Handle stdout_read;
    std::string work_dir;  // ASCII
    std::string buffer;    // gelesene, noch nicht verbrauchte stdout-Bytes
    std::atomic<bool> alive{false};
    std::mutex stop_mutex;
    unsigned counter = 0;
    // Eigene Nummer je Instanz: Mehrere Piper schreiben in dasselbe Arbeitsverzeichnis (kraken).
    unsigned instance = next_instance();

    bool read_line(std::string& line) {
        for (;;) {
            if (const std::size_t nl = buffer.find('\n'); nl != std::string::npos) {
                line = buffer.substr(0, nl);
                buffer.erase(0, nl + 1);
                while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) {
                    line.pop_back();
                }
                return true;
            }
            char chunk[512];
            DWORD got = 0;
            if (!ReadFile(stdout_read.h, chunk, sizeof chunk, &got, nullptr) || got == 0) {
                return false;  // Prozess beendet oder gestoppt
            }
            buffer.append(chunk, got);
            if (buffer.size() > 64 * 1024) {
                return false;  // Piper schreibt hier nur Pfade – so viel Text ist ein Fehler
            }
        }
    }
};

Piper::Piper() : impl_(std::make_unique<Impl>()) {}

Piper::~Piper() { stop(); }

bool Piper::running() const { return impl_->alive; }

bool Piper::start(const PiperOptions& o, std::string& error) {
    stop();
    std::error_code ec;
    std::filesystem::create_directories(o.work_dir, ec);
    const auto exe = ascii_path(o.exe);
    const auto model = ascii_path(o.model);
    const auto work = ascii_path(o.work_dir);
    if (!exe || !model || !work) {
        error = "Pfad zu Piper, Stimme oder Arbeitsordner lässt sich nicht als ASCII ausdrücken (Sonderzeichen im "
                "Benutzernamen und 8.3-Namen abgeschaltet)";
        return false;
    }
    impl_->work_dir = *work;

    SECURITY_ATTRIBUTES sa{sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
    HANDLE in_r = nullptr;
    HANDLE in_w = nullptr;
    HANDLE out_r = nullptr;
    HANDLE out_w = nullptr;
    if (!CreatePipe(&in_r, &in_w, &sa, 0) || !CreatePipe(&out_r, &out_w, &sa, 0)) {
        error = "Pipes für Piper nicht anlegbar (" + std::to_string(GetLastError()) + ")";
        return false;
    }
    Handle child_in(in_r);
    Handle child_out(out_w);
    impl_->stdin_write.reset(in_w);
    impl_->stdout_read.reset(out_r);
    // Nur die Kind-Enden vererben.
    SetHandleInformation(in_w, HANDLE_FLAG_INHERIT, 0);
    SetHandleInformation(out_r, HANDLE_FLAG_INHERIT, 0);
    Handle nul(CreateFileW(L"NUL", GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, &sa, OPEN_EXISTING, 0, nullptr));

    char scale[96];
    std::snprintf(scale, sizeof scale, "%.2f --noise_scale %.3f --noise_w %.3f", static_cast<double>(o.length_scale),
                  static_cast<double>(o.noise_scale), static_cast<double>(o.noise_w));
    std::wstring cmd_buf = L"\"" + widen(*exe) + L"\" --model \"" + widen(*model) +
                           L"\" --json-input --quiet --length_scale " + widen(scale);

    // Nur die drei Standard-Handles erben (PROC_THREAD_ATTRIBUTE_HANDLE_LIST), nicht jedes vererbbare Handle der App.
    SIZE_T attr_size = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &attr_size);
    std::vector<unsigned char> attr_buf(attr_size);
    auto* attrs = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attr_buf.data());
    if (!InitializeProcThreadAttributeList(attrs, 1, 0, &attr_size)) {
        error = "Prozessattribute nicht anlegbar";
        return false;
    }
    HANDLE inherit[3] = {child_in.h, child_out.h, nul.h};
    UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherit, sizeof inherit, nullptr, nullptr);

    STARTUPINFOEXW si{};
    si.StartupInfo.cb = sizeof si;
    si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    si.StartupInfo.hStdInput = child_in.h;
    si.StartupInfo.hStdOutput = child_out.h;
    si.StartupInfo.hStdError = nul.h;
    si.lpAttributeList = attrs;
    PROCESS_INFORMATION pi{};
    const std::wstring dir = o.exe.parent_path().wstring();
    const BOOL ok = CreateProcessW(o.exe.wstring().c_str(), cmd_buf.data(), nullptr, nullptr, TRUE,
                                   CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED, nullptr,
                                   dir.c_str(), &si.StartupInfo, &pi);
    DeleteProcThreadAttributeList(attrs);
    if (!ok) {
        error = "piper.exe startet nicht (Fehler " + std::to_string(GetLastError()) + ")";
        return false;
    }
    impl_->process.reset(pi.hProcess);
    Handle thread(pi.hThread);
    impl_->job.reset(CreateJobObjectW(nullptr, nullptr));
    if (impl_->job.h != nullptr) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION li{};
        li.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        SetInformationJobObject(impl_->job.h, JobObjectExtendedLimitInformation, &li, sizeof li);
        if (!AssignProcessToJobObject(impl_->job.h, pi.hProcess)) {
            log::warn("Piper: Job-Objekt nicht zuweisbar (" + std::to_string(GetLastError()) + ")");
        }
    }
    ResumeThread(thread.h);
    impl_->alive = true;
    impl_->buffer.clear();
    log::info("Piper gestartet: " + u8str(o.model.filename()) + " (PID " + std::to_string(pi.dwProcessId) + ")");
    return true;
}

std::optional<Pcm> Piper::synth(std::string_view text, std::string& error) {
    if (!impl_->alive) {
        error = "Piper läuft nicht";
        return std::nullopt;
    }
    const std::string file = impl_->work_dir + "\\s" + std::to_string(impl_->instance) + "_" +
                             std::to_string(++impl_->counter % 1000) + ".wav";
    const std::string line = piper_line(text, file);
    DWORD written = 0;
    if (!WriteFile(impl_->stdin_write.h, line.data(), static_cast<DWORD>(line.size()), &written, nullptr) ||
        written != line.size()) {
        error = "Piper nimmt keinen Text an (beendet?)";
        impl_->alive = false;
        return std::nullopt;
    }
    std::string back;
    if (!impl_->read_line(back)) {
        error = "Piper hat keinen Ton geliefert (beendet?)";
        impl_->alive = false;
        return std::nullopt;
    }
    const std::filesystem::path wav = u8path(file);
    const auto bytes = read_file_if_exists(wav, 64 * 1024 * 1024);
    std::error_code ec;
    std::filesystem::remove(wav, ec);
    if (!bytes) {
        error = "WAV von Piper fehlt: " + file;
        return std::nullopt;
    }
    return parse_wav(*bytes, error);
}

void Piper::stop() {
    std::lock_guard<std::mutex> lock(impl_->stop_mutex);
    if (impl_->process.h != nullptr) {
        // Erst stdin schließen (Piper endet von selbst), dann hart beenden, falls es hängt.
        impl_->stdin_write.reset();
        if (WaitForSingleObject(impl_->process.h, 1500) != WAIT_OBJECT_0) {
            TerminateProcess(impl_->process.h, 1);
            WaitForSingleObject(impl_->process.h, 2000);
        }
    }
    impl_->alive = false;
    impl_->process.reset();
    impl_->stdout_read.reset();
    impl_->stdin_write.reset();
    impl_->job.reset();
}

}  // namespace swbh::voice
