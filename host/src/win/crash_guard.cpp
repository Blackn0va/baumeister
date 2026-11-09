#include "win/crash_guard.hpp"

#include "base/log.hpp"
#include "base/paths.hpp"
#include "base/utf8.hpp"
#include "win/dll_guard.hpp"

#include <windows.h>

#include <dbghelp.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <memory>
#include <system_error>
#include <vector>

namespace swbh::win {

namespace {

using MiniDumpWriteDumpFn = BOOL(WINAPI*)(HANDLE, DWORD, HANDLE, MINIDUMP_TYPE, PMINIDUMP_EXCEPTION_INFORMATION,
                                          PMINIDUMP_USER_STREAM_INFORMATION, PMINIDUMP_CALLBACK_INFORMATION);

constexpr std::wstring_view kDumpPrefix = L"baumeister-absturz-";
constexpr std::wstring_view kDumpSuffix = L".dmp";
constexpr DWORD kReportTimeoutMs = 15000;

// Einmal geladen, nie freigegeben: Im Absturz soll kein LoadLibrary mehr laufen (Loader-Sperre, beschädigter Heap).
MiniDumpWriteDumpFn dump_writer() {
    static const MiniDumpWriteDumpFn fn = [] {
        wchar_t sys[MAX_PATH];
        const UINT n = GetSystemDirectoryW(sys, MAX_PATH);
        if (n == 0 || n >= MAX_PATH) {
            return MiniDumpWriteDumpFn{nullptr};
        }
        // Absoluter Pfad aus System32 (Hausregel: DLLs nur über absoluten Pfad). Die dbghelp.dll im Programmordner
        // gibt es nicht – wer eine dort ablegt, wird nicht geladen.
        const LoadedModule m = load_library_absolute(std::filesystem::path(sys) / L"dbghelp.dll");
        return reinterpret_cast<MiniDumpWriteDumpFn>(find_export(m.handle, "MiniDumpWriteDump"));
    }();
    return fn;
}

std::string two(int v) {
    char buf[4];
    std::snprintf(buf, sizeof buf, "%02d", v);
    return buf;
}

bool is_dump_name(const std::wstring& name) {
    return name.size() > kDumpPrefix.size() + kDumpSuffix.size() && name.starts_with(kDumpPrefix) &&
           name.ends_with(kDumpSuffix);
}

std::string exception_name(unsigned long code) {
    switch (code) {
        case 0xC0000005UL: return "Zugriffsverletzung";
        case 0xC00000FDUL: return "Stapelüberlauf";
        case 0xC0000094UL: return "Ganzzahl-Division durch null";
        case 0xC000001DUL: return "unzulässiger Befehl";
        case 0xC0000374UL: return "Heap beschädigt";
        case 0xC0000409UL: return "Pufferüberlauf erkannt";
        case 0xC0000006UL: return "Seitenfehler beim Einlesen";
        case 0xE06D7363UL: return "C++-Ausnahme ohne catch";
        case 0x80000003UL: return "Haltepunkt";
        default: return "unbekannte Ausnahme";
    }
}

struct Job {
    std::filesystem::path dir;
    EXCEPTION_POINTERS* exception = nullptr;
    DWORD thread_id = 0;
    std::string cause;
    std::optional<std::filesystem::path> result;
};

DWORD WINAPI report_thread(void* param) {
    Job& job = *static_cast<Job*>(param);
    const std::time_t now = std::time(nullptr);
    std::tm local{};
    localtime_s(&local, &now);
    const std::wstring name = crash_dump_name(local);
    const std::filesystem::path file = job.dir / name;

    std::optional<std::wstring> written;
    std::string error;
    const MiniDumpWriteDumpFn write = dump_writer();
    if (write == nullptr) {
        error = "dbghelp.dll nicht geladen";
    } else {
        HANDLE h = CreateFileW(file.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (h == INVALID_HANDLE_VALUE) {
            error = "Datei nicht anlegbar, Windows-Fehler " + std::to_string(GetLastError());
        } else {
            MINIDUMP_EXCEPTION_INFORMATION info{};
            info.ThreadId = job.thread_id;
            info.ExceptionPointers = job.exception;
            info.ClientPointers = FALSE;
            // Ohne Modellspeicher (der stünde mit MiniDumpWithFullMemory in voller Größe drin, 8 GB beim 14B-Modell):
            // Stapel aller Threads, Thread- und Modullisten und der Speicher, auf den die Stapel zeigen.
            const auto type = static_cast<MINIDUMP_TYPE>(MiniDumpWithIndirectlyReferencedMemory | MiniDumpWithThreadInfo |
                                                         MiniDumpWithUnloadedModules);
            const BOOL ok = write(GetCurrentProcess(), GetCurrentProcessId(), h, type,
                                  job.exception != nullptr ? &info : nullptr, nullptr, nullptr);
            const DWORD werr = ok ? 0 : GetLastError();
            CloseHandle(h);
            if (ok) {
                written = name;
                job.result = file;
            } else {
                error = "MiniDumpWriteDump scheiterte, Windows-Fehler " + std::to_string(werr);
                DeleteFileW(file.c_str());
            }
        }
    }
    // Erst jetzt die Logzeile: Hielt der abgestürzte Thread den Log-Mutex, hängt dieser Thread hier – das Abbild ist
    // dann schon geschrieben, und der Aufrufer wartet nur kReportTimeoutMs.
    log::error(crash_log_line(job.cause, written, error));
    return 0;
}

std::filesystem::path g_dump_dir;          // einmal in install_crash_guard gesetzt
std::atomic<bool> g_reported{false};       // erster Absturz zählt

void report_once(EXCEPTION_POINTERS* exception, const std::string& cause) {
    if (g_reported.exchange(true)) {
        return;
    }
    write_crash_report(g_dump_dir, exception, cause);
}

LONG WINAPI unhandled_filter(EXCEPTION_POINTERS* exception) {
    const EXCEPTION_RECORD* r = exception != nullptr ? exception->ExceptionRecord : nullptr;
    report_once(exception, r != nullptr ? exception_cause(r->ExceptionCode,
                                                          reinterpret_cast<std::uintptr_t>(r->ExceptionAddress))
                                        : std::string("Ausnahme ohne Angaben"));
    // Weiter an Windows: Fehlerbericht und Ereignis 1000 in der Ereignisanzeige bleiben (siehe Kopf).
    return EXCEPTION_CONTINUE_SEARCH;
}

[[noreturn]] void on_terminate() {
    std::string cause = "std::terminate";
    if (const std::exception_ptr e = std::current_exception()) {
        try {
            std::rethrow_exception(e);
        } catch (const std::exception& ex) {
            cause += ": " + log::safe(ex.what());
        } catch (...) {
            cause += ": Ausnahme ohne Text";
        }
    }
    report_once(nullptr, cause);
    std::abort();
}

}  // namespace

std::wstring crash_dump_name(const std::tm& local) {
    const std::string name = "baumeister-absturz-" + std::to_string(local.tm_year + 1900) + two(local.tm_mon + 1) +
                             two(local.tm_mday) + "-" + two(local.tm_hour) + two(local.tm_min) + two(local.tm_sec) +
                             ".dmp";
    return std::wstring(name.begin(), name.end());  // nur ASCII, Umwandlung ist verlustfrei
}

std::string exception_cause(unsigned long code, std::uintptr_t address) {
    char buf[96];
    std::snprintf(buf, sizeof buf, "Ausnahme 0x%08lX (%s) bei 0x%016llX", code, exception_name(code).c_str(),
                  static_cast<unsigned long long>(address));
    return buf;
}

std::string crash_log_line(std::string_view cause, std::optional<std::wstring> dump_file, std::string_view dump_error) {
    std::string line = "Absturz: " + std::string(cause);
    if (dump_file) {
        line += " – Abbild gespeichert: " + narrow(*dump_file) + " (im Log-Ordner, bitte mit dieser Logdatei weitergeben)";
    } else {
        line += " – kein Abbild (" + std::string(dump_error) + ")";
    }
    return line;
}

std::size_t prune_crash_dumps(const std::filesystem::path& dir, std::size_t keep) {
    std::vector<std::filesystem::path> dumps;
    std::error_code ec;
    for (std::filesystem::directory_iterator it(dir, ec), end; !ec && it != end; it.increment(ec)) {
        if (it->is_regular_file(ec) && is_dump_name(it->path().filename().wstring())) {
            dumps.push_back(it->path());
        }
    }
    if (dumps.size() <= keep) {
        return 0;
    }
    std::sort(dumps.begin(), dumps.end());
    std::size_t removed = 0;
    for (std::size_t i = 0; i + keep < dumps.size(); ++i) {
        // Einzelne Fehler überspringen: Ein Abbild kann gerade im Debugger offen sein.
        if (std::filesystem::remove(dumps[i], ec)) {
            ++removed;
        }
    }
    return removed;
}

bool install_crash_guard(const std::filesystem::path& dump_dir) {
    g_dump_dir = dump_dir;
    const bool have_dbghelp = dump_writer() != nullptr;
    SetUnhandledExceptionFilter(unhandled_filter);
    std::set_terminate(on_terminate);
    return have_dbghelp;
}

std::optional<std::filesystem::path> write_crash_report(const std::filesystem::path& dump_dir,
                                                        _EXCEPTION_POINTERS* exception, std::string_view cause) {
    // Auf dem Heap: Hängt der Hilfsthread, liest er job noch, wenn diese Funktion längst zurück ist.
    auto job = std::make_unique<Job>();
    job->dir = dump_dir;
    job->exception = exception;
    job->thread_id = GetCurrentThreadId();
    job->cause = std::string(cause);
    HANDLE t = CreateThread(nullptr, 256 * 1024, report_thread, job.get(), 0, nullptr);
    if (t == nullptr) {
        // Kein Thread mehr möglich (Speicher erschöpft): wenigstens die Zeile, ohne Abbild.
        log::error(crash_log_line(cause, std::nullopt, "kein Hilfsthread, Windows-Fehler " +
                                                           std::to_string(GetLastError())));
        return std::nullopt;
    }
    const DWORD wait = WaitForSingleObject(t, kReportTimeoutMs);
    CloseHandle(t);
    if (wait != WAIT_OBJECT_0) {
        // Absichtlich nicht freigegeben: Der hängende Hilfsthread liest job weiter. Im Absturz endet der Prozess gleich.
        static_cast<void>(job.release());
        return std::nullopt;
    }
    return job->result;
}

}  // namespace swbh::win
