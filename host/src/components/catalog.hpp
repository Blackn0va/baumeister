// Katalog der nachladbaren Rechenkerne (Betreiber 2026-10-14: „Teile, die man braucht, aus der Anwendung heraus
// aktualisieren … Vulkan, Tensor … NPU, AMD, Intel“).
//
// Quelle: deps.lock.json → components, beim Bau als Text in die EXE übersetzt (src/components/CMakeLists.txt) und hier
// streng gelesen. Jeder Kern stammt aus den Windows-Paketen von llama.cpp DERSELBEN Fassung wie die App (tag): Die
// Kern-DLLs importieren von ggml nur ggml-base.dll (dumpbin, 2026-10-14) und passen deshalb zur ggml-base.dll der App –
// zu keiner anderen Fassung. Neue Kerne kommen deshalb mit einer neuen App-Fassung, nicht von einem Server
// (Gedaechtnis: „Kein Update-Server“).
//
// Je Archiv: Größe und SHA-256 (GitHub-Digest), je entpackter Datei ebenfalls – entpackt wird nur, was hier steht.
//
// Threads: reentrant; builtin_catalog() liest einmal (threadsicher über eine statische lokale Variable).
// Fehler: parse_catalog wirft UserError mit Feld und Grund.

#pragma once

#include "base/json.hpp"

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::components {

struct ExtractFile {
    std::string name;  // relativer Pfad im Archiv mit „/“, z. B. "ggml-cuda.dll", "openvino-licensing/LICENSE"
    std::uint64_t size = 0;
    std::string sha256;
};

struct Archive {
    std::string file;  // Dateiname im Release, z. B. "llama-b10900-bin-win-cuda-13.3-x64.zip"
    std::string url;   // release + file
    std::uint64_t size = 0;
    std::string sha256;
    std::vector<ExtractFile> extract;
};

struct Component {
    std::string id;       // "cuda", "openvino", "sycl", "rocm" (Ordnername unter {data}\components)
    std::string title;
    std::string backend;  // Name des ggml-Backends: "CUDA", "OPENVINO", "SYCL", "ROCm"
    std::string dll;      // Kern-DLL, eine der entpackten Dateien
    std::string vendor;   // "nvidia" | "intel" | "amd"
    std::string version;
    std::string license;
    std::string terms;      // "" oder "nvidia-cuda": Laden nur nach ausdrücklicher Zustimmung
    std::string terms_url;  // Lizenztext zu terms
    std::string needs;      // "" oder "hip-sdk": braucht zusätzlich Software des Herstellers
    std::string note;
    std::vector<Archive> archives;

    std::uint64_t download_size() const;
    std::uint64_t installed_size() const;
};

struct Catalog {
    std::string tag;  // llama.cpp-Fassung, z. B. "b10900"
    std::vector<Component> items;

    const Component* find(std::string_view id) const;
};

// Streng: Pflichtfelder als Strings bzw. nicht-negative Ganzzahlen, SHA-256 64 Hex klein, Kennungen [a-z0-9-],
// Dateinamen ohne „..“, Laufwerk, Backslash oder führenden „/“, die Kern-DLL unter den entpackten Dateien, jede URL =
// release + file mit release = "https://github.com/ggml-org/llama.cpp/releases/download/<tag>/". Unbekannte Felder
// werden ignoriert. Wirft UserError.
Catalog parse_catalog(const Json& section);

// Der eingebaute Katalog (deps.lock.json → components). Wirft beim ersten Aufruf UserError, wenn er ungültig ist –
// ein Bau mit kaputtem Katalog fällt im Unit-Test auf, nicht beim Kunden.
const Catalog& builtin_catalog();

// Sicherer relativer Name (siehe parse_catalog). Öffentlich für die Tests.
bool safe_relative_name(std::string_view name);

}  // namespace swbh::components
