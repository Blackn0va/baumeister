#include "gui/companion_wiring.hpp"

#include <utility>

namespace swbh::gui {

std::optional<std::string> forward_companion_name(voice::SpeechInput& speech, ConsoleThrottle& throttle,
                                                  const std::string& name,
                                                  std::chrono::steady_clock::time_point now) {
    // Jedes Abholen (≤ 20 s) meldet den Namen. set_companion_name legt ihn nur ab (eigener kleiner Mutex) – vorher
    // nahm diese Stelle speech_apply_mutex_, den apply_speech über stop() samt join hält, und die Abholung hing so
    // lange (Prüfbefund 2026-11-21). Wer hier wieder auf apply_speech wartet, hält damit den Long-Poll des Plugins auf.
    if (!speech.set_companion_name(name)) {
        return std::nullopt;
    }
    // Nur bei einem Wechsel und höchstens eine Zeile je Minute: Ein Client, der abwechselnd Namen schickt, schrieb
    // sonst je Abholung eine Zeile. name ist geprüft (server::valid_companion_name: nur Buchstaben und Leerzeichen).
    const std::optional<std::size_t> skipped = throttle.allow(now);
    if (!skipped) {
        return std::nullopt;
    }
    const std::string more = *skipped > 0 ? " (" + std::to_string(*skipped) + " Wechsel davor nicht gemeldet)" : "";
    return "Begleitername vom Plugin: „" + name + "“ – die Spracherkennung achtet auf diesen Namen" + more + ".";
}

server::CompanionHandlers companion_handlers(CompanionHooks& hooks, voice::SpeechInput& speech,
                                             ConsoleThrottle& throttle) {
    server::CompanionHandlers h;
    h.say = [&hooks](const server::Spoken& s) { hooks.companion_said(s); };
    h.hear = [&hooks](const std::string& p, std::vector<float> pcm, std::string& e) {
        return hooks.hear_from_game(p, std::move(pcm), e);
    };
    h.voice = [&hooks](const std::string& t, std::string& e) { return hooks.voice_for_game(t, e); };
    h.in_game = [&hooks](const std::vector<std::string>& names) { hooks.game_voice(names); };
    h.companion_name = [&hooks, &speech, &throttle](const std::string& name) {
        if (const std::optional<std::string> line =
                forward_companion_name(speech, throttle, name, std::chrono::steady_clock::now())) {
            hooks.companion_name_line(*line);
        }
    };
    return h;
}

}  // namespace swbh::gui
