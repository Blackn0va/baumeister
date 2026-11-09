// Tonlage des Begleiters („tonlage“ in den Einstellungen) als Faktor für die Tonhöhe. Eigener Header ohne
// Abhängigkeiten: Die Einstellungen (swbh_gui_core) prüfen den Wert, ohne die Ton-Bibliothek zu linken.
//
// Wunsch 2026-11-06. Gemessen mit Piper Thorsten medium
// (probes/piper_cartoon_probe.py, Satz von ~5 s): Grundton 108 Hz bei 1,0 → 128 Hz bei 1,2 → 143 Hz bei 1,3 → 146 Hz bei
// 1,4; Dauer 5,4 / 5,0 / 4,7 / 5,0 s. Über 1,4 bringt kaum mehr Höhe – darum ist „extra“ die Grenze.
//
// Threads: zustandslos. Fehler: unbekannte Tonlage → 0 (der Aufrufer klemmt auf die Vorgabe).

#pragma once

#include <string_view>

namespace swbh::voice {

inline constexpr std::string_view kDefaultVoiceStyle = "cartoon";

// normal 1,0 · fröhlich 1,15 · cartoon 1,3 · extra 1,4 · sonst 0.
inline double voice_style_pitch(std::string_view style) {
    if (style == "normal") {
        return 1.0;
    }
    if (style == "fröhlich") {
        return 1.15;
    }
    if (style == "cartoon") {
        return 1.3;
    }
    if (style == "extra") {
        return 1.4;
    }
    return 0.0;
}

}  // namespace swbh::voice
