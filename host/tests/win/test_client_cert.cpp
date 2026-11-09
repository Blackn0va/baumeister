// Eigener Client-Schlüssel der Fernverbindung: anlegen (Software-KSP, eigener Testspeicher statt CurrentUser\My),
// wiederfinden wie nach einem Neustart, nicht exportierbar, Zertifikat nur für Client-Authentifizierung, löschen.
// Den TPM misst probes/winhttp_pin (388 ms je Schlüssel, Tests sollen ihn nicht belasten).
//
// WICHTIG: Der Test legt einen echten, kurzlebigen Schlüssel im Schlüsselspeicher des Benutzers an und löscht ihn am
// Ende wieder (auch bei Fehlschlag, Aufräumer unten).

#include "test_support.hpp"

#include "win/cert_pin.hpp"
#include "win/client_cert.hpp"

#include <windows.h>

#include <bcrypt.h>
#include <ncrypt.h>
#include <wincrypt.h>

#include <array>
#include <string>
#include <vector>

namespace {

swbh::win::ClientCertOptions test_options() {
    swbh::win::ClientCertOptions o;
    o.store = L"BaumeisterHostTest";
    o.name_prefix = "BaumeisterHostTest-";
    o.allow_tpm = false;
    return o;
}

struct Cleanup {
    std::string pin;
    ~Cleanup() {
        if (!pin.empty()) {
            swbh::win::delete_client_cert(pin, test_options());
        }
    }
};

std::vector<std::uint8_t> extension(PCCERT_CONTEXT c, const char* oid) {
    const CERT_EXTENSION* e =
        CertFindExtension(oid, c->pCertInfo->cExtension, c->pCertInfo->rgExtension);
    if (e == nullptr) {
        return {};
    }
    return std::vector<std::uint8_t>(e->Value.pbData, e->Value.pbData + e->Value.cbData);
}

}  // namespace

TEST_CASE("Client-Zertifikat: anlegen, nach einem Neustart wiederfinden, löschen") {
    const auto o = test_options();
    std::string note;
    swbh::win::ClientCert cert = swbh::win::create_client_cert(o, &note);
    Cleanup cleanup{cert.pin()};
    CHECK(note == "TPM abgeschaltet");
    CHECK(swbh::win::parse_pin(cert.pin()) == cert.pin());
    CHECK(cert.name().starts_with("BaumeisterHostTest-"));
    CHECK(cert.name().size() == std::string("BaumeisterHostTest-").size() + 16);
    CHECK_FALSE(cert.tpm());
    CHECK(cert.provider() == "Microsoft Software Key Storage Provider");

    // „Neustart“: frisch aus dem Speicher, nur über den Pin.
    const auto again = swbh::win::find_client_cert(cert.pin(), o);
    REQUIRE(again.has_value());
    CHECK(again->pin() == cert.pin());
    CHECK(again->name() == cert.name());
    // Fremder Namensanfang, anderer Pin: nichts gefunden – nie ein fremdes Zertifikat benutzen.
    auto other = o;
    other.name_prefix = "Fremd-";
    CHECK_FALSE(swbh::win::find_client_cert(cert.pin(), other).has_value());
    CHECK_FALSE(swbh::win::find_client_cert("sha256:" + std::string(64, '0'), o).has_value());
    CHECK_FALSE(swbh::win::find_client_cert("kein-pin", o).has_value());

    CHECK(swbh::win::delete_client_cert(cert.pin(), o));
    CHECK_FALSE(swbh::win::find_client_cert(cert.pin(), o).has_value());
    CHECK_FALSE(swbh::win::delete_client_cert(cert.pin(), o));
}

TEST_CASE("Client-Zertifikat: privater Schlüssel nicht exportierbar, Zertifikat nur Signatur und Client-Auth") {
    const auto o = test_options();
    swbh::win::ClientCert cert = swbh::win::create_client_cert(o);
    Cleanup cleanup{cert.pin()};
    const auto* c = static_cast<PCCERT_CONTEXT>(cert.context());

    HCRYPTPROV_OR_NCRYPT_KEY_HANDLE key = 0;
    DWORD spec = 0;
    BOOL must_free = FALSE;
    REQUIRE(CryptAcquireCertificatePrivateKey(c, CRYPT_ACQUIRE_ONLY_NCRYPT_KEY_FLAG | CRYPT_ACQUIRE_SILENT_FLAG, nullptr,
                                              &key, &spec, &must_free));
    std::array<BYTE, 512> blob{};
    DWORD blob_len = 0;
    const SECURITY_STATUS st = NCryptExportKey(key, 0, BCRYPT_ECCPRIVATE_BLOB, nullptr, blob.data(),
                                               static_cast<DWORD>(blob.size()), &blob_len, NCRYPT_SILENT_FLAG);
    CHECK(st != ERROR_SUCCESS);
    DWORD policy = 0xFFFF;
    DWORD got = 0;
    CHECK(NCryptGetProperty(key, NCRYPT_EXPORT_POLICY_PROPERTY, reinterpret_cast<PBYTE>(&policy), sizeof policy, &got,
                            0) == ERROR_SUCCESS);
    CHECK(policy == 0);
    // Signieren geht (das braucht Schannel im Handshake).
    std::array<BYTE, 32> digest{};
    std::array<BYTE, 128> sig{};
    DWORD sig_len = 0;
    CHECK(NCryptSignHash(key, nullptr, digest.data(), 32, sig.data(), static_cast<DWORD>(sig.size()), &sig_len,
                         NCRYPT_SILENT_FLAG) == ERROR_SUCCESS);
    CHECK(sig_len == 64);  // ECDSA P-256, r || s
    if (must_free) {
        NCryptFreeObject(key);
    }

    CHECK(std::string(c->pCertInfo->SubjectPublicKeyInfo.Algorithm.pszObjId) == szOID_ECC_PUBLIC_KEY);
    CHECK(std::string(c->pCertInfo->SignatureAlgorithm.pszObjId) == szOID_ECDSA_SHA256);
    // Selbst signiert (Aussteller = Inhaber) – Kette genau 1 lang, wie das Relay verlangt.
    CHECK(CertCompareCertificateName(X509_ASN_ENCODING, &c->pCertInfo->Issuer, &c->pCertInfo->Subject));
    BYTE usage = 0;
    CHECK(CertGetIntendedKeyUsage(X509_ASN_ENCODING, c->pCertInfo, &usage, 1));
    CHECK(usage == CERT_DIGITAL_SIGNATURE_KEY_USAGE);
    CHECK_FALSE(extension(c, szOID_ENHANCED_KEY_USAGE).empty());
    DWORD eku_len = 0;
    REQUIRE(CertGetEnhancedKeyUsage(c, CERT_FIND_EXT_ONLY_ENHKEY_USAGE_FLAG, nullptr, &eku_len));
    std::vector<BYTE> eku_buf(eku_len);
    auto* eku = reinterpret_cast<PCERT_ENHKEY_USAGE>(eku_buf.data());
    REQUIRE(CertGetEnhancedKeyUsage(c, CERT_FIND_EXT_ONLY_ENHKEY_USAGE_FLAG, eku, &eku_len));
    REQUIRE(eku->cUsageIdentifier == 1);
    CHECK(std::string(eku->rgpszUsageIdentifier[0]) == szOID_PKIX_KP_CLIENT_AUTH);
    // Gültig ab gestern (nachgehende Uhr des Relays), nicht in der Zukunft beginnend.
    FILETIME now{};
    GetSystemTimeAsFileTime(&now);
    CHECK(CompareFileTime(&c->pCertInfo->NotBefore, &now) < 0);
    CHECK(CompareFileTime(&c->pCertInfo->NotAfter, &now) > 0);
}
