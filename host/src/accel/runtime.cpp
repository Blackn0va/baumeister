#include "accel/runtime.hpp"

#include "base/errors.hpp"
#include "base/log.hpp"
#include "base/paths.hpp"
#include "components/store.hpp"
#include "win/dll_guard.hpp"
#include "win/fs_security.hpp"

#include <ggml-backend.h>
#include <ggml.h>

#include <windows.h>

#include <algorithm>
#include <cstdlib>
#include <mutex>

#ifndef SWBH_GGML_CPU_VARIANTS
#error "SWBH_GGML_CPU_VARIANTS fehlt (src/accel/CMakeLists.txt)"
#endif

namespace swbh::accel {

namespace {

struct Attempt {
    std::string key;  // Dateipfad (CPU/Vulkan) bzw. Komponenten-Kennung
    bool ok = false;
    std::size_t devices = 0;
};

// Zwei Sperren. Vorher hielt EINE Sperre die ganze load_component samt SHA-256-Prüfung (pin_files) und Kern-Init –
// component_in_use und cpu_variant im GUI-Thread warteten so lange (Seite „Rechenkerne“ fror beim Start mit CUDA
// 1–3 s ein). Jetzt serialisiert load_mutex das Laden (attempts, cookies und das Setzen von GGML_OPENVINO_DEVICE nur
// darunter), mutex schützt nur den Abfragezustand und wird jeweils kurz genommen. Reihenfolge immer load_mutex vor
// mutex. Wer die Abfragen wieder unter load_mutex legt, friert die Oberfläche während des Ladens wieder ein.
struct Global {
    std::mutex load_mutex;
    std::vector<Attempt> attempts;
    std::vector<win::DllDirectoryCookie> cookies;  // Freigaben bleiben bis Prozessende

    mutable std::mutex mutex;
    std::string cpu_file;
    std::vector<std::string> loaded_regs;
    std::vector<std::pair<std::string, components::PinnedFiles>> pinned;  // Kennung → gesperrte Dateien
    std::vector<std::string> loading;                                     // Kennungen, die gerade geprüft werden
    std::string openvino_target;                                          // Ziel beim Laden von OpenVINO

    std::shared_mutex registry;  // ggml-Geräteliste (registry_mutex)
};

Global& global() {
    static Global g;
    return g;
}

// Hält eine Komponente als „in Benutzung“, solange load_component sie prüft. Muss VOR dem PinnedFiles-Objekt
// angelegt werden: Dann wird es erst nach dessen Freigabe bzw. nach dem Eintrag in pinned zerstört, und
// component_in_use meldet in keinem Augenblick „frei“, solange die Dateien offen sind.
class LoadingMark final {
public:
    LoadingMark(Global& g, std::string id) : g_(g), id_(std::move(id)) {
        std::lock_guard<std::mutex> lock(g_.mutex);
        g_.loading.push_back(id_);
    }
    ~LoadingMark() {
        std::lock_guard<std::mutex> lock(g_.mutex);
        g_.loading.erase(std::find(g_.loading.begin(), g_.loading.end(), id_));
    }
    LoadingMark(const LoadingMark&) = delete;
    LoadingMark& operator=(const LoadingMark&) = delete;

private:
    Global& g_;
    std::string id_;
};

Attempt* find_attempt(Global& g, const std::string& key) {
    for (Attempt& a : g.attempts) {
        if (a.key == key) {
            return &a;
        }
    }
    return nullptr;
}

bool is_ascii(std::string_view s) {
    return std::all_of(s.begin(), s.end(), [](char c) { return static_cast<unsigned char>(c) < 0x80; });
}

// Die Kern-DLL IMMER selbst vorladen (LoadLibraryExW mit absolutem Pfad, Suche nur im Ordner der DLL, im EXE-Ordner,
// in System32 und in freigegebenen Ordnern – nie über PATH), erst dann ggml_backend_load. Zwei Gründe:
//  1. ggml lädt mit LoadLibraryW, also mit der Suchordnung des Prozesses. Ohne harden_dll_search (Tests, Werkzeuge)
//     fand ggml-cuda.dll so cuBLAS aus einem installierten CUDA-Toolkit über PATH statt aus der Komponente (in der
//     Messprobe 2026-10-14 bemerkt: OpenVINO scheiterte dort, CUDA „lief“ – mit fremdem cuBLAS). Vorgeladen sind alle
//     statischen Abhängigkeiten schon aufgelöst; ggml findet das geladene Modul und erhöht nur den Zähler.
//  2. ggml baut den Pfad über die ANSI-Codepage (ggml-backend-reg.cpp) – unter C:\Users\Jörg\… nicht ladbar (in
//     CaptionBridge gemessen). Dann bekommt ggml nur den ASCII-Dateinamen des schon geladenen Moduls.
// win32_error: Windows-Fehler des Vorladens (126 = Abhängigkeit fehlt, 193 = falsche Architektur), 0 sonst.
// direct_symbol: Registrierfunktion für Kerne ohne ggml_backend_init (siehe unten), leer = nur ggml_backend_load.
ggml_backend_reg_t backend_load(const std::filesystem::path& file, unsigned long& win32_error,
                                const std::string& direct_symbol = {}) {
    win32_error = 0;
    if (!file.is_absolute()) {
        win32_error = ERROR_INVALID_PARAMETER;
        return nullptr;
    }
    const win::LoadedModule pre = win::load_library_absolute(file);
    if (pre.handle == nullptr) {
        win32_error = pre.win32_error;
        return nullptr;
    }
    // Das OpenVINO-Paket von llama.cpp ist nicht mit GGML_BACKEND_DL gebaut: ggml-openvino.dll exportiert kein
    // ggml_backend_init, nur ggml_backend_openvino_reg (dumpbin /exports, 2026-10-14) – ggml_backend_load meldete
    // „failed to find ggml_backend_init“. Dann über die öffentliche Registrierung anmelden (ggml_backend_register).
    // Das Modul bleibt dann mit unserer Referenz geladen: ggml hält bei diesem Weg keine eigene.
    if (win::find_export(pre.handle, "ggml_backend_init") == nullptr && !direct_symbol.empty()) {
        void* fn = win::find_export(pre.handle, direct_symbol.c_str());
        ggml_backend_reg_t reg = fn != nullptr ? reinterpret_cast<ggml_backend_reg_t (*)()>(fn)() : nullptr;
        if (reg == nullptr) {
            win::free_library(pre.handle);
            return nullptr;
        }
        // Exklusiv: register_device hängt an die Geräteliste an, die andere Threads lesen (registry_mutex).
        std::unique_lock<std::shared_mutex> write(global().registry);
        ggml_backend_register(reg);
        return reg;
    }
    const std::string u8 = u8str(file);
    const std::string name = u8str(file.filename());
    ggml_backend_reg_t reg = nullptr;
    std::unique_lock<std::shared_mutex> write(global().registry);
    if (is_ascii(u8)) {
        reg = ggml_backend_load(u8.c_str());
    } else if (is_ascii(name)) {
        reg = ggml_backend_load(name.c_str());
    }
    write.unlock();
    win::free_library(pre.handle);  // ggml hält bei Erfolg eine eigene Referenz
    return reg;
}

std::string load_error_text(unsigned long win32_error) {
    if (win32_error == ERROR_MOD_NOT_FOUND) {
        return "eine benötigte DLL fehlt (Windows-Fehler 126)";
    }
    if (win32_error == ERROR_PROC_NOT_FOUND) {
        return "eine benötigte Funktion fehlt – Treiber oder Laufzeit zu alt (Windows-Fehler 127)";
    }
    return win32_error != 0 ? system_error_text(win32_error) : "Initialisierung des Kerns gescheitert";
}

bool is_cpu_variant_name(const std::string& lower_file_name) {
    constexpr std::string_view prefix = "ggml-cpu-";
    constexpr std::string_view suffix = ".dll";
    if (lower_file_name.size() <= prefix.size() + suffix.size() || !lower_file_name.starts_with(prefix) ||
        !lower_file_name.ends_with(suffix)) {
        return false;
    }
    const std::string_view variant = std::string_view(lower_file_name)
                                         .substr(prefix.size(), lower_file_name.size() - prefix.size() - suffix.size());
    std::string_view list = SWBH_GGML_CPU_VARIANTS;
    while (!list.empty()) {
        const std::size_t comma = list.find(',');
        if (list.substr(0, comma) == variant) {
            return true;
        }
        if (comma == std::string_view::npos) {
            break;
        }
        list.remove_prefix(comma + 1);
    }
    return false;
}

std::string lower(std::string s) {
    for (char& c : s) {
        if (c >= 'A' && c <= 'Z') {
            c = static_cast<char>(c - 'A' + 'a');
        }
    }
    return s;
}

// AMD HIP SDK (hipBLAS, rocBLAS): ggml-hip.dll importiert hipblas.dll, die im llama.cpp-Paket fehlt. Nur aus einem
// Ordner unter Program Files (dort schreibt nur ein Administrator) und nur, wenn hipblas.dll dort liegt.
std::optional<std::filesystem::path> hip_sdk_bin(std::string& why) {
    wchar_t buf[MAX_PATH];
    const DWORD n = GetEnvironmentVariableW(L"HIP_PATH", buf, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) {
        why = "AMD HIP SDK nicht gefunden (HIP_PATH fehlt).";
        return std::nullopt;
    }
    const std::filesystem::path bin = std::filesystem::path(buf) / L"bin";
    const auto real = win::final_path_of(bin);
    if (!bin.is_absolute() || !real) {
        why = "AMD HIP SDK: Ordner " + u8str(bin) + " fehlt.";
        return std::nullopt;
    }
    bool under_pf = false;
    for (const std::filesystem::path& root : win::program_files_roots()) {
        under_pf = under_pf || win::path_is_below(*real, root);
    }
    std::error_code ec;
    if (!under_pf || !std::filesystem::is_regular_file(*real / L"hipblas.dll", ec)) {
        why = "AMD HIP SDK unvollständig oder nicht unter Program Files (" + u8str(*real) + ").";
        return std::nullopt;
    }
    return *real;
}

}  // namespace

std::string_view reg_name(Backend b) {
    switch (b) {
        case Backend::Vulkan:
            return "Vulkan";
        case Backend::Cuda:
            return "CUDA";
        case Backend::OpenVino:
            return "OPENVINO";  // GGML_OPENVINO_NAME (ggml-openvino.h) – so heißt die Registrierung, nicht „OpenVINO“
        case Backend::Sycl:
            return "SYCL";
        case Backend::Rocm:
            return "ROCm";
    }
    return {};
}

std::optional<Backend> backend_from_reg(std::string_view reg) {
    for (Backend b : {Backend::Vulkan, Backend::Cuda, Backend::OpenVino, Backend::Sycl, Backend::Rocm}) {
        if (reg == reg_name(b)) {
            return b;
        }
    }
    return std::nullopt;
}

std::optional<Backend> backend_of(const components::Component& c) { return backend_from_reg(c.backend); }

std::optional<std::string> openvino_switch_note(std::string_view loaded, std::string_view requested) {
    if (loaded.empty() || requested.empty() || loaded == requested) {
        return std::nullopt;
    }
    return "OpenVINO rechnet in diesem Lauf schon auf " + std::string(loaded) + " – der Wechsel auf " +
           std::string(requested) + " wirkt erst nach einem Neustart von Baumeister-Host.";
}

std::shared_mutex& registry_mutex() { return global().registry; }

std::vector<ggml_backend_device*> registered_devices() {
    std::shared_lock<std::shared_mutex> read(global().registry);
    std::vector<ggml_backend_device*> out;
    const std::size_t n = ggml_backend_dev_count();
    out.reserve(n);
    for (std::size_t i = 0; i < n; ++i) {
        out.push_back(ggml_backend_dev_get(i));
    }
    return out;
}

void set_openvino_target(std::string_view device, const std::filesystem::path& cache_dir) {
    // Unter der Lade-Sperre: load_component liest die Variable (ggml beim Registrieren, wir für openvino_target).
    std::lock_guard<std::mutex> load(global().load_mutex);
    _putenv_s("GGML_OPENVINO_DEVICE", std::string(device).c_str());
    if (!cache_dir.empty()) {
        std::error_code ec;
        std::filesystem::create_directories(cache_dir, ec);
        _wputenv_s(L"GGML_OPENVINO_CACHE_DIR", cache_dir.c_str());
    }
}

Runtime& Runtime::get() {
    static Runtime r;
    return r;
}

std::filesystem::path Runtime::ggml_dir() const {
    HMODULE base = GetModuleHandleW(L"ggml-base.dll");
    if (base == nullptr) {
        return {};
    }
    std::wstring buf(1024, L'\0');
    for (;;) {
        const DWORD n = GetModuleFileNameW(base, buf.data(), static_cast<DWORD>(buf.size()));
        if (n == 0) {
            return {};
        }
        if (n < buf.size()) {
            buf.resize(n);
            break;
        }
        buf.resize(buf.size() * 2);
    }
    return std::filesystem::path(buf).parent_path();
}

CpuScan scan_cpu_variants(const std::filesystem::path& dir) {
    CpuScan scan;
    if (!dir.is_absolute()) {
        scan.problems.push_back("CPU-Varianten: Ordner ist nicht absolut: " + u8str(dir));
        return scan;
    }
    std::vector<std::filesystem::path> files;
    std::error_code ec;
    std::filesystem::directory_iterator it(dir, ec);
    if (ec) {
        scan.problems.push_back("CPU-Varianten: Ordner nicht lesbar: " + u8str(dir) + ": " + error_code_text(ec));
        return scan;
    }
    for (; it != std::filesystem::directory_iterator(); it.increment(ec)) {
        if (ec) {
            scan.problems.push_back("CPU-Varianten: Ordner nicht vollständig lesbar: " + error_code_text(ec));
            break;
        }
        std::error_code type_ec;
        if (it->is_regular_file(type_ec) && !type_ec && is_cpu_variant_name(lower(u8str(it->path().filename())))) {
            files.push_back(it->path());
        }
    }
    std::sort(files.begin(), files.end(), [](const std::filesystem::path& a, const std::filesystem::path& b) {
        return lower(u8str(a.filename())) < lower(u8str(b.filename()));
    });
    for (const std::filesystem::path& file : files) {
        const win::LoadedModule m = win::load_library_absolute(file);
        if (m.handle == nullptr) {
            scan.problems.push_back("CPU-Variante " + u8str(file.filename()) +
                                    " nicht ladbar: " + system_error_text(m.win32_error));
            continue;
        }
        void* fn = win::find_export(m.handle, "ggml_backend_score");
        if (fn == nullptr) {
            scan.problems.push_back("CPU-Variante " + u8str(file.filename()) + " exportiert kein ggml_backend_score");
            win::free_library(m.handle);
            continue;
        }
        const int score = reinterpret_cast<int (*)()>(fn)();
        win::free_library(m.handle);
        scan.all.push_back(CpuVariant{file, score});
        if (score > 0 && (!scan.best || score > scan.best->score)) {
            scan.best = CpuVariant{file, score};
        }
    }
    return scan;
}

bool Runtime::load_cpu(std::string& error) {
    Global& g = global();
    std::lock_guard<std::mutex> load(g.load_mutex);
    if (!g.cpu_file.empty()) {  // geschrieben nur unter load_mutex – hier ohne Zustandssperre lesbar
        return true;
    }
    const std::filesystem::path dir = ggml_dir();
    if (Attempt* prev = find_attempt(g, "cpu")) {
        error = "CPU-Kern nicht ladbar (" + u8str(dir) + ")";
        return prev->ok;
    }
    g.attempts.push_back(Attempt{"cpu"});
    const CpuScan scan = scan_cpu_variants(dir);
    for (const std::string& p : scan.problems) {
        log::warn(p);
    }
    if (!scan.best) {
        error = "Keine passende CPU-Variante von ggml gefunden (" + u8str(dir) +
                ") – bitte Baumeister-Host neu installieren.";
        return false;
    }
    unsigned long win32 = 0;
    ggml_backend_reg_t reg = backend_load(scan.best->file, win32);
    if (reg == nullptr || ggml_backend_reg_dev_count(reg) == 0) {
        error = "CPU-Kern nicht ladbar: " + u8str(scan.best->file) + " (" + load_error_text(win32) + ")";
        return false;
    }
    g.attempts.back().ok = true;
    {
        std::lock_guard<std::mutex> lock(g.mutex);
        g.cpu_file = u8str(scan.best->file.filename());
        g.loaded_regs.push_back("CPU");
    }
    log::info("ggml-CPU-Variante geladen: " + g.cpu_file + " (Score " + std::to_string(scan.best->score) + ")");
    return true;
}

bool Runtime::load_vulkan(std::string& error) {
    Global& g = global();
    std::lock_guard<std::mutex> load(g.load_mutex);
    const std::filesystem::path file = ggml_dir() / L"ggml-vulkan.dll";
    if (Attempt* prev = find_attempt(g, "vulkan")) {
        if (!prev->ok) {
            error = "Vulkan nicht verfügbar";
        }
        return prev->ok;
    }
    g.attempts.push_back(Attempt{"vulkan"});
    std::error_code ec;
    if (!file.is_absolute() || !std::filesystem::is_regular_file(file, ec)) {
        error = "ggml-vulkan.dll fehlt (" + u8str(file) + ") – bitte Baumeister-Host neu installieren.";
        log::error(error);
        return false;
    }
    // Nur reinen Grafikspeicher anfordern, keinen CPU-sichtbaren (ReBAR). Gemessen 2026-10-04 auf der RTX 4070 Ti
    // (12 GB) mit Qwen2.5-14B Q4_K_M: mit ReBAR legte Windows 1,8 GB der Gewichte in den „gemeinsamen GPU-Speicher“ –
    // 4,1 Tok/s; ohne 40 Tok/s. Wer das zurückdreht, macht große Modelle zehnmal langsamer. Eine gesetzte
    // Umgebungsvariable gewinnt. Muss vor dem Laden stehen: das Vulkan-Backend liest sie beim Anlegen der Geräte.
    char* preset = nullptr;
    std::size_t preset_len = 0;
    if (_dupenv_s(&preset, &preset_len, "GGML_VK_DISABLE_HOST_VISIBLE_VIDMEM") == 0 && preset == nullptr) {
        _putenv_s("GGML_VK_DISABLE_HOST_VISIBLE_VIDMEM", "1");
    }
    std::free(preset);
    unsigned long win32 = 0;
    ggml_backend_reg_t reg = backend_load(file, win32);
    if (reg == nullptr) {
        error = "Vulkan-Kern nicht ladbar – Grafiktreiber ohne Vulkan (System32\\vulkan-1.dll) oder zu alt (" +
                load_error_text(win32) + ").";
        log::warn(error);
        return false;
    }
    const std::size_t n = ggml_backend_reg_dev_count(reg);
    g.attempts.back().ok = n > 0;
    g.attempts.back().devices = n;
    if (n == 0) {
        error = "Vulkan geladen, aber kein Vulkan-Gerät gefunden.";
        log::warn(error);
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(g.mutex);
        g.loaded_regs.push_back("Vulkan");
    }
    log::info("Vulkan-Kern geladen: " + std::to_string(n) + " Gerät(e)");
    return true;
}

bool Runtime::load_component(const components::Component& c, const std::filesystem::path& component_dir,
                             std::string& error) {
    Global& g = global();
    std::lock_guard<std::mutex> load(g.load_mutex);
    if (Attempt* prev = find_attempt(g, "component:" + c.id)) {
        if (!prev->ok) {
            error = c.title + ": Laden ist in diesem Lauf schon gescheitert – nach einer Reparatur neu starten.";
        }
        return prev->ok;
    }
    g.attempts.push_back(Attempt{"component:" + c.id});
    if (!backend_of(c)) {
        error = c.title + ": unbekannter Kern „" + c.backend + "“";
        return false;
    }
    std::optional<std::filesystem::path> extra_dir;
    if (c.needs == "hip-sdk") {
        extra_dir = hip_sdk_bin(error);
        if (!extra_dir) {
            log::warn(c.title + ": " + error);
            return false;
        }
    }
    const LoadingMark mark(g, c.id);  // vor pinned anlegen, siehe LoadingMark
    std::optional<components::PinnedFiles> pinned = components::pin_files(component_dir, c, error);
    if (!pinned) {
        log::warn(error);
        return false;
    }
    log::info("Komponente " + c.id + ": " + std::to_string(pinned->count()) + " Dateien geprüft (" +
              std::to_string(pinned->bytes / (1024 * 1024)) + " MB in " + std::to_string(pinned->seconds).substr(0, 4) +
              " s) und gesperrt");
    // Erst jetzt, nach bestandener Prüfung, darf der Loader den Ordner sehen (Abhängigkeiten der Kern-DLL).
    std::vector<win::DllDirectoryCookie> cookies;
    for (const std::filesystem::path& d : {pinned->dir, extra_dir.value_or(std::filesystem::path())}) {
        if (d.empty()) {
            continue;
        }
        const auto cookie = win::add_dll_directory(d);
        if (!cookie) {
            for (const auto& ck : cookies) {
                win::remove_dll_directory(ck);
            }
            error = c.title + ": Ordner nicht freigebbar: " + system_error_text(GetLastError());
            return false;
        }
        cookies.push_back(*cookie);
    }
    unsigned long win32 = 0;
    ggml_backend_reg_t reg = backend_load(pinned->dll, win32, "ggml_backend_" + lower(c.backend) + "_reg");
    if (reg == nullptr) {
        // ggml gibt die DLL bei gescheitertem Laden selbst frei; Freigaben und Sperren zurücknehmen, damit ein
        // Reparieren oder Entfernen ohne Neustart geht.
        for (const auto& ck : cookies) {
            win::remove_dll_directory(ck);
        }
        error = c.title + ": Kern nicht ladbar – " + load_error_text(win32) + ".";
        log::warn(error + " (" + u8str(pinned->dll) + ")");
        return false;
    }
    const std::size_t n = ggml_backend_reg_dev_count(reg);
    g.attempts.back().devices = n;
    g.cookies.insert(g.cookies.end(), cookies.begin(), cookies.end());
    // ggml-openvino hat das Ziel beim Registrieren gelesen und behält es bis Prozessende (siehe openvino_switch_note).
    // Gemerkt wird, was ggml wirklich gelesen hat – die Variable, sonst sein Standard "CPU".
    std::string openvino_target;
    if (backend_of(c) == Backend::OpenVino) {
        char* dev = nullptr;
        std::size_t dev_len = 0;
        openvino_target = _dupenv_s(&dev, &dev_len, "GGML_OPENVINO_DEVICE") == 0 && dev != nullptr && *dev != '\0'
                              ? std::string(dev)
                              : std::string("CPU");
        std::free(dev);
    }
    {
        std::lock_guard<std::mutex> lock(g.mutex);
        g.pinned.emplace_back(c.id, std::move(*pinned));  // bleibt geladen und gesperrt (Entladen ist nicht gemessen)
        if (!openvino_target.empty()) {
            g.openvino_target = openvino_target;
        }
    }
    if (n == 0) {
        error = c.title + ": Kern geladen, aber kein passendes Gerät gefunden.";
        log::warn(error);
        return false;
    }
    g.attempts.back().ok = true;
    {
        std::lock_guard<std::mutex> lock(g.mutex);
        g.loaded_regs.push_back(c.backend);
    }
    log::info("Komponente " + c.id + " geladen: " + std::to_string(n) + " Gerät(e) (" + c.version + ")");
    return true;
}

bool Runtime::loaded(Backend b) const {
    Global& g = global();
    std::lock_guard<std::mutex> lock(g.mutex);
    const std::string name(reg_name(b));
    return std::find(g.loaded_regs.begin(), g.loaded_regs.end(), name) != g.loaded_regs.end();
}

bool Runtime::component_in_use(std::string_view id) const {
    Global& g = global();
    std::lock_guard<std::mutex> lock(g.mutex);
    return std::any_of(g.pinned.begin(), g.pinned.end(), [&](const auto& p) { return p.first == id; }) ||
           std::find(g.loading.begin(), g.loading.end(), id) != g.loading.end();
}

std::string Runtime::cpu_variant() const {
    Global& g = global();
    std::lock_guard<std::mutex> lock(g.mutex);
    return g.cpu_file;
}

std::string Runtime::openvino_target() const {
    Global& g = global();
    std::lock_guard<std::mutex> lock(g.mutex);
    return g.openvino_target;
}

std::vector<Device> Runtime::devices() const {
    std::vector<Device> out;
    for (ggml_backend_dev_t d : registered_devices()) {
        const auto type = ggml_backend_dev_type(d);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) {
            continue;
        }
        ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(d);
        const char* rn = reg != nullptr ? ggml_backend_reg_name(reg) : nullptr;
        const auto backend = backend_from_reg(rn != nullptr ? rn : "");
        if (!backend) {
            continue;
        }
        Device dev;
        dev.backend = *backend;
        const char* name = ggml_backend_dev_name(d);
        const char* desc = ggml_backend_dev_description(d);
        dev.ggml_name = name != nullptr ? name : "";
        dev.description = desc != nullptr && *desc != '\0' ? desc : dev.ggml_name;
        std::size_t free = 0;
        std::size_t total = 0;
        ggml_backend_dev_memory(d, &free, &total);
        dev.memory_free = free;
        dev.memory_total = total;
        dev.integrated = type == GGML_BACKEND_DEVICE_TYPE_IGPU;
        out.push_back(std::move(dev));
    }
    return out;
}

int Runtime::whisper_gpu_ordinal(const std::string& ggml_name) const {
    std::vector<std::string> names;
    for (ggml_backend_dev_t d : registered_devices()) {
        const auto type = ggml_backend_dev_type(d);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            const char* n = ggml_backend_dev_name(d);
            names.emplace_back(n != nullptr ? n : "");
        }
    }
    return ordinal_from_names(names, ggml_name);
}

int ordinal_from_names(const std::vector<std::string>& gpu_ggml_names, const std::string& wanted) {
    if (wanted.empty()) {
        return -1;
    }
    for (std::size_t i = 0; i < gpu_ggml_names.size(); ++i) {
        if (gpu_ggml_names[i] == wanted) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

}  // namespace swbh::accel
