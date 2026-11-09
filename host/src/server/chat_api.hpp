// OpenAI-kompatible Teilmenge für das Plugin: GET /health und POST /v1/chat/completions.
//
// Antwortformat wie llama-server b10900 (choices[0].message.content, usage, timings.predicted_n,
// timings.predicted_per_second), damit LlmClient.java im Plugin unverändert bleibt – es liest genau diese Felder.
// Fehler ebenfalls wie llama-server: {"error":{"code":…,"message":…,"type":…}}.
//
// Ein Auftrag gleichzeitig: Ein zweiter bekommt sofort 503 mit Retry-After (das Modell rechnet ohnehin seriell, und
// eine Warteschlange würde den Zeitbegriff des Plugins – Timeout ab Absenden – verfälschen). ASVS V11.1.4
// (Begrenzung gegen Überlast).
//
// Bricht die Verbindung während der Erzeugung ab (Timeout im Plugin, Tunnel weg), wird die Erzeugung abgebrochen,
// statt minutenlang für niemanden weiterzurechnen.
//
// Threads: handle() darf aus mehreren Verbindungs-Threads gleichzeitig kommen; nur einer erreicht das Modell.
// Beobachter-Aufrufe kommen aus diesen Threads.
// Fehler: alles als HTTP-Antwort, keine Ausnahmen nach außen.

#pragma once

#include "base/json.hpp"
#include "llm/engine.hpp"
#include "server/http.hpp"

#include <atomic>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::server {

// Höchstwerte der Anfrage. max_tokens wie im Plugin (Settings.java klemmt auf 256 … 8192).
inline constexpr int kMaxMessages = 64;
inline constexpr int kMaxTokensLimit = 8192;
inline constexpr int kDefaultMaxTokens = 3000;

// Das Modell hinter der Schnittstelle (LlamaEngine in der App, Attrappe in den Tests).
class CompletionBackend {
public:
    virtual ~CompletionBackend() = default;
    virtual bool ready() const = 0;
    virtual std::string model_name() const = 0;
    virtual std::optional<llm::CompletionResult> complete(const llm::CompletionRequest& request, std::string& error) = 0;
    virtual void cancel() = 0;
};

struct RequestInfo {
    std::string summary;  // Anfang der letzten Nutzer-Nachricht (fürs Protokoll gekürzt)
    std::string order;    // Auftragstext des Plugins (order_text), leer ohne „Bauauftrag:“
    int messages = 0;
    int max_tokens = 0;
    double temperature = 0.0;
    std::string grammar;  // "bauplan" oder "frei"
};

struct FinishInfo {
    bool ok = false;
    std::string error;
    llm::CompletionResult result;
};

// Ereignisse für die Live-Konsole. Alle Funktionen dürfen nicht blockieren.
class ApiObserver {
public:
    virtual ~ApiObserver() = default;
    virtual void request_started(const RequestInfo& info) = 0;
    virtual void token(std::string_view piece) = 0;
    virtual void request_finished(const FinishInfo& info) = 0;
};

struct ApiResponse {
    int status = 200;
    std::string content_type = "application/json; charset=utf-8";
    std::string body;
    std::vector<std::string> extra_headers;
};

// Anfragekörper → CompletionRequest (ohne Grammatik; die wählt plan::choose_grammar). nullopt + snake_case-Code bei
// ungültigem Inhalt. Öffentlich für die Tests.
std::optional<llm::CompletionRequest> parse_chat_body(const Json& body, std::string& error_code);

// Auftragstext aus dem Verlauf: die ERSTE Nutzer-Nachricht, die mit „Bauauftrag:“ beginnt (BuildService.userMessage),
// ohne den Vorspann und nur bis zum ersten Zeilenumbruch – dahinter steht die Umgebung für das Modell. Gekürzt und
// entschärft wie das Protokoll (log::safe, 160). Leer, wenn keine Nachricht so beginnt.
//
// Warum nicht die letzte Nutzer-Nachricht (summary): In der Reparaturrunde des Plugins ist das „The plan is invalid:
// … Return the complete corrected plan as JSON.“ (BuildService.java, Reparaturschleife) – Vorschau und Meldung
// zeigten dann den Reparaturtext als Auftrag (Prüfbefund 2026-10-29). Und getrennt wird VOR log::safe am
// Zeilenumbruch, weil log::safe „\n\n“ zu zwei Leerzeichen macht und ein Schnitt dort auch „ein  Haus“ (zwei
// Leerzeichen, so getippt) zu „ein“ kürzte.
std::string order_text(const std::vector<llm::ChatMessage>& messages);

// Vergleichsschlüssel für einen Auftragstext ohne Vorspann: bis zum ersten Zeilenumbruch, Leerraum außen weg,
// log::safe(…, 160). order_text ruft sie hinter „Bauauftrag:“ auf, handle_result (result_api) für „auftrag“ –
// so gilt order_key(p) == order_text({{"user", "Bauauftrag: " + p}}) für jeden Text p des Plugins.
//
// Warum eine gemeinsame Funktion: Vorher kürzte result_api den Auftrag selbst auf 200 BYTE ohne log::safe, order_text
// auf 160 Codepunkte mit log::safe. Ab 161 Zeichen (das Plugin schickt bis 300), ab 101 Umlauten oder mit einem Tab
// passten die Texte nicht mehr, und JobTracker::note_result legte das Ergebnis als eigene Zeile „ohne LLM“ ab; die
// LLM-Zeile blieb für immer bei „–“. Wer hier eine zweite Kürzung einbaut, bekommt das wieder.
std::string order_key(std::string_view raw);

// Antwort im Format von llama-server. Öffentlich für die Tests.
std::string chat_response_json(const llm::CompletionResult& result, const std::string& model, const std::string& id,
                               long long created);

// Fehlerkörper wie llama-server.
std::string error_json(int status, std::string_view message);

class ChatApi {
public:
    ChatApi(CompletionBackend& backend, ApiObserver* observer);

    // client_gone: true, sobald die Gegenseite die Verbindung geschlossen hat (der Server prüft das am Socket).
    ApiResponse handle(const HttpRequest& request, const std::function<bool()>& client_gone);

    bool busy() const { return busy_.load(); }

private:
    ApiResponse chat(const HttpRequest& request, const std::function<bool()>& client_gone);

    CompletionBackend& backend_;
    ApiObserver* observer_;
    std::atomic<bool> busy_{false};
};

}  // namespace swbh::server
