package com.blackn0va.baumeister;

import com.blackn0va.baumeister.earth.HeightField;
import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityPlanner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Etappen einer Erdwelt-Kachel: Die Karte ist schon geladen und projiziert (ein CityModel in Kachelkoordinaten,
 * Ursprung = Kachelmitte), geplant wird Etappe für Etappe (WorldTiles.stages) im Thread des Weltbaus.
 *
 * Anders als CityStages lädt diese Quelle nichts nach: Die Abfrage beim Kartendienst steuert WorldBuildService selbst,
 * weil sie dort gedrosselt wird (Throttle) – CityStages fragt bei „ausgelastet“ sofort den nächsten Dienst.
 *
 * Gelände (Prüfbefund 2026-10-28): Vorher rechnete CityJob die Höhen (TerrainFit) beim Übernehmen eines Abschnitts im
 * Bukkit-Takt. Das Höhenfeld der Erdwelt lädt fehlende Höhenkacheln aber im aufrufenden Thread – Platte,
 * PNG-Dekodierung, bei Cache-Fehlschlag HTTP mit 60 s Zeitlimit –, und gerade in neuem Gebiet fehlen sie. Der Server
 * hätte minutenlang gestanden, bis zum Watchdog. Jetzt rechnet planNow die Höhen gleich mit, im Weltbau-Thread, und
 * liefert absolute Zellen (absolute() = true). Die reine Rechnung kostet je Etappe 5–44 ms (gemessen, FitCostProbe,
 * 41 568 Zellen) – im Hintergrund nichts. Wer TerrainFit zurück in CityJob zieht, holt die Plattenzugriffe und
 * HTTP-Anfragen auf den Hauptthread zurück.
 *
 * Threads: Methoden vom Hauptthread, Planen im übergebenen Executor (einer, nacheinander – CityPlanner ist nicht
 * threadsicher; das HeightField ist laut Vertrag threadsicher). Fehler: „zu groß“ als fehlgeschlagene Future,
 * CityJob überspringt die Etappe.
 */
final class WorldTileSource implements StageSource {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    private final String place;
    private final Executor worker;
    private final long maxCells;
    private final HeightField ground;
    private final int baseX;
    private final int baseZ;
    private final List<CityPlanner.Clip> clips = WorldTiles.stages();
    private final CityPlanner planner = new CityPlanner();
    private volatile CityModel model;

    /** baseX/baseZ: Blockkoordinate der Kachelmitte (Ursprung des Modells) – dort fragt TerrainFit das Höhenfeld. */
    WorldTileSource(String place, CityModel model, Executor worker, long maxCells, HeightField ground, int baseX,
            int baseZ) {
        this.place = place;
        this.model = model;
        this.worker = worker;
        this.maxCells = maxCells;
        this.ground = ground;
        this.baseX = baseX;
        this.baseZ = baseZ;
        planner.shore(true);  // Küste aus natural=coastline (SeaMask) – nur hier, /bm stadt hat kein Meer
    }

    @Override
    public String place() {
        return place;
    }

    @Override
    public int count() {
        return clips.size();
    }

    @Override
    public boolean absolute() {
        return true;
    }

    @Override
    public String undoCommand() {
        return "/bm weltbau rückgängig";
    }

    @Override
    public CompletableFuture<CityPlanner.Plan> plan(int k) {
        return CompletableFuture.supplyAsync(() -> planNow(k), worker);
    }

    /** Im aufrufenden Thread planen – nur aus dem Executor des Weltbaus (erste Etappe vor dem Start). */
    CityPlanner.Plan planNow(int k) {
        CityModel m = model;
        if (m == null) {
            throw new IllegalStateException("Kachel " + place + " ist schon geschlossen");
        }
        long t0 = System.nanoTime();
        CityPlanner.Plan p = planner.plan(m, clips.get(k), maxCells);
        long t1 = System.nanoTime();
        CityPlanner.Plan fitted = fit(p);
        LOG.info(() -> String.format("weltbau | etappe_geplant | kachel=%s etappe=%d/%d blöcke=%d gebäude=%d "
                + "dauer_ms=%d gelände_ms=%d", place, k + 1, clips.size(), fitted.blocks(), fitted.buildings(),
                (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000));
        return fitted;
    }

    /** Alle Abschnitte aufs Gelände setzen; blocks zählt danach auch die Sockel (Fortschritt in CityJob). */
    private CityPlanner.Plan fit(CityPlanner.Plan p) {
        List<CityPlanner.Section> sections = new ArrayList<>(p.sections().size());
        long blocks = 0;
        int[] counts = new int[4];
        for (CityPlanner.Section s : p.sections()) {
            if (!s.building()) {
                TerrainFit.countShore(s, ground, baseX, baseZ, counts);
            }
        }
        boolean trusted = TerrainFit.shoreTrusted(counts[0], counts[1], counts[2], counts[3]);
        if (counts[0] + counts[2] > 0) {
            LOG.info(() -> String.format("weltbau | küste | kachel=%s meer=%d meer_hoch=%d strand=%d strand_tief=%d "
                    + "übernommen=%b", place, counts[0], counts[1], counts[2], counts[3], trusted));
        }
        TerrainFit.Shore shore = TerrainFit.Shore.of(p.palette(), trusted);
        for (CityPlanner.Section s : p.sections()) {
            List<CityPlanner.Cell> cells = TerrainFit.fit(s, ground, baseX, baseZ, shore);
            if (cells.isEmpty()) {
                continue;  // ganz ohne Gelände darunter (TerrainFit) – nichts zu bauen
            }
            sections.add(new CityPlanner.Section(s.label(), s.cx(), s.cz(), cells, s.building()));
            blocks += cells.size();
        }
        // Schilder mitnehmen (absolut wie die Zellen) – ohne sie stünden in der Erdwelt leere Straßenschilder.
        return new CityPlanner.Plan(p.palette(), List.copyOf(sections), p.buildings(), p.roads(), blocks, p.minX(),
                p.minZ(), p.maxX(), p.maxZ(), TerrainFit.fitSigns(p.signs(), ground, baseX, baseZ));
    }

    @Override
    public void close() {
        model = null;  // die Karte einer Großstadtkachel ist zig MB – nicht bis zum nächsten Bau festhalten
    }
}
