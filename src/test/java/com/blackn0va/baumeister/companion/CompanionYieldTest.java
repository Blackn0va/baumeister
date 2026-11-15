package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Platz machen (CompanionYield, Rückmeldung 2026-11-15). Füße bei y=64, Vera bei (0,5 | 0,5), 1 Aufruf = 2 Ticks.
 */
class CompanionYieldTest {

    /** rock=false: Boden y<=63, darüber Luft plus solids. rock=true: alles Fels außer air. */
    static final class Grid implements CompanionPath.Blocks {
        final boolean rock;
        final Set<String> solids = new HashSet<>();
        final Set<String> air = new HashSet<>();
        final Set<String> doors = new HashSet<>();
        final Set<String> ladders = new HashSet<>();
        final Set<String> stairs = new HashSet<>();
        long lookups;

        Grid(boolean rock) {
            this.rock = rock;
        }

        static String k(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        void tunnelZ(int x, int z1, int z2) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                air.add(k(x, 64, z));
                air.add(k(x, 65, z));
            }
        }

        @Override
        public boolean solid(int x, int y, int z) {
            lookups++;
            if (rock) {
                return !air.contains(k(x, y, z));
            }
            return y <= 63 || solids.contains(k(x, y, z));
        }

        @Override
        public boolean passable(int x, int y, int z) {
            return !solid(x, y, z);
        }

        @Override
        public boolean water(int x, int y, int z) {
            lookups++;
            return false;
        }

        @Override
        public boolean climbable(int x, int y, int z) {
            lookups++;
            return ladders.contains(k(x, y, z));
        }

        @Override
        public boolean doorway(int x, int y, int z) {
            lookups++;
            return doors.contains(k(x, y, z));
        }

        @Override
        public boolean stairs(int x, int y, int z) {
            lookups++;
            return stairs.contains(k(x, y, z));
        }
    }

    private static final double EYE = 1.62;

    /** Spieler auf y=64, der in seine Laufrichtung schaut (oder nach look, wenn er steht), ohne Zielblock. */
    private static CompanionYield.Person walker(double x, double z, double vx, double vz, double lx, double lz) {
        double ll = Math.hypot(lx, lz);
        return new CompanionYield.Person(x, 64, z, vx, vz, 64 + EYE, lx / ll, 0, lz / ll, 0, false, true, false);
    }

    /** Spieler, der beim Arbeiten auf einen Punkt schaut (Zielstrahl bis dorthin). */
    private static CompanionYield.Person aiming(double x, double z, double tx, double ty, double tz,
            boolean working, boolean talking) {
        double dx = tx - x;
        double dy = ty - (64 + EYE);
        double dz = tz - z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new CompanionYield.Person(x, 64, z, 0, 0, 64 + EYE, dx / len, dy / len, dz / len, len, working, true,
                talking);
    }

    private static CompanionYield.Decision decide(CompanionYield.State s, Grid w, CompanionYield.Zones z,
            CompanionYield.Person... people) {
        return CompanionYield.decide(s, w, z, 0.5, 64, 0.5, 0, 0, List.of(people));
    }

    private static double offLine(CompanionYield.Decision d, double lineX) {
        return Math.abs(d.x() - lineX);
    }

    @Test
    void playerWalkingStraightAtHerMakesHerStepAside() {
        Grid w = new Grid(false);
        CompanionYield.Decision d = decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE,
                walker(0.5, -3.5, 0, 0.216, 0, 1));
        assertEquals(CompanionYield.Reason.PATH, d.reason());
        assertTrue(offLine(d, 0.5) >= CompanionYield.TUBE, "Platz neben seiner Bahn: " + d);
        assertTrue(Math.hypot(d.x() - 0.5, d.z() - 0.5) <= 2.6, "kleiner Schritt, kein Umweg: " + d);
        assertTrue(d.faceOwner());
        assertTrue(d.holdFollow());
        assertEquals(CompanionYield.YIELD_SPEED, d.speed());
    }

    @Test
    void metHeadOnSheKeepsHerRightThatIsHisLeft() {
        // Er läuft nach Süden (+z): seine Linke ist Osten (+x).
        CompanionYield.Decision d = decide(new CompanionYield.State(), new Grid(false), CompanionYield.Zones.NONE,
                walker(0.5, -3.5, 0, 0.216, 0, 1));
        assertTrue(d.x() > 0.5, "nach Osten: " + d);
    }

    @Test
    void offToOneSideSheStaysOnThatSideInsteadOfCrossingHisPath() {
        // Vera 0,4 westlich seiner Bahn (seine Rechte): sie bleibt im Westen.
        CompanionYield.Decision d = CompanionYield.decide(new CompanionYield.State(), new Grid(false),
                CompanionYield.Zones.NONE, 0.1, 64, 0.5, 0, 0, List.of(walker(0.5, -3.5, 0, 0.216, 0, 1)));
        assertEquals(CompanionYield.Reason.PATH, d.reason());
        assertTrue(d.x() < 0.5, "im Westen: " + d);
    }

    @Test
    void standingPlayerLookingElsewhereLeavesHerAlone() {
        Grid w = new Grid(false);
        CompanionYield.State s = new CompanionYield.State();
        for (int i = 0; i < 100; i++) {
            CompanionYield.Decision d = decide(s, w, CompanionYield.Zones.NONE, walker(0.5, -2.5, 0, 0, 1, -1));
            assertFalse(d.moving(), "Aufruf " + i + ": " + d);
            assertFalse(d.holdFollow());
        }
    }

    @Test
    void playerWalkingAwayOrPassingTwoBlocksBesideIsNoConflict() {
        Grid w = new Grid(false);
        CompanionYield.Decision away = decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE,
                walker(0.5, -3.0, 0, -0.216, 0, -1));
        assertFalse(away.moving());
        assertFalse(away.holdFollow());
        CompanionYield.Decision beside = decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE,
                walker(2.5, -4.0, 0, 0.216, 0, 1));
        assertFalse(beside.moving());
    }

    @Test
    void conflictHasHysteresisBetweenEnterAndExit() {
        Grid w = new Grid(false);
        CompanionYield.State s = new CompanionYield.State();
        // 1,3 neben der Bahn: kein neuer Konflikt (Eintritt unter 1,0).
        decide(s, w, CompanionYield.Zones.NONE, walker(1.8, -3.5, 0, 0.216, 0, 1));
        assertFalse(s.conflict());
        // Mitten auf der Bahn: Konflikt. Danach 1,3 daneben: hält (Austritt erst über 1,6) – sie läuft nicht los.
        decide(s, w, CompanionYield.Zones.NONE, walker(0.5, -3.5, 0, 0.216, 0, 1));
        assertTrue(s.conflict());
        decide(s, w, CompanionYield.Zones.NONE, walker(1.8, -3.0, 0, 0.216, 0, 1));
        assertTrue(s.conflict());
        decide(s, w, CompanionYield.Zones.NONE, walker(2.5, -2.5, 0, 0.216, 0, 1));
        assertFalse(s.conflict());
    }

    @Test
    void doesNotFlipSidesWhileHisHeadingWavers() {
        // Er schlingert ±8° je Aufruf; Vera läuft wirklich (Simulation), jede Entscheidung bleibt auf einer Seite.
        Sim sim = new Sim(new Grid(false), 0.5, 0.5, 0.5, -3.5);
        Set<Boolean> east = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            double a = Math.toRadians(i % 2 == 0 ? 8 : -8);
            CompanionYield.Decision d = sim.step(0.216 * Math.sin(a), 0.216 * Math.cos(a));
            if (d.moving()) {
                east.add(d.x() > 0.5);
            }
        }
        assertEquals(1, east.size(), "eine Seite: " + east);
        assertTrue(sim.minDist >= 0.6, "berührt: " + sim.minDist);
    }

    /**
     * Mini-Simulation, 1 Aufruf = 2 Ticks. Vera: befohlenes Tempo v wirkt über 2 Ticks als v × 1,546 (Bodenreibung
     * 0,6 × 0,91 nach dem ersten Tick – Rechnung aus LivingEntity.travelInAir, nicht gemessen). Spieler: läuft u je
     * Tick, Geschwindigkeit nur über Motion (wie im Plugin).
     */
    static class Sim {
        final Grid w;
        final CompanionYield.State s = new CompanionYield.State();
        final CompanionYield.Motion pm = new CompanionYield.Motion();
        final CompanionYield.Motion vm = new CompanionYield.Motion();
        double vx;
        double vz;
        double px;
        double pz;
        double minDist = Double.MAX_VALUE;
        int moves;

        Sim(Grid w, double vx, double vz, double px, double pz) {
            this.w = w;
            this.vx = vx;
            this.vz = vz;
            this.px = px;
            this.pz = pz;
            pm.update(px, pz);
            vm.update(vx, vz);
        }

        int starts;
        boolean wasMoving;

        CompanionYield.Decision step(double ux, double uz) {
            double ll = Math.hypot(ux, uz);
            return step(ux, uz, ll < 1.0e-9 ? 0 : ux / ll, ll < 1.0e-9 ? 1 : uz / ll);
        }

        CompanionYield.Decision step(double ux, double uz, double lookX, double lookZ) {
            CompanionYield.Person p = new CompanionYield.Person(px, 64, pz, pm.vx(), pm.vz(), 64 + EYE, lookX, 0,
                    lookZ, 0, false, true, false);
            CompanionYield.Decision d = CompanionYield.decide(s, w, CompanionYield.Zones.NONE, vx, 64, vz, vm.vx(),
                    vm.vz(), List.of(p));
            if (d.moving() && !wasMoving) {
                starts++;
            }
            wasMoving = d.moving();
            if (d.moving()) {
                moves++;
                double dx = d.x() - vx;
                double dz = d.z() - vz;
                double h = Math.hypot(dx, dz);
                double go = Math.min(h, d.speed() * 1.546);
                if (h > 1.0e-9) {
                    vx += dx / h * go;
                    vz += dz / h * go;
                }
            }
            // Zwischen den Aufrufen: auf halbem Weg auch messen.
            minDist = Math.min(minDist, Math.hypot(px + ux - vx, pz + uz - vz));
            px += ux * 2;
            pz += uz * 2;
            minDist = Math.min(minDist, Math.hypot(px - vx, pz - vz));
            pm.update(px, pz);
            vm.update(vx, vz);
            return d;
        }
    }

    @Test
    void walkingOrSprintingPlayerPassesWithoutTouchingHer() {
        // Messung: kleinster Abstand Mitte zu Mitte (Berührung unter 0,6), Start aus dem Stand im Ruheabstand.
        StringBuilder out = new StringBuilder();
        for (double speed : new double[] {0.216, 0.281}) {
            for (double start : new double[] {3.0, 4.0}) {
                Sim sim = new Sim(new Grid(false), 0.5, 0.5, 0.5, 0.5 - start);
                for (int i = 0; i < 40; i++) {
                    sim.step(0, speed);
                }
                out.append(String.format("v=%.3f start=%.1f min=%.2f; ", speed, start, sim.minDist));
                if (speed < 0.25) {
                    assertTrue(sim.minDist >= 0.6, "gehend berührt: " + out);
                }
            }
        }
        // Gegenprobe: heutiges Verhalten (sie bleibt stehen, weil der Abstand sinkt) – er läuft durch sie hindurch.
        Sim still = new Sim(new Grid(false), 0.5, 0.5, 0.5, -2.5) {
            @Override
            CompanionYield.Decision step(double ux, double uz) {
                minDist = Math.min(minDist, Math.hypot(px - vx, pz - vz));
                px += ux * 2;
                pz += uz * 2;
                minDist = Math.min(minDist, Math.hypot(px - vx, pz - vz));
                return CompanionYield.Decision.STAY;
            }
        };
        for (int i = 0; i < 40; i++) {
            still.step(0, 0.216);
        }
        System.out.println("vorbeigang: " + out + "heute (stehen bleiben): min=" + String.format("%.2f",
                still.minDist));
        assertTrue(still.minDist < 0.6);
    }

    @Test
    void inTheAimedRayOfAWorkingPlayerSheMovesWithinTwoCalls() {
        Grid w = new Grid(false);
        CompanionYield.State s = new CompanionYield.State();
        // Er steht 3 südlich, schaut über Veras Brust auf einen Block 4,4 Blöcke weit.
        CompanionYield.Person p = aiming(0.5, -2.5, 0.5, 65.0, 1.9, true, false);
        assertFalse(decide(s, w, CompanionYield.Zones.NONE, p).moving());
        CompanionYield.Decision d = decide(s, w, CompanionYield.Zones.NONE, p);
        assertEquals(CompanionYield.Reason.RAY, d.reason());
        assertFalse(CompanionYield.rayHits(p, d.x(), d.y(), d.z(), CompanionYield.RAY_PAD_EXIT), "aus dem Strahl");
    }

    @Test
    void aClickOnHerCountsAsBeingInTheWayAtOnce() {
        Grid w = new Grid(false);
        CompanionYield.State s = new CompanionYield.State();
        s.bumped();
        CompanionYield.Decision d = decide(s, w, CompanionYield.Zones.NONE,
                aiming(0.5, -2.5, 0.5, 65.0, 1.9, true, false));
        assertEquals(CompanionYield.Reason.RAY, d.reason());
    }

    @Test
    void lookingAtHerWhileTalkingIsFineButTooLongInTheCenterOtherwiseIsNot() {
        Grid w = new Grid(false);
        CompanionYield.State talk = new CompanionYield.State();
        for (int i = 0; i < 40; i++) {
            assertFalse(decide(talk, w, CompanionYield.Zones.NONE,
                    aiming(0.5, -2.5, 0.5, 65.5, 0.5, false, true)).moving());
        }
        CompanionYield.State look = new CompanionYield.State();
        CompanionYield.Decision d = null;
        int calls = 0;
        while (calls < 40) {
            calls++;
            d = decide(look, w, CompanionYield.Zones.NONE, aiming(0.5, -2.5, 0.5, 65.5, 0.5, false, false));
            if (d.moving()) {
                break;
            }
        }
        assertEquals(CompanionYield.Reason.VIEW, d.reason());
        assertEquals(CompanionYield.VIEW_DWELL, calls);
        double ang = Math.toDegrees(Math.atan2(Math.abs(d.x() - 0.5), d.z() + 2.5));
        assertTrue(ang >= 30, "aus der Blickmitte: " + ang + "° " + d);
    }

    @Test
    void aGlanceAcrossHerDoesNotMoveHer() {
        Grid w = new Grid(false);
        CompanionYield.State s = new CompanionYield.State();
        for (int i = 0; i < 60; i++) {
            boolean atHer = i % 20 < 10;
            CompanionYield.Person p = atHer ? aiming(0.5, -2.5, 0.5, 65.5, 0.5, false, false)
                    : aiming(0.5, -2.5, 6.5, 65.5, -2.5, false, false);
            assertFalse(decide(s, w, CompanionYield.Zones.NONE, p).moving(), "Aufruf " + i);
        }
    }

    @Test
    void tooCloseForASecondStepsBackToComfortDistance() {
        Grid w = new Grid(false);
        CompanionYield.State s = new CompanionYield.State();
        CompanionYield.Decision d = null;
        int calls = 0;
        while (calls < 30) {
            calls++;
            d = decide(s, w, CompanionYield.Zones.NONE, walker(0.5, -0.5, 0, 0, 1, 0));
            if (d.moving()) {
                break;
            }
        }
        assertEquals(CompanionYield.Reason.NEAR, d.reason());
        assertEquals(CompanionYield.NEAR_DWELL, calls);
        assertTrue(Math.hypot(d.x() - 0.5, d.z() + 0.5) >= CompanionYield.COMFORT_MIN, d.toString());
        assertEquals(CompanionYield.CALM_SPEED, d.speed());
    }

    @Test
    void leavesADoorwayALadderAndStairsAfterASecondButNotANiche() {
        for (String what : List.of("door", "ladder", "stairs")) {
            Grid w = new Grid(false);
            switch (what) {
                case "door" -> {
                    w.doors.add(Grid.k(0, 64, 0));
                    w.doors.add(Grid.k(0, 65, 0));
                }
                case "ladder" -> w.ladders.add(Grid.k(0, 64, 0));
                default -> w.stairs.add(Grid.k(0, 64, 0));
            }
            CompanionYield.State s = new CompanionYield.State();
            CompanionYield.Decision d = null;
            for (int i = 0; i < 30 && (d == null || !d.moving()); i++) {
                d = decide(s, w, CompanionYield.Zones.NONE, walker(4.5, 0.5, 0, 0, 1, 0));
            }
            assertEquals(CompanionYield.Reason.SPOT, d.reason(), what);
            assertEquals(0.0, CompanionYield.spotCost(w, CompanionYield.Zones.NONE, (int) Math.floor(d.x()),
                    (int) d.y(), (int) Math.floor(d.z())), what + " → " + d);
        }
        // Nische: Fels ringsum, nur nach Norden offen – kein Grund zu gehen.
        Grid rock = new Grid(true);
        rock.tunnelZ(0, -6, 0);
        assertEquals(0.0, CompanionYield.spotCost(rock, CompanionYield.Zones.NONE, 0, 64, 0));
        assertEquals(3.0, CompanionYield.spotCost(rock, CompanionYield.Zones.NONE, 0, 64, -3));
    }

    @Test
    void neverPicksACellWhereSomeoneIsBuilding() {
        Grid w = new Grid(false);
        // Ganze Osthälfte ist Baustelle: Obwohl Osten seine Linke wäre, geht sie nach Westen.
        CompanionYield.Zones busy = (x, y, z) -> x >= 1;
        CompanionYield.Decision d = decide(new CompanionYield.State(), w, busy, walker(0.5, -3.5, 0, 0.216, 0, 1));
        assertEquals(CompanionYield.Reason.PATH, d.reason());
        assertTrue(d.x() < 0.5, d.toString());
    }

    @Test
    void inACorridorWithoutRoomSheLeadsAheadToTheNextNiche() {
        Grid w = new Grid(true);
        w.tunnelZ(0, -10, 10);
        w.air.add(Grid.k(1, 64, 5));
        w.air.add(Grid.k(1, 65, 5));
        CompanionYield.Decision d = decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE,
                walker(0.5, -3.5, 0, 0.216, 0, 1));
        assertEquals(CompanionYield.Reason.LEAD, d.reason());
        assertEquals(1.5, d.x());
        assertEquals(5.5, d.z());
        assertFalse(d.faceOwner(), "voraus läuft man in Laufrichtung");
        // In der Nische angekommen: bleiben, er geht 1 Block daneben vorbei.
        CompanionYield.Decision inNiche = CompanionYield.decide(new CompanionYield.State(), w,
                CompanionYield.Zones.NONE, 1.5, 64, 5.5, 0, 0, List.of(walker(0.5, 2.0, 0, 0.216, 0, 1)));
        assertFalse(inNiche.moving(), inNiche.toString());
        assertTrue(inNiche.holdFollow(), "nicht entgegenlaufen");
    }

    @Test
    void inADeadEndSheAsksToSwap() {
        Grid w = new Grid(true);
        w.tunnelZ(0, -10, 0);
        CompanionYield.Decision d = decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE,
                walker(0.5, -3.5, 0, 0.216, 0, 1));
        assertEquals(CompanionYield.Reason.SWAP, d.reason());
        assertFalse(d.moving());
    }

    @Test
    void inALongCorridorWithoutANicheSheSwapsInsteadOfWalkingAheadOfHim() {
        // WegProbe 2026-11-15: Lief sie ohne Nische weiter vor ihm her, waren es im 1 breiten Gang 10 Berührungen
        // und 22 Aufrufe im Weg.
        Grid w = new Grid(true);
        w.tunnelZ(0, -10, 30);
        CompanionYield.Decision d = decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE,
                walker(0.5, -3.5, 0, 0.216, 0, 1));
        assertEquals(CompanionYield.Reason.SWAP, d.reason(), d.toString());
        assertFalse(d.moving());
    }

    @Test
    void aSecondPlayerWalkingAtHerIsAvoidedToo() {
        Grid w = new Grid(false);
        CompanionYield.Person owner = walker(-2.5, 0.5, 0, 0, 1, 0);
        CompanionYield.Person other = new CompanionYield.Person(4.5, 64, 0.5, -0.216, 0, 64 + EYE, -1, 0, 0, 0, false,
                false, false);
        CompanionYield.Decision d = decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE, owner, other);
        assertEquals(CompanionYield.Reason.PATH, d.reason());
        assertTrue(Math.abs(d.z() - 0.5) >= CompanionYield.TUBE, d.toString());
    }

    @Test
    void aNewSpotIsHeldUnlessHisPathNeedsIt() {
        Grid w = new Grid(false);
        CompanionYield.State s = new CompanionYield.State();
        CompanionYield.Decision d = decide(s, w, CompanionYield.Zones.NONE, walker(0.5, -0.5, 0, 0, 1, 0));
        for (int i = 0; i < 30 && !d.moving(); i++) {
            d = decide(s, w, CompanionYield.Zones.NONE, walker(0.5, -0.5, 0, 0, 1, 0));
        }
        assertEquals(CompanionYield.Reason.NEAR, d.reason());
        // Angekommen; jetzt schaut er sie an – innerhalb HOLD bleibt sie.
        double vx = d.x();
        double vz = d.z();
        CompanionYield.Person looking = new CompanionYield.Person(0.5, 64, -0.5, 0, 0, 64 + EYE, 0, 0, 1, 0, false,
                true, false);
        double lx = vx - 0.5;
        double lz = vz + 0.5;
        double ll = Math.hypot(lx, lz);
        looking = new CompanionYield.Person(0.5, 64, -0.5, 0, 0, 64 + EYE, lx / ll, 0, lz / ll, 0, false, true, false);
        for (int i = 0; i < CompanionYield.HOLD - 1; i++) {
            CompanionYield.Decision h = CompanionYield.decide(s, w, CompanionYield.Zones.NONE, vx, 64, vz, 0, 0,
                    List.of(looking));
            assertFalse(h.moving(), "Aufruf " + i + ": " + h);
        }
        // Läuft er auf sie zu, weicht sie trotzdem sofort.
        double sx = (vx - 0.5) / ll * 0.216;
        double sz = (vz + 0.5) / ll * 0.216;
        CompanionYield.Decision path = CompanionYield.decide(s, w, CompanionYield.Zones.NONE, vx, 64, vz, 0, 0,
                List.of(new CompanionYield.Person(0.5, 64, -0.5, sx, sz, 64 + EYE, lx / ll, 0, lz / ll, 0, false,
                        true, false)));
        assertEquals(CompanionYield.Reason.PATH, path.reason());
    }

    @Test
    void anApproachingOwnerKeepsHerFromWalkingTowardHim() {
        // 6 Blöcke weg, kommt schräg auf sie zu (Vorbeigang 1,2 – kein Ausweichen nötig), aber nicht entgegenlaufen.
        Grid w = new Grid(false);
        CompanionYield.Decision d = decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE,
                walker(1.7, -5.5, 0, 0.216, 0, 1));
        assertFalse(d.moving());
        assertTrue(d.holdFollow());
    }

    @Test
    void playerHighAboveIsNoPathConflict() {
        Grid w = new Grid(false);
        CompanionYield.Person flyer = new CompanionYield.Person(0.5, 70, -3.5, 0, 0.5, 70 + EYE, 0, 0, 1, 0, false,
                true, false);
        assertFalse(decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE, flyer).moving());
    }

    @Test
    void motionSmoothsWalkingAndIgnoresTeleports() {
        CompanionYield.Motion m = new CompanionYield.Motion();
        double z = 0;
        for (int i = 0; i < 4; i++) {
            m.update(0, z);
            z += 0.432;
        }
        assertEquals(0.216, m.vz(), 0.01);
        m.update(0, z + 30);
        assertEquals(0.0, m.vz());
        assertNotEquals(Double.NaN, m.vx());
    }

    @Test
    void blockLookupsPerCallStaySmall() {
        Grid w = new Grid(false);
        CompanionYield.State s = new CompanionYield.State();
        CompanionYield.Person standing = walker(0.5, -2.5, 0, 0, 1, -1);
        w.lookups = 0;
        for (int i = 0; i < 100; i++) {
            decide(s, w, CompanionYield.Zones.NONE, standing);
        }
        long rest = w.lookups;
        w.lookups = 0;
        decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE, walker(0.5, -3.5, 0, 0.216, 0, 1));
        long dodge = w.lookups;
        Grid tunnel = new Grid(true);
        tunnel.tunnelZ(0, -10, 10);
        tunnel.air.add(Grid.k(1, 64, 5));
        tunnel.air.add(Grid.k(1, 65, 5));
        tunnel.lookups = 0;
        decide(new CompanionYield.State(), tunnel, CompanionYield.Zones.NONE, walker(0.5, -3.5, 0, 0.216, 0, 1));
        long lead = tunnel.lookups;
        System.out.printf("lookups: ruhig %.1f je Aufruf, ausweichen %d, voraus im Gang %d%n", rest / 100.0, dodge,
                lead);
        assertTrue(rest / 100.0 <= 10, "ruhig: " + rest);
        assertTrue(dodge <= 400, "ausweichen: " + dodge);
        assertTrue(lead <= 1200, "voraus: " + lead);
    }

    @Test
    void restlessnessWhileHeLooksAroundOrPottersAbout() {
        // Messung „Zappeln“: Platzwechsel je Minute (600 Aufrufe), wenn er steht und den Blick langsam kreisen lässt
        // (18°/s), und wenn er auf 2 Blöcken hin und her tritt (0,1 Block/Tick, Richtungswechsel alle 1,5 s).
        Sim look = new Sim(new Grid(false), 0.5, 3.5, 0.5, 0.5);
        for (int i = 0; i < 600; i++) {
            double a = Math.toRadians(1.8 * i);
            look.step(0, 0, Math.sin(a), Math.cos(a));
        }
        Sim potter = new Sim(new Grid(false), 0.5, 3.5, 0.5, 0.5);
        for (int i = 0; i < 600; i++) {
            double dir = (i / 15) % 2 == 0 ? 1 : -1;
            potter.step(0.1 * dir, 0, 0, 1);
        }
        System.out.printf("zappeln: blick kreist %d Platzwechsel/min, hin und her %d Platzwechsel/min%n",
                look.starts, potter.starts);
        assertTrue(look.starts <= 6, "blick: " + look.starts);
        assertTrue(potter.starts <= 6, "hin und her: " + potter.starts);
    }

    @Test
    void timePerCall() {
        // Nur Messung (Desktop, nicht Celeron): ns je Aufruf ruhig und beim Ausweichen.
        Grid w = new Grid(false);
        List<CompanionYield.Person> standing = new ArrayList<>(List.of(walker(0.5, -2.5, 0, 0, 1, -1)));
        List<CompanionYield.Person> walking = List.of(walker(0.5, -3.5, 0, 0.216, 0, 1));
        CompanionYield.State s = new CompanionYield.State();
        for (int i = 0; i < 200_000; i++) {
            CompanionYield.decide(s, w, CompanionYield.Zones.NONE, 0.5, 64, 0.5, 0, 0, standing);
            CompanionYield.decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE, 0.5, 64, 0.5, 0, 0,
                    walking);
        }
        int n = 200_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            CompanionYield.decide(s, w, CompanionYield.Zones.NONE, 0.5, 64, 0.5, 0, 0, standing);
        }
        long t1 = System.nanoTime();
        int m = 20_000;
        for (int i = 0; i < m; i++) {
            CompanionYield.decide(new CompanionYield.State(), w, CompanionYield.Zones.NONE, 0.5, 64, 0.5, 0, 0,
                    walking);
        }
        long t2 = System.nanoTime();
        System.out.printf("zeit: ruhig %.0f ns je Aufruf, ausweichen %.0f ns je Entscheidung (Gitter-Welt, HashSet)%n",
                (t1 - t0) / (double) n, (t2 - t1) / (double) m);
    }
}
