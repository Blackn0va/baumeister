// Ablage, Installation und Prüfung der Rechenkerne unter {data}\components (Katalog: components/catalog.hpp).
//
// Aufbau:
//   <root>\<id>\                 installierte Dateien, wie im Katalog benannt
//   <root>\<id>\installed.json   {"id","version","tag","terms_accepted"} – nur Anzeige und Nachweis der Zustimmung
//   <root>\.download\<zip>       Archive während der Installation (fortsetzbar), nach Erfolg gelöscht
//   <root>\.staging-<id>\        Entpacken und Prüfen
//   <root>\.pending-<id>\        fertig geprüft; der alte Kern war geladen → beim nächsten Start getauscht
//   <root>\.remove-<id>          Marke: beim nächsten Start löschen (Kern war geladen)
//
// Vertrauensanker ist immer der Katalog in der EXE, nie installed.json: status() vergleicht Fassung und Größen,
// pin_for_load() zusätzlich den SHA-256 jeder Datei und hält jede Datei bis zum Prozessende gegen Schreiben, Löschen
// und Umbenennen gesperrt – zwischen Prüfen und Laden kann niemand eine Datei austauschen.
// Weder Archive noch Dateien werden ausgeführt, bevor ihr SHA-256 stimmt; entpackt wird mit dem tar.exe aus System32
// (bsdtar, lehnt „..“ und absolute Pfade ab) und nur die im Katalog genannten Dateien.
//
// Threads: Ein Store gehört einem Ablauf; install/remove/cleanup nicht gleichzeitig für dieselbe Wurzel aufrufen.
// pin_for_load ist reentrant. Fehler: install wirft UserError (Text für die Oberfläche) bzw. win::DownloadError,
// win::HttpError (Kind::Cancelled beim Abbruch); status/cleanup werfen nicht.

#pragma once

#include "components/catalog.hpp"
#include "win/http.hpp"

#include <filesystem>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace swbh::components {

enum class State {
    NotInstalled,
    Installed,
    Outdated,        // andere Fassung installiert (die App wurde aktualisiert) → „Aktualisieren“
    Damaged,         // Datei fehlt oder hat die falsche Größe → „Reparieren“
    PendingRestart,  // neue Fassung liegt bereit, getauscht wird beim nächsten Start
    RemovalPending,  // wird beim nächsten Start gelöscht
};

struct Status {
    State state = State::NotInstalled;
    std::string installed_version;  // laut installed.json, leer wenn unbekannt
    std::string detail;             // deutsch, z. B. welche Datei fehlt
    bool terms_accepted = false;    // installed.json nennt die Zustimmung zu Component::terms
};

const char* state_name(State s);

// Ergebnis von install/remove: Wirksam sofort oder erst nach einem Neustart.
struct ChangeResult {
    bool needs_restart = false;
    std::string message;  // deutsch, für die Oberfläche
};

// Geöffnete, geprüfte Dateien eines Kerns (Handles bleiben bis zur Zerstörung offen, Freigabe nur Lesen).
class PinnedFiles {
public:
    PinnedFiles() = default;
    ~PinnedFiles();
    PinnedFiles(PinnedFiles&& other) noexcept;
    PinnedFiles& operator=(PinnedFiles&& other) noexcept;
    PinnedFiles(const PinnedFiles&) = delete;
    PinnedFiles& operator=(const PinnedFiles&) = delete;

    std::filesystem::path dir;       // absoluter Ordner des Kerns
    std::filesystem::path dll;       // absolute Kern-DLL
    std::uint64_t bytes = 0;         // geprüfte Bytes
    double seconds = 0.0;            // Dauer der Prüfung
    std::size_t count() const { return handles_.size(); }

private:
    friend std::optional<PinnedFiles> pin_files(const std::filesystem::path&, const Component&, std::string&);
    std::vector<void*> handles_;
};

class Store {
public:
    explicit Store(std::filesystem::path root);

    const std::filesystem::path& root() const { return root_; }
    std::filesystem::path dir(const Component& c) const;

    // Schnell, ohne Hash: installed.json, Fassung, Vorhandensein und Größe jeder Datei, Marken für den Neustart.
    Status status(const Component& c) const;

    // Lädt die Archive (Größe und SHA-256 streamend geprüft, fortsetzbar), entpackt nur die Katalogdateien, prüft jede
    // Datei (Größe, SHA-256) und setzt den Kern an seinen Platz. terms_accepted muss bei Component::terms gesetzt sein,
    // sonst UserError ohne Netzzugriff. in_use: Der bisherige Kern ist in diesem Prozess geladen – dann liegt die neue
    // Fassung bis zum nächsten Start unter .pending-<id>. progress(done, total) über alle Archive.
    ChangeResult install(win::HttpTransport& http, const Component& c, bool terms_accepted, bool in_use,
                         const win::ProgressFn& progress, win::CancelToken& cancel);

    // Entfernt den Kern; in_use → Marke, gelöscht beim nächsten Start. Wirft nicht; Fehler stehen in message.
    ChangeResult remove(const Component& c, bool in_use);
    // Nimmt ein vorgemerktes Entfernen zurück (Marke löschen). Wirft nicht.
    ChangeResult keep(const Component& c);

    // Beim Start VOR dem ersten Laden: Reste (.staging-*, .old-*) löschen, .pending-* einsetzen, .remove-* ausführen.
    // Liefert deutsche Protokollzeilen.
    std::vector<std::string> cleanup(const Catalog& catalog);

private:
    std::filesystem::path root_;
};

// Prüft und sperrt alle Dateien eines installierten Kerns für das Laden: Ordner ohne Reparse-Punkt in irgendeinem
// Pfadteil, jede Datei ohne Umleitung geöffnet (Freigabe nur Lesen), Größe und SHA-256 laut Katalog. Bei
// Component::terms muss installed.json die Zustimmung nennen. nullopt bei jedem Zweifel, Grund in error (deutsch).
std::optional<PinnedFiles> pin_files(const std::filesystem::path& component_dir, const Component& c,
                                     std::string& error);

// Datei-Name des Katalogs → Pfad unter dir (Teile mit „/“ getrennt).
std::filesystem::path file_path(const std::filesystem::path& dir, const std::string& name);

// Entpackt nur die genannten Einträge eines ZIP mit System32\tar.exe nach dest (Ordner muss existieren). Wirft
// UserError. Öffentlich für die Tests.
void extract_members(const std::filesystem::path& zip, const std::filesystem::path& dest,
                     const std::vector<std::string>& members);

}  // namespace swbh::components
