// Vorlesen der Begleiter-Sätze über die Sprachausgabe von Windows (SAPI 5). Gewählt, weil sie ohne neue Abhängigkeit
// auf jedem Windows mitkommt; deutsche Stimmen (Hedda, Katja, Stefan) bringt das deutsche Sprachpaket mit. Ein
// eigenes TTS-Modell (Piper o. ä.) hieße ONNX-Laufzeit, Phonemizer und Modelldownload – erst, wenn SAPI nicht reicht.
//
// Ein neuer Satz unterbricht den laufenden (SPF_PURGEBEFORESPEAK): Der Begleiter soll auf die letzte Frage antworten,
// nicht eine Warteschlange alter Antworten abarbeiten.
//
// Threads: Ein eigener Thread hält die COM-Objekte (Multithreaded Apartment) und spricht; say/stop sind aus jedem
// Thread aufrufbar. Fehler: open liefert nullptr mit Grund; say/stop werfen nicht.

#pragma once

#include "voice/audio.hpp"

#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace swbh::voice {

// Satz in den Speicher sprechen statt auf die Lautsprecher (22,05 kHz mono) – für die Ausgabe im Spiel über Simple
// Voice Chat und als Rückfall, wenn Piper fehlt. Blockiert bis zum Ende des Satzes; eigener COM-Zugang je Aufruf.
std::optional<Pcm> sapi_synth(std::string_view text, std::string_view voice_hint, std::string& error);

class Speaker final {
public:
    // voice_hint: Teil des Stimmnamens („Katja“); leer = erste deutsche Stimme, sonst die Standardstimme.
    static std::unique_ptr<Speaker> open(std::string_view voice_hint, std::string& error);
    ~Speaker();
    Speaker(const Speaker&) = delete;
    Speaker& operator=(const Speaker&) = delete;

    // Spricht text (UTF-8, ohne SSML: SPF_IS_NOT_XML). Ein laufender Satz bricht ab.
    void say(std::string_view text);
    void stop();
    // 0 … 100. Im Test 0, damit der Testlauf nicht spricht.
    void set_volume(int volume);
    // Name der gewählten Stimme („Microsoft Katja – German (Germany)“).
    std::string voice_name() const;
    // true, wenn die gewählte Stimme Deutsch spricht.
    bool german() const;
    // Wartet, bis alles gesprochen ist (höchstens ms). Für Tests.
    bool wait_idle(int ms);

    // Installierte Stimmen (Namen), deutsche zuerst.
    static std::vector<std::string> voices();

private:
    struct Impl;
    explicit Speaker(std::unique_ptr<Impl> impl);
    std::unique_ptr<Impl> impl_;
};

}  // namespace swbh::voice
