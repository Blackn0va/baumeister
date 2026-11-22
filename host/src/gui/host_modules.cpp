#include "gui/host_modules.hpp"

namespace swbh::gui {

namespace {

HostModule make(std::string id, std::string name, std::string description, std::string key, bool on,
                std::string effect) {
    HostModule m;
    m.id = std::move(id);
    m.name = std::move(name);
    m.description = std::move(description);
    m.key = std::move(key);
    m.on = on;
    m.effect = std::move(effect);
    m.state = on ? HostModuleState::Active : HostModuleState::Off;
    return m;
}

void plan_ai_state(HostModule& m, const HostModuleFacts& f) {
    // Zustand nach dem echten Modell, nicht nach dem Schalter: „KI einschalten“ lädt auch bei „beim Start: aus“.
    switch (f.model) {
    case LoadState::Ready:
        m.state = HostModuleState::Active;
        m.reason = m.on ? "Modell geladen" : "Modell geladen (beim Start: aus)";
        return;
    case LoadState::Busy:
        m.state = HostModuleState::Waiting;
        m.reason = f.downloading ? "Modell lädt herunter …" : "Modell lädt …";
        return;
    case LoadState::Failed:
        m.state = HostModuleState::Failed;
        m.reason = "Laden fehlgeschlagen – siehe Konsole";
        return;
    case LoadState::Off:
        m.state = m.on ? HostModuleState::Waiting : HostModuleState::Off;
        m.reason = m.on ? "nicht geladen – „KI einschalten“ lädt es" : "Stadt- und Weltbau laufen ohne KI";
        return;
    }
}

void speech_state(HostModule& m, const HostModuleFacts& f) {
    if (!m.on) {
        return;
    }
    switch (f.speech) {
    case LoadState::Ready: m.reason = "bereit"; return;
    case LoadState::Busy:
        m.state = HostModuleState::Waiting;
        m.reason = "Spracherkennung lädt …";
        return;
    case LoadState::Failed:
        m.state = HostModuleState::Failed;
        m.reason = f.speech_error.empty() ? std::string("Fehler – siehe Konsole") : f.speech_error;
        return;
    case LoadState::Off:
        // Gleicher Satz wie die Karte „Begleiter“: Ohne Minecraft-Namen startet apply_speech nicht.
        m.state = HostModuleState::Waiting;
        m.reason = "Minecraft-Name fehlt oder Erkennung lädt herunter";
        return;
    }
}

void link_state(HostModule& m, const AppConfig& c, const HostModuleFacts& f) {
    const std::string kind = c.relay_enabled    ? "Fernverbindung (HTTPS)"
                             : c.tunnel_enabled ? "SSH-Tunnel"
                                                : "nur dieser PC";
    m.on = f.server_running;
    if (!f.server_running) {
        m.state = HostModuleState::Off;
        m.reason = "getrennt – „Verbinden“ drücken";
        return;
    }
    if (!c.relay_enabled && !c.tunnel_enabled) {
        m.state = HostModuleState::Active;
        m.reason = kind;
        return;
    }
    switch (f.link) {
    case tunnel::State::Connected:
        m.state = HostModuleState::Active;
        m.reason = kind + " verbunden";
        return;
    case tunnel::State::Starting:
    case tunnel::State::Waiting:
    case tunnel::State::Stopped:
        m.state = HostModuleState::Waiting;
        m.reason = kind + (f.link_text.empty() ? std::string(" – baut auf …") : " – " + f.link_text);
        return;
    case tunnel::State::Failed:
        m.state = HostModuleState::Failed;
        m.reason = kind + (f.link_text.empty() ? std::string(" – Einstellungen prüfen") : " – " + f.link_text);
        return;
    }
}

}  // namespace

std::vector<HostModule> host_modules(const AppConfig& c, const HostModuleFacts& f) {
    std::vector<HostModule> out;

    HostModule ai = make("plan-ki", "Plan-KI", "Sprachmodell für Bauaufträge im Chat, auf der Grafikkarte dieses PCs.",
                         "modell_beim_start", c.load_model_on_start,
                         "An lädt ein vorhandenes Modell sofort (fehlt die Datei: „KI einschalten“), aus entlädt es. "
                         "Gespeichert als „beim Start laden“.");
    plan_ai_state(ai, f);
    out.push_back(std::move(ai));

    HostModule research = make("recherche", "Internet-Recherche",
                               "Echte Bauwerke vor dem Planen bei Wikipedia/Wikidata nachschlagen (fragt das Plugin).",
                               "research", c.research, "Wirkt sofort – das Plugin fragt vor jeder Recherche.");
    out.push_back(std::move(research));

    HostModule voice = make("stimme", "Stimme (Vorlesen)",
                            "Liest die Sätze des Begleiters vor (Piper, sonst Windows-Stimme).", "vorlesen",
                            c.speech_out, "Wirkt sofort.");
    if (voice.on && c.minecraft_name.empty()) {
        // Vorgelesen wird nur, was dem eingetragenen Spieler gilt (server::speak_for) – ohne Namen nichts.
        voice.state = HostModuleState::Waiting;
        voice.reason = "Minecraft-Name fehlt (Einstellungen)";
    }
    out.push_back(std::move(voice));

    HostModule mic = make("mikrofon", "Mikrofon (Sprechen)",
                          "Mit dem Begleiter sprechen: Taste halten, sprechen, loslassen.", "sprechen", c.speech_in,
                          "Wirkt sofort; beim ersten Einschalten lädt die Spracherkennung (574 MB).");
    speech_state(mic, f);
    out.push_back(std::move(mic));

    HostModule others = make("mitspieler", "Mitspieler verstehen",
                             "Sprache von Mitspielern aus Simple Voice Chat hier erkennen.", "fremde_verstehen",
                             c.hear_others, "Wirkt sofort.");
    if (others.on && f.speech != LoadState::Ready) {
        // Erkannt wird mit derselben Spracherkennung wie beim eigenen Mikrofon (Controller::hear_from_game).
        others.state = HostModuleState::Waiting;
        others.reason = "braucht die Spracherkennung (Modul Mikrofon)";
    }
    out.push_back(std::move(others));

    HostModule city = make("stadt-vorschau", "Stadt-Vorschau",
                           "Karte des Stadt- und Weltbaus im Reiter „Stadt“, wie das Plugin ihn meldet.",
                           "stadt_vorschau", c.city_preview,
                           "Wirkt sofort (Reiter ein-/ausblenden); die Meldungen des Plugins nimmt die App weiter an.");
    if (city.on && !f.city_reported) {
        city.state = HostModuleState::Waiting;
        city.reason = "noch kein Stadt- oder Weltbau gemeldet";
    }
    out.push_back(std::move(city));

    HostModule notes = make("meldungen", "Meldungen im Infobereich",
                            "Windows-Meldungen: Verbindung weg/wieder da, Modell bereit, Bauplan fertig.",
                            "notifications", c.notifications,
                            "Wirkt sofort. Fehler bei verborgenem Fenster meldet die App trotzdem.");
    out.push_back(std::move(notes));

    HostModule link = make("verbindung", "Verbindung zum Server",
                           "Server dieser App und der Weg zum Minecraft-Server (Tunnel oder Fernverbindung).", "",
                           false, "Nur Anzeige: Schalten über „Verbinden“, die Art in den Einstellungen.");
    link.switchable = false;
    link_state(link, c, f);
    out.push_back(std::move(link));
    return out;
}

bool set_host_module(AppConfig& c, std::string_view id, bool on) {
    bool* field = nullptr;
    if (id == "plan-ki") {
        field = &c.load_model_on_start;
    } else if (id == "recherche") {
        field = &c.research;
    } else if (id == "stimme") {
        field = &c.speech_out;
    } else if (id == "mikrofon") {
        field = &c.speech_in;
    } else if (id == "mitspieler") {
        field = &c.hear_others;
    } else if (id == "stadt-vorschau") {
        field = &c.city_preview;
    } else if (id == "meldungen") {
        field = &c.notifications;
    }
    if (field == nullptr) {
        return false;
    }
    *field = on;
    return true;
}

std::string_view host_module_state_de(HostModuleState state) {
    switch (state) {
    case HostModuleState::Active: return "aktiv";
    case HostModuleState::Off: return "aus";
    case HostModuleState::Waiting: return "wartet";
    case HostModuleState::Failed: return "fehler";
    }
    return "aus";
}

}  // namespace swbh::gui
