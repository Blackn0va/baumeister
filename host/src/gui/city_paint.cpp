#include "gui/city_paint.hpp"

#include <algorithm>
#include <cmath>

namespace swbh::gui {

namespace {

// Farben nahe am Spiel (Gras, Wasser, Beton), auf dunklem Grund unterscheidbar. Gebäude hell, damit die Stadt als
// Grundriss lesbar ist – im Spiel wären es bunte Fassaden, auf der Karte zählt der Umriss.
constexpr std::uint32_t kColors[10] = {
    0x00000000u,  // 0 leer
    0xffe8e2d4u,  // 1 Gebäude
    0xff6b6f78u,  // 2 Straße
    0xffa9a39au,  // 3 Fußweg/Platz
    0xff3b7dd8u,  // 4 Wasser
    0xff5e9e4au,  // 5 Grün
    0xff2f6b2cu,  // 6 Baum
    0xff8a5a3cu,  // 7 Gleis
    0xffd9a441u,  // 8 Brücke
    0xff7d6fa8u,  // 9 sonstiges
};

constexpr const char* kNames[10] = {"leer",      "Gebäude", "Straße", "Fußweg/Platz", "Wasser",
                                    "Grünfläche", "Baum",    "Gleis",  "Brücke",       "sonstiges"};

std::string thousands(long long n) {
    std::string digits = std::to_string(n < 0 ? -n : n);
    std::string out;
    for (std::size_t i = 0; i < digits.size(); ++i) {
        if (i > 0 && (digits.size() - i) % 3 == 0) {
            out += '.';
        }
        out += digits[i];
    }
    return n < 0 ? "-" + out : out;
}

std::string duration(long long secs) {
    if (secs >= 7200) {
        return std::to_string(secs / 3600) + " h " + std::to_string(secs % 3600 / 60) + " min";
    }
    if (secs >= 120) {
        return std::to_string(secs / 60) + " min";
    }
    return std::to_string(secs) + " s";
}

}  // namespace

std::uint32_t city_color(std::uint8_t category) {
    return kColors[category < 10 ? category : 9];
}

const char* city_category_name(std::uint8_t category) {
    return kNames[category < 10 ? category : 9];
}

std::vector<std::uint32_t> city_pixels(int w, int h, std::span<const std::uint8_t> cells) {
    if (w <= 0 || h <= 0 || cells.size() != static_cast<std::size_t>(w) * static_cast<std::size_t>(h)) {
        return {};
    }
    std::vector<std::uint32_t> out(cells.size());
    for (std::size_t i = 0; i < cells.size(); ++i) {
        out[i] = city_color(cells[i]);
    }
    return out;
}

std::string city_status_text(const CityStatus& s) {
    std::string out = (s.kind == "weltbau" ? "Weltbau " : "Stadt ") + s.place;
    if (s.ended) {
        const std::string what = s.end_reason == "fertig" ? "fertig" : s.end_reason == "fehler" ? "mit Fehler beendet"
                                                                                                 : "abgebrochen";
        return out + " · " + what + " · " + thousands(s.end_blocks) + " Blöcke";
    }
    if (!s.has_progress) {
        return out + " · " + std::to_string(s.stages) + " Etappen · startet …";
    }
    out += " · Etappe " + std::to_string(std::min(s.stages_done, s.stages)) + " von " + std::to_string(s.stages);
    out += " · " + thousands(s.blocks) + " Blöcke";
    out += " · " + thousands(static_cast<long long>(std::llround(s.rate))) + " Blöcke/s";
    if (s.rate > 0.0 && s.seconds >= 30.0 && s.stages_done > 0) {
        const double per_stage = static_cast<double>(s.planned) / s.stages_done;
        const double left_blocks = (s.stages - s.stages_done) * per_stage + std::max(0.0, double(s.planned - s.blocks));
        out += " · noch ca. " + duration(static_cast<long long>(left_blocks / s.rate));
    }
    if (s.paused) {
        out += " · PAUSE";
    }
    return out;
}

std::string world_status_text(const WorldStatus& w) {
    std::string out = "Weltbau: " + std::to_string(w.done) + (w.done == 1 ? " Kachel" : " Kacheln") + " fertig";
    if (w.failed > 0) {
        out += " · " + std::to_string(w.failed) + " gescheitert";
    }
    out += " · " + std::to_string(w.queued) + " wartend";
    if (w.has_current) {
        const std::string what = w.phase == "karte"    ? "lädt die Karte"
                                 : w.phase == "planen" ? "plant"
                                 : w.phase == "bereit" ? "startet"
                                 : w.phase == "bauen"  ? "baut"
                                                       : "wartet";
        out += " · Kachel " + std::to_string(w.current_tx) + "/" + std::to_string(w.current_tz) + " " + what;
    }
    out += w.auto_on ? " · Automatik an" : " · Automatik aus";
    out += " · " + std::to_string(w.players) + " Spieler in der Erdwelt";
    return out;
}

}  // namespace swbh::gui
