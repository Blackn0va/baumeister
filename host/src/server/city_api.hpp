// Stadtansicht: Das Plugin meldet Stadt- und Weltbau (POST /baumeister/v1/stadt), die GUI zeichnet daraus eine Karte
// (Wunsch 2026-10-29).
//
// Schnittstelle Version 1, Gegenstück im Plugin: HostReporter.java (Nachrichten) und CityRaster.java (Kategorien) –
// beide Seiten gleich halten. Nachrichten start | stage | progress | end; Kategorien 0 leer, 1 Gebäude, 2 Straße,
// 3 Fußweg/Platz, 4 Wasser, 5 Grün, 6 Baum, 7 Gleis, 8 Brücke, 9 sonstiges. Antwort 200 {"ok":true} (204 ginge mit dem
// immer gesetzten Content-Length nicht, RFC 9110 §8.6), 400 {"error":"snake_code"} bei ungültigem Inhalt. Größe der
// Anfrage begrenzt der Server (kMaxBodyBytes 256 KiB) – das Plugin hält jedes Raster deshalb bei höchstens 360².
//
// Weltbau (Nachricht „welt“, ohne Auftrag): Kachelübersicht der Erdwelt – fertige, gescheiterte und wartende
// Kacheln, die laufende samt Phase, Automatik und Spieler. Startet ein neuer Weltbau-Auftrag in derselben Welt, bleiben
// die Raster der vorigen Kacheln als Hintergrund stehen (Wunsch 2026-10-29 –
// vorher ersetzte jede Kachel die Karte der letzten). Bei Platzmangel fallen die ältesten Hintergrund-Etappen zuerst.
//
// Gehalten wird nur der jüngste Auftrag; Nachrichten eines anderen Auftrags werden mit 200 angenommen und verworfen
// (nach einem neuen „start“ treffen noch Reste des alten ein). Höchstens kCityMaxCells Rasterzellen je Auftrag (16 Mio.
// ≈ 16 MB) – mehr bekommt 400, damit ein kaputter oder böswilliger Absender den Speicher nicht füllt (ASVS V11.1.4).
//
// Threads: apply/handle_city aus beliebigen Verbindungs-Threads, snapshot aus der GUI. Jede Änderung legt einen neuen
// unveränderlichen Schnappschuss an (Raster als shared_ptr geteilt) – die GUI liest ohne Sperre weiter, während
// geschrieben wird. Fehler: keine Ausnahmen nach außen.

#pragma once

#include "base/json.hpp"
#include "server/chat_api.hpp"
#include "server/http.hpp"

#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::server {

inline constexpr std::string_view kCityPath = "/baumeister/v1/stadt";
inline constexpr int kCityMaxSide = 360;  // CityRaster.MAX_SIDE im Plugin
inline constexpr int kCityMaxCell = 16;
inline constexpr int kCityMaxStages = 10000;
inline constexpr std::uint64_t kCityMaxCells = 16'000'000;
inline constexpr std::uint8_t kCityCategories = 10;
inline constexpr int kWorldMaxDone = 4096;
inline constexpr int kWorldMaxQueue = 256;
inline constexpr int kWorldMaxPlayers = 64;

struct CityStage {
    int index = 0;
    int x0 = 0;  // Weltkoordinate der linken oberen Rasterecke
    int z0 = 0;
    int cell = 1;  // Blöcke je Rasterzelle
    int w = 0;
    int h = 0;
    std::vector<std::uint8_t> cells;  // zeilenweise (z außen, x innen), Werte 0..9
};

struct CityProgress {
    int stages_done = 0;
    int current_stage = -1;
    long long blocks = 0;
    long long planned = 0;
    double rate = 0.0;
    bool paused = false;
    bool has_npc = false;
    int npc_x = 0;
    int npc_z = 0;
};

// Kachelübersicht des Weltbaus (Nachricht „welt“). Kacheln als Index (tx, tz), Kante tile Blöcke.
struct WorldOverview {
    std::string world;
    int tile = 1024;
    std::vector<std::pair<int, int>> done;
    std::vector<std::pair<int, int>> failed;
    std::vector<std::pair<int, int>> queue;
    bool has_current = false;
    int current_tx = 0;
    int current_tz = 0;
    std::string phase;  // wartet | karte | planen | bereit | bauen
    bool auto_on = false;
    std::vector<std::pair<int, int>> players;  // Blockkoordinaten x, z
};

// Unveränderlicher Schnappschuss des aktuellen Auftrags.
struct CityView {
    std::string job;
    std::string kind;  // "stadt" | "weltbau"
    std::string place;
    std::string world;
    int stages = 0;
    int origin_x = 0;
    int origin_z = 0;
    std::vector<std::shared_ptr<const CityStage>> stage_list;  // in Ankunftsreihenfolge, je index höchstens einmal
    // Weltbau: Etappen früherer Kacheln derselben Welt, älteste zuerst (gezeichnet unter stage_list).
    std::vector<std::shared_ptr<const CityStage>> background;
    // Weltbau: letzte Kachelübersicht (nullptr = keine). Unabhängig vom Auftrag; job ist leer, solange nur sie da ist.
    std::shared_ptr<const WorldOverview> overview;
    CityProgress progress;
    bool has_progress = false;
    bool ended = false;
    std::string end_reason;
    long long end_blocks = 0;
    std::uint64_t cells = 0;  // Summe aller Rasterzellen (stage_list und background)
    std::chrono::steady_clock::time_point started{};
};

class CityState final {
public:
    // Eine Nachricht übernehmen. Leer = angenommen (auch: verworfen, weil fremder Auftrag), sonst snake_case-Code.
    std::string apply(const Json& message);
    // nullptr, solange kein Auftrag gemeldet wurde.
    std::shared_ptr<const CityView> snapshot() const;
    // Zählt jede übernommene Änderung – die GUI holt nur bei neuer Nummer.
    std::uint64_t seq() const;

private:
    mutable std::mutex mutex_;
    std::shared_ptr<const CityView> current_;
    std::uint64_t seq_ = 0;
};

bool is_city_path(std::string_view target);
ApiResponse handle_city(const HttpRequest& request, CityState& state);

}  // namespace swbh::server
