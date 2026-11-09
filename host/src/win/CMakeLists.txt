# Modul win – Windows-Bausteine hinter kleinen Schnittstellen (übernommen aus kraken, nur was Download,
# Komponentenprüfung und DLL-Härtung brauchen). Neu für die Fernverbindung (2026-11-08): cert_pin, client_cert
# (NCrypt, daher ncrypt.dll – steht in installer/windows-baseline-dlls.json) und pinned_ws.

swbh_add_module(win
    SOURCES
        cert_pin.hpp cert_pin.cpp
        client_cert.hpp client_cert.cpp
        cng.hpp cng.cpp
        der.hpp der.cpp
        crash_guard.hpp crash_guard.cpp
        dll_guard.hpp dll_guard.cpp
        fs_security.hpp fs_security.cpp
        http.hpp http.cpp
        pinned_ws.hpp pinned_ws.cpp
        proxy.hpp proxy.cpp
        tls_diag.hpp tls_diag.cpp
        secret_store.hpp secret_store.cpp
        winhttp_internal.hpp
        winhttp_transport.cpp
        winhttp_download.cpp
    PUBLIC_DEPS
        swbh::base
    PRIVATE_DEPS
        winhttp crypt32 bcrypt ncrypt advapi32 user32)
