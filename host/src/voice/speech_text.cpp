#include "voice/speech_text.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>

namespace swbh::voice {

namespace {

struct Spelling {
    std::string_view key;  // klein, ASCII
    std::string_view say;
    bool prefix;  // auch als Wortanfang („Spawner“, „Chunks“); false = nur ganzes Wort oder Plural-s
    // Nur mit großem Anfangsbuchstaben (Substantiv): „Lag“ ja, das deutsche „lag“ (von liegen) nicht.
    bool capital = false;
};

// Gemessen 2026-11-04 (probes/piper_aussprache_probe.py, Thorsten medium): links das Wort, rechts die Umschreibung,
// deren Phoneme der üblichen Aussprache deutscher Spieler entsprechen. Nicht aufgenommen, weil espeak sie schon richtig
// liest: Server, okay, Hey, Golem, Elytra, Shulker, Piglin, Stronghold, Craftingtisch. Gemessene Fehlgriffe, die
// NICHT wieder hinein dürfen: „S-pohn“ (liest den Buchstaben S), „Redstoun“ (ou → uː), „Kri-eitiv“.
// Reihenfolge: längere Schlüssel mit gleichem Anfang zuerst (respawn vor spawn ist unnötig – eigener Wortanfang).
constexpr std::array kSpellings{
    Spelling{"creeper", "Krieper", true},       Spelling{"redstone", "Rädstohn", true},
    Spelling{"netherite", "Nethereit", true},   Spelling{"enderman", "Endermän", true},
    Spelling{"endermen", "Endermän", true},     Spelling{"zombie", "Zombi", true},
    Spelling{"skeleton", "Skelett", true},      Spelling{"respawn", "Rispohn", true},
    Spelling{"spawn", "Spohn", true},           Spelling{"cobblestone", "Kobbelstohn", true},
    Spelling{"minecraft", "Mainkraft", true},   Spelling{"villager", "Willätscher", true},
    Spelling{"wither", "Wisser", true},         Spelling{"slime", "Slaim", true},
    Spelling{"blaze", "Bleihs", true},          Spelling{"beacon", "Bihken", true},
    Spelling{"chunk", "Tschank", true},         Spelling{"survival", "Sörweiwel", true},
    Spelling{"creative", "Kriehtiv", true},     Spelling{"gamemode", "Spielmodus", false},
    Spelling{"pickaxe", "Spitzhacke", false},   Spelling{"diamond", "Diamant", false},
    Spelling{"warden", "Wohrden", false},       Spelling{"dungeon", "Dandschen", true},
    Spelling{"biome", "Biom", false},           Spelling{"plot", "Plott", false},
    Spelling{"home", "Hohm", false},            Spelling{"sword", "Schwert", false},
    Spelling{"shield", "Schild", false},        Spelling{"torch", "Fackel", false},
    Spelling{"iron", "Eisen", false},           Spelling{"ore", "Erz", false},
    // Gemessen 2026-11-04 22:45 (dieselbe Probe, Thorsten medium), seit dem Abenteuer sagt Vera diese Wörter ständig:
    // Nether nˈeːtɜ („Neeter“) → Näther nˈɛtɜ; Hardcore hˈaɾtkoːrə → Haadkohr hˈɑːtkoːɾ; Enderauge ˈɛndeːrˌaʊɡə
    // („Endeer-auge“) → Ender-Auge ˈɛndərˈaʊɡə. „nether“ MUSS hinter „netherite“ stehen (erster Treffer gewinnt),
    // sonst würde Netherite zu „Nätherite“.
    Spelling{"nether", "Näther", true},         Spelling{"hardcore", "Haadkohr", true},
    Spelling{"enderauge", "Ender-Auge", true},
    // Gemessen 2026-11-16 (dieselbe Probe, Thorsten medium; vorher stand „Mob“ oben bei „liest espeak richtig“ –
    // falsch): Mob mˈoːp, Mobs mˈoːps (langes o) → Mobb mˈɔpp, Mobbs mˈɔps; Lag lˈɑːk (wie das deutsche „lag“) →
    // Läg lˈɛːk.
    // Auf dev01 am 2026-11-15 genauso (SpeechText.java). „Lag“ nur großgeschrieben: „der Stein lag dort“ bleibt.
    Spelling{"mobs", "Mobbs", false},           Spelling{"mob", "Mobb", false},
    Spelling{"lags", "Lägs", false, true},      Spelling{"lag", "Läg", false, true},
};

// Abkürzungen mit Punkt: piper teilt Sätze am Punkt – „ca. 20 Blöcke“ wurde zu „ca“ und Pause (gemessen).
constexpr std::array<std::array<std::string_view, 2>, 12> kAbbreviations{{
    {"z. B.", "zum Beispiel"}, {"z.B.", "zum Beispiel"}, {"d. h.", "das heißt"}, {"d.h.", "das heißt"},  // umlaut-ok
    {"u. a.", "unter anderem"}, {"u.a.", "unter anderem"}, {"bzw.", "beziehungsweise"}, {"usw.", "und so weiter"},
    {"ca.", "circa"}, {"Nr.", "Nummer"}, {"evtl.", "eventuell"}, {"ggf.", "gegebenenfalls"},
}};

bool is_digit(char c) {
    return c >= '0' && c <= '9';
}

bool is_ascii_letter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
}

// Teil eines Wortes: ASCII-Buchstabe oder ein Byte aus UTF-8 (ä, ö, ü, ß – Emojis sind vorher entfernt).
bool is_word_byte(char c) {
    return is_ascii_letter(c) || (static_cast<unsigned char>(c) >= 0x80);
}

char lower(char c) {
    return (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c;
}

// Emojis, Bildsymbole, Variationswähler und Joiner fallen weg: espeak liest sonst ihren Unicode-Namen vor.
bool is_pictograph(std::uint32_t cp) {
    return (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x2B00 && cp <= 0x2BFF) ||
           cp == 0xFE0F || cp == 0x200D || (cp >= 0x1F1E6 && cp <= 0x1F1FF);
}

std::string drop_pictographs(std::string_view s) {
    std::string out;
    out.reserve(s.size());
    std::size_t i = 0;
    while (i < s.size()) {
        const auto b = static_cast<unsigned char>(s[i]);
        std::size_t len = 1;
        std::uint32_t cp = b;
        if (b >= 0xF0 && i + 3 < s.size()) {
            len = 4;
            cp = ((b & 0x07u) << 18) | ((static_cast<unsigned char>(s[i + 1]) & 0x3Fu) << 12) |
                 ((static_cast<unsigned char>(s[i + 2]) & 0x3Fu) << 6) | (static_cast<unsigned char>(s[i + 3]) & 0x3Fu);
        } else if (b >= 0xE0 && i + 2 < s.size()) {
            len = 3;
            cp = ((b & 0x0Fu) << 12) | ((static_cast<unsigned char>(s[i + 1]) & 0x3Fu) << 6) |
                 (static_cast<unsigned char>(s[i + 2]) & 0x3Fu);
        } else if (b >= 0xC0 && i + 1 < s.size()) {
            len = 2;
            cp = ((b & 0x1Fu) << 6) | (static_cast<unsigned char>(s[i + 1]) & 0x3Fu);
        }
        if (!is_pictograph(cp)) {
            out.append(s.substr(i, len));
        }
        i += len;
    }
    return out;
}

void replace_all(std::string& s, std::string_view from, std::string_view to) {
    std::size_t at = 0;
    while ((at = s.find(from, at)) != std::string::npos) {
        // Nur am Wortanfang: „Mexico.“ enthält kein „ca.“ im Sinne der Abkürzung.
        if (at > 0 && is_word_byte(s[at - 1])) {
            ++at;
            continue;
        }
        s.replace(at, from.size(), to);
        at += to.size();
    }
}

// Nächstes Zeichen ungleich Leerzeichen vor bzw. nach pos (oder '\0').
char prev_solid(const std::string& s, std::size_t pos) {
    while (pos > 0) {
        --pos;
        if (s[pos] != ' ') {
            return s[pos];
        }
    }
    return '\0';
}

char next_solid(const std::string& s, std::size_t pos) {
    for (++pos; pos < s.size(); ++pos) {
        if (s[pos] != ' ') {
            return s[pos];
        }
    }
    return '\0';
}

// „6:00“ → „6 Uhr“, „14:30 Uhr“ → „14 Uhr 30“ (ein folgendes „Uhr“ fällt weg).
std::string clock_times(const std::string& s) {
    std::string out;
    std::size_t i = 0;
    while (i < s.size()) {
        std::size_t h = i;
        while (h < s.size() && is_digit(s[h]) && h - i < 3) {
            ++h;
        }
        const std::size_t hours = h - i;
        const bool start_ok = i == 0 || !is_digit(s[i - 1]);
        if (start_ok && (hours == 1 || hours == 2) && h + 2 < s.size() && s[h] == ':' &&
            is_digit(s[h + 1]) && is_digit(s[h + 2]) && (h + 3 >= s.size() || !is_digit(s[h + 3]))) {
            const int hh = std::stoi(s.substr(i, hours));
            const int mm = (s[h + 1] - '0') * 10 + (s[h + 2] - '0');
            if (hh <= 24 && mm <= 59) {
                std::string rest_minutes = mm == 0 ? std::string() : " " + std::to_string(mm);
                out += std::to_string(hh) + " Uhr" + rest_minutes;
                std::size_t next = h + 3;
                if (s.compare(next, 4, " Uhr") == 0 && (next + 4 >= s.size() || !is_word_byte(s[next + 4]))) {
                    next += 4;
                }
                i = next;
                continue;
            }
        }
        // Verhältnis „1:1“, „2:3“: eine Ziffer hinter dem Doppelpunkt ist keine Uhrzeit. Gemessen 2026-11-16 (Thorsten
        // medium): „Verhältnis 1:1“ → „ˈaɪns ˈaɪns“, mit „zu“ → „ˈaɪns tsuː ˈaɪns“. Wie SpeechText.java (RATIO).
        if (start_ok && (hours == 1 || hours == 2) && h + 1 < s.size() && s[h] == ':' && is_digit(s[h + 1]) &&
            (h + 2 >= s.size() || !is_digit(s[h + 2]))) {
            out += s.substr(i, hours) + " zu " + s[h + 1];
            i = h + 2;
            continue;
        }
        out += s[i];
        ++i;
    }
    return out;
}

// Einheit hinter Schrägstrich: „440 Blöcke/s“ → „440 Blöcke pro Sekunde“, „km/h“ → „km pro Stunde“. Gemessen
// 2026-11-16 (Thorsten medium): „Blöcke/s“ → „blˈœkə ˈɛs“ („Es“), „Stück/min“ → „ʃtˈyk mˈiːn“. Wie SpeechText.java
// (PER_UNIT), dazu nach einer Ziffer („440/s“).
std::string per_unit(const std::string& s) {
    struct Unit {
        std::string_view key;
        std::string_view say;
    };
    constexpr std::array kUnits{Unit{"sek", " pro Sekunde"}, Unit{"s", " pro Sekunde"}, Unit{"min", " pro Minute"},
                                Unit{"std", " pro Stunde"},  Unit{"h", " pro Stunde"},   Unit{"tag", " pro Tag"},
                                Unit{"Tag", " pro Tag"}};
    std::string out;
    std::size_t i = 0;
    while (i < s.size()) {
        if (s[i] == '/' && i > 0 && (is_word_byte(s[i - 1]) || is_digit(s[i - 1]))) {
            bool done = false;
            for (const Unit& u : kUnits) {
                const std::size_t end = i + 1 + u.key.size();
                if (s.compare(i + 1, u.key.size(), u.key) == 0 &&
                    (end >= s.size() || (!is_word_byte(s[end]) && !is_digit(s[end])))) {
                    out += u.say;
                    i = end;
                    done = true;
                    break;
                }
            }
            if (done) {
                continue;
            }
        }
        out += s[i];
        ++i;
    }
    return out;
}

// Rechenzeichen zwischen Zahlen, Gleichheitszeichen, Dezimalpunkt, „16x“.
std::string arithmetic(const std::string& s) {
    std::string out;
    for (std::size_t i = 0; i < s.size(); ++i) {
        const char c = s[i];
        const bool between = is_digit(prev_solid(s, i)) && is_digit(next_solid(s, i));
        if (c == '=') {
            out += " ist ";
        } else if (between && (c == '*' || c == 'x' || c == 'X')) {
            out += " mal ";
        } else if (between && c == '/') {
            out += " geteilt durch ";
        } else if (between && c == '^') {
            out += " hoch ";
        } else if (between && c == '+') {
            out += " plus ";
        } else if (between && c == '-' && i > 0 && s[i - 1] == ' ') {
            out += " minus ";  // „5 - 3“; „10-15 Blöcke“ ohne Leerzeichen bleibt ein Bereich
        } else if (c == '.' && i > 0 && is_digit(s[i - 1]) && i + 1 < s.size() && is_digit(s[i + 1])) {
            // „1.000“ (deutsch Tausend) → „1000“; „3.5“ → „3,5“.
            std::size_t n = i + 1;
            while (n < s.size() && is_digit(s[n])) {
                ++n;
            }
            if (n - (i + 1) != 3) {
                out += ',';
            }
        } else if ((c == 'x' || c == 'X') && i > 0 && is_digit(s[i - 1]) && (i + 1 >= s.size() || s[i + 1] == ' ')) {
            // „16x Fackel“ → „16 Fackel“ (gemessen: sonst „sechzehn iks“)
        } else if (c == '_') {
            out += ' ';  // „redstone_block“ → „redstone block“
        } else if (c == '(' || c == ')') {
            // Klammern liest espeak nicht und macht keine Pause (gemessen 2026-11-16: „Rechnung (Quelle: Wiki) fertig“
            // ohne Pause, mit Kommas mit). squeeze_spaces räumt doppelte Kommas weg.
            out += ", ";
        } else if (c == '&') {
            out += " und ";  // espeak sagt „&“ betont (ˈʊnt), als Wort unbetont wie im Satz (gemessen 2026-11-16)
        } else if (c == '*' || c == '#' || c == '`' || c == '~') {
            // Markdown-Reste aus dem Modell: nicht mitsprechen
        } else {
            out += c;
        }
    }
    return out;
}

// Ziffern mitten im Namen („Blackkn0va“ → „Blackknova“): espeak las „Blackkn null va“ (gemessen).
std::string leet_names(std::string s) {
    for (std::size_t i = 1; i + 1 < s.size(); ++i) {
        if (!is_ascii_letter(s[i - 1]) || !is_ascii_letter(s[i + 1])) {
            continue;
        }
        switch (s[i]) {
            case '0': s[i] = 'o'; break;
            case '1': s[i] = 'i'; break;
            case '3': s[i] = 'e'; break;
            case '4': s[i] = 'a'; break;
            case '5': s[i] = 's'; break;
            case '7': s[i] = 't'; break;
            default: break;
        }
    }
    return s;
}

std::string spellings(const std::string& s) {
    std::string out;
    std::size_t i = 0;
    while (i < s.size()) {
        if (!is_word_byte(s[i])) {
            // „/fly“, „/stop“: Schrägstrich vor einem Befehl nicht mitsprechen.
            if (s[i] != '/' || i + 1 >= s.size() || !is_ascii_letter(s[i + 1]) || (i > 0 && is_word_byte(s[i - 1]))) {
                out += s[i];
            }
            ++i;
            continue;
        }
        std::size_t end = i;
        while (end < s.size() && is_word_byte(s[end])) {
            ++end;
        }
        const std::string_view word(s.data() + i, end - i);
        bool done = false;
        for (const Spelling& sp : kSpellings) {
            if (word.size() < sp.key.size()) {
                continue;
            }
            bool same = true;
            for (std::size_t k = 0; k < sp.key.size(); ++k) {
                if (lower(word[k]) != sp.key[k]) {
                    same = false;
                    break;
                }
            }
            const std::string_view rest = word.substr(sp.key.size());
            const bool fits = rest.empty() || (sp.prefix ? true : (rest == "s" || rest == "S"));
            const bool case_ok = !sp.capital || (word[0] >= 'A' && word[0] <= 'Z');
            if (same && fits && case_ok) {
                out += sp.say;
                out += rest;
                done = true;
                break;
            }
        }
        if (!done) {
            out += word;
        }
        i = end;
    }
    return out;
}

std::string squeeze_spaces(const std::string& s) {
    std::string out;
    for (const char c : s) {
        if (c == ' ' && (out.empty() || out.back() == ' ')) {
            continue;
        }
        if ((c == ',' || c == '.' || c == '!' || c == '?') && !out.empty() && out.back() == ' ') {
            out.pop_back();
        }
        // Aus Klammern entstehen Kommas am Anfang, doppelt („a (b), c“) oder vor dem Satzende („a (b).“).
        if (c == ',' && (out.empty() || out.back() == ',')) {
            continue;
        }
        if ((c == '.' || c == '!' || c == '?') && !out.empty() && out.back() == ',') {
            out.pop_back();
        }
        out += c;
    }
    while (!out.empty() && (out.back() == ' ' || out.back() == ',')) {
        out.pop_back();
    }
    return out;
}

}  // namespace

std::string for_speech(std::string_view text) {
    std::string s = drop_pictographs(text);
    for (char& c : s) {
        if (c == '\n' || c == '\r' || c == '\t') {
            c = ' ';
        }
    }
    for (const auto& [from, to] : kAbbreviations) {
        replace_all(s, from, to);
    }
    // Zweiwortig, vor der Wortliste: „Crafting Table“ heißt auf Deutsch Werkbank.
    for (std::size_t at = 0; (at = s.find("rafting", at)) != std::string::npos; ++at) {
        if (at == 0 || lower(s[at - 1]) != 'c' || (at > 1 && is_word_byte(s[at - 2]))) {
            continue;
        }
        const std::size_t tail = at + 7;
        const std::string_view after(s.data() + tail, s.size() - tail);
        if (after.size() >= 6 && (after.substr(0, 6) == " table" || after.substr(0, 6) == " Table" ||
                                  after.substr(0, 6) == "_table")) {
            s.replace(at - 1, 1 + 7 + 6, "Werkbank");
        }
    }
    s = clock_times(s);
    s = per_unit(s);
    s = arithmetic(s);
    s = leet_names(std::move(s));
    s = spellings(s);
    return squeeze_spaces(s);
}

}  // namespace swbh::voice
