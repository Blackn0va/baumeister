package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Jedes Muster im Plugin, das \b neben Umlauten oder ß benutzt, braucht UNICODE_CHARACTER_CLASS ((?U)).
 *
 * Gemessen 2026-11-07 (CompanionUseTest rot): Ohne (?U) ist \b in Java reines ASCII – ä, ö, ü, ß zählen nicht als
 * Buchstaben. Folgen: Wörter, die mit Umlaut beginnen oder auf ß/Umlaut enden („öffne“, „über“, „groß“, „tschüß“),
 * finden keine Wortgrenze und werden nie erkannt; und „groß\b“ greift fälschlich in „großes“ (Grenze zwischen ß und e).
 *
 * Geprüft werden alle statischen Pattern-Felder der Klassen unter com.blackn0va.baumeister. Klassen, deren statische
 * Initialisierung einen laufenden Server braucht, werden übersprungen und gezählt.
 */
class UmlautBoundaryTest {

    private static final Pattern NON_ASCII_LETTER = Pattern.compile("[äöüÄÖÜß]");

    @Test
    void everyBoundaryPatternWithUmlautsIsUnicodeAware() throws IOException, URISyntaxException {
        Path root = Path.of(UmlautBoundaryTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent().resolve("classes");
        List<String> bad = new ArrayList<>();
        int checked = 0;
        int skipped = 0;
        try (Stream<Path> files = Files.walk(root.resolve("com/blackn0va/baumeister"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = root.relativize(f).toString().replace('\\', '/').replace('/', '.')
                        .replaceAll("\\.class$", "");
                Class<?> c;
                try {
                    c = Class.forName(name, false, UmlautBoundaryTest.class.getClassLoader());
                } catch (Throwable e) {
                    skipped++;
                    continue;
                }
                for (Field fd : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(fd.getModifiers()) || fd.getType() != Pattern.class) {
                        continue;
                    }
                    Pattern p;
                    try {
                        fd.setAccessible(true);
                        p = (Pattern) fd.get(null);
                    } catch (Throwable e) {
                        // Statische Initialisierung braucht Bukkit (Server): ohne Server nicht prüfbar.
                        skipped++;
                        break;
                    }
                    if (p == null) {
                        continue;
                    }
                    checked++;
                    String re = p.pattern();
                    boolean unicode = (p.flags() & Pattern.UNICODE_CHARACTER_CLASS) != 0 || re.matches("(?s)^\\(\\?[a-zA-Z]*U.*");
                    if (re.contains("\\b") && NON_ASCII_LETTER.matcher(re).find() && !unicode) {
                        bad.add(c.getSimpleName() + "." + fd.getName());
                    }
                }
            }
        }
        System.out.println("umlaut_grenzen | geprüft=" + checked + " übersprungen_klassen=" + skipped + " ohne_U="
                + bad.size());
        assertTrue(checked > 50, "zu wenige Muster gefunden: " + checked);
        assertTrue(bad.isEmpty(), "\\b neben Umlauten ohne (?U): " + bad);
    }
}
