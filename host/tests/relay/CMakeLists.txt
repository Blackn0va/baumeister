# Fernverbindung (Kanal v1): Testvektoren aus docs/, Weiterleitung an den ECHTEN eigenen Server, Verbindung gegen eine
# Loopback-Gegenstelle über den echten WinHTTP-WebSocket. Kein Netz: alles an 127.0.0.1.

add_library(swbh_test_relay_support STATIC ws_frame.hpp ws_frame.cpp fake_relay.hpp fake_relay.cpp)
target_include_directories(swbh_test_relay_support PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(swbh_test_relay_support PUBLIC swbh::compile_defaults swbh::relay swbh::test_support ws2_32
    bcrypt)
swbh_strict_warnings(swbh_test_relay_support)
set_target_properties(swbh_test_relay_support PROPERTIES FOLDER "tests")

swbh_add_test(swbh_test_relay SOURCES test_kanal.cpp test_relay_link.cpp
    LIBS swbh_test_relay_support swbh::relay swbh::server ws2_32)

# TLS gegen die JSSE-Gegenstelle der Messprobe (probes/winhttp_pin/RelayProbe.java): braucht ein JDK (JAVA_HOME) –
# deshalb „integration“, läuft nur ausdrücklich (ctest -L integration).
swbh_add_test(swbh_test_relay_tls SOURCES test_pinned_tls.cpp LIBS swbh::relay swbh::win LABELS integration)