package com.blackn0va.baumeister.client;

import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.SiteSurvey;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;

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
        BlockPos feet = mc.player.blockPosition();
        Orientation o = new Orientation(Cardinal.fromYaw(mc.player.getYRot()));
        SiteSurvey survey = SiteSurvey.scan(SCAN_MIN, SCAN_MAX, p -> {
            Pos w = o.toWorldOffset(p.add(0, 0, settings.distanceAhead));
            BlockPos at = feet.offset(w.x(), w.y(), w.z());
            if (at.getY() < mc.level.getMinY() || at.getY() > mc.level.getMaxY()) {
                return "air";
            }
            return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(at).getBlock()).getPath();
        });
        try {
            Optional<ClientPlanner.Planned> quick = planner.quick(prompt, survey);
            if (quick.isPresent()) {
                begin(mc, prompt, quick.get(), o, feet, survey);
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
        say.accept("Ich frage den Baumeister-Host …");
        planner.llm(prompt, survey, msg -> mc.execute(() -> {
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
                    begin(mc, prompt, planned, o, feet, survey);
                }));
    }

    private void begin(Minecraft mc, String prompt, ClientPlanner.Planned planned, Orientation o, BlockPos feet,
            SiteSurvey survey) {
        CompiledBuild b = planned.build();
        // Wie BuildService.startJob: ohne Nachbarbauten mittig vor den Spieler, sonst in den Koordinaten des Scans.
        boolean free = !survey.hasStructures();
        int shiftX = free ? -Math.floorDiv(b.min().x() + b.max().x(), 2) : 0;
        int shiftZ = free ? -b.min().z() : 0;
        ClientBuild next = ClientBuild.of(prompt, b, o, feet, settings.distanceAhead, shiftX, shiftZ,
                mc.level.registryAccess().lookupOrThrow(Registries.BLOCK), mc.level);
        if (!settings.commandMode()) {
            // Spieler-Bauweise strikt von unten nach oben (stabil sortiert, innerhalb einer Schicht die Reihenfolge des
            // Compilers). Der Compiler ordnet Abschnitt für Abschnitt – bei der Maschinenhalle stand das Dach vor den
            // Maschinen, und die Figur kam nicht mehr darunter (Spieltest 2026-10-31). Befehle brauchen das nicht:
            // Sie reichen durch Wände.
            next.targets.sort(java.util.Comparator.comparingInt(t -> t.pos().getY()));
        }
        int lowest = next.targets.stream().mapToInt(t -> t.pos().getY()).min().orElse(feet.getY());
        if (lowest <= mc.level.getMinY()) {
            say.accept("Hier geht das nicht so tief – der Bau reicht bis ans Grundgestein. Such dir mehr Boden darunter.");
            results.send(prompt, "abgesagt", "zu tief", 0);
            return;
        }
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
        say.accept("Gestoppt bei " + job.next + "/" + job.total() + ". /kbau rückgängig räumt weg, was gesetzt wurde.");
        results.send(job.order, "abgebrochen", job.next + " von " + job.total() + " Schritten", job.placed);
    }

    void undo(Minecraft mc) {
        if (job != null && job.active()) {
            say.accept("Erst /kbau stop – rückgängig geht nach dem Bau.");
            return;
        }
        if (lastFinished == null || lastFinished.originals.isEmpty() || lastFinished.undo) {
            say.accept("Ich habe nichts, was ich zurückbauen könnte.");
            return;
        }
        if (!ClientBuild.sameWorld(lastFinished, mc.level)) {
            // Die Originale gelten nur in der Welt des Baus. Vorher schrieb rückgängig sie an dieselben Koordinaten
            // der neuen Welt (Nether, fremder Server) und riss dort fremde Blöcke ab.
            say.accept("Der letzte Bau steht in einer anderen Welt – rückgängig geht nur dort.");
            return;
        }
        job = lastFinished.undoBuild();
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
        if (++hudTicks % 10 == 0) {
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
        List<ClientBuild.Target> wrong = new ArrayList<>();
        for (ClientBuild.Target t : job.targets) {
            var now = mc.level.getBlockState(t.pos());
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
                                mc.level.getBlockState(t.pos())));
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
