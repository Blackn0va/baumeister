package com.blackn0va.baumeister.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Spieler-Bauweise: Die Figur fliegt in Reichweite, schaut hin, nimmt den Block in die Hand und setzt ihn mit einem
 * echten Rechtsklick – wie ein Spieler im Kreativmodus. Der Server prüft dabei alles wie bei dir (Reichweite, Plot,
 * Region), es braucht keine OP-Rechte.
 *
 * Der genaue Zustand (Treppe oben/unten, Türseite, Hebel an der Wand …) kommt über die Item-Komponente block_state
 * (BlockItemStateProperties): Das Spiel übernimmt beim Setzen jede Eigenschaft daraus. Nur wo der Block selbst von der
 * Klickfläche abhängt (Wandfackel statt Fackel, Hebel an Wand/Decke/Boden), wählt clickFace die passende Fläche.
 * Flüssigkeiten setzt ein Rechtsklick nicht verlässlich (der Eimer zielt mit dem Blick, nicht mit dem Treffer) – sie
 * zählen als ausgelassen, in der Befehls-Bauweise gehen sie.
 *
 * Threads: Client-Thread. Fehler: Ziele ohne Halt werden zurückgestellt und am Ende ausgelassen, nie erzwungen.
 */
final class PlayerPlacer {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("Baumeister-Client");

    /** Hotbar-Platz, den der Bau benutzt (der neunte – meist frei). */
    static final int SLOT = 8;

    private final ClientSettings settings;
    private double budget;
    private Vec3 lastPos;
    private int stuckTicks;
    /** Weg zum Standplatz für pathFor (Wegpunkte = Fußzellen), gesucht von findPath. */
    private List<BlockPos> path = List.of();
    private int pathIndex;
    private BlockPos pathFor;
    private int replans;
    /** Ziele ohne Klickfläche: Versuche je Stelle (nach 3 ausgelassen), je Bau und Durchgang (DeferLedger). */
    private final DeferLedger<BlockPos> ledger = new DeferLedger<>();
    private ClientBuild ledgerJob;
    private int ledgerPass;
    /** Zählt jeden Aufruf von tick(). */
    private int ticks;

    PlayerPlacer(ClientSettings settings) {
        this.settings = settings;
    }

    /** false = Kreativmodus fehlt (der Aufrufer pausiert mit Grund). */
    boolean tick(Minecraft mc, ClientBuild job) {
        ticks++;
        if (DeferLedger.needsReset(ledgerJob, ledgerPass, job, job.passes)) {
            ledger.reset();
            ledgerJob = job;
            ledgerPass = job.passes;
        }
        LocalPlayer p = mc.player;
        if (!p.getAbilities().instabuild) {
            return false;
        }
        if (p.getAbilities().mayfly && !p.getAbilities().flying) {
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
        }
        budget = Math.min(budget + settings.blocksPerSecond / 20.0, 20);
        while (budget >= 1 && job.current() != null) {
            ClientBuild.Target t = job.current();
            BlockState now = mc.level.getBlockState(t.pos());
            if (ClientBuild.sameIntent(now, t.state()) || t.companion() && now.is(t.state().getBlock())) {
                job.next++;
                continue;
            }
            Vec3 eye = p.getEyePosition();
            Vec3 aim = Vec3.atCenterOf(t.pos());
            if (eye.distanceTo(aim) > reach(p)) {
                if (!approach(mc, p, t.pos())) {
                    // Kein Weg in Reichweite (eingeschlossen, zu weit): zurückstellen statt hängen.
                    stop(p);
                    defer(mc, job, t, "kein_weg");
                    return true;
                }
                return true;
            }
            stop(p);
            remember(job, mc, t.pos());
            if (!now.isAir() && !now.canBeReplaced()) {
                // Erst weg, was im Weg steht (Kreativ: ein Klick), gesetzt wird im nächsten Durchgang.
                look(p, aim);
                mc.gameMode.startDestroyBlock(t.pos(), Direction.UP);
                p.swing(InteractionHand.MAIN_HAND);
                budget--;
                if (t.state().isAir()) {
                    job.placed++;
                    job.next++;
                }
                continue;
            }
            if (t.state().isAir()) {
                job.next++;  // schon Luft bzw. ersetzbar (Gras): nichts zu tun
                continue;
            }
            Item item = t.state().getBlock().asItem();
            if (item == Items.AIR) {
                job.skipped++;
                job.next++;
                continue;
            }
            BlockHitResult hit = clickFace(mc, t);
            if (hit == null) {
                defer(mc, job, t, "keine_flaeche");  // umlaut-ok: Logcode; der Halt entsteht oft erst mit den nächsten Blöcken
                return true;  // Tick beenden: Der Server bestätigt gerade gesetzte Nachbarn erst im nächsten
            }
            ItemStack stack = new ItemStack(item);
            Map<String, String> props = properties(t.state());
            if (!props.isEmpty()) {
                stack.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(props));
            }
            mc.gameMode.handleCreativeModeItemAdd(stack, 36 + SLOT);
            p.getInventory().setItem(SLOT, stack);
            p.getInventory().setSelectedSlot(SLOT);
            look(p, hit.getLocation());
            sneakClick(mc, p, hit);
            p.swing(InteractionHand.MAIN_HAND);
            job.placed++;
            job.next++;
            budget--;
        }
        return true;
    }

    /**
     * Rechtsklick mit gedrücktem Schleichen, wie ein Spieler, der an einen Trichter oder eine Truhe baut. Ohne öffnete der
     * Klick auf einen Trichter dessen Inventar statt zu setzen, und der offene Bildschirm pausierte den Bau (Spieltest
     * 2026-10-31, Maschinenhalle). Dem Server wird Schleichen genau für diesen Klick gemeldet: Eingabe mit shift, Klick,
     * Eingabe wie zuvor – in dieser Reihenfolge verarbeitet er die Pakete. Der Client bekommt dieselbe Vorhersage.
     * Wer das Zurücksetzen weglässt, lässt die Figur auf dem Server dauerhaft schleichen.
     */
    private static void sneakClick(Minecraft mc, LocalPlayer p, BlockHitResult hit) {
        Input was = p.getLastSentInput();
        Input sneaking = new Input(was.forward(), was.backward(), was.left(), was.right(), was.jump(), true,
                was.sprint());
        p.connection.send(new ServerboundPlayerInputPacket(sneaking));
        boolean shift = p.isShiftKeyDown();
        p.setShiftKeyDown(true);
        InteractionResult r = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.setShiftKeyDown(shift);
        if (!r.consumesAction()) {
            BlockState on = mc.level.getBlockState(hit.getBlockPos());
            LOG.info(() -> "bau | klick_abgelehnt | ergebnis=" + r + " ziel=" + hit.getBlockPos().relative(
                    hit.getDirection()).toShortString() + " fläche=" + hit.getDirection() + " auf="
                    + BuiltInRegistries.BLOCK.getKey(on.getBlock()).getPath() + " hand="
                    + p.getMainHandItem().getItem());
        }
        p.connection.send(new ServerboundPlayerInputPacket(was));
    }

    /** Ziel ans Ende stellen; beim dritten Mal auslassen (steht dann in der Schlussmeldung). */
    private void defer(Minecraft mc, ClientBuild job, ClientBuild.Target t, String why) {
        // Zählregel (erst nach 10 Ticks) und Rücksetzen je Bau und Durchgang: DeferLedger.
        int tries = ledger.count(t.pos(), ticks);
        StringBuilder around = new StringBuilder();
        for (Direction d : Direction.values()) {
            around.append(' ').append(d.getName().charAt(0)).append('=').append(BuiltInRegistries.BLOCK.getKey(
                    mc.level.getBlockState(t.pos().relative(d)).getBlock()).getPath());
        }
        LOG.info(() -> "bau | zurueckgestellt | grund=" + why + " versuch=" + tries + " ziel=" + t.pos().toShortString()  // umlaut-ok
                + " block=" + BuiltInRegistries.BLOCK.getKey(t.state().getBlock()).getPath() + " spieler="
                + mc.player.blockPosition().toShortString() + around);
        if (tries >= 3) {
            job.skipped++;
        } else {
            // Ans Ende der eigenen Schicht, nicht der ganzen Liste: Vorher kam ein Trichter, dessen Ofen in derselben
            // Schicht erst später stand, nach dem Dach wieder dran – und war dann eingeschlossen (Spieltest 2026-10-31,
            // „kein_weg“ für 3 Behälter der Schmelzanlage).
            int at = job.next + 1;
            while (at < job.targets.size() && job.targets.get(at).pos().getY() <= t.pos().getY()) {
                at++;
            }
            job.targets.add(at, t);
        }
        job.next++;
    }

    /**
     * Fläche zum Klicken: die eines festen Nachbarn, an dem der Block hängen soll. Wandblöcke (facing zeigt von der
     * Wand weg) und face=floor/ceiling nehmen genau ihren Halt; alles andere den ersten festen Nachbarn, unten zuerst.
     */
    private static BlockHitResult clickFace(Minecraft mc, ClientBuild.Target t) {
        BlockState s = t.state();
        Direction support = null;
        if (s.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            AttachFace face = s.getValue(BlockStateProperties.ATTACH_FACE);
            support = face == AttachFace.FLOOR ? Direction.DOWN : face == AttachFace.CEILING ? Direction.UP
                    : s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        } else if (wallMounted(s)) {
            support = s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        Direction[] order = support != null ? new Direction[] {support}
                : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
                        Direction.UP};
        for (Direction d : order) {
            BlockPos n = t.pos().relative(d);
            BlockState ns = mc.level.getBlockState(n);
            if (!ns.isAir() && !ns.canBeReplaced() && ns.getFluidState().isEmpty()) {
                Direction face = d.getOpposite();
                Vec3 at = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                return new BlockHitResult(at, face, n, false);
            }
        }
        return null;
    }

    /**
     * Hängt an der Wand hinter sich (Wandfackel, Leiter, Wandschild …). Nach dem Registernamen: Vorher nach dem
     * Anzeigenamen – den teilen Wandfackeln aber mit der Stehfackel („block.minecraft.redstone_torch“), die Figur
     * klickte auf den Boden und setzte eine Stehfackel (Spieltest 2026-10-31, Maschinenhalle).
     */
    private static boolean wallMounted(BlockState s) {
        if (!s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return false;
        }
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return id.contains("wall_torch") || id.contains("wall_sign") || id.contains("wall_banner")
                || id.contains("wall_hanging_sign") || id.endsWith("wall_head") || id.endsWith("wall_skull")
                || id.endsWith("wall_fan") || id.equals("ladder");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Map<String, String> properties(BlockState s) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Property<?> prop : s.getProperties()) {
            Property raw = prop;
            out.put(prop.getName(), raw.getName(s.getValue(raw)));
        }
        return out;
    }

    private static void remember(ClientBuild job, Minecraft mc, BlockPos pos) {
        if (!job.undo) {
            job.originals.putIfAbsent(pos.immutable(), mc.level.getBlockState(pos));
        }
    }

    /** Blick wie ein Spieler (sichtbar für alle) – keine Mausbewegung, Control merkt davon nichts. */
    private static void look(LocalPlayer p, Vec3 at) {
        Vec3 d = at.subtract(p.getEyePosition());
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        p.setYRot((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0));
        p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, h)));
    }

    private static double reach(LocalPlayer p) {
        return p.blockInteractionRange() - 0.8;
    }

    /**
     * Zum nächsten Standplatz in Reichweite fliegen, auf einem gesuchten Weg (findPath). Vorher flog die Figur gerade
     * auf einen Punkt zwei Blöcke über dem Ziel zu – unter einem Dach oder hinter einer Wand kam sie dort nie an und
     * hing (Spieltest 2026-10-31, Maschinenhalle, zweimal vom Wunsch). Setzen
     * geht auch durch Wände (der Server prüft nur die Entfernung, keine Sicht) – es genügt also ein freier Platz in
     * Reichweite, der Weg dorthin muss frei sein.
     *
     * @return false, wenn es keinen Weg gibt oder dreimal neu gesucht werden musste
     */
    private boolean approach(Minecraft mc, LocalPlayer p, BlockPos target) {
        boolean fresh = !target.equals(pathFor);
        if (fresh || pathIndex >= path.size() || stuckTicks > 20) {
            replans = fresh ? 0 : replans + 1;
            if (replans > 3) {
                pathFor = null;
                return false;
            }
            pathFor = target;
            stuckTicks = 0;
            // Erst nur Plätze über der Zielschicht: In derselben Schicht stand die Figur in Zellen, die sie gleich
            // bebauen sollte, und der Server lehnte jeden Block in ihr ab (Spieltest 2026-10-31: 135 Lücken im Boden
            // des Hochhauses). Nur wenn es oben keinen Weg gibt (unter einem Dach), jeder freie Platz.
            List<BlockPos> found = findPath(mc, p, target, true);
            if (found == null) {
                found = findPath(mc, p, target, false);
            }
            if (found == null) {
                pathFor = null;
                return false;
            }
            path = found;
            pathIndex = 0;
            if (path.isEmpty()) {
                // Die Suche misst von der Zellenmitte, der Bau vom echten Auge – steht die Figur am Rand der Zelle,
                // fehlen Zehntel. Vorher gab der Bau hier auf und stellte das Ziel zurück, Block für Block (Spieltest
                // 2026-10-31: 13 Blöcke in 10 s). Jetzt in die Mitte der eigenen Zelle.
                path = List.of(BlockPos.containing(p.getX(), p.getY() + 0.1, p.getZ()));
            }
        }
        Vec3 pos = p.position();
        stuckTicks = lastPos != null && pos.distanceTo(lastPos) < 0.03 ? stuckTicks + 1 : 0;
        lastPos = pos;
        BlockPos wp = path.get(pathIndex);
        Vec3 goal = new Vec3(wp.getX() + 0.5, wp.getY() + 0.05, wp.getZ() + 0.5);
        Vec3 d = goal.subtract(pos);
        double len = d.length();
        if (len < 0.3) {
            pathIndex++;
            p.setDeltaMovement(Vec3.ZERO);
            return true;
        }
        p.setDeltaMovement(d.scale(Math.min(0.6, len) / len));
        look(p, Vec3.atCenterOf(target));
        return true;
    }

    /** Suchkasten um das Ziel (Blöcke je Richtung) und höchstens so viele besuchte Zellen. */
    private static final int SEARCH_RADIUS = 32;
    private static final int SEARCH_LIMIT = 80_000;

    /**
     * Breitensuche über freie Fußzellen (Zelle und die darüber ohne Kollision – die Figur ist zwei Blöcke hoch) bis zur
     * nächsten Zelle, deren Augenhöhe das Ziel erreicht und die nicht selbst die Zielzelle belegt (ein Block, in dem
     * die Figur steht, lässt sich nicht setzen). Liefert die Wegpunkte ohne den Start; null = kein Weg.
     */
    static List<BlockPos> findPath(Minecraft mc, LocalPlayer p, BlockPos target, boolean above) {
        double reach = reach(p) - 0.5;  // Abstand zur Prüfung im Bau (echtes Auge statt Zellenmitte)
        Vec3 aim = Vec3.atCenterOf(target);
        BlockPos start = BlockPos.containing(p.getX(), p.getY() + 0.1, p.getZ());
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        Map<BlockPos, BlockPos> prev = new java.util.HashMap<>();
        queue.add(start);
        prev.put(start, start);
        while (!queue.isEmpty() && prev.size() < SEARCH_LIMIT) {
            BlockPos c = queue.poll();
            boolean blocksTarget = c.equals(target) || c.above().equals(target)
                    || above && c.getY() <= target.getY();
            if (!blocksTarget && new Vec3(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5).distanceTo(aim) <= reach) {
                List<BlockPos> way = new java.util.ArrayList<>();
                for (BlockPos k = c; !k.equals(start); k = prev.get(k)) {
                    way.add(0, k);
                }
                return way;
            }
            for (Direction dir : Direction.values()) {
                BlockPos n = c.relative(dir);
                if (prev.containsKey(n) || Math.abs(n.getX() - target.getX()) > SEARCH_RADIUS
                        || Math.abs(n.getY() - target.getY()) > SEARCH_RADIUS
                        || Math.abs(n.getZ() - target.getZ()) > SEARCH_RADIUS || !free(mc, n) || !free(mc, n.above())) {
                    continue;
                }
                prev.put(n, c);
                queue.add(n);
            }
        }
        return null;
    }

    private static boolean free(Minecraft mc, BlockPos pos) {
        return mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    private void stop(LocalPlayer p) {
        p.setDeltaMovement(Vec3.ZERO);
        stuckTicks = 0;
        pathFor = null;
    }

    /** Pause: Figur sofort ruhig, damit der Spieler sie übernimmt, ohne dass sie weitertreibt. */
    void release(LocalPlayer p) {
        p.setDeltaMovement(Vec3.ZERO);
        lastPos = null;
    }
}
