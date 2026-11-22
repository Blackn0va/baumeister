swbh_add_test(swbh_test_gui SOURCES test_app_config.cpp test_secret.cpp test_autostart.cpp test_app_args.cpp
    test_plan_view.cpp test_notifier.cpp test_job_tracker.cpp test_city_paint.cpp test_console_export.cpp
    test_console_throttle.cpp test_exit_report.cpp test_simple_mode.cpp test_host_modules.cpp
    LIBS swbh::gui_core swbh::server swbh::win)

# Widgets mit echtem Qt (Plattform „minimal“ setzt der Test selbst, wie kraken swa_test_gui_widgets).
swbh_add_test(swbh_test_gui_widgets SOURCES test_plan_preview.cpp
    LIBS swbh::gui_qt Qt6::Widgets)

# Reiter „Stadt“ – eigenes Programm: Zwei QApplication in einem Prozess (Plan-Vorschau legt schon eine an) stürzen ab.
swbh_add_test(swbh_test_city_view SOURCES test_city_view.cpp
    LIBS swbh::gui_qt Qt6::Widgets)

# Einstellungen-Dialog (Fernverbindung, Ortswahl) – eigenes Programm aus demselben Grund wie oben.
swbh_add_test(swbh_test_settings_dialog SOURCES test_settings_dialog.cpp
    LIBS swbh::gui_qt Qt6::Widgets)
# Reiter „Module“ (Vertrag v012, AK-38) – eigenes Programm aus demselben Grund wie oben.
swbh_add_test(swbh_test_modules_page SOURCES test_modules_page.cpp
    LIBS swbh::gui_qt Qt6::Widgets)
# Verdrahtung (T-007): Begleitername Long-Poll → Erkennung (gui/companion_wiring liegt in gui_qt, ist aber Qt-frei) und
# Plan/Meldungen der Fensterabfrage (gui/window_feed). Ohne QApplication, deshalb ohne Plattform-Plugin.
swbh_add_test(swbh_test_gui_wiring SOURCES test_window_wiring.cpp
    LIBS swbh::gui_qt swbh::voice swbh::server)

# Messprobe T-017 (kein ctest): Zellen der Plan-Vorschau für den Vergleich mit dem PlanCompiler des Plugins, siehe
# probes/plan_preview/README.md. Echte Antworten liegen nie im Repo, daher Ordner als Argumente statt Testfall.
add_executable(swbh_probe_plan_preview plan_preview_probe.cpp)
target_link_libraries(swbh_probe_plan_preview PRIVATE swbh::compile_defaults swbh::gui_core)
swbh_strict_warnings(swbh_probe_plan_preview)
set_target_properties(swbh_probe_plan_preview PROPERTIES FOLDER "probes")
