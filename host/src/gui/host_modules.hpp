// Eigene Module der Host-App für die Seite „Module“ (Vertrag v012, AK-38; Wunsch 2026-11-22: „modular, übersichtlich“).
//
// Kein Umbau der Fachlogik: Jedes Modul ist nur eine Sicht auf einen BESTEHENDEN Konfigschlüssel (Vertrag, non_goals)
// und auf den Live-Zustand, den das Hauptfenster ohnehin abfragt. Schalten schreibt genau diesen Schlüssel – dieselbe
// Wirkung wie das Häkchen in den Einstellungen. Einziger neuer Schlüssel ist „stadt_vorschau“, weil die Stadt-Vorschau
// bisher gar keinen Schalter hatte.
//
// Die Verbindung zum Minecraft-Server steht nur als Anzeige hier: Festgelegt ist (2026-11-01), dass sie über den Knopf
// „Verbinden“ läuft und keine weiteren Tunnel- oder Start-Häkchen dazukommen. Ein zweiter Schalter dafür wäre genau so
// eines.
//
// Threads: reentrant. Fehler: keine Ausnahmen; set_host_module meldet ein unbekanntes oder nicht schaltbares Modul mit
// false.

#pragma once

#include "gui/app_config.hpp"
#include "tunnel/tunnel.hpp"

#include <string>
#include <string_view>
#include <vector>

namespace swbh::gui {

enum class HostModuleState { Active, Off, Waiting, Failed };

// Fortschritt eines Bausteins, der erst geladen werden muss (Sprachmodell, Spracherkennung).
enum class LoadState { Off, Busy, Ready, Failed };

// Live-Zustand, aus Snapshot und SpeechStatus abgeleitet (das macht das Fenster – hier kein Qt und kein Controller).
struct HostModuleFacts {
    LoadState model = LoadState::Off;
    bool downloading = false;
    LoadState speech = LoadState::Off;
    std::string speech_error;
    bool server_running = false;
    tunnel::State link = tunnel::State::Stopped;
    std::string link_text;
    bool city_reported = false;  // Plugin hat schon einen Stadt- oder Weltbau gemeldet
};

struct HostModule {
    std::string id;           // wie die Server-Module: [a-z0-9-]
    std::string name;
    std::string description;
    std::string key;          // Konfigschlüssel in config.local.json, leer = nur Anzeige
    bool on = false;
    bool switchable = true;
    HostModuleState state = HostModuleState::Off;
    std::string reason;       // leer = nichts zu sagen
    std::string effect;       // was ein Umschalten bewirkt („wirkt sofort“ oder ehrlicher Hinweis)
};

std::vector<HostModule> host_modules(const AppConfig& config, const HostModuleFacts& facts);

// Schreibt den Schlüssel des Moduls. false = unbekannte id oder reines Anzeigemodul (config bleibt unverändert).
bool set_host_module(AppConfig& config, std::string_view id, bool on);

std::string_view host_module_state_de(HostModuleState state);

}  // namespace swbh::gui
