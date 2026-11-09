// Einordnung von WinHTTP-Fehlern und TLS-Ursachen (siehe tls_diag.hpp).

#include "win/tls_diag.hpp"

#include "base/utf8.hpp"

#include <windows.h>
#include <winhttp.h>

namespace swbh::win {

HttpError::Kind classify_winhttp_error(unsigned long code, bool body_started) {
    switch (code) {
    case ERROR_WINHTTP_TIMEOUT:
        return HttpError::Kind::Timeout;
    case ERROR_WINHTTP_NAME_NOT_RESOLVED:
        return HttpError::Kind::Resolve;
    case ERROR_WINHTTP_CANNOT_CONNECT:
        return HttpError::Kind::Connect;
    case ERROR_WINHTTP_CONNECTION_ERROR:
        return body_started ? HttpError::Kind::Protocol : HttpError::Kind::Connect;
    case ERROR_WINHTTP_OPERATION_CANCELLED:
        return HttpError::Kind::Cancelled;
    case ERROR_WINHTTP_SECURE_FAILURE:
    case ERROR_WINHTTP_SECURE_CERT_DATE_INVALID:
    case ERROR_WINHTTP_SECURE_CERT_CN_INVALID:
    case ERROR_WINHTTP_SECURE_INVALID_CA:
    case ERROR_WINHTTP_SECURE_CERT_REV_FAILED:
    case ERROR_WINHTTP_SECURE_CHANNEL_ERROR:
    case ERROR_WINHTTP_SECURE_INVALID_CERT:
    case ERROR_WINHTTP_SECURE_CERT_REVOKED:
    case ERROR_WINHTTP_SECURE_CERT_WRONG_USAGE:
    case ERROR_WINHTTP_CLIENT_AUTH_CERT_NEEDED:
    case ERROR_WINHTTP_CLIENT_AUTH_CERT_NEEDED_PROXY:
    case ERROR_WINHTTP_SECURE_FAILURE_PROXY:
    case ERROR_WINHTTP_CLIENT_CERT_NO_PRIVATE_KEY:
    case ERROR_WINHTTP_CLIENT_CERT_NO_ACCESS_PRIVATE_KEY:
        return HttpError::Kind::Tls;
    case ERROR_WINHTTP_INVALID_SERVER_RESPONSE:
    case ERROR_WINHTTP_HEADER_NOT_FOUND:
    case ERROR_WINHTTP_INVALID_HEADER:
    case ERROR_WINHTTP_HEADER_COUNT_EXCEEDED:
    case ERROR_WINHTTP_HEADER_SIZE_OVERFLOW:
    case ERROR_WINHTTP_CHUNKED_ENCODING_HEADER_SIZE_OVERFLOW:
    case ERROR_WINHTTP_RESPONSE_DRAIN_OVERFLOW:
    case ERROR_WINHTTP_HTTP_PROTOCOL_MISMATCH:
        return HttpError::Kind::Protocol;
    case ERROR_WINHTTP_REDIRECT_FAILED:
        return HttpError::Kind::Redirect;
    // Kaputte PAC-Skripte oder Auto-Proxy-Dienst: Die Verbindung kommt nicht zustande.
    case ERROR_WINHTTP_AUTODETECTION_FAILED:
    case ERROR_WINHTTP_BAD_AUTO_PROXY_SCRIPT:
    case ERROR_WINHTTP_UNABLE_TO_DOWNLOAD_SCRIPT:
    case ERROR_WINHTTP_UNHANDLED_SCRIPT_TYPE:
    case ERROR_WINHTTP_SCRIPT_EXECUTION_ERROR:
    case ERROR_WINHTTP_AUTO_PROXY_SERVICE_ERROR:
        return HttpError::Kind::Connect;
    default:
        return HttpError::Kind::Other;
    }
}

std::string winhttp_error_text(unsigned long code) {
    std::string text;
    // WinHTTP-Codes (12xxx) stehen in winhttp.dll, nicht in der Systemtabelle.
    const HMODULE module = GetModuleHandleW(L"winhttp.dll");
    for (const DWORD source : {static_cast<DWORD>(FORMAT_MESSAGE_FROM_HMODULE), static_cast<DWORD>(FORMAT_MESSAGE_FROM_SYSTEM)}) {
        if (source == FORMAT_MESSAGE_FROM_HMODULE && module == nullptr) {
            continue;
        }
        wchar_t* buffer = nullptr;
        const DWORD length =
            FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_IGNORE_INSERTS | source,
                           source == FORMAT_MESSAGE_FROM_HMODULE ? module : nullptr, code, 0,
                           reinterpret_cast<wchar_t*>(&buffer), 0, nullptr);
        if (length > 0 && buffer != nullptr) {
            std::wstring_view view(buffer, length);
            while (!view.empty() && (view.back() == L'\r' || view.back() == L'\n' || view.back() == L' ' ||
                                     view.back() == L'.')) {
                view.remove_suffix(1);
            }
            text = narrow(view);
        }
        if (buffer != nullptr) {
            LocalFree(buffer);
        }
        if (!text.empty()) {
            break;
        }
    }
    if (text.empty()) {
        return "WinHTTP-Fehler " + std::to_string(code);
    }
    return text + " (WinHTTP " + std::to_string(code) + ")";
}

std::string tls_failure_reason(unsigned long tls_flags) {
    // Reihenfolge nach Aussagekraft für den Nutzer: Uhrzeit/Ablauf und fremde CA
    // (typisch Virenscanner mit TLS-Prüfung) zuerst.
    struct Named {
        unsigned long bit;
        const char* text;
    };
    static constexpr Named kNames[] = {
        {WINHTTP_CALLBACK_STATUS_FLAG_CERT_DATE_INVALID, "abgelaufen oder noch nicht gültig"},
        {WINHTTP_CALLBACK_STATUS_FLAG_INVALID_CA, "unbekannte Zertifizierungsstelle"},
        {WINHTTP_CALLBACK_STATUS_FLAG_CERT_CN_INVALID, "ausgestellt für einen anderen Namen"},
        {WINHTTP_CALLBACK_STATUS_FLAG_CERT_REVOKED, "widerrufen"},
        {WINHTTP_CALLBACK_STATUS_FLAG_CERT_REV_FAILED, "Widerrufsprüfung gescheitert"},
        {WINHTTP_CALLBACK_STATUS_FLAG_INVALID_CERT, "ungültiges Zertifikat"},
        {WINHTTP_CALLBACK_STATUS_FLAG_CERT_WRONG_USAGE, "nicht für Serverauthentifizierung zugelassen"},
        {WINHTTP_CALLBACK_STATUS_FLAG_SECURITY_CHANNEL_ERROR, "TLS-Aushandlung gescheitert"},
    };
    std::string text;
    for (const auto& n : kNames) {
        if ((tls_flags & n.bit) != 0) {
            if (!text.empty()) {
                text += "; ";
            }
            text += n.text;
        }
    }
    if (text.empty()) {
        return "Zertifikat konnte nicht geprüft werden";
    }
    return text;
}

}  // namespace swbh::win
