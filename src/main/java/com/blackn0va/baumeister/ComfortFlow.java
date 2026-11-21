package com.blackn0va.baumeister;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Die Entscheidungen von Warteschlange und Bauvorschau, ohne Bukkit: einreihen oder abweisen, wer als Nächster dran
 * ist, ob ein fertiger Plan nur gezeigt oder gebaut wird, und ob eine bestätigte Vorschau noch gebaut werden darf.
 *
 * Vorher steckte das als rund 370 Zeilen im angehängten Komfort-Abschnitt von BuildService, und gesteuert wurde die
 * Vorschau über einen veränderlichen Dienst-Merker (planOnly). Er wurde in request() gesetzt und erst in startJob
 * verbraucht. Getestet war davon nichts, auch nicht die Zusage „eine Vorschau baut nie“. Jetzt hängt die Vorschau am
 * Auftrag selbst (BuildService.Request.previewOnly). Alle Entscheidungen stehen hier und sind mit Gegenprobe getestet
 * (ComfortFlowTest). Die Bukkit-Seite (ComfortService) liefert nur noch Fakten über den Port: beschäftigt, online,
 * Prüfsumme, Ort. Sie führt außerdem aus, was hier entschieden wird: planen, bauen, melden.
 *
 * Rechte: Die Warteschlange speichert nur den Auftrag, keine Freigabe. Geprüft wird beim Bauen, vor dem ersten Block
 * (BuildJob, fail-closed), also erst, wenn der Auftrag dran ist.
 *
 * Threads: nur Hauptthread. Fehler: keine eigenen; Ausnahmen des Ports laufen durch bis zur Scheduler-Grenze.
 *
 * @param <R> der Auftrag (BuildService.Request)
 * @param <P> der fertige, noch nicht gebaute Plan
 */
final class ComfortFlow<R, P> {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Was die Bukkit-Seite beisteuert. */
    interface Port<R, P> {

        /** Plant oder baut der Baumeister gerade? */
        boolean busy();

        /** Auftraggeber; null = Konsole. */
        UUID owner(R request);

        String ownerName(R request);

        /** Auftragstext, schon bereinigt (Texts.clean). */
        String prompt(R request);

        /**
         * Derselbe Auftrag mit frischem Absender. null, wenn der Auftraggeber nicht mehr online ist; dann fällt der
         * Auftrag weg. Nach Ab- und Anmelden ist das alte Player-Objekt tot, Meldungen an es kämen nie an.
         */
        R refresh(R request);

        /** Neu planen lassen; preview = nur zeigen, nicht bauen. */
        void plan(R request, boolean preview);

        /**
         * Prüfsumme des Bauplatzes jetzt (SiteFingerprint.ofLoaded); SiteFingerprint.UNREADABLE, wenn er nicht
         * geladen ist.
         */
        long fingerprint(R request);

        /**
         * Bauplatz vorladen (SiteFingerprint.whenLoaded), dann then auf dem Server-Thread – sofort, wenn er schon
         * geladen ist. Ohne Bauplatz (kein Rahmen) sofort.
         */
        void loadSite(R request, Runnable then);

        /** Eine Vorschau ist angelegt (Partikel-Takt starten). */
        void previewOffered(R request);

        /** Bestätigten Plan bauen. Die Rechteprüfung läuft dort wie bei jedem Bau vor dem ersten Block. */
        void build(R request, P plan);

        void tell(R request, String text);

        /** Wo der Auftrag baut, für Meldungen („bei 120 / 64 / -30“). */
        String where(R request);
    }

    /** Ein eingereihter Auftrag: neu planen (preview = nur zeigen) oder eine bestätigte Vorschau (confirmed). */
    record Queued<R, P>(R request, boolean preview, Confirmable<R, P> confirmed) {
    }

    /** Geplante, noch nicht gebaute Vorschau; fingerprint = Bauplatz beim Zeigen. */
    record Confirmable<R, P>(R request, P plan, long fingerprint) {
    }

    /** Was beim Zeigen feststeht; erst berechnet, wenn es eine Vorschau wird (die Prüfsumme liest ~50 000 Blöcke). */
    record Shown(String world, BuildPreview.Box box, int blocks, long fingerprint) {
    }

    enum Outcome {
        /** Keine (gültige) Vorschau da. */
        NONE,
        /** Gebaut. */
        BUILT,
        /** Bauplatz verändert, nicht gebaut. */
        CHANGED,
        /** Bestätigte Vorschau in die Warteschlange gestellt. */
        QUEUED,
        /** Beschäftigt und nicht einreihbar; die Vorschau bleibt offen. */
        KEPT,
        /** Bauplatz wird erst geladen; danach wird verglichen und gebaut (oder abgelehnt). */
        LOADING,
        /** Bauplatz ließ sich nicht laden – fail-closed nicht gebaut. */
        UNREADABLE
    }

    private final Port<R, P> port;
    private final OrderQueue<Queued<R, P>> queue;
    private final BuildPreview<Confirmable<R, P>> previews;
    private final long previewMillis;
    /** Wie viele Vorschauen bzw. „ja“ gerade auf ihren Bauplatz warten; solange rückt niemand nach (pump). */
    private int loading;

    ComfortFlow(Port<R, P> port, int queueMax, int queuePerOwner, long previewMillis, LongSupplier clockMillis) {
        this.port = port;
        this.queue = new OrderQueue<>(queueMax, queuePerOwner);
        this.previews = new BuildPreview<>(clockMillis);
        this.previewMillis = previewMillis;
    }

    OrderQueue<Queued<R, P>> queue() {
        return queue;
    }

    BuildPreview<Confirmable<R, P>> previews() {
        return previews;
    }

    long previewSeconds() {
        return previewMillis / 1000;
    }

    // ---------------------------------------------------------------- Warteschlange

    /**
     * Auftrag einreihen statt abweisen. false = Warteschlange aus (komfort.warteschlange-max 0) oder Auftrag leer –
     * dann antwortet der Aufrufer wie früher („Ich baue gerade noch.“). true = erledigt, eingereiht oder mit Grund
     * abgelehnt.
     */
    boolean enqueue(R r, boolean preview) {
        if (!queue.enabled()) {
            return false;
        }
        String prompt = port.prompt(r);
        if (prompt.length() < 3) {
            return false;
        }
        String label = (preview ? "Vorschau: " : "") + Texts.clean(prompt, 60);
        int pos = queue.offer(port.owner(r), port.ownerName(r), label, new Queued<>(r, preview, null));
        if (pos < 0) {
            refuse(r, pos, "");
        } else {
            queued(r, pos, label);
        }
        return true;
    }

    private void refuse(R r, int pos, String tail) {
        port.tell(r, (pos == OrderQueue.FULL
                ? "Ich bin beschäftigt, und die Warteschlange ist voll (" + queue.max() + " Aufträge) – versuch es "
                        + "später noch einmal."
                : "Du hast schon " + queue.perOwner() + " Aufträge in der Warteschlange. /bm warteschlange "
                        + "abbrechen nimmt sie heraus.") + tail);
    }

    /**
     * Eingereiht. Der Ort steht in der Meldung: Gebaut wird dort, wo der Auftrag gegeben wurde, auch wenn der Spieler
     * bis zum Drankommen längst woanders ist. Vorher sagte erst der fertige Bau, wo er steht.
     */
    private void queued(R r, int pos, String label) {
        LOG.info(() -> String.format("warteschlange | eingereiht | besitzer=%s platz=%d auftrag=%s",
                port.ownerName(r), pos, label));
        port.tell(r, "Ich bin gerade beschäftigt – dein Auftrag steht auf Platz " + pos + " der Warteschlange. "
                + "Gebaut wird " + port.where(r) + ", wo du ihn gegeben hast. /bm warteschlange zeigt sie, "
                + "/bm warteschlange abbrechen nimmt deine heraus.");
    }

    /**
     * Sobald der Baumeister frei ist: nächster Auftrag. Aufträge eines inzwischen abgemeldeten Spielers fallen weg.
     * BuildJob bräche ohne ihn ohnehin vor dem ersten Block ab (fail-closed), aber erst nach minutenlangem Planen.
     * Ein Auftrag, der sofort scheitert (zu kurz), lässt den Baumeister frei; dann kommt im selben Durchgang der
     * nächste.
     */
    void pump() {
        while (!busy() && !queue.isEmpty()) {
            OrderQueue.Entry<Queued<R, P>> e = queue.poll();
            Queued<R, P> q = e.order();
            R r = port.refresh(q.request());
            if (r == null) {
                LOG.info(() -> String.format("warteschlange | verworfen_offline | besitzer=%s auftrag=%s",
                        e.ownerName(), e.label()));
                continue;
            }
            LOG.info(() -> String.format("warteschlange | dran | besitzer=%s auftrag=%s rest=%d", e.ownerName(),
                    e.label(), queue.size()));
            port.tell(r, "Jetzt bist du dran: " + e.label() + " – ich baue " + port.where(r) + ".");
            if (q.confirmed() != null) {
                Confirmable<R, P> c = q.confirmed();
                startConfirmed(new Confirmable<>(r, c.plan(), c.fingerprint()));
            } else {
                port.plan(r, q.preview());
            }
        }
    }

    /** „, 2 Aufträge in der Warteschlange“ für die Statuszeile; leer ohne Warteschlange. */
    String statusSuffix() {
        int n = queue.size();
        return n == 0 ? "" : ", " + n + (n == 1 ? " Auftrag" : " Aufträge") + " in der Warteschlange";
    }

    // ---------------------------------------------------------------- Bauvorschau

    /**
     * Fertiger Plan: true = nur zeigen (Vorschau angelegt) – der Aufrufer baut dann NICHT. false = bauen. Das ist das
     * Tor, an dem eine Vorschau nie zum Bau werden darf. Ohne Spieler (Konsole) gibt es keine Partikel; dann wird
     * nichts gezeigt und trotzdem nichts gebaut (shown liefert null).
     */
    boolean divert(R r, boolean previewOnly, P plan, Supplier<Shown> shown) {
        if (!previewOnly) {
            return false;
        }
        if (port.owner(r) == null) {
            port.tell(r, "Eine Vorschau gibt es nur im Spiel – nichts gebaut.");
            return true;
        }
        // Die Prüfsumme beim Zeigen liest den ganzen Bauplatz – erst laden (AK-8). Nach Minuten LLM-Zeit kann der
        // Spieler längst weitergegangen sein.
        whenSiteLoaded(r, () -> offer(r, plan, shown));
        return true;
    }

    private void offer(R r, P plan, Supplier<Shown> shown) {
        Shown s = shown.get();
        if (s == null) {
            port.tell(r, "Eine Vorschau gibt es nur im Spiel – nichts gebaut.");
            return;
        }
        if (s.fingerprint() == SiteFingerprint.UNREADABLE) {
            LOG.warning(() -> "vorschau | bauplatz_nicht_geladen | besitzer=" + port.ownerName(r));
            port.tell(r, "Den Bauplatz konnte ich nicht laden – ohne Prüfung zeige ich keine Vorschau. Versuch es "
                    + "noch einmal: /bm vorschau <auftrag>");
            return;
        }
        previews.offer(port.owner(r), s.world(), s.box(), s.blocks(), new Confirmable<>(r, plan, s.fingerprint()),
                previewMillis);
        BuildPreview.Box box = s.box();
        LOG.info(() -> String.format("vorschau | gezeigt | besitzer=%s größe=%dx%dx%d blöcke=%d", port.ownerName(r),
                box.sizeX(), box.sizeY(), box.sizeZ(), s.blocks()));
        port.tell(r, String.format("Vorschau: %d × %d × %d Blöcke (%,d Blöcke), Umriss in Violett. Sag \"ja\" (oder "
                + "/bm vorschau ja), dann baue ich – \"nein\" verwirft sie, sonst verfällt sie in %d s.", box.sizeX(),
                box.sizeY(), box.sizeZ(), s.blocks(), previewSeconds()));
        port.previewOffered(r);
    }

    /**
     * „ja“: Vorschau bauen. Herausgenommen wird sie erst, wenn feststeht, dass sie gebaut oder eingereiht wird. Vorher
     * nahm confirmPreview sie zuerst heraus; war der Baumeister beschäftigt und die Warteschlange aus, voll oder der
     * Spieler am Limit, war die fertig geplante Vorschau (Minuten LLM-Zeit) weg, und die Meldung sprach bei
     * warteschlange-max 0 von einer vollen Warteschlange mit 0 Aufträgen.
     */
    Outcome confirm(UUID owner) {
        BuildPreview.Pending<Confirmable<R, P>> pending = previews.peek(owner);
        if (pending == null) {
            return Outcome.NONE;
        }
        Confirmable<R, P> c = pending.plan();
        R r = port.refresh(c.request());
        if (r == null) {
            return Outcome.NONE;
        }
        Confirmable<R, P> fresh = new Confirmable<>(r, c.plan(), c.fingerprint());
        if (busy()) {
            String keep = " Deine Vorschau gilt noch " + previews.secondsLeft(owner) + " s – sag dann noch einmal "
                    + "\"ja\".";
            if (!queue.enabled()) {
                port.tell(r, "Ich baue gerade noch." + keep);
                return Outcome.KEPT;
            }
            String label = "bestätigte Vorschau: " + Texts.clean(port.prompt(r), 50);
            int pos = queue.offer(owner, port.ownerName(r), label, new Queued<>(r, false, fresh));
            if (pos < 0) {
                refuse(r, pos, keep);
                return Outcome.KEPT;
            }
            previews.discard(owner);
            queued(r, pos, label);
            return Outcome.QUEUED;
        }
        previews.discard(owner);
        return startConfirmed(fresh);
    }

    /**
     * Bestätigte Vorschau bauen. Vorher prüfen, ob der Bauplatz noch so aussieht wie beim Zeigen: Der Überbau-Schutz
     * hat gegen den damaligen Scan entschieden, und die Verschiebung „mittig vor den Spieler“ hängt daran, ob dort
     * schon etwas stand. Hat inzwischen jemand gebaut (auch ein Auftrag aus der Warteschlange), wird nicht gebaut –
     * fail-closed, der Spieler holt sich eine neue Vorschau.
     *
     * Ist der Bauplatz nicht geladen (Spieler weitergegangen, „ja“ aus der Warteschlange), wird er zuerst geladen
     * (AK-8): Vorher las die Prüfsumme ihn synchron im Server-Thread. LOADING = Vergleich und Bau folgen nach dem
     * Laden; bis dahin rückt niemand aus der Warteschlange nach.
     */
    Outcome startConfirmed(Confirmable<R, P> c) {
        Outcome[] now = new Outcome[1];
        boolean[] later = {false};
        boolean waited = !whenSiteLoaded(c.request(), () -> now[0] = check(c, !later[0]));
        later[0] = waited;
        if (waited) {
            port.tell(c.request(), "Ich lade erst den Bauplatz …");
            return Outcome.LOADING;
        }
        return now[0];
    }

    /**
     * then sofort, wenn der Bauplatz geladen ist (true), sonst nach dem Vorladen (false); solange zählt der Fluss als
     * beschäftigt.
     */
    private boolean whenSiteLoaded(R r, Runnable then) {
        boolean[] ran = {false};
        boolean[] counted = {false};
        port.loadSite(r, () -> {
            if (counted[0]) {
                loading--;
            }
            ran[0] = true;
            then.run();
        });
        if (!ran[0]) {
            counted[0] = true;
            loading++;
        }
        return ran[0];
    }

    /** Beschäftigt: der Baumeister selbst oder ein Bauplatz, der gerade für eine Vorschau bzw. ein „ja“ lädt. */
    private boolean busy() {
        return loading > 0 || port.busy();
    }

    private Outcome check(Confirmable<R, P> c, boolean sameTick) {
        R r = c.request();
        long site = port.fingerprint(r);
        if (site == SiteFingerprint.UNREADABLE || c.fingerprint() == SiteFingerprint.UNREADABLE) {
            LOG.warning(() -> "vorschau | bauplatz_nicht_geladen | besitzer=" + port.ownerName(r));
            port.tell(r, "Den Bauplatz konnte ich nicht laden – ungeprüft baue ich nicht. "
                    + "Neue Vorschau: /bm vorschau <auftrag>");
            return Outcome.UNREADABLE;
        }
        if (!sameTick && port.busy()) {
            // Während des Ladens hat ein anderer Auftrag angefangen (direkter Befehl) – nicht parallel bauen.
            LOG.info(() -> "vorschau | beim_laden_beschäftigt | besitzer=" + port.ownerName(r));
            port.tell(r, "Während ich den Bauplatz geladen habe, hat ein anderer Bau angefangen – deine Vorschau "
                    + "ist verworfen. Neue Vorschau: /bm vorschau <auftrag>");
            return Outcome.CHANGED;  // nur nach dem Laden erreichbar, der Wert geht an niemanden mehr
        }
        if (site != c.fingerprint()) {
            LOG.info(() -> "vorschau | bauplatz_verändert | besitzer=" + port.ownerName(r));
            port.tell(r, "Am Bauplatz hat sich seit der Vorschau etwas verändert – so baue ich nicht. "
                    + "Neue Vorschau: /bm vorschau <auftrag>");
            return Outcome.CHANGED;
        }
        LOG.info(() -> "vorschau | bestätigt | besitzer=" + port.ownerName(r));
        port.build(r, c.plan());
        return Outcome.BUILT;
    }

    boolean discard(UUID owner) {
        return previews.discard(owner);
    }

    /** Übernahme nach /bm neuladen: Warteschlange und Vorschauen des alten Dienstes. */
    void takeOver(ComfortFlow<R, P> old) {
        queue.takeOver(old.queue);
        previews.takeOver(old.previews);
    }
}
