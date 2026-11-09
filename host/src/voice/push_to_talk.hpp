// Haltetaste zum Sprechen mit dem Begleiter (Wunsch 2026-11-04). Solange die Taste gedrückt
// ist, nimmt die App das Mikrofon auf; beim Loslassen geht der Satz an die Erkennung.
//
// Warum ein Low-Level-Hook (WH_MOUSE_LL/WH_KEYBOARD_LL) statt RegisterHotKey: RegisterHotKey meldet nur das Drücken,
// nie das Loslassen, und kennt keine Maus-Seitentasten. Der Hook sieht beides auch, während Minecraft im Vordergrund
// ist. Er schluckt nichts (CallNextHookEx): Die Taste kommt weiter im Spiel an – deshalb als Vorgabe die hintere
// Maus-Seitentaste, die Minecraft von Haus aus nicht belegt.
//
// Threads: Der Hook braucht eine Nachrichtenschleife im installierenden Thread – PushToTalk hat dafür einen eigenen.
// on_change läuft in diesem Thread, muss also kurz sein. Höchstens EIN PushToTalk zugleich (die Hook-Rückrufe sind
// statisch). Fehler: start liefert false mit Grund.

#pragma once

#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

namespace swbh::voice {

struct TalkKey {
    enum class Kind { Mouse, Keyboard };
    Kind kind = Kind::Mouse;
    // Maus: 1 = XBUTTON1 (hinten), 2 = XBUTTON2 (vorn), 3 = Mitte. Tastatur: virtueller Tastencode (VK_*).
    int code = 1;
};

// „maus4“ (hintere Seitentaste, Vorgabe), „maus5“, „mausmitte“, ein Buchstabe oder eine Ziffer („v“, „7“), „f1“ … „f24“.
// Groß-/Kleinschreibung egal. nullopt = unbekannt.
std::optional<TalkKey> parse_talk_key(std::string_view text);
// Für Anzeige und Konsole: „Maus-Seitentaste hinten“, „Taste V“, „F8“.
std::string talk_key_title(const TalkKey& key);
inline constexpr std::string_view kDefaultTalkKey = "maus4";

// Wandelt die rohen Tastenereignisse in Flanken: Die Tastatur meldet beim Halten Wiederholungen („gedrückt“ mehrmals),
// die Maus nicht. Liefert true/false nur bei echter Änderung.
class PressEdge final {
public:
    std::optional<bool> feed(bool down) {
        if (down == down_) {
            return std::nullopt;
        }
        down_ = down;
        return down;
    }
    bool down() const { return down_; }

private:
    bool down_ = false;
};

class PushToTalk final {
public:
    using ChangeFn = std::function<void(bool pressed)>;

    PushToTalk();
    ~PushToTalk();
    PushToTalk(const PushToTalk&) = delete;
    PushToTalk& operator=(const PushToTalk&) = delete;

    bool start(const TalkKey& key, ChangeFn on_change, std::string& error);
    void stop();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace swbh::voice
