package com.blackn0va.baumeister.relay;

import java.nio.charset.StandardCharsets;

/**
 * Statuszeilen und die wenigen Antworten, die das Relay selbst schreibt.
 *
 * Den Status der App gibt das Relay unverändert weiter (docs/kanal-v1.md, Abschnitt 6), den Grundtext wählt es aus
 * dieser Liste; unbekannte Codes bekommen „Status“ – der Java-HttpClient des Plugins liest nur die Zahl.
 *
 * Threads: zustandslos. Fehler: keine.
 */
final class HttpStatus {

    private HttpStatus() {
    }

    static String phrase(int status) {
        return switch (status) {
            case 100 -> "Continue";
            case 101 -> "Switching Protocols";
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 206 -> "Partial Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 411 -> "Length Required";
            case 413 -> "Content Too Large";
            case 415 -> "Unsupported Media Type";
            case 417 -> "Expectation Failed";
            case 422 -> "Unprocessable Content";
            case 426 -> "Upgrade Required";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "Status";
        };
    }

    /**
     * Eigene Antwort des Relays ohne Inhalt: nur 4xx (HttpFailure erzwingt das), immer mit Connection: close.
     * extraHeader: fertige Kopfzeile ohne CRLF oder null.
     */
    static byte[] empty(int status, String extraHeader) {
        if (status < 400 || status > 499) {
            throw new IllegalArgumentException("Das Relay antwortet selbst nur mit 4xx: " + status);
        }
        String s = "HTTP/1.1 " + status + " " + phrase(status) + "\r\n"
                + (extraHeader == null ? "" : extraHeader + "\r\n")
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
