// Benachrichtigungen im Infobereich (Windows-Meldung über QSystemTrayIcon): Tunnel weg / wieder da, Modell geladen,
// Bauplan fertig / fehlgeschlagen – abschaltbar (AppConfig::notifications).
//
// Warum eine eigene Regelklasse ohne Qt: Die Oberfläche fragt den Zustand alle 250 ms ab (controller.hpp). Ob daraus
// eine Meldung wird, hängt am ÜBERGANG zwischen zwei Abfragen – genau das, was man ohne Fenster testen will:
//  - Der erste Aufruf legt nur den Ausgangszustand fest. Sonst meldete ein Start mit schon verbundenem Tunnel oder
//    geladenem Modell Dinge, die gar nicht passiert sind.
//  - „Tunnel weg“ nur aus „verbunden“ heraus (nicht beim ersten Verbindungsversuch, nicht bei „Alles stoppen“) und
//    nur einmal, bis er wieder steht; „wieder da“ nur nach einem „weg“. Ohne diese Klammer kam bei fehlendem
//    Pageant-Schlüssel alle zehn Sekunden eine Meldung (gleicher Grund wie in main_window.cpp hidden_error_area).
//    Ein absichtlicher Neustart (Snapshot::tunnel_generation hat sich geändert) zählt wie „Stopped“, auch wenn die
//    Abfrage den Zwischenstand nie sah.
//  - Abgeschaltet verfolgt die Klasse den Zustand weiter und meldet nur nichts. Sonst käme beim Wiedereinschalten
//    alles nach, was in der Zwischenzeit geschah.
// Fehler beim verborgenen Fenster (Download, Laden, Server, Tunnel-Einstellungen) meldet main_window.cpp weiter
// selbst – die bleiben auch mit abgeschalteten Benachrichtigungen, weil sonst ein stiller Start nach der Anmeldung
// stumm scheitert.
//
// Threads: nur GUI-Thread. Fehler: keine.

#pragma once

#include "gui/plan_view.hpp"
#include "tunnel/tunnel.hpp"

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace swbh::gui {

enum class NoticeKind { TunnelLost, PlanFailed, TunnelBack, PlanDone, ModelReady };

struct Notice {
    NoticeKind kind = NoticeKind::ModelReady;
    std::string title;
    std::string text;
    bool warning = false;
};

struct NotifyInput {
    bool model_loaded = false;
    std::string model_title;
    tunnel::State tunnel = tunnel::State::Stopped;
    std::string tunnel_text;
    std::uint64_t tunnel_generation = 0;  // Snapshot::tunnel_generation
    // Fernverbindung über die Adresse statt SSH-Tunnel (Snapshot::relay_enabled): gleiche Regeln, eigene Worte
    // („Verbindung getrennt“ statt „Tunnel getrennt“).
    bool via_address = false;
    PlanRecord job;                       // seq 0 = noch kein Auftrag
};

class Notifier final {
public:
    std::vector<Notice> update(const NotifyInput& in, bool enabled);

private:
    bool primed_ = false;
    bool model_loaded_ = false;
    tunnel::State tunnel_ = tunnel::State::Stopped;
    bool tunnel_lost_ = false;
    std::uint64_t tunnel_generation_ = 0;
    std::uint64_t job_seq_ = 0;
};

// Mehrere Meldungen aus einer Abfrage zu einer: Windows zeigt ohnehin nur die letzte. Vorne steht die wichtigste
// (Reihenfolge von NoticeKind: Tunnel weg vor Plan fehlgeschlagen vor wieder da vor Plan fertig vor Modell bereit), die
// übrigen als Zusatzzeile. nullopt bei leerer Liste.
std::optional<Notice> merge_notices(const std::vector<Notice>& notices);

// Lage des Fensters in der Abfrage, in der eine Meldung anstünde.
struct NoticeGate {
    bool enabled = true;         // AppConfig::notifications
    bool has_tray = false;       // Symbol im Infobereich vorhanden
    bool window_active = false;  // aktives, sichtbares, nicht minimiertes Hauptfenster
    bool error_shown = false;    // main_window hat in dieser Abfrage schon eine Fehler-Sprechblase gezeigt
};

// Was jetzt gezeigt wird. pending hält Meldungen, die eine Fehler-Sprechblase derselben Abfrage verdrängt hat, bis
// zur nächsten Abfrage ohne Sprechblase. Vorher wurden sie verworfen: Fiel ein Server-Fehler in dieselben 250 ms wie
// „Bauplan fertig“, erfuhr der Nutzer vom fertigen Plan nie (Prüfbefund 2026-10-29) – der Notifier hatte den Übergang
// da schon verbucht. Aktives Fenster, kein Symbol oder abgeschaltet: alles verwerfen, auch Zurückgehaltenes.
std::optional<Notice> notice_to_show(std::vector<Notice>& pending, const std::vector<Notice>& fresh,
                                     const NoticeGate& gate);

}  // namespace swbh::gui
