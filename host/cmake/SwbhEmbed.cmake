# Bettet eine Datei byte-genau als Array in ein Ziel ein.
#
# Warum kein String-Literal: MSVC erlaubt nur 16 380 Bytes je Literalstück (C2026)
# und würde ⚙/ä/ö/ü/ß ohne /utf-8 still umkodieren; die Overlay-Seite muss aber
# byte-identisch bleiben (SHA-256 wird getestet). Warum kein RCDATA: Ressourcen
# einer statischen Bibliothek landen nicht verlässlich in der EXE.
#
#   swbh_embed_file(TARGET <ziel> FILE <pfad> SYMBOL <c-name>)
# erzeugt ${CMAKE_BINARY_DIR}/generated/embedded/<c-name>.{hpp,cpp}:
#   namespace swbh::embedded {
#     extern const unsigned char <c-name>[];     // plus abschließendes 0-Byte
#     extern const std::size_t <c-name>_size;    // ohne das 0-Byte
#     std::string_view <c-name>_view();
#   }
# Eingebunden wird es mit #include "embedded/<c-name>.hpp".

include_guard(GLOBAL)

function(swbh_embed_file)
    cmake_parse_arguments(ARG "" "TARGET;FILE;SYMBOL" "" ${ARGN})
    if(NOT ARG_TARGET OR NOT ARG_FILE OR NOT ARG_SYMBOL)
        message(FATAL_ERROR "swbh_embed_file: TARGET, FILE und SYMBOL sind Pflicht")
    endif()
    if(NOT EXISTS "${ARG_FILE}")
        message(FATAL_ERROR "swbh_embed_file: Datei fehlt: ${ARG_FILE}")
    endif()
    file(READ "${ARG_FILE}" _hex HEX)
    file(SIZE "${ARG_FILE}" _size)
    file(SHA256 "${ARG_FILE}" _sha)
    string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," _bytes "${_hex}")
    # Zeilen zu 24 Bytes, damit der Compiler keine Monsterzeile verdauen muss.
    string(REGEX REPLACE "((0x[0-9a-f][0-9a-f],){24})" "\\1\n    " _bytes "${_bytes}")
    set(_dir "${CMAKE_BINARY_DIR}/generated/embedded")
    set(_sym "${ARG_SYMBOL}")
    file(CONFIGURE OUTPUT "${_dir}/${_sym}.hpp" CONTENT
"// Erzeugt von swbh_embed_file – nicht bearbeiten.
// Quelle: ${ARG_FILE}
// Bytes: ${_size}, SHA-256: ${_sha}
#pragma once
#include <cstddef>
#include <string_view>
namespace swbh::embedded {
extern const unsigned char ${_sym}[];
extern const std::size_t ${_sym}_size;
inline std::string_view ${_sym}_view() {
    return std::string_view(reinterpret_cast<const char*>(${_sym}), ${_sym}_size);
}
}  // namespace swbh::embedded
")
    file(CONFIGURE OUTPUT "${_dir}/${_sym}.cpp" CONTENT
"// Erzeugt von swbh_embed_file – nicht bearbeiten.
#include \"embedded/${_sym}.hpp\"
namespace swbh::embedded {
extern const unsigned char ${_sym}[] = {
    ${_bytes}0x00};
extern const std::size_t ${_sym}_size = ${_size};
}  // namespace swbh::embedded
")
    target_sources(${ARG_TARGET} PRIVATE "${_dir}/${_sym}.cpp" "${_dir}/${_sym}.hpp")
    target_include_directories(${ARG_TARGET} PUBLIC "${CMAKE_BINARY_DIR}/generated")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${ARG_FILE}")
endfunction()
