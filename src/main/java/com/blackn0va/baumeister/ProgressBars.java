package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Fortschrittsbalken (BossBar) für Bau, Stadtbau und Weltbau – der Kern ohne Bukkit.
 *
 * Vorher gab es Fortschritt nur auf Nachfrage (/bm status, /bm fortschritt, „wie weit bist du“), beim Normalbau sonst
 * nur im Server-Log, beim Stadtbau eine Chatzeile für den Auftraggeber alle 10 % der Etappen; wer daneben stand, sah
 * nichts. Jetzt zeigt ein Balken den Stand: dem Auftraggeber überall, allen
 * anderen Spielern nur im Umkreis der Baustelle (Nutzerwunsch Runde 4). Der Balken eines Auftrags lebt genau so lange
 * wie der Auftrag in der Meldung des Takts – fehlt er dort, wird er entfernt. Wer das Entfernen aus update() nimmt,
 * lässt nach jedem Bau einen Balken bei 100 % auf dem Bildschirm stehen.
 *
 * Die Bukkit-Seite (ProgressBarTask) sammelt im Takt die laufenden Aufträge und die Spielerpositionen und reicht sie
 * hier herein; Balken entstehen über die Fabrik, damit Tests ohne Server mitzählen können.
 *
 * Threads: nur Hauptthread. Fehler: keine Ausnahmen; ein Fortschritt außerhalb 0..1 wird begrenzt.
 */
final class ProgressBars {

    /** Art des Auftrags – bestimmt Farbe und Titel. */
    enum Kind {
        BUILD, CITY, WORLD
    }

    /**
     * Ein laufender Auftrag, wie die Quelle ihn im Takt meldet. key bleibt über die ganze Laufzeit gleich (ein neuer
     * Auftrag bekommt einen neuen key und damit einen frischen Balken). owner null = Konsole.
     */
    record Job(String key, Kind kind, String title, double progress, UUID owner, String world, double x, double z) {
    }

    /** Ein Spieler im Takt: wo er gerade ist. */
    record Viewer(UUID id, String world, double x, double z) {
    }

    /** Was ein Balken können muss; BukkitBar setzt es mit org.bukkit.boss.BossBar um. */
    interface Bar {
        void title(String title);

        void progress(double progress);

        /** Genau diese Spieler sehen den Balken – wer fehlt, verliert ihn. */
        void viewers(Set<UUID> players);

        void remove();
    }

    private final BiFunction<String, Kind, Bar> factory;
    private final Map<String, Bar> bars = new HashMap<>();
    private double radius;

    ProgressBars(BiFunction<String, Kind, Bar> factory, double radius) {
        this.factory = factory;
        this.radius = radius;
    }

    void radius(double r) {
        radius = r;
    }

    int size() {
        return bars.size();
    }

    /** Ein Takt: Balken anlegen, nachführen und die Balken beendeter Aufträge entfernen. */
    void update(List<Job> jobs, List<Viewer> online) {
        Set<String> alive = new HashSet<>();
        for (Job j : jobs) {
            alive.add(j.key());
            Bar bar = bars.computeIfAbsent(j.key(), k -> factory.apply(k, j.kind()));
            bar.title(j.title());
            bar.progress(clamp(j.progress()));
            bar.viewers(viewersOf(j, online, radius));
        }
        for (Iterator<Map.Entry<String, Bar>> it = bars.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Bar> e = it.next();
            if (!alive.contains(e.getKey())) {
                e.getValue().remove();
                it.remove();
            }
        }
    }

    /** Alle Balken weg (Abschalten, Plugin-Ende). */
    void clear() {
        for (Bar b : bars.values()) {
            b.remove();
        }
        bars.clear();
    }

    /** Auftraggeber immer (auch in einer anderen Welt), andere nur in derselben Welt im Umkreis. */
    static Set<UUID> viewersOf(Job j, List<Viewer> online, double radius) {
        Set<UUID> out = new HashSet<>();
        double r2 = radius * radius;
        for (Viewer v : online) {
            if (v.id().equals(j.owner())) {
                out.add(v.id());
                continue;
            }
            if (radius > 0 && v.world().equals(j.world())) {
                double dx = v.x() - j.x();
                double dz = v.z() - j.z();
                if (dx * dx + dz * dz <= r2) {
                    out.add(v.id());
                }
            }
        }
        return out;
    }

    static double clamp(double p) {
        if (Double.isNaN(p) || p < 0) {
            return 0;
        }
        return Math.min(1, p);
    }

    /**
     * Stadt- und Weltbau kennen ihre Gesamtgröße erst am Ende: Etappen werden nacheinander geplant. Geschätzt wird wie
     * in CityJob.progress() – bisheriger Schnitt je geplanter Etappe × Zahl der Etappen. Solange der Bau läuft, höchstens
     * 99 %: Eine spätere, größere Etappe soll den Balken nicht volllaufen und dann stehen lassen.
     */
    static double cityFraction(long processed, long planned, int stagesPlanned, int stageCount) {
        if (stageCount <= 0 || stagesPlanned <= 0 || planned <= 0) {
            return 0;
        }
        double perStage = (double) planned / stagesPlanned;
        double total = perStage * stageCount;
        return Math.min(0.99, clamp(processed / total));
    }

    /** Für Tests: die keys der aktuell gezeigten Balken. */
    List<String> keys() {
        return new ArrayList<>(bars.keySet());
    }
}
