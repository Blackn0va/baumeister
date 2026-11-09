// Kurze Signaltöne beim Sprechen (Aufnahme beginnt, endet, Fehler) und Wiedergabe einer Aufnahme (Mikrofontest).
//
// Über PlaySound aus dem Speicher (winmm, ohne weitere DLL): asynchron, ein neuer Ton ersetzt den laufenden. Die
// Töne sind leise und kurz (70 ms), damit sie Minecraft nicht übertönen; abschaltbar in den Einstellungen.
//
// Threads: aus jedem Thread aufrufbar (interne Sperre; der Puffer muss bis zum nächsten Aufruf leben).
// Fehler: still – ein fehlender Ton ist kein Grund, das Sprechen zu stören.

#pragma once

#include "voice/audio.hpp"

#include <span>

namespace swbh::voice {

enum class Tone { Start, Stop, Error };

void play_tone(Tone tone);
// 16 kHz mono, -1 … 1. Ersetzt einen laufenden Ton.
void play_pcm(std::span<const float> pcm);
// Beliebige Rate (Piper 22,05 kHz) – Vorlesen der Begleiter-Antwort auf diesem PC.
void play_pcm(const Pcm& pcm);

}  // namespace swbh::voice
