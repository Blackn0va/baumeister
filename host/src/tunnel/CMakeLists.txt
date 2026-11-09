# Modul tunnel – SSH-Rückwärtstunnel zu dev01 über plink.exe (Pageant-Schlüssel).

swbh_add_module(tunnel
    SOURCES
        tunnel.hpp tunnel.cpp
    PUBLIC_DEPS
        swbh::base
    PRIVATE_DEPS
        user32)
