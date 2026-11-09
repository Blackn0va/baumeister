// Einfache Bedienung: zwei Fragen statt verstreuter Häkchen, und ein Satz, was gerade geht.
//
// Warum: Wunsch 2026-11-01. Vorher gab es „Alles starten“, „Nur verbinden“, „Modell laden“,
// „Modell herunterladen“ nebeneinander und in den Einstellungen „Tunnel mitstarten“, „lokaler Server“, „Autostart“
// und „Modell beim Start“ als vier Häkchen. Jetzt:
//  - Hauptfenster: „Verbinden/Trennen“ (Server und, wenn nötig, Tunnel) und „KI einschalten/ausschalten“ (Modell, lädt
//    es vorher herunter) – zwei unabhängige Schalter. Darüber ein Satz (mode_line_de), was damit gerade geht.
//  - Einstellungen: „Der Server läuft auf einem anderen Rechner / auf diesem PC / im Internet über eine Adresse
//    (HTTPS)“ (ServerPlace, die dritte Wahl seit 2026-11-08: Fernverbindung über Kanal v1) und „Beim
//    Programmstart: verbinden und KI / nur verbinden / nichts“ (StartMode). Die Felder der Konfiguration bleiben, damit
//    alte config.local.json gültig sind; diese Funktionen übersetzen nur.
//
// Threads: reentrant. Fehler: keine.

#pragma once

#include "gui/app_config.hpp"

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::gui {

enum class ServerPlace { Remote, ThisPc, Internet };
enum class StartMode { ConnectWithAi, ConnectOnly, Nothing };

// Anderer Rechner = Tunnel an. Dieser PC = Tunnel aus; die App lauscht dann auf dem Plugin-Port (local_server), sonst
// erreichte sie der Server gar nicht – deshalb setzt apply_server_place local_server immer mit. Internet = Fernverbindung
// an, Tunnel aus (nie beide).
ServerPlace server_place(const AppConfig& config);
void apply_server_place(AppConfig& config, ServerPlace place);

StartMode start_mode(const AppConfig& config);
void apply_start_mode(AppConfig& config, StartMode mode);

enum class AiState { Off, Loading, On, Failed };

struct ModeInput {
    bool connected = false;      // Server dieser App läuft
    bool tunnel_needed = false;  // anderer Rechner
    bool tunnel_up = false;      // Tunnel meldet „verbunden“
    bool server_set = true;      // Server-Adresse bzw. Server-Pin eingetragen (nur bei tunnel_needed von Belang)
    bool via_address = false;    // Fernverbindung statt Tunnel (andere Sätze)
    AiState ai = AiState::Off;
};

// Ein Satz für die Kopfzeile der Schalter, z. B. „Verbunden ohne KI: Stadt- und Weltbau laufen, Bauaufträge an die KI
// lehnt das Plugin ab.“ Stadt- und Weltbau laufen im Plugin immer ohne KI und brauchen die App nicht einmal; verbunden
// zeigt sie nur ihren Fortschritt – auch das sagt der Satz, weil genau das unklar war.
std::string mode_line_de(const ModeInput& in);

// Felder der Fernverbindung in den Einstellungen prüfen: Adresse wss://…/kanal/v1, erster Pin Pflicht, zweiter
// optional, beide sha256:<64 Hex> und verschieden. nullopt = in Ordnung, sonst deutscher Grund für die Meldung.
std::optional<std::string> relay_settings_problem(std::string_view url, std::string_view pin1, std::string_view pin2);
// Die gültigen Pins aus beiden Feldern, klein und ohne leere (Reihenfolge wie eingegeben).
std::vector<std::string> relay_pins_from(std::string_view pin1, std::string_view pin2);

}  // namespace swbh::gui
