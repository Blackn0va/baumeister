# Bereitstellung per cmake --install, ohne windeployqt (obs-deps-Qt hat keins;
# in CaptionBridgeCPP gemessen: die Generator-Ausdrücke lösen im Release-Bau auf
# die Release-DLLs auf, auch für Qt6::QWindowsIntegrationPlugin).
#
# Komponenten:
#   app      BaumeisterHost.exe
#   runtime  Qt-DLLs + platforms/qwindows.dll,
#            VC-Laufzeit app-lokal. llama.cpp ist statisch in der EXE, ggml als
#            ggml.dll + ggml-base.dll mit den Kernen ggml-cpu-<Variante>.dll und
#            ggml-vulkan.dll (cmake/SwaLlama.cmake); vulkan-1.dll kommt mit dem
#            Grafiktreiber (Baseline-Liste driver_dlls). Weitere Kerne (CUDA, OpenVINO,
#            Den CUDA-Kern lädt die App als Komponente nach Zustimmung, nie im Setup.
#
# build.ps1 -Stage stage installiert app+runtime nach out/dist und prüft danach
# fail-closed jede PE-Abhängigkeit gegen installer/windows-baseline-dlls.json.

include_guard(GLOBAL)

set(SWBH_VC_REDIST_DIR "" CACHE PATH "Ordner .../VC/Redist/MSVC/<ver>/x64 (leer = automatisch neben dem Compiler suchen)")

function(_cs_find_vc_redist out_var)
    if(SWBH_VC_REDIST_DIR)
        set(${out_var} "${SWBH_VC_REDIST_DIR}" PARENT_SCOPE)
        return()
    endif()
    # cl.exe liegt in <VS>/VC/Tools/MSVC/<ver>/bin/Hostx64/x64 → <VS>/VC/Redist/MSVC/<redist-ver>/x64
    get_filename_component(_bin "${CMAKE_CXX_COMPILER}" DIRECTORY)
    get_filename_component(_vc "${_bin}/../../../../../.." ABSOLUTE)
    file(GLOB _candidates LIST_DIRECTORIES true "${_vc}/Redist/MSVC/14.44.*/x64")
    if(NOT _candidates)
        file(GLOB _candidates LIST_DIRECTORIES true "${_vc}/Redist/MSVC/14.*/x64")
    endif()
    list(SORT _candidates COMPARE NATURAL ORDER DESCENDING)
    list(GET _candidates 0 _first)
    set(${out_var} "${_first}" PARENT_SCOPE)
endfunction()

function(swbh_stage_rules)
    if(TARGET swbh_gui)
        install(TARGETS swbh_gui RUNTIME DESTINATION . COMPONENT app)
    endif()

    if(TARGET ggml-base)
        install(FILES "$<TARGET_FILE:ggml>" "$<TARGET_FILE:ggml-base>" DESTINATION . COMPONENT runtime)
        foreach(_t IN LISTS SWBH_GGML_BACKENDS)
            install(FILES "$<TARGET_FILE:${_t}>" DESTINATION . COMPONENT runtime)
        endforeach()
    endif()

    if(TARGET Qt6::Core)
        install(FILES "$<TARGET_FILE:Qt6::Core>" "$<TARGET_FILE:Qt6::Gui>" "$<TARGET_FILE:Qt6::Widgets>"
                DESTINATION . COMPONENT runtime)
        install(FILES "$<TARGET_FILE:Qt6::QWindowsIntegrationPlugin>" DESTINATION platforms COMPONENT runtime)
    endif()

    _cs_find_vc_redist(_redist)
    if(_redist AND EXISTS "${_redist}")
        file(GLOB _crt "${_redist}/Microsoft.VC143.CRT/msvcp140*.dll" "${_redist}/Microsoft.VC143.CRT/vcruntime140*.dll")
        install(FILES ${_crt} DESTINATION . COMPONENT runtime)
    else()
        message(FATAL_ERROR "VC-Laufzeit (Redist) nicht gefunden – ohne app-lokale CRT kein vollständiges Paket")
    endif()
endfunction()
