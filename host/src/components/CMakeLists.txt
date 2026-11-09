# Modul components – nachladbarer Rechenkern CUDA: Katalog, Installation, Prüfung vor dem Laden (übernommen aus
# kraken, ohne yt-dlp/OpenPGP). Kein ggml hier – geladen wird in src/accel.

# Katalog = Abschnitt deps.lock.json → components, byte-genau eingebettet. Die Kerne passen nur zur ggml-Fassung der
# App: Der Katalog muss dieselbe llama.cpp-Fassung nennen wie fetchcontent.llama.cpp (sonst bräche das Laden erst beim
# Nutzer mit einem unpassenden Kern).
string(JSON _cs_comp GET "${SWBH_LOCK_JSON}" components)
string(JSON _cs_comp_tag GET "${SWBH_LOCK_JSON}" components tag)
string(JSON _cs_llama_tag GET "${SWBH_LOCK_JSON}" fetchcontent llama.cpp tag)
if(NOT _cs_comp_tag STREQUAL _cs_llama_tag)
    message(FATAL_ERROR "deps.lock.json: components.tag '${_cs_comp_tag}' ≠ "
                        "fetchcontent.llama.cpp.tag '${_cs_llama_tag}' – "
                        "Rechenkerne passen nur zur llama.cpp-Fassung der App.")
endif()
file(CONFIGURE OUTPUT "${CMAKE_BINARY_DIR}/generated/components/catalog.json" CONTENT "${_cs_comp}" @ONLY)

swbh_add_module(components
    SOURCES
        catalog.hpp catalog.cpp
        store.hpp store.cpp
    PUBLIC_DEPS
        swbh::base swbh::win
    PRIVATE_DEPS
        bcrypt)

swbh_embed_file(TARGET swbh_components FILE "${CMAKE_BINARY_DIR}/generated/components/catalog.json"
               SYMBOL components_catalog_json)
