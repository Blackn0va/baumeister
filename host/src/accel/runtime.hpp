// Einzige Stelle, die ggml-Rechenkerne lädt (Muster: CaptionBridgeCPP src/accel/runtime.hpp).
//
// - CPU: ggml-cpu-<Variante>.dll aus dem Ordner von ggml-base.dll, jede bewertet über ggml_backend_score, die beste
//   geladen. Nur die Namen der ausgelieferten Varianten (SWBH_GGML_CPU_VARIANTS) – eine untergeschobene
//   „ggml-cpu-zz.dll“ wird nie ausgeführt.
// - Vulkan: ggml-vulkan.dll aus demselben Ordner (im Setup; braucht nur System32\vulkan-1.dll vom Grafiktreiber).
// - Komponenten (CUDA, OpenVINO, SYCL, ROCm): nur aus {data}\components\<id> und nur nach components::pin_files –
//   jede Datei mit Größe und SHA-256 laut Katalog geprüft und bis zum Prozessende gegen Austausch gesperrt. Erst dann
//   gibt AddDllDirectory den Ordner für den Loader frei (cuBLAS, OpenVINO-Plugins, oneAPI-Laufzeit liegen daneben).
// NIE ggml_backend_load_all: lädt aus Arbeitsverzeichnis, GGML_BACKEND_PATH und über PATH (in CaptionBridge gemessen).
//
// Warum „Ordner von ggml-base.dll“ statt {app}: Die EXE lädt ggml-base.dll beim Start aus ihrem Ordner
// (/DEPENDENTLOADFLAG, nur EXE-Ordner und System32); die Kerne gehören genau zu dieser Datei. In Tests liegt sie im
// Bauordner bin\ – derselbe Code findet die Kerne dort.
//
// Ladezustand ist PROZESSWEIT (die ggml-Registry ist global): je Datei ein Ladeversuch, auch ein gescheiterter, und
// geladene Kerne werden nie entladen (Modelle und Whisper können sie halten).
//
// Threads: Laden aus einem Arbeitsthread (Modell-Ladethread); eine prozessweite Lade-Sperre schützt gegen
// gleichzeitiges Laden. Die Abfragen (loaded, component_in_use, cpu_variant, openvino_target) nehmen nur eine kurze
// Zustandssperre – vorher warteten sie im GUI-Thread auf die ganze SHA-256-Prüfung (CUDA 627 MB, 1–1,7 s). Die
// ggml-Geräteliste schützt registry_mutex (ggml selbst sperrt nicht). Fehler: Rückgabe false mit deutschem Grund;
// Einzelheiten ins Protokoll.

#pragma once

#include "components/catalog.hpp"

#include <cstdint>
#include <filesystem>
#include <optional>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <vector>

struct ggml_backend_device;  // ggml_backend_dev_t – hier ohne ggml-Header

namespace swbh::accel {

enum class Backend { Vulkan, Cuda, OpenVino, Sycl, Rocm };

std::string_view reg_name(Backend b);  // Name des ggml-Backends: "Vulkan", "CUDA", "OPENVINO", "SYCL", "ROCm"
std::optional<Backend> backend_from_reg(std::string_view reg);
// Komponente → Backend über Component::backend (nullopt bei unbekanntem Namen).
std::optional<Backend> backend_of(const components::Component& c);

struct Device {
    Backend backend = Backend::Vulkan;
    std::string ggml_name;    // "Vulkan0", "CUDA0", "OpenVINO0" …
    std::string description;  // "NVIDIA GeForce RTX 4070 Ti"
    std::uint64_t memory_total = 0;
    std::uint64_t memory_free = 0;
    bool integrated = false;  // ggml-Typ IGPU
};

// Ziel des OpenVINO-Kerns: "NPU", "GPU" oder "CPU" (ggml-openvino liest GGML_OPENVINO_DEVICE einmal beim Laden).
// Muss vor load_component(openvino) gesetzt sein; cache_dir nimmt die übersetzten Modelle auf (NPU: sonst bei jedem
// Start minutenlang neu übersetzt). Nimmt die Lade-Sperre: ggml liest die Variable in load_component.
void set_openvino_target(std::string_view device, const std::filesystem::path& cache_dir);

// Hinweis, wenn der OpenVINO-Kern in diesem Prozess schon auf `loaded` rechnet und `requested` ein anderes Ziel ist.
// ggml-openvino liest das Ziel nur beim ersten Laden (statisches Singleton in ggml-openvino-extra.cpp), ein
// Entladen gibt es nicht – der Wechsel NPU ↔ Intel-Grafik wirkt erst nach einem Neustart. nullopt = kein Konflikt
// (noch nicht geladen, gleiches Ziel oder kein Ziel verlangt). Testbar ohne DLLs.
std::optional<std::string> openvino_switch_note(std::string_view loaded, std::string_view requested);

// Sperre um die ggml-Geräteliste: exklusiv bei jeder Registrierung (ggml_backend_load/ggml_backend_register in
// load_*), geteilt beim Lesen. ggml-backend-reg.cpp hat keine eigene Sperre – devices.push_back legt den Vektor neu
// an, während ein anderer Thread devices[i] liest (Lesen aus freigegebenem Speicher). Geteilt auch um fremde Aufrufe,
// die die Liste intern durchzählen (llama_model_load_from_file, llama_init_from_model, mtmd_init_from_file,
// whisper_init_*). Nie verschachtelt geteilt nehmen (std::shared_mutex ist nicht rekursiv).
std::shared_mutex& registry_mutex();

// Alle registrierten ggml-Geräte (Zeiger) unter der geteilten Sperre. Die Geräte selbst bleiben gültig, weil kein
// Kern je entladen wird – Name, Speicher usw. darf der Aufrufer danach ohne Sperre abfragen.
std::vector<ggml_backend_device*> registered_devices();

class Runtime {
public:
    static Runtime& get();

    // Ordner der geladenen ggml-base.dll (leer, wenn nicht ermittelbar).
    std::filesystem::path ggml_dir() const;

    // Beste CPU-Variante laden. false, wenn keine passt (Grund in error).
    bool load_cpu(std::string& error);
    // ggml-vulkan.dll laden. false = Datei fehlt, Treiber ohne Vulkan oder kein Gerät.
    bool load_vulkan(std::string& error);
    // Installierte Komponente prüfen, sperren und laden. false bei jedem Zweifel.
    bool load_component(const components::Component& c, const std::filesystem::path& component_dir,
                        std::string& error);

    bool loaded(Backend b) const;
    // true, wenn die Komponente in diesem Prozess geprüft und gesperrt ist (dann kein Ersetzen/Entfernen vor Neustart)
    // – und schon während load_component sie prüft: Sonst sähen Installieren/Entfernen „frei“ und ersetzten Dateien,
    // die gerade gehasht und gesperrt werden.
    bool component_in_use(std::string_view id) const;
    std::string cpu_variant() const;  // Dateiname der geladenen CPU-Variante, leer vorher
    // Ziel, mit dem der OpenVINO-Kern in diesem Prozess geladen wurde ("NPU", "GPU", "CPU"); leer = nicht geladen.
    std::string openvino_target() const;

    // GPU-Geräte (GPU und IGPU) aller geladenen Kerne in ggml-Reihenfolge.
    std::vector<Device> devices() const;

    // whisper_context_params.gpu_device für ein Gerät: Position unter ALLEN GPU/IGPU-Geräten der Registry (so zählt
    // whisper_backend_init_gpu). −1 = nicht gefunden.
    int whisper_gpu_ordinal(const std::string& ggml_name) const;

private:
    Runtime() = default;
};

// Ergebnis der CPU-Variantenwahl (testbar ohne ggml).
struct CpuVariant {
    std::filesystem::path file;
    int score = 0;  // 0 = CPU kann die Variante nicht ausführen
};
struct CpuScan {
    std::optional<CpuVariant> best;
    std::vector<CpuVariant> all;
    std::vector<std::string> problems;
};
// Bewertet die ausgelieferten ggml-cpu-*.dll im Ordner (absolut, keine Unterordner): laden, ggml_backend_score,
// freigeben. Strikt größer gewinnt, bei Gleichstand der erste Name (wie ggml_backend_load_best).
CpuScan scan_cpu_variants(const std::filesystem::path& dir);

// Position von wanted in der Liste (−1 = fehlt). Testbar ohne DLLs.
int ordinal_from_names(const std::vector<std::string>& gpu_ggml_names, const std::string& wanted);

}  // namespace swbh::accel
