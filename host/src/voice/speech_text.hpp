// Text des Begleiters für die Sprachausgabe aufbereiten (Piper/espeak-ng und Windows-Stimme).
//
// Warum: Wunsch 2026-11-04. Gemessen mit
// probes/piper_aussprache_probe.py (Phoneme aus piper --debug, Thorsten medium): espeak-ng liest Minecraft-Englisch
// deutsch („Creeper“ → kɾˈeːpɜ, „Redstone“ → reːtstˈoːnə), „6:00 Uhr“ als „sechs Uhr null null Uhr“, „17*23 = 391“
// ohne „mal“, „5 - 3“ als „fünf drei“, „3.5“ als „drei Punkt fünf“, ein Emoji als „grinsendes Gesicht“ – und „z.B.“
// oder „ca.“ beenden den Satz mitten im Wort. Die Wortliste unten ist jeweils die gemessene bessere Umschreibung.
//
// Nur für das Ohr: Chat und Log zeigen weiter den Originaltext.
//
// Threads: zustandslos. Fehler: keine – unbekannte Zeichen bleiben stehen.

#pragma once

#include <string>
#include <string_view>

namespace swbh::voice {

std::string for_speech(std::string_view text);

}  // namespace swbh::voice
