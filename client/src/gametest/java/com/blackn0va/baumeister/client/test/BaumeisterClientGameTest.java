package com.blackn0va.baumeister.client.test;

import com.blackn0va.baumeister.client.BaumeisterClient;

import java.util.Arrays;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Alles, was die Mod anfasst, läuft über ctx.runOnClient/computeOnClient: Der Test hat einen eigenen Thread, die Mod
 * lebt auf dem Client-Thread (Minecraft.getInstance verweigert sich dort – erster Lauf 2026-10-31).
 *
 * Spieltest im echten Client (Wunsch 2026-10-31). Einzelspielerwelt im Kreativmodus, Schnellaufträge ohne LLM (der Host
 * läuft hier nicht):
 *
 * 1. Befehls-Bauweise: Hochhaus mit 2 Stockwerken – alle Stellen stimmen nach dem Prüfdurchgang.
 * 2. Spieler-Bauweise: dasselbe Hochhaus woanders (Fassade, Glas, Leiter an der Wand, Licht) – per Rechtsklick.
 * 3. Kontrolle: Maschinenhalle (Redstone mit Ausrichtungen) im Spieler-Modus, nach 2 s „W“ drücken – der Bau steht im
 *    selben Tick, setzt 20 Ticks lang nichts, weiter macht an derselben Stelle weiter bis fertig.
 * 4. Rückgängig räumt die Halle wieder weg.
 *
 * Fehlschlag = AssertionError, runClientGameTest endet dann mit Fehler. Bildschirmfotos unter run/screenshots.
 */
public final class BaumeisterClientGameTest implements FabricClientGameTest {

    private static final int MINUTES = 20 * 60;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        // Cheats an: In der Einzelspielerwelt ist das die OP-Stufe des Spielers (/op gibt es nur auf Servern).
        try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
            sp.getConnection().waitForChunksRender();  // 26.x: getClientWorld() heißt getConnection()
            sp.getServer().runCommand("gamemode creative @a");
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("gamerule doDaylightCycle false");
            BaumeisterClient bm = ctx.computeOnClient(mc -> BaumeisterClient.get());

            // 1. Befehle
            moveTo(ctx, sp, 0, 0);
            ctx.runOnClient(mc -> bm.setMode("befehl"));
            ctx.runOnClient(mc -> bm.setSpeed(200));
            ctx.runOnClient(mc -> bm.order("bau ein hochhaus mit 2 stockwerken"));
            awaitDone(ctx, bm, 3 * MINUTES, "Befehls-Bauweise");
            int[] c = ctx.computeOnClient(mc -> bm.counts());
            check(c[3] == 0, "Befehls-Bauweise: " + c[3] + " Stellen stimmen nicht " + Arrays.toString(c));
            ctx.takeScreenshot("baumeister-befehl");

            // 2. Spieler
            moveTo(ctx, sp, 60, 0);
            ctx.runOnClient(mc -> bm.setMode("spieler"));
            ctx.runOnClient(mc -> bm.setSpeed(40));
            ctx.runOnClient(mc -> bm.order("bau ein hochhaus mit 2 stockwerken"));
            awaitDone(ctx, bm, 10 * MINUTES, "Spieler-Bauweise");
            c = ctx.computeOnClient(mc -> bm.counts());
            check(c[3] == 0, "Spieler-Bauweise: " + c[3] + " Stellen stimmen nicht " + Arrays.toString(c));
            ctx.takeScreenshot("baumeister-spieler");

            // 3. Übernahme mitten im Bau
            moveTo(ctx, sp, 120, 0);
            ctx.runOnClient(mc -> bm.order("bau mir eine maschine"));
            ctx.waitFor(mc -> "RUNNING".equals(bm.state()), MINUTES);
            ctx.waitTicks(40);
            ctx.getInput().holdKey(o -> o.keyUp);
            ctx.waitTicks(2);
            ctx.getInput().releaseKey(o -> o.keyUp);
            String paused = ctx.computeOnClient(mc -> bm.state());
            check("PAUSED".equals(paused), "nach W muss der Bau pausieren, Zustand " + paused);
            int before = ctx.computeOnClient(mc -> bm.counts()[2]);
            ctx.waitTicks(20);
            int after = ctx.computeOnClient(mc -> bm.counts()[2]);
            check(after == before, "pausiert darf nichts gesetzt werden: " + before + " → " + after);
            ctx.takeScreenshot("baumeister-pause");
            ctx.runOnClient(mc -> bm.resume());
            awaitDone(ctx, bm, 10 * MINUTES, "nach der Übernahme");
            c = ctx.computeOnClient(mc -> bm.counts());
            check(c[3] == 0, "Maschinenhalle: " + c[3] + " Stellen stimmen nicht " + Arrays.toString(c));
            ctx.takeScreenshot("baumeister-maschine");

            // 4. Rückgängig
            ctx.runOnClient(mc -> bm.undo());
            awaitDone(ctx, bm, 10 * MINUTES, "Rückgängig");
            int left = ctx.computeOnClient(mc -> bm.counts()[3]);
            check(left == 0, "Rückgängig: " + left + " Stellen nicht zurück");
            ctx.takeScreenshot("baumeister-rueckgaengig");  // umlaut-ok: Dateiname
        }
    }

    /** Spieler auf den Boden bei (x, z), Blick nach Norden. */
    private static void moveTo(ClientGameTestContext ctx, TestSingleplayerContext sp, int x, int z) {
        int y = sp.getServer().computeOnServer(s -> s.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
        sp.getServer().runCommand("tp @a " + x + " " + y + " " + z + " 180 0");
        ctx.waitTicks(10);
        sp.getConnection().waitForChunksRender();  // 26.x: getClientWorld() heißt getConnection()
    }

    private static void awaitDone(ClientGameTestContext ctx, BaumeisterClient bm, int ticks, String what) {
        ctx.waitFor(mc -> !"PLANNING".equals(bm.state()), ticks);
        // Auch bei PAUSED aufhören: Ein Bau, der sich selbst anhält (offener Bildschirm durch den eigenen Klick, Spieltest
        // 2026-10-31), wartete sonst die ganze Frist ab.
        ctx.waitFor(mc -> "DONE".equals(bm.state()) || "NONE".equals(bm.state()) || "PAUSED".equals(bm.state()),
                ticks);
        String state = ctx.computeOnClient(mc -> bm.state());
        int[] counts = ctx.computeOnClient(mc -> bm.counts());
        String why = ctx.computeOnClient(mc -> bm.pauseReason());
        check("DONE".equals(state), what + ": Bau nicht fertig, Zustand " + state + " (" + why + ") "
                + Arrays.toString(counts));
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
