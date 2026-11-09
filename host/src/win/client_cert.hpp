// Eigener TLS-Client-Schlüssel der App für die Fernverbindung (Kanal v1, docs/kanal-v1.md): ECDSA P-256, NICHT
// exportierbar, im TPM („Microsoft Platform Crypto Provider“), sonst im Software-Schlüsselspeicher; dazu ein selbst
// signiertes Zertifikat in CurrentUser\My mit neutralem Namen „BaumeisterHost-<16 Hex>“. Das Relay kennt die App nur
// über den Pin dieses Zertifikats (win/cert_pin.hpp).
//
// Warum so (gemessen mit probes/winhttp_pin, 2026-11-08, Windows 11 26300, TPM 2.0):
//  - Schannel findet den Schlüssel nur über CRYPT_KEY_PROV_INFO am Zertifikat im Speicher. Ein Zertifikat ohne den
//    Verweis scheitert im Handshake mit 12186 (ERROR_WINHTTP_CLIENT_CERT_NO_ACCESS_PRIVATE_KEY) – auch aus einem
//    frischen Prozess (= nach dem Neustart der App) gefunden und benutzt wird er nur so.
//  - TPM: Schlüssel 388 ms, Zertifikat 180 ms, je Handshake ~125 ms Signatur; Export abgelehnt (0x8009000A).
//    Software-KSP: 8 ms, Export abgelehnt (0x80090029). Beide funktionieren mit TLS 1.3 und 1.2 gegen JSSE.
//  - Nicht exportierbar heißt im TPM: Der Schlüssel verlässt den Chip nie; ein Schadprogramm im selben Windows-Konto
//    kann ihn benutzen, solange es läuft, aber nicht kopieren (ASVS V6.4.1/V6.4.2 sinngemäß). Im Software-
//    Schlüsselspeicher (Rückfall ohne TPM) ist das Export-Verbot dagegen KEINE kryptografische Sperre: DPAPI schützt
//    die Datei nur gegen andere Konten – Schadcode im selben Konto kann sie entschlüsseln und den Schlüssel kopieren
//    (zweite Sicherheitsprüfung 2026-11-08, N5; vorher stand hier, er sei auch dort nicht kopierbar). Wer das
//    Export-Verbot zurücknimmt, macht den Schlüssel mit jeder Datei-Kopie des Profils übertragbar.
//  - Der Name verrät weder Rechner noch Konto: Das Zertifikat geht im Handshake an den Server, bei TLS 1.2 sogar
//    unverschlüsselt (ASVS V8.3.4).
//  - find_client_cert sucht nur Zertifikate mit dem eigenen Namensanfang und prüft, dass der Schlüssel still greifbar
//    ist – ein fremdes Zertifikat im Speicher wird nie benutzt, und es öffnet sich kein Dialog.
//
// Threads: alle Funktionen reentrant (Speicher und Schlüssel je Aufruf). ClientCert ist unveränderlich und darf von
// mehreren Threads gelesen werden.
// Fehler: create_client_cert wirft ClientCertError mit deutschem Text; find_client_cert liefert nullopt.

#pragma once

#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>

namespace swbh::win {

struct ClientCertError : std::runtime_error {
    using std::runtime_error::runtime_error;
};

struct ClientCertOptions {
    // Systemspeicher des Benutzers (CurrentUser\<store>). Tests nehmen einen eigenen Speicher, nie „My“.
    std::wstring store = L"My";
    // Namensanfang des CN. Nur Zertifikate mit diesem Anfang werden gefunden oder gelöscht.
    std::string name_prefix = "BaumeisterHost-";
    // TPM zuerst versuchen. Tests schalten es ab (schnell, kein TPM-Zustand).
    bool allow_tpm = true;
    int valid_days = 3650;
};

class ClientCert final {
public:
    ClientCert(ClientCert&& other) noexcept;
    ClientCert& operator=(ClientCert&& other) noexcept;
    ClientCert(const ClientCert&) = delete;
    ClientCert& operator=(const ClientCert&) = delete;
    ~ClientCert();

    // PCCERT_CONTEXT (bleibt gültig, solange dieses Objekt lebt).
    const void* context() const { return context_; }
    const std::string& pin() const { return pin_; }
    const std::string& name() const { return name_; }
    const std::string& provider() const { return provider_; }
    bool tpm() const { return tpm_; }

private:
    friend std::optional<ClientCert> find_client_cert(std::string_view, const ClientCertOptions&);
    friend ClientCert create_client_cert(const ClientCertOptions&, std::string*);
    ClientCert(const void* context, std::string pin, std::string name, std::string provider, bool tpm);

    const void* context_ = nullptr;
    std::string pin_;
    std::string name_;
    std::string provider_;
    bool tpm_ = false;
};

// Zertifikat mit genau diesem Pin, eigenem Namensanfang und still greifbarem Schlüssel. nullopt sonst.
std::optional<ClientCert> find_client_cert(std::string_view pin, const ClientCertOptions& options = {});

// Neuer Schlüssel samt Zertifikat. tpm_note (optional): warum kein TPM benutzt wurde, leer bei TPM.
// Wirft ClientCertError; ein halb angelegter Schlüssel wird vorher wieder gelöscht.
ClientCert create_client_cert(const ClientCertOptions& options = {}, std::string* tpm_note = nullptr);

// Entfernt das Zertifikat mit diesem Pin (nur mit eigenem Namensanfang) und seinen Schlüssel. true = etwas entfernt.
bool delete_client_cert(std::string_view pin, const ClientCertOptions& options = {});

}  // namespace swbh::win
