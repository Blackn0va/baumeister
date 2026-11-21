swbh_add_test(swbh_test_gui SOURCES test_app_config.cpp test_secret.cpp test_autostart.cpp test_app_args.cpp
    test_plan_view.cpp test_notifier.cpp test_job_tracker.cpp test_city_paint.cpp test_console_export.cpp
    test_console_throttle.cpp test_exit_report.cpp test_simple_mode.cpp
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