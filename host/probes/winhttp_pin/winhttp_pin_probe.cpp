// Messprobe: gepinnte wss://-Verbindung über WinHTTP mit Client-Zertifikat aus einem nicht exportierbaren CNG-Schlüssel.
//
// WOZU: Kanal v1 (docs/kanal-v1.md) verlangt eine gegenseitig gepinnte TLS-Verbindung ohne CA. Bevor die App das baut,
// misst diese Probe gegen RelayProbe.java (JSSE wie das Relay):
//   (a) Ist das Serverzertifikat im Rückruf SENDING_REQUEST lesbar, BEVOR Bytes der Anfrage hinausgehen, und lässt sich
//       die Anfrage dort abbrechen (die Gegenstelle zählt die empfangenen HTTP-Bytes)?
//   (b) Geht TLS-Client-Authentifizierung mit einem nicht exportierbaren ECDSA-P-256-Schlüssel (TPM oder Software-KSP),
//       Zertifikat mit CRYPT_KEY_PROV_INFO, aus einem frischen Prozess (= nach einem Neustart der App)?
//   (c) WebSocket-Upgrade und Binärrahmen: Wie sendet WinHTTP große Nachrichten (ein Rahmen oder Fragmente), wie
//       liefert es große Rahmen, was tut es bei falschen Rahmen des Servers, was sendet es als Keepalive?
//   (d) Nur TLS 1.2 beim Server.
// Ergebnisse: probes/winhttp_pin/README.md. Ablauf: run_probe.ps1.
//
// Aufruf:
//   winhttp_pin_probe anlegen <name> <tpm|software>
//   winhttp_pin_probe nachsehen <name>                frischer Prozess: Zertifikat finden, Schlüssel benutzen
//   winhttp_pin_probe entfernen <name>
//   winhttp_pin_probe ohne-prov-info <name>           Zertifikat ohne Schlüsselverweis (Gegenprobe zu (b))
//   winhttp_pin_probe verbinden <port> <server-pin> <name|-> <szenario> [--nur-tls12] [--abbruch=callback|keiner]
//                     [--keepalive=ms] [--frist=ms] [--frist-anfrage=ms] [--ohne-ignore] [--puffer=bytes]
//
// Kein Teil der App. Übersetzt mit cl /std:c++20 /W4 /EHsc /utf-8 (run_probe.ps1).

#include <windows.h>

#include <bcrypt.h>
#include <ncrypt.h>
#include <wincrypt.h>
#include <winhttp.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "winhttp.lib")
#pragma comment(lib, "crypt32.lib")
#pragma comment(lib, "ncrypt.lib")
#pragma comment(lib, "bcrypt.lib")

namespace {

using Clock = std::chrono::steady_clock;
const Clock::time_point g_start = Clock::now();

long long ms_since_start() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - g_start).count();
}

void out(const std::string& line) {
    std::printf("[%lld ms] %s\n", ms_since_start(), line.c_str());
    std::fflush(stdout);
}

std::string narrow(const std::wstring& w) {
    if (w.empty()) {
        return {};
    }
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<std::size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

std::wstring widen(const std::string& s) {
    if (s.empty()) {
        return {};
    }
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(static_cast<std::size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

std::string hex(const std::uint8_t* p, std::size_t n) {
    static const char* k = "0123456789abcdef";
    std::string s;
    for (std::size_t i = 0; i < n; ++i) {
        s.push_back(k[p[i] >> 4]);
        s.push_back(k[p[i] & 15]);
    }
    return s;
}

std::string sha256_hex(const std::uint8_t* p, std::size_t n) {
    std::uint8_t digest[32]{};
    BCryptHash(BCRYPT_SHA256_ALG_HANDLE, nullptr, 0, const_cast<PUCHAR>(p), static_cast<ULONG>(n), digest, 32);
    return hex(digest, 32);
}

std::string pin_of(PCCERT_CONTEXT c) { return "sha256:" + sha256_hex(c->pbCertEncoded, c->cbCertEncoded); }

std::string status_hex(SECURITY_STATUS st) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "0x%08lx", static_cast<unsigned long>(st));
    return buf;
}

std::wstring cert_cn(PCCERT_CONTEXT c) {
    wchar_t buf[256]{};
    CertGetNameStringW(c, CERT_NAME_ATTR_TYPE, 0, const_cast<char*>(szOID_COMMON_NAME), buf, 256);
    return buf;
}

HCERTSTORE open_my() {
    return CertOpenStore(CERT_STORE_PROV_SYSTEM_W, 0, 0, CERT_SYSTEM_STORE_CURRENT_USER, L"My");
}

// Erstes Zertifikat im Speicher des Benutzers mit CN = name (Aufrufer gibt es frei).
PCCERT_CONTEXT find_cert(HCERTSTORE store, const std::wstring& name) {
    PCCERT_CONTEXT c = nullptr;
    while ((c = CertEnumCertificatesInStore(store, c)) != nullptr) {
        if (cert_cn(c) == name) {
            return CertDuplicateCertificateContext(c);
        }
    }
    return nullptr;
}

std::vector<BYTE> encode(LPCSTR type, const void* value) {
    DWORD n = 0;
    if (!CryptEncodeObjectEx(X509_ASN_ENCODING, type, value, 0, nullptr, nullptr, &n)) {
        return {};
    }
    std::vector<BYTE> b(n);
    CryptEncodeObjectEx(X509_ASN_ENCODING, type, value, 0, nullptr, b.data(), &n);
    b.resize(n);
    return b;
}

FILETIME add_days(FILETIME ft, long long days) {
    ULARGE_INTEGER u{};
    u.LowPart = ft.dwLowDateTime;
    u.HighPart = ft.dwHighDateTime;
    u.QuadPart += static_cast<ULONGLONG>(days * 24LL * 3600LL * 10000000LL);
    return FILETIME{u.LowPart, u.HighPart};
}

int cmd_create(const std::wstring& name, bool tpm, bool with_prov_info) {
    const wchar_t* provider = tpm ? MS_PLATFORM_CRYPTO_PROVIDER : MS_KEY_STORAGE_PROVIDER;
    const auto t0 = Clock::now();
    NCRYPT_PROV_HANDLE prov = 0;
    SECURITY_STATUS st = NCryptOpenStorageProvider(&prov, provider, 0);
    out("anbieter=" + narrow(provider) + " öffnen=" + status_hex(st));
    if (st != ERROR_SUCCESS) {
        return 1;
    }
    NCRYPT_KEY_HANDLE key = 0;
    st = NCryptCreatePersistedKey(prov, &key, NCRYPT_ECDSA_P256_ALGORITHM, name.c_str(), 0, 0);
    out("schlüssel_anlegen=" + status_hex(st));
    if (st != ERROR_SUCCESS) {
        NCryptFreeObject(prov);
        return 1;
    }
    DWORD policy = 0;
    st = NCryptSetProperty(key, NCRYPT_EXPORT_POLICY_PROPERTY, reinterpret_cast<PBYTE>(&policy), sizeof policy,
                           NCRYPT_PERSIST_FLAG);
    out("export_policy_0=" + status_hex(st));
    DWORD usage = NCRYPT_ALLOW_SIGNING_FLAG;
    st = NCryptSetProperty(key, NCRYPT_KEY_USAGE_PROPERTY, reinterpret_cast<PBYTE>(&usage), sizeof usage,
                           NCRYPT_PERSIST_FLAG);
    out("key_usage_signieren=" + status_hex(st));
    st = NCryptFinalizeKey(key, NCRYPT_SILENT_FLAG);
    out("abschließen=" + status_hex(st) + " dauer_ms=" +
        std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count()));
    if (st != ERROR_SUCCESS) {
        NCryptDeleteKey(key, 0);
        NCryptFreeObject(prov);
        return 1;
    }
    BYTE blob[512];
    DWORD blob_len = 0;
    st = NCryptExportKey(key, 0, BCRYPT_ECCPRIVATE_BLOB, nullptr, blob, sizeof blob, &blob_len, 0);
    out("export_privat_versuch=" + status_hex(st) + (st == ERROR_SUCCESS ? " (EXPORTIERBAR!)" : " (abgelehnt)"));

    const std::wstring x500 = L"CN=" + name;
    DWORD nb_len = 0;
    CertStrToNameW(X509_ASN_ENCODING, x500.c_str(), CERT_X500_NAME_STR, nullptr, nullptr, &nb_len, nullptr);
    std::vector<BYTE> nb(nb_len);
    CertStrToNameW(X509_ASN_ENCODING, x500.c_str(), CERT_X500_NAME_STR, nullptr, nb.data(), &nb_len, nullptr);
    CERT_NAME_BLOB subject{nb_len, nb.data()};

    BYTE ku_byte = CERT_DIGITAL_SIGNATURE_KEY_USAGE;
    CRYPT_BIT_BLOB ku{1, &ku_byte, 7};
    std::vector<BYTE> ku_enc = encode(X509_KEY_USAGE, &ku);
    LPSTR eku_oid = const_cast<LPSTR>(szOID_PKIX_KP_CLIENT_AUTH);
    CERT_ENHKEY_USAGE eku{1, &eku_oid};
    std::vector<BYTE> eku_enc = encode(X509_ENHANCED_KEY_USAGE, &eku);
    CERT_BASIC_CONSTRAINTS2_INFO bc{FALSE, FALSE, 0};
    std::vector<BYTE> bc_enc = encode(X509_BASIC_CONSTRAINTS2, &bc);
    CERT_EXTENSION ext[3] = {
        {const_cast<LPSTR>(szOID_KEY_USAGE), TRUE, {static_cast<DWORD>(ku_enc.size()), ku_enc.data()}},
        {const_cast<LPSTR>(szOID_ENHANCED_KEY_USAGE), FALSE, {static_cast<DWORD>(eku_enc.size()), eku_enc.data()}},
        {const_cast<LPSTR>(szOID_BASIC_CONSTRAINTS2), TRUE, {static_cast<DWORD>(bc_enc.size()), bc_enc.data()}},
    };
    CERT_EXTENSIONS exts{3, ext};
    CRYPT_KEY_PROV_INFO kpi{};
    kpi.pwszContainerName = const_cast<LPWSTR>(name.c_str());
    kpi.pwszProvName = const_cast<LPWSTR>(provider);
    kpi.dwProvType = 0;
    kpi.dwFlags = 0;
    kpi.dwKeySpec = 0;
    CRYPT_ALGORITHM_IDENTIFIER alg{const_cast<LPSTR>(szOID_ECDSA_SHA256), {0, nullptr}};
    FILETIME now{};
    GetSystemTimeAsFileTime(&now);
    const FILETIME from = add_days(now, -1);
    const FILETIME until = add_days(now, 3650);
    SYSTEMTIME st_from{};
    SYSTEMTIME st_until{};
    FileTimeToSystemTime(&from, &st_from);
    FileTimeToSystemTime(&until, &st_until);
    PCCERT_CONTEXT cert =
        CertCreateSelfSignCertificate(key, &subject, with_prov_info ? 0 : CERT_CREATE_SELFSIGN_NO_KEY_INFO,
                                      with_prov_info ? &kpi : nullptr, &alg, &st_from, &st_until, &exts);
    if (cert == nullptr) {
        out("zertifikat_fehler=" + std::to_string(GetLastError()));
        NCryptFreeObject(key);
        NCryptFreeObject(prov);
        return 1;
    }
    HCERTSTORE store = open_my();
    PCCERT_CONTEXT stored = nullptr;
    const BOOL added = CertAddCertificateContextToStore(store, cert, CERT_STORE_ADD_NEW, &stored);
    out(std::string("im_speicher=") + (added ? "ja" : "nein " + std::to_string(GetLastError())) +
        " pin=" + pin_of(cert));
    DWORD prop_len = 0;
    const BOOL has_prov = stored != nullptr &&
                          CertGetCertificateContextProperty(stored, CERT_KEY_PROV_INFO_PROP_ID, nullptr, &prop_len);
    out(std::string("prov_info_im_speicher=") + (has_prov ? "ja" : "nein"));
    if (stored != nullptr) {
        CertFreeCertificateContext(stored);
    }
    CertFreeCertificateContext(cert);
    CertCloseStore(store, 0);
    NCryptFreeObject(key);
    NCryptFreeObject(prov);
    return 0;
}

int cmd_check(const std::wstring& name) {
    HCERTSTORE store = open_my();
    PCCERT_CONTEXT cert = find_cert(store, name);
    if (cert == nullptr) {
        out("zertifikat_fehlt");
        CertCloseStore(store, 0);
        return 1;
    }
    out("gefunden pin=" + pin_of(cert));
    HCRYPTPROV_OR_NCRYPT_KEY_HANDLE key = 0;
    DWORD spec = 0;
    BOOL must_free = FALSE;
    const auto t0 = Clock::now();
    if (!CryptAcquireCertificatePrivateKey(cert, CRYPT_ACQUIRE_ONLY_NCRYPT_KEY_FLAG | CRYPT_ACQUIRE_SILENT_FLAG,
                                           nullptr, &key, &spec, &must_free)) {
        out("schlüssel_nicht_greifbar=" + std::to_string(GetLastError()));
        CertFreeCertificateContext(cert);
        CertCloseStore(store, 0);
        return 1;
    }
    std::uint8_t digest[32];
    for (int i = 0; i < 32; ++i) {
        digest[i] = static_cast<std::uint8_t>(i * 7 + 1);
    }
    BYTE sig[256];
    DWORD sig_len = 0;
    SECURITY_STATUS st = NCryptSignHash(key, nullptr, digest, 32, sig, sizeof sig, &sig_len, NCRYPT_SILENT_FLAG);
    out("signieren=" + status_hex(st) + " länge=" + std::to_string(sig_len) + " dauer_ms=" +
        std::to_string(std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - t0).count()));
    BCRYPT_KEY_HANDLE pub = nullptr;
    if (CryptImportPublicKeyInfoEx2(X509_ASN_ENCODING, &cert->pCertInfo->SubjectPublicKeyInfo, 0, nullptr, &pub)) {
        const NTSTATUS v = BCryptVerifySignature(pub, nullptr, digest, 32, sig, sig_len, 0);
        out(std::string("prüfen_mit_zertifikat=") + (v == 0 ? "gültig" : "UNGÜLTIG"));
        BCryptDestroyKey(pub);
    }
    wchar_t prov_name[128]{};
    DWORD got = 0;
    NCRYPT_PROV_HANDLE prov = 0;
    DWORD prov_len = sizeof prov;
    if (NCryptGetProperty(key, NCRYPT_PROVIDER_HANDLE_PROPERTY, reinterpret_cast<PBYTE>(&prov), prov_len, &got, 0) ==
            ERROR_SUCCESS &&
        NCryptGetProperty(prov, NCRYPT_NAME_PROPERTY, reinterpret_cast<PBYTE>(prov_name), sizeof prov_name, &got, 0) ==
            ERROR_SUCCESS) {
        out("anbieter=" + narrow(prov_name));
    }
    if (prov != 0) {
        NCryptFreeObject(prov);
    }
    if (must_free) {
        NCryptFreeObject(key);
    }
    CertFreeCertificateContext(cert);
    CertCloseStore(store, 0);
    return st == ERROR_SUCCESS ? 0 : 1;
}

int cmd_delete(const std::wstring& name) {
    HCERTSTORE store = open_my();
    int removed = 0;
    while (PCCERT_CONTEXT c = find_cert(store, name)) {
        CertDeleteCertificateFromStore(c);  // gibt c frei
        ++removed;
    }
    CertCloseStore(store, 0);
    int keys = 0;
    for (const wchar_t* provider : {MS_PLATFORM_CRYPTO_PROVIDER, MS_KEY_STORAGE_PROVIDER}) {
        NCRYPT_PROV_HANDLE prov = 0;
        if (NCryptOpenStorageProvider(&prov, provider, 0) != ERROR_SUCCESS) {
            continue;
        }
        NCRYPT_KEY_HANDLE key = 0;
        if (NCryptOpenKey(prov, &key, name.c_str(), 0, NCRYPT_SILENT_FLAG) == ERROR_SUCCESS) {
            if (NCryptDeleteKey(key, 0) == ERROR_SUCCESS) {
                ++keys;
            } else {
                NCryptFreeObject(key);
            }
        }
        NCryptFreeObject(prov);
    }
    out("gelöscht zertifikate=" + std::to_string(removed) + " schlüssel=" + std::to_string(keys));
    return 0;
}

// --- Verbindung ---------------------------------------------------------------------------------------------------

struct Ctx {
    std::string expected_pin;
    bool abort_in_callback = false;
    std::atomic<bool> cert_in_callback{false};
    std::atomic<bool> mismatch{false};
    std::atomic<bool> closed_in_callback{false};
    std::atomic<DWORD> secure_flags{0};
    std::string pin_in_callback;
};

const char* status_name(DWORD s) {
    switch (s) {
    case WINHTTP_CALLBACK_STATUS_RESOLVING_NAME: return "RESOLVING_NAME";
    case WINHTTP_CALLBACK_STATUS_NAME_RESOLVED: return "NAME_RESOLVED";
    case WINHTTP_CALLBACK_STATUS_CONNECTING_TO_SERVER: return "CONNECTING_TO_SERVER";
    case WINHTTP_CALLBACK_STATUS_CONNECTED_TO_SERVER: return "CONNECTED_TO_SERVER";
    case WINHTTP_CALLBACK_STATUS_SENDING_REQUEST: return "SENDING_REQUEST";
    case WINHTTP_CALLBACK_STATUS_REQUEST_SENT: return "REQUEST_SENT";
    case WINHTTP_CALLBACK_STATUS_RECEIVING_RESPONSE: return "RECEIVING_RESPONSE";
    case WINHTTP_CALLBACK_STATUS_RESPONSE_RECEIVED: return "RESPONSE_RECEIVED";
    case WINHTTP_CALLBACK_STATUS_CLOSING_CONNECTION: return "CLOSING_CONNECTION";
    case WINHTTP_CALLBACK_STATUS_CONNECTION_CLOSED: return "CONNECTION_CLOSED";
    case WINHTTP_CALLBACK_STATUS_HANDLE_CREATED: return "HANDLE_CREATED";
    case WINHTTP_CALLBACK_STATUS_HANDLE_CLOSING: return "HANDLE_CLOSING";
    case WINHTTP_CALLBACK_STATUS_SECURE_FAILURE: return "SECURE_FAILURE";
    case WINHTTP_CALLBACK_STATUS_REDIRECT: return "REDIRECT";
    default: return "anderer";
    }
}

void CALLBACK callback(HINTERNET h, DWORD_PTR context, DWORD status, LPVOID info, DWORD info_len) {
    if (context == 0) {
        return;
    }
    auto* ctx = reinterpret_cast<Ctx*>(context);
    std::string extra;
    if (status == WINHTTP_CALLBACK_STATUS_SECURE_FAILURE && info != nullptr && info_len >= sizeof(DWORD)) {
        ctx->secure_flags |= *static_cast<const DWORD*>(info);
        char b[32];
        std::snprintf(b, sizeof b, " flags=0x%08lx", static_cast<unsigned long>(*static_cast<const DWORD*>(info)));
        extra = b;
    }
    if (status == WINHTTP_CALLBACK_STATUS_REQUEST_SENT && info != nullptr && info_len >= sizeof(DWORD)) {
        extra = " bytes=" + std::to_string(*static_cast<const DWORD*>(info));
    }
    if (status == WINHTTP_CALLBACK_STATUS_SENDING_REQUEST) {
        PCCERT_CONTEXT cert = nullptr;
        DWORD size = sizeof cert;
        if (WinHttpQueryOption(h, WINHTTP_OPTION_SERVER_CERT_CONTEXT, &cert, &size) && cert != nullptr) {
            ctx->cert_in_callback = true;
            ctx->pin_in_callback = pin_of(cert);
            CertFreeCertificateContext(cert);
            extra = " zertifikat=lesbar pin_passt=" + std::string(ctx->pin_in_callback == ctx->expected_pin ? "ja" : "NEIN");
            if (ctx->pin_in_callback != ctx->expected_pin) {
                ctx->mismatch = true;
                if (ctx->abort_in_callback) {
                    const BOOL closed = WinHttpCloseHandle(h);
                    ctx->closed_in_callback = closed != FALSE;
                    extra += std::string(" schließen_im_callback=") + (closed ? "ok" : std::to_string(GetLastError()));
                }
            }
        } else {
            extra = " zertifikat=NICHT_lesbar fehler=" + std::to_string(GetLastError());
        }
    }
    out(std::string("rückruf ") + status_name(status) + extra);
}

std::string arg_value(const std::vector<std::string>& args, const std::string& key, const std::string& fallback) {
    for (const std::string& a : args) {
        if (a.rfind(key + "=", 0) == 0) {
            return a.substr(key.size() + 1);
        }
    }
    return fallback;
}

bool has_flag(const std::vector<std::string>& args, const std::string& flag) {
    for (const std::string& a : args) {
        if (a == flag) {
            return true;
        }
    }
    return false;
}

std::string type_name(WINHTTP_WEB_SOCKET_BUFFER_TYPE t) {
    switch (t) {
    case WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE: return "BINARY_MESSAGE";
    case WINHTTP_WEB_SOCKET_BINARY_FRAGMENT_BUFFER_TYPE: return "BINARY_FRAGMENT";
    case WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE: return "UTF8_MESSAGE";
    case WINHTTP_WEB_SOCKET_UTF8_FRAGMENT_BUFFER_TYPE: return "UTF8_FRAGMENT";
    case WINHTTP_WEB_SOCKET_CLOSE_BUFFER_TYPE: return "CLOSE";
    default: return "?";
    }
}

// Eine Nachricht empfangen; jeden Aufruf mitschreiben. Rückgabe: Bytes (leer bei Fehler/Close), ok=false bei Fehler.
std::string receive_message(HINTERNET ws, bool& ok, bool& closed) {
    ok = false;
    closed = false;
    std::string message;
    std::vector<char> buffer(70000);
    for (;;) {
        DWORD read = 0;
        WINHTTP_WEB_SOCKET_BUFFER_TYPE type{};
        const DWORD rc = WinHttpWebSocketReceive(ws, buffer.data(), static_cast<DWORD>(buffer.size()), &read, &type);
        if (rc != NO_ERROR) {
            out("empfangen fehler=" + std::to_string(rc));
            return message;
        }
        out("empfangen typ=" + type_name(type) + " bytes=" + std::to_string(read));
        if (type == WINHTTP_WEB_SOCKET_CLOSE_BUFFER_TYPE) {
            USHORT code = 0;
            char reason[128]{};
            DWORD reason_len = 0;
            WinHttpWebSocketQueryCloseStatus(ws, &code, reason, sizeof reason, &reason_len);
            out("close_vom_server code=" + std::to_string(code) + " grund=" + std::string(reason, reason_len));
            closed = true;
            return message;
        }
        message.append(buffer.data(), read);
        if (type == WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE || type == WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE) {
            ok = true;
            return message;
        }
    }
}

std::string pattern(std::size_t n) {
    std::string s(n, '\0');
    for (std::size_t i = 0; i < n; ++i) {
        s[i] = static_cast<char>(i % 251);
    }
    return s;
}

int cmd_connect(const std::vector<std::string>& args) {
    if (args.size() < 4) {
        return 2;
    }
    const int port = std::stoi(args[0]);
    Ctx ctx;
    ctx.expected_pin = args[1];
    const std::wstring name = widen(args[2]);
    const std::string scenario = args[3];
    const std::vector<std::string> opts(args.begin() + 4, args.end());
    ctx.abort_in_callback = arg_value(opts, "--abbruch", "callback") == "callback";

    HINTERNET session = WinHttpOpen(L"BaumeisterHost-Probe/1", WINHTTP_ACCESS_TYPE_NO_PROXY, WINHTTP_NO_PROXY_NAME,
                                    WINHTTP_NO_PROXY_BYPASS, 0);
    DWORD protocols = has_flag(opts, "--nur-tls12")
                          ? WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2
                          : (WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2 | WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_3);
    out(std::string("protokolle_setzen=") +
        (WinHttpSetOption(session, WINHTTP_OPTION_SECURE_PROTOCOLS, &protocols, sizeof protocols) ? "ok"
                                                                                                   : std::to_string(GetLastError())));
    HINTERNET connect = WinHttpConnect(session, L"127.0.0.1", static_cast<INTERNET_PORT>(port), 0);
    HINTERNET request = WinHttpOpenRequest(connect, L"GET", L"/kanal/v1", nullptr, WINHTTP_NO_REFERER,
                                           WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE);
    DWORD no_redirect = WINHTTP_DISABLE_REDIRECTS | WINHTTP_DISABLE_COOKIES | WINHTTP_DISABLE_KEEP_ALIVE;
    WinHttpSetOption(request, WINHTTP_OPTION_DISABLE_FEATURE, &no_redirect, sizeof no_redirect);
    if (!has_flag(opts, "--ohne-ignore")) {
        DWORD flags = SECURITY_FLAG_IGNORE_UNKNOWN_CA | SECURITY_FLAG_IGNORE_CERT_CN_INVALID;
        out(std::string("security_flags=") +
            (WinHttpSetOption(request, WINHTTP_OPTION_SECURITY_FLAGS, &flags, sizeof flags) ? "ok"
                                                                                            : std::to_string(GetLastError())));
    }
    HCERTSTORE store = nullptr;
    PCCERT_CONTEXT cert = nullptr;
    if (args[2] != "-") {
        store = open_my();
        cert = find_cert(store, name);
        if (cert == nullptr) {
            out("client_zertifikat_fehlt");
            return 1;
        }
        out("client_pin=" + pin_of(cert));
        out(std::string("client_cert_setzen=") +
            (WinHttpSetOption(request, WINHTTP_OPTION_CLIENT_CERT_CONTEXT, const_cast<CERT_CONTEXT*>(cert),
                              sizeof(CERT_CONTEXT))
                 ? "ok"
                 : std::to_string(GetLastError())));
    }
    if (const std::string b = arg_value(opts, "--puffer", ""); !b.empty()) {
        DWORD size = static_cast<DWORD>(std::stoul(b));
        out(std::string("empfangspuffer=") +
            (WinHttpSetOption(request, WINHTTP_OPTION_WEB_SOCKET_RECEIVE_BUFFER_SIZE, &size, sizeof size)
                 ? "ok"
                 : std::to_string(GetLastError())) +
            std::string(" sendepuffer=") +
            (WinHttpSetOption(request, WINHTTP_OPTION_WEB_SOCKET_SEND_BUFFER_SIZE, &size, sizeof size)
                 ? "ok"
                 : std::to_string(GetLastError())));
    }
    if (const std::string k = arg_value(opts, "--keepalive", ""); !k.empty()) {
        DWORD interval = static_cast<DWORD>(std::stoul(k));
        out(std::string("keepalive_session=") +
            (WinHttpSetOption(session, WINHTTP_OPTION_WEB_SOCKET_KEEPALIVE_INTERVAL, &interval, sizeof interval)
                 ? "ok"
                 : std::to_string(GetLastError())) +
            std::string(" keepalive_request=") +
            (WinHttpSetOption(request, WINHTTP_OPTION_WEB_SOCKET_KEEPALIVE_INTERVAL, &interval, sizeof interval)
                 ? "ok"
                 : std::to_string(GetLastError())));
    }
    if (const std::string f = arg_value(opts, "--frist-anfrage", ""); !f.empty()) {
        // Frist auf dem Anfrage-Handle VOR dem Upgrade – erbt der WebSocket sie? (Auf dem WebSocket-Handle selbst
        // lehnt WinHTTP die Option mit 12018 ab, gemessen.)
        const int ms = std::stoi(f);
        out(std::string("frist_anfrage_setzen=") +
            (WinHttpSetTimeouts(request, ms, ms, ms, ms) ? "ok" : std::to_string(GetLastError())));
    }
    WinHttpSetOption(request, WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET, nullptr, 0);
    const wchar_t* proto = L"Sec-WebSocket-Protocol: baumeister-kanal.v1\r\n";
    WinHttpAddRequestHeaders(request, proto, static_cast<DWORD>(-1L), WINHTTP_ADDREQ_FLAG_ADD);
    WinHttpSetStatusCallback(request, callback, WINHTTP_CALLBACK_FLAG_ALL_NOTIFICATIONS, 0);

    BOOL ok = WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0,
                                 reinterpret_cast<DWORD_PTR>(&ctx));
    DWORD err = ok ? 0 : GetLastError();
    out("senden=" + std::string(ok ? "ok" : "fehler " + std::to_string(err)) + " zertifikat_im_callback=" +
        (ctx.cert_in_callback ? "ja" : "nein") + " geschlossen_im_callback=" + (ctx.closed_in_callback ? "ja" : "nein"));
    if (ctx.closed_in_callback) {
        out("ergebnis=abgebrochen_im_callback");
        return 0;
    }
    if (!ok) {
        char b[32];
        std::snprintf(b, sizeof b, "0x%08lx", static_cast<unsigned long>(ctx.secure_flags.load()));
        out(std::string("ergebnis=sendefehler secure_flags=") + b);
        WinHttpCloseHandle(request);
        return 0;
    }
    ok = WinHttpReceiveResponse(request, nullptr);
    if (!ok) {
        out("antwort_fehler=" + std::to_string(GetLastError()));
        WinHttpCloseHandle(request);
        return 0;
    }
    DWORD status = 0;
    DWORD size = sizeof status;
    WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX,
                        &status, &size, WINHTTP_NO_HEADER_INDEX);
    wchar_t proto_reply[128]{};
    DWORD proto_len = sizeof proto_reply;
    const BOOL has_proto =
        WinHttpQueryHeaders(request, WINHTTP_QUERY_CUSTOM, L"Sec-WebSocket-Protocol", proto_reply, &proto_len,
                            WINHTTP_NO_HEADER_INDEX);
    PCCERT_CONTEXT server = nullptr;
    DWORD cs = sizeof server;
    std::string second_pin = "-";
    if (WinHttpQueryOption(request, WINHTTP_OPTION_SERVER_CERT_CONTEXT, &server, &cs) && server != nullptr) {
        second_pin = pin_of(server);
        CertFreeCertificateContext(server);
    }
    out("status=" + std::to_string(status) + " protokoll=" + (has_proto ? narrow(proto_reply) : "-") +
        " pin_vor_upgrade_passt=" + (second_pin == ctx.expected_pin ? "ja" : "NEIN"));
    if (second_pin != ctx.expected_pin) {
        out("ergebnis=abgebrochen_vor_upgrade");
        WinHttpCloseHandle(request);
        return 0;
    }
    HINTERNET ws = WinHttpWebSocketCompleteUpgrade(request, 0);
    if (ws == nullptr) {
        out("upgrade_fehler=" + std::to_string(GetLastError()));
        return 1;
    }
    WinHttpCloseHandle(request);
    if (const std::string f = arg_value(opts, "--frist", ""); !f.empty()) {
        DWORD timeout = static_cast<DWORD>(std::stoul(f));
        out(std::string("frist_setzen=") +
            (WinHttpSetOption(ws, WINHTTP_OPTION_RECEIVE_TIMEOUT, &timeout, sizeof timeout) ? "ok"
                                                                                            : std::to_string(GetLastError())));
    }
    if (const std::string k = arg_value(opts, "--keepalive", ""); !k.empty()) {
        DWORD interval = static_cast<DWORD>(std::stoul(k));
        out(std::string("keepalive_websocket=") +
            (WinHttpSetOption(ws, WINHTTP_OPTION_WEB_SOCKET_KEEPALIVE_INTERVAL, &interval, sizeof interval)
                 ? "ok"
                 : std::to_string(GetLastError())));
    }
    out("upgrade=ok");

    bool msg_ok = false;
    bool closed = false;
    if (scenario == "echo") {
        // Leere Nachricht: mit Zeiger auf Daten meldete WinHTTP 87 (gemessen) – hier mit nullptr.
        const DWORD rc0 = WinHttpWebSocketSend(ws, WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE, nullptr, 0);
        out("gesendet leer_mit_nullptr rc=" + std::to_string(rc0));
        if (rc0 == NO_ERROR) {
            const std::string back = receive_message(ws, msg_ok, closed);
            out("echo leer bytes=" + std::to_string(back.size()) + " ok=" + (msg_ok ? "ja" : "nein"));
        }
        for (const std::size_t n : {std::size_t{1}, std::size_t{125}, std::size_t{126},
                                    std::size_t{65535}, std::size_t{65536}, std::size_t{2 + 4096 + 65536}}) {
            const std::string data = pattern(n);
            const DWORD rc = WinHttpWebSocketSend(ws, WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE,
                                                  const_cast<char*>(data.data()), static_cast<DWORD>(data.size()));
            out("gesendet bytes=" + std::to_string(n) + " rc=" + std::to_string(rc));
            const std::string back = receive_message(ws, msg_ok, closed);
            out("echo bytes=" + std::to_string(back.size()) + " gleich=" + (back == data ? "ja" : "NEIN"));
        }
    } else if (scenario == "ping") {
        std::thread later([ws] {
            std::this_thread::sleep_for(std::chrono::seconds(10));
            const std::string end = "ende";
            WinHttpWebSocketSend(ws, WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE, const_cast<char*>(end.data()),
                                 static_cast<DWORD>(end.size()));
        });
        const std::string back = receive_message(ws, msg_ok, closed);
        out("erste_nachricht=" + back);
        later.join();
    } else if (scenario == "stille" || scenario == "ping-stille") {
        for (int i = 0; i < 3 && !closed; ++i) {
            const std::string back = receive_message(ws, msg_ok, closed);
            out("nachricht ok=" + std::string(msg_ok ? "ja" : "nein") + " inhalt=" + back.substr(0, 40));
            if (!msg_ok) {
                break;
            }
        }
    } else if (scenario == "voll") {
        for (int i = 0; i < 2; ++i) {
            const std::string back = receive_message(ws, msg_ok, closed);
            out("voll bytes=" + std::to_string(back.size()) + " gleich=" +
                (back == pattern(2 + 4096 + 65536) ? "ja" : "NEIN"));
        }
    } else {
        const std::string back = receive_message(ws, msg_ok, closed);
        out("nach_falschem_rahmen ok=" + std::string(msg_ok ? "ja" : "nein") + " bytes=" + std::to_string(back.size()) +
            " inhalt=" + back.substr(0, 20));
        const std::string after = "danach";
        const DWORD rc = WinHttpWebSocketSend(ws, WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE,
                                              const_cast<char*>(after.data()), static_cast<DWORD>(after.size()));
        out("senden_danach rc=" + std::to_string(rc));
        if (rc == NO_ERROR) {
            const std::string echo = receive_message(ws, msg_ok, closed);
            out("echo_danach ok=" + std::string(msg_ok ? "ja" : "nein") + " inhalt=" + echo);
        }
    }
    if (!closed) {
        const DWORD rc = WinHttpWebSocketClose(ws, WINHTTP_WEB_SOCKET_SUCCESS_CLOSE_STATUS, nullptr, 0);
        out("close rc=" + std::to_string(rc));
    }
    WinHttpCloseHandle(ws);
    WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    if (cert != nullptr) {
        CertFreeCertificateContext(cert);
    }
    if (store != nullptr) {
        CertCloseStore(store, 0);
    }
    out("ergebnis=fertig");
    return 0;
}

}  // namespace

// wmain: Argumente kommen über CreateProcessW als UTF-16 an. Befehle ohne Umlaute, damit auch cmd.exe (OEM-Codepage)
// sie unverändert weitergibt – die Messung „von außen“ startet die Probe über WMI und cmd.exe.
int wmain(int argc, wchar_t** argv) {
    SetConsoleOutputCP(CP_UTF8);
    // Wachhund: Eine hängende Messung soll den Lauf nicht anhalten (erster Lauf 2026-11-08 hing 5 min im Empfang).
    std::thread([] {
        std::this_thread::sleep_for(std::chrono::seconds(150));
        out("ergebnis=wachhund_abbruch");
        ExitProcess(3);
    }).detach();
    std::vector<std::string> args;
    for (int i = 1; i < argc; ++i) {
        args.push_back(narrow(argv[i]));
    }
    if (args.size() >= 3 && args[0] == "anlegen") {
        return cmd_create(widen(args[1]), args[2] == "tpm", true);
    }
    if (args.size() >= 3 && args[0] == "ohne-prov-info") {
        return cmd_create(widen(args[1]), args[2] == "tpm", false);
    }
    if (args.size() >= 2 && args[0] == "nachsehen") {
        return cmd_check(widen(args[1]));
    }
    if (args.size() >= 2 && args[0] == "entfernen") {
        return cmd_delete(widen(args[1]));
    }
    if (args.size() >= 5 && args[0] == "verbinden") {
        return cmd_connect(std::vector<std::string>(args.begin() + 1, args.end()));
    }
    std::fprintf(stderr, "Aufruf siehe Kopfkommentar\n");
    return 2;
}
