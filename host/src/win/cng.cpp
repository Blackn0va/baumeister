// SHA-256 und ECDSA-P-256 über CNG (Probe nat (c)).

#include "win/cng.hpp"

#include "base/encoding.hpp"
#include "base/errors.hpp"
#include "win/der.hpp"

#include <windows.h>
#include <bcrypt.h>

#include <algorithm>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <vector>

namespace swbh::win {
namespace {

constexpr NTSTATUS kStatusSuccess = 0;

// SubjectPublicKeyInfo für id-ecPublicKey (1.2.840.10045.2.1) mit prime256v1
// (1.2.840.10045.3.1.7) und BIT STRING mit unkomprimiertem Punkt (0x04):
//   30 59  30 13  06 07 2A8648CE3D0201  06 08 2A8648CE3D030107  03 42 00 04 ‖ X ‖ Y
constexpr std::uint8_t kSpkiPrefix[] = {0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D,
                                        0x02, 0x01, 0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01,
                                        0x07, 0x03, 0x42, 0x00, 0x04};
constexpr std::size_t kSpkiSize = sizeof(kSpkiPrefix) + 64;

constexpr std::string_view kPemBegin = "-----BEGIN PUBLIC KEY-----";
constexpr std::string_view kPemEnd = "-----END PUBLIC KEY-----";

bool is_pem_space(char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; }

// Strenges PEM: optional Leerraum, genau ein PUBLIC-KEY-Block, dazwischen nur
// Base64 und Zeilenumbrüche, danach nur Leerraum. Alles andere (zweiter Block,
// Text davor, privater Schlüssel) → nullopt.
std::optional<std::vector<std::uint8_t>> pem_public_key_der(std::string_view pem) {
    std::size_t begin = 0;
    while (begin < pem.size() && is_pem_space(pem[begin])) {
        ++begin;
    }
    std::size_t end = pem.size();
    while (end > begin && is_pem_space(pem[end - 1])) {
        --end;
    }
    std::string_view body = pem.substr(begin, end - begin);
    if (body.size() < kPemBegin.size() + kPemEnd.size() || body.substr(0, kPemBegin.size()) != kPemBegin ||
        body.substr(body.size() - kPemEnd.size()) != kPemEnd) {
        return std::nullopt;
    }
    body = body.substr(kPemBegin.size(), body.size() - kPemBegin.size() - kPemEnd.size());
    std::string base64;
    base64.reserve(body.size());
    for (const char c : body) {
        if (c == '\r' || c == '\n') {
            continue;
        }
        // Innerhalb des Blocks nur Base64-Zeichen; ein '-' hieße zweiter Block.
        base64.push_back(c);
    }
    if (base64.empty()) {
        return std::nullopt;
    }
    return base64_decode(base64);
}

// X‖Y aus dem festen SPKI, sonst nullopt.
std::optional<std::array<std::uint8_t, 64>> p256_point(std::string_view pem) {
    const auto der = pem_public_key_der(pem);
    if (!der || der->size() != kSpkiSize || std::memcmp(der->data(), kSpkiPrefix, sizeof(kSpkiPrefix)) != 0) {
        return std::nullopt;
    }
    std::array<std::uint8_t, 64> point{};
    std::memcpy(point.data(), der->data() + sizeof(kSpkiPrefix), point.size());
    return point;
}

// Importiert X‖Y als BCRYPT_ECCPUBLIC_BLOB. CNG prüft dabei, ob der Punkt auf
// der Kurve liegt; ein gekipptes Byte in X oder Y scheitert hier (gemessen im Test).
BCRYPT_KEY_HANDLE import_p256(const std::array<std::uint8_t, 64>& point) {
    std::vector<std::uint8_t> blob(sizeof(BCRYPT_ECCKEY_BLOB) + point.size());
    BCRYPT_ECCKEY_BLOB header{};
    header.dwMagic = BCRYPT_ECDSA_PUBLIC_P256_MAGIC;
    header.cbKey = 32;
    std::memcpy(blob.data(), &header, sizeof(header));
    std::memcpy(blob.data() + sizeof(header), point.data(), point.size());
    BCRYPT_KEY_HANDLE key = nullptr;
    const NTSTATUS status = BCryptImportKeyPair(BCRYPT_ECDSA_P256_ALG_HANDLE, nullptr, BCRYPT_ECCPUBLIC_BLOB, &key,
                                                blob.data(), static_cast<ULONG>(blob.size()), 0);
    if (status != kStatusSuccess) {
        return nullptr;
    }
    return key;
}

}  // namespace

struct Sha256Stream::Impl {
    BCRYPT_HASH_HANDLE hash = nullptr;
    bool finished = false;

    ~Impl() {
        if (hash != nullptr) {
            BCryptDestroyHash(hash);
        }
    }
};

Sha256Stream::Sha256Stream() : impl_(std::make_unique<Impl>()) {
    // Ohne eigenes Hash-Objekt verwaltet CNG den Speicher selbst (Windows 7+);
    // das Pseudo-Handle braucht kein BCryptOpenAlgorithmProvider.
    const NTSTATUS status = BCryptCreateHash(BCRYPT_SHA256_ALG_HANDLE, &impl_->hash, nullptr, 0, nullptr, 0, 0);
    if (status != kStatusSuccess) {
        impl_->hash = nullptr;
        throw std::runtime_error("SHA-256-Strom über CNG nicht anlegbar: " +
                                 system_error_text(static_cast<unsigned long>(status)));
    }
}

Sha256Stream::~Sha256Stream() = default;

void Sha256Stream::update(std::span<const std::uint8_t> data) {
    if (impl_->finished) {
        throw std::logic_error("Sha256Stream nach finish() benutzt");
    }
    // BCryptHashData nimmt nur ULONG; größere Spans in Stücken, nie abgeschnitten.
    constexpr std::size_t kMaxChunk = std::numeric_limits<ULONG>::max();
    while (!data.empty()) {
        const std::size_t n = std::min(data.size(), kMaxChunk);
        const NTSTATUS status = BCryptHashData(impl_->hash, const_cast<PUCHAR>(data.data()), static_cast<ULONG>(n), 0);
        if (status != kStatusSuccess) {
            throw std::runtime_error("SHA-256 über CNG fehlgeschlagen: " +
                                     system_error_text(static_cast<unsigned long>(status)));
        }
        data = data.subspan(n);
    }
}

std::array<std::uint8_t, 32> Sha256Stream::finish() {
    if (impl_->finished) {
        throw std::logic_error("Sha256Stream::finish() zweimal aufgerufen");
    }
    impl_->finished = true;
    std::array<std::uint8_t, 32> digest{};
    const NTSTATUS status = BCryptFinishHash(impl_->hash, digest.data(), static_cast<ULONG>(digest.size()), 0);
    BCryptDestroyHash(impl_->hash);
    impl_->hash = nullptr;
    if (status != kStatusSuccess) {
        throw std::runtime_error("SHA-256 über CNG nicht abschließbar: " +
                                 system_error_text(static_cast<unsigned long>(status)));
    }
    return digest;
}

std::optional<std::string> sha256_file_hex(const std::filesystem::path& file) {
    HANDLE h = CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        return std::nullopt;
    }
    Sha256Stream stream;
    std::vector<std::uint8_t> buffer(64 * 1024);
    bool ok = true;
    for (;;) {
        DWORD read = 0;
        if (!ReadFile(h, buffer.data(), static_cast<DWORD>(buffer.size()), &read, nullptr)) {
            ok = false;
            break;
        }
        if (read == 0) {
            break;
        }
        stream.update(std::span<const std::uint8_t>(buffer.data(), read));
    }
    CloseHandle(h);
    if (!ok) {
        return std::nullopt;
    }
    const auto digest = stream.finish();
    return to_hex(digest);
}

bool ecdsa_p256_verify(std::string_view spki_pem, std::span<const std::uint8_t, 32> digest,
                       std::span<const std::uint8_t> der_signature) {
    const auto point = p256_point(spki_pem);
    if (!point) {
        return false;
    }
    const auto rs = der_to_rs_strict(der_signature);
    if (!rs) {
        return false;
    }
    BCRYPT_KEY_HANDLE key = import_p256(*point);
    if (key == nullptr) {
        return false;
    }
    std::array<std::uint8_t, 32> digest_copy{};
    std::copy(digest.begin(), digest.end(), digest_copy.begin());
    std::array<std::uint8_t, 64> signature = *rs;
    const NTSTATUS status = BCryptVerifySignature(key, nullptr, digest_copy.data(), static_cast<ULONG>(digest_copy.size()),
                                                  signature.data(), static_cast<ULONG>(signature.size()), 0);
    BCryptDestroyKey(key);
    // Nur STATUS_SUCCESS gilt; jeder andere Wert (0xC000A000 ungültig, Parameterfehler …)
    // ist eine abgelehnte Signatur.
    return status == kStatusSuccess;
}

bool p256_public_key_usable(std::string_view spki_pem) {
    const auto point = p256_point(spki_pem);
    if (!point) {
        return false;
    }
    BCRYPT_KEY_HANDLE key = import_p256(*point);
    if (key == nullptr) {
        return false;
    }
    BCryptDestroyKey(key);
    return true;
}

}  // namespace swbh::win
