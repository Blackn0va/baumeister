# Modul gui – Einstellungen, Befehlszeile und Autostart (ohne Qt, testbar) und die Qt-6-Oberfläche BaumeisterHost.exe.

swbh_add_module(gui_core
    SOURCES
        app_config.hpp app_config.cpp
        app_args.hpp app_args.cpp
        autostart.hpp autostart.cpp
        plan_view.hpp plan_view.cpp
        job_stats.hpp job_stats.cpp
        job_tracker.hpp job_tracker.cpp
        notifier.hpp notifier.cpp
        city_paint.hpp city_paint.cpp
        console_export.hpp console_export.cpp
        console_throttle.hpp console_throttle.cpp
        exit_report.hpp exit_report.cpp
        simple_mode.hpp simple_mode.cpp
    PUBLIC_DEPS
        swbh::base swbh::llm swbh::tunnel swbh::relay)

swbh_add_module(gui_qt
    SOURCES
        style.hpp style.cpp
        controller.hpp controller.cpp
        main_window.hpp main_window.cpp
        settings_dialog.hpp settings_dialog.cpp
        plan_preview.hpp plan_preview.cpp
        city_view.hpp city_view.cpp
    PUBLIC_DEPS
        swbh::gui_core swbh::server swbh::tunnel swbh::relay swbh::voice swbh::llm swbh::components swbh::accel swbh::win Qt6::Widgets)
set_target_properties(swbh_gui_qt PROPERTIES AUTOMOC ON)
# Ohne Qt-Schlüsselwörter (signals/slots/emit als Makros) – aus kraken: sie zerlegten dort Bezeichner der Kernmodule.
target_compile_definitions(swbh_gui_qt PUBLIC QT_NO_KEYWORDS)
# Programm-Icon für Fenster, Tray und Dialoge: dieselbe Datei wie die EXE-Ressource, eingebettet.
swbh_embed_file(TARGET swbh_gui_qt FILE "${SWBH_SOURCE_ROOT}/resources/branding/BaumeisterHost.ico" SYMBOL app_icon_ico)

# APP_ICON: Programm-Icon als Ressource. BaumeisterHost.manifest: Per-Monitor-DPI v2 vor dem ersten Fenster (aus kraken).
swbh_add_executable(swbh_gui WIN32 APP_ICON
    OUTPUT_NAME BaumeisterHost
    DESCRIPTION "StreamWizard Baumeister-Host"
    SOURCES
        main_gui.cpp
        BaumeisterHost.manifest
    DEPS
        swbh::gui_qt Qt6::Widgets)
set_target_properties(swbh_gui PROPERTIES
    AUTOMOC ON
    qt_no_entrypoint TRUE)

# Entwicklungsläufe aus dem Bauordner: ggml.dll, ggml-base.dll und die Kerne neben die EXE (die EXE löst Importe nur
# im eigenen Ordner und in System32 auf, /DEPENDENTLOADFLAG).
set(_cs_ggml_files "$<TARGET_FILE:ggml>" "$<TARGET_FILE:ggml-base>")
foreach(_t IN LISTS SWBH_GGML_BACKENDS)
    list(APPEND _cs_ggml_files "$<TARGET_FILE:${_t}>")
endforeach()
add_dependencies(swbh_gui swbh_ggml_backends)
add_custom_command(TARGET swbh_gui POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different ${_cs_ggml_files} "$<TARGET_FILE_DIR:swbh_gui>"
    VERBATIM)

# qtbase_de.qm (deutsche Standardknöpfe), aus kraken übernommen; der Hash macht jede Abweichung sichtbar.
set(SWBH_QTBASE_DE_QM "${CMAKE_CURRENT_SOURCE_DIR}/translations/qtbase_de.qm")
set(SWBH_QTBASE_DE_QM_SHA256 "ed828a83dfaa51944f166c842155b48ab3bd7cb2c46dcd96269eb5d48af4f63f")
file(SHA256 "${SWBH_QTBASE_DE_QM}" _cs_qm_hash)
if(NOT _cs_qm_hash STREQUAL SWBH_QTBASE_DE_QM_SHA256)
    message(FATAL_ERROR "qtbase_de.qm weicht ab: erwartet ${SWBH_QTBASE_DE_QM_SHA256}, gefunden ${_cs_qm_hash}")
endif()
install(FILES "${SWBH_QTBASE_DE_QM}" DESTINATION translations COMPONENT runtime)

add_custom_command(TARGET swbh_gui POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "$<TARGET_FILE_DIR:swbh_gui>/platforms"
    COMMAND ${CMAKE_COMMAND} -E make_directory "$<TARGET_FILE_DIR:swbh_gui>/translations"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:Qt6::Core>" "$<TARGET_FILE:Qt6::Gui>" "$<TARGET_FILE:Qt6::Widgets>"
        "$<TARGET_FILE_DIR:swbh_gui>"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:Qt6::QWindowsIntegrationPlugin>" "$<TARGET_FILE_DIR:swbh_gui>/platforms"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${SWBH_QTBASE_DE_QM}" "$<TARGET_FILE_DIR:swbh_gui>/translations"
    VERBATIM)
