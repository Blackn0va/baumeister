// Locale-freie Zahlenformate im Python-Stil.
//
// Warum: Nutzertexte wie "Pegel {rms:.4f}" oder "Segment {s:.1f}s" würden mit
// iostream und deutscher Locale ein Komma bekommen (spec 03 Port-Risiken). Alle
// Funktionen nutzen std::to_chars, das nie eine Locale liest. Gemessen: MSVC
// to_chars(fixed, p) liefert in 64 von 64 Fällen dieselben Zeichen wie Python
// format(v, ".pf") – inklusive Halbwerten (2.5 → "2", 0.125 → "0.12") und -0.0.
//
// Threads: reentrant. Fehler: keine.

#pragma once

#include <cstdint>
#include <string>

namespace swbh {

// Python format(v, f".{prec}f"). NaN → "nan", ±∞ → "inf"/"-inf". prec in [0, 17].
std::string fmt_fixed(double v, int prec);

// f"{v:.{prec}f}".rstrip("0").rstrip(".") wie in der GUI (spec 05 §6.2):
// 1.0 → "1", 0.8 → "0.8". Ohne Dezimalpunkt (prec 0) wird nichts entfernt.
std::string fmt_trim(double v, int prec = 1);

// Python repr(float): kürzeste eindeutige Darstellung, "1.4", "5.0", "1e-05",
// "1e+16", "0.0001". NaN → "nan", ±∞ → "inf"/"-inf".
std::string py_float_repr(double v);

// Dezimaldarstellung einer Ganzzahl.
std::string fmt_int(std::int64_t v);

}  // namespace swbh
