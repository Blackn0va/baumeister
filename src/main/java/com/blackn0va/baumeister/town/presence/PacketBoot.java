package com.blackn0va.baumeister.town.presence;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;

/**
 * Lebenslauf von PacketEvents im Plugin nach der Anleitung von PacketEvents: onLoad → load(), onEnable → enable(),
 * onDisable → disable(). PacketEvents ist ins Plugin-Jar geschattet und verschoben (pom.xml), läuft also auch ohne das
 * PacketEvents-Plugin und stört eine fremde PacketEvents-Fassung nicht.
 *
 * Bewusst fail-open (Vertrag v013, AK-40): Tab-Einträge sind Anzeige, kein Schutz. Kennt PacketEvents die Serverfassung
 * nicht (ServerVersion.ERROR oder älter als 1.21.9, wo es noch keine Mannequins gibt) oder wirft beim Laden bzw.
 * Starten, bleibt tab() = Tab.NONE: Bewohner kommen wie bisher als Mannequins, nur ohne Tab-Eintrag, und eine
 * Logzeile „stadtleben | tab_aus | grund=…“ sagt warum. Gefangen wird RuntimeException und LinkageError (fehlende oder
 * unpassende Klasse, Fehler im statischen Teil) – an dieser Lebenslauf-Grenze darf nichts das Plugin mitreißen. Wer den
 * catch entfernt, lässt bei einer neuen Serverfassung das ganze Plugin nicht mehr starten.
 *
 * Threads: Hauptthread (Bukkit-Lebenslauf). Fehler: werden geloggt, nie geworfen.
 */
public final class PacketBoot {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Was PacketBoot von PacketEvents braucht – im Betrieb PacketEventsEngine, im Test ein Fake. */
    interface Engine {
        void load();

        void init();

        void terminate();

        /** Serverfassung, wie PacketEvents sie erkannt hat (Log). */
        String version();

        /** Kann PacketEvents diese Fassung? */
        boolean supported();
    }

    private Engine engine;
    private boolean on;
    private String reason = "nicht_geladen";
    private Tab tab = Tab.NONE;
    private final Supplier<Tab> packets;

    private PacketBoot(Supplier<Tab> packets) {
        this.packets = packets;
    }

    /** onLoad: PacketEvents bauen und laden (Netty-Einhängen muss vor onEnable geschehen). */
    public static PacketBoot load(Plugin plugin) {
        return load(() -> new PacketEventsEngine(plugin), PacketTab::new);
    }

    static PacketBoot load(Supplier<Engine> factory, Supplier<Tab> packets) {
        PacketBoot b = new PacketBoot(packets);
        Engine e = null;
        try {
            e = factory.get();
            e.load();
            if (!e.supported()) {
                b.off("serverfassung_unbekannt server=" + e.version());
                stop(e);
                return b;
            }
            b.engine = e;
            b.reason = "";
        } catch (RuntimeException | LinkageError x) {
            b.off("laden_fehlgeschlagen fehler=" + x);
            LOG.log(Level.FINE, "stadtleben | tab_laden | stacktrace", x);
            if (e != null) {
                stop(e);
            }
        }
        return b;
    }

    /** onEnable: PacketEvents starten; erst danach kommen Tab-Einträge an. */
    public void enable() {
        if (engine == null) {
            return;
        }
        try {
            engine.init();
            tab = packets.get();
            on = true;
            LOG.info(() -> "stadtleben | tab_an | packetevents=2.14.0 server=" + engine.version());
        } catch (RuntimeException | LinkageError x) {
            off("starten_fehlgeschlagen fehler=" + x);
            LOG.log(Level.FINE, "stadtleben | tab_starten | stacktrace", x);
            stop(engine);
            engine = null;
        }
    }

    /** onDisable: PacketEvents abmelden (Netty-Handler weg), danach keine Einträge mehr. */
    public void disable() {
        tab = Tab.NONE;
        on = false;
        if (engine != null) {
            stop(engine);
            engine = null;
        }
    }

    /** Wohin Presence die Einträge schickt; Tab.NONE im Rückfall und vor enable(). */
    public Tab tab() {
        return tab;
    }

    public boolean on() {
        return on;
    }

    /** Warum aus (leer, solange alles läuft). */
    public String reason() {
        return reason;
    }

    private void off(String why) {
        reason = why;
        tab = Tab.NONE;
        on = false;
        LOG.warning(() -> "stadtleben | tab_aus | grund=" + why + " – Bewohner bleiben Mannequins ohne Tab-Eintrag");
    }

    private static void stop(Engine e) {
        try {
            e.terminate();
        } catch (RuntimeException | LinkageError x) {
            // Abmelden nach einem Fehler oder beim Abschalten: Mehr als loggen geht nicht, der Server fährt weiter.
            LOG.log(Level.WARNING, "stadtleben | tab_abmelden_fehlgeschlagen | fehler=" + x, x);
        }
    }
}
