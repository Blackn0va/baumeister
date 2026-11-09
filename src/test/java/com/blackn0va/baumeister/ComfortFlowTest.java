package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Warteschlange und Bauvorschau ohne Bukkit: Eine Vorschau baut nie, eingereihte Vorschauen bleiben Vorschauen,
 * Aufträge abgemeldeter Spieler fallen weg, ein veränderter Bauplatz wird nicht bebaut, und ein „ja“, das nicht
 * eingereiht werden kann, kostet die Vorschau nicht.
 */
class ComfortFlowTest {

    private static final UUID ANNA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BERT = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** Auftrag im Test: version zählt, wie oft refresh einen frischen Absender eingesetzt hat. */
    record Req(UUID owner, String name, String prompt, int version) {
    }

    /** Port mit Zustand zum Nachsehen: geplant, gebaut, gesagt. */
    static final class FakePort implements ComfortFlow.Port<Req, String> {
        boolean busy;
        long site = 7;
        final Set<UUID> online = new HashSet<>(Set.of(ANNA, BERT));
        final List<String> planned = new ArrayList<>();
        final List<String> built = new ArrayList<>();
        final List<String> said = new ArrayList<>();

        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public UUID owner(Req r) {
            return r.owner();
        }

        @Override
        public String ownerName(Req r) {
            return r.name();
        }

        @Override
        public String prompt(Req r) {
            return r.prompt();
        }

        @Override
        public Req refresh(Req r) {
            return r.owner() != null && !online.contains(r.owner()) ? null
                    : new Req(r.owner(), r.name(), r.prompt(), r.version() + 1);
        }

        @Override
        public void plan(Req r, boolean preview) {
            planned.add(r.name() + ":" + r.prompt() + (preview ? ":vorschau" : ":bau"));
        }

        @Override
        public long fingerprint(Req r) {
            return site;
        }

        @Override
        public void build(Req r, String plan) {
            built.add(r.name() + ":" + plan + ":v" + r.version());
        }

        @Override
        public void tell(Req r, String text) {
            said.add(r.name() + ": " + text);
        }

        @Override
        public String where(Req r) {
            return "bei 10 / 64 / -3";
        }
    }

    private static Req anna(String prompt) {
        return new Req(ANNA, "Anna", prompt, 0);
    }

    private static Req bert(String prompt) {
        return new Req(BERT, "Bert", prompt, 0);
    }

    private static ComfortFlow.Shown shown(long fingerprint) {
        return new ComfortFlow.Shown("world", BuildPreview.Box.of(0, 64, 0, 6, 70, 8), 300, fingerprint);
    }

    private static ComfortFlow<Req, String> flow(FakePort port, int max, int perOwner) {
        return new ComfortFlow<>(port, max, perOwner, 120_000, () -> 1_000_000L);
    }

    /** Wie BuildService.startJob: divert = true heißt „nicht bauen“; sonst baut der Aufrufer. */
    private static void finishPlan(ComfortFlow<Req, String> flow, FakePort port, Req r, boolean previewOnly) {
        if (!flow.divert(r, previewOnly, "haus", () -> shown(port.site))) {
            port.build(r, "haus");
        }
    }

    @Test
    void aPreviewNeverBuilds() {
        FakePort port = new FakePort();
        ComfortFlow<Req, String> flow = flow(port, 10, 2);
        finishPlan(flow, port, anna("bau ein haus"), true);
        assertEquals(List.of(), port.built, "Vorschau hat gebaut");
        assertTrue(flow.previews().has(ANNA), "Vorschau ist nicht offen");

        // Konsole: keine Partikel, also keine Vorschau – gebaut wird trotzdem nicht.
        finishPlan(flow, port, new Req(null, "CONSOLE", "bau ein haus", 0), true);
        assertEquals(List.of(), port.built, "Vorschau der Konsole hat gebaut");

        finishPlan(flow, port, bert("bau ein haus"), false);
        assertEquals(List.of("Bert:haus:v0"), port.built, "normaler Auftrag muss bauen");
    }

    @Test
    void aQueuedPreviewStaysAPreview() {
        FakePort port = new FakePort();
        ComfortFlow<Req, String> flow = flow(port, 10, 2);
        port.busy = true;
        assertTrue(flow.enqueue(anna("zeig mir einen turm"), true));
        assertTrue(flow.enqueue(bert("bau einen brunnen"), false));
        port.busy = false;
        flow.pump();
        assertEquals(List.of("Anna:zeig mir einen turm:vorschau", "Bert:bau einen brunnen:bau"), port.planned,
                "eingereihte Vorschau wurde zum Bau");
    }

    @Test
    void ordersOfPlayersWhoLeftAreDropped() {
        FakePort port = new FakePort();
        ComfortFlow<Req, String> flow = flow(port, 10, 2);
        port.busy = true;
        flow.enqueue(anna("bau ein haus"), false);
        flow.enqueue(bert("bau einen turm"), false);
        port.online.remove(ANNA);
        port.busy = false;
        flow.pump();
        assertEquals(List.of("Bert:bau einen turm:bau"), port.planned, "Auftrag einer abgemeldeten Spielerin lief an");
        assertTrue(flow.queue().isEmpty());
    }

    @Test
    void nothingStartsWhileBusyAndQueueOffKeepsTheOldAnswer() {
        FakePort port = new FakePort();
        ComfortFlow<Req, String> on = flow(port, 10, 2);
        port.busy = true;
        on.enqueue(anna("bau ein haus"), false);
        on.pump();
        assertEquals(List.of(), port.planned, "Auftrag startete, obwohl der Baumeister beschäftigt ist");
        assertFalse(flow(port, 0, 2).enqueue(anna("bau ein haus"), false), "warteschlange-max 0 muss absagen");
    }

    @Test
    void queueMessagesNameTheBuildSite() {
        FakePort port = new FakePort();
        ComfortFlow<Req, String> flow = flow(port, 10, 2);
        port.busy = true;
        flow.enqueue(anna("bau ein haus"), false);
        assertTrue(port.said.getLast().contains("Platz 1") && port.said.getLast().contains("bei 10 / 64 / -3"),
                port.said.getLast());
        port.busy = false;
        flow.pump();
        assertTrue(port.said.getLast().startsWith("Anna: Jetzt bist du dran") && port.said.getLast().contains(
                "bei 10 / 64 / -3"), "Ort fehlt beim Drankommen: " + port.said.getLast());
    }

    @Test
    void confirmBuildsWithAFreshSenderWhenTheSiteIsUnchanged() {
        FakePort port = new FakePort();
        ComfortFlow<Req, String> flow = flow(port, 10, 2);
        finishPlan(flow, port, anna("bau ein haus"), true);
        assertEquals(ComfortFlow.Outcome.BUILT, flow.confirm(ANNA));
        assertEquals(List.of("Anna:haus:v1"), port.built);
        assertFalse(flow.previews().has(ANNA));
        assertEquals(ComfortFlow.Outcome.NONE, flow.confirm(ANNA), "zweites ja baute noch einmal");
    }

    @Test
    void changedSiteIsNotBuilt() {
        FakePort port = new FakePort();
        ComfortFlow<Req, String> flow = flow(port, 10, 2);
        finishPlan(flow, port, anna("bau ein haus"), true);
        port.site = 8;
        assertEquals(ComfortFlow.Outcome.CHANGED, flow.confirm(ANNA));
        assertEquals(List.of(), port.built, "auf verändertem Bauplatz gebaut");

        // Auch aus der Warteschlange heraus: geprüft wird beim Drankommen, nicht beim Einreihen.
        port.site = 7;
        finishPlan(flow, port, bert("bau ein haus"), true);
        port.busy = true;
        assertEquals(ComfortFlow.Outcome.QUEUED, flow.confirm(BERT));
        port.site = 9;
        port.busy = false;
        flow.pump();
        assertEquals(List.of(), port.built, "eingereihte Vorschau auf verändertem Bauplatz gebaut");
    }

    @Test
    void confirmThatCannotBeQueuedKeepsThePreview() {
        FakePort port = new FakePort();
        ComfortFlow<Req, String> full = flow(port, 1, 2);
        finishPlan(full, port, anna("bau ein haus"), true);
        port.busy = true;
        full.enqueue(bert("bau einen turm"), false);
        assertEquals(ComfortFlow.Outcome.KEPT, full.confirm(ANNA));
        assertTrue(full.previews().has(ANNA), "volle Warteschlange hat die Vorschau gekostet");

        ComfortFlow<Req, String> off = flow(port, 0, 2);
        finishPlan(off, port, anna("bau ein haus"), true);
        assertEquals(ComfortFlow.Outcome.KEPT, off.confirm(ANNA));
        assertTrue(off.previews().has(ANNA), "abgeschaltete Warteschlange hat die Vorschau gekostet");
        assertFalse(port.said.getLast().contains("voll"), "falsche Meldung: " + port.said.getLast());
        assertTrue(port.said.getLast().contains("gilt noch 120 s"), port.said.getLast());

        port.busy = false;
        assertEquals(ComfortFlow.Outcome.BUILT, off.confirm(ANNA), "nach dem Bau ließ sich nicht mehr bestätigen");
    }

    @Test
    void confirmWhileBusyQueuesAndBuildsLater() {
        FakePort port = new FakePort();
        ComfortFlow<Req, String> flow = flow(port, 10, 2);
        finishPlan(flow, port, anna("bau ein haus"), true);
        port.busy = true;
        assertEquals(ComfortFlow.Outcome.QUEUED, flow.confirm(ANNA));
        assertFalse(flow.previews().has(ANNA));
        port.busy = false;
        flow.pump();
        assertEquals(1, port.built.size());
        assertTrue(port.built.getFirst().startsWith("Anna:haus:"));
    }

    @Test
    void reloadMovesQueueAndPreviews() {
        FakePort port = new FakePort();
        ComfortFlow<Req, String> old = flow(port, 10, 2);
        finishPlan(old, port, anna("bau ein haus"), true);
        port.busy = true;
        old.enqueue(bert("bau einen turm"), false);
        ComfortFlow<Req, String> now = flow(port, 10, 2);
        now.takeOver(old);
        assertTrue(old.queue().isEmpty() && old.previews().isEmpty());
        assertTrue(now.previews().has(ANNA));
        assertEquals(1, now.queue().size());
    }
}
