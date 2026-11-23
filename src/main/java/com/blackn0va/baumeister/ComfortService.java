package com.blackn0va.baumeister;

import com.blackn0va.baumeister.BuildJob.UndoRecord;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bukkit-Seite des Komforts: Warteschlange, Bauvorschau und Rückgängig über Neustart – Befehle, Timer, Meldungen und
 * die Verdrahtung mit BuildService.
 *
 * Vorher stand das als angehängter Abschnitt (~370 Zeilen) in BuildService, einer Knotendatei, die parallele Pakete
 * ebenfalls ändern. Jetzt gibt BuildService nur schmale Haken frei: request, startConfirmed, busy, adoptUndo, und an
 * pushUndo/trimUndo/undo je eine Zeile. Die Entscheidungen selbst trifft ComfortFlow, ohne Bukkit und getestet. Hier
 * stehen nur Fakten (online, Prüfsumme, Ort) und das Ausführen.
 *
 * Threads: Hauptthread; nur UndoJournal schreibt und prüft im eigenen Thread. Fehler: Takte fangen an der
 * Scheduler-Grenze (Warteschlange loggt SEVERE und läuft weiter, Partikel sind fail-open).
 */
final class ComfortService implements ComfortFlow.Port<BuildService.Request, ComfortService.Planned> {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Fertig geplanter, noch nicht gebauter Bau – was startJob zum Bauen braucht. */
    /**
     * fixes/rounds: Nachbesserungen und LLM-Runden des Plans für die Ergebnis-Meldung an die App. Vorher gingen sie
     * mit der Vorschau verloren (BuildService.startJob leert den Merker), nach „ja“ meldete die App „ohne
     * Nachbesserung“.
     */
    record Planned(CompiledBuild build, String planJson, boolean update, List<String> fixes, int rounds) {
    }

    private final BaumeisterPlugin plugin;
    private final BuildService service;
    private final BuilderNpc npc;
    private final Settings settings;
    private final ComfortSettings comfort;
    private final ComfortFlow<BuildService.Request, Planned> flow;
    private final UndoJournal journal;
    private BukkitTask queueTask;
    private BukkitTask previewTask;

    ComfortService(BaumeisterPlugin plugin, BuildService service, BuilderNpc npc, Settings settings) {
        this.plugin = plugin;
        this.service = service;
        this.npc = npc;
        this.settings = settings;
        this.comfort = ComfortSettings.from(plugin.getConfig());
        this.flow = new ComfortFlow<>(this, comfort.queueMax(), comfort.queuePerPlayer(),
                comfort.previewSeconds() * 1000L, System::currentTimeMillis);
        this.journal = new UndoJournal(plugin.getDataFolder().toPath().resolve(UndoJournal.DIR),
                comfort.undoAcrossRestart());
    }

    // ---------------------------------------------------------------- Port (Fakten für ComfortFlow)

    @Override
    public boolean busy() {
        return service.busy();
    }

    @Override
    public UUID owner(BuildService.Request r) {
        return r.ownerId();
    }

    @Override
    public String ownerName(BuildService.Request r) {
        return r.ownerName();
    }

    @Override
    public String prompt(BuildService.Request r) {
        return Texts.clean(r.prompt(), BuildService.MAX_PROMPT);
    }

    @Override
    public BuildService.Request refresh(BuildService.Request r) {
        if (!(r.sender() instanceof Player)) {
            return r;
        }
        Player now = r.ownerId() == null ? null : Bukkit.getPlayer(r.ownerId());
        return now == null ? null : r.withSender(now);
    }

    @Override
    public void plan(BuildService.Request r, boolean preview) {
        service.request(r.withPreview(preview));
    }

    @Override
    public long fingerprint(BuildService.Request r) {
        // Ohne Rahmen gibt es keine Vorschau (divert verlangt ihn); UNREADABLE passt dann zu keiner Prüfsumme.
        // Ungeladene Chunks liest ofLoaded nicht (AK-8) – geladen hat sie vorher loadSite.
        SiteFrame f = r.frame();
        return f == null ? SiteFingerprint.UNREADABLE
                : SiteFingerprint.ofLoaded(ChunkPrefetch.of(plugin, f.world()), f.scanChunks(), SiteFingerprint.ids(f));
    }

    @Override
    public void loadSite(BuildService.Request r, Runnable then) {
        SiteFrame f = r.frame();
        if (f == null) {
            then.run();
            return;
        }
        SiteFingerprint.whenLoaded(ChunkPrefetch.of(plugin, f.world()), f.scanChunks(), then);
    }

    @Override
    public void previewOffered(BuildService.Request r) {
        startPreviewTimer();
    }

    @Override
    public void build(BuildService.Request r, Planned p) {
        service.startConfirmed(r, p.build(), p.planJson(), p.update(), p.fixes(), p.rounds());
    }

    @Override
    public void tell(BuildService.Request r, String text) {
        Texts.info(r.sender(), text);
    }

    @Override
    public String where(BuildService.Request r) {
        Location f = r.feet();
        return "bei " + f.getBlockX() + " / " + f.getBlockY() + " / " + f.getBlockZ()
                + (f.getWorld() == null ? "" : " in " + f.getWorld().getName());
    }

    // ---------------------------------------------------------------- Haken für BuildService

    /** Beschäftigt: einreihen. false = Warteschlange aus, BuildService antwortet wie früher. */
    boolean enqueue(BuildService.Request r) {
        if (!flow.enqueue(r, r.previewOnly())) {
            return false;
        }
        if (!flow.queue().isEmpty()) {
            startQueueTimer();
        }
        return true;
    }

    /**
     * Aus startJob, nach der Verschiebungsrechnung: true = nur Vorschau (nicht bauen). Box und Prüfsumme werden nur
     * für eine Vorschau ausgerechnet.
     */
    boolean divert(BuildService.Request r, CompiledBuild build, String planJson, boolean update, int shiftX,
            int shiftZ, List<String> fixes, int rounds) {
        boolean shown = flow.divert(r, r.previewOnly(), new Planned(build, planJson, update, List.copyOf(fixes),
                rounds), () -> {
            if (!(r.sender() instanceof Player) || r.frame() == null) {
                return null;
            }
            Block a = r.frame().blockAt(build.min().add(shiftX, 0, shiftZ));
            Block b = r.frame().blockAt(build.max().add(shiftX, 0, shiftZ));
            return new ComfortFlow.Shown(a.getWorld().getName(), BuildPreview.Box.of(a.getX(), a.getY(), a.getZ(),
                    b.getX(), b.getY(), b.getZ()), build.blockCount(), fingerprint(r));
        });
        if (shown) {
            // Den Partikel-Takt startet previewOffered – die Vorschau entsteht erst, wenn der Bauplatz geladen ist.
            npc.status("bereit");
        }
        return shown;
    }

    String statusSuffix() {
        return flow.statusSuffix();
    }

    /** Wartet kein Auftrag? Bewohner bauen nur dann (BuildService.startTown: Spieler gehen vor). */
    boolean queueEmpty() {
        return flow.queue().isEmpty();
    }

    // ---------------------------------------------------------------- Warteschlange

    private void startQueueTimer() {
        if (queueTask == null && plugin.isEnabled()) {
            queueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pumpGuarded, 20L, 20L);
        }
    }

    /** Scheduler-Grenze: Ein Fehler beim Starten eines Auftrags darf die übrigen nicht festhalten. */
    private void pumpGuarded() {
        try {
            flow.pump();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "warteschlange | fehler", e);
        }
        if (flow.queue().isEmpty() && queueTask != null) {
            queueTask.cancel();
            queueTask = null;
        }
    }

    /**
     * „stopp“ (BuildService.stop, Vorfall 2026-11-16): die eigenen wartenden Aufträge streichen – vorher rückte nach
     * einem Stopp sofort der nächste eigene Auftrag nach. Anzahl der gestrichenen.
     */
    int dropQueued(CommandSender sender) {
        UUID me = sender instanceof Player p ? p.getUniqueId() : null;
        int n = flow.queue().cancelAll(me).size();
        if (n > 0) {
            LOG.info(() -> "warteschlange | gestoppt | von=" + sender.getName() + " anzahl=" + n);
        }
        return n;
    }

    /** /bm warteschlange [abbrechen [platz|alle]]. */
    void queueCommand(CommandSender sender, String[] args) {
        OrderQueue<ComfortFlow.Queued<BuildService.Request, Planned>> queue = flow.queue();
        UUID me = sender instanceof Player p ? p.getUniqueId() : null;
        boolean admin = sender.hasPermission("baumeister.admin");
        if (args.length >= 2 && List.of("abbrechen", "weg", "cancel").contains(args[1].toLowerCase(Locale.ROOT))) {
            String what = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
            if (what.equals("alle")) {
                if (!admin) {
                    Texts.info(sender, "Alle Aufträge abbrechen dürfen nur Admins – ohne Zusatz nimmst du deine heraus.");
                    return;
                }
                int n = queue.clear().size();
                LOG.info(() -> "warteschlange | geleert | von=" + sender.getName() + " anzahl=" + n);
                Texts.info(sender, n + (n == 1 ? " Auftrag" : " Aufträge") + " aus der Warteschlange genommen.");
                return;
            }
            if (!what.isEmpty()) {
                int pos;
                try {
                    pos = Integer.parseInt(what);
                } catch (NumberFormatException e) {
                    Texts.info(sender, "/bm warteschlange abbrechen [platz" + (admin ? "|alle" : "") + "]");
                    return;
                }
                OrderQueue.Entry<ComfortFlow.Queued<BuildService.Request, Planned>> gone = queue.cancelAt(pos, me,
                        admin);
                Texts.info(sender, gone == null ? "Auf Platz " + pos + " steht kein Auftrag von dir."
                        : "Abgebrochen: " + gone.label() + " (" + gone.ownerName() + ")");
                if (gone != null) {
                    LOG.info(() -> "warteschlange | abgebrochen | von=" + sender.getName() + " besitzer="
                            + gone.ownerName());
                }
                return;
            }
            int n = queue.cancelAll(me).size();
            Texts.info(sender, n == 0 ? "Du hast nichts in der Warteschlange."
                    : n + (n == 1 ? " Auftrag" : " Aufträge") + " abgebrochen.");
            return;
        }
        Texts.info(sender, "Gerade: " + service.statusLine());
        for (String line : queue.describe(me, 10)) {
            Texts.info(sender, line);
        }
    }

    // ---------------------------------------------------------------- Bauvorschau

    /** Wie requestFromPlayer, aber der Auftrag trägt previewOnly: planen und den Umriss zeigen, nicht bauen. */
    void requestPreview(Player p, String prompt) {
        Location feet = p.getLocation().getBlock().getLocation();
        service.request(new BuildService.Request(p, p.getUniqueId(), p.getName(), settings.checkPermissions(), feet,
                Cardinal.fromYaw(p.getLocation().getYaw()), prompt, null, null, null, true));
    }

    /**
     * Chat: „zeig mir …“ plant eine Vorschau; bei offener Vorschau bestätigt „ja/bau“ und verwirft „nein“. true =
     * erledigt. Ohne offene Vorschau gehen „ja“, „bau“ … wie bisher weiter (Auftrag ans LLM).
     */
    boolean previewChat(Player p, String prompt) {
        String show = BuildPreview.showRequest(prompt);
        if (show != null) {
            requestPreview(p, show);
            return true;
        }
        if (!flow.previews().has(p.getUniqueId())) {
            return false;
        }
        if (BuildPreview.confirms(prompt)) {
            confirm(p);
            return true;
        }
        if (BuildPreview.discards(prompt)) {
            discard(p);
            return true;
        }
        return false;
    }

    /** /bm vorschau [ja|nein|&lt;auftrag&gt;]. */
    void previewCommand(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            Texts.info(sender, "Die Vorschau gibt es nur im Spiel – sie zeigt dir den Umriss mit Partikeln.");
            return;
        }
        if (args.length < 2) {
            long left = flow.previews().secondsLeft(p.getUniqueId());
            Texts.info(sender, left > 0 ? "Deine Vorschau gilt noch " + left + " s: /" + label + " vorschau ja baut, /"
                    + label + " vorschau nein verwirft sie."
                    : "/" + label + " vorschau <auftrag> – oder im Chat: \"" + npc.name() + ", zeig mir …\"");
            return;
        }
        String rest = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String word = rest.trim().toLowerCase(Locale.ROOT);
        if (word.equals("ja") || word.equals("bau") || word.equals("bestätigen")) {
            confirm(p);
        } else if (word.equals("nein") || word.equals("verwerfen") || word.equals("weg")) {
            discard(p);
        } else {
            requestPreview(p, rest);
        }
    }

    private void confirm(Player p) {
        ComfortFlow.Outcome o = flow.confirm(p.getUniqueId());
        if (o == ComfortFlow.Outcome.NONE) {
            Texts.info(p, "Es liegt keine Vorschau bereit (oder sie ist verfallen). Neu: /bm vorschau <auftrag>");
        } else if (o == ComfortFlow.Outcome.QUEUED) {
            startQueueTimer();
        }
    }

    private void discard(Player p) {
        Texts.info(p, flow.discard(p.getUniqueId()) ? "Vorschau verworfen – nichts gebaut."
                : "Es liegt keine Vorschau bereit.");
    }

    private void startPreviewTimer() {
        if (previewTask == null && plugin.isEnabled()) {
            previewTask = Bukkit.getScheduler().runTaskTimer(plugin, this::previewTickGuarded, 1L, 10L);
        }
    }

    /** Scheduler-Grenze: Partikel sind Komfort – ein Fehler schaltet nur die Anzeige ab (fail-open), nie den Bau. */
    private void previewTickGuarded() {
        try {
            PreviewDisplay.tick(flow.previews());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "vorschau | anzeige_fehler", e);
        }
        if (flow.previews().isEmpty() && previewTask != null) {
            previewTask.cancel();
            previewTask = null;
        }
    }

    // ---------------------------------------------------------------- Rückgängig über Neustart

    /** Eintrag auf die Platte (UndoJournal): Ein Spool bekommt den Zettel, ein Speicher-Eintrag eine Kopie. */
    void persistUndo(UndoRecord rec) {
        if (!journal.enabled() || rec.world() == null) {
            return;
        }
        UndoJournal.Manifest m = UndoJournal.manifestOf(rec);
        if (rec.spool() != null) {
            journal.persistSpool(rec.spool(), m);
        } else {
            journal.persistCopy(rec, UndoJournal.snapshot(rec.originals(), rec.placed()), m);
        }
    }

    /** Eintrag ist aus dem Stapel (zurückgesetzt oder herausgefallen): seine Kopie auf der Platte löschen. */
    void forget(UndoRecord rec) {
        journal.forget(rec);
    }

    /**
     * Eintrag dieser Sitzung auf seine fertige Kopie auf der Platte umziehen (UndoStack.offload): derselbe Eintrag,
     * gelesen aus der Kopie, ohne Ursprünge im Speicher. Die Kopie gehört danach dem neuen Eintrag (rekey), damit
     * forget sie weiter findet. Ist sie nicht fertig, kommt rec unverändert zurück.
     */
    UndoRecord onDisk(UndoRecord rec) {
        if (rec.spool() != null || rec.world() == null) {
            return rec;
        }
        java.util.Optional<Path> dir = journal.writtenCopy(rec);
        if (dir.isEmpty()) {
            return rec;
        }
        UndoRecord disk = new UndoRecord(rec.owner(), rec.ownerName(), rec.world(), List.of(), rec.when(),
                UndoSpool.existing(dir.get(), rec.size()));
        journal.rekey(rec, disk);
        return disk;
    }

    /**
     * Nach dem Start: Kandidaten aus UndoJournal.sweep im Hintergrund prüfen und in den Stapel nehmen. Das Ergebnis
     * geht an den Dienst, der dann aktiv ist (nach /bm neuladen ein neuer).
     */
    void resumeUndo(List<Path> candidates) {
        if (!journal.enabled() || candidates.isEmpty()) {
            return;
        }
        journal.loadAsync(candidates, UndoJournal.Limits.of(settings.undoDepth(), comfort), task -> {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        }, loaded -> {
            BuildService now = plugin.service();
            (now != null ? now.comfort() : this).adoptLoaded(loaded);
        });
    }

    /**
     * Geprüfte Einträge hinten an den Stapel (sie sind älter als alles aus dieser Sitzung). Vorher wird jeder Zustand
     * gegen DIESE Serverfassung geprüft: Ein Eintrag lebt bis zu rückgängig-max-tage und übersteht damit auch ein
     * Spigot-Update mit umbenannten Blöcken. Vorher warf Bukkit.createBlockData dann erst beim Zurücksetzen, mitten
     * im Timer und bei jedem Tick an derselben Stelle. Jetzt wird ein solcher Eintrag verworfen und gelöscht, bevor er
     * in den Stapel kommt (fail-closed). Fehlt die Welt (Multiverse-Welt nicht geladen), bleibt der Eintrag liegen,
     * bis er zu alt ist.
     */
    void adoptLoaded(List<UndoJournal.Loaded> loaded) {
        StateCache<BlockData> states = new StateCache<>(Bukkit::createBlockData);
        List<UndoRecord> records = new ArrayList<>();
        for (UndoJournal.Loaded l : journal.keepKnown(loaded, states::knows)) {
            UndoJournal.Manifest m = l.manifest();
            World w = Bukkit.getWorld(m.world());
            if (w == null) {
                LOG.info(() -> "rückgängig | welt_fehlt | welt=" + m.world() + " ordner=" + l.dir().getFileName());
                continue;
            }
            records.add(UndoJournal.toRecord(l, w));
        }
        int stack = service.adoptUndo(records);
        LOG.info(() -> "rückgängig | geladen | einträge=" + records.size() + " stapel=" + stack);
    }

    // ---------------------------------------------------------------- Wiederherstellen beim Start

    /**
     * Einträge aus UndoJournal.RESTORE_DIR (vom Betreiber abgelegt, siehe dort) im Hintergrund prüfen und dann
     * zurücksetzen – wie /bm rückgängig, aber ohne Stapel und ohne Spieler. Unabhängig von „Rückgängig über
     * Neustart“: Der Ordner ist ein Auftrag des Betreibers, keine Kopie des Stapels.
     */
    void restoreAtStart(List<Path> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        journal.loadAsync(candidates, UndoJournal.Limits.of(UndoJournal.RESTORE_MAX_ENTRIES, comfort), task -> {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        }, loaded -> {
            BuildService now = plugin.service();
            (now != null ? now.comfort() : this).applyRestores(loaded);
        });
    }

    /**
     * Hauptthread: geprüfte Einträge der Zeit nach (ältester zuerst) an BuildService.restore. Das setzt einen nach
     * dem anderen zurück (undoQueue), je Zelle nur, wo noch steht, was der Eintrag als gesetzt nennt (UndoCheck), und
     * löscht den Ordner erst, wenn alles durch ist – ein Neustart mittendrin setzt beim nächsten Start fort, schon
     * zurückgesetzte Zellen lässt UndoCheck stehen. Zustände, die diese Serverfassung nicht kennt: Eintrag verworfen
     * (keepKnown, fail-closed). Fehlt die Welt, bleibt der Eintrag liegen, bis sie da ist oder er zu alt ist.
     */
    void applyRestores(List<UndoJournal.Loaded> loaded) {
        StateCache<BlockData> states = new StateCache<>(Bukkit::createBlockData);
        List<UndoJournal.Loaded> known = new ArrayList<>(journal.keepKnown(loaded, states::knows));
        known.sort(java.util.Comparator.comparingLong((UndoJournal.Loaded l) -> l.manifest().when())
                .thenComparing(l -> l.dir().getFileName().toString()));
        for (UndoJournal.Loaded l : known) {
            UndoJournal.Manifest m = l.manifest();
            World w = Bukkit.getWorld(m.world());
            if (w == null) {
                LOG.warning(() -> "wiederherstellen | welt_fehlt | welt=" + m.world() + " ordner="
                        + l.dir().getFileName());
                continue;
            }
            LOG.info(() -> "wiederherstellen | eintrag | ordner=" + l.dir().getFileName() + " name=" + m.ownerName()
                    + " welt=" + m.world() + " blöcke=" + m.count());
            service.restore(Bukkit.getConsoleSender(), UndoJournal.toRecord(l, w));
        }
    }

    // ---------------------------------------------------------------- Neuladen, Ende

    /**
     * /bm neuladen: Warteschlange, Vorschauen und Kopien ziehen in den neuen Dienst um. Das Journal übernimmt den
     * Schreib-Thread des alten, statt auf ihn zu warten (siehe UndoJournal.takeOver).
     */
    void takeOver(ComfortService old) {
        old.stopTimers();
        journal.takeOver(old.journal);
        flow.takeOver(old.flow);
        if (!flow.queue().isEmpty()) {
            startQueueTimer();
        }
        if (!flow.previews().isEmpty()) {
            startPreviewTimer();
        }
    }

    void shutdown() {
        stopTimers();
        // Nach dem Abbruch des Baus: dessen Rückgängig-Eintrag (onJobFinished → pushUndo) ist dann schon unterwegs.
        journal.close();
    }

    private void stopTimers() {
        if (queueTask != null) {
            queueTask.cancel();
            queueTask = null;
        }
        if (previewTask != null) {
            previewTask.cancel();
            previewTask = null;
        }
    }
}
