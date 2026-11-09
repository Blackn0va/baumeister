// Zertifikats-Pins im Format von Kanal v1 (docs/kanal-v1.md, Abschnitt 1): "sha256:" + 64 Hex-Zeichen klein über die
// DER-Bytes des BLATTZERTIFIKATS – nicht nur über den öffentlichen Schlüssel.
//
// Warum über das ganze Zertifikat: Das Relay prüft genau so (Java, chain[0].getEncoded()), und die Testvektoren legen
// es fest. Ein Pin nur über den Schlüssel bliebe bei einem neu ausgestellten Zertifikat gleich – hier soll jeder
// Wechsel bewusst eingetragen werden. Ein exakter Pin ist strenger als jede Namens- oder CA-Prüfung: Er lässt genau
// ein Zertifikat zu (ASVS V9.2.1 sinngemäß, OWASP A02/A07).
//
// Threads: reentrant. Fehler: parse_pin liefert nullopt statt Ausnahme (ein halber Pin darf nie gelten).

#pragma once

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::win {

inline constexpr std::string_view kPinPrefix = "sha256:";

// Pin über DER-Bytes.
std::string pin_of_der(std::span<const std::uint8_t> der);

// Eingabe aus den Einstellungen: Rand-Leerraum weg, Präfix und Hex in beliebiger Schreibung, Ergebnis klein.
// nullopt bei allem anderen (falsche Länge, Nicht-Hex, anderes Verfahren, Leerzeichen im Wert).
std::optional<std::string> parse_pin(std::string_view text);

// true, wenn pin in der Liste steht. Vergleich über alle Einträge ohne frühes Ende (die Laufzeit verrät nicht, ab
// welchem Zeichen ein Pin abweicht – Pins sind öffentlich, aber die Regel kostet nichts).
bool pin_listed(std::string_view pin, const std::vector<std::string>& pins);

}  // namespace swbh::win
