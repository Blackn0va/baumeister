#include "gui/autostart.hpp"

#include "base/errors.hpp"
#include "gui/app_args.hpp"

#include <windows.h>

#include <cwchar>
#include <vector>

namespace swbh::gui {

namespace {

// Schließt einen offenen Registry-Schlüssel auch auf dem Ausnahmeweg.
class KeyHandle final {
public:
    KeyHandle() = default;
    ~KeyHandle() {
        if (key_ != nullptr) {
            RegCloseKey(key_);
        }
    }
    KeyHandle(const KeyHandle&) = delete;
    KeyHandle& operator=(const KeyHandle&) = delete;

    HKEY* put() { return &key_; }
    HKEY get() const { return key_; }

private:
    HKEY key_ = nullptr;
};

// StartupApproved\Run: REG_BINARY, 12 Byte (Kennbyte, drei Nullbytes, FILETIME des Abschaltens). Bekannt sind 0x02
// (an) und 0x03 (aus, Task-Manager). Geprüft wird das unterste Bit: Es trennt genau diese beiden, und ein anderer
// ungerader Wert zählt im Zweifel als „aus“ – dann zeigt das Häkchen eher zu wenig als einen Autostart, den Windows gar
// nicht ausführt. Andere Kennbytes als 0x02/0x03 sind hier NICHT gemessen.
bool disabled_by_user(const AutostartKeys& keys) {
    DWORD size = 0;
    LSTATUS st = RegGetValueW(HKEY_CURRENT_USER, keys.approved.c_str(), keys.value_name.c_str(), RRF_RT_REG_BINARY,
                              nullptr, nullptr, &size);
    if (st != ERROR_SUCCESS || size == 0) {
        return false;  // kein Vermerk (oder fremder Typ): Windows startet den Run-Wert
    }
    std::vector<BYTE> data(size);
    st = RegGetValueW(HKEY_CURRENT_USER, keys.approved.c_str(), keys.value_name.c_str(), RRF_RT_REG_BINARY, nullptr,
                      data.data(), &size);
    if (st != ERROR_SUCCESS || size == 0) {
        return false;
    }
    return (data[0] & 0x01) != 0;
}

}  // namespace

std::wstring run_value(const std::filesystem::path& exe) {
    return L"\"" + exe.wstring() + L"\" " + std::wstring(kBackgroundSwitch);
}

std::optional<std::wstring> autostart_command(const AutostartKeys& keys) {
    // RRF_NOEXPAND: den Wert so zeigen, wie er dasteht; die App schreibt ohnehin REG_SZ.
    constexpr DWORD kFlags = RRF_RT_REG_SZ | RRF_RT_REG_EXPAND_SZ | RRF_NOEXPAND;
    DWORD size = 0;
    LSTATUS st = RegGetValueW(HKEY_CURRENT_USER, keys.run.c_str(), keys.value_name.c_str(), kFlags, nullptr, nullptr,
                              &size);
    if (st != ERROR_SUCCESS || size < sizeof(wchar_t)) {
        return std::nullopt;
    }
    std::wstring text(size / sizeof(wchar_t), L'\0');
    st = RegGetValueW(HKEY_CURRENT_USER, keys.run.c_str(), keys.value_name.c_str(), kFlags, nullptr, text.data(),
                      &size);
    if (st != ERROR_SUCCESS) {
        return std::nullopt;
    }
    text.resize(wcsnlen(text.c_str(), size / sizeof(wchar_t)));
    return text;
}

bool autostart_enabled(const AutostartKeys& keys) {
    const std::optional<std::wstring> command = autostart_command(keys);
    return command.has_value() && !command->empty() && !disabled_by_user(keys);
}

void set_autostart(bool on, const std::filesystem::path& exe, const AutostartKeys& keys) {
    if (!on) {
        const LSTATUS st = RegDeleteKeyValueW(HKEY_CURRENT_USER, keys.run.c_str(), keys.value_name.c_str());
        if (st != ERROR_SUCCESS && st != ERROR_FILE_NOT_FOUND) {
            throw UserError("Autostart ließ sich nicht abschalten: " + system_error_text(static_cast<unsigned long>(st)));
        }
        return;
    }
    const std::wstring value = run_value(exe);
    KeyHandle key;
    LSTATUS st = RegCreateKeyExW(HKEY_CURRENT_USER, keys.run.c_str(), 0, nullptr, REG_OPTION_NON_VOLATILE,
                                 KEY_SET_VALUE, nullptr, key.put(), nullptr);
    if (st != ERROR_SUCCESS) {
        throw UserError("Autostart ließ sich nicht einschalten: " + system_error_text(static_cast<unsigned long>(st)));
    }
    st = RegSetValueExW(key.get(), keys.value_name.c_str(), 0, REG_SZ, reinterpret_cast<const BYTE*>(value.c_str()),
                        static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
    if (st != ERROR_SUCCESS) {
        throw UserError("Autostart ließ sich nicht einschalten: " + system_error_text(static_cast<unsigned long>(st)));
    }
    // Nur einen Deaktiviert-Vermerk entfernen; ein „an“-Vermerk (0x02) darf stehen bleiben. Ohne das Entfernen bliebe
    // ein früher im Task-Manager abgeschalteter Eintrag aus, obwohl der Wunsch gewählt hat.
    if (disabled_by_user(keys)) {
        st = RegDeleteKeyValueW(HKEY_CURRENT_USER, keys.approved.c_str(), keys.value_name.c_str());
        if (st != ERROR_SUCCESS && st != ERROR_FILE_NOT_FOUND) {
            throw UserError("Autostart ist im Task-Manager deaktiviert und ließ sich nicht freigeben: " +
                            system_error_text(static_cast<unsigned long>(st)));
        }
    }
}

}  // namespace swbh::gui
