package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Mannequin;
import org.junit.jupiter.api.Test;

/**
 * Ein JAR für Spigot und Paper (2026-10-30: unter Paper warf jeder NPC-Spawn NoSuchMethodError). Hier der Spigot-Weg
 * gegen die Spigot-API; den Paper-Weg belegt der Testserver mit Paper 1.21.11-132.
 */
class MannequinCompatTest {

    @Test
    void spigotGetsItsStringDescriptionAndVisibleLabel() {
        List<String> calls = new ArrayList<>();
        Mannequin m = (Mannequin) Proxy.newProxyInstance(Mannequin.class.getClassLoader(),
                new Class<?>[] {Mannequin.class}, (proxy, method, args) -> {
                    calls.add(method.getName() + "(" + (args == null ? "" : args[0]) + ")");
                    return null;
                });
        MannequinCompat.description(m, "denkt nach …");
        MannequinCompat.showDescription(m);
        assertEquals(List.of("setDescription(denkt nach …)", "setHideDescription(false)"), calls);
    }

    @Test
    void missingMethodIsSkippedQuietly() {
        assertTrue(MannequinCompat.find(Object.class, "setDescription", String.class.getName()).isEmpty());
    }

    /**
     * Seit PacketEvents samt Adventure geschattet wird (T-033), verschiebt maven-shade jede Text-Konstante, die mit
     * „net.kyori“ beginnt – gemessen 2026-11-22 mit javap im Plugin-Jar. Steht der Paper-Klassenname als ein Literal in
     * der .class-Datei, sucht Paper später nach unserer verschobenen Kopie und die Beschriftung fehlt still. Der Name muss
     * also zur Laufzeit zusammengesetzt werden und trotzdem stimmen.
     */
    @Test
    void paperComponentNameSurvivesShading() throws Exception {
        assertEquals("net.kyori.adventure.text.Component", MannequinCompat.PAPER_COMPONENT);
        byte[] cls;
        try (java.io.InputStream in = MannequinCompat.class.getResourceAsStream("MannequinCompat.class")) {
            cls = in.readAllBytes();
        }
        String text = new String(cls, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(!text.contains("net.kyori") && !text.contains("net/kyori"),
                "Literal mit net.kyori in MannequinCompat.class – shade schreibt es auf die eigene Kopie um");
    }
}
