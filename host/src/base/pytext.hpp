// Python-kompatible Textfunktionen (str.isspace, strip, split, re.sub(r"\s+"),
// lower, isalpha).
//
// Warum: Textnormalisierung, Halluzinationsfilter, Geräteauflösung und die
// Konfig-Prüfung der Python-Version arbeiten auf Unicode-Leerraum und
// Unicode-Buchstaben. Eine naive ASCII-Fassung liefert andere Tokens und damit
// andere Filterergebnisse (spec 03 Port-Risiken). Der Leerraum-Satz ist gegen
// Python 3.13.13 gemessen (29 Codepoints), isalpha stammt aus einer erzeugten
// Tabelle (gen_pytext_tables.py, Unicode 15.1.0).
//
// Bewusste Abweichung: py_lower nutzt LCMapStringEx(LOCALE_NAME_INVARIANT,
// LCMAP_LOWERCASE) laut Architektur. Gemessen weicht das bei 206 von 1216
// BMP-Großbuchstaben von Python ab (ẞ, İ, Schluss-Sigma, Cherokee …); deutsche
// und Latin-1-Namen stimmen zu 100 %.
//
// Threads: reentrant. Fehler: keine Ausnahmen außer std::bad_alloc; Eingaben mit
// ungültigem UTF-8 werden wie mit errors="replace" behandelt, Ausgaben sind
// immer gültiges UTF-8.

#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace swbh {

// Python str.isspace() für einen Codepoint (U+0009–000D, U+001C–0020, U+0085,
// U+00A0, U+1680, U+2000–200A, U+2028, U+2029, U+202F, U+205F, U+3000).
bool py_isspace(char32_t cp) noexcept;

// Python str.isalpha() für einen Codepoint (Kategorien Lu, Ll, Lt, Lm, Lo).
bool py_isalpha_cp(char32_t cp) noexcept;

// str.strip() / lstrip() / rstrip() ohne Argument.
std::string py_strip(std::string_view s);
std::string py_lstrip(std::string_view s);
std::string py_rstrip(std::string_view s);

// str.split() ohne Argument: trennt an Leerraum-Läufen, keine leeren Teile.
std::vector<std::string> py_split(std::string_view s);

// re.sub(r"\s+", " ", s): jeder Leerraum-Lauf wird ein Leerzeichen, ohne strip.
std::string py_collapse_ws(std::string_view s);

// str.lower() nach LCMapStringEx (siehe Abweichung oben).
std::string py_lower(std::string_view s);

// str.isalpha(): nicht leer und jeder Codepoint ein Buchstabe.
bool py_isalpha(std::string_view s);

// Nicht leer und nur ASCII-Ziffern '0'–'9'. Bewusst ohne Unicode-Ziffern: Python
// isdigit() nimmt auch "²", int("²") scheitert dann (spec 01 §1.5).
bool ascii_digits(std::string_view s) noexcept;

// Nur A–Z → a–z, sonst unverändert (Schlüssel, Header, Sprachcodes).
std::string ascii_lower(std::string_view s);

}  // namespace swbh
