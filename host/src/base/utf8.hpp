// UTF-8-Grundlagen ohne Windows-Abhängigkeit.
//
// Warum eigene Dekodierung statt MultiByteToWideChar: Alle Kürzungen der
// Python-Version zählen Codepoints (900, 220, 180, 160, 140, 120) und alle
// Netztexte werden mit errors="replace" gelesen. Damit C++ exakt dieselben
// Ersatzzeichen erzeugt, folgt decode_next der Unicode-Empfehlung „maximales
// Teilstück“ (Unicode 15, §3.9, U+FFFD-Substitution) – genau das, was Python
// tut (gemessen: b"\xed\xa0\x80" → drei U+FFFD, b"\xe2\x82" → eines).
//
// Threads: alle Funktionen sind reentrant und ohne gemeinsamen Zustand.
// Fehler: keine Ausnahmen außer std::bad_alloc; ungültige Eingaben werden ersetzt.

#pragma once

#include <cstddef>
#include <string>
#include <string_view>

namespace swbh {

inline constexpr char32_t kReplacementChar = U'�';

// Dekodiert das Zeichen ab u8[pos] und rückt pos dahinter. Ungültige Folgen
// ergeben je maximalem Teilstück genau ein U+FFFD. Vorbedingung: pos < u8.size().
char32_t decode_next(std::string_view u8, std::size_t& pos) noexcept;

// Hängt cp als UTF-8 an. Surrogate und Werte über U+10FFFF werden zu U+FFFD.
void append_utf8(std::string& out, char32_t cp);

// true, wenn u8 wohlgeformtes UTF-8 ist (keine Surrogate, keine Überlangformen).
bool is_valid_utf8(std::string_view u8) noexcept;

// Ersetzt jede ungültige Folge durch U+FFFD (wie bytes.decode(errors="replace")).
std::string sanitize_utf8(std::string_view u8);

// Anzahl der Codepoints nach Ersetzen ungültiger Folgen (Python len()).
std::size_t codepoint_count(std::string_view u8) noexcept;

// Python s[:n]: die ersten n Codepoints. Das Ergebnis ist immer gültiges UTF-8,
// ungültige Folgen der Eingabe zählen als je ein U+FFFD.
std::string truncate_cp(std::string_view u8, std::size_t n);

// UTF-8 ↔ UTF-32 (Ersetzungsregeln wie oben).
std::u32string to_u32(std::string_view u8);
std::string from_u32(std::u32string_view u32);

// UTF-8 ↔ UTF-16 für Windows-APIs. Ungepaarte Surrogate in narrow() werden zu U+FFFD.
std::wstring widen(std::string_view u8);
std::string narrow(std::wstring_view w);

}  // namespace swbh
