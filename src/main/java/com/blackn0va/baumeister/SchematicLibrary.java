package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.Nbt;
import com.blackn0va.baumeister.plan.Snbt;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.Pos;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.block.BaseBlock;

import org.enginehub.linbus.tree.LinByteArrayTag;
import org.enginehub.linbus.tree.LinByteTag;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinDoubleTag;
import org.enginehub.linbus.tree.LinFloatTag;
import org.enginehub.linbus.tree.LinIntArrayTag;
import org.enginehub.linbus.tree.LinIntTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinLongArrayTag;
import org.enginehub.linbus.tree.LinLongTag;
import org.enginehub.linbus.tree.LinShortTag;
import org.enginehub.linbus.tree.LinStringTag;
import org.enginehub.linbus.tree.LinTag;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Bauteile als WorldEdit-Schematics unter plugins/Baumeister/bauteile/ plus bauteile.yml:
 *
 * <pre>
 * kolbentuer:
 *   datei: kolbentuer.schem
 *   vorne: s            # Himmelsrichtung, in die die Vorderseite beim Speichern zeigte
 *   beschreibung: 2x2-Kolbentür, Knopf auf beiden Seiten
 * </pre>
 *
 * Diese Klasse berührt WorldEdit-Klassen; sie wird nur erzeugt, wenn WorldEdit geladen ist
 * (softdepend).
 *
 * Blockdaten (seit 2026-11-08, Plan B6): Gespeichert werden sie nur, wenn ein Admin speichert – für alle anderen
 * entfernt save sie vor dem Schreiben (Truhen-Inhalte, Befehle, Schildtexte aus fremden Plots blieben sonst in der
 * Datei). Geladen werden sie als geprüfter Baum (BlockSpec.withTrustedNbt); ob sie beim Nachbauen gesetzt werden,
 * entscheidet die Stufe des Auftraggebers beim Setzen (BuildJob). Jeder gespeicherte Name hat einen Besitzer
 * (besitzer in bauteile.yml); überschreiben darf ihn nur der Besitzer oder ein Admin.
 */
final class SchematicLibrary implements ModuleLibrary {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final int MAX_EDGE = 32;
    static final int MAX_CELLS = 4000;

    private final File dir;
    private final File index;
    private final Map<String, Module> modules = new LinkedHashMap<>();

    SchematicLibrary(File dataFolder) {
        this.dir = new File(dataFolder, "bauteile");
        this.index = new File(dir, "bauteile.yml");
    }

    static boolean validName(String name) {
        return name.matches("[a-z0-9_]{2,32}");
    }

    /** Wert von „daten“ in bauteile.yml, wenn ein Admin mit Blockdaten gespeichert hat. */
    static final String DATA_FROM_ADMIN = "admin";

    /**
     * Lädt ein Bauteil seine Blockdaten? Nur mit dem Vermerk eines Admins (Sicherheitsprüfung 2026-11-08, Befund 4):
     * Bauteil-Daten gelten als vertrauenswürdig und gehen nie durch die Bestätigung. Vor 2026-11-08 speicherte Vera
     * aber für jeden Spieler volle Blöcke, auch aus fremden Plots – ohne Vermerk wären deren Kisteninhalte und Befehle
     * jetzt mit Admin-Vertrauen gebaut worden. Altbestand und Einträge von Nicht-Admins laden deshalb nur Zustände.
     */
    static boolean trustsData(String mark) {
        return DATA_FROM_ADMIN.equals(mark);
    }

    /** Liest alles neu. Ein kaputtes Bauteil wird übersprungen und gemeldet, nicht die ganze Bibliothek. */
    void reload() {
        modules.clear();
        if (!index.isFile()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(index);
        for (String name : yml.getKeys(false)) {
            ConfigurationSection sec = yml.getConfigurationSection(name);
            if (sec == null || !validName(name)) {
                LOG.warning(() -> "bauteile | ungültiger_eintrag | name=" + name);
                continue;
            }
            try {
                modules.put(name, load(name, sec));
            } catch (IOException | PlanException | RuntimeException e) {
                LOG.log(Level.WARNING, "bauteile | laden_fehlgeschlagen | name=" + name + " grund=" + e.getMessage());
            }
        }
        LOG.info(() -> "bauteile | geladen | anzahl=" + modules.size());
    }

    private Module load(String name, ConfigurationSection sec) throws IOException, PlanException {
        File file = new File(dir, sec.getString("datei", name + ".schem"));
        // Nur Dateien direkt im Bauteil-Ordner – kein ../ aus einer bearbeiteten bauteile.yml (ASVS V12.3.1).
        if (!file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())) {
            throw new IOException("Datei liegt außerhalb von bauteile/");
        }
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            throw new IOException("unbekanntes Schematic-Format: " + file.getName());
        }
        Clipboard clip;
        try (InputStream in = Files.newInputStream(file.toPath()); ClipboardReader reader = format.getReader(in)) {
            clip = reader.read();
        }
        Region region = clip.getRegion();
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 size = region.getMaximumPoint().subtract(min).add(1, 1, 1);
        if (size.x() > MAX_EDGE || size.y() > MAX_EDGE || size.z() > MAX_EDGE) {
            throw new IOException("größer als " + MAX_EDGE + " je Richtung");
        }
        Cardinal front = Cardinal.parse(sec.getString("vorne", "s"));
        // Vorderseite zeigte nach "front" → so, als hätte der Spieler beim Bau in die Gegenrichtung geschaut.
        Orientation o = new Orientation(front.opposite());
        List<CompiledBuild.Change> cells = new ArrayList<>();
        int lx = Integer.MAX_VALUE;
        int lz = Integer.MAX_VALUE;
        boolean trustData = trustsData(sec.getString("daten"));
        int dropped = 0;
        for (BlockVector3 v : region) {
            BaseBlock full = clip.getFullBlock(v);
            String state = full.toImmutableState().getAsString();
            LazyReference<LinCompoundTag> nbt = full.getNbtReference();
            if (!trustData && nbt != null) {
                nbt = null;
                dropped++;
            }
            BlockSpec spec = withData(name, v, o.toLocal(BlockSpec.parseTrusted(state)), nbt);
            Pos rel = o.toLocalOffset(new Pos(v.x() - min.x(), v.y() - min.y(), v.z() - min.z()));
            lx = Math.min(lx, rel.x());
            lz = Math.min(lz, rel.z());
            cells.add(new CompiledBuild.Change(rel, spec));
        }
        if (cells.size() > MAX_CELLS) {
            throw new IOException("mehr als " + MAX_CELLS + " Blöcke");
        }
        if (dropped > 0) {
            int n = dropped;
            LOG.info(() -> "bauteile | daten_ohne_herkunft | name=" + name + " blöcke=" + n
                    + " – geladen ohne Blockdaten (nicht von einem Admin gespeichert)");
        }
        List<CompiledBuild.Change> normalized = new ArrayList<>(cells.size());
        int maxX = 0;
        int maxZ = 0;
        for (CompiledBuild.Change c : cells) {
            Pos p = c.pos().add(-lx, 0, -lz);
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
            normalized.add(new CompiledBuild.Change(p, c.block()));
        }
        return new Module(name, sec.getString("beschreibung", ""), new Pos(maxX + 1, size.y(), maxZ + 1),
                List.copyOf(normalized));
    }

    /**
     * Blockdaten einer Zelle als geprüfter Baum. Was die Prüfung nicht besteht (Befehl mit „~“-Versatz oder „^“, die
     * nicht mit dem Bau drehen; Daten über 32 KiB), fällt mit Warnung weg – der Block bleibt, wie vor 2026-11-08 ganz
     * ohne Daten.
     */
    private static BlockSpec withData(String module, BlockVector3 v, BlockSpec spec,
            LazyReference<LinCompoundTag> ref) {
        LinCompoundTag tag = ref == null ? null : ref.getValue();
        if (tag == null || tag.value().isEmpty()) {
            return spec;
        }
        try {
            Nbt.Compound data = (Nbt.Compound) toNbt(tag);
            if (Snbt.write(data).length() > Snbt.MAX_TRUSTED) {
                throw new PlanException("Blockdaten über 32 KiB");
            }
            return spec.withTrustedNbt(data);
        } catch (PlanException | RuntimeException e) {
            LOG.warning(() -> "bauteile | daten_verworfen | name=" + module + " x=" + v.x() + " y=" + v.y() + " z="
                    + v.z() + " grund=" + e.getMessage());
            return spec;
        }
    }

    /** lin-bus-Baum (WorldEdit) → Nbt-Baum, ohne Umweg über Text (keine Escape-Fragen). */
    static Nbt toNbt(LinTag<?> tag) {
        return switch (tag) {
            case LinCompoundTag c -> {
                Map<String, Nbt> m = new LinkedHashMap<>();
                c.value().forEach((k, val) -> m.put(k, toNbt(val)));
                yield new Nbt.Compound(m);
            }
            case LinListTag<?> l -> {
                List<Nbt> items = new ArrayList<>();
                for (LinTag<?> item : l.value()) {
                    items.add(toNbt(item));
                }
                yield new Nbt.ListTag(items);
            }
            case LinStringTag t -> new Nbt.Str(t.value());
            case LinByteTag t -> new Nbt.Num('b', BigDecimal.valueOf(t.valueAsByte()));
            case LinShortTag t -> new Nbt.Num('s', BigDecimal.valueOf(t.valueAsShort()));
            case LinIntTag t -> new Nbt.Num('i', BigDecimal.valueOf(t.valueAsInt()));
            case LinLongTag t -> new Nbt.Num('l', BigDecimal.valueOf(t.valueAsLong()));
            case LinFloatTag t -> new Nbt.Num('f', new BigDecimal(Float.toString(t.valueAsFloat())));
            case LinDoubleTag t -> new Nbt.Num('d', new BigDecimal(Double.toString(t.valueAsDouble())));
            case LinByteArrayTag t -> {
                List<Long> vals = new ArrayList<>();
                for (byte b : t.value()) {
                    vals.add((long) b);
                }
                yield new Nbt.Arr('B', vals);
            }
            case LinIntArrayTag t -> {
                List<Long> vals = new ArrayList<>();
                for (int i : t.value()) {
                    vals.add((long) i);
                }
                yield new Nbt.Arr('I', vals);
            }
            case LinLongArrayTag t -> {
                List<Long> vals = new ArrayList<>();
                for (long x : t.value()) {
                    vals.add(x);
                }
                yield new Nbt.Arr('L', vals);
            }
            default -> throw new IllegalArgumentException("unbekannter NBT-Typ " + tag.getClass().getSimpleName());
        };
    }

    /**
     * Darf player den Namen belegen? Ohne Eintrag ja; mit Eintrag nur der Besitzer oder ein Admin. Einträge ohne
     * Besitzer (vor 2026-11-08, /bm bauteil) gehören dem Server – nur Admins. null = erlaubt, sonst der Grund (deutsch).
     */
    static String overwriteProblem(String name, boolean exists, String ownerId, String ownerName, UUID player,
            boolean admin) {
        if (!exists || admin) {
            return null;
        }
        if (ownerId != null && ownerId.equals(player.toString())) {
            return null;
        }
        return "Den Namen „" + name + "“ hat schon " + (ownerName != null ? ownerName : "der Server")
                + " belegt – nimm einen anderen.";
    }

    /**
     * Speichert die aktuelle WorldEdit-Auswahl des Spielers als Bauteil. „vorne“ ist die
     * Himmelsrichtung, in die die Vorderseite zeigt (dorthin, wo man davorsteht).
     */
    String save(Player player, String name, Cardinal front, String description) throws IOException {
        if (!validName(name)) {
            return "Name nur aus a-z, 0-9 und _ (2–32 Zeichen).";
        }
        com.sk89q.worldedit.entity.Player wp = BukkitAdapter.adapt(player);
        LocalSession session = WorldEdit.getInstance().getSessionManager().get(wp);
        Region region;
        try {
            region = session.getSelection(session.getSelectionWorld());
        } catch (IncompleteRegionException e) {
            return "Erst mit WorldEdit auswählen (//wand, //pos1, //pos2).";
        }
        return save(player, region, name, front, description);
    }

    /**
     * Alles, was sich VOR der Bereichsprüfung von „merk dir das Bauwerk“ entscheiden lässt: Name, Welt, Größe,
     * Besitz des Namens. Sicherheitsprüfung 2026-11-08, Befund 2: Vorher lief die Prüfung über jeden Block des
     * Quaders, bevor die Größe geprüft wurde – zwei Ecken 20 000 Blöcke auseinander luden Chunks im Hauptthread bis
     * zum Logout. null = darf geprüft werden, sonst der Grund (deutsch).
     */
    String rememberProblem(Player player, org.bukkit.Location a, org.bukkit.Location b, String name) {
        if (!validName(name)) {
            return "Name nur aus a-z, 0-9 und _ (2–32 Zeichen).";
        }
        if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            return "Beide Ecken müssen in derselben Welt liegen.";
        }
        String size = sizeProblem(Math.abs(a.getBlockX() - b.getBlockX()) + 1,
                Math.abs(a.getBlockY() - b.getBlockY()) + 1, Math.abs(a.getBlockZ() - b.getBlockZ()) + 1);
        if (size != null) {
            return size;
        }
        YamlConfiguration known = index.isFile() ? YamlConfiguration.loadConfiguration(index) : new YamlConfiguration();
        return overwriteProblem(name, known.contains(name), known.getString(name + ".besitzer"),
                known.getString(name + ".besitzer-name"), player.getUniqueId(), player.hasPermission("baumeister.admin"));
    }

    /** Kantenlängen gegen MAX_EDGE; null = passt. */
    static String sizeProblem(int dx, int dy, int dz) {
        if (dx > MAX_EDGE || dy > MAX_EDGE || dz > MAX_EDGE) {
            return "Auswahl zu groß – höchstens " + MAX_EDGE + " je Richtung (gezeigt: " + dx + " × " + dy + " × " + dz
                    + ").";
        }
        return null;
    }

    /**
     * Wie save, aber aus zwei Ecken statt der WorldEdit-Auswahl – für „Vera, Ecke eins / Ecke zwei / merk dir das
     * Bauwerk als …“ (2026-11-05), ohne Zauberstab. null = gespeichert, sonst der Grund.
     */
    String saveCorners(Player player, org.bukkit.Location a, org.bukkit.Location b, String name, Cardinal front,
            String description) throws IOException {
        if (!validName(name)) {
            return "Name nur aus a-z, 0-9 und _ (2–32 Zeichen).";
        }
        if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            return "Beide Ecken müssen in derselben Welt liegen.";
        }
        Region region = new com.sk89q.worldedit.regions.CuboidRegion(BukkitAdapter.adapt(a.getWorld()),
                BlockVector3.at(a.getBlockX(), a.getBlockY(), a.getBlockZ()),
                BlockVector3.at(b.getBlockX(), b.getBlockY(), b.getBlockZ()));
        return save(player, region, name, front, description);
    }

    private String save(Player player, Region region, String name, Cardinal front, String description)
            throws IOException {
        BlockVector3 size = region.getMaximumPoint().subtract(region.getMinimumPoint()).add(1, 1, 1);
        if (size.x() > MAX_EDGE || size.y() > MAX_EDGE || size.z() > MAX_EDGE) {
            return "Auswahl zu groß – höchstens " + MAX_EDGE + " je Richtung.";
        }
        boolean admin = player.hasPermission("baumeister.admin");
        YamlConfiguration known = index.isFile() ? YamlConfiguration.loadConfiguration(index) : new YamlConfiguration();
        String taken = overwriteProblem(name, known.contains(name), known.getString(name + ".besitzer"),
                known.getString(name + ".besitzer-name"), player.getUniqueId(), admin);
        if (taken != null) {
            return taken;
        }
        BlockArrayClipboard clip = new BlockArrayClipboard(region);
        try (EditSession es = WorldEdit.getInstance().newEditSession(region.getWorld())) {
            ForwardExtentCopy copy = new ForwardExtentCopy(es, region, clip, region.getMinimumPoint());
            // Entities (Rüstungsständer, Rahmen, Tiere) lädt load nie – sie gehören nicht in die Datei.
            copy.setCopyingEntities(false);
            Operations.complete(copy);
            if (!admin) {
                stripData(clip, region);
            }
        } catch (WorldEditException e) {
            throw new IOException("Kopieren fehlgeschlagen: " + e.getMessage(), e);
        }
        Files.createDirectories(dir.toPath());
        File file = new File(dir, name + ".schem");
        try (OutputStream out = Files.newOutputStream(file.toPath());
                ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out)) {
            writer.write(clip);
        }
        YamlConfiguration yml = index.isFile() ? YamlConfiguration.loadConfiguration(index) : new YamlConfiguration();
        yml.set(name + ".datei", file.getName());
        yml.set(name + ".vorne", front.name().substring(0, 1).toLowerCase(Locale.ROOT));
        yml.set(name + ".beschreibung", description);
        yml.set(name + ".besitzer", player.getUniqueId().toString());
        yml.set(name + ".besitzer-name", player.getName());
        // Herkunft der Blockdaten (Befund 4): nur „admin“ lädt load mit Daten.
        yml.set(name + ".daten", admin ? DATA_FROM_ADMIN : null);
        yml.save(index);
        reload();
        LOG.info(() -> String.format("bauteile | gespeichert | name=%s größe=%dx%dx%d von=%s daten=%s", name,
                size.x(), size.y(), size.z(), player.getName(), admin ? "mit" : "ohne"));
        return null;
    }

    /** Blockdaten aus der Kopie entfernen (Nicht-Admins, Plan B6): nur Zustände bleiben. */
    private static void stripData(BlockArrayClipboard clip, Region region) throws WorldEditException {
        for (BlockVector3 v : region) {
            BaseBlock full = clip.getFullBlock(v);
            if (full.getNbtReference() != null) {
                clip.setBlock(v, full.toImmutableState());
            }
        }
    }

    @Override
    public Optional<Module> find(String name) {
        return Optional.ofNullable(modules.get(name.toLowerCase(Locale.ROOT)));
    }

    @Override
    public List<Module> all() {
        return List.copyOf(modules.values());
    }
}
