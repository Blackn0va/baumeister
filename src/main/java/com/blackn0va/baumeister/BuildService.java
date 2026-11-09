package com.blackn0va.baumeister;

import com.blackn0va.baumeister.BuildJob.Placement;
import com.blackn0va.baumeister.BuildJob.UndoRecord;
import com.blackn0va.baumeister.BuildJob.WorldAction;
import com.blackn0va.baumeister.BuildJob.WorldChange;
import com.blackn0va.baumeister.plan.AttachPlanner;
import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.BlockTiers;
import com.blackn0va.baumeister.plan.CommandText;
import com.blackn0va.baumeister.plan.Nbt;
import com.blackn0va.baumeister.plan.PlanCommands;
import com.blackn0va.baumeister.plan.Snbt;
import com.blackn0va.baumeister.plan.BuildPlan;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;
import com.blackn0va.baumeister.plan.FarmGuide;
import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.OrderIntent;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.PlanReview;
import com.blackn0va.baumeister.plan.PlanPrompt;
import com.blackn0va.baumeister.plan.PlotLayout;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.QuickOrders;
import com.blackn0va.baumeister.plan.QuickPlacement;
import com.blackn0va.baumeister.plan.TemplateCatalog;
import com.blackn0va.baumeister.plan.Tier;
import com.blackn0va.baumeister.plan.SpokenWords;
import com.blackn0va.baumeister.plan.RequestHints;
import com.blackn0va.baumeister.plan.SiteSurvey;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Vom Chat-Auftrag bis zum laufenden Bau. Alles hier läuft auf dem Hauptthread, außer dem
 * Warten auf das LLM (HttpClient-Thread) und dem Ablegen der Rohantwort.
 *
 * Bewusst nur ein Auftrag gleichzeitig: Das LLM läuft auf einem Laptop mit einem Slot. Ein
 * zweiter Auftrag würde nur hinter dem ersten warten und dabei den Timeout verbrauchen.
 */
final class BuildService {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final int MAX_PROMPT = 300;
    private static final int KEEP_PLANS = 50;

    private final BaumeisterPlugin plugin;
    private final BuilderNpc npc;
    private final Settings settings;
    private final LlmClient llm;
    private final String systemPrompt;
    private final PlanCompiler compiler;
    private final ModuleLibrary modules;
    private final Deque<UndoRecord> undo = new ArrayDeque<>();

    private boolean thinking;
    private BuildJob active;
    /** Laufendes Rückgängig (höchstens eines, siehe restore) und die, die danach drankommen. */
    private RestoreJob restoring;
    private org.bukkit.scheduler.BukkitTask restoringTask;
    /** Fertiger KI-Plan, der auf das Ende eines Stadt-/Weltbaus wartet (DeferredStart), und sein Prüf-Timer. */
    private final DeferredStart deferred = new DeferredStart();
    private org.bukkit.scheduler.BukkitTask deferredTask;
    private final Deque<Map.Entry<CommandSender, UndoRecord>> undoQueue = new ArrayDeque<>();

    /** Komfort: Warteschlange, Bauvorschau, Rückgängig über Neustart (ComfortService; Entscheidungen: ComfortFlow). */
    private final ComfortService comfort;
    /** Recherche echter Bauwerke vor dem LLM (llm.recherche), null = aus. */
    private final ReferenceLookup research;

    /**
     * Was zu einem Auftrag gehört, solange das LLM rechnet. Paketweit für ComfortService. previewOnly: nur planen und
     * den Umriss zeigen, nicht bauen. Vorher stand das als veränderlicher Dienst-Merker (planOnly) neben dem Auftrag
     * und stimmte nur, solange genau ein Plan unterwegs war. Jetzt reist es mit dem Auftrag. Es gibt keinen Konstruktor
     * ohne das Feld: Jede Kopie muss es ausdrücklich weitergeben, sonst baute eine Vorschau.
     */
    /**
     * cap: höchste Stufe, mit der dieser Auftrag bauen darf (Plan B5) – COMMANDS = nur die Rechte des Auftraggebers
     * zählen; CONTENTS, sobald fremder Text (Recherche) im Prompt stand oder der fortgeschriebene Plan so entstand.
     * consent: wie Befehle aus Plantext behandelt werden (fragen, genau diese bestätigt, weglassen).
     */
    record Request(CommandSender sender, UUID ownerId, String ownerName, boolean checkAsOwner,
            Location feet, Cardinal facing, String prompt, SiteFrame frame, SiteSurvey survey, Memory memory,
            boolean previewOnly, PlotSquaredPlots.PlotSite plot, Tier cap, CommandConsent consent) {

        /**
         * Ohne Plot: Den Plot setzt erst request() (withPlot), nachdem der Rahmen feststeht – alles davor (Chat, Befehl,
         * Warteschlange) kennt ihn nicht. Die with-Kopien unten geben ihn weiter.
         */
        Request(CommandSender sender, UUID ownerId, String ownerName, boolean checkAsOwner, Location feet,
                Cardinal facing, String prompt, SiteFrame frame, SiteSurvey survey, Memory memory,
                boolean previewOnly) {
            this(sender, ownerId, ownerName, checkAsOwner, feet, facing, prompt, frame, survey, memory, previewOnly,
                    null, Tier.COMMANDS, CommandConsent.ASK);
        }

        Request withSender(CommandSender to) {
            return new Request(to, ownerId, ownerName, checkAsOwner, feet, facing, prompt, frame, survey, memory,
                    previewOnly, plot, cap, consent);
        }

        Request withPreview(boolean preview) {
            return new Request(sender, ownerId, ownerName, checkAsOwner, feet, facing, prompt, frame, survey, memory,
                    preview, plot, cap, consent);
        }

        Request withPlot(PlotSquaredPlots.PlotSite p) {
            return new Request(sender, ownerId, ownerName, checkAsOwner, feet, facing, prompt, frame, survey, memory,
                    previewOnly, p, cap, consent);
        }

        /** Obergrenze senken, nie heben (Tier.min) – fremder Text im Prompt lässt sich nicht wegbestätigen. */
        Request withCap(Tier max) {
            return new Request(sender, ownerId, ownerName, checkAsOwner, feet, facing, prompt, frame, survey, memory,
                    previewOnly, plot, Tier.min(cap, max), consent);
        }

        Request withConsent(CommandConsent c) {
            return new Request(sender, ownerId, ownerName, checkAsOwner, feet, facing, prompt, frame, survey, memory,
                    previewOnly, plot, cap, c);
        }
    }

    /**
     * Befehle aus Plantext (Plan B5): ASK = Liste zeigen und auf den Klick warten; CONFIRMED = genau die Fassung mit
     * dieser Prüfsumme (PlanCommands.fingerprint) bauen; WITHOUT = ohne diese Zellen bauen.
     */
    record CommandConsent(Kind kind, String fingerprint) {

        enum Kind {
            ASK, CONFIRMED, WITHOUT
        }

        static final CommandConsent ASK = new CommandConsent(Kind.ASK, null);
        static final CommandConsent WITHOUT = new CommandConsent(Kind.WITHOUT, null);

        static CommandConsent confirmed(String fingerprint) {
            return new CommandConsent(Kind.CONFIRMED, fingerprint);
        }
    }

    /** Plotrahmen (Ecke, Breite, Tiefe) zur Blickrichtung des Auftrags; null ohne Plot-Modus. */
    private static PlotLayout.Frame plotFrame(Request r) {
        return r.plot() == null ? null : PlotLayout.frame(r.plot().rect(), new Orientation(r.facing()));
    }

    /** Grundfläche, auf die die Recherche ein Bauwerk im Plot-Modus verkleinert (0 = keine Grenze). */
    private static int plotFootprint(PlotLayout.Frame pf) {
        return pf == null ? 0 : Math.max(1, Math.min(pf.width(), pf.depth()) - 2 * PlotLayout.MARGIN);
    }

    /** Bau samt Plan-JSON, wie startJob ihn ausführt (im Plot-Modus mit Gestaltung). */
    private record Placed(CompiledBuild build, String json) {
    }

    private final PlotSquaredPlots plots = new PlotSquaredPlots();
    /** Auftrag und Plot des laufenden Baus – für die Ergebnis-Meldung an den Host (onJobFinished). */
    private String activeOrder = "";
    private String activePlot = "";
    /** Nachbesserungen ohne LLM und LLM-Runden des laufenden Baus – für die Ergebnis-Meldung an die App. */
    private List<String> activeFixes = List.of();
    private int activeRounds = 0;
    private List<String> pendingFixes = List.of();
    private int pendingRounds = 0;

    /** Auftragstext wie er ans LLM ging (ohne Helfer- und Plot-Angabe) – so ordnet die App das Ergebnis zu. */
    private static String orderText(Request r) {
        return PlotLayout.strip(HelperWish.strip(Texts.clean(r.prompt(), MAX_PROMPT)));
    }

    /** Ergebnis an die App (ResultReporter, reine Anzeige); still, wenn abgeschaltet. */
    private void reportResult(Request r, String outcome, String reason, long blocks) {
        reportResult(r, outcome, reason, blocks, List.of(), 0);
    }

    private void reportResult(Request r, String outcome, String reason, long blocks, List<String> fixes,
            int rounds) {
        ResultReporter rr = plugin.resultReporter();
        if (rr != null) {
            rr.report(orderText(r), outcome, reason, blocks, r.plot() == null ? "" : r.plot().id(), fixes, rounds);
        }
    }

    /**
     * Gedächtnis je Spieler: der letzte Bau in den Koordinaten seines Bauplatzes (SiteFrame). Wunsch 2026-10-28. Vorher
     * begann jeder Auftrag
     * bei null – „mach das Dach rot“ kannte kein Dach. Jetzt plant ein Folgeauftrag in der Nähe im selben
     * Koordinatensystem und sieht den alten Plan; mit mode "update" ersetzt der neue Plan den alten (startJob räumt
     * weg, was fehlt).
     *
     * @param planJson alter Plan, Koordinaten schon in den Rahmen verschoben (startJob zentriert freie Bauten)
     * @param cells    gesetzte Blöcke (Welt, gepackt) → Material: weggeräumt wird nur, was noch so dasteht
     */
    /**
     * cap: Obergrenze des Auftrags, aus dem dieser Plan stammt (Request.cap). Ein Folgeauftrag schreibt den alten Plan
     * fort und erbt sie – sonst brächte „mach ihn höher“ die Lava zurück, die eingeschleuster Recherche-Text in den
     * ersten Plan geschrieben hatte und die dort weggelassen wurde.
     */
    private record Memory(SiteFrame frame, Location feet, Cardinal facing, String planJson,
            Map<Long, org.bukkit.Material> cells, java.time.Instant when, String prompt, Tier cap) {
    }

    private final Map<UUID, Memory> memory = new java.util.HashMap<>();
    /** Baubuch für „reparieren“ (BuildLedger); wird nach jedem vollständigen Bau außerhalb des Hauptthreads ergänzt. */
    private final BuildLedger ledger;
    /** Bauvorlagen von Minecraft (VanillaTemplates); ohne Aufruf von useTemplates gibt es keine (Tests, Probe). */
    private TemplateCatalog.Source templates = name -> Optional.empty();
    /** Bauwerk-Dateien (FileSchematics); null in Tests ohne Plugin. */
    private FileSchematics fileSchematics;

    /** Was der laufende Auftrag fürs Baubuch ist: neuer Bau, Umbau (ersetzt den alten Eintrag) oder Reparatur. */
    private enum LedgerKind {
        NEW, UPDATE, REPAIR
    }

    private LedgerKind activeKind = LedgerKind.NEW;
    private String activeLedgerOrder = "";
    private String activeLedgerInfo = "";
    /**
     * Bedienhinweise der Farmen im laufenden Bau (FarmGuide), der Bau, für den sie gelten, und wer sie bekommt. An den
     * Bau gebunden statt an „den laufenden“: Eine Reparatur startet ihren BuildJob ohne startJob – mit einem bloßen Feld
     * hätte sie nach dem Fertigwerden die Hinweise des letzten Baus noch einmal gesagt.
     */
    private List<FarmGuide.Guide> activeGuides = List.of();
    private BuildJob guidedJob;
    private CommandSender guideTo;
    /**
     * Stehender Helfer-Wunsch je Spieler (/bm helfer, Chat „mehr helfer“; HelperWish). Nur im Speicher: Nach einem
     * Neustart gilt wieder die Größenregel – ein vergessener Wunsch „16“ soll nicht ewig 16 NPCs je Bau holen.
     */
    private final Map<UUID, Integer> helperWish = new java.util.HashMap<>();
    /** Schlüssel für Konsole/RCON im Helfer-Wunsch. */
    private static final UUID CONSOLE = new UUID(0, 0);
    /** Eine Stunde, 48 Blöcke: Wer weggeht oder später wiederkommt, meint einen neuen Bau. */
    static final java.time.Duration MEMORY_TTL = java.time.Duration.ofHours(1);
    static final int MEMORY_RADIUS = 48;

    private Memory recall(Request r) {
        Memory m = r.ownerId() == null ? null : memory.get(r.ownerId());
        if (m == null || m.when().plus(MEMORY_TTL).isBefore(java.time.Instant.now())
                || !m.feet().getWorld().equals(r.feet().getWorld()) || m.feet().distance(r.feet()) > MEMORY_RADIUS) {
            return null;
        }
        return m;
    }

    /** „vergiss“: Gedächtnis eines Spielers löschen, der nächste Auftrag beginnt frisch. */
    boolean forget(UUID owner) {
        return owner != null && memory.remove(owner) != null;
    }

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (long) y & 0xFFF;
    }

    static int unpackX(long k) {
        return (int) (k >> 38);
    }

    static int unpackZ(long k) {
        return (int) (k << 26 >> 38);
    }

    static int unpackY(long k) {
        return (int) (k << 52 >> 52);
    }

    /** from/to/pos aller Schritte um (dx, dz) verschieben – derselbe Plan in Rahmen-Koordinaten. */
    static String translate(String planJson, int dx, int dz) {
        JsonObject root = com.google.gson.JsonParser.parseString(planJson.substring(planJson.indexOf('{'),
                planJson.lastIndexOf('}') + 1)).getAsJsonObject();
        if (root.has("steps")) {
            for (var el : root.getAsJsonArray("steps")) {
                JsonObject st = el.getAsJsonObject();
                for (String k : List.of("from", "to", "pos")) {
                    if (st.has(k) && st.get(k).isJsonArray() && st.getAsJsonArray(k).size() == 3) {
                        JsonArray v = st.getAsJsonArray(k);
                        JsonArray moved = new JsonArray();
                        moved.add(v.get(0).getAsInt() + dx);
                        moved.add(v.get(1).getAsInt());
                        moved.add(v.get(2).getAsInt() + dz);
                        st.add(k, moved);
                    }
                }
                PlanCommands.shiftPlaceholders(st, dx, 0, dz);
            }
        }
        root.remove("reply");
        return root.toString();
    }

    BuildService(BaumeisterPlugin plugin, BuilderNpc npc, Settings settings, LlmClient llm, String systemPrompt,
            ModuleLibrary modules) {
        this.plugin = plugin;
        this.npc = npc;
        this.settings = settings;
        this.llm = llm;
        this.systemPrompt = systemPrompt;
        this.modules = modules;
        // Ein einzelnes Schematic darf größer sein als ein LLM-Plan (PlanCompiler.Limits.forPlan, 2026-11-08).
        this.compiler = new PlanCompiler(new BukkitBlockCatalog(), settings.fallbackBlock(),
                new PlanCompiler.Limits(settings.maxBlocks(), settings.maxExtent(),
                        Math.clamp(plugin.getConfig().getInt("schematics.max-blöcke", 1_000_000), 1, 4_000_000),
                        Math.clamp(plugin.getConfig().getInt("schematics.max-ausdehnung", 384), 4, 512)), modules);
        this.comfort = new ComfortService(plugin, this, npc, settings);
        this.ledger = new BuildLedger(plugin.getDataFolder().toPath().resolve("bauten"));
        org.bukkit.configuration.file.FileConfiguration c = plugin.getConfig();
        this.research = c.getBoolean("llm.recherche", true) ? new ReferenceLookup(
                c.getString("llm.recherche-wikipedia", "https://de.wikipedia.org/w/api.php"),
                c.getString("llm.recherche-wikidata", "https://www.wikidata.org/w/api.php"),
                plugin.getDataFolder().toPath().resolve("wissen"), c.getString("llm.recherche-kontakt", ""),
                settings.llmUrl().resolve("/baumeister/v1/recherche")) : null;
    }

    ComfortService comfort() {
        return comfort;
    }

    void useTemplates(TemplateCatalog.Source source) {
        this.templates = source;
    }

    void useSchematics(FileSchematics files) {
        this.fileSchematics = files;
    }

    ModuleLibrary modules() {
        return modules;
    }

    LlmClient llm() {
        return llm;
    }

    // ---------------------------------------------------------------- Auftrag

    /** Auftrag eines Spielers: Bauplatz vor ihm, Rechte werden in seinem Namen geprüft. */
    void requestFromPlayer(Player p, String prompt) {
        // „bau mir ein Display/einen Bildschirm (mit bewegten Bildern)“: Karten in Rahmen, kein Bauplan (ScreenService).
        // Vorher wurde daraus die 7-Segment-Anzeige (Wunsch 2026-11-07).
        com.blackn0va.baumeister.screen.ScreenRequest.Request screen =
                com.blackn0va.baumeister.screen.ScreenRequest.parse(prompt);
        if (screen != null && plugin.screens() != null) {
            Texts.info(p, plugin.screens().build(p, screen));
            return;
        }
        Location feet = p.getLocation().getBlock().getLocation();
        request(new Request(p, p.getUniqueId(), p.getName(), settings.checkPermissions(), feet,
                Cardinal.fromYaw(p.getLocation().getYaw()), prompt, null, null, null, false));
    }

    /** Auftrag an festen Koordinaten (Konsole/RCON, nur Admins). Keine Spieler-Rechteprüfung. */
    void requestAt(CommandSender sender, Location feet, Cardinal facing, String prompt) {
        UUID owner = sender instanceof Player p ? p.getUniqueId() : null;
        request(new Request(sender, owner, sender.getName(), false, feet, facing, prompt, null, null, null, false));
    }

    /**
     * Ein Bauteil der Bibliothek direkt bauen, ohne LLM (nur Admins): vordere linke untere Ecke genau bei corner,
     * Betrachter blickt nach facing. Für eingebaute Maschinen der verlässliche Weg – und die Grundlage der
     * Funktionsprobe (MachineProbe), die die Ecke kennen muss. Keine Zentrierung, kein Abstand nach vorn.
     */
    /** Gibt es das Bauteil (auch _gespiegelt/_doppelt)? Für Vera: sonst geht der Satz an das Modell. */
    boolean hasModule(String name) {
        return modules.find(name).isPresent();
    }

    void requestModuleAt(CommandSender sender, Location corner, Cardinal facing, String name) {
        if (modules.find(name).isEmpty()) {
            Texts.info(sender, "Bauteil '" + name + "' gibt es nicht. /bm bauteile zeigt alle.");
            return;
        }
        JsonObject step = new JsonObject();
        step.addProperty("op", "module");
        step.addProperty("name", name);
        directAt(sender, corner, facing, step, "bauteil " + name, "Baue Bauteil '" + name + "'.");
    }

    /**
     * Logik-Schaltung aus einer Formel direkt bauen (LogicCircuit), ohne LLM – wie requestModuleAt: vordere linke
     * untere Ecke bei corner. Die Formel geht durch denselben Parser wie ein LLM-Plan (op logic).
     */
    void requestLogicAt(CommandSender sender, Location corner, Cardinal facing, String formula) {
        com.blackn0va.baumeister.logic.LogicCircuit.Circuit circuit;
        try {
            circuit = com.blackn0va.baumeister.logic.LogicCircuit.compile(formula);
        } catch (IllegalArgumentException e) {
            Texts.info(sender, "Die Formel geht nicht: " + e.getMessage());
            return;
        }
        JsonObject step = new JsonObject();
        step.addProperty("op", "logic");
        step.addProperty("expr", formula);
        // Ansage aus LogicCircuit: bei mehreren Ausgängen (Formeln mit „;“) nennt sie die Lampen der Reihe nach.
        directAt(sender, corner, facing, step, "logik " + formula,
                com.blackn0va.baumeister.logic.LogicCircuit.announce(circuit, formula));
    }

    private void directAt(CommandSender sender, Location corner, Cardinal facing, JsonObject step, String label,
            String announce) {
        if (thinking || active != null && active.running()) {
            Texts.info(sender, "Ich bin gerade beschäftigt – /bm stop oder kurz warten.");
            return;
        }
        String conflict = otherBuildConflict();
        if (conflict != null) {
            Texts.info(sender, conflict);
            return;
        }
        String name = label;
        CompiledBuild build;
        try {
            JsonArray pos = new JsonArray();
            pos.add(0);
            pos.add(0);
            pos.add(0);
            step.add("pos", pos);
            JsonObject plan = new JsonObject();
            plan.addProperty("design", "");
            plan.addProperty("reply", "");
            JsonArray steps = new JsonArray();
            steps.add(step);
            plan.add("steps", steps);
            build = compiler.compile(PlanParser.parse(plan.toString()));
        } catch (PlanException e) {
            Texts.info(sender, "Nicht baubar: " + e.getMessage());
            return;
        }
        SiteFrame frame = new SiteFrame(corner.getWorld(), corner.getBlockX(), corner.getBlockY(), corner.getBlockZ(),
                new Orientation(facing), 0);
        UUID owner = sender instanceof Player p ? p.getUniqueId() : null;
        // Plan B6: Vorher prüfte der Direktbau keine Regionsrechte (checkAsOwner false) – Veras „bau … hier“ braucht
        // nur baumeister.bauen und setzte damit in fremden Plots. Jetzt wie ein Chat-Auftrag je Block im Namen des
        // Spielers; die Konsole (MachineProbe, bauenbei) bleibt ungeprüft.
        boolean check = directCheck(sender instanceof Player, settings.checkPermissions());
        Request r = new Request(sender, owner, sender.getName(), check, corner, facing, label, frame, null, null,
                false);
        npc.ensureNear(corner.clone().add(0.5, 0, 0.5));
        LOG.info(() -> String.format("bauteil | direktbau | was=%s welt=%s x=%d y=%d z=%d richtung=%s", name,
                corner.getWorld().getName(), corner.getBlockX(), corner.getBlockY(), corner.getBlockZ(), facing));
        Texts.info(sender, announce);
        showWarnings(r, build);
        startJob(r, build);
    }

    /** Prüft der Direktbau (Bauteil, Logik, Veras „bau … hier“) die Regionsrechte je Block? Spieler ja, Konsole nein. */
    static boolean directCheck(boolean senderIsPlayer, boolean checkPermissions) {
        return checkPermissions && senderIsPlayer;
    }

    /**
     * Absturz-Audit 2026-10-30: Ein normaler Bau startete auch, während der Stadt- oder Weltbau lief; beide halten
     * Plugin-Tickets auf ihren Chunks und gaben sie sich gegenseitig frei (BuildJob und CityJob nehmen dieselbe
     * Plugin-Kennung). Die Gegenrichtung prüfen CityService und WorldBuildService schon (service().busy()).
     * Wer diese Sperre entfernt, lässt beim Stop des einen Baus die Chunks des anderen unter ihm entladen.
     */
    private String otherBuildConflict() {
        CityService city = plugin.city();
        WorldBuildService world = plugin.weltbau();
        return conflictMessage(city != null && city.running(), world != null && world.running());
    }

    /** Ohne Server testbar: null = frei. */
    static String conflictMessage(boolean cityRunning, boolean worldRunning) {
        if (cityRunning) {
            return "Gerade läuft ein Stadtbau – erst „stopp“ oder warten, bis er fertig ist.";
        }
        if (worldRunning) {
            return "Gerade baut der Weltbau eine Kachel – erst „/bm weltbau stop“ oder kurz warten.";
        }
        return null;
    }

    /** Paketweit für ComfortService (Warteschlange, Vorschau). Beschäftigt: einreihen statt abweisen. */
    void request(Request req) {
        Request r = req;
        // Abenteuer in der Farmwelt (CompanionAdventure): Dort baut der Spieler selbst. Vorher sperrte nur Vera – „Baumeister,
        // bau mir eine Burg“ im Chat, /bm bau und die Vorschau gingen am Fair-Play vorbei (Gedächtnis „Offen“
        // 2026-11-04). Hier, weil alle Spieler-Wege (Chat, Befehl, Vorschau, Warteschlange, Vera) durch request laufen.
        if (r.sender() instanceof Player p && plugin.companion() != null && plugin.companion().adventureFair(p)) {
            Texts.info(p, "Im Abenteuer baust du selbst – der Baumeister macht so lange Pause. "
                    + "(„Vera, beende das Abenteuer“ hebt das auf.)");
            LOG.info(() -> "bau | abgelehnt | besitzer=" + p.getName() + " grund=abenteuer");
            return;
        }
        if (thinking) {
            if (comfort.enqueue(r)) {
                return;
            }
            Texts.info(r.sender(), "Ich plane gerade schon etwas – einen Moment bitte.");
            return;
        }
        if (active != null && active.running()) {
            if (comfort.enqueue(r)) {
                return;
            }
            Texts.info(r.sender(), "Ich baue gerade noch. /bm stop bricht ab.");
            return;
        }
        String conflict = otherBuildConflict();
        if (conflict != null) {
            Texts.info(r.sender(), conflict);
            return;
        }
        // „… mit 6 Helfern“ ist eine Anweisung an den Bau, nicht an den Plan (HelperWish): Das LLM bekommt den Auftrag
        // ohne; startJob liest die Zahl aus r.prompt(), das unverändert bleibt.
        // Ebenso „auf meinem Plot“ (PlotLayout): Das ist der Bauplatz, kein Teil des Bauwerks.
        // SpokenWords: Hörfehler („Rapstone“) vor jeder Erkennung, sonst verfehlt der Auftrag den festen Bauweg.
        String prompt = SpokenWords.fix(PlotLayout.strip(HelperWish.strip(Texts.clean(r.prompt(), MAX_PROMPT))));
        if (prompt.length() < 3) {
            Texts.info(r.sender(), "Was soll ich bauen? Zum Beispiel: " + npc.name() + ", bau ein kleines Haus aus Fichtenholz");
            return;
        }
        // Plot-Modus (PlotLayout): Nennt der Auftrag den Plot und steht der Spieler auf einem, ist der ganze Plot der
        // Bauplatz. Fremder Plot: Absage vor dem LLM – sonst rechnete das Modell minutenlang, und PlotSquared lehnte
        // erst in der Prüfphase ab.
        PlotSquaredPlots.PlotSite plot = null;
        if (PlotLayout.mentioned(r.prompt())) {
            Optional<PlotSquaredPlots.PlotSite> found = plots.at(r.feet(), r.checkAsOwner() ? r.ownerId() : null);
            if (found.isEmpty()) {
                Texts.info(r.sender(), "Du stehst auf keinem Plot – ich baue wie sonst vor dir.");
            } else if (!found.get().mayBuild() && !r.sender().hasPermission("baumeister.admin")) {
                Texts.info(r.sender(), "Plot " + found.get().id() + " gehört nicht dir – dort baue ich nicht.");
                return;
            } else {
                plot = found.get();
            }
        }
        thinking = true;
        // Folgeauftrag in der Nähe des letzten Baus: derselbe Rahmen, damit der alte Plan und der neue dieselben
        // Koordinaten haben. Ein neuer Bau daneben geht trotzdem – das LLM sieht den alten als Bauwerk im Scan.
        // Plot-Modus: immer frisch, der Rahmen ist der Plot.
        // Neues eigenständiges Bauwerk („ein Haus“, „einen Redstone Computer“): kein Gedächtnis, frisch am Standort.
        // Vorher plante das Modell so etwas als update des alten Baus (Vorfall 2026-11-04 13:49, siehe OrderIntent).
        boolean fresh = OrderIntent.isNewStructure(r.prompt());
        Memory mem = plot != null || fresh ? null : recall(r);
        if (fresh && plot == null && recall(r) != null) {
            String who = r.ownerName();
            String order = Texts.clean(r.prompt(), 80);
            LOG.info(() -> "bau | neuer_bau_trotz_gedächtnis | besitzer=" + who + " auftrag=" + order);
        }
        if (mem != null) {
            // Obergrenze des fortgeschriebenen Plans erben (Memory.cap): nur senken, nie heben.
            r = new Request(r.sender(), r.ownerId(), r.ownerName(), r.checkAsOwner(), mem.feet(), mem.facing(),
                    r.prompt(), null, null, mem, r.previewOnly(), null, Tier.min(r.cap(), mem.cap()), r.consent());
        }
        PlotLayout.Frame pf = plot == null ? null : PlotLayout.frame(plot.rect(), new Orientation(r.facing()));
        SiteFrame frame = pf != null
                ? new SiteFrame(r.feet().getWorld(), pf.cornerX(), r.feet().getBlockY(), pf.cornerZ(),
                        new Orientation(r.facing()), 0)
                : mem != null ? mem.frame() : new SiteFrame(r.feet().getWorld(), r.feet().getBlockX(),
                        r.feet().getBlockY(), r.feet().getBlockZ(), new Orientation(r.facing()), settings.distanceAhead());
        long scanStart = System.nanoTime();
        SiteSurvey survey = frame.survey();
        long scanMs = (System.nanoTime() - scanStart) / 1_000_000;
        r = new Request(r.sender(), r.ownerId(), r.ownerName(), r.checkAsOwner(), r.feet(), r.facing(), r.prompt(),
                frame, survey, r.memory(), r.previewOnly(), plot, r.cap(), r.consent());
        if (pf != null) {
            PlotSquaredPlots.PlotSite p = plot;
            LOG.info(() -> String.format("plot | modus | id=%s breite=%d tiefe=%d welt=%d/%d..%d/%d", p.id(),
                    pf.width(), pf.depth(), p.rect().minX(), p.rect().minZ(), p.rect().maxX(), p.rect().maxZ()));
        }
        int footprint = plotFootprint(pf);
        LOG.info(() -> String.format("bauplatz | gescannt | dauer_ms=%d bauwerke=%d", scanMs,
                survey.structures().size()));
        Location spot = r.feet().clone().add(0.5, 0, 0.5).add(sideOf(r.facing()).multiply(2));
        Mannequin m = npc.ensureNear(spot);
        npc.status("denkt nach …");
        Player owner = r.ownerId() == null ? null : Bukkit.getPlayer(r.ownerId());
        if (owner != null) {
            npc.lookAt(BuilderNpc.eye(owner.getLocation()));
        }
        // Schnellweg vor dem LLM: Hochhaus mit N Stockwerken und Maschinen per Name brauchen kein Modell. Nach dem
        // Scan, damit der Überbau-Schutz auch hier greift.
        Optional<String> quick = QuickOrders.match(prompt, modules, settings.maxExtent());
        if (quick.isPresent() && acceptPlan(r, prompt, quick.get())) {
            return;
        }
        // Zwischenschicht (TemplateCatalog, Wunsch 2026-11-04): reine
        // Vorlagen-Aufträge („ein Iglu“, „ein Wüstendorf mit 8 Häusern“) ohne LLM, in Millisekunden.
        Optional<TemplateCatalog.Match> tpl = TemplateCatalog.match(prompt, templates, biomeAt(r.feet()),
                settings.maxExtent(), System.nanoTime(), true);
        if (tpl.isPresent()) {
            String what = tpl.get().what();
            LOG.info(() -> "bau | vorlage | weg=direkt " + what);
            if (acceptPlan(r, prompt, tpl.get().json())) {
                return;
            }
        }
        // Bauwerk-Dateien (FileSchematics, 2026-11-08): „bau mir einen Taktgeber“ / „… Variante 3“ ohne LLM.
        Optional<com.blackn0va.baumeister.plan.SchematicCatalog.Match> file = fileSchematics == null
                ? Optional.empty() : fileSchematics.catalog().match(prompt);
        if (file.isPresent()) {
            com.blackn0va.baumeister.plan.SchematicCatalog.Match fm = file.get();
            LOG.info(() -> "bau | schematic | weg=direkt name=" + fm.entry().name());
            if (acceptPlan(r, prompt, QuickOrders.moduleJson(fm.entry().name(), schematicReply(fm)))) {
                return;
            }
        }
        Texts.say(npc.name(), m.getLocation(), r.sender(), survey.hasStructures()
                ? "Hmm, ich schaue mir an, was hier schon steht, und überlege …"
                : "Hmm, lass mich überlegen …");
        Request fr = r;
        // Recherche (Wikipedia/Wikidata) vor dem LLM: fail-open, höchstens ~10 s, dann geht es ohne Referenz weiter.
        java.util.concurrent.CompletableFuture<Optional<ReferenceLookup.Reference>> ref = research == null
                ? java.util.concurrent.CompletableFuture.completedFuture(Optional.empty())
                : research.lookup(prompt);
        ref.whenComplete((found, err) -> runSync(() -> {
            Optional<ReferenceLookup.Reference> reference = err != null || found == null ? Optional.empty() : found;
            reference.ifPresent(x -> research.report(prompt, x, settings.maxExtent(), footprint));
            reference.ifPresent(x -> Texts.say(npc.name(), npcLocation(fr.feet()), fr.sender(), "Ich habe "
                    + x.title() + " nachgeschlagen" + (x.height() > 0 ? " – " + Math.round(x.height()) + " m hoch" : "")
                    + (x.scale(settings.maxExtent(), footprint) < 1 ? ", ich baue ihn verkleinert"
                            : ", ich baue ihn 1:1") + "."));
            // Fremder Text im Prompt (Recherche, bis 400 Zeichen aus Wikipedia): Dieser Auftrag baut höchstens bis
            // „Inhalte“, auch für einen Admin (Plan B5, gegen Prompt-Injection). Vorher sah der Plan vor, Admins die
            // Recherche ganz zu verbieten – das hätte dem Hauptnutzer (Admin) die echten Maße genommen. Wer die
            // Obergrenze entfernt, lässt einen präparierten Artikel Lava, TNT oder Befehle in den Bau eines Admins legen.
            Request asked = reference.isPresent() ? fr.withCap(Tier.CONTENTS) : fr;
            String user = userMessage(prompt, survey, settings.distanceAhead(), modules, mem == null ? null
                    : mem.planJson(), mem == null ? null : mem.prompt(), mem == null ? 0
                    : java.time.Duration.between(mem.when(), java.time.Instant.now()).toMinutes(),
                    reference.map(x -> x.prompt(settings.maxExtent(), footprint)).orElse(null), rightsLine(asked))
                    + (pf != null ? PlotLayout.hint(pf.width(), pf.depth()) : "")
                    + redstoneAtSite(fr, prompt)
                    + (fileSchematics == null ? "" : fileSchematics.catalog().hint(prompt, 8));
            LOG.info(() -> String.format("llm | anfrage | besitzer=%s zeichen=%d kontext_zeichen=%d richtung=%s "
                    + "referenz=%s obergrenze=%s", fr.ownerName(), prompt.length(), user.length(), fr.facing(),
                    reference.map(ReferenceLookup.Reference::title).orElse("-"), asked.cap()));
            List<JsonObject> messages = new ArrayList<>();
            messages.add(LlmClient.message("system", systemPrompt));
            messages.add(LlmClient.message("user", user));
            ask(asked, prompt, messages, 0);
        }));
    }

    /**
     * Schnellauftrag ohne LLM: derselbe Weg wie ein LLM-Plan (Parser, Compiler mit Config-Limits, Überbau-Schutz,
     * gleiche Request samt checkAsOwner). false = verworfen, der Auftrag geht an das LLM – ein Schnellweg, der an
     * einer Prüfung scheitert, darf nie zur Absage werden, solange das LLM es noch versuchen kann.
     */
    private boolean acceptPlan(Request r, String prompt, String json) {
        BuildPlan plan;
        CompiledBuild build;
        try {
            plan = PlanParser.parse(json);
            build = compiler.compile(plan);
        } catch (PlanException e) {
            LOG.info(() -> "bau | schnellauftrag_verworfen | grund=" + e.getMessage());
            return false;
        }
        // Plot-Modus: Der Bau steht hier noch nicht an seiner Stelle (mittig auf dem Plot, startJob) – geprüft wird
        // dort, auf dem fertigen Plotplan.
        if (r.plot() == null) {
            try {
                checkOverbuild(r, prompt, build);
            } catch (PlanException e) {
                // Statt verwerfen zur Seite rücken (QuickPlacement, Vorfall Fort Knox 2026-11-04 20:22). Nicht bei
                // „bau an das Haus an“: Dort ist der Anschluss gewünscht, den nur das LLM plant.
                Optional<int[]> moved = AttachPlanner.WORDS.matcher(prompt).find() ? Optional.empty()
                        : QuickPlacement.shift(r.survey().structures(), build.min(), build.max(),
                                -settings.distanceAhead()).filter(s -> s[0] != 0 || s[1] != 0);
                if (moved.isEmpty()) {
                    LOG.info(() -> "bau | schnellauftrag_verworfen | grund=" + e.getMessage());
                    return false;
                }
                int dx = moved.get()[0];
                int dz = moved.get()[1];
                try {
                    JsonObject shifted = com.google.gson.JsonParser.parseString(translate(json, dx, dz))
                            .getAsJsonObject();
                    shifted.addProperty("reply", plan.reply());
                    json = shifted.toString();
                    plan = PlanParser.parse(json);
                    build = compiler.compile(plan);
                    // Dieselbe Prüfung auf den Zellen – der Kastenvergleich in QuickPlacement ersetzt sie nicht.
                    checkOverbuild(r, prompt, build);
                } catch (PlanException again) {
                    LOG.info(() -> "bau | schnellauftrag_verworfen | grund=auch verschoben (" + dx + "," + dz
                            + "): " + again.getMessage());
                    return false;
                }
                LOG.info(() -> "bau | schnellauftrag_verschoben | dx=" + dx + " dz=" + dz + " grund=überbau");
                Texts.say(npc.name(), npcLocation(r.feet()), r.sender(), "Da steht schon etwas – ich baue "
                        + QuickPlacement.describe(dx, dz) + ".");
            }
        }
        BuildPlan.Step step = plan.steps().getFirst();
        if (step.op() == BuildPlan.Op.SKYSCRAPER) {
            LOG.info(() -> String.format("bau | schnellauftrag | art=hochhaus stockwerke=%d fläche=%dx%d",
                    step.floors(), Math.abs(step.to().x() - step.from().x()) + 1,
                    Math.abs(step.to().z() - step.from().z()) + 1));
        } else if (step.op() != BuildPlan.Op.MODULE) {
            // Bauformen aus QuickForms (Burg, Mauer, Brücke, Garten, Baum) – sonst stünde „art=modul name=“ im Log.
            // entwurf: Bunker, Fort Knox und Taschenrechner beginnen mit fill air bzw. logic – art allein sagt da nichts.
            String design = plan.design().length() > 60 ? plan.design().substring(0, 60) : plan.design();
            LOG.info(() -> "bau | schnellauftrag | art=" + step.op().name().toLowerCase(java.util.Locale.ROOT)
                    + " block=" + step.block() + " entwurf=\"" + design + "\"");
        } else {
            LOG.info(() -> "bau | schnellauftrag | art=modul name=" + step.name());
        }
        thinking = false;
        Texts.say(npc.name(), npcLocation(r.feet()), r.sender(), Texts.clean(plan.reply(), 200));
        showWarnings(r, build);
        startJob(r, build, json, false);
        return true;
    }

    /** Antwort beim Bau einer Bauwerk-Datei: was, woher, wie viele Varianten es gibt. */
    static String schematicReply(com.blackn0va.baumeister.plan.SchematicCatalog.Match m) {
        com.blackn0va.baumeister.plan.SchematicCatalog.Entry e = m.entry();
        String more = m.variants() > 1 ? " Es gibt " + m.variants() + " Varianten – sag z. B. „" + e.family()
                + " Variante 2“." : "";
        String from = e.source().isEmpty() ? "" : " (Quelle: " + e.source() + ")";
        return Texts.clean("Ich baue „" + e.display() + "“ aus der Schematic-Sammlung" + from + "." + more, 200);
    }

    private Set<String> moduleNames() {
        Set<String> names = new HashSet<>();
        for (ModuleLibrary.Module mod : modules.all()) {
            names.add(mod.name());
        }
        return names;
    }

    /**
     * Auftrag plus Lage am Bauplatz plus verfügbare Bauteile plus die aus dem Auftrag gelesenen Fakten. Steht im
     * User-Teil, nicht im System-Prompt: Der System-Prompt bleibt so über alle Aufträge gleich, und llama-server
     * rechnet ihn aus dem Cache statt jedes Mal neu (~1700 Token). Statisch, damit LlmProbe genau diese Nachricht
     * schickt und nicht eine eigene, die vom Plugin abweicht.
     */
    /**
     * Vorhandenes Redstone am Bauplatz, nur bei Maschinen-Aufträgen (PlanPrompt.wantsMachineDetails – ein Haus braucht
     * die Schaltung nebenan nicht und spart die Token). Vorher stand dort nur „r“ im Grundriss, und „bau eine Lampe an
     * meine Schaltung“ traf den Eingang des Verstärkers nur zufällig. Läuft im Hauptthread (Blöcke lesen).
     */
    private String redstoneAtSite(Request r, String prompt) {
        List<String> names = modules.all().stream().map(ModuleLibrary.Module::name).toList();
        if (r.frame() == null || r.survey() == null || !PlanPrompt.wantsMachineDetails(prompt, names)) {
            return "";
        }
        long t0 = System.nanoTime();
        String text = RedstoneScan.atSite(r.frame(), r.survey());
        if (!text.isEmpty()) {
            LOG.info(() -> "llm | redstone_am_bauplatz | zeichen=" + text.length() + " ms="
                    + (System.nanoTime() - t0) / 1_000_000);
        }
        return text;
    }

    static String userMessage(String prompt, SiteSurvey survey, int ahead, ModuleLibrary modules) {
        return userMessage(prompt, survey, ahead, modules, null, null, 0);
    }

    /** Mit Gedächtnis: previousPlan/previousPrompt des letzten Baus dieses Spielers (oder null). */
    static String userMessage(String prompt, SiteSurvey survey, int ahead, ModuleLibrary modules, String previousPlan,
            String previousPrompt, long minutesAgo) {
        return userMessage(prompt, survey, ahead, modules, previousPlan, previousPrompt, minutesAgo, null);
    }

    /** reference: Recherche-Ergebnis (ReferenceLookup.Reference.prompt) oder null – steht direkt unter dem Auftrag. */
    static String userMessage(String prompt, SiteSurvey survey, int ahead, ModuleLibrary modules, String previousPlan,
            String previousPrompt, long minutesAgo, String reference) {
        return userMessage(prompt, survey, ahead, modules, previousPlan, previousPrompt, minutesAgo, reference, null);
    }

    /** rights: Zeile „Rechte: …“ (PlanPrompt.rightsLine) oder null. */
    static String userMessage(String prompt, SiteSurvey survey, int ahead, ModuleLibrary modules, String previousPlan,
            String previousPrompt, long minutesAgo, String reference, String rights) {
        // Seit 2026-10-31 in der Plan-Logik (PlanPrompt), damit die Client-Mod dieselbe Nachricht schickt.
        return PlanPrompt.userMessage(prompt, survey, ahead, modules, previousPlan, previousPrompt, minutesAgo,
                reference, rights);
    }

    /**
     * Rechte-Zeile fürs LLM (Plan B5): was der Auftraggeber in diesem Moment darf, gedeckelt durch die Obergrenze des
     * Auftrags (Recherche, fortgeschriebener Plan). Nur Hinweis ans Modell – gesetzt wird nach der Prüfung beim Bauen.
     */
    static String rightsLine(Request r) {
        BuildRights effective = BuildRights.ofSender(r.sender(), r.feet().getWorld()).capped(r.cap());
        return PlanPrompt.rightsLine(effective.allows(Tier.CONTENTS), effective.allows(Tier.DANGEROUS),
                effective.allows(Tier.COMMANDS));
    }

    private void showWarnings(Request r, CompiledBuild build) {
        if (!build.warnings().isEmpty()) {
            List<String> w = build.warnings();
            String shown = String.join("; ", w.subList(0, Math.min(3, w.size())));
            Texts.info(r.sender(), "Hinweis: " + shown + (w.size() > 3 ? " (+" + (w.size() - 3) + ")" : ""));
        }
    }

    /** Rechts neben dem Spieler – dort wartet der NPC, solange er nachdenkt. */
    private static Vector sideOf(Cardinal facing) {
        Orientation o = new Orientation(facing);
        Pos p = o.toWorldOffset(new Pos(1, 0, 0));
        return new Vector(p.x(), 0, p.z());
    }

    private void ask(Request r, String prompt, List<JsonObject> messages, int attempt) {
        llm.complete(messages).whenComplete((reply, err) -> {
            if (reply != null && settings.savePlans()) {
                savePlan(r.ownerName(), prompt, attempt, reply);
            }
            runSync(() -> handleReply(r, prompt, messages, attempt, reply, err));
        });
    }

    private void handleReply(Request r, String prompt, List<JsonObject> messages, int attempt,
            LlmClient.Reply reply, Throwable err) {
        Location npcLoc = npcLocation(r.feet());
        if (err != null && attempt > 0 && messages.size() >= 2) {
            // Korrekturrunde gescheitert (dev01 2026-11-04 18:04: „Prompt zu lang“, 8 243 Token bei 8 192 Kontext –
            // die erste Antwort hatte den Kontext schon gefüllt). Vorher war damit der ganze Auftrag verloren. Jetzt
            // baut der Baumeister den letzten Plan ohne weitere Runde; Baufehler gelten dann wie nach der letzten
            // Runde (lintMode: reparieren baut, strikt verwirft).
            JsonObject last = messages.get(messages.size() - 2);
            if (last.has("role") && "assistant".equals(last.get("role").getAsString()) && last.has("content")) {
                String previous = last.get("content").getAsString();
                LOG.log(Level.WARNING, "llm | reparatur_fehlgeschlagen | besitzer=" + r.ownerName() + " versuch="
                        + attempt + " – baue den letzten Plan", err);
                handleReply(r, prompt, messages.subList(0, messages.size() - 2), Math.max(attempt,
                        settings.repairAttempts()), new LlmClient.Reply(previous, 0, 0, 0), null);
                return;
            }
        }
        if (err != null) {
            thinking = false;
            npc.status("bereit");
            LOG.log(Level.WARNING, "llm | fehler | besitzer=" + r.ownerName() + " versuch=" + attempt, err);
            if (templateFallback(r, prompt, "das Modell ist gerade nicht erreichbar")) {
                return;
            }
            Texts.say(npc.name(), npcLoc, r.sender(), LlmClient.describe(err));
            return;
        }
        LOG.info(() -> String.format("llm | antwort | besitzer=%s versuch=%d dauer_ms=%d tokens=%d tps=%.1f",
                r.ownerName(), attempt, reply.millis(), reply.tokens(), reply.tokensPerSecond()));
        RequestHints.Hints hints = RequestHints.parse(prompt, moduleNames());
        PlotLayout.Frame pf = plotFrame(r);
        int footprint = plotFootprint(pf);
        int targetHeight = research == null ? 0
                : research.cached(prompt).map(x -> x.targetHeight(settings.maxExtent(), footprint)).orElse(0);
        // Die Prüfkette (Auftrag, Anbau-Ausrichtung, PlanLinter, Überbau-Schutz) steht in PlanReview, damit sie ohne
        // Server testbar ist; hier wird nur ausgeführt. Ein sauberer Plan kostet keinen weiteren LLM-Aufruf.
        // Plot-Modus: Überbau erst auf dem fertigen Plotplan (startJob), hier aber die Grundfläche gegen den Plot.
        PlanReview.Verdict verdict = new PlanReview(compiler, modules, new PlanReview.Context(prompt, hints,
                r.survey(), r.memory() != null, pf != null ? Integer.MAX_VALUE : settings.maxOverbuild(),
                settings.repairAttempts(), settings.lintMode(), targetHeight, pf == null ? 0 : pf.width(),
                pf == null ? 0 : pf.depth()))
                .judge(reply.content(), attempt);
        if (verdict instanceof PlanReview.Repair rep) {
            LOG.info(() -> "llm | reparatur | grund=" + rep.reason());
            Texts.say(npc.name(), npcLoc, r.sender(), rep.playerText());
            List<JsonObject> next = com.blackn0va.baumeister.plan.PlanPrompt.repairConversation(messages, prompt,
                    moduleNames(), rep.assistant(), rep.llmRequest());
            ask(r, prompt, next, attempt + 1);
            return;
        }
        if (verdict instanceof PlanReview.GiveUp g) {
            // siteCheck: Überbau und fehlender Anschluss hängen am Bauplatz, nicht am Entwurf – dort hilft kein
            // Ersatz-Hochhaus, das ja ebenso über das Nachbarhaus fiele oder frei stünde.
            if (hints.highRise() && !g.siteCheck() && fallBack(r, prompt, hints, new PlanException(g.reason()))) {
                return;
            }
            if (templateFallback(r, prompt, "die KI hat keinen brauchbaren Plan geschafft")) {
                return;
            }
            thinking = false;
            npc.status("bereit");
            LOG.info(() -> String.format("llm | plan_verworfen | besitzer=%s grund=%s", r.ownerName(), g.reason()));
            Texts.say(npc.name(), npcLoc, r.sender(), "Ich bekomme keinen brauchbaren Plan hin: " + g.playerText());
            reportResult(r, "verworfen", g.playerText(), 0, List.of(), attempt + 1);
            return;
        }
        PlanReview.Accept ok = (PlanReview.Accept) verdict;
        BuildPlan plan = ok.plan();
        CompiledBuild build = ok.build();
        thinking = false;
        String answer = Texts.clean(plan.reply(), 200);
        Texts.say(npc.name(), npcLoc, r.sender(), answer.isEmpty() ? "Ich fange an!" : answer);
        showWarnings(r, build);
        if (!ok.repairs().isEmpty()) {
            // Der Spieler soll wissen, warum vielleicht etwas anders aussieht (abgeschnittene Antwort, Grundriss
            // geklemmt, Pfeiler ergänzt …). Vorher „die Antwort des Modells war fehlerhaft“ – seit PlanAutoFix
            // (2026-10-31) bessert der Baumeister auch Entwurfsfehler selbst nach, nicht nur kaputtes JSON.
            Texts.info(r.sender(), "Ich habe den Plan selbst nachgebessert: "
                    + Texts.clean(String.join(", ", ok.repairs()), 200) + ".");
        }
        pendingFixes = ok.repairs();
        pendingRounds = attempt + 1;
        boolean update = plan.isUpdate() && r.memory() != null;
        if (plan.isUpdate() && r.memory() == null) {
            LOG.info(() -> "llm | update_ohne_gedächtnis | besitzer=" + r.ownerName() + " – als Neubau behandelt");
        }
        startJob(r, build, ok.json(), update);
    }

    /**
     * Zwischenschicht als Rückfall (TemplateCatalog locker): Liefert das LLM nichts, baut der Baumeister die passende
     * Minecraft-Vorlage („ein Haus mit Pool“ → Dorfhaus im Stil des Bioms). Vorher endete das mit „Ich bekomme keinen
     * brauchbaren Plan hin“ (dev01 2026-11-04 14:15). Nur bei neuen eigenständigen Bauten (OrderIntent): Ein
     * gescheiterter Anbau oder Umbau würde sonst zu einem Dorfhaus daneben. Prüfungen wie jeder Schnellauftrag
     * (acceptPlan: Compiler, Überbau-Schutz, Rechte in startJob).
     */
    private boolean templateFallback(Request r, String prompt, String why) {
        if (!OrderIntent.isNewStructure(prompt) || r.memory() != null) {
            return false;
        }
        Optional<TemplateCatalog.Match> m = TemplateCatalog.match(prompt, templates, biomeAt(r.feet()),
                settings.maxExtent(), System.nanoTime(), false);
        if (m.isEmpty()) {
            return false;
        }
        String what = m.get().what();
        LOG.info(() -> "bau | vorlage | weg=rückfall grund=" + why + " " + what);
        Texts.info(r.sender(), "Hinweis: " + why + " – ich baue eine passende Minecraft-Vorlage.");
        return acceptPlan(r, prompt, m.get().json());
    }

    /** Stil der Dorfvorlagen nach dem Biom unter dem Spieler; unlesbar → Wiesen. */
    private static TemplateCatalog.Biome biomeAt(Location feet) {
        try {
            return TemplateCatalog.biomeOf(feet.getBlock().getBiome().getKey().toString());
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "vorlagen | biom_unlesbar", e);
            return TemplateCatalog.Biome.PLAINS;
        }
    }

    /**
     * Deterministischer Rückfall nach dem letzten gescheiterten Versuch, nur bei Hochhäusern: der 16×16-skyscraper
     * aus RequestHints.defaultPlan. Er läuft durch compile (Config-Limits) UND den Überbau-Schutz; scheitert eine
     * Prüfung, bleibt es bei der alten Absage. Die Rechteprüfung steckt unverändert in startJob. Vorher bekam der
     * Spieler nach drei Fehlversuchen nur „kein brauchbarer Plan“, obwohl der Bauweg für Hochhäuser feststeht.
     *
     * @return true, wenn der Ersatzbau gestartet ist
     */
    private boolean fallBack(Request r, String prompt, RequestHints.Hints hints, PlanException cause) {
        BuildPlan plan;
        CompiledBuild build;
        try {
            plan = RequestHints.defaultPlan(hints);
            build = compiler.compile(plan);
            if (r.plot() == null) {  // Plot-Modus: geprüft wird in startJob auf dem fertigen Plotplan
                checkOverbuild(r, prompt, build);
            }
        } catch (PlanException e) {
            LOG.info(() -> String.format("llm | rückfall_verworfen | besitzer=%s grund=%s", r.ownerName(),
                    e.getMessage()));
            return false;
        }
        BuildPlan.Step step = plan.steps().getFirst();
        LOG.info(() -> String.format("llm | rückfall | besitzer=%s art=hochhaus stockwerke=%d llm_grund=%s",
                r.ownerName(), step.floors(), cause.getMessage()));
        thinking = false;
        Texts.say(npc.name(), npcLocation(r.feet()), r.sender(), Texts.clean(plan.reply(), 200));
        showWarnings(r, build);
        startJob(r, build);
        return true;
    }

    /**
     * Anbau statt Durchbau: Verändert der Plan mehr als max-überbauen Blöcke eines bestehenden
     * Bauwerks, geht er mit der Stelle zurück an das LLM (Reparaturrunde) – außer der Auftrag
     * sagt ausdrücklich „umbauen/abreißen/ersetzen“. Ein Durchgang (2) oder Fenster (1) bleibt erlaubt.
     */
    private void checkOverbuild(Request r, String prompt, CompiledBuild build) throws PlanException {
        // Wortgleich nach PlanReview.checkOverbuild gezogen, damit LLM-Pläne (PlanReview), Schnellaufträge und der
        // Rückfall durch dieselbe, testbare Prüfung gehen.
        PlanReview.checkOverbuild(r.survey(), prompt, build, settings.maxOverbuild());
    }

    private void startJob(Request r, CompiledBuild build) {
        startJob(r, build, null, false);
    }

    /**
     * planJson != null: Plan ins Gedächtnis des Spielers (Folgeaufträge). update: zuerst wegräumen, was der letzte Bau
     * gesetzt hat und der neue Plan nicht mehr enthält – von oben nach unten, nur wo noch dasselbe Material steht.
     */
    private void startJob(Request r, CompiledBuild planned, String plannedJson, boolean update) {
        // Stadt- oder Weltbau ist während der KI-Planung gestartet (das dürfen sie seit 2026-11-01): Plan vormerken
        // statt verwerfen, samt Nachbesserungs-Merker, und bauen, sobald die Baustelle frei ist.
        String conflict = otherBuildConflict();
        if (conflict != null) {
            List<String> keptFixes = pendingFixes;
            int keptRounds = pendingRounds;
            pendingFixes = List.of();
            pendingRounds = 0;
            boolean queued = deferred.offer(r.ownerName(), () -> {
                pendingFixes = keptFixes;
                pendingRounds = keptRounds;
                startJob(r, planned, plannedJson, update);
            });
            if (!queued) {
                Texts.info(r.sender(), conflict + " Ein anderer fertiger Plan wartet schon – dieser fällt weg.");
                return;
            }
            npc.status("wartet auf den Stadtbau");
            Texts.say(npc.name(), npcLocation(r.feet()), r.sender(),
                    "Mein Plan ist fertig – ich baue ihn, sobald der Stadt- bzw. Weltbau fertig ist („stopp“ verwirft ihn).");
            LOG.info(() -> "bau | vorgemerkt | besitzer=" + r.ownerName() + " grund=stadt_oder_weltbau");
            startDeferredTimer();
            return;
        }
        // Sofort übernehmen und löschen: Bricht dieser Aufruf vor dem Bau ab (zu tief, Plot belegt), hinge der Merker
        // sonst am nächsten Schnellauftrag. Geht der Plan in die Vorschau, trägt ComfortService.Planned ihn mit, und
        // startConfirmed setzt ihn nach „ja“ zurück (seit 2026-11-01; vorher meldete die App dann nichts).
        List<String> fixes = pendingFixes;
        int rounds = pendingRounds;
        pendingFixes = List.of();
        pendingRounds = 0;
        PlotLayout.Frame pf = plotFrame(r);
        Placed placed = pf != null && plannedJson != null ? onPlot(r, pf, planned, plannedJson)
                : new Placed(planned, plannedJson);
        if (placed == null) {
            return;
        }
        CompiledBuild build = placed.build();
        String planJson = placed.json();
        World world = r.feet().getWorld();
        Orientation o = new Orientation(r.facing());
        // Ohne Nachbarbauten mittig vor den Spieler, Vorderkante bei z=0. Mit Nachbarbauten keine
        // Verschiebung – das LLM hat dann in den Koordinaten des Scans geplant (SiteFrame).
        // survey == null: Direktbau eines Bauteils an fester Ecke (requestModuleAt) – nie verschieben.
        boolean free = r.survey() != null && !r.survey().hasStructures() && pf == null;
        int shiftX = free ? -Math.floorDiv(build.min().x() + build.max().x(), 2) : 0;
        int shiftZ = free ? -build.min().z() : 0;
        int by = r.feet().getBlockY();
        // Weltboden: Unten bleibt die unterste Schicht (Grundgestein) unberührt. Vorher wurden Zellen unter
        // getMinHeight still übersprungen (invalid) – seit es Bunker gibt (2026-10-30, Fort Knox 14 tief), hätte ein
        // Bau in einer flachen Welt (Boden −61) das Grundgestein bei −64 durch Luft ersetzt und ein Loch ins Nichts
        // gerissen, ohne Tresor. Wer das entfernt, bringt das Loch zurück.
        if (by + build.min().y() <= world.getMinHeight()) {
            int room = by - world.getMinHeight() - 1;
            Texts.say(npc.name(), npcLocation(r.feet()), r.sender(), "Hier geht das nicht so tief: Der Bau reicht "
                    + -build.min().y() + " Blöcke unter deine Füße, bis zum Grundgestein sind es nur " + Math.max(0,
                            room) + ". Such dir eine Stelle mit mehr Boden darunter.");
            LOG.info(() -> String.format("bau | zu_tief | besitzer=%s welt=%s y=%d tiefe=%d platz=%d", r.ownerName(),
                    world.getName(), by, -build.min().y(), room));
            npc.status("bereit");
            reportResult(r, "abgesagt", "zu tief: " + -build.min().y() + " Blöcke unter den Füßen, bis zum "
                    + "Grundgestein " + Math.max(0, room), 0);
            return;
        }
        // Vorschau statt Bau: Plan und Verschiebung stehen fest, gebaut wird erst nach „ja“ (ComfortFlow.divert).
        if (comfort.divert(r, build, planJson, update, shiftX, shiftZ, fixes, rounds)) {
            return;
        }
        // Befehle aus Plantext (Modell, bauenbei) nur nach Bestätigung genau dieser Liste durch den Auftraggeber (Plan
        // B5). Bestätigt ist nur die Fassung mit derselben Prüfsumme – hat sich der Plan seit dem Klick geändert, wird
        // neu gefragt, nie die alte Zusage übertragen.
        // Bestätigbar sind nur reine Impuls- und Ketten-Befehlsblöcke ohne Strom daneben – im Plan (PlanCommands.find)
        // und in der Welt, hier bei jedem Aufruf neu gelesen, also beim Fragen UND beim Bauen (Sicherheitsprüfung
        // 2026-11-08, H1/M3). Alles andere der Stufe Befehle aus Plantext fällt immer weg.
        List<PlanCommands.Found> planCommands = PlanCommands.find(build);
        Set<Pos> confirmableAt = new java.util.HashSet<>();
        List<String> refusedLines = new ArrayList<>();
        for (PlanCommands.Found f : planCommands) {
            Block at = r.frame().blockAt(f.pos().add(shiftX, 0, shiftZ));
            String why = f.refusal() != null ? f.refusal() : at.isBlockIndirectlyPowered()
                    ? "dort liegt in der Welt schon Strom an – liefe beim Setzen von selbst los" : null;
            if (why == null) {
                confirmableAt.add(f.pos());
            } else {
                refusedLines.add(f.block() + " bei " + at.getX() + " " + at.getY() + " " + at.getZ() + ": " + why);
            }
        }
        String commandsFingerprint = PlanCommands.fingerprint(build);
        boolean commandsAllowed = !confirmableAt.isEmpty()
                && r.consent().kind() == CommandConsent.Kind.CONFIRMED
                && commandsFingerprint.equals(r.consent().fingerprint()) && mayConfirmCommands(r);
        if (!confirmableAt.isEmpty() && !commandsAllowed && r.consent().kind() != CommandConsent.Kind.WITHOUT
                && offerCommands(r, planned, plannedJson, update, fixes, rounds, commandsFingerprint,
                        commandLines(r, planCommands, confirmableAt, shiftX, shiftZ), refusedLines)) {
            return;
        }

        List<WorldAction> actions = new ArrayList<>(build.actions().size());
        int invalid = 0;
        List<String> invalidSamples = new ArrayList<>();
        int heldBack = 0;
        double sx = 0;
        double sz = 0;
        int n = 0;
        BlockTiers.WorldFacts facts = new BlockTiers.WorldFacts(world.isBedWorks(), world.isRespawnAnchorWorks());
        int fx = shiftX;
        int fz = shiftZ;
        for (Action a : build.actions()) {
            List<WorldChange> changes = new ArrayList<>(a.changes().size());
            boolean dropAction = false;
            for (Change c : a.changes()) {
                // Unbestätigte Befehle aus Plantext gar nicht setzen, mit Hinweis. Ein Paar fällt dann ganz weg.
                if (PlanCommands.needsConfirmation(c.block())
                        && !(commandsAllowed && confirmableAt.contains(c.pos()))) {
                    heldBack += a.changes().size();
                    dropAction = true;
                    break;
                }
                // „Immer aktiv“ aus Plantext wird zu „braucht Redstone“, auch bestätigt (PlanCommands.withoutAutoStart).
                BlockSpec spec = PlanCommands.withoutAutoStart(c.block());
                Pos local = c.pos().add(shiftX, 0, shiftZ);
                int y = by + local.y();
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    invalid++;
                    continue;
                }
                Converted conv = toBlockData(spec, o);
                if (conv.data() == null) {
                    invalid++;
                    if (invalidSamples.size() < 3) {
                        invalidSamples.add(conv.problem());
                    }
                    continue;
                }
                Block b = r.frame().blockAt(local);
                changes.add(new WorldChange(b, conv.data(), placement(spec, c.group(), facts, o,
                        q -> r.frame().blockAt(q.add(fx, 0, fz)))));
                sx += b.getX();
                sz += b.getZ();
                n++;
            }
            if (!dropAction && !changes.isEmpty()) {
                actions.add(new WorldAction(a.step(), List.copyOf(changes)));
            }
        }
        if (invalid > 0) {
            // Kein stilles Verwerfen mehr (Plan B3): welche Zustände nicht gingen, steht dabei.
            Texts.info(r.sender(), invalid + " Blöcke ließen sich nicht umsetzen und fallen weg"
                    + (invalidSamples.isEmpty() ? "." : ": " + String.join("; ", invalidSamples) + "."));
        }
        if (heldBack > 0) {
            String why = r.consent().kind() == CommandConsent.Kind.WITHOUT ? "wie gewünscht ohne Befehle"
                    : r.cap() != Tier.COMMANDS ? "im Auftrag stand fremder Text (Recherche), Befehle daraus setze ich nie"
                            : "Befehle aus einem Bauplan setze ich nur, wenn ein Admin sie bestätigt";
            Texts.info(r.sender(), "Hinweis: " + heldBack + " Blöcke mit Befehlen aus dem Bauplan lasse ich aus – "
                    + why + ".");
            int held = heldBack;
            LOG.info(() -> "bau | befehle_zurückgehalten | besitzer=" + r.ownerName() + " blöcke=" + held
                    + " grund=" + r.consent().kind() + "/" + r.cap());
        }
        if (actions.isEmpty()) {
            npc.status("bereit");
            Texts.info(r.sender(), "Nach dem Umrechnen blieb kein Block übrig.");
            return;
        }
        List<String> stepNames = build.stepNames();
        Map<Long, org.bukkit.Material> placedCells = new java.util.HashMap<>();
        for (WorldAction a : actions) {
            for (WorldChange c : a.changes()) {
                if (!c.data().getMaterial().isAir()) {
                    Block b = c.block();
                    placedCells.put(pack(b.getX(), b.getY(), b.getZ()), c.data().getMaterial());
                }
            }
        }
        boolean keptOld = false;
        String mergedMemory = null;
        if (update) {
            List<Block> gone = new ArrayList<>();
            for (Map.Entry<Long, org.bukkit.Material> e : r.memory().cells().entrySet()) {
                if (placedCells.containsKey(e.getKey())) {
                    continue;
                }
                long k = e.getKey();
                Block b = world.getBlockAt(unpackX(k), unpackY(k), unpackZ(k));
                if (b.getType() == e.getValue()) {
                    gone.add(b);
                }
            }
            if (com.blackn0va.baumeister.plan.UpdateGuard.keepOld(r.prompt(), r.memory().cells().size(), gone.size())) {
                // Anbau statt Ersatz (UpdateGuard): Das Modell hat den alten Bau gekürzt, der Spieler wollte nur etwas
                // dazu. Alter Bau bleibt, Gedächtnis kennt danach beide Teile.
                int kept = gone.size();
                LOG.info(() -> String.format("bau | änderung_als_anbau | besitzer=%s nicht_weggeräumt=%d alt=%d",
                        r.ownerName(), kept, r.memory().cells().size()));
                for (Block b : gone) {
                    placedCells.put(pack(b.getX(), b.getY(), b.getZ()), b.getType());
                }
                gone.clear();
                keptOld = true;
                if (planJson != null) {
                    try {
                        // Alter Plan steht schon im Rahmen, nur der neue wird verschoben (wie unten).
                        mergedMemory = com.blackn0va.baumeister.plan.UpdateGuard.merge(r.memory().planJson(),
                                translate(planJson, shiftX, shiftZ));
                    } catch (RuntimeException e) {
                        // Gedächtnis ist Komfort (wie unten): ohne Vereinigung kennt es eben nur den neuen Teil.
                        LOG.log(Level.WARNING, "bau | gedächtnis_vereinigen_fehlgeschlagen | besitzer="
                                + r.ownerName(), e);
                    }
                }
            }
            gone.sort(java.util.Comparator.comparingInt(Block::getY).reversed());
            if (!gone.isEmpty()) {
                List<String> names = new ArrayList<>(stepNames);
                names.add("Altes wegräumen");
                int removeStep = names.size() - 1;
                List<WorldAction> withRemoval = new ArrayList<>();
                BlockData airData = org.bukkit.Bukkit.createBlockData(org.bukkit.Material.AIR);
                for (Block b : gone) {
                    withRemoval.add(new WorldAction(removeStep, List.of(new WorldChange(b, airData))));
                }
                withRemoval.addAll(actions);
                actions = withRemoval;
                stepNames = names;
            }
            int removed = gone.size();
            LOG.info(() -> String.format("bau | änderung | besitzer=%s weggeräumt=%d", r.ownerName(), removed));
        }
        if (planJson != null && r.ownerId() != null && r.frame() != null) {
            try {
                memory.put(r.ownerId(), new Memory(r.frame(), r.feet(), r.facing(),
                        mergedMemory != null ? mergedMemory : translate(planJson, shiftX, shiftZ), placedCells, java.time.Instant.now(), HelperWish.strip(r.prompt()),
                        r.cap()));
            } catch (RuntimeException e) {
                // Gedächtnis ist Komfort: Ein unlesbarer Plan (sollte nach dem Parser nicht vorkommen) darf den Bau
                // nicht verhindern – bewusst fail-open, der nächste Auftrag beginnt dann eben frisch.
                LOG.log(Level.WARNING, "bau | gedächtnis_fehlgeschlagen | besitzer=" + r.ownerName(), e);
            }
        }
        Location center = new Location(world, sx / n + 0.5, by, sz / n + 0.5);
        Pos f = o.toWorldOffset(new Pos(0, 0, -1));
        Vector front = new Vector(f.x(), 0, f.z());
        UUID checkAs = r.checkAsOwner() ? r.ownerId() : null;
        // Stufen (Plan B1) gelten für den, der den Auftrag gegeben hat – beim Setzen gelesen. Konsole: null = alle.
        UUID rightsOf = r.sender() instanceof Player rp ? rp.getUniqueId() : null;
        int blocks = build.blockCount();
        java.util.OptionalInt asked = HelperWish.fromPrompt(r.prompt());
        Integer standing = helperWish.get(r.ownerId() != null ? r.ownerId() : CONSOLE);
        int helpers = HelperWish.choose(asked, standing, blocks, settings.secondHelperFrom(),
                settings.thirdHelperFrom());
        activeOrder = orderText(r);
        // Anbau (UpdateGuard): eigener Baubuch-Eintrag, der alte Bau behält seinen – „reparieren“ kennt beide.
        activeKind = update && !keptOld ? LedgerKind.UPDATE : LedgerKind.NEW;
        activeLedgerOrder = HelperWish.strip(r.prompt());
        activeLedgerInfo = buildInfo(plannedJson);
        activePlot = r.plot() == null ? "" : r.plot().id();
        // Nur der LLM-Weg setzt pending*; Schnellaufträge und Rückfall starten ohne – dann leer/0.
        activeFixes = fixes;
        activeRounds = rounds;
        active = new BuildJob(plugin, npc, settings, r.ownerId(), r.ownerName(), checkAs, rightsOf, r.sender(),
                List.copyOf(actions), stepNames, center, front, by, helpers, asked.isPresent() || standing != null,
                this::onJobFinished).cap(r.cap());
        activeGuides = FarmGuide.inPlan(plannedJson);
        guidedJob = activeGuides.isEmpty() ? null : active;
        guideTo = r.sender();
        LOG.info(() -> String.format("bau | start | besitzer=%s aktionen=%d blöcke=%d welt=%s x=%d y=%d z=%d "
                + "verschoben=%s", r.ownerName(), build.actions().size(), blocks, world.getName(),
                r.feet().getBlockX(), by, r.feet().getBlockZ(), free));
        active.start();
    }

    /**
     * Plot-Modus: Hauptbau mittig auf den Plot, Gestaltung drumherum (PlotLayout), dann Überbau-Schutz auf dem ganzen
     * Plotplan – der Plot gehört dem Spieler, aber was dort schon steht, bleibt geschützt wie überall („ersetzen“ im
     * Auftrag hebt das auf). null = passt nicht, der Spieler hat Bescheid.
     */
    private Placed onPlot(Request r, PlotLayout.Frame pf, CompiledBuild build, String json) {
        String id = r.plot().id();
        Optional<String> tooBig = PlotLayout.problem(build, pf.width(), pf.depth());
        String why = null;
        Placed placed = null;
        if (tooBig.isPresent()) {
            why = "Das passt nicht auf Plot " + id + " (" + pf.width() + " × " + pf.depth() + "): Der Bau ist "
                    + (build.max().x() - build.min().x() + 1) + " × " + (build.max().z() - build.min().z() + 1)
                    + " groß.";
            LOG.info(() -> "plot | zu_gross | id=" + id + " grund=" + tooBig.get());
        } else {
            int[] d = PlotLayout.centerShift(build, pf.width(), pf.depth());
            String combined = PlotLayout.combine(translate(json, d[0], d[1]), PlotLayout.landscape(pf.width(),
                    pf.depth(), build.min().x() + d[0], build.min().z() + d[1], build.max().x() + d[0],
                    build.max().z() + d[1]));
            try {
                CompiledBuild all = compiler.compile(PlanParser.parse(combined, PlotLayout.MAX_STEPS));
                checkOverbuild(r, r.prompt(), all);
                placed = new Placed(all, combined);
                LOG.info(() -> String.format("plot | gestaltet | id=%s breite=%d tiefe=%d verschoben=%d/%d "
                        + "aktionen=%d", id, pf.width(), pf.depth(), d[0], d[1], all.actions().size()));
            } catch (PlanException e) {
                LOG.info(() -> "plot | verworfen | id=" + id + " grund=" + e.getMessage());
                why = e.getMessage().contains("existing structure")
                        ? "Auf Plot " + id + " steht schon etwas, das ich überbauen würde. Sag „ersetzen“ dazu, dann "
                                + "baue ich darüber."
                        : "Auf Plot " + id + " geht das nicht: " + e.getMessage();
            }
        }
        if (placed == null) {
            Texts.say(npc.name(), npcLocation(r.feet()), r.sender(), why);
            npc.status("bereit");
            reportResult(r, "abgesagt", why, 0);
        }
        return placed;
    }

    /** Umgerechneter Block: data oder (bei ungültigem Zustand) problem. */
    record Converted(BlockData data, String problem) {
    }

    /** Drehung je Blickrichtung (Cardinal.ordinal) – auch für den Selbsttest (BlockSelfTest.expected). */
    static final StructureRotation[] TURNS = {StructureRotation.NONE, StructureRotation.CLOCKWISE_90,
        StructureRotation.CLOCKWISE_180, StructureRotation.COUNTERCLOCKWISE_90};

    /**
     * Lokaler Block → Weltzustand (Plan B3). Lokale Namen in den Nordrahmen, dann dreht und spiegelt Minecraft selbst:
     * BlockData.mirror (offene Spiegelung eines Bauteils) und BlockData.rotate (Bauteil-Drehung plus Blickrichtung) –
     * nach den Vanilla-Regeln für jeden Zustand (Schilddrehung, Zaunverbindungen, Ranken, Werkbank-Ausrichtung …).
     * Vorher drehte Orientation nur facing, axis und Schienen. Zustände fallen nie mehr still weg (StateFallback).
     */
    static Converted toBlockData(BlockSpec spec, Orientation o) {
        BlockSpec nf = Orientation.northFrame(spec);
        BlockData data = StateFallback.create(nf.props(), nf.explicit(), () -> propertyNames(nf.id()),
                props -> tryCreate(nf.id(), props));
        if (data == null) {
            return new Converted(null, "Zustand ungültig: " + Texts.clean(spec.asString().substring("minecraft:"
                    .length()), 80));
        }
        BlockSpec.Pending p = spec.pending();
        if (p != null && p.mirror()) {
            data.mirror(Mirror.FRONT_BACK);
        }
        int turns = Math.floorMod((p == null ? 0 : p.turns()) + o.forward().ordinal(), 4);
        if (turns != 0) {
            data.rotate(TURNS[turns]);
        }
        return new Converted(data, null);
    }

    private static BlockData tryCreate(String id, Map<String, String> props) {
        try {
            return Bukkit.createBlockData(new BlockSpec(id, props).asString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Welche Zustände der Block überhaupt hat (aus seinem Grundzustand). */
    private static Set<String> propertyNames(String id) {
        Material m = Material.matchMaterial(id);
        if (m == null || !m.isBlock()) {
            return Set.of();
        }
        String s = Bukkit.createBlockData(m).getAsString();
        int open = s.indexOf('[');
        if (open < 0) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String pair : s.substring(open + 1, s.length() - 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.add(pair.substring(0, eq));
            }
        }
        return out;
    }

    /**
     * Was BuildJob beim Setzen braucht: Stufe (BlockTiers, mit den Bett-/Anker-Regeln dieser Welt), ausdrücklich
     * genannte Zustände in Welt-Namen, Blockdaten neu geschrieben mit Platzhaltern in Weltkoordinaten (toWorldBlock:
     * Planposition → Weltblock, dieselbe Verschiebung wie die Zellen), und das Bauteil.
     */
    static Placement placement(BlockSpec spec, int group, BlockTiers.WorldFacts facts, Orientation o,
            java.util.function.Function<Pos, Block> toWorldBlock) {
        BlockTiers.Verdict v = BlockTiers.place(spec, facts);
        Set<String> explicit = o.worldExplicit(spec);
        if (spec.nbt() == null) {
            return new Placement(v.tier(), v.reason(), explicit, null, BlockDataWriter.Expectation.NONE, group);
        }
        Nbt.Compound data = spec.nbt();
        if (data.get("Command") instanceof Nbt.Str cmd) {
            data = data.with("Command", new Nbt.Str(CommandText.render(cmd.value(), q -> {
                Block w = toWorldBlock.apply(q);
                return new int[] {w.getX(), w.getY(), w.getZ()};
            })));
        }
        return new Placement(v.tier(), v.reason(), explicit, Snbt.write(data),
                BlockDataWriter.Expectation.of(spec.id(), data), group);
    }

    /** Rückgängig-Eintrag von außen (Stadtbau, CityJob) – dieselbe Liste wie für normale Bauten. */
    void pushUndo(UndoRecord rec) {
        if (rec.empty()) {
            if (rec.spool() != null) {
                rec.spool().delete();
            }
            return;
        }
        undo.push(rec);
        comfort.persistUndo(rec);
        trimUndo();
    }

    /**
     * Geladene Einträge aus einem früheren Lauf (ComfortService.adoptLoaded) hinten an den Stapel – sie sind älter als
     * alles aus dieser Sitzung. Zurück kommt die Größe des Stapels.
     */
    int adoptUndo(List<UndoRecord> older) {
        undo.addAll(older);
        trimUndo();
        return undo.size();
    }

    // ---------------------------------------------------------------- Befehle bestätigen (Plan B5)

    /** Höchstens so viele Blöcke mit Befehlen listet die Bestätigung – mehr liest niemand, also wird nichts bestätigt. */
    static final int MAX_LISTED_COMMANDS = 20;

    /** Alles, um einen Plan nach der Antwort auf die Befehlsliste zu bauen – dieselben Eingaben wie startJob. */
    private record CommandOffer(Request r, CompiledBuild planned, String plannedJson, boolean update,
            List<String> fixes, int rounds) {
    }

    private final CommandConfirmations<CommandOffer> commandOffers = new CommandConfirmations<>();

    /**
     * Darf r Befehle aus Plantext bestätigen? Konsole ja; ein Spieler nur mit der Stufe „Befehle“ in diesem Moment und
     * nur, wenn kein fremder Text im Prompt stand (Obergrenze COMMANDS). Gelesen beim Fragen UND beim Klick.
     */
    private static boolean mayConfirmCommands(Request r) {
        if (r.cap() != Tier.COMMANDS) {
            return false;
        }
        // Befehlsblock, execute as, fremdes Plugin: weder Spieler noch Konsole – darf nie bestätigen (Befund 9).
        return BuildRights.ofSender(r.sender(), r.feet().getWorld()).allows(Tier.COMMANDS);
    }

    /**
     * Liste der Befehle an den Auftraggeber zeigen; der Bau wartet auf den Klick (/bm befehle ja|ohne|nein). false = er
     * darf nicht bestätigen oder es sind zu viele – dann baut startJob ohne diese Zellen. lines: je Zelle eine Zeile.
     */
    private boolean offerCommands(Request r, CompiledBuild planned, String plannedJson, boolean update,
            List<String> fixes, int rounds, String fingerprint, List<String> lines, List<String> refused) {
        if (!mayConfirmCommands(r)) {
            return false;
        }
        if (lines.size() > MAX_LISTED_COMMANDS) {
            Texts.info(r.sender(), "Der Plan enthält " + lines.size() + " Blöcke mit Befehlen – zu viele zum "
                    + "Bestätigen (höchstens " + MAX_LISTED_COMMANDS + "). Ich baue ohne sie.");
            return false;
        }
        UUID who = r.sender() instanceof Player p ? p.getUniqueId() : null;
        String token = commandOffers.offer(who, fingerprint,
                new CommandOffer(r.withConsent(CommandConsent.ASK), planned, plannedJson, update, fixes, rounds));
        Texts.info(r.sender(), "Der Plan enthält " + lines.size() + (lines.size() == 1 ? " Block" : " Blöcke")
                + " mit Befehlen. Gebaut wird erst, wenn du sie bestätigst:");
        for (String line : lines) {
            Texts.info(r.sender(), line);
        }
        if (!refused.isEmpty()) {
            Texts.info(r.sender(), "Diese " + refused.size() + " lasse ich in jedem Fall weg:");
            for (int i = 0; i < Math.min(5, refused.size()); i++) {
                Texts.info(r.sender(), "  " + refused.get(i));
            }
            if (refused.size() > 5) {
                Texts.info(r.sender(), "  … und " + (refused.size() - 5) + " weitere.");
            }
        }
        Texts.choices(r.sender(), List.of(
                new Texts.Choice("Befehle bestätigen", "/bm befehle ja " + token,
                        "Baut den Plan mit genau diesen Befehlen"),
                new Texts.Choice("ohne Befehle bauen", "/bm befehle ohne " + token,
                        "Baut den Plan, lässt diese Blöcke weg"),
                new Texts.Choice("abbrechen", "/bm befehle nein " + token, "Verwirft den Plan")),
                " – gilt 10 Minuten.");
        npc.status("wartet auf Bestätigung");
        int n = lines.size();
        LOG.info(() -> "bau | befehle_zur_bestätigung | besitzer=" + r.ownerName() + " blöcke=" + n
                + " prüfsumme=" + fingerprint.substring(0, 12));
        return true;
    }

    /**
     * Je Block mit Befehl eine Zeile für die Bestätigung: Nummer, Block, Weltkoordinaten, Befehl (wie geplant, mit
     * Platzhaltern {x,y,z} in Plankoordinaten) bzw. der Grund aus BlockTiers.
     */
    private static List<String> commandLines(Request r, List<PlanCommands.Found> found, Set<Pos> confirmable,
            int shiftX, int shiftZ) {
        List<String> out = new ArrayList<>();
        int i = 0;
        for (PlanCommands.Found f : found) {
            if (!confirmable.contains(f.pos())) {
                continue;
            }
            i++;
            Block b = r.frame().blockAt(f.pos().add(shiftX, 0, shiftZ));
            // Der Befehl, wie er läuft: Platzhalter in Weltkoordinaten (wie BuildService.placement), ungekürzt und mit
            // sichtbaren Sonderzeichen (PlanCommands.visible) – bestätigt wird, was dasteht.
            String runs = CommandText.render(f.command(), q -> {
                Block w = r.frame().blockAt(q.add(shiftX, 0, shiftZ));
                return new int[] {w.getX(), w.getY(), w.getZ()};
            });
            out.add("#" + i + " " + f.block() + " bei " + b.getX() + " " + b.getY() + " " + b.getZ() + ": „"
                    + PlanCommands.visible(runs) + "“" + (f.autoStart() ? " (immer aktiv → wird zu „braucht Redstone“)"
                            : ""));
        }
        return out;
    }

    /** /bm befehle ja|ohne|nein &lt;token&gt; – Antwort auf die Befehlsliste (Plan B5). */
    void answerCommands(CommandSender sender, String[] args) {
        String verb = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
        if (args.length < 3 || !List.of("ja", "ohne", "nein").contains(verb)) {
            Texts.info(sender, "Antwort auf die Befehlsliste eines Plans: /bm befehle ja|ohne|nein <code> – am "
                    + "einfachsten die Knöpfe unter der Liste anklicken.");
            return;
        }
        if (!(sender instanceof Player) && !BuildRights.isConsole(sender)) {
            // Ein Befehlsblock hätte sonst wie die Konsole antworten können (Befund 9).
            Texts.info(sender, "Befehle bestätigen können nur Spieler und die Konsole.");
            return;
        }
        UUID who = sender instanceof Player p ? p.getUniqueId() : null;
        if (!"nein".equals(verb) && busy()) {
            // Nicht einlösen: Die Bestätigung bleibt gültig, bis sie abläuft.
            Texts.info(sender, "Ich plane oder baue gerade – klick noch einmal, wenn ich fertig bin.");
            return;
        }
        CommandConfirmations.Taken<CommandOffer> t = commandOffers.take(args[2], who);
        if (!t.ok()) {
            Texts.info(sender, t.problem());
            return;
        }
        CommandOffer o = t.offer().payload();
        switch (verb) {
            case "ja" -> {
                // Recht beim Klick erneut: entzogen oder Kreativ/Welt gewechselt zwischen Liste und Klick → nichts.
                if (!mayConfirmCommands(o.r())) {
                    npc.status("bereit");
                    Texts.info(sender, "Dir fehlt inzwischen das Recht " + Tier.COMMANDS.permission()
                            + " – der Plan ist verworfen.");
                    LOG.info(() -> "bau | befehle_abgelehnt | besitzer=" + o.r().ownerName() + " grund=recht_fehlt");
                    return;
                }
                LOG.info(() -> "bau | befehle_bestätigt | besitzer=" + o.r().ownerName() + " prüfsumme="
                        + t.offer().fingerprint().substring(0, 12));
                restartAfterCommands(o, CommandConsent.confirmed(t.offer().fingerprint()));
            }
            case "ohne" -> {
                LOG.info(() -> "bau | befehle_weggelassen | besitzer=" + o.r().ownerName());
                restartAfterCommands(o, CommandConsent.WITHOUT);
            }
            default -> {
                npc.status("bereit");
                Texts.info(sender, "Abgebrochen – der Plan ist verworfen.");
                LOG.info(() -> "bau | befehle_verworfen | besitzer=" + o.r().ownerName());
            }
        }
    }

    /** Plan nach der Antwort bauen: dieselben Eingaben, Merker der Nachbesserung zurück, wie startConfirmed. */
    private void restartAfterCommands(CommandOffer o, CommandConsent consent) {
        pendingFixes = o.fixes();
        pendingRounds = o.rounds();
        startJob(o.r().withPreview(false).withConsent(consent), o.planned(), o.plannedJson(), o.update());
    }

    /** Bestätigte Vorschau bauen (ComfortFlow, nach dem Vergleich der Prüfsumme). Nie wieder als Vorschau. */
    void startConfirmed(Request r, CompiledBuild build, String planJson, boolean update, List<String> fixes,
            int rounds) {
        Texts.say(npc.name(), npcLocation(r.feet()), r.sender(), "Dann los!");
        // Merker der Vorschau zurück, startJob übernimmt und leert ihn wie bei jedem Bau.
        pendingFixes = fixes;
        pendingRounds = rounds;
        startJob(r.withPreview(false), build, planJson, update);
    }

    /** Aus dem Stapel gefallene Einträge vergessen – bei Stadtbauten auch die Dateien auf der Platte. */
    private void trimUndo() {
        while (undo.size() > settings.undoDepth()) {
            UndoRecord dropped = undo.removeLast();
            comfort.forget(dropped);
            if (dropped.spool() != null) {
                dropped.spool().delete();
            }
        }
    }

    /**
     * true, solange wirklich in der Welt gebaut oder zurückgebaut wird – NICHT während der KI-Planung. Stadt- und Weltbau
     * fragen das seit 2026-11-01 statt busy(): Sie brauchen die KI nie, warteten aber bis zu 900 s auf einen LLM-Plan.
     * Wer sie wieder an busy() hängt, macht den Stadtbau abhängig von einem langsamen oder hängenden LLM.
     */
    boolean buildingInWorld() {
        return active != null && active.running() || restoring != null || deferred.waiting();
    }

    private void startDeferredTimer() {
        if (deferredTask != null || !plugin.isEnabled()) {
            return;
        }
        deferredTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                deferred.runIfFree(otherBuildConflict() == null && !thinking
                        && (active == null || !active.running()) && restoring == null);
            } finally {
                if (!deferred.waiting() && deferredTask != null) {
                    deferredTask.cancel();
                    deferredTask = null;
                }
            }
        }, 20L, 20L);
    }

    /** true, solange der Baumeister plant, baut oder zurückbaut – Stadtbau, Weltbau und Warteschlange warten dann. */
    boolean busy() {
        return thinking || active != null && active.running() || restoring != null;
    }

    private void onJobFinished(BuildJob job) {
        pushUndo(job.undoRecord());
        if (active == job && activeKind != LedgerKind.REPAIR && job.done() >= job.total()) {
            record(job, activeKind == LedgerKind.UPDATE, activeLedgerOrder, activeLedgerInfo);
        }
        ResultReporter rr = plugin.resultReporter();
        if (rr != null && active == job) {
            rr.report(activeOrder, job.done() >= job.total() ? "gebaut" : "abgebrochen",
                    job.done() >= job.total() ? "" : job.done() + " von " + job.total() + " Schritten", job.placed(),
                    activePlot, activeFixes, activeRounds);
        }
        if (job == guidedJob) {
            if (job.done() >= job.total()) {
                tellGuides();
            }
            guidedJob = null;
        }
        if (active == job) {
            active = null;
        }
    }

    /**
     * Nach dem Bau sagen, was der Spieler an der Farm noch tun muss (Nutzer 2026-11-08) – erst jetzt, weil er sie dann
     * vor sich hat; die Antwort beim Auftrag nennt nur, was er bereitlegen soll (FarmGuide.acceptReply). Nur nach einem
     * vollständigen Bau: Für eine halb gebaute Farm stimmen die Schritte nicht. Läuft nach BuildJob.finish, also nach
     * dessen „Fertig!“.
     */
    private void tellGuides() {
        Mannequin m = npc.get();
        Location at = m != null ? m.getLocation() : null;
        int lines = 0;
        for (FarmGuide.Guide g : activeGuides) {
            for (String line : g.lines()) {
                Texts.say(npc.name(), at, guideTo, line);
                lines++;
            }
        }
        int n = lines;
        String who = guideTo == null ? "-" : guideTo.getName();
        String names = String.join(",", activeGuides.stream().map(FarmGuide.Guide::module).toList());
        LOG.info(() -> "bau | anleitung | an=" + who + " bauteile=" + names + " zeilen=" + n);
    }

    // ---------------------------------------------------------------- Stop, Rückgängig, Status

    void stop(CommandSender sender) {
        // Offene Bestätigung von Befehlen (Plan B5): „stopp“ verwirft den wartenden Plan wie einen vorgemerkten. Läuft
        // daneben ein Bau, geht es unten mit ihm weiter.
        if (commandOffers.drop(sender instanceof Player p ? p.getUniqueId() : null)) {
            Texts.info(sender, "Der Plan, der auf die Bestätigung seiner Befehle wartete, ist verworfen.");
            if (!deferred.waiting() && (active == null || !active.running())) {
                npc.status("bereit");
                return;
            }
        }
        if (deferred.waiting() && (active == null || !active.running())) {
            if (sender.hasPermission("baumeister.admin") || sender.getName().equals(deferred.owner())) {
                deferred.cancel();
                npc.status("bereit");
                Texts.info(sender, "Der vorgemerkte Plan ist verworfen.");
            } else {
                Texts.info(sender, "Der vorgemerkte Plan gehört " + deferred.owner() + ".");
            }
            return;
        }
        if (active == null || !active.running()) {
            Texts.info(sender, thinking ? "Ich plane noch – stoppen geht erst, wenn ich baue." : "Ich baue gerade nichts.");
            return;
        }
        if (!mayTouch(sender, active.ownerId())) {
            Texts.info(sender, "Das ist nicht dein Bau.");
            return;
        }
        active.cancel("Gestoppt. Was steht, bleibt stehen – /bm rückgängig räumt es weg.");
    }

    /**
     * /bm helfer [n|+n|-n|auto] und Chat „mehr/weniger helfer“, wenn keine Stadt läuft. Ändert den laufenden Bau (wenn
     * er dem Absender gehört oder der Admin ist) sofort und merkt sich die Zahl für die nächsten Bauten des Absenders.
     */
    void helpers(CommandSender sender, String arg) {
        UUID key = sender instanceof Player p ? p.getUniqueId() : CONSOLE;
        BuildJob job = active != null && active.running() && mayTouch(sender, active.ownerId()) ? active : null;
        Integer standing = helperWish.get(key);
        if (arg == null) {
            Texts.info(sender, (job != null ? "Gerade " + job.helpers() + " Helfer. " : "") + "Nächste Bauten: "
                    + (standing == null ? "nach Größe (ab " + settings.secondHelperFrom() + " Blöcken einer, ab "
                            + settings.thirdHelperFrom() + " zwei)" : standing + " Helfer")
                    + ". Ändern: /bm helfer <0–" + HelperWish.MAX + "|auto> – oder im Auftrag „… mit 4 Helfern“.");
            return;
        }
        int current = job != null ? job.helpers() : standing != null ? standing : 0;
        Integer n = HelperWish.parseCommand(arg, current);
        if (n == null) {
            Texts.info(sender, "Helfer: eine Zahl von 0 bis " + HelperWish.MAX + " oder auto, z. B. /bm helfer 4");
            return;
        }
        if (n < 0) {
            helperWish.remove(key);
            Texts.info(sender, "Helfer wieder nach Größe des Baus."
                    + (job != null ? " Der laufende Bau behält " + job.helpers() + "." : ""));
            return;
        }
        helperWish.put(key, n);
        if (job != null) {
            job.helpers(n);
        }
        Texts.info(sender, (job != null ? "Jetzt " + n + " Helfer – auch für deine nächsten Bauten."
                : "Deine nächsten Bauten bekommen " + n + " Helfer.") + " Zurück zur Größenregel: /bm helfer auto");
    }

    void undo(CommandSender sender) {
        if (active != null && active.running() && mayTouch(sender, active.ownerId())) {
            active.cancel("Gestoppt, ich räume auf.");
        }
        UndoRecord rec = null;
        for (Iterator<UndoRecord> it = undo.iterator(); it.hasNext();) {
            UndoRecord candidate = it.next();
            if (mayTouch(sender, candidate.owner())) {
                rec = candidate;
                it.remove();
                comfort.forget(rec);
                break;
            }
        }
        if (rec == null) {
            Texts.info(sender, "Ich habe nichts, was ich zurückbauen könnte.");
            return;
        }
        UUID owner = rec.owner();
        long when = rec.when().toEpochMilli();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> ledger.forget(owner, when));
        restore(sender, rec);
    }

    /**
     * Rückwärts und ohne Physik: exakt der alte Zustand, ohne dass Sand rieselt oder Wasser läuft. Paketweit, weil der
     * Weltbau seine Kacheln in einem eigenen Speicher hält (TileUndo) und sie hierüber zurücksetzt.
     *
     * Chunks wie im Stadtbau nur gedrosselt laden (RestoreJob). Und immer nur ein Rückgängig zur Zeit: Vorher startete
     * jedes /bm rückgängig einen eigenen Takt neben allem anderen – dev01 2026-10-30: eines über 4,5 Mio. Blöcke
     * parallel zu einem neuen Stadtbau, danach vier weitere in 6 s, kurz darauf stand der Server (Watchdog). Jetzt
     * warten weitere in undoQueue, und solange eines läuft, ist busy() wahr – Stadtbau, Fortsetzen, Weltbau und die
     * Warteschlange warten mit.
     */
    void restore(CommandSender sender, UndoRecord rec) {
        if (restoring != null) {
            undoQueue.add(Map.entry(sender, rec));
            Texts.info(sender, "Ich räume gerade noch auf – danach kommt dieser Bau dran (" + undoQueue.size()
                    + ". in der Reihe).");
            return;
        }
        // Einträge überleben Neustarts und damit Spigot-Updates: Ein unbekannter Zustand wird übersprungen
        // (StateCache), statt im Timer bei jedem Tick an derselben Stelle zu werfen.
        StateCache<BlockData> states = new StateCache<>(Bukkit::createBlockData);
        World world = rec.world();
        RestoreJob.Source source;
        long total;
        int partCount;
        if (rec.spool() != null) {
            UndoSpool spool = rec.spool();
            List<Path> parts;
            try {
                parts = spool.partsNewestFirst();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "bau | rückgängig_unlesbar | ordner=" + spool.dir().getFileName(), e);
                Texts.info(sender, "Die Rückgängig-Daten sind nicht lesbar: " + e.getMessage());
                startNextUndo();
                return;
            }
            total = spool.count();
            partCount = parts.size();
            Iterator<Path> it = parts.iterator();
            source = () -> it.hasNext() ? spoolPart(world, UndoSpool.read(it.next()), states) : null;
        } else {
            List<Map.Entry<Block, BlockData>> list = rec.originals();
            total = list.size();
            partCount = 1;
            boolean[] given = {false};
            source = () -> {
                if (given[0]) {
                    return null;
                }
                given[0] = true;
                return originalsPart(list);
            };
        }
        Texts.info(sender, "Ich räume " + total + " Blöcke weg …");
        // Vorgaben des Stadtbaus (CityService.launch): dieselbe Rate neuer Chunks, dieselbe Zeit je Tick.
        int chunks = cityCfg("stadt.neue-chunks-pro-tick", 8, 1, 64);
        int ms = cityCfg("stadt.ms-pro-tick", 25, 5, 45);
        int lagMs = cityCfg("stadt.lag-ms", 150, 60, 2000);
        RestoreJob job = new RestoreJob(source, chunksOf(world), new LoadControl(lagMs * 1_000_000L, chunks),
                ms * 1_000_000L, 20_000, CityJob.maxHeld(world.getMaxHeight() - world.getMinHeight()),
                System::nanoTime);
        restoring = job;
        long t0 = System.nanoTime();
        long[] last = {t0};
        restoringTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.nanoTime();
            long gap = now - last[0];
            last[0] = now;
            RestoreJob.Step step;
            try {
                step = job.tick(gap);
            } catch (IOException e) {
                endRestore(job);
                LOG.log(Level.WARNING, "bau | rückgängig_teil_unlesbar | besitzer=" + rec.ownerName(), e);
                Texts.info(sender, "Rückgängig abgebrochen, ein Teil ist nicht lesbar: " + e.getMessage());
                startNextUndo();
                return;
            }
            if (step != RestoreJob.Step.DONE) {
                return;
            }
            endRestore(job);
            if (rec.spool() != null) {
                rec.spool().delete();
            }
            long skipped = states.skipped();
            Texts.info(sender, "Rückgängig gemacht: " + job.restored() + " Blöcke wiederhergestellt."
                    + (skipped == 0 ? "" : " " + skipped + " Blöcke mit unbekanntem Zustand übersprungen."));
            LOG.info(() -> String.format("bau | rückgängig | besitzer=%s blöcke=%d teile=%d übersprungen=%d "
                    + "tickets=%d dauer_s=%d", rec.ownerName(), job.restored(), partCount, skipped, job.tickets(),
                    (System.nanoTime() - t0) / 1_000_000_000L));
            startNextUndo();
        }, 1L, 1L);
    }

    private void endRestore(RestoreJob job) {
        if (restoringTask != null) {
            restoringTask.cancel();
            restoringTask = null;
        }
        job.releaseAll();
        restoring = null;
    }

    private void startNextUndo() {
        Map.Entry<CommandSender, UndoRecord> next = undoQueue.poll();
        if (next != null) {
            restore(next.getKey(), next.getValue());
        }
    }

    /** true, solange ein Rückgängig läuft (CityService sagt dann, worauf der Stadtbau wartet). */
    boolean restoring() {
        return restoring != null;
    }

    private int cityCfg(String key, int def, int min, int max) {
        return Math.max(min, Math.min(max, plugin.getConfig().getInt(key, def)));
    }

    private RestoreJob.Chunks chunksOf(World w) {
        return new RestoreJob.Chunks() {
            @Override
            public boolean loaded(int cx, int cz) {
                return w.isChunkLoaded(cx, cz);
            }

            @Override
            public void hold(int cx, int cz) {
                // Lädt bzw. erzeugt den Chunk sofort im Hauptthread (CraftWorld.addPluginChunkTicket) – deshalb die
                // Rate in RestoreJob.
                w.addPluginChunkTicket(cx, cz, plugin);
            }

            @Override
            public void release(int cx, int cz) {
                w.removePluginChunkTicket(cx, cz, plugin);
            }
        };
    }

    private static RestoreJob.Part spoolPart(World w, UndoSpool.Part p, StateCache<BlockData> states) {
        return new RestoreJob.Part() {
            @Override
            public int size() {
                return p.size();
            }

            @Override
            public int x(int i) {
                return p.x()[i];
            }

            @Override
            public int z(int i) {
                return p.z()[i];
            }

            @Override
            public boolean set(int i) {
                BlockData data = states.get(p.state()[i]);
                if (data == null) {
                    return false;
                }
                w.getBlockAt(p.x()[i], p.y()[i], p.z()[i]).setBlockData(data, false);
                return true;
            }
        };
    }

    private static RestoreJob.Part originalsPart(List<Map.Entry<Block, BlockData>> list) {
        return new RestoreJob.Part() {
            @Override
            public int size() {
                return list.size();
            }

            @Override
            public int x(int i) {
                return list.get(i).getKey().getX();
            }

            @Override
            public int z(int i) {
                return list.get(i).getKey().getZ();
            }

            @Override
            public boolean set(int i) {
                Map.Entry<Block, BlockData> e = list.get(i);
                e.getKey().setBlockData(e.getValue(), false);
                return true;
            }
        };
    }

    /**
     * Soll-Zustand des fertigen Baus ins Baubuch: je Block der zuletzt gesetzte Zustand, ohne Luft. Zustände werden im
     * Hauptthread zu Text, Schreiben läuft asynchron.
     */
    private void record(BuildJob job, boolean replace, String order, String info) {
        if (job.ownerId() == null || job.actions().isEmpty()) {
            return;
        }
        Map<Long, BuildLedger.Cell> last = new java.util.LinkedHashMap<>();
        World world = null;
        for (WorldAction a : job.actions()) {
            for (WorldChange c : a.changes()) {
                Block b = c.block();
                world = b.getWorld();
                long k = pack(b.getX(), b.getY(), b.getZ());
                if (c.data().getMaterial().isAir()) {
                    last.remove(k);
                } else {
                    last.put(k, new BuildLedger.Cell(b.getX(), b.getY(), b.getZ(), c.data().getAsString()));
                }
                if (last.size() > BuildLedger.MAX_CELLS) {
                    return;  // Stadt- oder Weltbau-Größe: nicht ins Baubuch
                }
            }
        }
        if (world == null || last.isEmpty()) {
            return;
        }
        UUID worldId = world.getUID();
        UUID owner = job.ownerId();
        String name = job.ownerName();
        long undoMillis = job.undoRecord() != null ? job.undoRecord().when().toEpochMilli() : 0;
        List<BuildLedger.Cell> cells = List.copyOf(last.values());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> ledger.add(owner, name, order, worldId, undoMillis,
                cells, replace, info).ifPresent(e -> LOG.info(() -> "baubuch | eingetragen | besitzer=" + name + " blöcke="
                        + e.cells() + " auftrag=" + Texts.clean(order, 60))));
    }

    /**
     * Antwort des Plans plus Beschreibung jedes Bauteils (Machines: Bedienung wie „Hebel links = Takt, Lampen vorn =
     * Wert“). Höchstens 900 Zeichen, damit Veras Prompt klein bleibt. Ohne Plan (Rückfall-Hochhaus): leer.
     */
    private String buildInfo(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            StringBuilder sb = new StringBuilder();
            if (root.has("reply")) {
                sb.append(root.get("reply").getAsString());
            }
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            if (root.has("steps")) {
                for (com.google.gson.JsonElement el : root.getAsJsonArray("steps")) {
                    JsonObject s = el.getAsJsonObject();
                    String op = s.has("op") ? s.get("op").getAsString() : "";
                    if (op.equals("module") && s.has("name") && seen.add(s.get("name").getAsString())) {
                        String n = s.get("name").getAsString();
                        modules.find(n).filter(m -> !m.description().isBlank())
                                .ifPresent(m -> sb.append(" | ").append(n).append(": ").append(m.description()));
                    } else if (op.equals("logic") && s.has("expr") && seen.add("logic")) {
                        sb.append(" | Logikschaltung ").append(s.get("expr").getAsString())
                                .append(": Hebel vorn sind die Eingänge, Lampen hinten die Ausgänge.");
                    }
                }
            }
            return Texts.clean(sb.toString(), 900);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "baubuch | info_unlesbar", e);
            return "";
        }
    }

    /** Für Veras Kontext: der Baumeister-Bau, in dem der Spieler steht, samt Bedienung – oder leer. */
    Optional<String> describeBuildAt(Player p) {
        Location l = p.getLocation();
        return ledger.at(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), 3)
                .map(e -> "„" + Texts.clean(e.order(), 80) + "“ (gebaut vom Baumeister für " + e.ownerName() + ")"
                        + (e.info() == null || e.info().isBlank() ? "" : ". Bedienung: " + e.info()));
    }

    /**
     * „Vera, reparier das“ (2026-11-04): den Baumeister-Bau an der Stelle des Spielers in den Soll-Zustand aus
     * dem Baubuch zurücksetzen – nur fehlende oder durch anderes Material ersetzte Blöcke (BuildLedger.damaged). Läuft
     * als normaler Bauauftrag: Rechteprüfung (WorldGuard, Plots) wie beim Bauen, rückgängig machbar, Redstone wird
     * aufgefrischt. Rückgabe: was Vera dazu sagt.
     */
    String repair(Player p) {
        if (thinking || (active != null && active.running()) || restoring != null) {
            return "Der Baumeister ist gerade beschäftigt – sobald er fertig ist, repariere ich gern.";
        }
        Location l = p.getLocation();
        World world = l.getWorld();
        Optional<BuildLedger.Entry> found = ledger.at(world.getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), 3);
        if (found.isEmpty()) {
            return "Hier steht kein Bau vom Baumeister, den ich kenne. Reparieren kann ich nur, was er gebaut hat.";
        }
        BuildLedger.Entry e = found.get();
        if (!mayTouch(p, e.owner())) {
            return "Das ist der Bau von " + e.ownerName() + " – reparieren darf ihn nur " + e.ownerName()
                    + " oder ein Admin.";
        }
        List<BuildLedger.Cell> cells;
        try {
            cells = ledger.cells(e);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "baubuch | lesen_fehlgeschlagen | id=" + e.id(), ex);
            return "Ich finde die Baupläne dazu nicht mehr.";
        }
        List<BuildLedger.Cell> bad = BuildLedger.damaged(cells,
                (x, y, z) -> world.getBlockAt(x, y, z).getType().getKey().toString());
        if (bad.isEmpty()) {
            return "An „" + Texts.clean(e.order(), 60) + "“ fehlt nichts – alles ist so, wie der Baumeister es "
                    + "gebaut hat.";
        }
        StateCache<BlockData> states = new StateCache<>(Bukkit::createBlockData);
        List<WorldAction> actions = new ArrayList<>();
        // Plan B4: Reparieren setzt nur Zustände (das Baubuch kennt keine Daten) und prüft die Stufe des Anfragenden
        // beim Setzen neu (BuildJob) – wer keinen Spawner setzen darf, bekommt ihn auch über „reparier“ nicht.
        BlockTiers.WorldFacts facts = new BlockTiers.WorldFacts(world.isBedWorks(), world.isRespawnAnchorWorks());
        for (BuildLedger.Cell c : bad) {
            BlockData data = states.get(c.data());
            if (data != null) {
                actions.add(new WorldAction(0, List.of(new WorldChange(world.getBlockAt(c.x(), c.y(), c.z()), data,
                        repairPlacement(c.data(), facts)))));
            }
        }
        if (actions.isEmpty()) {
            return "Die Blöcke dieses Baus kennt diese Minecraft-Version nicht mehr.";
        }
        Location center = new Location(world, (e.minX() + e.maxX()) / 2.0 + 0.5, e.minY(),
                (e.minZ() + e.maxZ()) / 2.0 + 0.5);
        Cardinal facing = Cardinal.fromYaw(l.getYaw());
        Pos f = new Orientation(facing).toWorldOffset(new Pos(0, 0, -1));
        UUID checkAs = settings.checkPermissions() ? p.getUniqueId() : null;
        activeOrder = "Reparatur: " + e.order();
        activePlot = "";
        activeFixes = List.of();
        activeRounds = 0;
        activeKind = LedgerKind.REPAIR;
        int n = actions.size();
        LOG.info(() -> "baubuch | reparatur | spieler=" + p.getName() + " besitzer=" + e.ownerName() + " blöcke=" + n
                + " auftrag=" + Texts.clean(e.order(), 60));
        active = new BuildJob(plugin, npc, settings, e.owner(), e.ownerName(), checkAs, p.getUniqueId(), p,
                List.copyOf(actions), List.of("Reparatur"), center, new Vector(f.x(), 0, f.z()), e.minY(), 1, false,
                this::onJobFinished);
        active.start();
        // Was zurückkommt, nach Material: Vorher nur „8736 Blöcke fehlen“ – dass es das Dach war, das der Spieler selbst
        // mit //set 0 entfernt hatte, sah niemand (dev01 2026-11-04 16:20).
        Map<String, Integer> byMaterial = new java.util.HashMap<>();
        for (BuildLedger.Cell c : bad) {
            byMaterial.merge(c.material().replace("minecraft:", ""), 1, Integer::sum);
        }
        String what = byMaterial.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3).map(x -> x.getValue() + "× " + x.getKey()).collect(java.util.stream.Collectors.joining(", "));
        return "Ich repariere „" + Texts.clean(e.order(), 60) + "“: " + (n == 1 ? "ein Block fehlt" : n + " Blöcke fehlen")
                + " oder sind falsch (" + what + (byMaterial.size() > 3 ? ", …" : "") + ").";
    }

    /** Stufe eines Baubuch-Zustands (nur ID und Zustände, nie Daten); unlesbar → höchste Stufe. */
    static Placement repairPlacement(String state, BlockTiers.WorldFacts facts) {
        BlockTiers.Verdict v;
        try {
            BlockSpec spec = BlockSpec.parseTrusted(state);
            v = BlockTiers.state(spec.id(), spec.props(), facts);
        } catch (PlanException ex) {
            v = new BlockTiers.Verdict(com.blackn0va.baumeister.plan.Tier.COMMANDS, "unlesbarer Zustand");
        }
        return new Placement(v.tier(), v.reason(), Set.of(), null, BlockDataWriter.Expectation.NONE, 0);
    }

    private static boolean mayTouch(CommandSender sender, UUID owner) {
        if (sender.hasPermission("baumeister.admin")) {
            return true;
        }
        return sender instanceof Player p && p.getUniqueId().equals(owner);
    }

    String statusLine() {
        if (deferred.waiting() && !thinking && (active == null || !active.running())) {
            return "Plan von " + deferred.owner() + " wartet auf das Ende des Stadt- bzw. Weltbaus"
                    + comfort.statusSuffix();
        }
        if (thinking) {
            return "plant gerade (wartet auf das LLM)" + comfort.statusSuffix();
        }
        if (active != null && active.running()) {
            return "baut: " + active.done() + "/" + active.total() + " Aktionen"
                    + (active.helpers() > 0 ? ", " + active.helpers() + " Helfer" : "") + comfort.statusSuffix();
        }
        if (restoring != null) {
            return "räumt auf (rückgängig): " + restoring.restored() + " Blöcke zurückgesetzt"
                    + (undoQueue.isEmpty() ? "" : ", " + undoQueue.size() + " warten") + comfort.statusSuffix();
        }
        return "bereit" + (undo.isEmpty() ? "" : ", " + undo.size() + " Bau(ten) rückgängig machbar")
                + comfort.statusSuffix();
    }

    void shutdown() {
        if (active != null) {
            active.cancel("Server fährt herunter – Bau abgebrochen.");
        }
        if (restoring != null) {
            RestoreJob job = restoring;
            endRestore(job);
            LOG.warning(() -> "bau | rückgängig_unterbrochen | wiederhergestellt=" + job.restored() + " wartend="
                    + undoQueue.size() + " – der Rest bleibt stehen");
            undoQueue.clear();
        }
        comfort.shutdown();
    }

    /** Laufender Bau oder null – für den Fortschrittsbalken (ProgressBarTask). */
    BuildJob activeJob() {
        return active != null && active.running() ? active : null;
    }

    /**
     * /bm neuladen baut einen neuen Dienst: Rückgängig-Stapel, Warteschlange, Vorschauen und Kopien ziehen mit um.
     * Vorher ging der Stapel beim Neuladen verloren.
     */
    void takeOver(BuildService old) {
        undo.addAll(old.undo);
        old.undo.clear();
        helperWish.putAll(old.helperWish);
        comfort.takeOver(old.comfort);
        trimUndo();
    }

    // ---------------------------------------------------------------- Hilfen

    private Location npcLocation(Location fallback) {
        Mannequin m = npc.get();
        return m != null ? m.getLocation() : fallback;
    }

    private void runSync(Runnable r) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    /**
     * Rohantwort ablegen – daraus lernt man, welche Blocknamen und Fehler das Modell wirklich
     * produziert (Aliase in BlockRules nur von dort nachtragen). Fehler beim Schreiben sind
     * fail-open: Der Bau hängt nicht an der Diagnose.
     */
    private void savePlan(String owner, String prompt, int attempt, LlmClient.Reply reply) {
        Path dir = plugin.getDataFolder().toPath().resolve("pläne");
        String safeOwner = owner.replaceAll("[^A-Za-z0-9_]", "_");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        JsonObject o = new JsonObject();
        o.addProperty("besitzer", owner);
        o.addProperty("auftrag", prompt);
        o.addProperty("versuch", attempt);
        o.addProperty("dauer_ms", reply.millis());
        o.addProperty("tokens", reply.tokens());
        o.addProperty("antwort", reply.content());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(stamp + "-" + safeOwner + "-" + attempt + ".json"),
                    new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o),
                    StandardCharsets.UTF_8);
            try (Stream<Path> files = Files.list(dir)) {
                List<Path> sorted = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
                for (int k = 0; k < sorted.size() - KEEP_PLANS; k++) {
                    Files.deleteIfExists(sorted.get(k));
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "plan | speichern_fehlgeschlagen | ordner=" + dir, e);
        }
    }

}
