#include "voice/piper_catalog.hpp"

namespace swbh::voice {

namespace {

constexpr std::string_view kRepo = "rhasspy/piper-voices";
constexpr std::string_view kRevision = "c10ece1aade47bb51c153c893d14e5bf8e5b7117";

VoiceSpec v(std::string key, std::string title, std::string path, std::string file, std::uint64_t size,
            std::string sha, std::uint64_t json_size, std::string json_sha, std::string license) {
    VoiceSpec s;
    s.key = std::move(key);
    s.title = std::move(title);
    s.repo = std::string(kRepo);
    s.revision = std::string(kRevision);
    s.path = std::move(path);
    s.file = std::move(file);
    s.size = size;
    s.sha256 = std::move(sha);
    s.json_size = json_size;
    s.json_sha256 = std::move(json_sha);
    s.license = std::move(license);
    return s;
}

}  // namespace

std::string VoiceSpec::onnx_url() const {
    return "https://huggingface.co/" + repo + "/resolve/" + revision + "/" + path + "/" + file;
}

std::string VoiceSpec::json_url() const { return onnx_url() + ".json"; }

const PiperSpec& piper_spec() {
    static const PiperSpec s{"Piper 2023.11.14-2 für Windows (Sprachausgabe)",
                             "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip",
                             22477236,
                             "f3c58906402b24f3a96d92145f58acba6d86c9b5db896d207f78dc80811efcea",
                             "MIT (Piper), GPL-3.0-or-later (espeak-ng, mitgeliefert), MIT (ONNX Runtime)",
                             "piper/piper.exe"};
    return s;
}

const std::vector<VoiceSpec>& piper_voices() {
    static const std::vector<VoiceSpec> list = {
        v("thorsten-medium", "Thorsten (männlich, natürlich – empfohlen)", "de/de_DE/thorsten/medium",
          "de_DE-thorsten-medium.onnx", 63201294, "7e64762d8e5118bb578f2eea6207e1a35a8e0c30595010b666f983fc87bb7819",
          4819, "974adee790533adb273a1ac88f49027d2a1b8f0f2cf4905954a4791e79264e85",
          "CC0-1.0 (Datensatz Thorsten-Voice)"),
        v("thorsten-high", "Thorsten (männlich, hohe Qualität)", "de/de_DE/thorsten/high", "de_DE-thorsten-high.onnx",
          113895201, "9df1c43c61149ef9b39e618e2b861fbe41e1fcea9390b2dac62e8761573ea4f1", 4875,
          "6de734444e4c3f9e33b7ebe2746dbc19b71e85f613e79c65acf623200b99a76a", "CC0-1.0 (Datensatz Thorsten-Voice)"),
        v("kerstin-low", "Kerstin (weiblich, einfache Qualität)", "de/de_DE/kerstin/low", "de_DE-kerstin-low.onnx",
          63104526, "d352a7641892cebf2903859af94e9ba81a141110215fe3943bcda7f7da401b7a", 4158,
          "56e708556b7b9b7a53c4f8957e021421e69f11a600962bba554cffbe72cf2d47", "CC0-1.0 (Datensatz Kerstin)"),
        v("ramona-low", "Ramona (weiblich, einfache Qualität)", "de/de_DE/ramona/low", "de_DE-ramona-low.onnx",
          63104526, "a1068592532aad8c49bf3d1a01b8749c11d7dba726689ee28ad64e7b627ae4c5", 4157,
          "64906ab7a050ac685fa3dcea044baa798f67ed77be164f9c668a69298c6487d2",
          "M-AILABS-Datensatz (Lizenz laut caito.de)"),
        v("eva-xlow", "Eva K. (weiblich, sehr klein und schnell)", "de/de_DE/eva_k/x_low", "de_DE-eva_k-x_low.onnx",
          20628813, "e88cf290fbfb768bf111330d2e8a46e376b0d85e3423a28bfebbc863a260dad8", 4158,
          "ef14b3dcb279ab4b18422a7a132877bee7a148821bd91152fb7ae9c4b3d79625",
          "M-AILABS-Datensatz (Lizenz laut caito.de)"),
    };
    return list;
}

const VoiceSpec& voice_or_default(std::string_view key) {
    for (const VoiceSpec& s : piper_voices()) {
        if (s.key == key) {
            return s;
        }
    }
    for (const VoiceSpec& s : piper_voices()) {
        if (s.key == kDefaultPiperVoice) {
            return s;
        }
    }
    return piper_voices().front();
}

}  // namespace swbh::voice
