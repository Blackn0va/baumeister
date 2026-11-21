package com.blackn0va.baumeister;

import com.blackn0va.baumeister.earth.EarthProjection;
import com.blackn0va.baumeister.earth.ProjectionChoice;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.OsmClient;
import com.blackn0va.baumeister.osm.StreetFinder;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

/**
 * Städte und Dörfer 1:1 aus OpenStreetMap (Wunsch 2026-10-28).
 *
 * Ablauf: /bm stadt &lt;Ort|lat,lon&gt; [radius] → Ort suchen, Kartendaten holen, planen (abseits des Hauptthreads) →
 * Zusammenfassung mit Größe und Dauer → /bm stadt los baut mit dem Baumeister und Helfern (CityJob). Steuerung im Lauf:
 * /bm pause · weiter · tempo &lt;n&gt; · helfer &lt;n&gt; · fortschritt · stop · komm – oder im Chat („Baumeister, schneller“).
 *
 * Nur für baumeister.admin: Ein Ort ersetzt auf hunderten Metern alles, was dort steht; die Rechteprüfung je Block
 * (BlockPlaceEvent) wäre bei Hunderttausenden Blöcken nicht tragbar.
 */
final class CityService {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final Pattern CHAT_CITY = Pattern.compile(
            "^(?:bau(?:e)?\\s+(?:mir\\s+|uns\\s+)?)?(?:(?:die\\s+stadt|das\\s+dorf|den\\s+ort|stadt|dorf|ort)\\s+)?"
                    + "(.+?)(?:\\s+(?:mit\\s+)?(\\d{2,4})\\s*m(?:etern?)?)?(?:\\s+1:1)?\\s+nach(?:bauen)?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private record Pending(CommandSender sender, CityStages stages, CityPlanner.Plan first) {
    }

    /** Öffentliche Overpass-Instanzen aus dem OSM-Wiki (Overpass_API#Public_Overpass_API_instances). */
    static final java.util.List<String> OVERPASS_DEFAULT = java.util.List.of(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter");

    private final BaumeisterPlugin plugin;
    private final BuilderNpc npc;
    private OsmClient osm;
    private Pending pending;
    private CityJob job;
    private boolean loading;
    /** Stand des laufenden Stadtbaus auf der Platte (Pause, Neustart) – siehe CityResume. */
    private final CityResume resume;
    /** Gebaute Städte (Mitte, Welt, Basis) für /bm ort &lt;Stadt&gt; &lt;Straße&gt;. */
    private final CityIndex cities;
    private CityResume.State state;
    /** /bm stop: Stand danach löschen. Herunterfahren und Fehler lassen ihn stehen. */
    private boolean stoppedByUser;

    CityService(BaumeisterPlugin plugin, BuilderNpc npc) {
        this.plugin = plugin;
        this.npc = npc;
        this.resume = new CityResume(plugin.getDataFolder().toPath().resolve("stadt").resolve("fortsetzen.json"));
        this.cities = new CityIndex(plugin.getDataFolder().toPath().resolve("stadt").resolve("staedte.json"));
        reload();
    }

    /**
     * /bm ort &lt;Stadt&gt; &lt;Straße&gt; [Nr] (Nutzer 2026-10-30). false, wenn der Anfang keine gebaute Stadt nennt –
     * dann ist es ein gewöhnlicher Reisepunkt. Gesucht wird in den Kartenkacheln der Stadt aus dem Zwischenspeicher
     * (StreetFinder, kein Netz) im Hintergrund, gereist auf dem Hauptthread wie bei /bm ort.
     */
    boolean travelToStreet(CommandSender sender, java.util.List<String> words) {
        java.util.Optional<CityIndex.Match> m = cities.prefix(words);
        if (m.isEmpty()) {
            return false;
        }
        CityResume.State c = m.get().city();
        java.util.List<String> rest = words.subList(m.get().rest(), words.size());
        String number = null;
        if (rest.size() > 1 && rest.getLast().matches("\\d{1,4}[a-zA-Z]?")) {
            number = rest.getLast();
            rest = rest.subList(0, rest.size() - 1);
        }
        String street = String.join(" ", rest);
        String nr = number;
        org.bukkit.World w = Bukkit.getWorld(c.world());
        if (w == null) {
            Texts.info(sender, "Die Welt „" + c.world() + "“ von " + c.place() + " ist nicht geladen.");
            return true;
        }
        Path cache = plugin.getDataFolder().toPath().resolve("karten");
        Texts.info(sender, "Suche " + street + (nr == null ? "" : " " + nr) + " in " + c.place() + " …");
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            CityStages st = new CityStages(osm, cache, c.place(), c.lat(), c.lon(), c.radius(), c.maxStageBlocks());
            try {
                // Kachel für Kachel: Vorher lagen alle entpackten Kacheln der Stadt zugleich in einer Liste (25 km,
                // über tausend Kacheln) – gegen die Speicherregel aus CityStages. Jetzt hält die Suche nur die
                // gerade gelesene Kachel und die passenden Wege (StreetFinder.Search).
                StreetFinder.Search search = new StreetFinder.Search(c.lat(), c.lon(), street, nr);
                int tiles = 0;
                for (Path p : st.cachedTiles()) {
                    try {
                        search.add(CityStages.readCache(p));
                        tiles++;
                    } catch (java.io.IOException e) {
                        LOG.log(Level.WARNING, "orte | strasse_kachel_unlesbar | datei=" + p.getFileName(), e);
                    }
                }
                return new Object[] {search.result(), tiles};
            } finally {
                st.close();
            }
        }).whenComplete((res, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                LOG.log(Level.WARNING, "orte | strasse_fehlgeschlagen | ort=" + c.place(), cause);
                Texts.info(sender, "Die Suche ging schief: " + cause.getMessage());
                return;
            }
            @SuppressWarnings("unchecked")
            java.util.Optional<StreetFinder.Hit> hit = (java.util.Optional<StreetFinder.Hit>) res[0];
            int tiles = (int) res[1];
            if (hit.isEmpty()) {
                Texts.info(sender, "Eine Straße „" + Texts.clean(street, 64) + "“ finde ich in " + c.place()
                        + " nicht" + (tiles == 0 ? " – die Kartendaten sind nicht mehr im Zwischenspeicher (30 Tage)."
                        : " (" + tiles + " Kartenkacheln durchsucht)."));
                return;
            }
            StreetFinder.Hit h = hit.get();
            String label = c.place() + ", " + h.street() + (h.number() != null && !h.numberMissing() ? " "
                    + h.number() : "");
            Location at = new Location(w, c.x() + h.x() + 0.5, c.y() + 1, c.z() + h.z() + 0.5, h.yaw(), 0f);
            if (h.numberMissing()) {
                Texts.info(sender, "Hausnummer " + Texts.clean(h.number(), 8) + " steht an keinem Haus der "
                        + h.street() + " – ich bringe dich zur Straßenmitte.");
            }
            LOG.info(() -> "orte | strasse | ort=" + c.place() + " gesucht=" + Texts.clean(street, 64) + " gefunden="
                    + h.street() + " nr=" + h.number() + " fehlt=" + h.numberMissing() + " x=" + at.getBlockX()
                    + " z=" + at.getBlockZ());
            BaumeisterCommand.arrive(plugin, sender, at, label);
        }));
        return true;
    }

    CityIndex cities() {
        return cities;
    }

    OsmClient osm() {
        return osm;
    }

    void reload() {
        FileConfiguration c = plugin.getConfig();
        osm = new OsmClient(overpassUrls(c), c.getString("stadt.nominatim-url",
                "https://nominatim.openstreetmap.org/search"), c.getString("stadt.plz-land", "de"));
    }

    /**
     * Overpass-Instanzen: Liste aus der Datei, sonst eigene Einzeladresse aus der Datei (Configs vor 2026-10-28),
     * sonst die Vorgabe-Liste. Vorher fragte reload getStringList("stadt.overpass-urls") samt JAR-Vorgaben – die Liste
     * war darum nie leer, und eine eigene stadt.overpass-url wirkte nie: Wer einen eigenen Overpass betrieb, belastete
     * trotzdem die öffentlichen Dienste (Prüfbefund 2026-10-29, ConfigCheck warnte nur). Jetzt zählt für Liste und
     * Einzeladresse nur, was in der Datei steht (contains ohne Vorgaben). Wer das zurückdreht, überstimmt die eigene
     * Instanz des Admins wieder still mit der Vorgabe-Liste. Eine ausdrücklich leere Liste fällt wie bisher auf die
     * Einzeladresse zurück; die alte Vorgabe-Adresse stammt aus der Vorlage, nicht vom Admin – dann die Liste.
     */
    static java.util.List<String> overpassUrls(ConfigurationSection c) {
        if (c.contains("stadt.overpass-urls", true)) {
            java.util.List<String> own = c.getStringList("stadt.overpass-urls");
            if (!own.isEmpty()) {
                return own;
            }
        }
        String single = c.contains("stadt.overpass-url", true) ? c.getString("stadt.overpass-url", "") : "";
        if (single != null && !single.isBlank() && !single.equals(OVERPASS_DEFAULT.getFirst())) {
            return java.util.List.of(single);
        }
        ConfigurationSection defaults = c.getDefaultSection();
        java.util.List<String> bundled = defaults == null ? java.util.List.of()
                : defaults.getStringList("stadt.overpass-urls");
        return bundled.isEmpty() ? OVERPASS_DEFAULT : bundled;
    }

    private int cfg(String key, int def, int min, int max) {
        return Math.max(min, Math.min(max, plugin.getConfig().getInt(key, def)));
    }

    boolean running() {
        return job != null && job.running();
    }

    /** Laufender Bau oder null – für den Fortschrittsbalken (ProgressBarTask). */
    CityJob activeJob() {
        return running() ? job : null;
    }

    /** Lädt /bm stadt gerade Kartendaten (Vorschau)? Der Weltbau fragt dann nicht selbst beim Dienst an. */
    boolean loading() {
        return loading;
    }

    /**
     * Weltbau aktiv? Zwei Ortsbauten gleichzeitig teilten sich den NPC, zwei Abfragen gleichzeitig belasteten den
     * Kartendienst doppelt (Wunsch 2026-10-28).
     */
    private boolean worldBuildBusy(CommandSender sender) {
        WorldBuildService w = plugin.weltbau();
        if (w != null && (w.running() || w.fetching())) {
            Texts.info(sender, w.running() ? "Der Weltbau baut gerade eine Kachel – /bm weltbau stop oder warten."
                    : "Der Weltbau lädt gerade Kartendaten – einen Moment.");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Planen

    /**
     * /bm stadt &lt;ort…&gt; [radius]: Ort suchen, Etappen auslegen und die erste Etappe schon planen – so steht vor
     * „los“ fest, dass die Kartendaten taugen, und der Bau beginnt sofort.
     */
    void prepare(CommandSender sender, String query, Integer radiusArg) {
        if (loading) {
            Texts.info(sender, "Ich lade gerade schon Kartendaten – einen Moment.");
            return;
        }
        if (running()) {
            Texts.info(sender, "Ich baue gerade eine Stadt. /bm fortschritt, /bm stop.");
            return;
        }
        if (worldBuildBusy(sender)) {
            return;
        }
        int maxRadius = cfg("stadt.radius-max", 5000, 50, 20_000);
        // Ohne Angabe: der ganze Ort laut Nominatim (Rechteck um die Ortsgrenze), höchstens radius-max-ganz.
        // Vorher fest 150 m – von Hepberg entstand nur der Ortskern (dev01 2026-10-28).
        int wholeMax = cfg("stadt.radius-max-ganz", 25_000, 100, 60_000);
        int fallback = cfg("stadt.radius-standard", 150, 30, 20_000);
        Integer fixedRadius = radiusArg == null ? null : Math.max(30, Math.min(maxRadius, radiusArg));
        // Grenze je Etappe (256 × 256 m), nicht mehr für den ganzen Ort: Speicher hält nur noch zwei Etappen.
        long maxStageBlocks = plugin.getConfig().getLong("stadt.max-blöcke-je-etappe", 12_000_000L);
        if (pending != null) {
            pending.stages().close();
            pending = null;
        }
        loading = true;
        Texts.info(sender, "Suche „" + query + "“" + (fixedRadius != null ? " im Umkreis von " + fixedRadius + " m"
                : " – den ganzen Ort") + " und lade die Karte (OpenStreetMap) …");
        long t0 = System.nanoTime();
        Path cache = plugin.getDataFolder().toPath().resolve("karten");
        osm.resolve(query).thenCompose(place -> {
            int radius = fixedRadius != null ? fixedRadius
                    : place.extent() > 0 ? Math.max(100, Math.min(wholeMax, place.extent())) : fallback;
            CityStages stages = new CityStages(osm, cache, shortName(place.name()), place.lat(), place.lon(), radius,
                    maxStageBlocks);
            return stages.plan(0).thenApply(first -> new Pending(sender, stages, first));
        }).whenComplete((p, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            loading = false;
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                LOG.log(Level.WARNING, "stadt | laden_fehlgeschlagen | ort=" + query, cause);
                Texts.info(sender, "Das ging nicht: " + cause.getMessage());
                return;
            }
            pending = p;
            long ms = (System.nanoTime() - t0) / 1_000_000;
            CityStages st = p.stages();
            // Ganze Karte schon jetzt holen – während der Spieler den Platz sucht und während gebaut wird.
            st.downloadAll();
            int side = 2 * st.radius();
            Texts.info(sender, String.format(Locale.GERMAN, "%s: %d × %d m in %d Etappen à %d m (Karte in %d Kacheln). "
                    + "Erste Etappe: %d Gebäude, %,d Blöcke (geladen und geplant in %.1f s).", st.place(), side, side,
                    st.count(), CityStages.STAGE, st.tiles(), p.first().buildings(), p.first().blocks(), ms / 1000.0));
            if (st.count() > 1) {
                // Grob: Die erste Etappe liegt in der Mitte und ist meist die dichteste – eine Obergrenze, kein Versprechen.
                int budget = cfg("stadt.blöcke-pro-tick", 2000, 50, 50_000);
                long secs = p.first().blocks() * (long) st.count() / Math.max(1, budget * 20L);
                Texts.info(sender, "Die übrigen Etappen lade und plane ich während des Baus. Grob geschätzt höchstens "
                        + (secs >= 7200 ? secs / 3600 + " h" : secs >= 120 ? secs / 60 + " min" : secs + " s")
                        + " – kleiner geht's mit /bm stadt <Ort> <radius>.");
            }
            Texts.info(sender, "Mitte = dort, wo du stehst (Boden unter deinen Füßen). Achtung: Alles auf der Fläche wird "
                    + "überbaut. Starten: /bm stadt los · an der echten Stelle (wie in der Erdwelt): /bm stadt los echt · "
                    + "Verwerfen: /bm stadt abbrechen · Karte © OpenStreetMap-Mitwirkende");
            LOG.info(() -> String.format("stadt | geplant | ort=%s radius=%d etappen=%d kacheln=%d erste_blöcke=%d "
                    + "dauer_ms=%d", st.place(), st.radius(), st.count(), st.tiles(), p.first().blocks(), ms));
        }));
    }

    private static String shortName(String display) {
        int comma = display.indexOf(',');
        return comma > 0 && !display.matches("^-?\\d.*") ? display.substring(0, comma) : display;
    }

    /**
     * /bm stadt los echt – in der Stadtwelt an der Stelle, die der Ort auch in der Erdwelt hat (CityPlacement), statt
     * unter dem Spieler. Projektion wie die Erdwelt (festgeschrieben in erde/welt.json); ohne Erd-Dienst die aus
     * erde.projektion, damit beide nie auseinanderlaufen.
     */
    void startReal(CommandSender sender) {
        if (pending == null) {
            Texts.info(sender, "Erst planen: /bm stadt <Ort> [radius]");
            return;
        }
        CityStages st = pending.stages();
        EarthProjection projection = plugin.earth() != null ? plugin.earth().projection() : null;
        if (projection == null) {
            try {
                projection = ProjectionChoice.byName(plugin.getConfig().getString("erde.projektion",
                        ProjectionChoice.DEFAULT));
            } catch (IllegalArgumentException e) {
                Texts.info(sender, "Die echte Stelle lässt sich nicht berechnen: " + e.getMessage());
                return;
            }
        }
        java.util.Optional<int[]> xz = CityPlacement.real(projection, st.lat(), st.lon(), st.radius());
        if (xz.isEmpty()) {
            Texts.info(sender, "Die echte Stelle von " + st.place() + " liegt außerhalb der Welt – baue mit "
                    + "/bm stadt los an deiner Stelle.");
            return;
        }
        org.bukkit.World w = BaumeisterPlugin.loadCityWorld(true);
        if (w == null) {
            Texts.info(sender, "Die Stadtwelt ließ sich nicht laden.");
            return;
        }
        int x = xz.get()[0];
        int z = xz.get()[1];
        // Flache Stadtwelt: Boden der Säule (lädt diesen einen Chunk – CityJob braucht ihn ohnehin als ersten).
        Location base = new Location(w, x, w.getHighestBlockYAt(x, z), z);
        start(sender, base);
        if (running()) {
            Texts.info(sender, st.place() + " entsteht an der echten Stelle: Stadtwelt x " + x + " z " + z
                    + " – hin mit /bm ort " + st.place() + ".");
            LOG.info(() -> "stadt | echte_stelle | ort=" + st.place() + " x=" + x + " z=" + z);
        }
    }

    /** /bm stadt los [welt x y z] – Mitte = Boden unter dem Spieler bzw. angegebene Stelle. */
    void start(CommandSender sender, Location center) {
        if (pending == null) {
            Texts.info(sender, "Erst planen: /bm stadt <Ort> [radius]");
            return;
        }
        if (plugin.service().restoring()) {
            Texts.info(sender, "Ich räume gerade noch auf (/bm rückgängig) – danach geht es mit /bm stadt los.");
            return;
        }
        // buildingInWorld statt busy: Eine laufende KI-Planung hält den Stadtbau nicht mehr auf (BuildService).
        if (running() || plugin.service().buildingInWorld()) {
            Texts.info(sender, "Ich bin gerade beschäftigt – /bm stop oder warten.");
            return;
        }
        if (worldBuildBusy(sender)) {
            return;
        }
        Location base = center;
        if (base == null) {
            if (!(sender instanceof Player p)) {
                Texts.info(sender, "Von der Konsole: /bm stadt los <welt> <x> <y> <z>");
                return;
            }
            base = p.getLocation().getBlock().getLocation().add(0, -1, 0);
        }
        Pending p = pending;
        pending = null;
        UUID owner = sender instanceof Player pl ? pl.getUniqueId() : null;
        CityStages st = p.stages();
        CityResume.State s = new CityResume.State(st.place(), st.lat(), st.lon(), st.radius(), st.maxCells(),
                base.getWorld().getName(), base.getBlockX(), base.getBlockY(), base.getBlockZ(), 0, st.count(), false,
                owner, sender.getName(), 0);
        launch(sender, owner, sender.getName(), base, st, p.first(), 0, s);
        // Sprungpunkt vorerst auf dem Boden der Mitte, Blick wie der Auftraggeber. Sobald eine Etappe mit Gebäuden
        // dran ist, wandert er dorthin (TravelSpot) – die Mitte laut Nominatim kann im Feld liegen.
        Location spot = base.clone().add(0.5, 1, 0.5);
        if (sender instanceof Player pl) {
            spot.setYaw(pl.getLocation().getYaw());
        }
        plugin.places().remember(st.place(), spot, PlaceRegistry.Kind.CITY);
    }

    /** Stadtbau ab Etappe startStage starten und seinen Stand mitschreiben (neu und beim Fortsetzen). */
    private void launch(CommandSender sender, UUID owner, String ownerName, Location base, CityStages st,
            CityPlanner.Plan first, int startStage, CityResume.State s) {
        npc.ensureNear(base.clone().add(0.5, 3, 0.5));
        int helpers = cfg("stadt.helfer", 4, 0, 16);
        int budget = cfg("stadt.blöcke-pro-tick", 2000, 50, 50_000);
        int chunks = cfg("stadt.neue-chunks-pro-tick", 8, 1, 64);
        int ms = cfg("stadt.ms-pro-tick", 25, 5, 45);
        int lagMs = cfg("stadt.lag-ms", 150, 60, 2000);
        UndoSpool spool = plugin.getConfig().getBoolean("stadt.rückgängig", true)
                ? new UndoSpool(plugin.getDataFolder().toPath().resolve("rückgängig")
                        .resolve(String.valueOf(System.currentTimeMillis())))
                : null;
        CityJob j0 = new CityJob(plugin, npc, sender, owner, ownerName, base, st, first, startStage, budget, helpers,
                chunks, ms, lagMs, spool, null, j -> {
                    plugin.service().pushUndo(j.undoRecord());
                    // Fertig oder ausdrücklich gestoppt: nichts mehr fortzusetzen. Herunterfahren und Fehler lassen den
                    // Stand stehen (shutdown schreibt ihn vorher).
                    if (j.completed() || stoppedByUser) {
                        resume.delete();
                    }
                    stoppedByUser = false;
                    if (job == j) {
                        job = null;
                    }
                });
        job = j0;
        state = s;
        resume.save(state);
        cities.put(s);
        j0.onResumePoint(k -> {
            state = state.at(k, j0.paused());
            resume.save(state);
        });
        TravelSpot travel = new TravelSpot(s.travel());
        j0.onStage(plan -> travel.offer(plan.sections()).ifPresent(t -> {
            Location at = base.clone().add(t.x() + 0.5, 1, t.z() + 0.5);
            plugin.places().remember(st.place(), at, PlaceRegistry.Kind.CITY);
            state = state.withTravel(t.buildings());
            resume.save(state);
            LOG.info(() -> "orte | reisepunkt_versetzt | name=" + st.place() + " gebäude=" + t.buildings() + " x="
                    + at.getBlockX() + " z=" + at.getBlockZ());
        }));
        Texts.info(sender, (startStage > 0 ? "Weiter geht's: " + st.place() + " ab Etappe " + (startStage + 1) + " von "
                + st.count() : "Los geht's: " + st.place()) + " mit " + helpers + " Helfern, " + budget
                + " Blöcke/Tick. Steuern: /bm pause · weiter · tempo <n> · helfer <n> · fortschritt · stop");
        j0.start();
    }

    /**
     * Gespeicherten Stadtbau fortsetzen (nach einem Neustart von selbst, sonst mit /bm weiter). Ort aus der Datei,
     * Kartenkacheln aus dem Zwischenspeicher; ab der ersten nicht ganz fertigen Etappe.
     */
    void resumeSaved(CommandSender sender) {
        if (running() || loading || plugin.service().buildingInWorld()) {
            Texts.info(sender, "Ich bin gerade beschäftigt – erst wenn der laufende Bau fertig ist.");
            return;
        }
        if (worldBuildBusy(sender)) {
            return;
        }
        java.util.Optional<CityResume.State> loaded = resume.load();
        if (loaded.isEmpty()) {
            Texts.info(sender, "Es gibt keinen unterbrochenen Stadtbau.");
            return;
        }
        CityResume.State s = loaded.get();
        org.bukkit.World w = Bukkit.getWorld(s.world());
        if (w == null) {
            // Datei bleibt: Lädt Multiverse die Welt später, geht es mit /bm weiter.
            Texts.info(sender, "Stadtbau " + s.place() + " wartet: Welt " + s.world() + " ist nicht geladen.");
            LOG.warning("stadt | fortsetzen_welt_fehlt | ort=" + s.place() + " welt=" + s.world());
            return;
        }
        if (s.stage() >= s.stages()) {
            resume.delete();
            Texts.info(sender, "Stadtbau " + s.place() + " war schon fertig.");
            return;
        }
        Path cache = plugin.getDataFolder().toPath().resolve("karten");
        CityStages st = new CityStages(osm, cache, s.place(), s.lat(), s.lon(), s.radius(), s.maxStageBlocks());
        if (st.count() != s.stages()) {
            // Anderes Etappen-Raster (andere Fassung): Etappe k wäre eine andere Stelle – lieber nicht raten.
            st.close();
            Texts.info(sender, "Stadtbau " + s.place() + " lässt sich nicht fortsetzen (Etappen " + st.count() + " statt "
                    + s.stages() + ").");
            LOG.warning("stadt | fortsetzen_etappen_abweichend | ort=" + s.place());
            return;
        }
        st.downloadAll();
        loading = true;
        Texts.info(sender, "Setze Stadtbau " + s.place() + " fort: Etappe " + (s.stage() + 1) + " von " + s.stages()
                + (s.paused() ? " (pausiert – weiter mit /bm weiter)" : "") + " …");
        LOG.info(() -> "stadt | fortsetzen | ort=" + s.place() + " etappe=" + (s.stage() + 1) + "/" + s.stages()
                + " pause=" + s.paused());
        Location base = new Location(w, s.x(), s.y(), s.z());
        st.plan(s.stage()).whenComplete((first, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            loading = false;
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                LOG.log(Level.WARNING, "stadt | fortsetzen_fehlgeschlagen | ort=" + s.place(), cause);
                Texts.info(sender, "Fortsetzen ging nicht: " + cause.getMessage() + " – noch einmal mit /bm weiter.");
                st.close();
                return;
            }
            if (running()) {
                st.close();
                return;
            }
            launch(sender, s.owner(), s.ownerName(), base, st, first, s.stage(), s);
            if (s.paused()) {
                job.pause(true);
            }
        }));
    }

    /** Nach dem Serverstart: gespeicherten Stadtbau von selbst fortsetzen (stadt.fortsetzen-nach-neustart). */
    void resumeAfterRestart() {
        if (resume.exists() && plugin.getConfig().getBoolean("stadt.fortsetzen-nach-neustart", true)) {
            resumeSaved(Bukkit.getConsoleSender());
        }
    }

    /** Pause/Weiter im Stand festhalten, damit ein Neustart sie behält. */
    private void savePause() {
        if (job != null && state != null) {
            state = state.at(job.resumeStage(), job.paused());
            resume.save(state);
        }
    }

    void discard(CommandSender sender) {
        Texts.info(sender, pending == null ? "Es liegt kein Stadtplan bereit." : "Stadtplan verworfen.");
        if (pending != null) {
            pending.stages().close();
        }
        pending = null;
    }

    // ------------------------------------------------------------------ Steuern

    /** false = keine Stadt läuft (Aufrufer kann dann den normalen Bau ansprechen). */
    boolean control(CommandSender sender, String action, String arg) {
        if (!running()) {
            if (action.equals("komm")) {
                return comeHere(sender);
            }
            // Unterbrochener Stadtbau (Neustart, Fortsetzen abgeschaltet oder gescheitert): /bm weiter holt ihn zurück.
            if (action.equals("weiter") && resume.exists() && !plugin.service().buildingInWorld()) {
                resumeSaved(sender);
                return true;
            }
            return false;
        }
        switch (action) {
            case "pause" -> {
                job.pause(true);
                savePause();
                Texts.info(sender, "Pause. Weiter mit /bm weiter – auch nach einem Neustart.");
            }
            case "weiter" -> {
                job.pause(false);
                savePause();
                Texts.info(sender, "Weiter geht's.");
            }
            case "tempo" -> {
                Integer n = parse(arg);
                if (n == null) {
                    Texts.info(sender, "Tempo jetzt " + job.budget() + " Blöcke/Tick. Ändern: /bm tempo <50–50000>");
                } else {
                    job.budget(Math.max(50, Math.min(50_000, n)));
                    Texts.info(sender, "Tempo: " + job.budget() + " Blöcke/Tick (= " + job.budget() * 20 + "/s).");
                }
            }
            case "schneller" -> job.budget(Math.min(50_000, job.budget() * 2));
            case "langsamer" -> job.budget(Math.max(50, job.budget() / 2));
            case "helfer" -> {
                Integer n = parse(arg);
                if (n == null) {
                    Texts.info(sender, job.helpers() + " Helfer. Ändern: /bm helfer <0–16>");
                } else {
                    job.helpers(Math.max(0, Math.min(16, n)));
                    Texts.info(sender, "Jetzt " + job.helpers() + " Helfer.");
                }
            }
            // Chat „mehr helfer“/„weniger helfer“: je zwei, damit man nicht achtmal schreiben muss.
            case "mehrhelfer", "wenigerhelfer" -> {
                job.helpers(Math.max(0, Math.min(16, job.helpers() + (action.equals("mehrhelfer") ? 2 : -2))));
                Texts.info(sender, "Jetzt " + job.helpers() + " Helfer.");
            }
            case "fortschritt" -> Texts.info(sender, job.progress());
            case "stop" -> {
                CityJob stopped = job;
                stoppedByUser = true;
                stopped.cancel("Gestoppt. Was steht, bleibt stehen – /bm rückgängig räumt es weg.");
                // cancel() meldet sich beim Auftraggeber; wer von woanders stoppt (Konsole, anderer Admin), bekam
                // im Test 2026-10-28 gar keine Antwort.
                if (sender != stopped.requester()) {
                    Texts.info(sender, "Stadtbau gestoppt.");
                }
            }
            case "komm" -> comeHere(sender);
            default -> {
                return false;
            }
        }
        if (action.equals("schneller") || action.equals("langsamer")) {
            Texts.info(sender, "Tempo: " + job.budget() + " Blöcke/Tick.");
        }
        return true;
    }

    private boolean comeHere(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Texts.info(sender, "Nur im Spiel.");
            return true;
        }
        Mannequin m = npc.ensureNear(p.getLocation());
        if (m != null) {
            m.teleport(BuilderNpc.facing(p.getLocation().clone().add(p.getLocation().getDirection().setY(0)
                    .normalize().multiply(2)), BuilderNpc.eye(p.getLocation())));
        }
        Texts.info(sender, "Bin da.");
        return true;
    }

    private static Integer parse(String s) {
        try {
            return s == null ? null : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Chat an den Baumeister: nur noch „los“ und „&lt;Ort&gt; nachbauen“, beides nur für Admins. true = erledigt.
     * Die Steuerwörter („stopp“, „schneller“ …) erkennt seit 2026-10-28 {@link ChatIntent} im Listener – vorher
     * wirkten sie hier nur bei laufender Stadt, sonst ging „stopp“ als Bauauftrag ans LLM.
     */
    boolean handleChat(Player player, String text) {
        String t = text.trim().toLowerCase(Locale.GERMAN).replaceAll("[!.?]+$", "");
        boolean admin = player.hasPermission("baumeister.admin");
        if (t.equals("los") || t.equals("bau los") || t.equals("fang an")) {
            if (pending != null && admin) {
                start(player, null);
                return true;
            }
        }
        CityRequest req = parseCityRequest(text);
        if (req != null && admin) {
            prepare(player, req.place(), req.radius());
            return true;
        }
        return false;
    }

    /** Ort und Radius aus einer Chat-Bitte oder null. */
    record CityRequest(String place, Integer radius) {
    }

    /**
     * „bau Bad Kreuznach nach“, „bau mir das Dorf Hargesheim mit 300 m nach“, „Mainz 1:1 nachbauen“. Nur mit „nach“ am
     * Ende – „bau mir ein Haus“ soll weiter ans LLM gehen.
     */
    static CityRequest parseCityRequest(String text) {
        Matcher m = CHAT_CITY.matcher(text.trim().replaceAll("[!.]+$", ""));
        if (!m.matches()) {
            return null;
        }
        return new CityRequest(m.group(1).trim(), m.group(2) != null ? Integer.valueOf(m.group(2)) : null);
    }

    void shutdown() {
        if (job != null && job.running() && state != null) {
            // Stand zuerst: cancel räumt die Warteschlange, danach wäre der Fortsetzungspunkt verloren.
            int k = job.resumeStage();
            state = state.at(k, job.paused());
            resume.save(state);
            LOG.info(() -> "stadt | gesichert | ort=" + state.place() + " etappe=" + (k + 1) + "/" + state.stages()
                    + " pause=" + state.paused());
            job.cancel("Server fährt herunter – Stadtbau gesichert, nach dem Neustart geht es bei Etappe " + (k + 1)
                    + " weiter.");
        } else if (job != null) {
            job.cancel("Server fährt herunter – Stadtbau abgebrochen.");
        }
        if (pending != null) {
            pending.stages().close();
            pending = null;
        }
    }
}
