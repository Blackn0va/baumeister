# Modul relay – Fernverbindung über Kanal v1 (docs/kanal-v1.md): Nachrichten, Weiterleitung an den eigenen Server,
# Verbindung zum Relay mit Neustart. Ohne Qt; TLS, Pins und Client-Zertifikat liegen in win/.

swbh_add_module(relay
    SOURCES
        kanal.hpp kanal.cpp
        forwarder.hpp forwarder.cpp
        relay_link.hpp relay_link.cpp
    PUBLIC_DEPS
        swbh::base swbh::win)