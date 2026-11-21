// Messprobe, kein Unit-Test: gibt je Plan die Zellen der Host-Vorschau (gui/plan_view) aus – Gegenstück zu
// PreviewCellsProbe.java (Zellen des PlanCompilers im Plugin). Ein Python-Vergleich (probes/plan_preview/vergleich.py)
// zählt die Abweichungen je Art (Auftrag T-017, AK-19).
//
// Warum ein eigenes Programm statt eines Testfalls: Die echten Antworten sind Spielertexte und liegen NIE im Repo –
// Ein- und Ausgabeordner kommen als Argumente, ctest kennt das Programm nicht.
//
// Eingabe: je Datei ein JSON-Objekt mit „antwort“ (Plan als Text), wie der Host sie mitschreibt. Ausgabe je Plan:
// <name>.host.txt, erste Zeile „# status …“, dann je Zeile „x y z block“ (Block ohne Zustände).
//
// Aufruf: swbh_probe_plan_preview.exe <ordner mit *.json> <ausgabeordner>
//
// Threads: ein Lauf, ein Thread. Fehler: eine unlesbare Datei bekommt „# status unlesbar“, der Lauf geht weiter.

#include "base/fileio.hpp"
#include "base/json.hpp"
#include "gui/plan_view.hpp"

#include <windows.h>

#include <algorithm>
#include <array>
#include <cstdio>
#include <exception>
#include <filesystem>
#include <string>
#include <vector>

namespace {

std::string cells_text(const swbh::gui::PlanBlocks& plan) {
    std::string out;
    for (int x = plan.min_x; x <= plan.max_x; ++x) {
        for (int y = plan.min_y; y <= plan.max_y; ++y) {
            for (int z = plan.min_z; z <= plan.max_z; ++z) {
                if (const auto b = plan.at(x, y, z)) {
                    out += std::to_string(x) + " " + std::to_string(y) + " " + std::to_string(z) + " ";
                    out += *b;
                    out += "\n";
                }
            }
        }
    }
    return out;
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    SetConsoleOutputCP(CP_UTF8);
    if (argc < 3) {
        std::fputs("Aufruf: swbh_probe_plan_preview <ordner mit *.json> <ausgabeordner>\n", stderr);
        return 1;
    }
    const std::filesystem::path in = argv[1];
    const std::filesystem::path out = argv[2];
    swbh::ensure_directory(out);
    std::vector<std::filesystem::path> files;
    for (const auto& e : std::filesystem::directory_iterator(in)) {
        if (e.path().extension() == L".json") {
            files.push_back(e.path());
        }
    }
    std::sort(files.begin(), files.end());
    int ok = 0;
    for (const auto& f : files) {
        std::string text;
        try {
            const swbh::Json record = swbh::parse_utf8(swbh::read_file(f), true);
            const swbh::Json* answer = swbh::json_find(record, "antwort");
            if (answer == nullptr || !answer->is_string()) {
                throw std::runtime_error("antwort fehlt");
            }
            const swbh::gui::PlanBlocks plan = swbh::gui::rasterize_plan(answer->get<std::string>());
            if (plan.ok) {
                ++ok;
                text = "# status gebaut notizen=" + std::to_string(plan.notes.size()) + "\n" + cells_text(plan);
                for (const std::string& n : plan.notes) {
                    text += "# notiz " + n + "\n";
                }
            } else {
                text = "# status abgelehnt " + plan.error + "\n";
            }
        } catch (const std::exception& e) {
            // Grenze je Datei: eine kaputte Aufzeichnung soll die übrigen nicht verhindern.
            text = std::string("# status unlesbar ") + e.what() + "\n";
        }
        std::filesystem::path target = out / f.filename();
        target.replace_extension(L".host.txt");
        swbh::write_file_atomic(target, text);
    }
    std::printf("Pläne: %zu, Vorschau ok: %d\n", files.size(), ok);
    return 0;
}
