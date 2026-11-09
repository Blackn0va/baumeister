// Stadtansicht ohne Qt (testbar): Farbe je Kategorie, Bildpunkte einer Etappe und die Kopfzeile.
//
// Kategorien wie die Schnittstelle /baumeister/v1/stadt (server/city_api.hpp, im Plugin CityRaster.java): 0 leer,
// 1 Gebäude, 2 Straße, 3 Fußweg/Platz, 4 Wasser, 5 Grün, 6 Baum, 7 Gleis, 8 Brücke, 9 sonstiges.
//
// Threads: rein. Fehler: keine (unbekannte Kategorie → „sonstiges“).

#pragma once

#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace swbh::gui {

// 0xAARRGGBB; Kategorie 0 (leer) ist durchsichtig.
std::uint32_t city_color(std::uint8_t category);

// Ein Bildpunkt je Rasterzelle, zeilenweise wie die Zellen. Leere Liste, wenn cells nicht w*h lang ist.
std::vector<std::uint32_t> city_pixels(int w, int h, std::span<const std::uint8_t> cells);

// Deutscher Name je Kategorie (Legende).
const char* city_category_name(std::uint8_t category);

struct CityStatus {
    std::string kind;   // "stadt" | "weltbau"
    std::string place;
    int stages = 0;
    int stages_done = 0;
    long long blocks = 0;
    long long planned = 0;
    double rate = 0.0;
    bool paused = false;
    bool has_progress = false;
    bool ended = false;
    std::string end_reason;
    long long end_blocks = 0;
    double seconds = 0.0;  // seit dem Start
};

// Kopfzeile, z. B. „Stadt Bad Kreuznach · Etappe 3 von 5 · 82.749 Blöcke · 31.686 Blöcke/s · noch ca. 6 s“.
// Restzeit wie im Plugin (CityJob.progress): bisheriger Schnitt je Etappe × offene Etappen, erst nach 30 s.
std::string city_status_text(const CityStatus& s);

struct WorldStatus {
    int done = 0;
    int failed = 0;
    int queued = 0;
    int players = 0;
    bool auto_on = false;
    std::string phase;  // wartet | karte | planen | bereit | bauen
    bool has_current = false;
    int current_tx = 0;
    int current_tz = 0;
};

// Weltbau-Zeile, z. B. „Weltbau: 3 Kacheln fertig · 2 wartend · Kachel 366/6584 baut · Automatik an · 1 Spieler in
// der Erdwelt“.
std::string world_status_text(const WorldStatus& w);

}  // namespace swbh::gui
