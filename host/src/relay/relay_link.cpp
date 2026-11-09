#include "relay/relay_link.hpp"

#include "base/log.hpp"
#include "relay/forwarder.hpp"
#include "relay/kanal.hpp"
#include "win/cert_pin.hpp"

#include <algorithm>
#include <deque>
#include <exception>
#include <map>
#include <stdexcept>
#include <utility>

namespace swbh::relay {

namespace {

bool printable(std::string_view s) {
    return std::all_of(s.begin(), s.end(), [](char c) {
        const auto u = static_cast<unsigned char>(c);
        return u >= 0x20 && u <= 0x7E;
    });
}

std::string seconds_text(std::chrono::milliseconds ms) {
    const long long s = (ms.count() + 999) / 1000;
    return std::to_string(s) + " s";
}

}  // namespace

struct RelayLink::Job {
    std::uint32_t id = 0;
    LocalRequest request;
    std::uint64_t length = 0;
    bool complete = false;  // unter Session::mutex
    win::CancelToken cancel;
    // Senden und „abbruch“ einer id schließen sich aus: Nach dem Abbruch geht zu ihr nichts mehr hinaus (Abschnitt 3).
    std::mutex send_mutex;
};

struct RelayLink::Session {
    std::shared_ptr<win::WsConnection> conn;
    std::mutex mutex;
    std::condition_variable cv;
    std::map<std::uint32_t, std::shared_ptr<Job>> jobs;
    std::deque<std::shared_ptr<Job>> ready;
    bool over = false;
    bool hello = false;
    bool hello_late = false;
    std::uint32_t last_id = 0;
};

std::optional<std::string> validate(const LinkConfig& c) {
    if (!relay_url_allowed(c.url)) {
        return "Adresse muss die Form wss://<server>:<port>/kanal/v1 haben";
    }
    if (c.server_pins.empty()) {
        return "Kein Fingerabdruck des Servers eingetragen – ohne ihn baut die App keine Verbindung auf "
               "(Einstellungen → Server)";
    }
    if (c.server_pins.size() > 2) {
        return "Höchstens zwei Fingerabdrücke des Servers";
    }
    for (const std::string& p : c.server_pins) {
        if (!win::parse_pin(p)) {
            return "Fingerabdruck des Servers hat nicht die Form sha256:<64 Hex>";
        }
    }
    if (c.local_port < 1 || c.local_port > 65535) {
        return "Interner Port ungültig";
    }
    if (c.app_name.empty() || c.app_name.size() > kMaxPeerNameBytes || !printable(c.app_name)) {
        return "Kennung der App ungültig (intern)";
    }
    return std::nullopt;
}

std::chrono::seconds backoff(int attempt) {
    int s = 10;
    for (int i = 1; i < attempt && s < 60; ++i) {
        s *= 2;
    }
    return std::chrono::seconds(std::min(s, 60));
}

RelayLink::RelayLink(EventFn on_event, Options options)
    : on_event_(std::move(on_event)), options_(std::move(options)) {}

RelayLink::~RelayLink() { stop(); }

State RelayLink::state() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return state_;
}

LinkStats RelayLink::stats() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return stats_;
}

void RelayLink::emit(State s, std::string text, bool alarm) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_ = s;
    }
    if (on_event_) {
        on_event_(Event{s, std::move(text), alarm});
    }
}

bool RelayLink::start(const LinkConfig& config, std::string& error) {
    if (thread_.joinable()) {
        return true;
    }
    if (const auto problem = validate(config)) {
        error = *problem;
        emit(State::Failed, error);
        return false;
    }
    if (!options_.connect) {
        error = "Verbindungsaufbau fehlt (intern)";
        emit(State::Failed, error);
        return false;
    }
    if (!http_) {
        try {
            http_ = options_.make_http ? options_.make_http() : win::make_winhttp_transport();
        } catch (const std::exception& e) {
            error = std::string("WinHTTP nicht verfügbar: ") + e.what();
            emit(State::Failed, error);
            return false;
        }
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stop_requested_ = false;
    }
    thread_ = std::thread([this, config] { run(config); });
    return true;
}

void RelayLink::stop() {
    if (!thread_.joinable()) {
        return;
    }
    std::shared_ptr<win::WsConnection> conn;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stop_requested_ = true;
        conn = current_;
        if (connect_cancel_ != nullptr) {
            connect_cancel_->cancel();
        }
    }
    if (conn) {
        conn->close(kCloseGoingAway, "app_beendet");
    }
    wake_.notify_all();
    thread_.join();
    emit(State::Stopped, "gestoppt");
}

void RelayLink::run(LinkConfig config) {
    const std::string where = relay_host_port(config.url);
    int attempt = 0;
    while (true) {
        win::CancelToken cancel;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stop_requested_) {
                return;
            }
            connect_cancel_ = &cancel;
        }
        ++attempt;
        emit(State::Starting, "verbinde mit " + where + " (Versuch " + std::to_string(attempt) + ") …");
        win::WsConnectError error;
        std::unique_ptr<win::WsConnection> raw;
        try {
            raw = options_.connect(config, error, cancel);
        } catch (const std::exception& e) {
            // Rand eines fremden Bausteins (Zertifikat anlegen, WinHTTP): als Verbindungsfehler weiter, Neustart folgt.
            error.kind = win::WsConnectError::Kind::Connect;
            error.text = std::string("Verbindungsaufbau gescheitert: ") + e.what();
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            connect_cancel_ = nullptr;
        }
        std::string why;
        bool alarm = false;
        bool established = false;
        const auto started = std::chrono::steady_clock::now();
        if (raw) {
            std::shared_ptr<win::WsConnection> conn(std::move(raw));
            bool stopping = false;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                stopping = stop_requested_;
                if (!stopping) {
                    current_ = conn;
                }
            }
            if (stopping) {
                conn->close(kCloseGoingAway, "app_beendet");  // nicht unter mutex_: wartet bis 1 s auf das Close
                return;
            }
            why = run_session(config, conn, established);
            conn->close(kCloseNormal, "");
            std::lock_guard<std::mutex> lock(mutex_);
            current_.reset();
        } else {
            if (error.kind == win::WsConnectError::Kind::Cancelled) {
                return;
            }
            if (error.kind == win::WsConnectError::Kind::Config) {
                // Einstellungen oder eigener Schlüssel unbrauchbar: Wiederholen hilft nicht (wie Tunnel „Failed“).
                emit(State::Failed, error.text);
                return;
            }
            why = error.text;
            alarm = error.kind == win::WsConnectError::Kind::PinMismatch;
            if (alarm) {
                log::warn("Fernverbindung: falscher Fingerabdruck des Servers – gezeigt " + error.found_pin);
            }
        }
        std::unique_lock<std::mutex> lock(mutex_);
        if (stop_requested_) {
            return;
        }
        // Nach mindestens 60 s stabiler Verbindung beginnt das Warten von vorn (Router-Neustart o. Ä.).
        if (established && std::chrono::steady_clock::now() - started > std::chrono::seconds(60)) {
            attempt = 1;
        }
        const std::chrono::milliseconds wait =
            options_.backoff ? options_.backoff(attempt)
                             : std::chrono::duration_cast<std::chrono::milliseconds>(backoff(attempt));
        lock.unlock();
        emit(State::Waiting, why + " – neuer Versuch in " + seconds_text(wait), alarm);
        lock.lock();
        wake_.wait_for(lock, wait, [this] { return stop_requested_; });
    }
}

std::string RelayLink::run_session(const LinkConfig& config, std::shared_ptr<win::WsConnection> conn,
                                   bool& established) {
    const std::string where = relay_host_port(config.url);
    Session s;
    s.conn = conn;
    Message hello;
    hello.type = Type::Hallo;
    hello.peer = config.app_name;
    hello.max_jobs = kMaxJobs;
    if (!conn->send_binary(encode(hello, Sender::App))) {
        return "Verbindung zu " + where + " brach vor dem hallo ab";
    }
    // WinHTTP kennt beim WebSocket keine Empfangsfrist (gemessen) – das hallo des Relays bewacht ein eigener Faden.
    std::thread guard([&s, timeout = options_.hello_timeout] {
        std::unique_lock<std::mutex> lock(s.mutex);
        if (!s.cv.wait_for(lock, timeout, [&s] { return s.hello || s.over; })) {
            s.hello_late = true;
            lock.unlock();
            s.conn->close(kCloseProtocol, "hallo_fehlt");
        }
    });
    std::vector<std::thread> workers;
    std::string why;
    const auto protocol_error = [&](std::string_view reason) {
        why = "Protokollfehler des Relays (" + std::string(reason) + ") – Verbindung beendet";
        log::warn("Fernverbindung: " + why);
        conn->close(close_code(reason), reason);
    };
    const auto send_abort = [&](std::uint32_t id) {
        Message a;
        a.type = Type::Abbruch;
        a.id = id;
        conn->send_binary(encode(a, Sender::App));
    };
    bool running = true;
    while (running) {
        win::WsMessage msg = conn->receive();
        if (msg.kind == win::WsMessage::Kind::Text) {
            protocol_error("textrahmen");
            break;
        }
        if (msg.kind == win::WsMessage::Kind::Closed) {
            why = "Relay hat die Verbindung beendet (Code " + std::to_string(msg.close_code) +
                  (msg.text.empty() ? std::string() : ": " + log::safe(msg.text, 60)) + ")";
            break;
        }
        if (msg.kind == win::WsMessage::Kind::Error) {
            bool late = false;
            {
                std::lock_guard<std::mutex> lock(s.mutex);
                late = s.hello_late;
            }
            why = late ? "Relay schickte binnen " + seconds_text(options_.hello_timeout) + " kein hallo"
                       : (established ? "Verbindung zu " + where + " getrennt – " : "Verbindung zu " + where +
                                                                                      " brach ab – ") +
                             msg.text;
            break;
        }
        Decoded d = decode(msg.data, Sender::Relay);
        if (!d.message) {
            protocol_error(d.reason);
            break;
        }
        Message& m = *d.message;
        if (!established) {
            if (m.type != Type::Hallo) {
                protocol_error("hallo_fehlt");
                break;
            }
            {
                std::lock_guard<std::mutex> lock(s.mutex);
                s.hello = true;
            }
            s.cv.notify_all();
            established = true;
            for (int i = 0; i < kMaxJobs; ++i) {
                workers.emplace_back([this, &s, port = config.local_port] { work(s, port); });
            }
            emit(State::Connected, "verbunden – das Plugin erreicht diese App über " + where + " (Relay „" +
                                       log::safe(m.peer, 64) + "“)");
            continue;
        }
        switch (m.type) {
        case Type::Hallo:
            protocol_error("hallo_doppelt");
            running = false;
            break;
        case Type::Anfrage: {
            std::unique_lock<std::mutex> lock(s.mutex);
            if (m.id <= s.last_id) {
                lock.unlock();
                protocol_error("id_ungueltig");  // umlaut-ok: Grund aus der Spezifikation
                running = false;
                break;
            }
            s.last_id = m.id;
            if (s.jobs.size() >= static_cast<std::size_t>(kMaxJobs)) {
                lock.unlock();
                {
                    std::lock_guard<std::mutex> stats_lock(mutex_);
                    ++stats_.refused;
                }
                log::warn("Fernverbindung: Auftrag " + std::to_string(m.id) + " über der Grenze von " +
                          std::to_string(kMaxJobs) + " – abbruch");
                send_abort(m.id);
                break;
            }
            auto job = std::make_shared<Job>();
            job->id = m.id;
            job->request = LocalRequest{m.method, m.path, m.content_type, std::move(m.data)};
            job->length = m.length;
            s.jobs[m.id] = job;
            if (job->request.body.size() == job->length) {
                job->complete = true;
                s.ready.push_back(job);
            }
            lock.unlock();
            s.cv.notify_all();
            std::lock_guard<std::mutex> stats_lock(mutex_);
            ++stats_.jobs;
            break;
        }
        case Type::Daten: {
            std::unique_lock<std::mutex> lock(s.mutex);
            const auto it = s.jobs.find(m.id);
            if (it == s.jobs.end()) {
                // Nach einem abbruch unterwegs (Abschnitt 3): verwerfen und zählen, kein Close.
                lock.unlock();
                std::lock_guard<std::mutex> stats_lock(mutex_);
                ++stats_.dropped;
                break;
            }
            Job& job = *it->second;
            if (job.complete || job.request.body.size() + m.data.size() > job.length) {
                lock.unlock();
                protocol_error("laenge_ungueltig");  // umlaut-ok: mehr Bytes als len
                running = false;
                break;
            }
            job.request.body += m.data;
            if (job.request.body.size() == job.length) {
                job.complete = true;
                s.ready.push_back(it->second);
                lock.unlock();
                s.cv.notify_all();
            }
            break;
        }
        case Type::Abbruch: {
            std::shared_ptr<Job> job;
            {
                std::lock_guard<std::mutex> lock(s.mutex);
                if (const auto it = s.jobs.find(m.id); it != s.jobs.end()) {
                    job = it->second;
                    s.jobs.erase(it);
                    s.ready.erase(std::remove(s.ready.begin(), s.ready.end(), job), s.ready.end());
                }
            }
            if (job) {
                // Erst die Sendesperre der id, dann abbrechen: Ein gerade laufendes Senden endet noch, danach geht zu
                // dieser id nichts mehr hinaus. Nicht unter mutex_ – ein hängendes Senden hielte sonst stop() auf.
                std::lock_guard<std::mutex> send_lock(job->send_mutex);
                job->cancel.cancel();  // schließt die lokale Anfrage – der eigene Server hört auf zu rechnen
            }
            std::lock_guard<std::mutex> stats_lock(mutex_);
            ++(job ? stats_.aborted : stats_.dropped);
            break;
        }
        case Type::Antwort:
            protocol_error("typ_unbekannt");
            running = false;
            break;
        }
    }
    // Ende der Verbindung: alle lokalen Aufträge abbrechen (Abschnitt 4), Arbeiter und Wächter beenden.
    {
        std::lock_guard<std::mutex> lock(s.mutex);
        s.over = true;
        for (auto& [id, job] : s.jobs) {
            job->cancel.cancel();
        }
        s.jobs.clear();
        s.ready.clear();
    }
    s.cv.notify_all();
    guard.join();
    for (std::thread& w : workers) {
        w.join();
    }
    return why.empty() ? "Verbindung zu " + where + " beendet" : why;
}

void RelayLink::work(Session& s, int port) {
    for (;;) {
        std::shared_ptr<Job> job;
        {
            std::unique_lock<std::mutex> lock(s.mutex);
            s.cv.wait(lock, [&s] { return s.over || !s.ready.empty(); });
            if (s.over) {
                return;
            }
            job = s.ready.front();
            s.ready.pop_front();
        }
        process(s, job, port);
        std::lock_guard<std::mutex> lock(s.mutex);
        if (const auto it = s.jobs.find(job->id); it != s.jobs.end() && it->second == job) {
            s.jobs.erase(it);
        }
    }
}

bool RelayLink::send(Session& s, const std::shared_ptr<Job>& job, const std::string& payload) {
    std::lock_guard<std::mutex> lock(job->send_mutex);
    if (job->cancel.cancelled()) {
        return false;  // nach „abbruch“ geht zu dieser id nichts mehr hinaus
    }
    return s.conn->send_binary(payload);
}

void RelayLink::process(Session& s, const std::shared_ptr<Job>& job, int port) {
    std::string error;
    std::optional<LocalResponse> resp = forward_local(*http_, port, job->request, job->cancel, error);
    if (job->cancel.cancelled()) {
        return;  // abbruch vom Relay oder Verbindung vorbei – nichts mehr senden
    }
    try {
        if (!resp) {
            {
                std::lock_guard<std::mutex> lock(mutex_);
                ++stats_.local_failed;
            }
            log::warn("Fernverbindung: Auftrag " + std::to_string(job->id) + " nicht weitergeleitet – " + error);
            Message a;
            a.type = Type::Abbruch;
            a.id = job->id;
            send(s, job, encode(a, Sender::App));
            return;
        }
        Message head;
        head.type = Type::Antwort;
        head.id = job->id;
        head.status = resp->status;
        head.content_type = resp->content_type;
        head.length = resp->body.size();
        const std::size_t first = std::min(resp->body.size(), kMaxPieceBytes);
        head.data = resp->body.substr(0, first);
        if (!send(s, job, encode(head, Sender::App))) {
            return;
        }
        for (std::size_t off = first; off < resp->body.size(); off += kMaxPieceBytes) {
            Message piece;
            piece.type = Type::Daten;
            piece.id = job->id;
            piece.data = resp->body.substr(off, kMaxPieceBytes);
            if (!send(s, job, encode(piece, Sender::App))) {
                return;
            }
        }
    } catch (const std::invalid_argument& e) {
        // Eigene Nachricht verletzt die Regeln (Programmierfehler): nicht senden, Auftrag abbrechen.
        log::error(std::string("Fernverbindung: Antwort nicht kodierbar – ") + e.what());
        Message a;
        a.type = Type::Abbruch;
        a.id = job->id;
        send(s, job, encode(a, Sender::App));
    }
}

}  // namespace swbh::relay
