#include "win/client_cert.hpp"

#include "base/encoding.hpp"
#include "base/log.hpp"
#include "base/utf8.hpp"
#include "win/cert_pin.hpp"

#include <windows.h>

#include <bcrypt.h>
#include <ncrypt.h>
#include <wincrypt.h>

#include <array>
#include <cstdint>
#include <cstdio>
#include <span>
#include <utility>
#include <vector>

namespace swbh::win {

namespace {

std::string hex_status(SECURITY_STATUS st) {
    char buf[16];
    std::snprintf(buf, sizeof buf, "0x%08lX", static_cast<unsigned long>(st));
    return buf;
}

class StoreHandle final {
public:
    explicit StoreHandle(HCERTSTORE h) : h_(h) {}
    ~StoreHandle() {
        if (h_ != nullptr) {
            CertCloseStore(h_, 0);
        }
    }
    StoreHandle(const StoreHandle&) = delete;
    StoreHandle& operator=(const StoreHandle&) = delete;
    HCERTSTORE get() const { return h_; }

private:
    HCERTSTORE h_;
};

class NcryptHandle final {
public:
    NcryptHandle() = default;
    ~NcryptHandle() { reset(); }
    NcryptHandle(const NcryptHandle&) = delete;
    NcryptHandle& operator=(const NcryptHandle&) = delete;
    NCRYPT_HANDLE* out() { return &h_; }
    NCRYPT_HANDLE get() const { return h_; }
    // NCryptDeleteKey gibt das Handle selbst frei.
    void release() { h_ = 0; }
    void reset() {
        if (h_ != 0) {
            NCryptFreeObject(h_);
            h_ = 0;
        }
    }

private:
    NCRYPT_HANDLE h_ = 0;
};

HCERTSTORE open_store(const ClientCertOptions& o, bool create) {
    DWORD flags = CERT_SYSTEM_STORE_CURRENT_USER;
    if (!create) {
        flags |= CERT_STORE_OPEN_EXISTING_FLAG;
    }
    return CertOpenStore(CERT_STORE_PROV_SYSTEM_W, 0, 0, flags, o.store.c_str());
}

std::string common_name(PCCERT_CONTEXT c) {
    wchar_t buf[256]{};
    const DWORD n = CertGetNameStringW(c, CERT_NAME_ATTR_TYPE, 0, const_cast<char*>(szOID_COMMON_NAME), buf,
                                       static_cast<DWORD>(std::size(buf)));
    return n > 1 ? narrow(std::wstring_view(buf, n - 1)) : std::string();
}

std::string pin_of(PCCERT_CONTEXT c) {
    return pin_of_der(std::span<const std::uint8_t>(c->pbCertEncoded, c->cbCertEncoded));
}

// Name des Schlüsselanbieters über das Zertifikat (CRYPT_KEY_PROV_INFO) – ohne den Schlüssel zu öffnen.
std::optional<std::pair<std::wstring, std::wstring>> key_location(PCCERT_CONTEXT c) {
    DWORD size = 0;
    if (!CertGetCertificateContextProperty(c, CERT_KEY_PROV_INFO_PROP_ID, nullptr, &size) || size == 0) {
        return std::nullopt;
    }
    std::vector<std::uint8_t> buf(size);
    if (!CertGetCertificateContextProperty(c, CERT_KEY_PROV_INFO_PROP_ID, buf.data(), &size)) {
        return std::nullopt;
    }
    const auto* info = reinterpret_cast<const CRYPT_KEY_PROV_INFO*>(buf.data());
    if (info->pwszContainerName == nullptr || info->pwszProvName == nullptr) {
        return std::nullopt;
    }
    return std::make_pair(std::wstring(info->pwszContainerName), std::wstring(info->pwszProvName));
}

// Schlüssel still greifbar (so wie Schannel ihn im Handshake öffnet)? Keine Dialoge (CRYPT_ACQUIRE_SILENT_FLAG).
bool key_usable(PCCERT_CONTEXT c) {
    HCRYPTPROV_OR_NCRYPT_KEY_HANDLE key = 0;
    DWORD spec = 0;
    BOOL must_free = FALSE;
    if (!CryptAcquireCertificatePrivateKey(c, CRYPT_ACQUIRE_ONLY_NCRYPT_KEY_FLAG | CRYPT_ACQUIRE_SILENT_FLAG, nullptr,
                                           &key, &spec, &must_free)) {
        return false;
    }
    if (must_free) {
        NCryptFreeObject(key);
    }
    return true;
}

std::vector<BYTE> encode_object(LPCSTR type, const void* value) {
    DWORD n = 0;
    if (!CryptEncodeObjectEx(X509_ASN_ENCODING, type, value, 0, nullptr, nullptr, &n)) {
        throw ClientCertError("Zertifikatserweiterung nicht kodierbar (Fehler " + std::to_string(GetLastError()) + ")");
    }
    std::vector<BYTE> out(n);
    if (!CryptEncodeObjectEx(X509_ASN_ENCODING, type, value, 0, nullptr, out.data(), &n)) {
        throw ClientCertError("Zertifikatserweiterung nicht kodierbar (Fehler " + std::to_string(GetLastError()) + ")");
    }
    out.resize(n);
    return out;
}

SYSTEMTIME days_from_now(long long days) {
    FILETIME now{};
    GetSystemTimeAsFileTime(&now);
    ULARGE_INTEGER u{};
    u.LowPart = now.dwLowDateTime;
    u.HighPart = now.dwHighDateTime;
    u.QuadPart = static_cast<ULONGLONG>(static_cast<long long>(u.QuadPart) + days * 24LL * 3600LL * 10000000LL);
    const FILETIME ft{u.LowPart, u.HighPart};
    SYSTEMTIME st{};
    FileTimeToSystemTime(&ft, &st);
    return st;
}

std::string random_name(const ClientCertOptions& o) {
    std::array<std::uint8_t, 8> bytes{};
    if (BCryptGenRandom(nullptr, bytes.data(), static_cast<ULONG>(bytes.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG) !=
        0) {
        throw ClientCertError("Zufallszahl für den Schlüsselnamen nicht verfügbar");
    }
    return o.name_prefix + to_hex(bytes);
}

// Legt einen nicht exportierbaren ECDSA-P-256-Schlüssel an. nullopt + Grund, wenn der Anbieter nicht will.
bool create_key(const wchar_t* provider, const std::wstring& name, NcryptHandle& key, std::string& why) {
    NcryptHandle prov;
    SECURITY_STATUS st = NCryptOpenStorageProvider(reinterpret_cast<NCRYPT_PROV_HANDLE*>(prov.out()), provider, 0);
    if (st != ERROR_SUCCESS) {
        why = "Anbieter nicht verfügbar (" + hex_status(st) + ")";
        return false;
    }
    st = NCryptCreatePersistedKey(static_cast<NCRYPT_PROV_HANDLE>(prov.get()),
                                  reinterpret_cast<NCRYPT_KEY_HANDLE*>(key.out()), NCRYPT_ECDSA_P256_ALGORITHM,
                                  name.c_str(), 0, 0);
    if (st != ERROR_SUCCESS) {
        why = "Schlüssel nicht anlegbar (" + hex_status(st) + ")";
        return false;
    }
    // Ausdrücklich 0 = nicht exportierbar (auch wenn eine Gruppenrichtlinie etwas anderes vorgibt).
    DWORD policy = 0;
    st = NCryptSetProperty(key.get(), NCRYPT_EXPORT_POLICY_PROPERTY, reinterpret_cast<PBYTE>(&policy), sizeof policy,
                           NCRYPT_PERSIST_FLAG);
    if (st == ERROR_SUCCESS) {
        DWORD usage = NCRYPT_ALLOW_SIGNING_FLAG;
        st = NCryptSetProperty(key.get(), NCRYPT_KEY_USAGE_PROPERTY, reinterpret_cast<PBYTE>(&usage), sizeof usage,
                               NCRYPT_PERSIST_FLAG);
    }
    if (st == ERROR_SUCCESS) {
        st = NCryptFinalizeKey(static_cast<NCRYPT_KEY_HANDLE>(key.get()), NCRYPT_SILENT_FLAG);
    }
    if (st != ERROR_SUCCESS) {
        why = "Schlüssel nicht fertigstellbar (" + hex_status(st) + ")";
        if (NCryptDeleteKey(static_cast<NCRYPT_KEY_HANDLE>(key.get()), 0) == ERROR_SUCCESS) {
            key.release();
        }
        return false;
    }
    // Gegenprobe statt Vertrauen: Ließe sich der private Teil doch exportieren, wird der Schlüssel verworfen
    // (fail-closed – lieber kein Schlüssel als ein kopierbarer).
    std::array<BYTE, 512> blob{};
    DWORD blob_len = 0;
    st = NCryptExportKey(static_cast<NCRYPT_KEY_HANDLE>(key.get()), 0, BCRYPT_ECCPRIVATE_BLOB, nullptr, blob.data(),
                         static_cast<DWORD>(blob.size()), &blob_len, NCRYPT_SILENT_FLAG);
    SecureZeroMemory(blob.data(), blob.size());
    if (st == ERROR_SUCCESS) {
        why = "Schlüssel wäre exportierbar – verworfen";
        if (NCryptDeleteKey(static_cast<NCRYPT_KEY_HANDLE>(key.get()), 0) == ERROR_SUCCESS) {
            key.release();
        }
        return false;
    }
    return true;
}

void delete_key(const std::wstring& provider, const std::wstring& name) {
    NcryptHandle prov;
    if (NCryptOpenStorageProvider(reinterpret_cast<NCRYPT_PROV_HANDLE*>(prov.out()), provider.c_str(), 0) !=
        ERROR_SUCCESS) {
        return;
    }
    NcryptHandle key;
    if (NCryptOpenKey(static_cast<NCRYPT_PROV_HANDLE>(prov.get()), reinterpret_cast<NCRYPT_KEY_HANDLE*>(key.out()),
                      name.c_str(), 0, NCRYPT_SILENT_FLAG) != ERROR_SUCCESS) {
        return;
    }
    if (NCryptDeleteKey(static_cast<NCRYPT_KEY_HANDLE>(key.get()), 0) == ERROR_SUCCESS) {
        key.release();
    }
}

}  // namespace

ClientCert::ClientCert(const void* context, std::string pin, std::string name, std::string provider, bool tpm)
    : context_(context), pin_(std::move(pin)), name_(std::move(name)), provider_(std::move(provider)), tpm_(tpm) {}

ClientCert::ClientCert(ClientCert&& other) noexcept
    : context_(std::exchange(other.context_, nullptr)),
      pin_(std::move(other.pin_)),
      name_(std::move(other.name_)),
      provider_(std::move(other.provider_)),
      tpm_(other.tpm_) {}

ClientCert& ClientCert::operator=(ClientCert&& other) noexcept {
    if (this != &other) {
        if (context_ != nullptr) {
            CertFreeCertificateContext(static_cast<PCCERT_CONTEXT>(context_));
        }
        context_ = std::exchange(other.context_, nullptr);
        pin_ = std::move(other.pin_);
        name_ = std::move(other.name_);
        provider_ = std::move(other.provider_);
        tpm_ = other.tpm_;
    }
    return *this;
}

ClientCert::~ClientCert() {
    if (context_ != nullptr) {
        CertFreeCertificateContext(static_cast<PCCERT_CONTEXT>(context_));
    }
}

std::optional<ClientCert> find_client_cert(std::string_view pin, const ClientCertOptions& o) {
    const std::optional<std::string> wanted = parse_pin(pin);
    if (!wanted) {
        return std::nullopt;
    }
    StoreHandle store(open_store(o, false));
    if (store.get() == nullptr) {
        return std::nullopt;
    }
    PCCERT_CONTEXT c = nullptr;
    while ((c = CertEnumCertificatesInStore(store.get(), c)) != nullptr) {
        if (pin_of(c) != *wanted) {
            continue;
        }
        const std::string cn = common_name(c);
        const auto where = key_location(c);
        if (!cn.starts_with(o.name_prefix) || !where || !key_usable(c)) {
            continue;
        }
        const bool tpm = where->second == MS_PLATFORM_CRYPTO_PROVIDER;
        return ClientCert(CertDuplicateCertificateContext(c), *wanted, cn, narrow(where->second), tpm);
    }
    return std::nullopt;
}

ClientCert create_client_cert(const ClientCertOptions& o, std::string* tpm_note) {
    const std::string name = random_name(o);
    const std::wstring wname = widen(name);
    NcryptHandle key;
    const wchar_t* provider = nullptr;
    bool tpm = false;
    std::string why;
    if (o.allow_tpm && create_key(MS_PLATFORM_CRYPTO_PROVIDER, wname, key, why)) {
        provider = MS_PLATFORM_CRYPTO_PROVIDER;
        tpm = true;
    } else {
        if (tpm_note != nullptr) {
            *tpm_note = o.allow_tpm ? "TPM: " + why : "TPM abgeschaltet";
        }
        key.reset();
        std::string soft_why;
        if (!create_key(MS_KEY_STORAGE_PROVIDER, wname, key, soft_why)) {
            throw ClientCertError("Eigener Schlüssel nicht anlegbar – " + soft_why);
        }
        provider = MS_KEY_STORAGE_PROVIDER;
    }
    // Ab hier: bei jedem Fehler Schlüssel und Zertifikat wieder entfernen.
    PCCERT_CONTEXT stored = nullptr;
    try {
        const std::wstring x500 = L"CN=" + wname;
        DWORD name_len = 0;
        if (!CertStrToNameW(X509_ASN_ENCODING, x500.c_str(), CERT_X500_NAME_STR, nullptr, nullptr, &name_len,
                            nullptr)) {
            throw ClientCertError("Zertifikatsname nicht kodierbar");
        }
        std::vector<BYTE> name_blob(name_len);
        if (!CertStrToNameW(X509_ASN_ENCODING, x500.c_str(), CERT_X500_NAME_STR, nullptr, name_blob.data(), &name_len,
                            nullptr)) {
            throw ClientCertError("Zertifikatsname nicht kodierbar");
        }
        CERT_NAME_BLOB subject{name_len, name_blob.data()};

        // Nur Signieren, nur Client-Authentifizierung, keine CA – ein gestohlenes Zertifikat taugt zu nichts anderem.
        BYTE usage_byte = CERT_DIGITAL_SIGNATURE_KEY_USAGE;
        CRYPT_BIT_BLOB usage{1, &usage_byte, 7};
        std::vector<BYTE> usage_enc = encode_object(X509_KEY_USAGE, &usage);
        LPSTR eku_oid = const_cast<LPSTR>(szOID_PKIX_KP_CLIENT_AUTH);
        CERT_ENHKEY_USAGE eku{1, &eku_oid};
        std::vector<BYTE> eku_enc = encode_object(X509_ENHANCED_KEY_USAGE, &eku);
        CERT_BASIC_CONSTRAINTS2_INFO basic{FALSE, FALSE, 0};
        std::vector<BYTE> basic_enc = encode_object(X509_BASIC_CONSTRAINTS2, &basic);
        std::array<CERT_EXTENSION, 3> ext{{
            {const_cast<LPSTR>(szOID_KEY_USAGE), TRUE, {static_cast<DWORD>(usage_enc.size()), usage_enc.data()}},
            {const_cast<LPSTR>(szOID_ENHANCED_KEY_USAGE), FALSE, {static_cast<DWORD>(eku_enc.size()), eku_enc.data()}},
            {const_cast<LPSTR>(szOID_BASIC_CONSTRAINTS2), TRUE, {static_cast<DWORD>(basic_enc.size()), basic_enc.data()}},
        }};
        CERT_EXTENSIONS exts{static_cast<DWORD>(ext.size()), ext.data()};

        // CRYPT_KEY_PROV_INFO: Ohne diesen Verweis findet Schannel den Schlüssel nicht (12186, gemessen).
        CRYPT_KEY_PROV_INFO kpi{};
        kpi.pwszContainerName = const_cast<LPWSTR>(wname.c_str());
        kpi.pwszProvName = const_cast<LPWSTR>(provider);
        kpi.dwProvType = 0;  // CNG
        kpi.dwFlags = 0;     // Benutzerschlüssel
        kpi.dwKeySpec = 0;
        CRYPT_ALGORITHM_IDENTIFIER alg{const_cast<LPSTR>(szOID_ECDSA_SHA256), {0, nullptr}};
        // Ab gestern: Eine nachgehende Uhr des Relays soll das neue Zertifikat nicht als „noch nicht gültig“ sehen.
        SYSTEMTIME from = days_from_now(-1);
        SYSTEMTIME until = days_from_now(o.valid_days);
        PCCERT_CONTEXT cert = CertCreateSelfSignCertificate(static_cast<NCRYPT_KEY_HANDLE>(key.get()), &subject, 0, &kpi,
                                                            &alg, &from, &until, &exts);
        if (cert == nullptr) {
            throw ClientCertError("Zertifikat nicht erzeugbar (Fehler " + std::to_string(GetLastError()) + ")");
        }
        StoreHandle store(open_store(o, true));
        BOOL added = FALSE;
        if (store.get() != nullptr) {
            added = CertAddCertificateContextToStore(store.get(), cert, CERT_STORE_ADD_NEW, &stored);
        }
        const DWORD add_error = added ? 0 : GetLastError();
        CertFreeCertificateContext(cert);
        if (!added || stored == nullptr) {
            throw ClientCertError("Zertifikat nicht im Speicher des Benutzers ablegbar (Fehler " +
                                  std::to_string(add_error) + ")");
        }
        const std::wstring friendly = L"Baumeister-Host Fernverbindung";
        CRYPT_DATA_BLOB fb{static_cast<DWORD>((friendly.size() + 1) * sizeof(wchar_t)),
                           reinterpret_cast<BYTE*>(const_cast<wchar_t*>(friendly.c_str()))};
        CertSetCertificateContextProperty(stored, CERT_FRIENDLY_NAME_PROP_ID, 0, &fb);
        if (!key_location(stored) || !key_usable(stored)) {
            throw ClientCertError("Neues Zertifikat findet seinen Schlüssel nicht");
        }
    } catch (...) {
        if (stored != nullptr) {
            CertDeleteCertificateFromStore(stored);  // gibt stored frei
        }
        if (NCryptDeleteKey(static_cast<NCRYPT_KEY_HANDLE>(key.get()), 0) == ERROR_SUCCESS) {
            key.release();
        }
        throw;
    }
    const std::string pin = pin_of(stored);
    log::info(std::string("Fernverbindung: eigener Schlüssel angelegt (") + (tpm ? "TPM" : "Software") + ", " +
              narrow(provider) + ")");
    return ClientCert(stored, pin, name, narrow(provider), tpm);
}

bool delete_client_cert(std::string_view pin, const ClientCertOptions& o) {
    const std::optional<std::string> wanted = parse_pin(pin);
    if (!wanted) {
        return false;
    }
    StoreHandle store(open_store(o, false));
    if (store.get() == nullptr) {
        return false;
    }
    bool removed = false;
    PCCERT_CONTEXT c = nullptr;
    while ((c = CertEnumCertificatesInStore(store.get(), c)) != nullptr) {
        if (pin_of(c) != *wanted || !common_name(c).starts_with(o.name_prefix)) {
            continue;
        }
        if (const auto where = key_location(c)) {
            delete_key(where->second, where->first);
        }
        PCCERT_CONTEXT doomed = CertDuplicateCertificateContext(c);
        CertDeleteCertificateFromStore(doomed);  // gibt doomed frei; c bleibt für die Aufzählung gültig
        removed = true;
    }
    return removed;
}

}  // namespace swbh::win
