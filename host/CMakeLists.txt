# StreamWizard Baumeister-Host – lokales Sprachmodell für den Baumeister-NPC (Minecraft-Plugin auf dev01).
#
# Ersetzt llm-host/start-llm.ps1 (llama-server.exe + plink in PowerShell): Die App rechnet das Modell im eigenen
# Prozess (llama.cpp, GPU), bietet dem Plugin eine kleine OpenAI-kompatible Schnittstelle an 127.0.0.1 und hält den
# SSH-Rückwärtstunnel (plink + Pageant) zu dev01 offen. Läuft auf einem PC mit NVIDIA-Karte ohne Java und ohne
# PowerShell.
#
# Aufbau wie kraken (StreamWizard Agent, Präfix swa): statische Kernbibliotheken swbh_<modul> ohne Qt,
# BaumeisterHost.exe (Qt 6 Widgets), Tests je Modul (doctest, ctest). base/win/components/accel/llm stammen aus
# kraken und sind dort gemessen und begründet; was hier neu ist, steht in server/, plan/, tunnel/ und gui/.
#
# Bau ausschließlich über build.ps1 (Stages, Tests inklusive). Von Hand:
#   cmake -S . -B out/build -G Ninja -DCMAKE_BUILD_TYPE=Release   (in einer vcvars64-Umgebung)
#   cmake --build out/build
#   ctest --test-dir out/build -L unit

cmake_minimum_required(VERSION 3.30)

# Einzige Versionsquelle. build.ps1, Setup und Versionsressource lesen sie von hier.
project(BaumeisterHost VERSION 0.2.0 DESCRIPTION "StreamWizard Baumeister-Host" LANGUAGES C CXX)

if(NOT MSVC OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "Baumeister-Host baut nur mit MSVC für x64.")
endif()
enable_language(RC)

option(SWBH_BUILD_TESTS "Tests bauen (ctest)" ON)
# Ohne llama.cpp gibt es kein Modell; der Schalter bleibt nur für die Gleichheit mit kraken (base/version.hpp).
set(SWBH_WITH_LLM ON)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
# /MD überall: Qt ist eine DLL-Laufzeit, ggml wird passend gebaut.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")
set_property(GLOBAL PROPERTY USE_FOLDERS ON)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
include(SwbhCompile)
include(SwbhDeps)
include(SwbhEmbed)
include(SwbhStage)
include(SwbhTesting)

swbh_find_qt()
include(SwbhLlama)
include(SwbhWhisper)

message(STATUS "Baumeister-Host ${PROJECT_VERSION}: Tests=${SWBH_BUILD_TESTS}")

# Module in Abhängigkeitsreihenfolge (keine Zyklen).
set(SWBH_MODULES base win components accel llm plan server tunnel relay voice)
foreach(_m IN LISTS SWBH_MODULES)
    add_subdirectory(src/${_m})
endforeach()
add_subdirectory(src/gui)

if(SWBH_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests/support)
    foreach(_m IN LISTS SWBH_MODULES)
        add_subdirectory(tests/${_m})
    endforeach()
    add_subdirectory(tests/gui)
endif()

swbh_stage_rules()
