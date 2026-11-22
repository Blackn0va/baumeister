package com.blackn0va.baumeister.client;

import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.SiteSurvey;
import com.blackn0va.baumeister.plan.UpdateGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ablauf eines Client-Baus: Auftrag → Plan (ClientPlanner) → Bau Tick für Tick (PlayerPlacer oder CommandPlacer) →
 * Prüfdurchgang → Ergebnis. Dazwischen hat der Spieler jederzeit Vorrang (Control): Jede eigene Eingabe pausiert im
 * selben Tick; weiter macht an derselben Stelle weiter.
 *
 * Prüfdurchgang: Nach dem letzten Ziel 10 Ticks warten (Antworten des Servers), dann jede Stelle mit dem Ziel
 * vergleichen und Abweichungen EINMAL nachbauen (Nachbar-Updates, ein verweigerter Klick). Was danach noch abweicht,
 * zählt als ausgelassen und steht in der Schlussmeldung – nie still.
 *
 * Threads: alles auf dem Client-Thread; nur die LLM-Antwort kommt aus dem HttpClient-Pool und springt per
 * Minecraft.execute zurück. Fehler: gehen als Chatzeile an den Spieler, nie als Absturz.
 */
final class ClientBuilder {

    private static final Logger LOG = Logger.getLogger("Baumeister-Client");
    /** Scan-Kasten wie SiteFrame im Plugin. */
    static final Pos SCAN_MIN = new Pos(-16, -4, -6);
    static final Pos SCAN_MAX = new Pos(32, 20, 36);

    private final ClientSettings settings;
    private final ClientPlanner planner;
    private final Control control = new Control();
    private final PlayerPlacer player;
    private final CommandPlacer commands;
    private final ResultSender results;
    private final Consumer<String> say;
    private ClientBuild job;
    private ClientBuild lastFinished;
    private boolean planning;
    /** Gültigkeit des LLM-Rückrufs (/kbau stop während der Planung) und der Auftrag dazu für die Meldung an die App. */
    private final PlanTicket tickets = new PlanTicket();
    private String planningPrompt;
    /** Laufende Anfrage an den Host – /kbau stop bricht sie ab, nicht nur ihr Ergebnis (PlanRequest). */
    private PlanRequest planningRequest;
    private int verifyWait;
    private int progressTicks;
    private int hudTicks;
    /** Letzter Bau für Folgeaufträge (ClientMemory) – nur im Speicher, nach einem Spielneustart leer. */
    private final ClientMemory<Map<BlockPos, BlockState>> memory = new ClientMemory<>();

    ClientBuilder(ClientSettings settings, ClientPlanner planner, ResultSender results, Consumer<String> say) {
        this.settings = settings;
        this.planner = planner;
        this.player = new PlayerPlacer(settings);
        this.commands = new CommandPlacer(settings);
        this.results = results;
        this.say = say;
    }

    ClientBuild job() {
        return job;
    }

    /** Für Tests: das Gedächtnis dieses Builders (was der nächste Nachtrag sähe). */
    ClientMemory<Map<BlockPos, BlockState>> memory() {
        return memory;
    }

    boolean planning() {
        return planning;
    }

    // ---------------------------------------------------------------- Auftrag

    void start(Minecraft mc, String prompt) {
        if (job != null && job.active()) {
            say.accept("Es läuft schon ein Bau – /kbau stop bricht ihn ab.");
            return;
        }
        if (planning) {
            say.accept("Ich plane noch – einen Moment.");
            return;
        }
        // Ohne OP-Stufe 2 (Gamemaster) beantwortet der Server jedes /setblock mit „Unbekannter Befehl“ – vorher sendete
        // die Mod trotzdem und meldete danach jede Stelle als falsch (Spieltest 2026-10-31: 1185 von 2364).
        if (settings.commandMode() && !mc.player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
            say.accept("Die Befehls-Bauweise braucht OP-Rechte (bzw. Cheats in der Einzelspielerwelt). "
                    + "Ohne: /kbau modus spieler.");
            return;
        }
        if (!settings.commandMode() && !mc.player.getAbilities().instabuild) {
            say.accept("Die Spieler-Bauweise braucht den Kreativmodus. Wechsle ihn oder nimm /kbau modus befehl (OP).");
            return;
        }
        BlockPos here = mc.player.blockPosition();
        // Folgeauftrag in der Nähe des letzten Baus (ClientMemory): derselbe Rahmen wie damals – Füße, Blickrichtung
        // und Verschiebung des alten Baus –, damit alter Plan, Scan und neuer Plan dieselben Koordinaten haben. Wie
        // BuildService.request im Plugin; vorher begann jeder Client-Auftrag bei null.
        ClientMemory.Entry<Map<BlockPos, BlockState>> mem = recall(settings, memory, mc.level, here, prompt);
        BlockPos feet = mem != null ? new BlockPos(mem.x(), mem.y(), mem.z()) : here;
        Orientation o = new Orientation(mem != null ? mem.facing() : Cardinal.fromYaw(mc.player.getYRot()));
        int scanShiftX = mem != null ? mem.shiftX() : 0;
        int scanShiftZ = mem != null ? mem.shiftZ() : settings.distanceAhead;
        SiteSurvey survey = SiteSurvey.scan(SCAN_MIN, SCAN_MAX, p -> {
            Pos w = o.toWorldOffset(p.add(scanShiftX, 0, scanShiftZ));
            BlockPos at = feet.offset(w.x(), w.y(), w.z());
            if (at.getY() < mc.level.getMinY() || at.getY() > mc.level.getMaxY()) {
                return "air";
            }
            return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(at).getBlock()).getPath();
        });
        try {
            Optional<ClientPlanner.Planned> quick = planner.quick(prompt, survey);
            if (quick.isPresent()) {
                begin(mc, prompt, quick.get(), o, feet, survey, mem);
                return;
            }
        } catch (PlanException e) {
            say.accept("Das geht so nicht: " + e.getMessage());
            results.send(prompt, "abgesagt", e.getMessage(), 0);
            return;
        }
        planning = true;
        planningPrompt = prompt;
        PlanRequest request = new PlanRequest();
        planningRequest = request;
        int ticket = tickets.issue();
        // Die Welt des Scans: Kommt der Plan erst nach einem Welt- oder Dimensionswechsel, gehören feet und survey
        // nicht zur neuen Welt. Vorher prüfte der Rückruf nur mc.level == null und baute dann in der fremden Welt.
        Object scanned = mc.level;
        say.accept(mem != null ? "Ich frage den Baumeister-Host (er kennt deinen letzten Bau) …"
                : "Ich frage den Baumeister-Host …");
        ClientPlanner.Previous previous = mem == null ? null
                : new ClientPlanner.Previous(mem.planJson(), mem.prompt(), mem.minutesAgo(memory.now()));
        planner.llm(prompt, survey, previous, msg -> mc.execute(() -> {
            if (tickets.isCurrent(ticket)) {
                say.accept(msg);
            }
        }), request).whenComplete((planned, err) ->
                mc.execute(() -> {
                    if (!tickets.isCurrent(ticket)) {
                        // Per /kbau stop abgebrochen und schon gemeldet; planning gehört womöglich einem neuen Auftrag.
                        return;
                    }
                    planning = false;
                    if (err != null) {
                        Throwable cause = err.getCause() != null ? err.getCause() : err;
                        say.accept(cause.getMessage());
                        results.send(prompt, "verworfen", cause.getMessage(), 0);
                        return;
                    }
                    if (mc.player == null || mc.level == null || mc.level != scanned) {
                        say.accept("Welt gewechselt – der Plan gehört zur alten Welt und wird verworfen.");
                        results.send(prompt, "verworfen", "welt_gewechselt", 0);
                        return;
                    }
                    begin(mc, prompt, planned, o, feet, survey, mem);
                }));
    }

    /**
     * Modul „nachtrag“ (ClientModules, AK-39): aus → jeder Auftrag plant frisch, auch in der Nähe des letzten Baus.
     * Nur das Abrufen hängt am Schalter, nicht das Merken: Rückgängig braucht memoryBefore auch bei ausgeschaltetem
     * Gedächtnis, und wer es wieder einschaltet, findet den letzten Bau. Statisch, damit ClientModulesTest den Schalter
     * ohne Spielstart prüft.
     */
    static <T> ClientMemory.Entry<T> recall(ClientSettings settings, ClientMemory<T> memory, Object level,
            BlockPos here, String prompt) {
        if (!settings.followUps) {
            return null;
        }
        return memory.recall(level, here.getX(), here.getY(), here.getZ(), prompt).orElse(null);
    }

    /** mem: voriger Bau, in dessen Rahmen geplant wurde, oder null (frischer Auftrag). */
    private void begin(Minecraft mc, String prompt, ClientPlanner.Planned planned, Orientation o, BlockPos feet,
            SiteSurvey survey, ClientMemory.Entry<Map<BlockPos, BlockState>> mem) {
        CompiledBuild b = planned.build();
        // Wie BuildService.startJob: ohne Nachbarbauten mittig vor den Spieler, sonst in den Koordinaten des Scans.
        // Mit Gedächtnis gilt die Verschiebung des alten Baus (shiftZ schließt den Abstand ein) – sonst rutschte der
        // Nachtrag gegen den alten Plan, in dessen Koordinaten das LLM ihn geschrieben hat.
        boolean free = mem == null && !survey.hasStructures();
        int shiftX = mem != null ? mem.shiftX() : free ? -Math.floorDiv(b.min().x() + b.max().x(), 2) : 0;
        int shiftZ = mem != null ? mem.shiftZ() : (free ? -b.min().z() : 0) + settings.distanceAhead;
        ClientBuild next = ClientBuild.of(prompt, b, o, feet, 0, shiftX, shiftZ,
                mc.level.registryAccess().lookupOrThrow(Registries.BLOCK), mc.level);
        if (!settings.commandMode()) {
            // Spieler-Bauweise strikt von unten nach oben (stabil sortiert, innerhalb einer Schicht die Reihenfolge des
            // Compilers). Der Compiler ordnet Abschnitt für Abschnitt – bei der Maschinenhalle stand das Dach vor den
            // Maschinen, und die Figur kam nicht mehr darunter (Spieltest 2026-10-31). Befehle brauchen das nicht:
            // Sie reichen durch Wände.
            next.targets.sort(java.util.Comparator.comparingInt(t -> t.pos().getY()));
        }
        launch(next, planned, mc.level, mc.level.getMinY(), feet, o.forward(), shiftX, shiftZ, mem,
                mc.level::getBlockState);
    }

    /**
     * Zweite Hälfte von begin(): Tiefe prüfen, Gedächtnis, Bau übernehmen, ansagen. Ohne Minecraft-Instanz (world nur
     * als Identität, worldAt statt mc.level), damit ClientBuilderMemoryTest den echten Weg aus begin() geht. Vorher
     * rief der Test remember() direkt auf – ohne den Aufruf hier blieben alle Tests grün (Gegenprobe des Dirigenten
     * 2026-11-21).
     */
    void launch(ClientBuild next, ClientPlanner.Planned planned, Object world, int minY, BlockPos feet,
            Cardinal facing, int shiftX, int shiftZ, ClientMemory.Entry<Map<BlockPos, BlockState>> mem,
            java.util.function.Function<BlockPos, BlockState> worldAt) {
        String prompt = next.order;
        int lowest = next.targets.stream().mapToInt(t -> t.pos().getY()).min().orElse(feet.getY());
        if (lowest <= minY) {
            say.accept("Hier geht das nicht so tief – der Bau reicht bis ans Grundgestein. Such dir mehr Boden darunter.");
            results.send(prompt, "abgesagt", "zu tief", 0);
            return;
        }
        remember(memory, next, world, feet, facing, shiftX, shiftZ, planned, mem, worldAt);
        next.fixes = planned.fixes();
        next.rounds = planned.rounds();
        job = next;
        String reply = planned.reply() == null || planned.reply().isBlank() ? "Ich fange an!" : planned.reply();
        say.accept(reply + " (" + job.total() + " Blöcke, " + (settings.commandMode() ? "Befehle" : "wie ein Spieler")
                + " – jede eigene Eingabe hält an, [K] oder /kbau weiter macht weiter)");
        if (!planned.fixes().isEmpty()) {
            say.accept("Ich habe den Plan selbst nachgebessert: " + String.join("; ", planned.fixes().subList(0,
                    Math.min(3, planned.fixes().size()))));
        }
        if (!planned.warnings().isEmpty()) {
            say.accept("Hinweis: " + String.join("; ", planned.warnings().subList(0,
                    Math.min(3, planned.warnings().size()))));
        }
        LOG.info(() -> "bau | start | ziele=" + job.total() + " modus=" + settings.mode + " ungültig=" + job.invalid);
    }

    /**
     * Gedächtnis zum gerade angelegten Bau next: beim Umbau erst das Alte wegräumen (replaceOld), dann den Bau ins
     * Gedächtnis – beim Start wie BuildService.startJob, ein Nachtrag während des Baus kennt ihn schon. Was vorher im
     * Gedächtnis stand, merkt sich next (memoryBefore), damit Rückgängig es zurückholen kann (undoMemory).
     *
     * Statisch und ohne Minecraft-Instanz (worldAt statt mc.level), damit ClientFollowUpTest die Kette aus Bau,
     * Nachtrag und Rückgängig ohne Spielstart prüft. Vorher hing das in begin() und war nur im Spiel prüfbar.
     */
    static void remember(ClientMemory<Map<BlockPos, BlockState>> memory, ClientBuild next, Object world,
            BlockPos feet, Cardinal facing, int shiftX, int shiftZ, ClientPlanner.Planned planned,
            ClientMemory.Entry<Map<BlockPos, BlockState>> mem, java.util.function.Function<BlockPos, BlockState> worldAt) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (ClientBuild.Target t : next.targets) {
            if (!t.state().isAir()) {
                cells.put(t.pos(), t.state());
            }
        }
        String memoryPlan = planned.json();
        if (planned.update() && mem != null) {
            memoryPlan = replaceOld(next.order, next, cells, mem, memoryPlan, worldAt);
        }
        if (memoryPlan != null) {
            next.memoryBefore = memory.remember(new ClientMemory.Entry<>(world, feet.getX(), feet.getY(), feet.getZ(),
                    facing, shiftX, shiftZ, memoryPlan, next.order, memory.now(), cells));
            next.remembered = true;
        }
    }

    /**
     * Rückgängig eines Baus setzt das Gedächtnis auf den Stand vor diesem Bau – bzw. vergisst, wenn es davor keinen
     * gab. Vorher blieb der zurückgebaute Bau im Gedächtnis: Der nächste Nachtrag bekam dessen Plan und räumte dessen
     * Blöcke weg, die gar nicht mehr standen (Gegenprobe des Dirigenten 2026-11-21). Das Plugin löscht sein Gedächtnis
     * beim Rückgängig ebenfalls nicht – dort offen, hier behoben.
     */
    static void undoMemory(ClientMemory<Map<BlockPos, BlockState>> memory, ClientBuild undone) {
        if (undone.remembered) {
            memory.restore(undone.memoryBefore);
        }
    }

    /**
     * Umbau (mode "update"): Der neue Plan ist der vollständige Bau. Was der alte gesetzt hat und der neue nicht mehr
     * enthält, wird zuerst weggeräumt – von oben nach unten, nur wo noch derselbe Block steht. Wie BuildService.startJob,
     * samt UpdateGuard: Kürzt das Modell den alten Bau, ohne dass der Auftrag Wegnehmen verlangt, bleibt der alte Bau
     * stehen und das Gedächtnis kennt beide Pläne. Rückgängig des Umbaus stellt die weggeräumten Blöcke wieder her –
     * sie sind Ziele des Umbaus und landen wie jeder überschriebene Block in originals (PlayerPlacer/CommandPlacer).
     *
     * @return Plan fürs Gedächtnis (bei einem Anbau alter und neuer vereint)
     */
    private static String replaceOld(String prompt, ClientBuild next, Map<BlockPos, BlockState> cells,
            ClientMemory.Entry<Map<BlockPos, BlockState>> mem, String plan,
            java.util.function.Function<BlockPos, BlockState> worldAt) {
        List<BlockPos> gone = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : mem.cells().entrySet()) {
            if (!cells.containsKey(e.getKey()) && worldAt.apply(e.getKey()).equals(e.getValue())) {
                gone.add(e.getKey());
            }
        }
        if (UpdateGuard.keepOld(prompt, mem.cells().size(), gone.size())) {
            int kept = gone.size();
            LOG.info(() -> "bau | änderung_als_anbau | nicht_weggeräumt=" + kept + " alt=" + mem.cells().size());
            for (BlockPos p : gone) {
                cells.put(p, mem.cells().get(p));
            }
            try {
                return UpdateGuard.merge(mem.planJson(), plan);
            } catch (RuntimeException e) {
                // Bewusst fail-open wie im Plugin: Das Gedächtnis ist Komfort, ohne Vereinigung kennt es den neuen Teil.
                LOG.log(Level.WARNING, "bau | gedächtnis_vereinigen_fehlgeschlagen", e);
                return plan;
            }
        }
        gone.sort(java.util.Comparator.comparingInt((BlockPos p) -> p.getY()).reversed());
        List<ClientBuild.Target> removal = new ArrayList<>(gone.size());
        for (BlockPos p : gone) {
            removal.add(new ClientBuild.Target(p, Blocks.AIR.defaultBlockState(), false));
        }
        next.targets.addAll(0, removal);
        LOG.info(() -> "bau | änderung | weggeräumt=" + removal.size());
        return plan;
    }

    // ---------------------------------------------------------------- Steuerung

    void pauseOrResume() {
        if (job == null || !job.active()) {
            say.accept("Gerade läuft kein Bau.");
        } else if (job.state == ClientBuild.State.PAUSED) {
            resume();
        } else {
            job.pause("angehalten");
            say.accept("Pausiert. [K] oder /kbau weiter macht weiter.");
        }
    }

    void resume() {
        if (job != null && job.state == ClientBuild.State.PAUSED) {
            job.resume();  // rechnet die Pause aus der Bauzeit heraus (ClientBuild.activeSeconds)
            say.accept("Weiter geht's (" + job.next + "/" + job.total() + ").");
        }
    }

    void stop() {
        if (planning) {
            // Vorher: „Gerade läuft kein Bau.“ – und der Plan wurde Minuten später trotzdem gebaut (PlanTicket).
            tickets.cancel();
            if (planningRequest != null) {
                planningRequest.cancel();
            }
            planning = false;
            say.accept("Planung abgebrochen – der Host hört auf zu rechnen.");
            results.send(planningPrompt, "abgebrochen", "während der Planung", 0);
            return;
        }
        if (job == null || !job.active()) {
            say.accept("Gerade läuft kein Bau.");
            return;
        }
        job.state = ClientBuild.State.CANCELLED;
        lastFinished = job;
        if (job.originals.isEmpty()) {
            // Abgebrochen, bevor ein Block stand: Rückgängig verweigert diesen Bau (undo, originals leer), also darf er
            // auch nicht im Gedächtnis bleiben. Vorher plante der nächste Nachtrag auf einem Bau, den es nicht gibt –
            // und räumte bei mode "update" womöglich den Bau davor weg. Wer das entfernt, hat das wieder.
            undoMemory(memory, job);
        }
        say.accept("Gestoppt bei " + job.next + "/" + job.total() + ". /kbau rückgängig räumt weg, was gesetzt wurde.");
        results.send(job.order, "abgebrochen", job.next + " von " + job.total() + " Schritten", job.placed);
    }

    void undo(Minecraft mc) {
        undo(mc.level);
    }

    /** level nur als Identität (sameWorld) – so prüft ClientBuilderMemoryTest Rückgängig ohne Spielstart. */
    void undo(Object level) {
        if (job != null && job.active()) {
            say.accept("Erst /kbau stop – rückgängig geht nach dem Bau.");
            return;
        }
        if (lastFinished == null || lastFinished.originals.isEmpty() || lastFinished.undo) {
            say.accept("Ich habe nichts, was ich zurückbauen könnte.");
            return;
        }
        if (!ClientBuild.sameWorld(lastFinished, level)) {
            // Die Originale gelten nur in der Welt des Baus. Vorher schrieb rückgängig sie an dieselben Koordinaten
            // der neuen Welt (Nether, fremder Server) und riss dort fremde Blöcke ab.
            say.accept("Der letzte Bau steht in einer anderen Welt – rückgängig geht nur dort.");
            return;
        }
        job = lastFinished.undoBuild();
        undoMemory(memory, lastFinished);
        lastFinished = null;
        say.accept("Ich räume " + job.total() + " Stellen zurück.");
    }

    String status() {
        if (planning) {
            return "plant (wartet auf den Host)";
        }
        if (job == null || !job.active()) {
            return "bereit" + (lastFinished != null && !lastFinished.undo ? ", /kbau rückgängig möglich" : "");
        }
        return (job.state == ClientBuild.State.PAUSED ? "pausiert (" + job.pauseReason + ")" : "baut") + ": "
                + job.next + "/" + job.total() + ", " + job.placed + " gesetzt, " + job.skipped + " ausgelassen";
    }

    // ---------------------------------------------------------------- Tick

    void tick(Minecraft mc) {
        String taken = control.takenOver(mc);
        if (job == null || !job.active()) {
            return;
        }
        if (!ClientBuild.sameWorld(job, mc.level)) {
            // Welt verlassen, Dimension oder Server gewechselt: Die Ziele sind absolute Koordinaten der alten Welt.
            // Vorher kehrte tick() bei mc.level == null nur zurück, der Bau blieb aktiv und lief in der neuen Welt
            // weiter. Wer das zurückdreht, baut im Nether bzw. auf einem fremden Server.
            job.state = ClientBuild.State.CANCELLED;
            lastFinished = null;
            if (mc.player != null) {
                player.release(mc.player);
            }
            say.accept("Welt gewechselt – Bau abgebrochen.");
            results.send(job.order, "abgebrochen", "welt_gewechselt", job.placed);
            return;
        }
        if (mc.player == null) {
            return;
        }
        if (taken != null && job.state != ClientBuild.State.PAUSED) {
            job.pause(taken);
            player.release(mc.player);
            say.accept("Pausiert – du hast die Kontrolle (" + taken + "). [K] oder /kbau weiter macht weiter.");
            return;
        }
        // Aktionsleiste alle 0,5 s: Stand, Prozent, Restzeit bzw. Pausengrund (ClientBuild.progressLine).
        // Modul „aktionsleiste“ (ClientModules): je Tick gelesen, wirkt sofort.
        if (++hudTicks % 10 == 0 && settings.progressBar) {
            // Aktionsleiste: 26.x sendOverlayMessage (vorher displayClientMessage(…, true)).
            mc.player.sendOverlayMessage(Component.literal(ClientBuild.progressLine(job.next, job.total(),
                    job.passSeconds(), job.state == ClientBuild.State.PAUSED ? job.pauseReason : null)));
        }
        if (job.state == ClientBuild.State.PAUSED) {
            return;
        }
        if (job.state == ClientBuild.State.RUNNING) {
            // Alle 10 s Stand ins Log: Ohne sah ein langsamer Bau von außen wie ein hängender aus (Spieltest 2026-10-31).
            if (++progressTicks % 200 == 0 && job.current() != null) {
                ClientBuild.Target cur = job.current();
                LOG.info(() -> String.format("bau | stand | %d/%d gesetzt=%d ausgelassen=%d ziel=%s spieler=%s",
                        job.next, job.total(), job.placed, job.skipped, cur.pos().toShortString(),
                        mc.player.blockPosition().toShortString()));
            }
            if (job.current() == null) {
                job.state = ClientBuild.State.VERIFYING;
                verifyWait = 10;
                return;
            }
            try {
                if (settings.commandMode()) {
                    commands.tick(mc, job);
                } else if (!player.tick(mc, job)) {
                    job.pause("kein Kreativmodus");
                    say.accept("Pausiert: Die Spieler-Bauweise braucht den Kreativmodus.");
                }
            } catch (RuntimeException e) {
                // Thread-Grenze des Baus: Ein Fehler in einem Ziel darf das Spiel nicht beenden – pausieren, melden.
                LOG.log(Level.WARNING, "bau | fehler | ziel=" + job.next, e);
                job.pause("Fehler");
                say.accept("Pausiert wegen eines Fehlers: " + e + " – /kbau weiter versucht es erneut.");
            }
            return;
        }
        if (--verifyWait > 0) {
            return;
        }
        verifyPass(mc.level::getBlockState);
    }

    /**
     * Prüfdurchgang nach dem letzten Ziel: Abweichungen einmal nachbauen, danach DONE und lastFinished. Aus tick()
     * gelöst und ohne Minecraft-Instanz (worldAt statt mc.level), damit ClientBuilderMemoryTest einen Bau bis zum
     * Rückgängig über den echten Weg führt statt lastFinished von Hand zu setzen.
     */
    void verifyPass(java.util.function.Function<BlockPos, BlockState> worldAt) {
        List<ClientBuild.Target> wrong = new ArrayList<>();
        for (ClientBuild.Target t : job.targets) {
            var now = worldAt.apply(t.pos());
            if (!ClientBuild.sameIntent(now, t.state()) && !(t.companion() && now.is(t.state().getBlock()))) {
                wrong.add(t);
            }
        }
        if (!wrong.isEmpty()) {
            // Beispiele ins Log: Ohne sie war „1185 Stellen stimmen nicht“ (Spieltest 2026-10-31) nicht zu deuten.
            StringBuilder sample = new StringBuilder();
            for (ClientBuild.Target t : wrong.subList(0, Math.min(6, wrong.size()))) {
                sample.append(' ').append(t.pos().toShortString()).append(" soll=")
                        .append(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(t.state()))
                        .append(" ist=").append(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(
                                worldAt.apply(t.pos())));
            }
            LOG.info(() -> "bau | abweichungen | anzahl=" + wrong.size() + " durchgang=" + job.passes + sample);
        }
        if (!wrong.isEmpty() && job.passes == 0) {
            job.passes++;
            job.targets.clear();
            job.targets.addAll(wrong);
            job.next = 0;
            job.startPass();  // Restzeit misst den Nachbesserungsdurchgang für sich
            job.state = ClientBuild.State.RUNNING;
            LOG.info(() -> "bau | nachbessern | stellen=" + wrong.size());
            return;
        }
        job.skipped = wrong.size();
        job.state = ClientBuild.State.DONE;
        lastFinished = job;
        long secs = Math.round(job.activeSeconds());
        say.accept((job.undo ? "Zurückgebaut" : "Fertig") + "! " + job.placed + " Blöcke in " + secs + " s"
                + (job.skipped > 0 ? ", " + job.skipped + " Stellen stimmen nicht (z. B. Wasser in der "
                        + "Spieler-Bauweise)" : "") + (job.undo ? "." : ". Gefällt's dir nicht? /kbau rückgängig"));
        if (!job.undo) {
            results.send(job.order, "gebaut", job.skipped > 0 ? job.skipped + " Stellen ausgelassen" : "",
                    job.placed, job.fixes, job.rounds);
        }
        LOG.info(() -> "bau | ende | gesetzt=" + job.placed + " ausgelassen=" + job.skipped + " dauer_s=" + secs);
    }
}
