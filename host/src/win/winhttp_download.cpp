// Download über WinHTTP: .part, Range-Fortsetzen, Fortschritt, Mindestrate,
// Abbruch, SHA-256 streamend (Architektur §6.3, §12.4 Schritt 7; Vertrag in http.hpp).

#include "win/winhttp_internal.hpp"

#include "base/encoding.hpp"
#include "base/errors.hpp"
#include "base/fileio.hpp"
#include "base/paths.hpp"
#include "base/pytext.hpp"
#include "win/cng.hpp"

#include <algorithm>
#include <charconv>
#include <chrono>
#include <optional>

namespace swbh::win::detail {
namespace {

using Clock = std::chrono::steady_clock;

// Die .part-Datei. Offen mit FILE_SHARE_READ: Ein zweiter Download derselben
// Datei scheitert beim Öffnen, statt still in dieselbe Datei zu schreiben.
class PartFile {
public:
    explicit PartFile(std::filesystem::path path) : path_(std::move(path)) {}
    PartFile(const PartFile&) = delete;
    PartFile& operator=(const PartFile&) = delete;
    ~PartFile() { close(); }

    const std::filesystem::path& path() const { return path_; }

    void open() {
        handle_ = CreateFileW(path_.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ, nullptr, OPEN_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
        if (handle_ == INVALID_HANDLE_VALUE) {
            handle_ = nullptr;
            fail_write("nicht zu öffnen", GetLastError());
        }
    }

    std::uint64_t size() const {
        LARGE_INTEGER value{};
        if (!GetFileSizeEx(handle_, &value)) {
            fail_write("Größe nicht lesbar", GetLastError());
        }
        return static_cast<std::uint64_t>(value.QuadPart);
    }

    // Hasht den vorhandenen Inhalt (Fortsetzen) und steht danach am Ende.
    void hash_existing(Sha256Stream& hasher, std::uint64_t bytes) {
        seek(0);
        std::vector<std::uint8_t> buffer(256 * 1024);
        std::uint64_t left = bytes;
        while (left > 0) {
            DWORD read = 0;
            const DWORD want = static_cast<DWORD>(std::min<std::uint64_t>(left, buffer.size()));
            if (!ReadFile(handle_, buffer.data(), want, &read, nullptr)) {
                fail_write("nicht lesbar", GetLastError());
            }
            if (read == 0) {
                fail_write("kürzer als erwartet", ERROR_HANDLE_EOF);
            }
            hasher.update(std::span<const std::uint8_t>(buffer.data(), read));
            left -= read;
        }
    }

    void truncate() {
        seek(0);
        if (!SetEndOfFile(handle_)) {
            fail_write("nicht zu kürzen", GetLastError());
        }
    }

    void seek_end() {
        LARGE_INTEGER zero{};
        if (!SetFilePointerEx(handle_, zero, nullptr, FILE_END)) {
            fail_write("nicht positionierbar", GetLastError());
        }
    }

    void write(const char* data, std::size_t n) {
        while (n > 0) {
            DWORD written = 0;
            const DWORD chunk = static_cast<DWORD>(std::min<std::size_t>(n, 1u << 30));
            if (!WriteFile(handle_, data, chunk, &written, nullptr) || written == 0) {
                const DWORD err = GetLastError();
                if (err == ERROR_DISK_FULL || err == ERROR_HANDLE_DISK_FULL) {
                    throw DownloadError(DownloadError::Kind::DiskSpace,
                                        "Datenträger voll beim Schreiben von " + u8str(path_.filename()));
                }
                fail_write("nicht schreibbar", err);
            }
            data += written;
            n -= written;
        }
    }

    void flush() {
        if (!FlushFileBuffers(handle_)) {
            fail_write("nicht auf die Platte zu schreiben", GetLastError());
        }
    }

    void close() {
        if (handle_ != nullptr) {
            CloseHandle(handle_);
            handle_ = nullptr;
        }
    }

    void remove() {
        close();
        // Fehler hier sind nicht mehr meldbar; eine liegengebliebene .part wird
        // beim nächsten Download ohne resume ohnehin gekürzt.
        DeleteFileW(path_.c_str());
    }

private:
    void seek(std::int64_t pos) {
        LARGE_INTEGER to{};
        to.QuadPart = pos;
        if (!SetFilePointerEx(handle_, to, nullptr, FILE_BEGIN)) {
            fail_write("nicht positionierbar", GetLastError());
        }
    }

    [[noreturn]] void fail_write(const char* what, DWORD err) const {
        throw DownloadError(DownloadError::Kind::Write,
                            "Download-Datei " + u8str(path_.filename()) + " " + what + ": " + system_error_text(err));
    }

    std::filesystem::path path_;
    HANDLE handle_ = nullptr;
};

struct ContentRange {
    std::uint64_t first = 0;
    std::uint64_t last = 0;
    std::uint64_t total = 0;
};

// "bytes 100-199/1000"; "*" als Gesamtgröße gilt als unbrauchbar.
std::optional<ContentRange> parse_content_range(std::string_view value) {
    constexpr std::string_view kPrefix = "bytes ";
    if (value.substr(0, kPrefix.size()) != kPrefix) {
        return std::nullopt;
    }
    value.remove_prefix(kPrefix.size());
    const std::size_t dash = value.find('-');
    const std::size_t slash = value.find('/');
    if (dash == std::string_view::npos || slash == std::string_view::npos || dash > slash) {
        return std::nullopt;
    }
    const auto first = parse_u64(value.substr(0, dash));
    const auto last = parse_u64(value.substr(dash + 1, slash - dash - 1));
    const auto total = parse_u64(value.substr(slash + 1));
    if (!first || !last || !total || *first > *last || *last >= *total) {
        return std::nullopt;
    }
    return ContentRange{*first, *last, *total};
}

void finalize_part(const std::filesystem::path& part, const std::filesystem::path& target) {
    // Virenscanner sperren frisch geschriebene Dateien kurz (spec 06 §3.6: im IST
    // waren dafür 20 Verschiebeversuche nötig). Höchstens 10 × 100 ms.
    DWORD err = 0;
    for (int attempt = 0; attempt < 10; ++attempt) {
        if (MoveFileExW(part.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
            return;
        }
        err = GetLastError();
        if (err != ERROR_SHARING_VIOLATION && err != ERROR_ACCESS_DENIED && err != ERROR_LOCK_VIOLATION) {
            break;
        }
        Sleep(100);
    }
    throw DownloadError(DownloadError::Kind::Write,
                        "Download nicht nach " + u8str(target.filename()) + " umbenennbar: " + system_error_text(err));
}

std::string kib_text(std::uint64_t bytes_per_second) {
    return std::to_string(bytes_per_second / 1024) + " KiB/s";
}

}  // namespace

DownloadResult WinHttpTransport::download(const DownloadRequest& request, const ProgressFn& progress,
                                          CancelToken& cancel) {
    // Pflichtangaben: Ohne Größe und Hash gibt es keine Prüfung – dann gar nicht laden.
    if (request.expected_size == 0) {
        throw DownloadError(DownloadError::Kind::BadRequest, "Download ohne erwartete Größe");
    }
    if (!is_sha256_hex_lower(request.expected_sha256)) {
        throw DownloadError(DownloadError::Kind::BadRequest, "Download ohne gültigen SHA-256 (64 Hex, klein)");
    }
    if (request.target.empty() || !request.target.is_absolute() || !request.target.has_filename()) {
        throw DownloadError(DownloadError::Kind::BadRequest, "Download-Ziel muss ein absoluter Dateipfad sein");
    }
    if (request.min_bytes_per_second > 0 && request.min_rate_window_s < 1) {
        throw DownloadError(DownloadError::Kind::BadRequest, "Mindestrate ohne Zeitfenster");
    }
    const auto parsed = parse_url(request.url);
    if (!parsed) {
        throw DownloadError(DownloadError::Kind::BadRequest, "Ungültige Download-URL");
    }
    if (!(parsed->scheme == "https" || (options_.allow_plain_http_loopback && is_loopback_host(parsed->host)))) {
        throw HttpError(HttpError::Kind::Scheme, "Download nur über https (" + parsed->host + ")");
    }
    throw_if_cancelled(&cancel);

    std::filesystem::path part_path = request.target;
    part_path += L".part";
    try {
        ensure_directory(request.target.parent_path());
    } catch (const FileError& e) {
        throw DownloadError(DownloadError::Kind::Write, e.what());
    }

    PartFile part(part_path);
    const std::uint64_t total = request.expected_size;
    bool resumed = false;
    try {
        part.open();
        std::optional<Sha256Stream> hasher;
        hasher.emplace();
        std::uint64_t offset = 0;
        const std::uint64_t existing = part.size();
        if (request.resume && existing > 0 && existing <= total) {
            part.hash_existing(*hasher, existing);
            offset = existing;
            if (existing == total) {
                // Ein früherer Lauf endete zwischen Prüfung und Umbenennen: Stimmt
                // der Hash, ist die Datei fertig – ohne Netz übernehmen.
                const auto digest = hasher->finish();
                if (to_hex(digest) == request.expected_sha256) {
                    if (progress) {
                        progress(total, total);
                    }
                    part.close();
                    DownloadResult done;
                    done.bytes = total;
                    done.resumed = true;
                    if (request.finalize) {
                        finalize_part(part_path, request.target);
                        done.file = request.target;
                    } else {
                        done.file = part_path;
                    }
                    return done;
                }
                part.truncate();
                hasher.emplace();
                offset = 0;
            }
        } else if (existing > 0) {
            part.truncate();
        }

        // Freier Platz für den Rest (GetDiskFreeSpaceExW berücksichtigt Kontingente).
        ULARGE_INTEGER available{};
        if (GetDiskFreeSpaceExW(request.target.parent_path().c_str(), &available, nullptr, nullptr)) {
            const std::uint64_t needed = total - offset;
            if (available.QuadPart < needed) {
                throw DownloadError(DownloadError::Kind::DiskSpace,
                                    "Zu wenig freier Speicher: " + std::to_string(available.QuadPart / (1024 * 1024)) +
                                        " MiB frei, " + std::to_string((needed + 1024 * 1024 - 1) / (1024 * 1024)) +
                                        " MiB nötig");
            }
        } else {
            throw DownloadError(DownloadError::Kind::Write,
                                "Freier Speicher nicht ermittelbar: " + system_error_text(GetLastError()));
        }

        std::uint64_t done = offset;
        for (int attempt = 0;; ++attempt) {
            RequestSpec spec;
            spec.method = "GET";
            spec.url = request.url;
            spec.user_agent = request.user_agent;
            spec.headers = request.headers;
            spec.timeouts = request.timeouts;
            spec.redirects = request.redirects;
            spec.allow_list = request.redirect_host_suffixes;
            if (offset > 0) {
                spec.range = "bytes=" + std::to_string(offset) + "-";
            }
            if (request.check_linked_etag) {
                // Geprüft in der Umleitungskette, bevor der CDN-Host gefragt wird.
                // Fehlt die Kopfzeile, entscheidet allein der SHA-256 am Ende.
                spec.expected_linked_etag = request.expected_sha256;
                spec.expected_linked_size = total;
            }
            auto open = open_request(sessions_, spec, &cancel, options_);
            const std::string where = host_port(open->target);

            if (is_redirect_status(open->status)) {
                throw HttpError(HttpError::Kind::Redirect, "Umleitung (HTTP " + std::to_string(open->status) +
                                                               ") von " + where + " nicht erlaubt");
            }
            if (open->status == 416 && offset > 0 && attempt == 0) {
                // Range passt nicht (Datei auf dem Server anders): einmal von vorn.
                part.truncate();
                hasher.emplace();
                offset = 0;
                done = 0;
                continue;
            }
            if (open->status == 206 && offset > 0) {
                const auto range = open->header("content-range");
                const auto parsed_range = range ? parse_content_range(*range) : std::nullopt;
                if (!parsed_range || parsed_range->total != total) {
                    throw DownloadError(DownloadError::Kind::SizeMismatch,
                                        "Content-Range von " + where + " passt nicht zur erwarteten Größe " +
                                            std::to_string(total));
                }
                if (parsed_range->first != offset || parsed_range->last != total - 1) {
                    throw DownloadError(DownloadError::Kind::SizeMismatch,
                                        "Content-Range von " + where + " beginnt nicht bei " + std::to_string(offset));
                }
                resumed = true;
            } else if (open->status == 200) {
                if (offset > 0) {
                    // Server ignoriert Range: ganze Datei, also von vorn schreiben.
                    part.truncate();
                    hasher.emplace();
                    offset = 0;
                    done = 0;
                }
            } else {
                throw DownloadError(DownloadError::Kind::HttpStatus,
                                    "HTTP " + std::to_string(open->status) + " von " + where, open->status);
            }

            const std::uint64_t body_expected = total - offset;
            if (open->content_length && *open->content_length != body_expected) {
                throw DownloadError(DownloadError::Kind::SizeMismatch,
                                    "Größe laut Server " + std::to_string(offset + *open->content_length) +
                                        " statt " + std::to_string(total) + " Bytes");
            }

            part.seek_end();
            std::vector<char> buffer(256 * 1024);
            auto last_progress = Clock::now();
            auto window_start = Clock::now();
            std::uint64_t window_bytes = done;
            const auto window = std::chrono::seconds(request.min_rate_window_s);
            for (;;) {
                const std::size_t n = open->read(buffer.data(), buffer.size());
                if (n == 0) {
                    break;
                }
                if (done + n > total) {
                    throw DownloadError(DownloadError::Kind::SizeMismatch,
                                        "Server sendet mehr als " + std::to_string(total) + " Bytes");
                }
                part.write(buffer.data(), n);
                hasher->update(std::span<const std::uint8_t>(reinterpret_cast<const std::uint8_t*>(buffer.data()), n));
                done += n;
                const auto now = Clock::now();
                if (progress && now - last_progress >= std::chrono::milliseconds(250)) {
                    last_progress = now;
                    progress(done, total);
                }
                if (request.min_bytes_per_second > 0 && now - window_start >= window) {
                    const double seconds = std::chrono::duration<double>(now - window_start).count();
                    const double rate = static_cast<double>(done - window_bytes) / seconds;
                    if (rate < static_cast<double>(request.min_bytes_per_second)) {
                        throw HttpError(HttpError::Kind::Timeout,
                                        "Download von " + where + " zu langsam (unter " +
                                            kib_text(request.min_bytes_per_second) + " über " +
                                            std::to_string(request.min_rate_window_s) + " s)");
                    }
                    window_start = now;
                    window_bytes = done;
                }
            }
            if (done < total) {
                // Verbindung vorzeitig zu: Transportfehler, mit resume fortsetzbar.
                throw HttpError(HttpError::Kind::Protocol, "Verbindung zu " + where + " vor Ende abgebrochen (" +
                                                               std::to_string(done) + " von " + std::to_string(total) +
                                                               " Bytes)");
            }
            break;
        }

        if (progress) {
            progress(done, total);
        }
        part.flush();
        part.close();
        const auto digest = hasher->finish();
        if (to_hex(digest) != request.expected_sha256) {
            throw DownloadError(DownloadError::Kind::HashMismatch,
                                "SHA-256 von " + u8str(request.target.filename()) + " stimmt nicht");
        }
        DownloadResult result;
        result.bytes = total;
        result.resumed = resumed;
        if (request.finalize) {
            finalize_part(part_path, request.target);
            result.file = request.target;
        } else {
            result.file = part_path;
        }
        return result;
    } catch (const HttpError&) {
        // Reiner Transportfehler (auch Abbruch): mit resume bleibt die .part zum
        // Fortsetzen liegen, sonst weg – nie eine halbe Datei unter dem Endnamen.
        if (request.resume) {
            part.close();
        } else {
            part.remove();
        }
        throw;
    } catch (...) {
        part.remove();
        throw;
    }
}

}  // namespace swbh::win::detail
