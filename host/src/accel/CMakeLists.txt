# Modul accel – lädt die ggml-Rechenkerne (CPU-Variante, Vulkan, CUDA-Komponente); übernommen aus kraken ohne die
# Rechenweg-Wahl (choose), die hier die Einstellungen übernehmen.

# Namen der ausgelieferten CPU-Varianten (cmake/SwbhLlama.cmake): Nur diese werden bewertet und geladen.
list(JOIN SWBH_GGML_CPU_VARIANTS "," _cs_cpu_variants)

swbh_add_module(accel
    SOURCES
        runtime.hpp runtime.cpp
    PUBLIC_DEPS
        swbh::base swbh::win swbh::components
    PRIVATE_DEPS
        ggml ggml-base)
target_compile_definitions(swbh_accel PRIVATE "SWBH_GGML_CPU_VARIANTS=\"${_cs_cpu_variants}\"")
