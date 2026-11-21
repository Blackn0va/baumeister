#include "gui/plan_view.hpp"

#include "base/json.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <set>
#include <utility>

namespace swbh::gui {

namespace {

constexpr int kFloorHeight = 4;   // PlanCompiler.FLOOR_HEIGHT
constexpr int kMaxRoofLayers = 24;  // PlanCompiler.MAX_ROOF_LAYERS
constexpr int kAxisBits = 10;     // kCoordLow … kCoordHigh = 1024 Werte
static_assert(kCoordHigh - kCoordLow + 1 == (1 << kAxisBits));

std::uint32_t pack(int x, int y, int z) {
    return (static_cast<std::uint32_t>(x - kCoordLow) << (2 * kAxisBits)) |
           (static_cast<std::uint32_t>(y - kCoordLow) << kAxisBits) | static_cast<std::uint32_t>(z - kCoordLow);
}

std::array<int, 3> unpack(std::uint32_t key) {
    constexpr std::uint32_t mask = (1u << kAxisBits) - 1u;
    return {static_cast<int>((key >> (2 * kAxisBits)) & mask) + kCoordLow,
            static_cast<int>((key >> kAxisBits) & mask) + kCoordLow, static_cast<int>(key & mask) + kCoordLow};
}

struct Vec {
    int x = 0;
    int y = 0;
    int z = 0;
};

std::optional<Vec> read_vec(const Json& step, const char* key) {
    const Json* v = json_find(step, key);
    if (v == nullptr || !v->is_array() || v->size() != 3) {
        return std::nullopt;
    }
    std::array<int, 3> out{};
    for (std::size_t i = 0; i < 3; ++i) {
        const Json& c = (*v)[i];
        if (!c.is_number_integer()) {
            return std::nullopt;
        }
        // Außerhalb des darstellbaren Bereichs auf den Rand – put() schneidet dort ohnehin ab, und so überläuft
        // keine Ausdehnungsrechnung bei absurden Zahlen aus einer freien Antwort.
        out[i] = static_cast<int>(std::clamp<long long>(c.get<long long>(), kCoordLow - 64, kCoordHigh + 64));
    }
    return Vec{out[0], out[1], out[2]};
}

std::string read_str(const Json& step, const char* key) {
    const Json* v = json_find(step, key);
    return v != nullptr && v->is_string() ? v->get<std::string>() : std::string();
}

std::optional<int> read_int(const Json& step, const char* key) {
    const Json* v = json_find(step, key);
    if (v == nullptr || !v->is_number_integer()) {
        return std::nullopt;
    }
    return static_cast<int>(std::clamp<long long>(v->get<long long>(), -100000, 100000));
}

bool read_bool(const Json& step, const char* key) {
    const Json* v = json_find(step, key);
    return v != nullptr && v->is_boolean() && v->get<bool>();
}

bool is_air(std::string_view base) { return base == "air" || base == "cave_air" || base == "void_air"; }

bool is_door(std::string_view base) { return base.ends_with("_door"); }

// TwoPart.TALL des Plugins: untere/obere Hälfte über half=lower/upper.
bool is_tall_plant(std::string_view base) {
    static constexpr std::array<std::string_view, 10> kTall{"tall_grass", "large_fern",    "sunflower",
                                                            "lilac",      "rose_bush",     "peony",
                                                            "tall_seagrass", "pitcher_plant", "small_dripleaf",
                                                            "pitcher_crop"};
    return std::find(kTall.begin(), kTall.end(), base) != kTall.end();
}

// Wert eines Zustands aus „name[key=value,…]“, klein geschrieben; leer, wenn nicht angegeben.
std::string state_value(std::string_view block, std::string_view key) {
    const std::size_t open = block.find('[');
    const std::size_t close = block.rfind(']');
    if (open == std::string_view::npos || close == std::string_view::npos || close < open) {
        return {};
    }
    std::string_view states = block.substr(open + 1, close - open - 1);
    while (!states.empty()) {
        const std::size_t comma = states.find(',');
        std::string_view pair = states.substr(0, comma);
        states = comma == std::string_view::npos ? std::string_view() : states.substr(comma + 1);
        const std::size_t eq = pair.find('=');
        if (eq == std::string_view::npos) {
            continue;
        }
        std::string k(pair.substr(0, eq));
        std::erase(k, ' ');
        if (k == key) {
            std::string v(pair.substr(eq + 1));
            std::erase(v, ' ');
            for (char& c : v) {
                c = c >= 'A' && c <= 'Z' ? static_cast<char>(c - 'A' + 'a') : c;
            }
            return v;
        }
    }
    return {};
}

struct Offset {
    int x = 0;
    int z = 0;
};

// TwoPart.offset über StateTransform.toCompass: back = north = +z, front = south = −z, right = east = +x,
// left = west = −x. Unbekannt: kein Kopfteil (wie im Plugin).
std::optional<Offset> facing_offset(std::string_view facing) {
    if (facing == "back" || facing == "north") {
        return Offset{0, 1};
    }
    if (facing == "front" || facing == "south") {
        return Offset{0, -1};
    }
    if (facing == "right" || facing == "east") {
        return Offset{1, 0};
    }
    if (facing == "left" || facing == "west") {
        return Offset{-1, 0};
    }
    return std::nullopt;
}

// Wie PlanCompiler.inDisc/inBall: Abstand < r + 0,5.
bool in_disc(int dx, int dz, int r) { return dx * dx + dz * dz < (r + 0.5) * (r + 0.5); }
bool in_ball(int dx, int dy, int dz, int r) { return dx * dx + dy * dy + dz * dz < (r + 0.5) * (r + 0.5); }

// Die festen Umschreibungen aus BlockRules.resolve des Plugins, die nicht vom Blockkatalog des Servers abhängen:
// UNSTABLE (gültig, aber zerfällt im Bau) und ALIASES (generische Namen, die es in 1.21 als Block nicht gibt – „sign“,
// „redstone“, „stairs“ –, deshalb greift der Alias auf jedem Server). Vorher zeigte die Vorschau frosted_ice, wo das
// Plugin packed_ice baut (460 Zellen in einem echten Plan, T-017). Plural/Singular, ItemNames und der Ersatzblock
// Stein brauchen den Katalog des Servers und fehlen hier bewusst. Wer BlockRules ändert, zieht diese Liste nach.
std::string canonical_base(std::string base) {
    static const std::unordered_map<std::string_view, std::string_view> kRewrite{
        {"frosted_ice", "packed_ice"},
        {"planks", "oak_planks"},
        {"wood_planks", "oak_planks"},
        {"wooden_planks", "oak_planks"},
        {"wood_plank", "oak_planks"},
        {"log", "oak_log"},
        {"wood_log", "oak_log"},
        {"wooden_log", "oak_log"},
        {"door", "oak_door"},
        {"wooden_door", "oak_door"},
        {"wood_door", "oak_door"},
        {"stairs", "oak_stairs"},
        {"wooden_stairs", "oak_stairs"},
        {"wood_stairs", "oak_stairs"},
        {"slab", "oak_slab"},
        {"wooden_slab", "oak_slab"},
        {"fence", "oak_fence"},
        {"wooden_fence", "oak_fence"},
        {"leaves", "oak_leaves"},
        {"window", "glass_pane"},
        {"glass_window", "glass_pane"},
        {"stone_brick", "stone_bricks"},
        {"brick", "bricks"},
        {"brick_block", "bricks"},
        {"wool", "white_wool"},
        {"concrete", "white_concrete"},
        {"grass", "grass_block"},
        {"lamp", "lantern"},
        {"cobble", "cobblestone"},
        {"redstone", "redstone_wire"},
        {"redstone_dust", "redstone_wire"},
        {"sign", "oak_sign"},
        {"wall_sign", "oak_wall_sign"},
        {"button", "stone_button"},
        {"pressure_plate", "stone_pressure_plate"},
        {"redstone_comparator", "comparator"},
        {"redstone_repeater", "repeater"},
    };
    if (const auto it = kRewrite.find(base); it != kRewrite.end()) {
        return std::string(it->second);
    }
    return base;
}

// Blockangabe mit umgeschriebenem Namen, Zustände bleiben: „door[facing=left]“ → „oak_door[facing=left]“.
std::string canonical_block(const std::string& raw) {
    const std::string base = block_base_name(raw);
    const std::string canon = canonical_base(base);
    if (canon == base) {
        return raw;
    }
    const std::size_t bracket = raw.find('[');
    return bracket == std::string::npos ? canon : canon + raw.substr(bracket);
}

class Raster {
public:
    explicit Raster(PlanBlocks& out) : out_(out) {}

    // Blockname → Paletten-Index; std::nullopt für Luft (löscht).
    std::optional<std::uint16_t> block(std::string_view name) {
        const std::string base = canonical_base(block_base_name(name));
        if (is_air(base)) {
            return std::nullopt;
        }
        if (const auto it = index_.find(base); it != index_.end()) {
            return it->second;
        }
        const auto idx = static_cast<std::uint16_t>(out_.palette.size());
        out_.palette.push_back(base);
        index_.emplace(base, idx);
        return idx;
    }

    void put(int x, int y, int z, std::optional<std::uint16_t> b) {
        if (x < kCoordLow || x > kCoordHigh || y < kCoordLow || y > kCoordHigh || z < kCoordLow || z > kCoordHigh) {
            ++out_.clipped;
            return;
        }
        const std::uint32_t key = pack(x, y, z);
        halves_.erase(key);  // überschrieben: keine Hälfte mehr
        if (!b) {
            out_.cells.erase(key);
            return;
        }
        if (out_.cells.size() >= kMaxPreviewBlocks && !out_.cells.contains(key)) {
            full_ = true;
            return;
        }
        out_.cells[key] = *b;
    }

    // Untere bzw. obere Hälfte eines senkrecht zweiteiligen Blocks (Tür, hohe Pflanze).
    void put_half(int x, int y, int z, std::optional<std::uint16_t> b, bool upper) {
        put(x, y, z, b);
        if (b && x >= kCoordLow && x <= kCoordHigh && y >= kCoordLow && y <= kCoordHigh && z >= kCoordLow &&
            z <= kCoordHigh && out_.cells.contains(pack(x, y, z))) {
            halves_[pack(x, y, z)] = upper;
        }
    }

    // Wie PlanCompiler.order: Eine Hälfte ohne ihr Gegenstück (von einem späteren Schritt überschrieben) baut das
    // Plugin nicht – sie zerfiele sofort. Vorher zeigte die Vorschau dort eine halbe Tür (2 von 50 echten Plänen,
    // T-017).
    void drop_lone_halves() {
        std::vector<std::uint32_t> lone;
        for (const auto& [key, upper] : halves_) {
            const auto [x, y, z] = unpack(key);
            const int partner_y = upper ? y - 1 : y + 1;
            bool ok = false;
            if (partner_y >= kCoordLow && partner_y <= kCoordHigh) {
                const std::uint32_t pk = pack(x, partner_y, z);
                const auto half = halves_.find(pk);
                ok = half != halves_.end() && half->second != upper && out_.cells.at(pk) == out_.cells.at(key);
            }
            if (!ok) {
                lone.push_back(key);
            }
        }
        for (const std::uint32_t key : lone) {
            out_.cells.erase(key);
        }
    }

    bool full() const { return full_; }

private:
    PlanBlocks& out_;
    std::unordered_map<std::string, std::uint16_t> index_;
    std::unordered_map<std::uint32_t, bool> halves_;  // Zelle → obere Hälfte?
    bool full_ = false;
};

enum class Shell { None, Walls, All };

struct Box {
    int x0, x1, y0, y1, z0, z1;
    std::int64_t volume() const {
        return static_cast<std::int64_t>(x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
    }
};

Box box_of(const Vec& a, const Vec& b) {
    return {std::min(a.x, b.x), std::max(a.x, b.x), std::min(a.y, b.y), std::max(a.y, b.y), std::min(a.z, b.z),
            std::max(a.z, b.z)};
}

class StepPainter {
public:
    StepPainter(Raster& r, PlanBlocks& out) : r_(r), out_(out) {}

    // true, sobald ein Schritt das Gesamtbudget kMaxPreviewWork überschritten hätte (er wurde nicht gerechnet).
    bool exhausted() const { return exhausted_; }

    void paint(const Json& step, int n) {
        n_ = n;
        const std::string op = read_str(step, "op");
        // Umschreibung vor der Tür-Prüfung wie im Plugin (BlockRules.resolve vor box/single): „door“ ist eine Tür.
        const std::string block = canonical_block(read_str(step, "block"));
        if (op == "fill" || op == "walls" || op == "hollow") {
            const auto from = read_vec(step, "from");
            const auto to = read_vec(step, "to");
            if (!from || !to || !has_block(block)) {
                return skip_missing(op, from && to ? "block" : "from/to");
            }
            const Box b = box_of(*from, *to);
            if (!fits(b.volume(), op)) {
                return;
            }
            if (is_door(block_base_name(block))) {
                // Wie PlanCompiler.box: Eine „Tür-Füllung“ ist fast immer als einzelne Tür gemeint.
                return door(Vec{b.x0, b.y0, b.z0}, block);
            }
            // fill mit hollow:true baut das Plugin als Hülle (PlanCompiler.emit, FILL); fill air räumt immer ganz aus.
            // Vorher zeigte die Vorschau hier einen Vollblock (T-017, 2026-11-21).
            const bool hollow_fill = op == "fill" && read_bool(step, "hollow") && r_.block(block).has_value();
            box(b, r_.block(block),
                op == "fill" && !hollow_fill ? Shell::None : op == "walls" ? Shell::Walls : Shell::All);
        } else if (op == "line") {
            const auto from = read_vec(step, "from");
            const auto to = read_vec(step, "to");
            if (!from || !to || !has_block(block)) {
                return skip_missing(op, from && to ? "block" : "from/to");
            }
            line(*from, *to, r_.block(block));
        } else if (op == "set" || op == "door") {
            const auto pos = read_vec(step, "pos");
            if (!pos || !has_block(block)) {
                return skip_missing(op, pos ? "block" : "pos");
            }
            if (is_door(block_base_name(block))) {
                return door(*pos, block);
            }
            two_part(*pos, block, read_str(step, "facing"));
        } else if (op == "cylinder" || op == "sphere") {
            const auto pos = read_vec(step, "pos");
            const auto radius = read_int(step, "radius");
            if (!pos || !radius || *radius < 1 || !has_block(block)) {
                return skip_missing(op, !pos ? "pos" : !radius || *radius < 1 ? "radius" : "block");
            }
            const int rad = std::min(*radius, 64);
            if (op == "cylinder") {
                const int h = std::clamp(read_int(step, "height").value_or(1), 1, 512);
                if (fits(static_cast<std::int64_t>(2 * rad + 1) * (2 * rad + 1) * h, op)) {
                    cylinder(*pos, rad, h, read_bool(step, "hollow"), r_.block(block));
                }
            } else if (fits(static_cast<std::int64_t>(2 * rad + 1) * (2 * rad + 1) * (2 * rad + 1), op)) {
                sphere(*pos, rad, read_bool(step, "hollow"), read_bool(step, "dome"), r_.block(block));
            }
        } else if (op == "roof") {
            const auto from = read_vec(step, "from");
            const auto to = read_vec(step, "to");
            if (!from || !to || !has_block(block)) {
                return skip_missing(op, from && to ? "block" : "from/to");
            }
            const Box b = box_of(*from, *to);
            if (!fits(static_cast<std::int64_t>(b.x1 - b.x0 + 5) * (b.z1 - b.z0 + 5) * 2, op)) {
                return;
            }
            std::string shape = read_str(step, "shape");
            shape = shape.empty() ? "gable" : shape;
            roof(b, shape, std::clamp(read_int(step, "overhang").value_or(1), 0, 2), block,
                 gable_index(read_str(step, "gable_block")));
        } else if (op == "house") {
            house(step, block);
        } else if (op == "skyscraper") {
            skyscraper(step, block);
        } else if (op == "module" || op == "logic") {
            const auto pos = read_vec(step, "pos");
            if (!pos) {
                return skip_missing(op, "pos");
            }
            const bool module = op == "module";
            r_.put(pos->x, pos->y, pos->z, r_.block(module ? kModuleMarker : kLogicMarker));
            const std::string what = module ? "Modul „" + read_str(step, "name") + "“"
                                            : "Logik „" + read_str(step, "expr") + "“";
            out_.notes.push_back("Schritt " + std::to_string(n_) + ": " + what + " bei " + std::to_string(pos->x) +
                                 ", " + std::to_string(pos->y) + ", " + std::to_string(pos->z) +
                                 " – Maße kennt nur das Plugin, als Markierung gezeigt.");
        } else if (op == "castle" || op == "wall" || op == "bridge" || op == "garden" || op == "tree") {
            // Bewusst nicht nachgebaut (T-017): Die Bauformen rechnet plan/Structures.java auf gut 1 100 Zeilen mit
            // Türmen, Toren, Rampen und Bäumen – ein zweiter Nachbau liefe bei jeder Änderung dort auseinander.
            out_.notes.push_back("Schritt " + std::to_string(n_) + ": Bauform „" + op +
                                 "“ rechnet nur das Plugin – in der Vorschau nicht gezeigt.");
        } else {
            out_.notes.push_back("Schritt " + std::to_string(n_) + ": unbekannte op „" + op + "“ ausgelassen.");
        }
    }

private:
    static bool has_block(const std::string& block) { return !block.empty(); }

    void skip_missing(const std::string& op, const char* field) {
        out_.notes.push_back("Schritt " + std::to_string(n_) + " (" + op + "): " + field +
                             " fehlt oder ungültig – ausgelassen.");
    }

    bool fits(std::int64_t volume, const std::string& op) {
        if (volume > static_cast<std::int64_t>(kMaxStepVolume)) {
            out_.notes.push_back("Schritt " + std::to_string(n_) + " (" + op + "): " + std::to_string(volume) +
                                 " Blöcke – zu groß für die Vorschau, ausgelassen.");
            return false;
        }
        // Gesamtbudget: Ohne die Summe rechnete ein Plan aus vielen überlappenden Großschritten Sekunden im GUI-Thread
        // (Prüfbefund 2026-10-29, 70 × fill 84³ = 6,9 s). Wer das entfernt, friert das Fenster bei solchen Plänen ein.
        if (work_ + volume > static_cast<std::int64_t>(kMaxPreviewWork)) {
            exhausted_ = true;
            return false;
        }
        work_ += volume;
        return true;
    }

    std::optional<std::uint16_t> gable_index(const std::string& gable) {
        if (gable.empty()) {
            return std::nullopt;
        }
        return r_.block(gable);
    }

    void box(const Box& b, std::optional<std::uint16_t> block, Shell shell) {
        for (int y = b.y0; y <= b.y1; ++y) {
            for (int z = b.z0; z <= b.z1; ++z) {
                for (int x = b.x0; x <= b.x1; ++x) {
                    const bool side = x == b.x0 || x == b.x1 || z == b.z0 || z == b.z1;
                    const bool cap = y == b.y0 || y == b.y1;
                    const bool keep = shell == Shell::None || (shell == Shell::Walls && side) ||
                                      (shell == Shell::All && (side || cap));
                    if (keep) {
                        r_.put(x, y, z, block);
                    }
                }
            }
        }
    }

    // PlanCompiler.line: auf der längsten Achse jeden Block, die anderen gerundet (Math.round = halb nach oben).
    void line(const Vec& a, const Vec& b, std::optional<std::uint16_t> block) {
        const int dx = b.x - a.x;
        const int dy = b.y - a.y;
        const int dz = b.z - a.z;
        const int steps = std::max(std::abs(dx), std::max(std::abs(dy), std::abs(dz)));
        for (int i = 0; i <= steps; ++i) {
            const double t = steps == 0 ? 0.0 : static_cast<double>(i) / steps;
            r_.put(static_cast<int>(std::floor(a.x + dx * t + 0.5)), static_cast<int>(std::floor(a.y + dy * t + 0.5)),
                   static_cast<int>(std::floor(a.z + dz * t + 0.5)), block);
        }
    }

    void door(const Vec& p, const std::string& block) {
        const auto b = r_.block(block);
        r_.put_half(p.x, p.y, p.z, b, false);
        r_.put_half(p.x, p.y + 1, p.z, b, true);
    }

    // Wie TwoPart.expand im Plugin: Hohe Pflanzen und Betten ohne Angabe der Hälfte bekommen die zweite gleich dazu
    // (Pflanze darüber, Bettkopf in Blickrichtung – facing am Block, sonst das facing des Schritts, sonst back).
    // Vorher zeigte die Vorschau nur die erste Hälfte (T-017). Nur für set: fill/line mit Betten kamen in 50 echten
    // Plänen nicht vor.
    void two_part(const Vec& p, const std::string& raw, const std::string& step_facing) {
        const std::string base = block_base_name(raw);
        const auto b = r_.block(raw);
        if (is_tall_plant(base) && state_value(raw, "half").empty()) {
            r_.put_half(p.x, p.y, p.z, b, false);
            r_.put_half(p.x, p.y + 1, p.z, b, true);
            return;
        }
        r_.put(p.x, p.y, p.z, b);
        if (base.ends_with("_bed") && state_value(raw, "part").empty()) {
            std::string facing = state_value(raw, "facing");
            facing = facing.empty() ? step_facing : facing;
            if (const auto d = facing_offset(facing.empty() ? "back" : facing)) {
                r_.put(p.x + d->x, p.y, p.z + d->z, b);
            }
        }
    }

    void cylinder(const Vec& base, int r, int h, bool hollow, std::optional<std::uint16_t> block) {
        for (int dy = 0; dy < h; ++dy) {
            for (int dz = -r; dz <= r; ++dz) {
                for (int dx = -r; dx <= r; ++dx) {
                    if (!in_disc(dx, dz, r)) {
                        continue;
                    }
                    const bool edge = !(in_disc(dx + 1, dz, r) && in_disc(dx - 1, dz, r) && in_disc(dx, dz + 1, r) &&
                                        in_disc(dx, dz - 1, r));
                    if (!hollow || edge) {
                        r_.put(base.x + dx, base.y + dy, base.z + dz, block);
                    }
                }
            }
        }
    }

    void sphere(const Vec& m, int r, bool hollow, bool dome, std::optional<std::uint16_t> block) {
        for (int dy = dome ? 0 : -r; dy <= r; ++dy) {
            for (int dz = -r; dz <= r; ++dz) {
                for (int dx = -r; dx <= r; ++dx) {
                    if (!in_ball(dx, dy, dz, r)) {
                        continue;
                    }
                    const bool edge = !(in_ball(dx + 1, dy, dz, r) && in_ball(dx - 1, dy, dz, r) &&
                                        in_ball(dx, dy + 1, dz, r) && in_ball(dx, dy - 1, dz, r) &&
                                        in_ball(dx, dy, dz + 1, r) && in_ball(dx, dy, dz - 1, r));
                    if (!hollow || edge) {
                        r_.put(m.x + dx, m.y + dy, m.z + dz, block);
                    }
                }
            }
        }
    }

    // PlanCompiler.roof: Überstand um das Wandrechteck, Schichten nach innen; Satteldach mit First entlang der längeren
    // Seite und Giebelfüllung innerhalb der Wände. Treppenrichtungen spielen für die Vorschau keine Rolle. Den First
    // (und Flach- bzw. Walmdach-Deckel) legt das Plugin aus Treppen als Platte (PlanCompiler.slabFor) – vorher zeigte
    // die Vorschau dort Treppen, gemessen an 14 echten Hausplänen 115 Zellen falsch (T-017, 2026-11-21).
    void roof(const Box& w, const std::string& shape, int o, const std::string& block_name,
              std::optional<std::uint16_t> gable) {
        const auto block = r_.block(block_name);
        const std::string base = block_base_name(block_name);
        constexpr std::string_view kStairs = "_stairs";
        const auto top =
            base.ends_with(kStairs) ? r_.block(base.substr(0, base.size() - kStairs.size()) + "_slab") : block;
        const int x0 = w.x0 - o;
        const int x1 = w.x1 + o;
        const int z0 = w.z0 - o;
        const int z1 = w.z1 + o;
        const int base_y = w.y0;
        if (shape == "flat") {
            for (int z = z0; z <= z1; ++z) {
                for (int x = x0; x <= x1; ++x) {
                    r_.put(x, base_y, z, top);
                }
            }
            return;
        }
        if (shape == "hip") {
            for (int i = 0; i < kMaxRoofLayers; ++i) {
                const int xa = x0 + i;
                const int xb = x1 - i;
                const int za = z0 + i;
                const int zb = z1 - i;
                if (xa > xb || za > zb) {
                    break;
                }
                const int y = base_y + i;
                if (xa == xb || za == zb) {
                    for (int z = za; z <= zb; ++z) {
                        for (int x = xa; x <= xb; ++x) {
                            r_.put(x, y, z, top);
                        }
                    }
                    break;
                }
                for (int x = xa; x <= xb; ++x) {
                    r_.put(x, y, za, block);
                    r_.put(x, y, zb, block);
                }
                for (int z = za + 1; z < zb; ++z) {
                    r_.put(xa, y, z, block);
                    r_.put(xb, y, z, block);
                }
            }
            return;
        }
        const bool ridge_along_x = (x1 - x0) >= (z1 - z0);
        const int lo = ridge_along_x ? z0 : x0;
        const int hi = ridge_along_x ? z1 : x1;
        const int ridge_lo = ridge_along_x ? x0 : z0;
        const int ridge_hi = ridge_along_x ? x1 : z1;
        const int wall_lo = ridge_along_x ? w.z0 : w.x0;
        const int wall_hi = ridge_along_x ? w.z1 : w.x1;
        const int gable_a = ridge_along_x ? w.x0 : w.z0;
        const int gable_b = ridge_along_x ? w.x1 : w.z1;
        auto put_roof = [&](int r, int y, int t, std::optional<std::uint16_t> b) {
            if (ridge_along_x) {
                r_.put(r, y, t, b);
            } else {
                r_.put(t, y, r, b);
            }
        };
        for (int i = 0; i < kMaxRoofLayers && lo + i <= hi - i; ++i) {
            const int y = base_y + i;
            const int a = lo + i;
            const int b = hi - i;
            for (int r = ridge_lo; r <= ridge_hi; ++r) {
                put_roof(r, y, a, a == b ? top : block);
                put_roof(r, y, b, a == b ? top : block);
            }
            if (gable) {
                for (int t = std::max(a + 1, wall_lo); t <= std::min(b - 1, wall_hi); ++t) {
                    put_roof(gable_a, y, t, gable);
                    put_roof(gable_b, y, t, gable);
                }
            }
        }
    }

    // PlanCompiler.house Zelle für Zelle: Boden und Decken alle 4 Blöcke, Eckstämme, Fenster auf Augenhöhe (dy = 2)
    // nach houseWindow, Leiter hinten links mit Loch in jeder Zwischendecke (ab 2 Geschossen), Einrichtung je Geschoss
    // nach Innenmaß (furnish), Tür vorn in der Mitte, Satteldach mit Überstand 1 und Giebel aus dem Wandblock.
    // Vorher (bis T-017) ohne Einrichtung und Leiter und mit Fenstern auf den falschen Feldern: gemessen an 14 echten
    // Hausplänen von dev01 lagen 359 Fensterzellen daneben und 263 Zellen Einrichtung fehlten – die Vorschau zeigte
    // ein anderes Haus als das gebaute. Annahme wie überall hier: Der Server kennt jeden genannten Block (firstKnown
    // nimmt den ersten Kandidaten); wer den Nachbau wieder vereinfacht, bringt diese Abweichung zurück.
    void house(const Json& step, std::string wall) {
        const auto from = read_vec(step, "from");
        const auto to = read_vec(step, "to");
        if (!from || !to) {
            return skip_missing("house", "from/to");
        }
        wall = wall.empty() ? "oak_planks" : wall;
        const Box g = box_of(*from, *to);
        const int floors = std::clamp(read_int(step, "floors").value_or(1), 1, 4);
        const int top = g.y0 + floors * kFloorHeight;
        if (!fits(static_cast<std::int64_t>(g.x1 - g.x0 + 3) * (g.z1 - g.z0 + 3) * (top - g.y0 + 14), "house")) {
            return;
        }
        const std::string base = block_base_name(wall);
        const bool planks = base.ends_with("_planks");
        const std::string wood = planks ? base.substr(0, base.size() - std::string_view("_planks").size()) : "oak";
        const auto wall_b = r_.block(wall);
        const std::string corner_name = planks ? log_of(wood) : std::string();
        const auto corner_b = corner_name.empty() ? wall_b : r_.block(corner_name);
        const std::string window = read_str(step, "window_block");
        const auto window_b = r_.block(window.empty() ? "glass_pane" : window);
        const auto ladder_b = r_.block("ladder");
        const bool with_ladder = floors > 1;  // einstöckig führte die Leiter nirgendwohin
        const int ladder_x = g.x0 + 1;
        const int ladder_z = g.z1 - 1;
        const int mid_x = (g.x0 + g.x1) / 2;
        for (int f = 0; f <= floors; ++f) {
            const int yb = g.y0 + f * kFloorHeight;
            for (int x = g.x0; x <= g.x1; ++x) {
                for (int z = g.z0; z <= g.z1; ++z) {
                    const bool edge = x == g.x0 || x == g.x1 || z == g.z0 || z == g.z1;
                    const bool corner = (x == g.x0 || x == g.x1) && (z == g.z0 || z == g.z1);
                    auto slab = wall_b;
                    if (with_ladder && f > 0 && f < floors && x == ladder_x && z == ladder_z) {
                        slab = ladder_b;  // Deckenloch nur in den Zwischendecken
                    } else if (corner && f > 0 && f < floors) {
                        slab = corner_b;
                    }
                    r_.put(x, yb, z, slab);
                    if (f == floors) {
                        continue;
                    }
                    for (int dy = 1; dy < kFloorHeight; ++dy) {
                        std::optional<std::uint16_t> b;  // innen Luft
                        if (!edge) {
                            if (with_ladder && x == ladder_x && z == ladder_z) {
                                b = ladder_b;
                            }
                        } else if (corner) {
                            b = corner_b;
                        } else {
                            b = dy == 2 && house_window(x, z, g, mid_x, ladder_x) ? window_b : wall_b;
                        }
                        r_.put(x, yb + dy, z, b);
                    }
                }
            }
            if (f < floors) {
                furnish(yb, g, mid_x, ladder_x, ladder_z, wood);
            }
        }
        door(Vec{mid_x, g.y0 + 1, g.z0}, wood + "_door");
        const std::string roof_block = read_str(step, "roof_block");
        roof(Box{g.x0, g.x1, top, top, g.z0, g.z1}, "gable", 1, roof_block.empty() ? stairs_for(base) : roof_block,
             wall_b);
    }

    // Netherholz hat _stem statt _log, Bambus keinen Stamm (dann bleibt die Ecke Wandblock) – wie firstKnown im Plugin.
    static std::string log_of(const std::string& wood) {
        if (wood == "crimson" || wood == "warped") {
            return wood + "_stem";
        }
        return wood == "bamboo" ? std::string() : wood + "_log";
    }

    // Erster Kandidat aus PlanCompiler.stairsCandidates: spruce_planks → spruce_stairs,
    // stone_bricks → stone_brick_stairs.
    static std::string stairs_for(const std::string& wall) {
        if (wall.ends_with("_planks")) {
            return wall.substr(0, wall.size() - std::string_view("_planks").size()) + "_stairs";
        }
        if (wall.ends_with("_block")) {
            return wall.substr(0, wall.size() - std::string_view("_block").size()) + "_stairs";
        }
        if (wall.ends_with("s")) {
            return wall.substr(0, wall.size() - 1) + "_stairs";
        }
        return wall + "_stairs";
    }

    // PlanCompiler.houseWindow: jeden 2. Block, nie in Ecken, nie in der Türachse vorn und nie hinter der Leiter.
    static bool house_window(int x, int z, const Box& g, int mid_x, int ladder_x) {
        if (z == g.z0 || z == g.z1) {
            if ((z == g.z0 && x == mid_x) || (z == g.z1 && x == ladder_x)) {
                return false;
            }
            return x != g.x0 && x != g.x1 && (x - g.x0) % 2 == 1;
        }
        return z != g.z0 && z != g.z1 && (z - g.z0) % 2 == 1;
    }

    // PlanCompiler.furnish: Einrichtung hinten rechts und an der rechten Wand, gestaffelt nach Innenmaß; frei bleiben
    // Leiterzelle, die Zelle davor und der Gang Tür → Leiter. Blocknamen ohne Zustände – facing zeigt die Vorschau
    // nicht.
    void furnish(int yb, const Box& g, int mid_x, int ladder_x, int ladder_z, const std::string& wood) {
        const int ix0 = g.x0 + 1;
        const int ix1 = g.x1 - 1;
        const int iz0 = g.z0 + 1;
        const int iz1 = g.z1 - 1;
        const int inner = std::min(ix1 - ix0 + 1, iz1 - iz0 + 1);
        std::set<std::pair<int, int>> taken{{ladder_x, ladder_z}, {ladder_x, ladder_z - 1}};
        for (int z = iz0; z <= ladder_z - 1; ++z) {
            taken.insert({mid_x, z});
        }
        for (int x = ladder_x; x <= mid_x; ++x) {
            taken.insert({x, ladder_z - 1});
        }
        const int y = yb + 1;
        auto place = [&](int x, int z, int at_y, const std::string& name) {
            if (x < ix0 || x > ix1 || z < iz0 || z > iz1 || taken.contains({x, z})) {
                return;
            }
            r_.put(x, at_y, z, r_.block(name));
            taken.insert({x, z});
        };
        if (inner >= 3) {
            place(ix1, iz1, y, "crafting_table");
            place(ix1 - 1, iz1, y, "chest");
        }
        if (inner >= 5) {
            place(ix1 - 2, iz1, y, "furnace");
            const int lx = mid_x + 1;
            const int lz = (iz0 + iz1) / 2;
            if (lx <= ix1 && !(lx == ladder_x && lz == ladder_z)) {
                r_.put(lx, yb + kFloorHeight - 1, lz, r_.block("lantern"));
            }
        }
        const int tz = iz1 - 3;
        if (inner >= 7) {
            if (tz - 1 >= iz0 && !taken.contains({ix1, tz}) && !taken.contains({ix1, tz - 1}) &&
                !taken.contains({ix1, tz + 1})) {
                place(ix1, tz, y, wood + "_fence");
                r_.put(ix1, y + 1, tz, r_.block(wood + "_pressure_plate"));
                place(ix1, tz - 1, y, wood + "_stairs");
                place(ix1, tz + 1, y, wood + "_stairs");
            }
            for (int z = iz0; z <= tz - 2; ++z) {
                place(ix1, z, y, "bookshelf");
            }
        }
        if (inner >= 9) {
            for (int x = ix1 - 2; x <= ix1 - 1; ++x) {
                for (int z = tz - 1; z <= tz + 1; ++z) {
                    place(x, z, y, "red_carpet");
                }
            }
        }
    }

    // PlanCompiler.skyscraper Zelle für Zelle: Decken alle 4 Blöcke (Rand Fassade, innen polished_andesite, Mitte
    // sea_lantern), Fassade mit Pfeilern alle 4 Blöcke und Fensterbändern dazwischen, Leiterschacht hinten links bis
    // aufs Dach, Brüstung, Eingang 3 × 3 vorn in der Mitte. Vorher ohne Leiter und Lampen (bis T-017).
    void skyscraper(const Json& step, std::string facade) {
        const auto from = read_vec(step, "from");
        const auto to = read_vec(step, "to");
        if (!from || !to) {
            return skip_missing("skyscraper", "from/to");
        }
        facade = facade.empty() ? "light_gray_concrete" : facade;
        const Box g = box_of(*from, *to);
        const int floors = std::clamp(read_int(step, "floors").value_or(1), 1, 45);
        const int top = g.y0 + floors * kFloorHeight;
        if (!fits(static_cast<std::int64_t>(g.x1 - g.x0 + 1) * (g.z1 - g.z0 + 1) * (top - g.y0 + 2), "skyscraper")) {
            return;
        }
        const auto facade_b = r_.block(facade);
        const std::string window = read_str(step, "window_block");
        const auto window_b = r_.block(window.empty() ? "glass" : window);
        const auto floor_b = r_.block("polished_andesite");
        const auto light_b = r_.block("sea_lantern");
        const auto ladder_b = r_.block("ladder");
        const int shaft_x = g.x0 + 1;
        const int shaft_z = g.z1 - 1;
        const int mid_x = (g.x0 + g.x1) / 2;
        const int mid_z = (g.z0 + g.z1) / 2;
        for (int f = 0; f <= floors; ++f) {
            const int yb = g.y0 + f * kFloorHeight;
            for (int x = g.x0; x <= g.x1; ++x) {
                for (int z = g.z0; z <= g.z1; ++z) {
                    const bool edge = x == g.x0 || x == g.x1 || z == g.z0 || z == g.z1;
                    auto slab = edge || f == floors ? facade_b : floor_b;
                    if (!edge && x == shaft_x && z == shaft_z && f > 0) {
                        slab = ladder_b;  // Loch in der Decke, die Leiter läuft durch
                    } else if (!edge && f > 0 && x == mid_x && z == mid_z) {
                        slab = light_b;
                    }
                    r_.put(x, yb, z, slab);
                    if (f == floors) {
                        continue;
                    }
                    for (int dy = 1; dy < kFloorHeight; ++dy) {
                        std::optional<std::uint16_t> b;
                        if (!edge) {
                            if (x == shaft_x && z == shaft_z) {
                                b = ladder_b;
                            }
                        } else {
                            const bool corner = (x == g.x0 || x == g.x1) && (z == g.z0 || z == g.z1);
                            const bool pillar = corner || ((z == g.z0 || z == g.z1) && (x - g.x0) % 4 == 0) ||
                                                ((x == g.x0 || x == g.x1) && (z - g.z0) % 4 == 0);
                            b = pillar ? facade_b : window_b;
                        }
                        r_.put(x, yb + dy, z, b);
                    }
                }
            }
        }
        for (int x = g.x0; x <= g.x1; ++x) {
            for (int z = g.z0; z <= g.z1; ++z) {
                if (x == g.x0 || x == g.x1 || z == g.z0 || z == g.z1) {
                    r_.put(x, top + 1, z, facade_b);
                }
            }
        }
        for (int x = mid_x - 1; x <= mid_x + 1; ++x) {
            for (int dy = 1; dy < kFloorHeight; ++dy) {
                r_.put(x, g.y0 + dy, g.z0, std::nullopt);
            }
        }
    }

    Raster& r_;
    PlanBlocks& out_;
    int n_ = 0;
    std::int64_t work_ = 0;
    bool exhausted_ = false;
};

std::string lower_ascii(std::string_view s) {
    std::string out(s);
    for (char& c : out) {
        if (c >= 'A' && c <= 'Z') {
            c = static_cast<char>(c - 'A' + 'a');
        }
    }
    return out;
}

std::string_view trim(std::string_view s) {
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) {
        s.remove_prefix(1);
    }
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t')) {
        s.remove_suffix(1);
    }
    return s;
}

}  // namespace

std::string order_title(std::string_view summary) {
    std::string_view s = trim(summary);
    constexpr std::string_view kPrefix = "Bauauftrag:";
    if (!s.starts_with(kPrefix)) {
        return std::string(s);
    }
    // Erst den Abstand nach dem Doppelpunkt weg: Sonst fände die Suche unten dort das „Doppel-Leerzeichen“ und schnitte
    // den ganzen Auftrag ab (Test „Auftragstext ohne Vorspann“, 2026-10-29).
    s = trim(s.substr(kPrefix.size()));
    // „\n\n“ nach dem Auftrag kommt aus log::safe als zwei Leerzeichen an; dahinter steht die Umgebung (Englisch, für
    // das Modell) – die gehört nicht in die Überschrift.
    if (const std::size_t cut = s.find("  "); cut != std::string_view::npos) {
        s = s.substr(0, cut);
    }
    return std::string(trim(s));
}

std::string block_base_name(std::string_view block) {
    std::string_view s = trim(block);
    if (const std::size_t bracket = s.find('['); bracket != std::string_view::npos) {
        s = s.substr(0, bracket);
    }
    if (s.starts_with("minecraft:")) {
        s.remove_prefix(10);
    }
    return lower_ascii(trim(s));
}

std::uint32_t block_rgb(std::string_view block) {
    const std::string name = block_base_name(block);
    const std::string_view n = name;
    if (n == kModuleMarker) {
        return 0xa78bfa;  // Akzent der App: Bauteil aus der Modulbibliothek
    }
    if (n == kLogicMarker) {
        return 0xf87171;
    }
    // Gefärbte Blöcke zuerst nach Farbstoff: „light_blue“/„light_gray“ vor „blue“/„gray“.
    struct Dye {
        std::string_view prefix;
        std::uint32_t rgb;
    };
    static constexpr std::array<Dye, 16> kDyes{{{"light_blue_", 0x3aafd9}, {"light_gray_", 0x8e8e86},
                                                 {"white_", 0xe9ecec},      {"orange_", 0xf07613},
                                                 {"magenta_", 0xbd44b3},    {"yellow_", 0xf8c527},
                                                 {"lime_", 0x70b919},       {"pink_", 0xed8dac},
                                                 {"gray_", 0x3e4447},       {"cyan_", 0x158991},
                                                 {"purple_", 0x792aac},     {"blue_", 0x35399d},
                                                 {"brown_", 0x724728},      {"green_", 0x546d1b},
                                                 {"red_", 0xa12722},        {"black_", 0x141519}}};
    constexpr std::array<std::string_view, 9> kDyed{"concrete", "wool",   "terracotta", "stained_glass", "carpet",
                                                    "glazed",   "candle", "banner",     "shulker"};
    for (const Dye& d : kDyes) {
        if (n.starts_with(d.prefix)) {
            for (std::string_view kind : kDyed) {
                if (n.find(kind) != std::string_view::npos) {
                    return d.rgb;
                }
            }
        }
    }
    struct Material {
        std::string_view part;
        std::uint32_t rgb;
    };
    // Reihenfolge trägt: Lichtquellen vor „redstone“/„stone“, „dark_oak“ vor „oak“, „mossy“ vor „stone_brick“,
    // „stone_brick“ vor „brick“ und „stone“, die *stone-Varianten vor „stone“.
    static constexpr std::array<Material, 52> kMaterials{{
        {"lantern", 0xf6d365},     {"torch", 0xf6d365},       {"glowstone", 0xf6d365},   {"froglight", 0xf6d365},
        {"lamp", 0xf6d365},        {"redstone", 0xc81e1e},    {"dark_oak", 0x4a3218},    {"spruce", 0x735532},
        {"birch", 0xc8b77a},       {"jungle", 0xa07350},      {"acacia", 0xa85a32},      {"mangrove", 0x763631},
        {"cherry", 0xe2b2ac},      {"bamboo", 0xc2af52},      {"crimson", 0x7e3a56},     {"warped", 0x2b6963},
        {"oak", 0xa2834f},         {"deepslate", 0x4d4d51},   {"blackstone", 0x2a2328},  {"nether_brick", 0x2c1619},
        {"end_stone", 0xdbde9e},   {"purpur", 0xa97ca9},      {"prismarine", 0x63ab9e},  {"sandstone", 0xd8cb9b},
        {"sand", 0xdbcfa3},        {"mossy", 0x6e7b5a},       {"stone_brick", 0x7a7979},       {"cobble", 0x7f7f7f},
        {"andesite", 0x888888},    {"diorite", 0xbdbdbd},     {"granite", 0x9a6b5a},     {"smooth_stone", 0x9e9e9e},
        {"stone", 0x7d7d7d},       {"brick", 0x96503f},       {"quartz", 0xebe5de},      {"glass", 0xbfe6f5},
        {"pane", 0xbfe6f5},        {"water", 0x3f76e4},       {"leaves", 0x4a7f2a},      {"grass", 0x5d9b3a},
        {"moss", 0x597a30},        {"dirt", 0x866043},        {"path", 0x94794a},        {"gravel", 0x857f7e},
        {"snow", 0xfafafa},        {"hay", 0xc8a32c},         {"iron", 0xd8d8d8},        {"gold", 0xf5d33c},
        {"copper", 0xc0704f},      {"terracotta", 0x985e43},  {"concrete", 0xcfd5d6},    {"obsidian", 0x1b1528},
    }};
    for (const Material& m : kMaterials) {
        if (n.find(m.part) != std::string_view::npos) {
            return m.rgb;
        }
    }
    // Unbekannt: FNV-1a über den Namen → gedeckte Farbe (je Kanal 0x50 … 0xaf), damit sie sich von den bekannten abhebt,
    // ohne zu leuchten.
    std::uint32_t h = 2166136261u;
    for (const char c : n) {
        h = (h ^ static_cast<unsigned char>(c)) * 16777619u;
    }
    const auto chan = [](std::uint32_t v) { return 0x50u + (v & 0xffu) % 0x60u; };
    return (chan(h >> 16) << 16) | (chan(h >> 8) << 8) | chan(h);
}

std::optional<std::string_view> PlanBlocks::at(int x, int y, int z) const {
    if (x < kCoordLow || x > kCoordHigh || y < kCoordLow || y > kCoordHigh || z < kCoordLow || z > kCoordHigh) {
        return std::nullopt;
    }
    const auto it = cells.find(pack(x, y, z));
    if (it == cells.end()) {
        return std::nullopt;
    }
    return std::string_view(palette[it->second]);
}

std::optional<std::string> cut_truncated_plan(std::string_view text) {
    const std::size_t start = text.find('{');
    if (start == std::string_view::npos) {
        return std::nullopt;
    }
    struct Frame {
        char open;
        std::size_t safe;  // Ende des letzten vollständigen Eintrags (nur Listen)
    };
    std::vector<Frame> stack;
    bool in_string = false;
    bool escaped = false;
    for (std::size_t i = start; i < text.size(); ++i) {
        const char c = text[i];
        if (in_string) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                in_string = false;
            }
            continue;
        }
        if (c == '"') {
            in_string = true;
        } else if (c == '{' || c == '[') {
            stack.push_back({c, i + 1});
        } else if (c == '}' || c == ']') {
            if (stack.empty()) {
                return std::nullopt;
            }
            stack.pop_back();
            if (stack.empty()) {
                return std::nullopt;  // vollständig – dann war der Fehler kein Abbruch, sondern etwas anderes
            }
            if (stack.back().open == '[') {
                stack.back().safe = i + 1;
            }
        } else if (c == ',' && !stack.empty() && stack.back().open == '[') {
            // Ende eines einfachen Eintrags (Zahl, Text) in einer Liste – vor dem Komma.
            stack.back().safe = std::max(stack.back().safe, i);
        }
    }
    // Wie JsonRepair: an der sicheren Stelle der tiefsten Liste schneiden, deren angefangener Eintrag ein Objekt ist
    // (beim Bauplan die Schrittliste) – ein halber Schritt hieße raten, welche Koordinate noch fehlte.
    // Ohne angefangenes Objekt (Abbruch direkt nach „},“) die innerste offene Liste selbst. Vorher zählte die innerste
    // Liste immer – bei einem Abbruch in „"pos":[10,0,-9“ blieb ein halber Schritt ohne pos stehen (T-017).
    std::size_t cut = stack.size();
    for (std::size_t k = 0; k + 1 < stack.size(); ++k) {
        if (stack[k].open == '[' && stack[k + 1].open == '{') {
            cut = k;
        }
    }
    if (cut == stack.size() && stack.back().open == '[') {
        cut = stack.size() - 1;
    }
    if (cut == stack.size()) {
        return std::nullopt;
    }
    std::string out(text.substr(start, stack[cut].safe - start));
    for (std::size_t k = cut + 1; k-- > 0;) {
        out += stack[k].open == '[' ? ']' : '}';
    }
    return out;
}

PlanBlocks rasterize_plan(std::string_view json_text) {
    PlanBlocks out;
    std::optional<Json> j = try_parse_utf8(json_text, ParseOptions{false, true});
    bool repaired = false;
    if (!j) {
        // Erreicht die Antwort max_tokens, bricht das JSON mitten in einem Schritt ab. Das Plugin baut dann alle
        // vollständigen Schritte (JsonRepair); die Vorschau blieb vorher leer – gemessen bei 4 von 50 echten Plänen
        // (dev01, T-017, 2026-11-21), während im Spiel gebaut wurde.
        if (const auto cut = cut_truncated_plan(json_text)) {
            j = try_parse_utf8(*cut, ParseOptions{false, true});
            repaired = j.has_value();
        }
    }
    if (!j || !j->is_object()) {
        out.error = "keine gültige JSON-Antwort";
        return out;
    }
    if (repaired) {
        out.notes.push_back(
            "Antwort abgeschnitten – gezeigt sind die vollständigen Schritte, wie das Plugin sie baut.");
    }
    const Json* steps = json_find(*j, "steps");
    if (steps == nullptr || !steps->is_array()) {
        out.error = "kein Bauplan (steps fehlt)";
        return out;
    }
    out.design = read_str(*j, "design");
    out.reply = read_str(*j, "reply");
    out.mode = read_str(*j, "mode");
    out.steps = static_cast<int>(steps->size());
    Raster raster(out);
    StepPainter painter(raster, out);
    int n = 0;
    for (const Json& step : *steps) {
        ++n;
        if (!step.is_object()) {
            out.notes.push_back("Schritt " + std::to_string(n) + ": kein Objekt – ausgelassen.");
            continue;
        }
        painter.paint(step, n);
        if (raster.full()) {
            out.notes.push_back("Vorschau bei " + std::to_string(kMaxPreviewBlocks) + " Blöcken abgebrochen (Schritt " +
                                std::to_string(n) + " von " + std::to_string(out.steps) + ").");
            break;
        }
        if (painter.exhausted()) {
            out.notes.push_back("Vorschau bei " + std::to_string(kMaxPreviewWork) +
                                " Blockoperationen abgebrochen (Schritt " + std::to_string(n) + " von " +
                                std::to_string(out.steps) + ").");
            break;
        }
    }
    raster.drop_lone_halves();
    if (out.clipped > 0) {
        out.notes.push_back(std::to_string(out.clipped) + " Blöcke außerhalb des darstellbaren Bereichs ausgelassen.");
    }
    bool first = true;
    for (const auto& entry : out.cells) {
        const auto [x, y, z] = unpack(entry.first);
        if (first) {
            out.min_x = out.max_x = x;
            out.min_y = out.max_y = y;
            out.min_z = out.max_z = z;
            first = false;
            continue;
        }
        out.min_x = std::min(out.min_x, x);
        out.max_x = std::max(out.max_x, x);
        out.min_y = std::min(out.min_y, y);
        out.max_y = std::max(out.max_y, y);
        out.min_z = std::min(out.min_z, z);
        out.max_z = std::max(out.max_z, z);
    }
    out.ok = true;
    return out;
}

PlanView top_view(const PlanBlocks& plan) {
    PlanView v;
    if (plan.empty()) {
        return v;
    }
    v.width = plan.max_x - plan.min_x + 1;
    v.height = plan.max_z - plan.min_z + 1;
    v.origin_u = plan.min_x;
    v.origin_v = plan.min_z;
    v.cells.assign(static_cast<std::size_t>(v.width) * v.height, ViewCell{});
    for (const auto& [key, block] : plan.cells) {
        const auto [x, y, z] = unpack(key);
        ViewCell& c = v.cells[static_cast<std::size_t>(z - v.origin_v) * v.width + (x - v.origin_u)];
        if (c.block < 0 || y > c.level) {
            c.block = block;
            c.level = y;
        }
    }
    return v;
}

PlanView section_view(const PlanBlocks& plan, int z) {
    PlanView v;
    if (plan.empty()) {
        return v;
    }
    v.width = plan.max_x - plan.min_x + 1;
    v.height = plan.max_y - plan.min_y + 1;
    v.origin_u = plan.min_x;
    v.origin_v = plan.min_y;
    v.cells.assign(static_cast<std::size_t>(v.width) * v.height, ViewCell{});
    for (const auto& [key, block] : plan.cells) {
        const auto [x, y, cz] = unpack(key);
        if (cz == z) {
            v.cells[static_cast<std::size_t>(y - v.origin_v) * v.width + (x - v.origin_u)].block = block;
        }
    }
    return v;
}

}  // namespace swbh::gui
