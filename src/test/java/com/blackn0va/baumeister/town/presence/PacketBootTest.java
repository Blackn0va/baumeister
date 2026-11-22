package com.blackn0va.baumeister.town.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rückfall: Kennt PacketEvents die Serverfassung nicht oder wirft es, bleibt es bei Mannequins ohne Tab – mit Log. */
class PacketBootTest {

    /** Ablauf mitschreiben; failAt = an dieser Stelle werfen. */
    static final class FakeEngine implements PacketBoot.Engine {
        final List<String> calls = new ArrayList<>();
        String failAt = "";
        Throwable failWith = new IllegalStateException("kaputt");
        boolean supported = true;

        private void step(String name) {
            calls.add(name);
            if (name.equals(failAt)) {
                if (failWith instanceof RuntimeException r) {
                    throw r;
                }
                throw (Error) failWith;
            }
        }

        @Override
        public void load() {
            step("load");
        }

        @Override
        public void init() {
            step("init");
        }

        @Override
        public void terminate() {
            step("terminate");
        }

        @Override
        public String version() {
            return "26.2";
        }

        @Override
        public boolean supported() {
            return supported;
        }
    }

    private final Tab packets = new PresenceTest.FakeTab();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord r) {
            if (r.getLevel().intValue() >= Level.INFO.intValue()) {
                log.add(r.getLevel() + " " + r.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void listen() {
        Logger.getLogger("Baumeister").addHandler(handler);
    }

    @AfterEach
    void stop() {
        Logger.getLogger("Baumeister").removeHandler(handler);
    }

    /** Normalfall: load in onLoad, init in onEnable, terminate in onDisable – Tab erst nach enable. */
    @Test
    void lifecycleFollowsPacketEventsGuide() {
        FakeEngine e = new FakeEngine();
        PacketBoot b = PacketBoot.load(() -> e, () -> packets);
        assertSame(Tab.NONE, b.tab(), "vor onEnable keine Pakete");
        b.enable();
        assertSame(packets, b.tab());
        assertTrue(b.on());
        assertTrue(log.stream().anyMatch(l -> l.contains("stadtleben | tab_an")), log.toString());
        b.disable();
        assertSame(Tab.NONE, b.tab());
        assertEquals(List.of("load", "init", "terminate"), e.calls);
    }

    /** Fassung unbekannt: abmelden, Tab.NONE, Logzeile mit Grund. */
    @Test
    void unsupportedServerFallsBack() {
        FakeEngine e = new FakeEngine();
        e.supported = false;
        PacketBoot b = PacketBoot.load(() -> e, () -> packets);
        b.enable();
        assertSame(Tab.NONE, b.tab());
        assertFalse(b.on());
        assertTrue(b.reason().startsWith("serverfassung_unbekannt"), b.reason());
        assertEquals(List.of("load", "terminate"), e.calls);
        assertTrue(log.stream().anyMatch(l -> l.startsWith("WARNING stadtleben | tab_aus | grund=serverfassung_unbekannt")),
                log.toString());
    }

    /** Fehlende Klasse beim Laden (LinkageError): kein Absturz, Rückfall. */
    @Test
    void linkageErrorWhileLoadingFallsBack() {
        FakeEngine e = new FakeEngine();
        e.failAt = "load";
        e.failWith = new NoClassDefFoundError("io/netty/Channel");
        PacketBoot b = PacketBoot.load(() -> e, () -> packets);
        b.enable();
        b.disable();
        assertSame(Tab.NONE, b.tab());
        assertTrue(b.reason().startsWith("laden_fehlgeschlagen"), b.reason());
    }

    /** Schon der Bau wirft (statischer Teil von PacketEvents): Rückfall, kein terminate auf nichts. */
    @Test
    void factoryFailureFallsBack() {
        PacketBoot b = PacketBoot.load(() -> {
            throw new ExceptionInInitializerError("statisch");
        }, () -> packets);
        b.enable();
        assertSame(Tab.NONE, b.tab());
        assertTrue(b.reason().contains("ExceptionInInitializerError"), b.reason());
    }

    /** Wirft init in onEnable: abmelden, Rückfall, disable danach ohne zweites terminate. */
    @Test
    void initFailureFallsBack() {
        FakeEngine e = new FakeEngine();
        e.failAt = "init";
        PacketBoot b = PacketBoot.load(() -> e, () -> packets);
        b.enable();
        assertSame(Tab.NONE, b.tab());
        assertTrue(b.reason().startsWith("starten_fehlgeschlagen"), b.reason());
        b.disable();
        assertEquals(List.of("load", "init", "terminate"), e.calls);
    }
}
