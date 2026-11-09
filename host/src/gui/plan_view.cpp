#include "gui/plan_view.hpp"

#include "base/json.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
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

// Wie PlanCompiler.inDisc/inBall: Abstand < r + 0,5.
bool in_disc(int dx, int dz, int r) { return dx * dx + dz * dz < (r + 0.5) * (r + 0.5); }
bool in_ball(int dx, int dy, int dz, int r) { return dx * dx + dy * dy + dz * dz < (r + 0.5) * (r + 0.5); }

class Raster {
public:
    explicit Raster(PlanBlocks& out) : out_(out) {}

    // Blockname → Paletten-Index; std::nullopt für Luft (löscht).
    std::optional<std::uint16_t> block(std::string_view name) {
        const std::string base = block_base_name(name);
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

    bool full() const { return full_; }

private:
    PlanBlocks& out_;
    std::unordered_map<std::string, std::uint16_t> index_;
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
        const std::string block = read_str(step, "block");
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
            box(b, r_.block(block), op == "fill" ? Shell::None : op == "walls" ? Shell::Walls : Shell::All);
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
            r_.put(pos->x, pos->y, pos->z, r_.block(block));
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
            roof(b, shape, std::clamp(read_int(step, "overhang").value_or(1), 0, 2), r_.block(block),
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
        r_.put(p.x, p.y, p.z, b);
        r_.put(p.x, p.y + 1, p.z, b);
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
    // Seite und Giebelfüllung innerhalb der Wände. Treppenrichtungen und Platten spielen für die Vorschau keine Rolle –
    // jede Schicht trägt den Dachblock.
    void roof(const Box& w, const std::string& shape, int o, std::optional<std::uint16_t> block,
              std::optional<std::uint16_t> gable) {
        const int x0 = w.x0 - o;
        const int x1 = w.x1 + o;
        const int z0 = w.z0 - o;
        const int z1 = w.z1 + o;
        const int base_y = w.y0;
        if (shape == "flat") {
            for (int z = z0; z <= z1; ++z) {
                for (int x = x0; x <= x1; ++x) {
                    r_.put(x, base_y, z, block);
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
                            r_.put(x, y, z, block);
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
                put_roof(r, y, a, block);
                put_roof(r, y, b, block);
            }
            if (gable) {
                for (int t = std::max(a + 1, wall_lo); t <= std::min(b - 1, wall_hi); ++t) {
                    put_roof(gable_a, y, t, gable);
                    put_roof(gable_b, y, t, gable);
                }
            }
        }
    }

    // PlanCompiler.house ohne Einrichtung und Leiter: Boden und Decken alle 4 Blöcke, Wände mit Eckstämmen, Fenster auf
    // Augenhöhe (dy = 2) jeden zweiten Block (das Plugin wählt sie nach Innenmaß – hier angenähert), Tür vorn in der
    // Mitte, Satteldach mit Überstand 1 und Giebel aus dem Wandblock.
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
        const std::string wood = base.ends_with("_planks") ? base.substr(0, base.size() - 7) : "oak";
        const auto wall_b = r_.block(wall);
        const auto corner_b = base.ends_with("_planks") ? r_.block(wood + "_log") : wall_b;
        std::string window = read_str(step, "window_block");
        const auto window_b = r_.block(window.empty() ? "glass_pane" : window);
        const int mid_x = (g.x0 + g.x1) / 2;
        for (int f = 0; f <= floors; ++f) {
            const int yb = g.y0 + f * kFloorHeight;
            for (int x = g.x0; x <= g.x1; ++x) {
                for (int z = g.z0; z <= g.z1; ++z) {
                    const bool edge = x == g.x0 || x == g.x1 || z == g.z0 || z == g.z1;
                    const bool corner = (x == g.x0 || x == g.x1) && (z == g.z0 || z == g.z1);
                    r_.put(x, yb, z, corner && f > 0 && f < floors ? corner_b : wall_b);
                    if (f == floors) {
                        continue;
                    }
                    for (int dy = 1; dy < kFloorHeight; ++dy) {
                        std::optional<std::uint16_t> b;  // innen Luft
                        if (corner) {
                            b = corner_b;
                        } else if (edge) {
                            const int along = (z == g.z0 || z == g.z1) ? x - g.x0 : z - g.z0;
                            b = dy == 2 && along % 2 == 0 && x != mid_x ? window_b : wall_b;
                        }
                        r_.put(x, yb + dy, z, b);
                    }
                }
            }
        }
        const auto door_b = r_.block(wood + "_door");
        r_.put(mid_x, g.y0 + 1, g.z0, door_b);
        r_.put(mid_x, g.y0 + 2, g.z0, door_b);
        const std::string roof_block = read_str(step, "roof_block");
        const auto roof_b = r_.block(roof_block.empty() ? wood + "_stairs" : roof_block);
        roof(Box{g.x0, g.x1, top, top, g.z0, g.z1}, "gable", 1, roof_b, wall_b);
    }

    // PlanCompiler.skyscraper ohne Leiter und Lampen: Decken alle 4 Blöcke (Rand Fassade, innen polished_andesite),
    // Fassade mit Pfeilern alle 4 Blöcke und Fensterbändern dazwischen, Brüstung, Eingang 3 × 3 vorn in der Mitte.
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
        for (int f = 0; f <= floors; ++f) {
            const int yb = g.y0 + f * kFloorHeight;
            for (int x = g.x0; x <= g.x1; ++x) {
                for (int z = g.z0; z <= g.z1; ++z) {
                    const bool edge = x == g.x0 || x == g.x1 || z == g.z0 || z == g.z1;
                    r_.put(x, yb, z, edge || f == floors ? facade_b : floor_b);
                    if (f == floors) {
                        continue;
                    }
                    for (int dy = 1; dy < kFloorHeight; ++dy) {
                        std::optional<std::uint16_t> b;
                        if (edge) {
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
        const int mid_x = (g.x0 + g.x1) / 2;
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

PlanBlocks rasterize_plan(std::string_view json_text) {
    PlanBlocks out;
    const std::optional<Json> j = try_parse_utf8(json_text, ParseOptions{false, true});
    if (!j || !j->is_object()) {
        out.error = "keine gültige JSON-Antwort";
        return out;
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
