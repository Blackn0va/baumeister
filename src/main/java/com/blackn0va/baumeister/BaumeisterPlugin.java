package com.blackn0va.baumeister;

import com.blackn0va.baumeister.earth.EarthService;
import com.blackn0va.baumeister.earth.EarthWorld;
import com.blackn0va.baumeister.plan.CombinedLibrary;
import com.blackn0va.baumeister.plan.Machines;
import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Einstieg. Verdrahtet NPC, LLM-Client und Bau-Dienst; alles Weitere steht in den Klassen selbst.
 */
public final class BaumeisterPlugin extends JavaPlugin {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    private BuilderNpc npc;
    private BuildService service;
    private CityService city;
    private WorldBuildService weltbau;
    private TerrainService terrain;
    private EarthService earth;
    private PlaceRegistry places;
    private Settings settings;
    /** Stadtansicht im Baumeister-Host; null = abgeschaltet (gui.stadt-anzeige) oder keine Adresse. */
    private HostReporter hostReporter;
    private ResultReporter resultReporter;
    private TickMonitor ticks;
    /** Begleiter (/bm begleiter, „Hey Vera“), null bis load() durch ist. */
    private CompanionService companion;
    /** Stadtleben (Vertrag v008): Bewohner-Bots, nur mit stadtleben.an und erreichbarer KI. */
    private TownLife town;
    private RucksackService rucksack;
    private WarpService warps;
    private PortalService portals;
    private QuestService quests;
    /** Modpaket vom Server (mods.download.*), null = aus. */
    private ModDownloads modDownloads;
    private HelpService help;
    /** Kistenfenster der Bauwerk-Dateien (/bm katalog). */
    private SchematicMenu schematicMenu;
    /** Minikarte (/bm karte), null bis onEnable durch ist. */
    private Minimap minimap;
    private ScreenService screens;
    /** Modulverzeichnis (/bm module, Kistenmenü, modul_json an Host und Mod), null bis onEnable durch ist. */
    private ModuleService modules;
    private ConfigReload configReload;
    /** Fortschrittsbalken (Komfort), null bis onEnable durch ist. */
    private ProgressBarTask bars;
    /** Schutz vor der PlotSquared-Rekursion im ChunkLoadEvent (siehe ChunkLoadGuard), null ohne onEnable. */
    private ChunkLoadGuard chunkGuard;
    /** Keine fallenden Gegenstände im Baubereich während eines Baus (Plan B3), null ohne onEnable. */
    private BuildDropGuard dropGuard;
    private LagWatch lagWatch;
    private GameStructures gameStructures;
    // Als Object gehalten, damit ohne WorldEdit keine WorldEdit-Klasse geladen werden muss.
    private Object schematics;
    /** Bauvorlagen von Minecraft (Zwischenschicht, TemplateCatalog); einmal je Serverlauf, sie ändern sich nicht. */
    private final VanillaTemplates templates = new VanillaTemplates();
    /** Stimme auf dem Server (Piper, sprache.*); null = aus. */
    private LocalVoice localVoice;
    /** Veras eigene Stimme vom Server (sprache.vera-*); null = aus. */
    private LocalVoice veraVoice;
    /** Bauwerk-Dateien aus plugins/Baumeister/schematics/ (FileSchematics), ohne WorldEdit. */
    private FileSchematics fileSchematics;
    private boolean templatesPreloaded;

    @Override
    public void onEnable() {
        // Vorher: LOG.setParent(getLogger()) – gemessen 2026-10-28 kam die Zeile dann ohne "[Baumeister]",
        // weil der Eltern-Weg nur die Handler aufruft, nicht PluginLogger.log(), das das Präfix setzt.
        // Jetzt reicht ein Handler jeden Eintrag an PluginLogger.log() weiter.
        // Nur unter Spigot: Paper registriert den Plugin-Logger unter dem Plugin-Namen, getLogger() IST dann LOG.
        // Vorher hing die Weiterleitung dort an sich selbst – StackOverflowError in onEnable, Plugin aus
        // (Paper 1.21.11-132, Testserver 2026-10-30). Paper setzt das Präfix selbst.
        if (getLogger() != LOG) {
            LOG.setUseParentHandlers(false);
            LOG.addHandler(forwarder);
        }
        saveDefaultConfig();
        // Fehlende Schlüssel aus der Vorlage samt Kommentar ergänzen, vor dem ersten getConfig() (ConfigMigration).
        // Kaputtes YAML: fail-closed aus, bevor irgendein saveConfig die Datei des Admins überschreiben kann.
        if (!ConfigMigration.onStart(this)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Vorher: Rückgängig überlebte keinen Neustart, der ganze Ordner wurde hier gelöscht. Jetzt bleiben Einträge
        // mit Begleitzettel für den Stapel (UndoJournal, geprüft nach dem Start), alles andere (Weltbau-Kacheln,
        // abgebrochenes Schreiben) ist verwaist und geht wie bisher weg. Vor load(): Noch legt kein Bau einen Spool an.
        List<java.nio.file.Path> undoKept = UndoJournal.sweep(getDataFolder().toPath().resolve(UndoJournal.DIR),
                ComfortSettings.from(getConfig()).undoAcrossRestart());
        // Wiederherstellen beim Start (Betreiber-Ordner, siehe UndoJournal.RESTORE_DIR): immer behalten, angewandt nach
        // den Welten (restoreAtStart unten).
        List<java.nio.file.Path> restoreKept = UndoJournal.sweep(
                getDataFolder().toPath().resolve(UndoJournal.RESTORE_DIR), true, UndoJournal.RESTORE_DIR);
        try {
            load();
        } catch (IllegalArgumentException | IOException e) {
            // fail-closed: Mit kaputter Konfiguration lieber gar nicht bauen als mit geratenen Werten.
            LOG.log(Level.SEVERE, "start | konfiguration_ungültig | " + e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        weltbau = new WorldBuildService(this, npc);
        // Unterbrochenen Stadtbau fortsetzen, wenn die Welten geladen sind (Multiverse lädt nach dem Plugin).
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (city != null) {
                city.resumeAfterRestart();
            }
        }, 200L);
        configReload = new ConfigReload(this);
        ticks = TickMonitor.start(this);
        // Vor Befehl und Diensten: /bm orte, Stadtbau und Gelände tragen sofort ein.
        places = new PlaceRegistry(getDataFolder().toPath().resolve("orte.json"));
        places.load();
        BaumeisterCommand command = new BaumeisterCommand(this);
        PluginCommand pc = getCommand("baumeister");
        if (pc == null) {
            throw new IllegalStateException("Befehl baumeister fehlt in plugin.yml");
        }
        pc.setExecutor(command);
        pc.setTabCompleter(command);
        getServer().getPluginManager().registerEvents(new BaumeisterListener(this), this);
        // Stille Bereichsprüfung (RegionCheck.mayBreakQuietly): Prüf-Ereignisse nicht in CoreProtect.
        getServer().getPluginManager().registerEvents(new RegionCheck.QuietProbes(), this);
        dropGuard = new BuildDropGuard();
        getServer().getPluginManager().registerEvents(dropGuard, this);
        lagWatch = new LagWatch(this);
        gameStructures = new GameStructures(this);
        getServer().getPluginManager().registerEvents(lagWatch, this);
        chunkGuard = new ChunkLoadGuard(r -> Bukkit.getScheduler().runTask(this, r));
        getServer().getPluginManager().registerEvents(chunkGuard, this);
        // Erst im ersten Tick: Dann haben alle Plugins (auch ein nach uns geladenes PlotSquared) ihre Handler.
        Bukkit.getScheduler().runTask(this, () -> chunkGuard.installAndLog("start"));
        loadCityWorld(false);
        terrain = new TerrainService(this);
        terrain.loadSaved();
        earth = new EarthService(this, Texts::info);
        earth.onArrival((name, where) -> places.remember(name, where, PlaceRegistry.Kind.EARTH));
        earth.start();
        weltbau.start();
        // Nach den Welten (Stadtwelt, Erde): Die Einträge nennen ihre Welt nur beim Namen.
        service.comfort().resumeUndo(undoKept);
        service.comfort().restoreAtStart(restoreKept);
        bars = ProgressBarTask.start(this);
        minimap = new Minimap(this, MinimapSettings.from(getConfig()));
        minimap.start();
        screens = new ScreenService(this);
        getServer().getPluginManager().registerEvents(screens, this);
        screens.start();
        companion.start();
        getServer().getPluginManager().registerEvents(town, this);
        town.start();
        // Rucksack (ersetzt MinepacksStable, 2026-11-05): eigener Befehl /rucksack, Aliase wie bei Minepacks.
        rucksack = new RucksackService(this, RucksackService.Settings.from(getConfig()));
        PluginCommand rc = getCommand("rucksack");
        if (rc == null) {
            throw new IllegalStateException("Befehl rucksack fehlt in plugin.yml");
        }
        rc.setExecutor(rucksack);
        rc.setTabCompleter(rucksack);
        getServer().getPluginManager().registerEvents(rucksack, this);
        rucksack.start();
        // Warps (ersetzt GWarp, 2026-11-05): übernimmt plugins/GWarp/Warps.data beim ersten Start.
        warps = new WarpService(this, WarpService.Settings.from(getConfig()));
        warps.start();
        // Portale (2026-11-15): nach den Warps, weil ein Portal zu einem Warp führen kann.
        portals = new PortalService(this);
        getServer().getPluginManager().registerEvents(portals, this);
        portals.start();
        quests = new QuestService(this, QuestService.Settings.from(getConfig()));
        getServer().getPluginManager().registerEvents(quests, this);
        quests.start();
        // Hilfebuch für neue Spieler (/hilfe, beim ersten Beitreten einmal von selbst).
        help = new HelpService(this, HelpService.Settings.from(getConfig()));
        getServer().getPluginManager().registerEvents(help, this);
        help.start();
        schematicMenu = new SchematicMenu(this);
        getServer().getPluginManager().registerEvents(schematicMenu, this);
        // /mods: Fassung, Modliste und Download-Link (ModsInfo) – für alle, ohne Recht.
        PluginCommand mc = getCommand("mods");
        if (mc == null) {
            throw new IllegalStateException("Befehl mods fehlt in plugin.yml");
        }
        // Download vom Server selbst (2026-11-08): nur mit mods.download.an, Einstellungen ab dem nächsten Neustart.
        modDownloads = new ModDownloads(ModDownloads.Settings.from(getConfig()),
                getDataFolder().toPath().resolve("modpaket"));
        if (!modDownloads.start()) {
            modDownloads = null;
        }
        mc.setExecutor((sender, cmd, label, args) -> {
            ModsInfo.show(sender, getDataFolder().toPath(), getConfig().getString("mods.link", ""), modDownloads);
            return true;
        });
        // /vera <satz>: wie „Vera, <satz>“ im Chat – damit das Hilfebuch Sätze per Klick auslösen kann (ein Buchklick
        // kann nur Befehle ausführen, keine Chatzeile senden).
        PluginCommand vc = getCommand("vera");
        if (vc == null) {
            throw new IllegalStateException("Befehl vera fehlt in plugin.yml");
        }
        vc.setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player p)) {
                sender.sendMessage("Der Begleiter hört nur Spieler.");
                return true;
            }
            if (args.length == 0) {
                companion.summon(p);
            } else if (!companion.onChat(p, companion.name() + ", " + String.join(" ", args))) {
                Texts.info(p, "Der Begleiter ist gerade nicht verfügbar (Recht baumeister.begleiter oder begleiter.an).");
            }
            return true;
        });
        // Simple Voice Chat (softdepend): Begleiter im Spiel hören und sprechen lassen. Über VoicechatBoot, damit ohne
        // das Plugin keine Voice-Chat-Klasse geladen wird.
        // CoreProtect (softdepend): Kisten-Auskunft und Reparieren. Über CoreProtectBoot, damit ohne das Plugin keine
        // CoreProtect-Klasse geladen wird.
        if (getServer().getPluginManager().getPlugin("CoreProtect") != null) {
            companion.blockHistory(CoreProtectBoot.start(this));
        }
        if (getServer().getPluginManager().getPlugin("voicechat") != null) {
            companion.inGameVoice(VoicechatBoot.start(this));
        }
        // Zuletzt: Das Modulverzeichnis liest den Zustand aller Dienste oben (Vertrag v012, AK-37).
        ModuleMenu moduleMenu = new ModuleMenu(this, () -> modules == null ? List.of() : modules.entries());
        getServer().getPluginManager().registerEvents(moduleMenu, this);
        modules = ModuleService.from(this, moduleMenu);
        modules.start(this);
        LOG.info(() -> "start | bereit | llm=" + settingsUrl());
    }

    @Override
    public void onDisable() {
        Texts.spoken = null;
        if (modules != null) {
            modules.stop();
        }
        if (localVoice != null) {
            localVoice.stop();
        }
        if (veraVoice != null) {
            veraVoice.stop();
        }
        if (modDownloads != null) {
            modDownloads.stop();
        }
        if (bars != null) {
            bars.stop();
        }
        if (screens != null) {
            screens.stop();
        }
        if (minimap != null) {
            minimap.stop();
        }
        if (companion != null) {
            companion.shutdown();
        }
        if (town != null) {
            town.shutdown();
        }
        if (rucksack != null) {
            rucksack.shutdown();
        }
        if (quests != null) {
            quests.shutdown();
        }
        if (chunkGuard != null) {
            chunkGuard.uninstall();
        }
        if (weltbau != null) {
            weltbau.shutdown();
        }
        if (city != null) {
            city.shutdown();
        }
        if (service != null) {
            service.shutdown();
        }
        if (earth != null) {
            earth.shutdown();
        }
        // Sonst hängt nach /reload ein zweiter Handler am statischen Logger und jede Zeile käme doppelt.
        LOG.removeHandler(forwarder);
    }

    private final Handler forwarder = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            getLogger().log(logRecord);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private void load() throws IOException {
        Settings s = Settings.from(getConfig());
        String systemPrompt = resource("system-prompt.txt");
        JsonObject schema = JsonParser.parseString(resource("plan-schema.json")).getAsJsonObject();
        if (npc == null) {
            npc = new BuilderNpc(this, s.npcName());
        } else {
            npc.rename(s.npcName());
        }
        settings = s;
        hostReporter = HostReporter.from(getConfig(), s.llmUrl());
        resultReporter = ResultReporter.from(getConfig(), s.llmUrl());
        BuildService old = service;
        String model = getConfig().getString("llm.modell", "lokal");
        service = new BuildService(this, npc, s, new LlmClient(s, schema).withModel(model), systemPrompt,
                loadModules());
        service.useTemplates(templates);
        service.useSchematics(fileSchematics);
        if (!templatesPreloaded) {
            templatesPreloaded = true;
            templates.preload(this);
        }
        if (old != null) {
            // /bm neuladen: Rückgängig-Stapel, Warteschlange und Vorschauen ziehen in den neuen Dienst um.
            service.takeOver(old);
        }
        if (city == null) {
            city = new CityService(this, npc);
        } else {
            city.reload();
        }
        // Begleiter: eigenes Schema „begleiter“ (Host-Grammatik plan/begleiter.gbnf) und kurze Antworten.
        CompanionSettings cs = CompanionSettings.from(getConfig());
        JsonObject companionSchema = JsonParser.parseString(resource("begleiter-schema.json")).getAsJsonObject();
        if (companion == null) {
            companion = new CompanionService(this);
        }
        companion.configure(cs, new LlmClient(s, companionSchema, "begleiter", cs.maxTokens(), LlmClient.RETRY_DELAYS_MS)
                .withModel(getConfig().getString("llm.modell", "lokal")),
                resource("begleiter-prompt.txt"), CompanionVoiceLink.from(getConfig(), s.llmUrl()));
        reopenVoice();
        // Stadtleben: eigener Client mit eigenem OutageLog wie beim Begleiter – ein Ausfall, den die Bewohner bemerken,
        // schaltet nicht die volle Wiederholungskette der Bauaufträge ab. Begleiter-Schema, weil der Host nur „bauplan“
        // und „begleiter“ annimmt; Aktionen verwirft TownTalk. Ohne Wiederholung: Ein Bewohner-Satz lohnt kein Warten.
        if (town == null) {
            town = new TownLife(this);
        }
        town.configure(com.blackn0va.baumeister.town.TownSettings.from(getConfig()),
                TownLife.client(s, companionSchema, getConfig().getString("llm.modell", "lokal")));
        Texts.spoken = this::builderSpeaks;
    }

    /**
     * Sprache auf dem Server (2026-11-08): Vera und der Baumeister sprechen auch ohne App, jeder mit eigener Stimme –
     * Vera als Zeichentrick-Kumpel (sprache.vera-stimme, -tonhöhe), der Baumeister natürlich (sprache.stimme). Vorher
     * teilten sich beide ein Modell; das englische ryan klang auf Deutsch für Vera furchtbar. Bei /bm neuladen und
     * nach /bm sprache neu gelesen.
     */
    void reopenVoice() {
        if (localVoice != null) {
            localVoice.stop();
        }
        if (veraVoice != null) {
            veraVoice.stop();
        }
        localVoice = LocalVoice.open(LocalVoice.Settings.from(getConfig()), getDataFolder().toPath()).orElse(null);
        veraVoice = LocalVoice.open(LocalVoice.Settings.forVera(getConfig()), getDataFolder().toPath()).orElse(null);
        if (companion != null) {
            companion.useLocalVoice(veraVoice);
        }
        // Vorrat fester Sätze (sprache-vorrat.txt) eine Minute später: nicht mitten im Serverstart rechnen.
        if (getConfig().getBoolean("sprache.vorrat", true) && (localVoice != null || veraVoice != null)) {
            LocalVoice b = localVoice;
            LocalVoice v = veraVoice;
            getServer().getScheduler().runTaskLater(this, () -> {
                List<String> vera = new java.util.ArrayList<>();
                List<String> builder = new java.util.ArrayList<>();
                try (java.io.InputStream in = getResource("sprache-vorrat.txt")) {
                    if (in == null) {
                        return;
                    }
                    for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .split("\n")) {
                        if (line.startsWith("V\t")) {
                            vera.add(line.substring(2));
                        } else if (line.startsWith("B\t")) {
                            builder.add(line.substring(2));
                        }
                    }
                } catch (java.io.IOException e) {
                    LOG.log(java.util.logging.Level.WARNING, "sprache | vorrat_unlesbar", e);
                    return;
                }
                // Nur, wenn die Stimmen inzwischen nicht neu geöffnet wurden (dann läuft deren eigener Vorrat).
                if (v != null && v == veraVoice) {
                    v.prewarm(vera);
                }
                if (b != null && b == localVoice) {
                    b.prewarm(builder);
                }
            }, 1200L);
        }
    }

    /** Stimme des Baumeisters; null = Sprache vom Server aus oder nicht startklar. */
    LocalVoice localVoice() {
        return localVoice;
    }

    /** Veras Stimme; null = aus oder nicht startklar. */
    LocalVoice veraVoice() {
        return veraVoice;
    }

    /** Modulverzeichnis, null bis onEnable durch ist. */
    ModuleService modules() {
        return modules;
    }

    /** Modpaket-Download vom Server, null = aus oder nicht gestartet. */
    ModDownloads modDownloads() {
        return modDownloads;
    }

    /** Kistenfenster der Sammlung. */
    SchematicMenu schematicMenu() {
        return schematicMenu;
    }

    /**
     * Probesatz mit der Server-Stimme (/bm sprache probe, nach jeder Stimmwahl): aus Veras Mund, wenn sie beim
     * Spieler ist, sonst vom Baumeister in 64 Blöcken. Jede Lücke sagt der Chat – vorher hieß „nichts zu hören“
     * entweder Piper kaputt, Voice Chat getrennt oder niemand in der Nähe, und man konnte es nicht unterscheiden.
     */
    void speakProbe(org.bukkit.entity.Player p, String text, boolean preferVera) {
        CompanionInGameVoice iv = companion == null ? null : companion.inGameVoice();
        if (localVoice == null && veraVoice == null) {
            Texts.info(p, "Die Sprache vom Server ist aus oder nicht startklar – /bm sprache zeigt warum.");
            return;
        }
        if (iv == null) {
            Texts.info(p, "Simple Voice Chat läuft auf dem Server nicht – ohne ihn spricht niemand im Spiel.");
            return;
        }
        if (!iv.connected(p.getUniqueId())) {
            Texts.info(p, "Dein Voice Chat ist nicht verbunden (Taste V) – dann hörst du die Probe nicht.");
            return;
        }
        org.bukkit.entity.Entity vera = companion.entityOf(p);
        org.bukkit.entity.Mannequin m = npc == null ? null : npc.get();
        org.bukkit.entity.Entity builder = m != null && m.getWorld().equals(p.getWorld())
                && m.getLocation().distanceSquared(p.getLocation()) <= 64 * 64 ? m : null;
        // Wer spricht, mit welcher Stimme: Vera mit ihrer, der Baumeister mit seiner – sonst prüfte man die falsche.
        boolean useVera = vera != null && veraVoice != null && (preferVera || builder == null || localVoice == null);
        org.bukkit.entity.Entity speaker = useVera ? vera : localVoice != null ? builder : null;
        LocalVoice lv = useVera ? veraVoice : localVoice;
        if (speaker == null) {
            Texts.info(p, preferVera ? "Ruf erst " + companion.name() + " (/vera) – sie spricht die Probe."
                    : "Geh zum Baumeister oder ruf " + companion.name() + " (/vera) – einer spricht die Probe.");
            return;
        }
        Texts.info(p, "Probe: „" + Texts.clean(text, 120) + "“");
        lv.voice(text).thenAccept(pcm -> getServer().getScheduler().runTask(this, () -> {
            if (pcm.isEmpty()) {
                Texts.info(p, "Piper hat nichts geliefert – der Grund steht im Server-Log (sprache |).");
            } else if (speaker.isValid() && !iv.play(speaker, pcm.get())) {
                Texts.info(p, "Abspielen ging gerade nicht – gleich noch einmal.");
            }
        }));
    }

    /**
     * Der Baumeister spricht seine Chatzeilen (Texts.say) – nur, wenn im Umkreis von 48 Blöcken jemand Simple Voice
     * Chat verbunden hat; sonst rechnete der Server Sätze, die niemand hört.
     */
    private void builderSpeaks(String name, org.bukkit.Location where, String text) {
        LocalVoice lv = localVoice;
        CompanionInGameVoice iv = companion == null ? null : companion.inGameVoice();
        if (lv == null || !lv.settings().builder() || iv == null || npc == null || !name.equals(npc.name())
                || where == null || where.getWorld() == null) {
            return;
        }
        boolean anyone = where.getWorld().getPlayers().stream().anyMatch(p -> p.getLocation()
                .distanceSquared(where) <= 48 * 48 && iv.connected(p.getUniqueId()));
        if (!anyone) {
            return;
        }
        lv.voice(text).thenAccept(pcm -> getServer().getScheduler().runTask(this, () -> {
            org.bukkit.entity.Mannequin m = npc.get();
            if (pcm.isPresent() && m != null) {
                iv.play(m, pcm.get());
            }
        }));
    }

    /**
     * Bauteil-Bibliothek nur mit WorldEdit. Der Typ SchematicLibrary taucht hier bewusst nur im
     * Zweig auf, in dem WorldEdit geladen ist – sonst liefe der Server in NoClassDefFoundError.
     */
    private ModuleLibrary loadModules() {
        // Eingebaute Maschinen (plan/Machines) gibt es immer – auch ohne WorldEdit.
        Machines builtin = new Machines();
        if (fileSchematics == null) {
            // Alte .schematic (vor 1.13) über die Umrechnung des Servers lesbar machen (ServerLegacyBlocks).
            com.blackn0va.baumeister.structure.StructureFile.useLegacy(new ServerLegacyBlocks());
            fileSchematics = new FileSchematics(getDataFolder().toPath().resolve("schematics"));
            fileSchematics.catalog();
        }
        if (getServer().getPluginManager().getPlugin("WorldEdit") == null) {
            LOG.info(() -> "bauteile | nur_eingebaute | grund=WorldEdit fehlt maschinen=" + builtin.all().size());
            schematics = null;
            return new com.blackn0va.baumeister.plan.ModuleTransforms(
                    new CombinedLibrary(List.of(builtin, fileSchematics, templates)));
        }
        SchematicLibrary lib = new SchematicLibrary(getDataFolder());
        lib.reload();
        schematics = lib;
        FileSchematics files = fileSchematics;
        lib.reserveNames(n -> builtin.find(n).isPresent() || files.catalog().find(n).isPresent()
                || templates.find(n).isPresent());
        // Bauwerk-Dateien VOR der Bibliothek, in die Spieler schreiben (Vera „merk dir das Bauwerk“): Vorher stand sie
        // davor, ein gemerktes Bauteil gleichen Namens ersetzte die Datei – auch im Auftrag eines Admins (Review
        // 2026-11-15). Vorlagen zuletzt; ModuleTransforms obendrauf: Name_gespiegelt / Name_doppelt.
        return new com.blackn0va.baumeister.plan.ModuleTransforms(
                new CombinedLibrary(List.of(builtin, fileSchematics, lib, templates)));
    }

    /** Bauwerk-Dateien (immer da, ggf. leer). */
    FileSchematics fileSchematics() {
        return fileSchematics;
    }

    /** null, wenn WorldEdit fehlt. */
    Object schematics() {
        return schematics;
    }

    /** null = Stadtansicht im Host aus. Ein laufender Stadtbau behält den Melder, mit dem er gestartet ist. */
    HostReporter hostReporter() {
        return hostReporter;
    }

    /** null = Ergebnis-Meldung an den Host aus (gui.ergebnis-melden). */
    ResultReporter resultReporter() {
        return resultReporter;
    }

    /** Für /bm neuladen (ConfigReload): BuildService mit den Einstellungen aus getConfig() neu anlegen. */
    void rebuildBuildService() throws IOException {
        load();
    }

    TickMonitor ticks() {
        return ticks;
    }

    ConfigReload configReload() {
        return configReload;
    }

    private String resource(String name) throws IOException {
        try (InputStream in = getResource(name)) {
            if (in == null) {
                throw new IOException("Ressource fehlt im JAR: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String settingsUrl() {
        return settings == null ? "?" : settings.llmUrl().toString();
    }

    static final String CITY_WORLD = "stadtwelt";

    /**
     * Flache Stadtwelt laden (create=false: nur wenn es sie schon gibt). Spigot lädt selbst angelegte Welten nach
     * einem Neustart nicht von allein, deshalb ruft onEnable das ohne create auf. Monster aus: Eine Großstadt im
     * Bau soll nicht nachts von Zombies bevölkert werden.
     */
    static World loadCityWorld(boolean create) {
        if (Bukkit.getWorld(CITY_WORLD) != null) {
            return Bukkit.getWorld(CITY_WORLD);
        }
        if (!create && !new java.io.File(Bukkit.getWorldContainer(), CITY_WORLD).isDirectory()) {
            return null;
        }
        World w = new WorldCreator(CITY_WORLD).type(WorldType.FLAT).generateStructures(false).createWorld();
        if (w != null) {
            w.setSpawnFlags(false, true);
            LOG.info(() -> "stadtwelt | geladen | welt=" + CITY_WORLD);
        }
        return w;
    }

    BuilderNpc npc() {
        return npc;
    }

    ScreenService screens() {
        return screens;
    }

    Minimap minimap() {
        return minimap;
    }

    /** null, solange onEnable nicht durch ist. */
    HelpService help() {
        return help;
    }

    /** null, solange onEnable nicht durch ist. */
    QuestService quests() {
        return quests;
    }

    /** null, solange onEnable nicht durch ist. */
    WarpService warps() {
        return warps;
    }

    /** null, solange onEnable nicht durch ist. */
    PortalService portals() {
        return portals;
    }

    /** null, solange onEnable nicht durch ist. */
    RucksackService rucksack() {
        return rucksack;
    }

    CompanionService companion() {
        return companion;
    }

    /** null bis load() durch ist. */
    TownLife town() {
        return town;
    }

    /** Keine fallenden Gegenstände im Baubereich (BuildJob); null, bevor onEnable lief. */
    BuildDropGuard dropGuard() {
        return dropGuard;
    }

    /** Bauwerke des Spiels als Vorlagen (/bm spielbauwerk); null vor onEnable. */
    GameStructures gameStructures() {
        return gameStructures;
    }

    /** Lag-Wächter (Leerlauf-Messung gebauter Bereiche); null vor onEnable. */
    LagWatch lagWatch() {
        return lagWatch;
    }

    BuildService service() {
        return service;
    }

    TerrainService terrain() {
        return terrain;
    }

    /** Die Erdwelt (ganze Erde 1:1); world() ist null, solange das Datapack oder der Neustart fehlt. */
    EarthService earth() {
        return earth;
    }

    CityService city() {
        return city;
    }

    WorldBuildService weltbau() {
        return weltbau;
    }

    /**
     * Die Erdwelt für den Weltbau, oder null, solange es sie nicht gibt (Datapack fehlt, kein Neustart, oder die
     * Projektionswahl ist fail-closed abgeschaltet). Mit null meldet /bm weltbau, dass die Erdwelt fehlt, und die
     * Automatik wartet.
     */
    EarthWorld earthWorld() {
        return earth != null && earth.world() != null ? earth : null;
    }

    /** Reisepunkte (orte.json). Öffentlich, damit das Erdwelt-Paket über remember() eintragen kann. */
    public PlaceRegistry places() {
        return places;
    }

    Settings settings() {
        return settings;
    }
}
