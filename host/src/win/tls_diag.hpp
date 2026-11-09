// Einordnung von WinHTTP-Fehlern (Architektur §9, Probe nat (b)).
//
// Gemessen: Jeder Zertifikatsfehler ergibt denselben Code 12175
// (ERROR_WINHTTP_SECURE_FAILURE); die Ursache steht nur in den Flags des
// WINHTTP_CALLBACK_STATUS_SECURE_FAILURE-Rückrufs (0x20 abgelaufen, 0x08
// unbekannte CA, 0x10 falscher Name). Der Transport sammelt die Flags und den
// Aussteller (WINHTTP_OPTION_SERVER_CERT_CONTEXT) in HttpError; hier liegt nur
// die reine, testbare Zuordnung.
//
// Threads: reentrant.

#pragma once

#include "win/http.hpp"

#include <string>

namespace swbh::win {

// Code → Art. body_started: Fehler beim Lesen des Bodys (12030 = Verbindung
// mitten im Body abgerissen → Protocol statt Connect).
HttpError::Kind classify_winhttp_error(unsigned long code, bool body_started);

// Windows-Text zum WinHTTP-Code aus winhttp.dll (Systemsprache) mit Code:
// "Zeitüberschreitung für den Vorgang (WinHTTP 12002)". Unbekannt: "WinHTTP-Fehler 12345".
std::string winhttp_error_text(unsigned long code);

}  // namespace swbh::win
