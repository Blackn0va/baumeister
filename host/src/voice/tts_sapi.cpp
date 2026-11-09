#include "voice/tts_sapi.hpp"

#include "base/utf8.hpp"

#include <windows.h>

#include <sapi.h>

#include <atomic>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

namespace swbh::voice {

namespace {

// COM-Zeiger ohne ATL (CComPtr bräuchte atlbase.h; die Build Tools haben ATL, die App soll es nicht brauchen).
template <typename T>
class Com final {
public:
    Com() = default;
    ~Com() { reset(); }
    Com(const Com&) = delete;
    Com& operator=(const Com&) = delete;
    T** put() {
        reset();
        return &p_;
    }
    T* get() const { return p_; }
    T* operator->() const { return p_; }
    explicit operator bool() const { return p_ != nullptr; }
    void reset() {
        if (p_ != nullptr) {
            p_->Release();
            p_ = nullptr;
        }
    }

private:
    T* p_ = nullptr;
};

struct TokenInfo {
    std::wstring name;
    bool german = false;
};

TokenInfo describe(ISpObjectToken* token) {
    TokenInfo info;
    wchar_t* text = nullptr;
    if (SUCCEEDED(token->GetStringValue(nullptr, &text)) && text != nullptr) {
        info.name = text;
        CoTaskMemFree(text);
    }
    Com<ISpDataKey> attrs;
    if (SUCCEEDED(token->OpenKey(L"Attributes", attrs.put())) && attrs) {
        wchar_t* lang = nullptr;
        if (SUCCEEDED(attrs->GetStringValue(L"Language", &lang)) && lang != nullptr) {
            // Language ist eine Liste hexadezimaler LCIDs („407“ = Deutsch, „407;9“). Primärsprache Deutsch = 0x07.
            const std::wstring l = lang;
            CoTaskMemFree(lang);
            std::size_t pos = 0;
            while (pos < l.size()) {
                const std::size_t end = l.find(L';', pos);
                const unsigned long lcid = std::wcstoul(l.substr(pos, end - pos).c_str(), nullptr, 16);
                info.german = info.german || PRIMARYLANGID(static_cast<LANGID>(lcid)) == LANG_GERMAN;
                pos = end == std::wstring::npos ? l.size() : end + 1;
            }
        }
    }
    return info;
}

// Alle Stimmen-Token; leer, wenn SAPI fehlt.
template <typename Fn>
void each_voice(Fn&& fn) {
    Com<ISpObjectTokenCategory> cat;
    if (FAILED(CoCreateInstance(CLSID_SpObjectTokenCategory, nullptr, CLSCTX_ALL, IID_ISpObjectTokenCategory,
                                reinterpret_cast<void**>(cat.put())))) {
        return;
    }
    if (FAILED(cat->SetId(SPCAT_VOICES, FALSE))) {
        return;
    }
    Com<IEnumSpObjectTokens> list;
    if (FAILED(cat->EnumTokens(nullptr, nullptr, list.put())) || !list) {
        return;
    }
    ULONG count = 0;
    list->GetCount(&count);
    for (ULONG i = 0; i < count; ++i) {
        Com<ISpObjectToken> token;
        if (SUCCEEDED(list->Item(i, token.put())) && token) {
            fn(token.get());
        }
    }
}

// Für Aufrufe außerhalb des Sprech-Threads (voices()).
struct ComScope {
    HRESULT hr;
    ComScope() : hr(CoInitializeEx(nullptr, COINIT_MULTITHREADED)) {}
    ~ComScope() {
        if (SUCCEEDED(hr)) {
            CoUninitialize();
        }
    }
};

// Stimme wie in Speaker::Impl::pick_voice: Hinweis vor erster deutscher Stimme.
void choose_voice(ISpVoice* voice, const std::wstring& hint) {
    Com<ISpObjectToken> hinted;
    Com<ISpObjectToken> german_first;
    each_voice([&](ISpObjectToken* token) {
        const TokenInfo info = describe(token);
        if (!hinted && !hint.empty() && info.name.find(hint) != std::wstring::npos) {
            token->AddRef();
            *hinted.put() = token;
        }
        if (!german_first && info.german) {
            token->AddRef();
            *german_first.put() = token;
        }
    });
    ISpObjectToken* chosen = hinted ? hinted.get() : german_first.get();
    if (chosen != nullptr) {
        voice->SetVoice(chosen);
    }
}

}  // namespace

std::optional<Pcm> sapi_synth(std::string_view text, std::string_view voice_hint, std::string& error) {
    ComScope com;
    std::wstring w;
    try {
        w = widen(text);
    } catch (const std::exception&) {
        error = "Text ist kein gültiges UTF-8";
        return std::nullopt;
    }
    Com<ISpVoice> voice;
    if (FAILED(CoCreateInstance(CLSID_SpVoice, nullptr, CLSCTX_ALL, IID_ISpVoice, reinterpret_cast<void**>(voice.put())))) {
        error = "Windows-Sprachausgabe (SAPI) nicht verfügbar";
        return std::nullopt;
    }
    choose_voice(voice.get(), widen(voice_hint));
    Com<IStream> memory;
    if (FAILED(CreateStreamOnHGlobal(nullptr, TRUE, memory.put()))) {
        error = "Speicher für die Sprachausgabe nicht anlegbar";
        return std::nullopt;
    }
    Com<ISpStream> stream;
    if (FAILED(CoCreateInstance(CLSID_SpStream, nullptr, CLSCTX_ALL, IID_ISpStream,
                                reinterpret_cast<void**>(stream.put())))) {
        error = "SAPI-Stream nicht anlegbar";
        return std::nullopt;
    }
    WAVEFORMATEX f{};
    f.wFormatTag = WAVE_FORMAT_PCM;
    f.nChannels = 1;
    f.nSamplesPerSec = 22050;
    f.wBitsPerSample = 16;
    f.nBlockAlign = 2;
    f.nAvgBytesPerSec = f.nSamplesPerSec * 2;
    if (FAILED(stream->SetBaseStream(memory.get(), SPDFID_WaveFormatEx, &f)) ||
        FAILED(voice->SetOutput(stream.get(), TRUE))) {
        error = "SAPI-Ausgabe in den Speicher nicht einrichtbar";
        return std::nullopt;
    }
    if (FAILED(voice->Speak(w.c_str(), SPF_IS_NOT_XML, nullptr))) {
        error = "SAPI konnte den Satz nicht sprechen";
        return std::nullopt;
    }
    STATSTG st{};
    if (FAILED(memory->Stat(&st, STATFLAG_NONAME))) {
        error = "SAPI-Ausgabe nicht lesbar";
        return std::nullopt;
    }
    Pcm pcm;
    pcm.rate = 22050;
    pcm.samples.resize(static_cast<std::size_t>(st.cbSize.QuadPart / 2));
    LARGE_INTEGER zero{};
    memory->Seek(zero, STREAM_SEEK_SET, nullptr);
    ULONG got = 0;
    memory->Read(pcm.samples.data(), static_cast<ULONG>(pcm.samples.size() * 2), &got);
    pcm.samples.resize(got / 2);
    return pcm;
}

struct Speaker::Impl {
    std::thread thread;
    std::mutex mutex;
    std::condition_variable cv;
    std::deque<std::wstring> queue;
    bool stop_now = false;
    bool quit = false;
    int volume = 100;
    bool volume_changed = false;
    std::atomic<bool> busy{false};
    std::wstring hint;
    // Vom Thread gesetzt, bevor ready kommt.
    std::string name;
    bool german = false;
    std::string error;
    bool ready = false;

    void run() {
        const HRESULT co = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        Com<ISpVoice> voice;
        HRESULT hr = CoCreateInstance(CLSID_SpVoice, nullptr, CLSCTX_ALL, IID_ISpVoice,
                                      reinterpret_cast<void**>(voice.put()));
        if (FAILED(hr) || !voice) {
            finish_start("Windows-Sprachausgabe (SAPI) nicht verfügbar (HRESULT " + std::to_string(hr) + ")");
            if (SUCCEEDED(co)) {
                CoUninitialize();
            }
            return;
        }
        pick_voice(voice.get());
        finish_start({});
        std::unique_lock<std::mutex> lock(mutex);
        while (!quit) {
            cv.wait_for(lock, std::chrono::milliseconds(100));
            if (volume_changed) {
                voice->SetVolume(static_cast<USHORT>(volume));
                volume_changed = false;
            }
            if (stop_now) {
                stop_now = false;
                voice->Speak(nullptr, SPF_PURGEBEFORESPEAK, nullptr);
            }
            while (!queue.empty()) {
                const std::wstring text = std::move(queue.front());
                queue.pop_front();
                lock.unlock();
                voice->Speak(text.c_str(), SPF_ASYNC | SPF_PURGEBEFORESPEAK | SPF_IS_NOT_XML, nullptr);
                lock.lock();
            }
            SPVOICESTATUS status{};
            busy = SUCCEEDED(voice->GetStatus(&status, nullptr)) && status.dwRunningState == SPRS_IS_SPEAKING;
        }
        lock.unlock();
        voice->Speak(nullptr, SPF_PURGEBEFORESPEAK, nullptr);
        voice.reset();
        if (SUCCEEDED(co)) {
            CoUninitialize();
        }
    }

    void pick_voice(ISpVoice* voice) {
        // Vorrang: Stimme, deren Name den Hinweis enthält; sonst die erste deutsche; sonst bleibt die Standardstimme.
        Com<ISpObjectToken> hinted;
        Com<ISpObjectToken> german_first;
        each_voice([&](ISpObjectToken* token) {
            const TokenInfo info = describe(token);
            if (!hinted && !hint.empty() && info.name.find(hint) != std::wstring::npos) {
                token->AddRef();
                *hinted.put() = token;
            }
            if (!german_first && info.german) {
                token->AddRef();
                *german_first.put() = token;
            }
        });
        ISpObjectToken* chosen = hinted ? hinted.get() : german_first.get();
        if (chosen != nullptr) {
            voice->SetVoice(chosen);
        }
        Com<ISpObjectToken> current;
        if (SUCCEEDED(voice->GetVoice(current.put())) && current) {
            const TokenInfo info = describe(current.get());
            name = narrow(info.name);
            german = info.german;
        }
    }

    void finish_start(std::string err) {
        std::lock_guard<std::mutex> lock(mutex);
        error = std::move(err);
        ready = true;
        cv.notify_all();
    }
};

Speaker::Speaker(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}

Speaker::~Speaker() {
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        impl_->quit = true;
    }
    impl_->cv.notify_all();
    if (impl_->thread.joinable()) {
        impl_->thread.join();
    }
}

std::unique_ptr<Speaker> Speaker::open(std::string_view voice_hint, std::string& error) {
    auto impl = std::make_unique<Impl>();
    impl->hint = widen(voice_hint);
    Impl* raw = impl.get();
    impl->thread = std::thread([raw] { raw->run(); });
    {
        std::unique_lock<std::mutex> lock(raw->mutex);
        raw->cv.wait(lock, [raw] { return raw->ready; });
    }
    if (!raw->error.empty()) {
        error = raw->error;
        raw->thread.join();
        return nullptr;
    }
    return std::unique_ptr<Speaker>(new Speaker(std::move(impl)));
}

void Speaker::say(std::string_view text) {
    std::wstring w;
    try {
        w = widen(text);
    } catch (const std::exception&) {
        return;  // kaputtes UTF-8 kommt über companion_api nicht herein; hier still verwerfen statt werfen
    }
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        impl_->queue.clear();
        impl_->queue.push_back(std::move(w));
        impl_->busy = true;
    }
    impl_->cv.notify_all();
}

void Speaker::stop() {
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        impl_->queue.clear();
        impl_->stop_now = true;
    }
    impl_->cv.notify_all();
}

void Speaker::set_volume(int volume) {
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        impl_->volume = volume < 0 ? 0 : volume > 100 ? 100 : volume;
        impl_->volume_changed = true;
    }
    impl_->cv.notify_all();
}

std::string Speaker::voice_name() const { return impl_->name; }

bool Speaker::german() const { return impl_->german; }

bool Speaker::wait_idle(int ms) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    // Der Sprech-Thread aktualisiert busy alle 100 ms; zweimal hintereinander still = fertig.
    int quiet = 0;
    while (std::chrono::steady_clock::now() < until) {
        std::this_thread::sleep_for(std::chrono::milliseconds(120));
        bool queued = false;
        {
            std::lock_guard<std::mutex> lock(impl_->mutex);
            queued = !impl_->queue.empty();
        }
        quiet = !queued && !impl_->busy ? quiet + 1 : 0;
        if (quiet >= 2) {
            return true;
        }
    }
    return false;
}

std::vector<std::string> Speaker::voices() {
    ComScope com;
    std::vector<std::string> german;
    std::vector<std::string> other;
    each_voice([&](ISpObjectToken* token) {
        const TokenInfo info = describe(token);
        (info.german ? german : other).push_back(narrow(info.name));
    });
    german.insert(german.end(), other.begin(), other.end());
    return german;
}

}  // namespace swbh::voice
