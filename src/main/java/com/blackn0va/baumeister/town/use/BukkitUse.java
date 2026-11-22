package com.blackn0va.baumeister.town.use;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Bukkit-Seite des Benutzens (AK-44): UseWorld über eine Welt, Actor über ein Mannequin. Nur API, kein NMS; alle Typen
 * mit javap in spigot-api 1.21.11-R0.2 und paper-api 26.2.build.135 geprüft (2026-11-22): Openable, Bed (Part,
 * isOccupied), Bisected, Slab, Directional, ArmorStand.setMarker, Entity.addPassenger, World.playSound(Location, String,
 * SoundCategory, float, float), Particle als Enum.
 *
 * Posen: Mannequins nehmen nur STANDING, CROUCHING, SWIMMING, FALL_FLYING, SLEEPING (javap Mannequin.VALID_POSES in
 * paper-26.2 und CraftMannequin.setPose in spigot-1.21.11: anderes wirft IllegalArgumentException). SITTING geht also
 * nicht. Sitzen deshalb als Mitfahrer auf einem unsichtbaren Marker-Rüstungsständer: Der Client zeichnet Mitfahrer mit
 * angewinkelten Beinen (javap LivingEntityRenderer, Client 26.2: isPassenger → HumanoidRenderState.isPassenger), und
 * ein Avatar hängt 0,6 unter dem Mitfahrpunkt (Avatar.DEFAULT_VEHICLE_ATTACHMENT, paper-26.2) – der Ständer steht
 * deshalb auf der Sitzfläche. Er ist nicht persistent und trägt die Bewohner-Marke, damit BaumeisterListener ihn nach
 * einem harten Abbruch wie einen verwaisten Bewohner wegräumt.
 *
 * Threads: Hauptthread. Fehler: Rechteprüfung fail-closed (jede Ausnahme = verboten, einmal gewarnt); Darstellung
 * fail-open (Geräusch/Partikel fehlen, einmal gewarnt).
 */
public final class BukkitUse {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * Rechteprüfung eines Bewohners vor dem Benutzen eines Blocks: ein EntityInteractEvent mit dem Mannequin als
     * Verursacher – dasselbe Ereignis, mit dem Bukkit Druckplatten und Ackerland durch Nicht-Spieler meldet und auf das
     * Schutz-Plugins hören. Eigene Unterklasse nur, damit TownLife.onInteract (sagt jede Bewohner-Interaktion ab) diese
     * Prüfung durchlässt; die Handler-Liste bleibt die von EntityInteractEvent (getHandlers geerbt), also erreicht sie
     * jeden, der auf EntityInteractEvent hört.
     */
    public static final class Check extends EntityInteractEvent {
        public Check(Entity who, Block block) {
            super(who, block);
        }
    }

    private final Server server;
    private final NamespacedKey marker;
    private final BiConsumer<Mannequin, Pose> poser;

    /** poser: Pose fest setzen (im Betrieb MannequinCompat::pose – Spigot setPose(Pose), Paper setPose(Pose, true)). */
    public BukkitUse(Server server, NamespacedKey marker, BiConsumer<Mannequin, Pose> poser) {
        this.server = server;
        this.marker = marker;
        this.poser = poser;
    }

    public UseWorld world(World w) {
        return new UseWorld() {
            @Override
            public String name() {
                return w.getName();
            }

            private org.bukkit.block.Block block(int x, int y, int z) {
                if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) {
                    return null;
                }
                return w.getBlockAt(x, y, z);
            }

            @Override
            public UseWorld.Block at(int x, int y, int z) {
                org.bukkit.block.Block b = block(x, y, z);
                if (b == null) {
                    return null;
                }
                BlockData d = b.getBlockData();
                String facing = d instanceof Directional dir ? dir.getFacing().name() : "";
                String half;
                if (d instanceof Bed bed) {
                    half = bed.getPart().name();
                } else if (d instanceof Slab slab) {
                    half = slab.getType().name();
                } else if (d instanceof Bisected bi) {
                    half = bi.getHalf().name();
                } else {
                    half = "";
                }
                return new UseWorld.Block(b.getType().name(), facing, half, d instanceof Openable o && o.isOpen(),
                        d instanceof Bed bed && bed.isOccupied());
            }

            @Override
            public boolean passable(int x, int y, int z) {
                org.bukkit.block.Block b = block(x, y, z);
                if (b == null) {
                    return false;
                }
                Material m = b.getType();
                // Wie TownLife.BukkitEnv.standNear: isPassable gilt auch für Wasser, Lava, Feuer und Spinnweben.
                return b.isPassable() && !b.isLiquid() && !Tag.FIRE.isTagged(m) && m != Material.COBWEB
                        && m != Material.POWDER_SNOW && m != Material.SWEET_BERRY_BUSH;
            }

            @Override
            public boolean solid(int x, int y, int z) {
                org.bukkit.block.Block b = block(x, y, z);
                if (b == null) {
                    return false;
                }
                Material g = b.getType();
                return g.isSolid() && g != Material.MAGMA_BLOCK && !Tag.LEAVES.isTagged(g);
            }

            @Override
            public boolean setOpen(int x, int y, int z, boolean open) {
                org.bukkit.block.Block b = block(x, y, z);
                // Doppelt gesichert: TownUse ruft nur für Türen, aber auch ein Fehler dort darf nie einen Behälter,
                // Hebel oder sonst etwas anfassen.
                if (b == null || UseBlocks.door(b.getType().name()) == null
                        || !(b.getBlockData() instanceof Openable o)) {
                    return false;
                }
                o.setOpen(open);
                // Mit Physik: Die zweite Türhälfte zieht Minecraft beim Block-Update nach (wie CompanionService).
                b.setBlockData(o, true);
                return true;
            }

            @Override
            public boolean mayUse(UUID who, int x, int y, int z) {
                Entity e = server.getEntity(who);
                org.bukkit.block.Block b = block(x, y, z);
                if (e == null || !e.isValid() || b == null) {
                    return false;
                }
                try {
                    Check c = new Check(e, b);
                    server.getPluginManager().callEvent(c);
                    return !c.isCancelled();
                } catch (RuntimeException ex) {
                    // Fail-closed (ASVS V4.1.5): Ein Schutz-Plugin, das beim Fragen wirft, hat nicht ja gesagt.
                    if (WARNED.add("mayUse")) {
                        LOG.log(Level.WARNING, "stadtleben | nutzen_rechte_fehler | grund=" + ex, ex);
                    }
                    return false;
                }
            }

            @Override
            public void sound(String key, double x, double y, double z) {
                w.playSound(new Location(w, x, y, z), key, SoundCategory.BLOCKS, 1f, 1f);
            }

            @Override
            public boolean playerNear(double x, double y, double z, double r) {
                for (Player p : w.getPlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR) {
                        continue;
                    }
                    Location l = p.getLocation();
                    double dx = l.getX() - x;
                    double dy = l.getY() - y;
                    double dz = l.getZ() - z;
                    if (dx * dx + dy * dy + dz * dz <= r * r) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    public Actor actor(Mannequin m) {
        return new Actor() {
            @Override
            public UUID id() {
                return m.getUniqueId();
            }

            @Override
            public double x() {
                return m.getLocation().getX();
            }

            @Override
            public double y() {
                return m.getLocation().getY();
            }

            @Override
            public double z() {
                return m.getLocation().getZ();
            }

            @Override
            public void face(double x, double y, double z) {
                Location eye = m.getEyeLocation();
                Vector d = new Vector(x - eye.getX(), y - eye.getY(), z - eye.getZ());
                if (d.lengthSquared() < 1.0e-6) {
                    return;
                }
                eye.setDirection(d);
                m.setRotation(eye.getYaw(), eye.getPitch());
            }

            @Override
            public void swing() {
                m.swingMainHand();
            }

            @Override
            public void lie(double x, double y, double z, float yaw) {
                m.setVelocity(new Vector());
                // Ohne Schwerkraft und unbeweglich: Die Liegeform (0,2 hoch) fiele sonst durch die Bettkante auf die
                // Höhe des Bettfußes, und Stöße schöben ihn vom Bett (Mannequin-Physik, Gedächtnis 2026-11-06).
                m.setGravity(false);
                m.setImmovable(true);
                m.teleport(new Location(m.getWorld(), x, y, z, yaw, 0f));
                poser.accept(m, Pose.SLEEPING);
                m.setRotation(yaw, 0f);
            }

            @Override
            public void rise(double x, double y, double z) {
                poser.accept(m, Pose.STANDING);
                // Wie TownLife.prepare: Schwerkraft an, beweglich – sonst liefe er morgens nicht los.
                m.setGravity(true);
                m.setImmovable(false);
                if (m.isValid()) {
                    m.teleport(new Location(m.getWorld(), x, y, z, m.getLocation().getYaw(), 0f));
                }
            }

            @Override
            public Runnable sit(double x, double y, double z, float yaw) {
                World w = m.getWorld();
                ArmorStand seat = w.spawn(new Location(w, x, y, z, yaw, 0f), ArmorStand.class, s -> {
                    s.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
                    s.setMarker(true);
                    s.setInvisible(true);
                    s.setGravity(false);
                    s.setInvulnerable(true);
                    s.setSilent(true);
                    s.setPersistent(false);
                    s.setCollidable(false);
                });
                if (!seat.isValid()) {
                    // Spawn abgesagt (Schutz-Plugin, Spawn-Grenze): nicht sitzen, stehen bleiben.
                    return null;
                }
                m.setVelocity(new Vector());
                m.setRotation(yaw, 0f);
                if (!seat.addPassenger(m)) {
                    seat.remove();
                    return null;
                }
                return () -> {
                    seat.removePassenger(m);
                    seat.remove();
                    if (m.isValid()) {
                        m.teleport(new Location(w, x, y + 0.05, z, m.getLocation().getYaw(), 0f));
                    }
                };
            }

            @Override
            public void effect(String sound, String particle, double x, double y, double z) {
                World w = m.getWorld();
                Location at = new Location(w, x, y, z);
                w.playSound(at, sound, SoundCategory.NEUTRAL, 0.6f, 1f);
                if (particle == null || particle.isEmpty()) {
                    return;
                }
                try {
                    w.spawnParticle(Particle.valueOf(particle), at, 4, 0.25, 0.15, 0.25, 0.01);
                } catch (IllegalArgumentException e) {
                    // Fail-open: Partikel ist Schmuck; ein Name, den diese Serverfassung nicht kennt oder der Daten
                    // braucht, kostet nur die Partikel. Einmal je Name gewarnt.
                    if (WARNED.add("particle:" + particle)) {
                        LOG.warning(() -> "stadtleben | partikel_unbekannt | name=" + particle + " grund=" + e);
                    }
                }
            }
        };
    }
}
