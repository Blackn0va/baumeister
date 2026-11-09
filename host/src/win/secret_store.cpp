#include "win/secret_store.hpp"

#include <windows.h>
#include <dpapi.h>
#include <wincrypt.h>

#include <vector>

namespace swbh::win {

namespace {

std::string b64(const BYTE* data, DWORD len) {
    DWORD n = 0;
    CryptBinaryToStringA(data, len, CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, nullptr, &n);
    std::string out(n, '\0');
    if (!CryptBinaryToStringA(data, len, CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF, out.data(), &n)) {
        return {};
    }
    out.resize(n);
    while (!out.empty() && out.back() == '\0') {
        out.pop_back();
    }
    return out;
}

std::optional<std::vector<BYTE>> unb64(std::string_view s) {
    DWORD n = 0;
    if (!CryptStringToBinaryA(s.data(), static_cast<DWORD>(s.size()), CRYPT_STRING_BASE64, nullptr, &n, nullptr,
                              nullptr)) {
        return std::nullopt;
    }
    std::vector<BYTE> out(n);
    if (!CryptStringToBinaryA(s.data(), static_cast<DWORD>(s.size()), CRYPT_STRING_BASE64, out.data(), &n, nullptr,
                              nullptr)) {
        return std::nullopt;
    }
    out.resize(n);
    return out;
}

}  // namespace

std::optional<std::string> protect_secret(std::string_view plain, std::string_view purpose) {
    DATA_BLOB in{static_cast<DWORD>(plain.size()), reinterpret_cast<BYTE*>(const_cast<char*>(plain.data()))};
    DATA_BLOB entropy{static_cast<DWORD>(purpose.size()), reinterpret_cast<BYTE*>(const_cast<char*>(purpose.data()))};
    DATA_BLOB out{};
    if (!CryptProtectData(&in, L"StreamWizard Baumeister-Host", &entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out)) {
        return std::nullopt;
    }
    std::string s = b64(out.pbData, out.cbData);
    LocalFree(out.pbData);
    if (s.empty()) {
        return std::nullopt;
    }
    return s;
}

std::optional<std::string> unprotect_secret(std::string_view stored, std::string_view purpose) {
    auto raw = unb64(stored);
    if (!raw || raw->empty()) {
        return std::nullopt;
    }
    DATA_BLOB in{static_cast<DWORD>(raw->size()), raw->data()};
    DATA_BLOB entropy{static_cast<DWORD>(purpose.size()), reinterpret_cast<BYTE*>(const_cast<char*>(purpose.data()))};
    DATA_BLOB out{};
    if (!CryptUnprotectData(&in, nullptr, &entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out)) {
        return std::nullopt;  // anderes Konto, anderer Zweck oder manipuliert – fail-closed
    }
    std::string s(reinterpret_cast<const char*>(out.pbData), out.cbData);
    SecureZeroMemory(out.pbData, out.cbData);
    LocalFree(out.pbData);
    return s;
}

}  // namespace swbh::win
