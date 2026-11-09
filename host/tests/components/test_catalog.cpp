// Eingebetteter Komponentenkatalog: nur CUDA, mit NVIDIA-Zustimmungspflicht und Prüfsummen aus kraken.

#include "test_support.hpp"

#include "components/catalog.hpp"

TEST_CASE("Komponenten: Katalog enthält CUDA b10900 mit Zustimmungspflicht und geprüften Dateien") {
    const auto& cat = swbh::components::builtin_catalog();
    CHECK(cat.tag == "b10900");
    REQUIRE(cat.items.size() == 1);
    const auto* cuda = cat.find("cuda");
    REQUIRE(cuda != nullptr);
    CHECK(cuda->backend == "CUDA");
    CHECK(cuda->dll == "ggml-cuda.dll");
    CHECK(cuda->terms == "nvidia-cuda");  // ohne Zustimmung kein Laden
    CHECK(cuda->terms_url.starts_with("https://docs.nvidia.com/"));
    REQUIRE(cuda->archives.size() == 2);
    std::size_t files = 0;
    for (const auto& a : cuda->archives) {
        CHECK(a.url.starts_with("https://github.com/ggml-org/llama.cpp/releases/download/b10900/"));
        CHECK(a.sha256.size() == 64);
        files += a.extract.size();
    }
    CHECK(files == 4);  // ggml-cuda, cudart, cublas, cublasLt
    CHECK(cuda->download_size() == 149706788ull + 390970417ull);
}
