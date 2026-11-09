package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.PlotLayout;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * Welcher PlotSquared-Plot liegt an einer Stelle, und darf ein Spieler dort bauen? Für den Plot-Modus (PlotLayout).
 *
 * Über Reflexion wie ChunkLoadGuard: PlotSquared ist keine Abhängigkeit des Baumeisters (läuft ohne), und die API-Jar
 * liegt nicht im Maven-Cache – sie zu laden wäre ein Download nur für vier Methoden. Benutzt wird die stabile API von
 * PlotSquared 6/7: PlotSquared.get().getPlotAreaManager().getApplicablePlotArea(Location) → PlotArea.getPlot(Location)
 * → Plot.getRegions() (WorldEdit-CuboidRegion), getOwner(), isAdded(UUID), getId().
 *
 * Fail-closed für das Bauen auf fremden Plots: Was hier nicht gelesen werden kann, ist „kein Plot“ – dann baut der
 * Baumeister wie ohne Plot-Modus vor dem Spieler, und die Rechteprüfung des Baus (BlockPlaceEvent in BuildJob, das
 * PlotSquared selbst beantwortet) entscheidet wie immer. Der Plot-Modus öffnet also nichts, was sonst zu wäre.
 *
 * Threads: nur Hauptthread (PlotSquared-Abfragen). Fehler: einmal geloggt, danach still „kein Plot“.
 */
final class PlotSquaredPlots {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** id wie PlotSquared sie zeigt („3;-2“), rect in Weltkoordinaten (gemergte Plots: das umfassende Rechteck). */
    record PlotSite(String id, PlotLayout.Rect rect, UUID owner, boolean mayBuild) {
    }

    private boolean warned;

    /** Plot an loc; mayBuild = player ist Besitzer oder hinzugefügt (null = Konsole, darf immer). */
    Optional<PlotSite> at(Location loc, UUID player) {
        Plugin ps = Bukkit.getPluginManager().getPlugin("PlotSquared");
        if (ps == null || !ps.isEnabled() || loc.getWorld() == null) {
            return Optional.empty();
        }
        try {
            ClassLoader cl = ps.getClass().getClassLoader();
            Class<?> core = Class.forName("com.plotsquared.core.PlotSquared", true, cl);
            Class<?> locClass = Class.forName("com.plotsquared.core.location.Location", true, cl);
            Object api = core.getMethod("get").invoke(null);
            Object areas = core.getMethod("getPlotAreaManager").invoke(api);
            Object pLoc = locClass.getMethod("at", String.class, int.class, int.class, int.class)
                    .invoke(null, loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            Object area = method(areas.getClass(), "getApplicablePlotArea", locClass).invoke(areas, pLoc);
            if (area == null) {
                return Optional.empty();
            }
            Object plot = method(area.getClass(), "getPlot", locClass).invoke(area, pLoc);
            if (plot == null) {
                return Optional.empty();  // Straße zwischen den Plots
            }
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (Object region : (Collection<?>) method(plot.getClass(), "getRegions").invoke(plot)) {
                Object lo = method(region.getClass(), "getMinimumPoint").invoke(region);
                Object hi = method(region.getClass(), "getMaximumPoint").invoke(region);
                minX = Math.min(minX, coord(lo, "x"));
                minZ = Math.min(minZ, coord(lo, "z"));
                maxX = Math.max(maxX, coord(hi, "x"));
                maxZ = Math.max(maxZ, coord(hi, "z"));
            }
            if (minX > maxX) {
                return Optional.empty();
            }
            UUID owner = (UUID) method(plot.getClass(), "getOwner").invoke(plot);
            boolean may = player == null
                    || (Boolean) method(plot.getClass(), "isAdded", UUID.class).invoke(plot, player);
            String id = String.valueOf(method(plot.getClass(), "getId").invoke(plot));
            return Optional.of(new PlotSite(id, new PlotLayout.Rect(minX, minZ, maxX, maxZ), owner, may));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Bewusst „kein Plot“ (siehe Kopf): gebaut wird dann wie ohne Plot-Modus, die Rechteprüfung bleibt.
            if (!warned) {
                warned = true;
                LOG.log(Level.WARNING, "plot | plotsquared_unlesbar | Plot-Modus aus (andere PlotSquared-Fassung?)",
                        e);
            }
            return Optional.empty();
        }
    }

    /**
     * Öffentliche Methode samt geerbter. Implementierungsklassen von PlotSquared sind teils nicht öffentlich; invoke
     * über deren Method würfe IllegalAccessException – trySetAccessible erlaubt den Aufruf (kein Modulsystem bei
     * Bukkit-Plugins) und lässt ihn sonst unverändert.
     */
    private static Method method(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        Method m = c.getMethod(name, params);
        m.trySetAccessible();
        return m;
    }

    /** WorldEdit 7.3+: x()/z(); ältere Fassungen getX()/getZ(). */
    private static int coord(Object vec, String axis) throws ReflectiveOperationException {
        try {
            return (Integer) vec.getClass().getMethod(axis).invoke(vec);
        } catch (NoSuchMethodException e) {
            return (Integer) vec.getClass().getMethod("get" + axis.toUpperCase(java.util.Locale.ROOT)).invoke(vec);
        }
    }
}
