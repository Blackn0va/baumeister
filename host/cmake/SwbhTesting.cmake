# Testrahmen: doctest 2.4.11 (vendort), ein Test-Programm je Modul, ctest-Labels.
#
#   swbh_add_test(<name> SOURCES … [LIBS …] [LABELS unit|integration …])
#
# Label "unit": ohne Netz, ohne Modelle, schnell – läuft in build.ps1 -Stage test.
# Label "gpu": braucht eine GGUF-Datei (Umgebungsvariable SWBH_TEST_GGUF) und eine GPU;
# läuft nur auf ausdrücklichen Wunsch (build.ps1 -GpuTestModel <datei>).
#
# Jedes Test-Programm bekommt:
#   SWBH_SOURCE_DIR       Wurzel von Baumeister-Host (Testdaten, resources/)
#   SWBH_TEST_BINARY_DIR  eigener Ausgabeordner (Temp-Dateien der Tests)
# und im PATH die Laufzeitordner aus SWBH_RUNTIME_PATHS (Qt, llama.cpp).

include_guard(GLOBAL)

function(swbh_add_test name)
    cmake_parse_arguments(ARG "" "" "SOURCES;LIBS;LABELS" ${ARGN})
    add_executable(${name} ${ARG_SOURCES})
    target_link_libraries(${name} PRIVATE swbh::compile_defaults swbh::test_main swbh::test_support ${ARG_LIBS})
    swbh_strict_warnings(${name})
    target_compile_definitions(${name} PRIVATE
        SWBH_SOURCE_DIR="${SWBH_SOURCE_ROOT}"
        SWBH_TEST_BINARY_DIR="${CMAKE_CURRENT_BINARY_DIR}")
    set_target_properties(${name} PROPERTIES FOLDER "tests")
    set(_labels "${ARG_LABELS}")
    if(NOT _labels)
        set(_labels unit)
    endif()
    add_test(NAME ${name} COMMAND ${name} --no-intro=true)
    set(_env "")
    foreach(_dir IN LISTS SWBH_RUNTIME_PATHS)
        file(TO_NATIVE_PATH "${_dir}" _native)
        list(APPEND _env "PATH=path_list_prepend:${_native}")
    endforeach()
    set_tests_properties(${name} PROPERTIES LABELS "${_labels}" TIMEOUT 300)
    if(_env)
        set_tests_properties(${name} PROPERTIES ENVIRONMENT_MODIFICATION "${_env}")
    endif()
endfunction()
