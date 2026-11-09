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

        /** Prüfsumme des Bauplatzes jetzt (SiteFingerprint). */
        long fingerprint(R request);

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
        KEPT
    }

    private final Port<R, P> port;
    private final OrderQueue<Queued<R, P>> queue;
    private final BuildPreview<Confirmable<R, P>> previews;
    private final long previewMillis;

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
        while (!port.busy() && !queue.isEmpty()) {
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
        Shown s = port.owner(r) == null ? null : shown.get();
        if (s == null) {
            port.tell(r, "Eine Vorschau gibt es nur im Spiel – nichts gebaut.");
            return true;
        }
        previews.offer(port.owner(r), s.world(), s.box(), s.blocks(), new Confirmable<>(r, plan, s.fingerprint()),
                previewMillis);
        BuildPreview.Box box = s.box();
        LOG.info(() -> String.format("vorschau | gezeigt | besitzer=%s größe=%dx%dx%d blöcke=%d", port.ownerName(r),
                box.sizeX(), box.sizeY(), box.sizeZ(), s.blocks()));
        port.tell(r, String.format("Vorschau: %d × %d × %d Blöcke (%,d Blöcke), Umriss in Violett. Sag \"ja\" (oder "
                + "/bm vorschau ja), dann baue ich – \"nein\" verwirft sie, sonst verfällt sie in %d s.", box.sizeX(),
                box.sizeY(), box.sizeZ(), s.blocks(), previewSeconds()));
        return true;
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
        if (port.busy()) {
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
     */
    Outcome startConfirmed(Confirmable<R, P> c) {
        R r = c.request();
        if (port.fingerprint(r) != c.fingerprint()) {
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
