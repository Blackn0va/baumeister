# whisper.cpp (Spracherkennung des Begleiters) per FetchContent – gebaut wird nur src/whisper.cpp als statische Bibliothek
# gegen das ggml, das SwbhLlama.cmake schon als DLL baut.
#
# Warum kein add_subdirectory von whisper.cpp: Dessen CMakeLists bringt ein eigenes ggml mit. Zwei ggml.dll in einem
# Prozess teilen sich keinen Geräte-Registry – whisper sähe die von accel::Runtime geladenen Kerne (Vulkan, CUDA) nicht,
# und Symbole gleichen Namens aus zwei DLLs sind eine Fehlerquelle für sich. Deshalb der Commit mit derselben
# ggml-Fassung (0.23.0) wie llama.cpp b10900 (deps.lock.json, fetchcontent whisper.cpp).
#
# Wie llama.cpp als C++17, /utf-8, keine eigenen Warnstufen (Fremdcode).

include_guard(GLOBAL)
include(FetchContent)

string(JSON SWBH_WHISPER_REPO GET "${SWBH_LOCK_JSON}" fetchcontent whisper.cpp repository)
string(JSON SWBH_WHISPER_COMMIT GET "${SWBH_LOCK_JSON}" fetchcontent whisper.cpp commit)

FetchContent_Declare(whisper
    GIT_REPOSITORY "${SWBH_WHISPER_REPO}"
    GIT_TAG "${SWBH_WHISPER_COMMIT}"
    GIT_SHALLOW FALSE
    SOURCE_SUBDIR cs-nur-holen)
FetchContent_MakeAvailable(whisper)

# ggml-Gleichstand prüfen (wie kraken/cmake/SwaWhisper.cmake): Ein späterer Commit-Wechsel auf einer Seite fiele sonst
# erst als Absturz zur Laufzeit auf. Wer llama.cpp hebt, muss whisper.cpp auf einen Stand mit gleichem ggml heben.
file(STRINGS "${whisper_SOURCE_DIR}/ggml/CMakeLists.txt" _swbh_wg REGEX "^set\\(GGML_VERSION_(MAJOR|MINOR|PATCH) ")
file(STRINGS "${llama_SOURCE_DIR}/ggml/CMakeLists.txt" _swbh_lg REGEX "^set\\(GGML_VERSION_(MAJOR|MINOR|PATCH) ")
if(NOT _swbh_wg STREQUAL _swbh_lg)
    message(FATAL_ERROR "ggml in whisper.cpp (${_swbh_wg}) ≠ ggml in llama.cpp (${_swbh_lg}) – beide auf denselben Stand heben.")
endif()
# Version für WHISPER_VERSION (sonst aus whisper.cpps eigenem CMake): der Tag aus deps.lock.json.
string(JSON _swbh_wv GET "${SWBH_LOCK_JSON}" fetchcontent whisper.cpp tag)
message(STATUS "whisper.cpp ${_swbh_wv} (${SWBH_WHISPER_COMMIT}), ggml geteilt mit llama.cpp")

add_library(swbh_whisper_lib STATIC
    "${whisper_SOURCE_DIR}/src/whisper.cpp"
    "${whisper_SOURCE_DIR}/include/whisper.h")
target_include_directories(swbh_whisper_lib
    PUBLIC "${whisper_SOURCE_DIR}/include"
    PRIVATE "${whisper_SOURCE_DIR}/src")
target_link_libraries(swbh_whisper_lib PUBLIC ggml ggml-base)
set_target_properties(swbh_whisper_lib PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON FOLDER "third_party")
target_compile_options(swbh_whisper_lib PRIVATE "$<$<COMPILE_LANGUAGE:C,CXX>:/utf-8>")
# WHISPER_VERSION setzt sonst whisper.cpps eigenes CMake (src/CMakeLists.txt), das hier nicht läuft.
target_compile_definitions(swbh_whisper_lib PRIVATE WHISPER_VERSION="${_swbh_wv}" _CRT_SECURE_NO_WARNINGS)
