// Strenger DER-Parser für ECDSA-Signaturen (SEQUENCE { INTEGER r, INTEGER s }).
//
// Warum eigener Parser: CryptDecodeObjectEx(X509_ECC_SIGNATURE) nimmt eine
// DER-Signatur mit angehängtem Restbyte an (gemessen). Für eine fail-closed
// Update-Prüfung muss jede Abweichung von kanonischem DER abgelehnt werden.
//
// Regeln: äußere SEQUENCE deckt die Eingabe exakt ab; Längen in Kurzform bzw.
// minimaler Langform; INTEGER positiv, ohne überflüssige führende 0x00, 1..33
// Bytes (mit Vorzeichen-Null), Wert 1..32 Bytes; keine Restbytes. Zusätzlich
// (P-256): r und s ungleich 0 und kleiner als die Gruppenordnung n. Die
// High-S-Form (n−s) bleibt erlaubt – CNG und mbedTLS nehmen sie ebenfalls an
// (gemessen), für losgelöste Dateisignaturen ohne Bedeutung.
//
// Threads: reentrant. Fehler: nullopt.

#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <span>

namespace swbh::win {

// r‖s je 32 Byte big-endian (für BCryptVerifySignature), oder nullopt.
std::optional<std::array<std::uint8_t, 64>> der_to_rs_strict(std::span<const std::uint8_t> der);

}  // namespace swbh::win
