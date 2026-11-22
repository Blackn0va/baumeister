package com.blackn0va.baumeister;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.entity.Mannequin;

/**
 * Mannequin-Aufrufe, die Spigot und Paper verschieden anbieten – ein JAR für beide Server.
 *
 * Gemessen 2026-10-30 (Plugin gegen paper-api 1.21.11-R0.1 aus paper-1.21.11-132 kompiliert): genau fünf Stellen,
 * alle hier. Spigot: setDescription(String), setHideDescription(boolean), setPlayerProfile(PlayerProfile). Paper:
 * setDescription(Component), kein setHideDescription, setProfile(ResolvableProfile). Vorher rief BuilderNpc die
 * Spigot-Methoden direkt – unter Paper warf jeder NPC-Spawn NoSuchMethodError, kein Bau startete (Testserver).
 *
 * Deshalb über Reflexion, nur auf API-Methoden (kein NMS), einmal je Methode gesucht und gemerkt. Fehlt eine Methode
 * auf beiden Wegen, bleibt es beim fehlenden Schmuck (Beschriftung, Skin) – bewusst fail-open: Der NPC ist Anzeige,
 * kein Sicherheitspfad, und der Bau läuft ohne ihn weiter. Gewarnt wird einmal je Methode.
 *
 * Öffentlich seit 2026-11-22 (T-033): town.presence setzt über pose() das Schleichen der Bewohner.
 *
 * Threads: Hauptthread (Bukkit-Entities). Fehler: werden geloggt, nie geworfen.
 */
public final class MannequinCompat {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    /**
     * Papers Adventure-Component, zusammengesetzt statt als ein Text. Gemessen 2026-11-22 (javap im Plugin-Jar): Seit
     * PacketEvents samt Adventure geschattet wird, schreibt die Verschiebung net.kyori → com.blackn0va.baumeister.lib.kyori
     * auch Text-Konstanten um – aus "net.kyori.adventure.text.Component" wurde unsere eigene Kopie, und unter Paper fand
     * description() die Methode setDescription(Component) nicht mehr (Beschriftung fehlte still). Wer das wieder zu
     * einem Literal zusammenzieht, bringt das zurück; MannequinCompatTest prüft die .class-Datei.
     */
    static final String PAPER_COMPONENT = String.join(".", "net", "kyori", "adventure", "text", "Component");

    private MannequinCompat() {
    }

    /** Beschriftung unter dem Namen: Spigot mit String, Paper mit Adventure-Component. */
    static void description(Mannequin m, String text) {
        if (invoke(m, "setDescription", String.class, text)) {
            return;
        }
        Object component = adventureText(text);
        Optional<Method> paper = find(m.getClass(), "setDescription", PAPER_COMPONENT);
        if (component != null && paper.isPresent() && call(paper.get(), m, component)) {
            return;
        }
        warnOnce("setDescription");
    }

    /** Beschriftung zeigen (nur Spigot; unter Paper ist sie immer sichtbar, sobald gesetzt). */
    static void showDescription(Mannequin m) {
        invoke(m, "setHideDescription", boolean.class, false);
    }

    /** Skin: Spigot setPlayerProfile(PlayerProfile), Paper setProfile(ResolvableProfile.resolvableProfile(profile)). */
    static void skin(Mannequin m, org.bukkit.profile.PlayerProfile profile) {
        if (invoke(m, "setPlayerProfile", org.bukkit.profile.PlayerProfile.class, profile)) {
            return;
        }
        try {
            Class<?> rp = Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
            for (Method factory : rp.getMethods()) {
                if (factory.getName().equals("resolvableProfile") && factory.getParameterCount() == 1
                        && factory.getParameterTypes()[0].isInstance(profile)) {
                    Object resolvable = factory.invoke(null, profile);
                    Optional<Method> set = find(m.getClass(), "setProfile", rp.getName());
                    if (set.isPresent() && call(set.get(), m, resolvable)) {
                        return;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.fine(() -> "npc | skin_paper_fehlgeschlagen | " + e);
        }
        warnOnce("setProfile");
    }

    /**
     * Pose fest setzen. Paper: setPose(Pose, true) – fest, sonst setzt das Spiel sie im nächsten Takt zurück; Spigot:
     * Mannequin.setPose(Pose). Gemessen 2026-11-06 (probes/mannequin/PoseProbe, Paper 1.21.11-132): alle fünf
     * Mannequin-Posen bleiben so 40 Ticks stehen. Fail-open wie der Rest: Pose ist Schmuck.
     */
    public static void pose(Mannequin m, org.bukkit.entity.Pose pose) {
        if (pose == m.getPose()) {
            return;
        }
        Optional<Method> paper = METHODS.computeIfAbsent(m.getClass().getName() + "#setPose(Pose,boolean)", k -> {
            try {
                return Optional.of(m.getClass().getMethod("setPose", org.bukkit.entity.Pose.class, boolean.class));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        });
        try {
            if (paper.isPresent()) {
                paper.get().invoke(m, pose, true);
            } else {
                m.setPose(pose);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.fine(() -> "npc | pose_fehlgeschlagen | " + e);
            warnOnce("setPose");
        }
    }

    // ---------------------------------------------------------------- Reflexion

    private static boolean invoke(Object target, String name, Class<?> param, Object arg) {
        Optional<Method> m = find(target.getClass(), name, param.getName());
        return m.isPresent() && call(m.get(), target, arg);
    }

    /** Öffentliche Methode mit genau einem Parameter dieses Typs (über die Schnittstellen, nicht die Craft-Klasse). */
    static Optional<Method> find(Class<?> type, String name, String paramType) {
        return METHODS.computeIfAbsent(type.getName() + "#" + name + "(" + paramType + ")", k -> {
            for (Method m : type.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].getName().equals(paramType)) {
                    m.setAccessible(true);
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        });
    }

    private static boolean call(Method m, Object target, Object arg) {
        try {
            m.invoke(target, arg);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.fine(() -> "npc | aufruf_fehlgeschlagen | methode=" + m.getName() + " " + e);
            return false;
        }
    }

    private static Object adventureText(String text) {
        try {
            return Class.forName(PAPER_COMPONENT).getMethod("text", String.class)
                    .invoke(null, text);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void warnOnce(String method) {
        if (WARNED.putIfAbsent(method, Boolean.TRUE) == null) {
            LOG.warning(() -> "npc | methode_fehlt | " + method + " – weder Spigot- noch Paper-Weg; ohne weiter");
        }
    }
}
