package com.blackn0va.baumeister.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Hat der Spieler gerade selbst etwas getan? Dann gehört ihm die Figur: Der Bau hält im selben Tick an. Wunsch
 * 2026-10-31.
 *
 * Gezählt wird, was nur der Spieler auslöst: Bewegungs-, Sprung-, Schleich-, Angriffs- und Benutzen-Taste, jede
 * Mausbewegung bei gefangenem Zeiger und jeder offene Bildschirm (Chat, Inventar, Menü). Der Bau selbst drückt keine
 * Tasten und bewegt den Zeiger nicht – er setzt Blickwinkel und Geschwindigkeit direkt –, löst die Pause also nie
 * selbst aus. Wer hier eine Taste weglässt, lässt den Bau gegen den Spieler weiterlaufen.
 *
 * Threads: Client-Thread. Fehler: keine.
 */
final class Control {

    private double lastX = Double.NaN;
    private double lastY = Double.NaN;

    /** Grund der Übernahme oder null. Muss jeden Tick laufen, sonst zählt eine alte Mausbewegung als neue. */
    String takenOver(Minecraft mc) {
        double x = mc.mouseHandler.xpos();
        double y = mc.mouseHandler.ypos();
        boolean moved = !Double.isNaN(lastX) && mc.mouseHandler.isMouseGrabbed()
                && Math.abs(x - lastX) + Math.abs(y - lastY) > 0.5;
        lastX = x;
        lastY = y;
        if (mc.gui.screen() != null) {  // 26.x: der offene Bildschirm hängt an Gui, nicht mehr an Minecraft
            return "Bildschirm offen";
        }
        Options o = mc.options;
        if (o.keyUp.isDown() || o.keyDown.isDown() || o.keyLeft.isDown() || o.keyRight.isDown()) {
            return "Bewegung";
        }
        if (o.keyJump.isDown() || o.keyShift.isDown()) {
            return "Springen/Schleichen";
        }
        if (o.keyAttack.isDown() || o.keyUse.isDown()) {
            return "Klick";
        }
        return moved ? "Maus" : null;
    }
}
