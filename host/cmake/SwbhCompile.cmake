# Gemeinsame Compiler- und Linker-Einstellungen für eigenen Code.
#
# Übernommen aus CaptionBridgeCPP: C++20, MSVC 19.44, /utf-8 /W4 /permissive- /EHsc,
# /WX für eigenen Code, /MD überall, UNICODE, NOMINMAX, WIN32_LEAN_AND_MEAN,
# _WIN32_WINNT=0x0A00.
#
# /utf-8 ist Pflicht: Ohne es übersetzt MSVC deutsche Literale still in CP1252.
# Warnungen aus fremden Headern (<windows.h>, Qt, nlohmann, doctest, llama.h) blendet
# /external:anglebrackets aus; eigene Header werden immer mit "…" eingebunden.

include_guard(GLOBAL)

set(SWBH_SOURCE_ROOT "${CMAKE_CURRENT_LIST_DIR}/.." CACHE INTERNAL "Wurzel von Baumeister-Host")
cmake_path(NORMAL_PATH SWBH_SOURCE_ROOT)

add_library(swbh_compile_defaults INTERFACE)
add_library(swbh::compile_defaults ALIAS swbh_compile_defaults)
target_compile_features(swbh_compile_defaults INTERFACE cxx_std_20)
target_compile_definitions(swbh_compile_defaults INTERFACE
    UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN
    _WIN32_WINNT=0x0A00 WINVER=0x0A00
    SWBH_WITH_LLM=$<BOOL:${SWBH_WITH_LLM}>)
target_compile_options(swbh_compile_defaults INTERFACE
    /utf-8 /permissive- /EHsc /Zc:__cplusplus /Zc:preprocessor /fp:precise
    # Debug-Infos auch im Release, im Objekt statt in einer Compiler-PDB (/Z7: keine Sperrkonflikte bei parallelem
    # Bau, aus kraken). Nie im Setup (build.ps1 -Stage stage prüft das). Der Maschinencode bleibt gleich.
    $<$<CONFIG:Release>:/Z7>)

# Strenge Warnungen nur für eigenen Code (nicht für vendorte oder erzeugte Fremdteile).
function(swbh_strict_warnings target)
    target_compile_options(${target} PRIVATE /W4 /WX /external:anglebrackets /external:W0 /MP)
endfunction()

# Härtung der erzeugten Binärdateien (Control Flow Guard, CET-Schattenstapel).
function(swbh_harden_binary target)
    target_compile_options(${target} PRIVATE /guard:cf)
    target_link_options(${target} PRIVATE /guard:cf /CETCOMPAT /DYNAMICBASE /HIGHENTROPYVA /NXCOMPAT)
endfunction()

# Legt eine Modulbibliothek swbh_<name> (Alias swbh::<name>) an.
#   swbh_add_module(<name> SOURCES … [PUBLIC_DEPS …] [PRIVATE_DEPS …])
# Öffentliche Header liegen unter src/<name>/ und werden als "<name>/x.hpp" eingebunden.
function(swbh_add_module name)
    cmake_parse_arguments(ARG "" "" "SOURCES;PUBLIC_DEPS;PRIVATE_DEPS" ${ARGN})
    add_library(swbh_${name} STATIC ${ARG_SOURCES})
    add_library(swbh::${name} ALIAS swbh_${name})
    target_include_directories(swbh_${name} PUBLIC "${SWBH_SOURCE_ROOT}/src")
    target_link_libraries(swbh_${name}
        PUBLIC swbh::compile_defaults ${ARG_PUBLIC_DEPS}
        PRIVATE ${ARG_PRIVATE_DEPS})
    swbh_strict_warnings(swbh_${name})
    target_compile_options(swbh_${name} PRIVATE /guard:cf)
    set_target_properties(swbh_${name} PROPERTIES FOLDER "module")
endfunction()

# Legt eine EXE an.
#   swbh_add_executable(<name> [WIN32] [APP_ICON] SOURCES … [DEPS …] [OUTPUT_NAME name] [DESCRIPTION text])
# APP_ICON: Programm-Icon als Ressource (swbh_add_app_icon) – nur BaumeisterHost.exe.
# EXEs lösen statische Importe nur im eigenen Ordner und in System32 auf (/DEPENDENTLOADFLAG:0xA00 =
# LOAD_LIBRARY_SEARCH_APPLICATION_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32): harden_dll_search() greift erst in wWinMain,
# die statischen Importe löst der Loader vorher auf (aus kraken/CaptionBridgeGui).
function(swbh_add_executable name)
    cmake_parse_arguments(ARG "WIN32;APP_ICON" "OUTPUT_NAME;DESCRIPTION" "SOURCES;DEPS" ${ARGN})
    if(ARG_WIN32)
        add_executable(${name} WIN32 ${ARG_SOURCES})
    else()
        add_executable(${name} ${ARG_SOURCES})
    endif()
    target_link_libraries(${name} PRIVATE swbh::compile_defaults ${ARG_DEPS})
    swbh_strict_warnings(${name})
    swbh_harden_binary(${name})
    target_link_options(${name} PRIVATE /DEPENDENTLOADFLAG:0xA00)
    if(ARG_OUTPUT_NAME)
        set_target_properties(${name} PROPERTIES OUTPUT_NAME "${ARG_OUTPUT_NAME}")
    endif()
    set(_desc "${ARG_DESCRIPTION}")
    if(NOT _desc)
        set(_desc "StreamWizard Baumeister-Host")
    endif()
    swbh_add_version_resource(${name} "${_desc}")
    if(ARG_APP_ICON)
        swbh_add_app_icon(${name})
    endif()
    set_target_properties(${name} PROPERTIES FOLDER "app")
endfunction()

# Programm-Icon (resources/branding/BaumeisterHost.ico) als Ressource IDI_ICON1 in ein
# Ziel. Das .ico ist eingecheckt und wird NICHT beim Bau erzeugt (resources/branding/make_icon.py).
function(swbh_add_app_icon target)
    set(SWBH_APP_ICON_FILE "${SWBH_SOURCE_ROOT}/resources/branding/BaumeisterHost.ico")
    if(NOT EXISTS "${SWBH_APP_ICON_FILE}")
        message(FATAL_ERROR "Programm-Icon fehlt: ${SWBH_APP_ICON_FILE} (python -B resources/branding/make_icon.py)")
    endif()
    set(_rc "${CMAKE_BINARY_DIR}/generated/rc/${target}_icon.rc")
    configure_file("${SWBH_SOURCE_ROOT}/app/app_icon.rc.in" "${_rc}" @ONLY)
    target_sources(${target} PRIVATE "${_rc}")
    # rc.exe liest das .ico erst beim Bau: Änderungen daran sollen neu übersetzen.
    set_source_files_properties("${_rc}" PROPERTIES OBJECT_DEPENDS "${SWBH_APP_ICON_FILE}")
endfunction()

# Versionsressource (Datei-Eigenschaften im Explorer, Inno liest sie nicht, der
# Support schon). Quelle: app/version.rc.in, Version aus project().
function(swbh_add_version_resource target description)
    get_target_property(_out ${target} OUTPUT_NAME)
    if(NOT _out)
        set(_out ${target})
    endif()
    get_target_property(_type ${target} TYPE)
    if(_type STREQUAL "SHARED_LIBRARY" OR _type STREQUAL "MODULE_LIBRARY")
        set(SWBH_RC_FILETYPE "VFT_DLL")
        set(SWBH_RC_EXT ".dll")
    else()
        set(SWBH_RC_FILETYPE "VFT_APP")
        set(SWBH_RC_EXT ".exe")
    endif()
    set(SWBH_RC_DESCRIPTION "${description}")
    set(SWBH_RC_ORIGINAL "${_out}${SWBH_RC_EXT}")
    set(_rc "${CMAKE_BINARY_DIR}/generated/rc/${target}_version.rc")
    configure_file("${SWBH_SOURCE_ROOT}/app/version.rc.in" "${_rc}" @ONLY)
    target_sources(${target} PRIVATE "${_rc}")
endfunction()
