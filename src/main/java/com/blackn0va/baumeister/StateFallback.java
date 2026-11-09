package com.blackn0va.baumeister;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Zustände für Bukkit.createBlockData, ohne still etwas zu verwerfen (Plan B3).
 *
 * Vorher wich BuildService.toBlockData bei jedem Fehler auf die nackte ID aus: decorate hängt die Blickrichtung des
 * Schritts als facing an jeden Block, ein Stamm hat kein facing – aus oak_log[axis=x] wurde so oak_log[axis=y], ohne
 * Meldung. Jetzt fallen nur ergänzte Zustände weg: zuerst die, die der Block gar nicht kennt, dann alle ergänzten.
 * Ausdrücklich genannte bleiben immer; passt einer nicht, ist die Zelle ungültig (null) und der Bau meldet sie.
 *
 * Threads: zustandslos. Fehler: keine (null = ungültig).
 */
final class StateFallback {

    /** Baut die Daten aus Zuständen; null = abgelehnt (createBlockData warf). */
    interface Creator<T> {
        T create(Map<String, String> props);
    }

    private StateFallback() {
    }

    /** knownKeys: Zustände, die der Block hat – nur auf dem Fehlerweg gefragt (kostet einen createBlockData-Aufruf). */
    static <T> T create(Map<String, String> props, Set<String> explicit, Supplier<Set<String>> knownKeys,
            Creator<T> creator) {
        T d = creator.create(props);
        if (d != null) {
            return d;
        }
        Set<String> known = knownKeys.get();
        Map<String, String> kept = new LinkedHashMap<>();
        Map<String, String> explicitOnly = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (explicit.contains(e.getKey())) {
                kept.put(e.getKey(), e.getValue());
                explicitOnly.put(e.getKey(), e.getValue());
            } else if (known.contains(e.getKey())) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        if (kept.size() < props.size()) {
            d = creator.create(kept);
            if (d != null) {
                return d;
            }
        }
        return explicitOnly.size() < kept.size() ? creator.create(explicitOnly) : null;
    }
}
