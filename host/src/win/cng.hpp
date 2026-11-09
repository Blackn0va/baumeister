// SHA-256 streamend und ECDSA-P-256-Prüfung über CNG (Architektur §12, Probe nat (c)).
//
// Gemessen: CNG liefert für swobsbot.dll denselben SHA-256 wie manifest.sha256 und
// sha256sum; die Plugin-Signatur ist über den SPKI-Import gültig, ein gekipptes
// Byte, fremder Schlüssel oder 20-Byte-Digest ergeben 0xC000A000.
// CNG nimmt die High-S-Variante (n−s) an (wie mbedTLS) – für losgelöste
// Dateisignaturen ohne Bedeutung. Die DER-Strenge liegt in der.hpp, weil
// CryptDecodeObjectEx(X509_ECC_SIGNATURE) Restbytes annimmt (gemessen).
//
// Schlüsselformat: PEM „-----BEGIN PUBLIC KEY-----“ mit genau dem
// SubjectPublicKeyInfo für id-ecPublicKey + prime256v1 und unkomprimiertem
// Punkt (91 Byte DER, festes 26-Byte-Präfix, danach 04‖X‖Y). Jede andere
// Kodierung – andere Kurve, komprimierter Punkt, zusätzliche Felder, privater
// Schlüssel – wird abgelehnt. Das ist bewusst enger als ein allgemeiner
// ASN.1-Leser: Die Signierkette erzeugt immer genau diese Form (Probe „Weg B“).
//
// Die Einmal-Fassung swbh::sha256 liegt in base/hash.hpp.
//
// Threads: Funktionen reentrant; ein Sha256Stream gehört einem Thread.
// Fehler: sha256_file_hex → nullopt bei Lesefehler; ecdsa_p256_verify → false bei
// JEDEM Fehler (fail-closed); Sha256Stream wirft std::runtime_error, wenn CNG versagt,
// und std::logic_error bei Benutzung nach finish().

#pragma once

#include <array>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>

namespace swbh::win {

class Sha256Stream {
public:
    Sha256Stream();
    ~Sha256Stream();
    Sha256Stream(const Sha256Stream&) = delete;
    Sha256Stream& operator=(const Sha256Stream&) = delete;

    void update(std::span<const std::uint8_t> data);
    // Schließt ab; danach ist der Strom verbraucht (weitere Aufrufe werfen).
    std::array<std::uint8_t, 32> finish();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// SHA-256 einer Datei als Hex (klein), in 64-KiB-Blöcken gelesen. nullopt, wenn
// die Datei nicht vollständig lesbar ist – nie eine Summe über einen Teil.
std::optional<std::string> sha256_file_hex(const std::filesystem::path& file);

// Prüft eine DER-kodierte ECDSA-Signatur über einen SHA-256-Digest mit einem
// öffentlichen P-256-Schlüssel im PEM-Format (SubjectPublicKeyInfo).
// false bei: leerem/kaputtem PEM, anderer Kurve als P-256, DER nicht streng
// gültig (siehe der.hpp), ungültiger Signatur.
bool ecdsa_p256_verify(std::string_view spki_pem, std::span<const std::uint8_t, 32> digest,
                       std::span<const std::uint8_t> der_signature);

// true, wenn spki_pem ein im obigen Sinn gültiger öffentlicher P-256-Schlüssel ist,
// den CNG importiert (Punkt auf der Kurve). Für Diagnose und Werkzeuge, damit
// „Schlüssel unbrauchbar“ von „Signatur ungültig“ unterscheidbar ist.
bool p256_public_key_usable(std::string_view spki_pem);

}  // namespace swbh::win
