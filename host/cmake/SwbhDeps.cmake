# Findet die Abhängigkeiten und legt importierte Ziele an (übernommen aus
# CaptionBridgeCPP, ohne den Engine-Stempel-Cache – llama.cpp kommt über
# cmake/SwaLlama.cmake per FetchContent).
#
# Vendorte Header (immer, ohne Netz):
#   swbh::nlohmann   third_party/nlohmann/json.hpp 3.11.3
#   swbh::doctest    third_party/doctest/doctest.h 2.4.11
# Qt: Qt6::Core/Gui/Widgets aus SWBH_QT_DIR, DLL-Hashes
#   werden gegen deps.lock.json geprüft (zwei obs-deps-Kopien mit
#   verschiedener Qt6Core.dll existieren).
# Variable für Tests: SWBH_RUNTIME_PATHS (Ordner, die Tests im PATH brauchen).

include_guard(GLOBAL)

set(SWBH_QT_DIR "C:/swb/deps/qt6" CACHE PATH "Qt 6.11.1 aus obs-deps")
option(SWBH_ALLOW_OTHER_QT "Qt-DLLs dürfen von deps.lock.json abweichen (nur Entwicklung)" OFF)

set(SWBH_LOCK_FILE "${SWBH_SOURCE_ROOT}/deps.lock.json")
if(NOT EXISTS "${SWBH_LOCK_FILE}")
    message(FATAL_ERROR "deps.lock.json fehlt: ${SWBH_LOCK_FILE}")
endif()
file(READ "${SWBH_LOCK_FILE}" SWBH_LOCK_JSON)
set_property(DIRECTORY "${CMAKE_SOURCE_DIR}" APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${SWBH_LOCK_FILE}")

# --- vendorte Header -------------------------------------------------------

# Prüft eine vendorte Datei gegen den SHA-256 in deps.lock.json. Ein abweichender
# Header wäre ein unbemerkter Versionswechsel.
function(_cs_check_vendored lock_key rel_path)
    string(JSON _expected GET "${SWBH_LOCK_JSON}" vendored ${lock_key} sha256 "${rel_path}")
    set(_file "${SWBH_SOURCE_ROOT}/${rel_path}")
    if(NOT EXISTS "${_file}")
        message(FATAL_ERROR "Vendorte Datei fehlt: ${_file} (siehe deps.lock.json → vendored.${lock_key})")
    endif()
    file(SHA256 "${_file}" _actual)
    if(NOT _actual STREQUAL _expected)
        message(FATAL_ERROR "Vendorte Datei weicht von deps.lock.json ab: ${rel_path}\n  erwartet ${_expected}\n  gefunden ${_actual}")
    endif()
endfunction()

_cs_check_vendored(nlohmann_json "third_party/nlohmann/json.hpp")
_cs_check_vendored(doctest "third_party/doctest/doctest.h")
add_library(swbh_nlohmann INTERFACE)
add_library(swbh::nlohmann ALIAS swbh_nlohmann)
target_include_directories(swbh_nlohmann SYSTEM INTERFACE "${SWBH_SOURCE_ROOT}/third_party")
# Keine impliziten Konvertierungen: Typfehler in JSON sollen am Aufrufer sichtbar werden.
target_compile_definitions(swbh_nlohmann INTERFACE JSON_USE_IMPLICIT_CONVERSIONS=0)

add_library(swbh_doctest INTERFACE)
add_library(swbh::doctest ALIAS swbh_doctest)
target_include_directories(swbh_doctest SYSTEM INTERFACE "${SWBH_SOURCE_ROOT}/third_party/doctest")

set(SWBH_RUNTIME_PATHS "")

# --- Qt ---------------------------------------------------------------------

macro(swbh_find_qt)
    list(APPEND CMAKE_PREFIX_PATH "${SWBH_QT_DIR}")
    find_package(Qt6 6.11 REQUIRED COMPONENTS Core Gui Widgets)
    if(NOT Qt6_VERSION VERSION_EQUAL "6.11.1")
        message(WARNING "Qt ${Qt6_VERSION} statt 6.11.1 (deps.lock.json) gefunden")
    endif()
    string(JSON _qt_count LENGTH "${SWBH_LOCK_JSON}" qt sha256)
    math(EXPR _qt_last "${_qt_count} - 1")
    foreach(_i RANGE ${_qt_last})
        string(JSON _rel MEMBER "${SWBH_LOCK_JSON}" qt sha256 ${_i})
        string(JSON _expected GET "${SWBH_LOCK_JSON}" qt sha256 "${_rel}")
        set(_file "${SWBH_QT_DIR}/${_rel}")
        if(NOT EXISTS "${_file}")
            set(_actual "fehlt")
        else()
            file(SHA256 "${_file}" _actual)
        endif()
        if(NOT _actual STREQUAL _expected)
            set(_msg "Qt-Datei weicht von deps.lock.json ab: ${_file}\n  erwartet ${_expected}\n  gefunden ${_actual}")
            if(SWBH_ALLOW_OTHER_QT)
                message(WARNING "${_msg}")
            else()
                message(FATAL_ERROR "${_msg}\n  (SWBH_QT_DIR prüfen oder -DSWBH_ALLOW_OTHER_QT=ON nur für Entwicklung)")
            endif()
        endif()
    endforeach()
    list(APPEND SWBH_RUNTIME_PATHS "${SWBH_QT_DIR}/bin")
endmacro()
