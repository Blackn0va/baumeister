# llama.cpp (mit ggml) per FetchContent – übernommen aus kraken/cmake/SwaLlama.cmake, ohne libmtmd (kein Bildmodell).
# Im Setup: CPU (alle Varianten) und Vulkan als ladbare Kerne.
#
# Warum nur Vulkan im Setup: CUDA bräuchte cublas64_13.dll + cublasLt64_13.dll
# (über 500 MB) und eine NVIDIA-Zustimmung. Vulkan läuft auf NVIDIA, AMD und Intel,
# vulkan-1.dll kommt mit dem Grafiktreiber. CUDA lädt die App erst nach Zustimmung als Komponente nach
# (src/components, src/accel).
#
# Warum der Commit statt des Tags: Ein Tag lässt sich verschieben, ein Commit nicht.
# Stand b10900 ist derselbe wie in kraken und localAI – dort gebaut und gelaufen.
#
# Übernommene Fallen aus localAI/src/chat/CMakeLists.txt (dort gemessen):
#  - llama.cpp als C++17: src/llama-chat.cpp scheitert unter C++20 (u8"" → char8_t).
#  - Eigene Optionen (/W4 /WX /permissive-) dürfen llama.cpp nicht erreichen – sie
#    hängen nur an swbh_* -Zielen (SwaCompile.cmake), nie global.
#  - GGML_NATIVE=OFF: NATIVE kompiliert für die CPU des Baurechners; auf älteren
#    CPUs stürzte das mit 0xc000001d ab. Seit 2026-10-14 statt fester Grundlinie AVX2 alle CPU-Varianten.
#  - GGML_OPENMP=OFF: spart vcomp140.dll im Setup; ggml nimmt dann seinen eigenen
#    Threadpool. Die Rechenlast liegt ohnehin auf der GPU.

include_guard(GLOBAL)
include(FetchContent)

string(JSON SWBH_LLAMA_REPO GET "${SWBH_LOCK_JSON}" fetchcontent llama.cpp repository)
string(JSON SWBH_LLAMA_COMMIT GET "${SWBH_LOCK_JSON}" fetchcontent llama.cpp commit)

# Vulkan-SDK ausdrücklich aus VULKAN_SDK: Qt aus obs-deps bringt eigene Vulkan-Header
# 1.3.216 mit, und weil swbh_find_qt() den Qt-Ordner vorher in CMAKE_PREFIX_PATH legt, fand
# find_package(Vulkan) sonst DIESE (gemessen: out/build mit GUI → 1.3.216, ggml-vulkan.cpp
# scheiterte an spirv/unified1/spirv.hpp). Die Fassung muss der in deps.lock.json gleichen.
string(JSON SWBH_VULKAN_SDK_VERSION GET "${SWBH_LOCK_JSON}" toolchain vulkan_sdk)
if(NOT DEFINED ENV{VULKAN_SDK} OR NOT EXISTS "$ENV{VULKAN_SDK}/Include/vulkan/vulkan.h")
    message(FATAL_ERROR "VULKAN_SDK nicht gesetzt oder ohne Header. ggml-vulkan.dll braucht das Vulkan-SDK ${SWBH_VULKAN_SDK_VERSION}.")
endif()
file(TO_CMAKE_PATH "$ENV{VULKAN_SDK}" _cs_vk)
set(Vulkan_INCLUDE_DIR "${_cs_vk}/Include" CACHE PATH "Vulkan-SDK-Header" FORCE)
set(Vulkan_LIBRARY "${_cs_vk}/Lib/vulkan-1.lib" CACHE FILEPATH "Vulkan-Loader-Importbibliothek" FORCE)
set(Vulkan_GLSLC_EXECUTABLE "${_cs_vk}/Bin/glslc.exe" CACHE FILEPATH "glslc" FORCE)
find_package(Vulkan COMPONENTS glslc)
if(NOT Vulkan_FOUND OR NOT Vulkan_GLSLC_EXECUTABLE)
    message(FATAL_ERROR "Vulkan-SDK mit glslc fehlt (VULKAN_SDK=${_cs_vk}).")
endif()
if(NOT Vulkan_VERSION VERSION_EQUAL SWBH_VULKAN_SDK_VERSION)
    message(FATAL_ERROR "Vulkan-SDK ${Vulkan_VERSION} gefunden (${Vulkan_INCLUDE_DIR}), deps.lock.json verlangt ${SWBH_VULKAN_SDK_VERSION}")
endif()

set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# Kein libmtmd: Der Baumeister-Host braucht kein Bildmodell (in kraken für llm/vision an).
set(LLAMA_BUILD_MTMD     OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN      ON  CACHE BOOL "" FORCE)
set(GGML_CUDA        OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE      OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP      OFF CACHE BOOL "" FORCE)
# Rechenkerne als ladbare DLLs (2026-10-14, Betreiber: „Teile, die man braucht, aus der Anwendung heraus aktualisieren
# … NPU, AMD, Intel“). Vorher war ggml samt Vulkan statisch in der EXE – ein anderer Kern (CUDA, OpenVINO, SYCL, ROCm)
# ließ sich gar nicht nachladen. Jetzt: ggml.dll + ggml-base.dll, die CPU in allen Varianten (die beste wählt
# accel::Runtime zur Laufzeit, statt fest AVX2 vorauszusetzen) und ggml-vulkan.dll im Setup; weitere Kerne lädt die App
# als Komponente aus den llama.cpp-Releases DERSELBEN Fassung – sie hängen nur an ggml-base.dll (dumpbin gemessen).
set(GGML_BACKEND_DL       ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
# Die CPU-Varianten, die MSVC baut (ggml/src/CMakeLists.txt: ivybridge, piledriver, cooperlake, zen4 und
# sapphirerapids nur mit Clang). Jede ist Pflicht im Paket – fehlte eine, liefe eine passende CPU still langsamer.
set(SWBH_GGML_CPU_VARIANTS x64 sse42 sandybridge haswell skylakex cannonlake cascadelake icelake alderlake)

set(_cs_saved_shared "${BUILD_SHARED_LIBS}")
set(_cs_saved_std "${CMAKE_CXX_STANDARD}")
set(CMAKE_CXX_STANDARD 17)

# Nur holen, nicht einbinden (SOURCE_SUBDIR ohne CMakeLists): ggml und llama werden unten getrennt eingebunden.
FetchContent_Declare(llama
    GIT_REPOSITORY "${SWBH_LLAMA_REPO}"
    GIT_TAG "${SWBH_LLAMA_COMMIT}"
    GIT_SHALLOW FALSE
    SOURCE_SUBDIR cs-nur-holen)
FetchContent_MakeAvailable(llama)

# ggml als DLL, llama STATISCH in der EXE: llm/llama_engine.cpp nutzt die interne Grammatik (llama_grammar_*_impl),
# die eine llama.dll nicht exportiert. llama.cpp sieht das vor – CMakeLists.txt:210 baut ggml nur, wenn noch kein Ziel
# „ggml“ existiert („otherwise assume ggml is added by a parent“).
string(SUBSTRING "${SWBH_LLAMA_COMMIT}" 0 7 _cs_llama_short)
set(GGML_BUILD_COMMIT "${_cs_llama_short}")
set(BUILD_SHARED_LIBS ON)
add_subdirectory("${llama_SOURCE_DIR}/ggml" "${llama_BINARY_DIR}/ggml" EXCLUDE_FROM_ALL)
set(BUILD_SHARED_LIBS OFF)
add_subdirectory("${llama_SOURCE_DIR}" "${llama_BINARY_DIR}" EXCLUDE_FROM_ALL)

set(BUILD_SHARED_LIBS "${_cs_saved_shared}")
set(CMAKE_CXX_STANDARD "${_cs_saved_std}")

# Ladbare Kerne hängen an nichts, was gelinkt wird – ohne eigenes Ziel baute EXCLUDE_FROM_ALL sie nie.
set(SWBH_GGML_BACKENDS ggml-vulkan)
foreach(_v IN LISTS SWBH_GGML_CPU_VARIANTS)
    list(APPEND SWBH_GGML_BACKENDS ggml-cpu-${_v})
endforeach()
foreach(_t IN LISTS SWBH_GGML_BACKENDS)
    if(NOT TARGET ${_t})
        message(FATAL_ERROR "ggml-Kern ${_t} fehlt im Bau (GGML_BACKEND_DL/GGML_CPU_ALL_VARIANTS)")
    endif()
endforeach()
add_custom_target(swbh_ggml_backends ALL DEPENDS ${SWBH_GGML_BACKENDS})
# ggml.dll, ggml-base.dll und die Kerne landen in <bau>/bin (ggml/CMakeLists.txt) – Tests finden sie über PATH,
# accel::Runtime die Kerne neben der geladenen ggml-base.dll.
list(APPEND SWBH_RUNTIME_PATHS "${CMAKE_BINARY_DIR}/bin")
set_target_properties(swbh_ggml_backends PROPERTIES FOLDER "third_party")

# Fremdcode: /utf-8 (sonst übersetzt MSVC Literale still in CP1252), keine Warnungen
# als Fehler. Nur C/C++, nie .comp/.cu.
foreach(_t llama ggml ggml-base ${SWBH_GGML_BACKENDS})
    if(TARGET ${_t})
        target_compile_options(${_t} PRIVATE "$<$<COMPILE_LANGUAGE:C,CXX>:/utf-8>")
        set_target_properties(${_t} PROPERTIES FOLDER "third_party")
    endif()
endforeach()

# Der geholte Stand muss genau der Commit aus deps.lock.json sein.
# git-Fehlertext mit ausgeben: Am 2026-10-04 meldete ein Lauf nur „steht auf ''“ – ohne die Meldung von
# git war die Ursache (vermutlich zwei Bauläufe gleichzeitig im selben Ordner) nicht mehr zu klären.
# Git über find_package (voller Pfad), nicht blankes „git“: Im Terminal des Betreibers stand git nicht im
# PATH – FetchContent und ggml fanden es über find_package, diese Prüfung nicht („Exit no such file or
# directory“, 2026-10-04).
find_package(Git REQUIRED)
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${llama_SOURCE_DIR}" rev-parse HEAD
    OUTPUT_VARIABLE _cs_llama_head OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_VARIABLE _cs_llama_err ERROR_STRIP_TRAILING_WHITESPACE
    RESULT_VARIABLE _cs_rc)
if(NOT _cs_rc EQUAL 0 OR NOT _cs_llama_head STREQUAL SWBH_LLAMA_COMMIT)
    message(FATAL_ERROR "llama.cpp steht auf '${_cs_llama_head}', erwartet ${SWBH_LLAMA_COMMIT} (deps.lock.json)\n"
                        "git (Exit ${_cs_rc}) in ${llama_SOURCE_DIR}: ${_cs_llama_err}")
endif()
message(STATUS "llama.cpp ${SWBH_LLAMA_COMMIT} (statisch; ggml als DLL mit Kernen: ${SWBH_GGML_BACKENDS})")
