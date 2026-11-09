package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.TemplateCatalog;
import com.blackn0va.baumeister.plan.TemplateModules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.structure.Structure;

/**
 * Die Bauvorlagen von Minecraft (Dorfhäuser, Iglu, Schiff …) als Bauteile – die Zwischenschicht aus TemplateCatalog.
 * Gelesen wird über Bukkits StructureManager aus dem Server-JAR, erst beim ersten Gebrauch, danach aus dem Speicher.
 *
 * all() ist bewusst leer: Die Vorlagen stehen nicht in der Bauteil-Liste des LLM-Prompts. 300 Namen kosteten jeden
 * Auftrag Tausende Token (der Bauprompt war 2026-11-04 schon zu groß, PlanPrompt.wantsMachineDetails); gebaut werden
 * sie über TemplateCatalog. find() kennt sie trotzdem, damit der Plan-Compiler die Schritte auflöst.
 *
 * Threads: synchronisiert; geladen wird im Hauptthread (Schnellaufträge und Rückfall laufen dort). Fehler: Fehlt eine
 * Vorlage (andere Minecraft-Fassung), gibt es sie nicht – gemeldet einmal je Name, nie als Ausnahme (fail-open: dann
 * baut eben das LLM).
 */
final class VanillaTemplates implements ModuleLibrary, TemplateCatalog.Source {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    private final Map<String, Optional<TemplateModules.Template>> cache = new HashMap<>();

    @Override
    public synchronized Optional<TemplateModules.Template> template(String name) {
        return cache.computeIfAbsent(name, this::load);
    }

    private Optional<TemplateModules.Template> load(String name) {
        Optional<String> key = TemplateCatalog.key(name);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        try {
            Structure s = Bukkit.getStructureManager().loadStructure(NamespacedKey.minecraft(key.get()), false);
            if (s == null || s.getPalettes().isEmpty()) {
                LOG.warning(() -> "vorlagen | fehlt | name=" + name + " vorlage=" + key.get());
                return Optional.empty();
            }
            List<TemplateModules.RawCell> raw = new ArrayList<>();
            for (BlockState b : s.getPalettes().getFirst().getBlocks()) {
                raw.add(new TemplateModules.RawCell(b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString()));
            }
            TemplateModules.Template t = TemplateModules.build(name, "Minecraft-Vorlage " + key.get(), raw);
            LOG.fine(() -> "vorlagen | geladen | name=" + name + " blöcke=" + t.module().cells().size() + " vorne="
                    + t.front() + " boden=" + t.ground());
            return Optional.of(t);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "vorlagen | laden_fehlgeschlagen | name=" + name + " vorlage=" + key.get(), e);
            return Optional.empty();
        }
    }

    /**
     * Lädt nach dem Start alle Vorlagen vor – eine je Tick, damit kein Tick spürbar länger wird – und meldet die
     * Bilanz. Zweck: Der erste „bau mir ein Dorf“ wartet nicht aufs Einlesen, und eine neue Minecraft-Fassung, in der
     * Vorlagen umbenannt wurden, steht sofort im Log statt erst beim Bauen.
     */
    void preload(org.bukkit.plugin.Plugin plugin) {
        List<String> names = TemplateCatalog.names();
        List<String> missing = new ArrayList<>();
        int[] next = {0};
        long[] cells = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (next[0] >= names.size()) {
                task.cancel();
                int ok = names.size() - missing.size();
                long c = cells[0];
                LOG.info(() -> "vorlagen | bereit | geladen=" + ok + " fehlend=" + missing.size() + " blöcke=" + c
                        + (missing.isEmpty() ? "" : " fehlt=" + String.join(",", missing)));
                return;
            }
            String n = names.get(next[0]++);
            Optional<TemplateModules.Template> t = template(n);
            if (t.isEmpty()) {
                missing.add(n);
            } else {
                cells[0] += t.get().module().cells().size();
            }
        }, 40L, 1L);
    }

    @Override
    public Optional<Module> find(String name) {
        return template(name).map(TemplateModules.Template::module);
    }

    @Override
    public List<Module> all() {
        return List.of();
    }
}
