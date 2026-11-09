# Modul base – Fundament ohne Qt (übernommen aus kraken, ohne Lizenz- und Quellenmarken).

if(SWBH_WITH_LLM)
    set(SWBH_WITH_LLM_BOOL true)
else()
    set(SWBH_WITH_LLM_BOOL false)
endif()
configure_file("${CMAKE_CURRENT_SOURCE_DIR}/version.hpp.in" "${CMAKE_BINARY_DIR}/generated/base/version.hpp" @ONLY)

swbh_add_module(base
    SOURCES
        clock.hpp clock.cpp
        encoding.hpp encoding.cpp
        errors.hpp errors.cpp
        fileio.hpp fileio.cpp
        format.hpp format.cpp
        hash.hpp hash.cpp
        json.hpp json.cpp
        log.hpp log.cpp
        paths.hpp paths.cpp
        pytext.hpp pytext.cpp pytext_alpha.inc
        utf8.hpp utf8.cpp
        version.hpp.in
        "${CMAKE_BINARY_DIR}/generated/base/version.hpp"
    PUBLIC_DEPS
        swbh::nlohmann
    PRIVATE_DEPS
        bcrypt shell32 ole32)

target_include_directories(swbh_base PUBLIC "${CMAKE_BINARY_DIR}/generated")
