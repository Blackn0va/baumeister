package com.blackn0va.baumeister;

import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.util.Vector;

/**
 * Der sichtbare Baumeister: eine Mannequin-Entity (1.21.9+), also ein Spielermodell ohne Spieler.
 *
 * Warum Mannequin statt Citizens: kein Fremdplugin, kein Fake-Player-Paket, kein NMS – das
 * übersteht Spigot-Updates. Die Entity hat keine KI; jede Bewegung ist ein kleiner Teleport pro
 * Tick, den der Client flüssig interpoliert. Sie fliegt (keine Schwerkraft) wie ein Spieler im
 * Kreativmodus, damit sie Dachblöcke ohne Gerüst erreicht.
 *
 * Wiederfinden nach Neustart: UUID in config.yml plus PDC-Marke. Taucht beim Chunk-Laden eine
 * markierte Entity mit fremder UUID auf (alter NPC, der nicht geladen war, als ein neuer
 * gespawnt wurde), entfernt {@link BaumeisterListener} sie.
 */
final class BuilderNpc {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final double EYE_HEIGHT = 1.62;

    private final JavaPlugin plugin;
    private final NamespacedKey marker;
    private final NamespacedKey helperMarker;
    private String name;
    private UUID id;

    BuilderNpc(JavaPlugin plugin, String name) {
        this.plugin = plugin;
        this.marker = new NamespacedKey(plugin, "npc");
        this.helperMarker = new NamespacedKey(plugin, "helfer");
        this.name = name;
        String raw = plugin.getConfig().getString("npc.uuid", "");
        this.id = raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }

    void rename(String newName) {
        this.name = newName;
        Mannequin m = get();
        if (m != null) {
            m.setCustomName(newName);
        }
    }

    String name() {
        return name;
    }

    boolean isOurs(Entity e) {
        return e.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    boolean isCurrent(Entity e) {
        return e.getUniqueId().equals(id);
    }

    /** Nur geladen und gültig – sonst null. */
    Mannequin get() {
        if (id == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(id);
        return e instanceof Mannequin m && m.isValid() ? m : null;
    }

    /** Holt den NPC an den Bauplatz; existiert er nicht (oder nicht geladen), entsteht ein neuer. */
    Mannequin ensureNear(Location near) {
        Mannequin m = get();
        if (m == null) {
            return spawn(near, null);
        }
        if (!m.getWorld().equals(near.getWorld()) || m.getLocation().distanceSquared(near) > 48 * 48) {
            m.teleport(near);
        }
        return m;
    }

    Mannequin spawn(Location loc, PlayerProfile skin) {
        remove();
        Mannequin m = loc.getWorld().spawn(loc, Mannequin.class, e -> {
            e.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
            e.setCustomName(name);
            e.setCustomNameVisible(true);
            // Spigot und Paper verschieden (MannequinCompat).
            MannequinCompat.description(e, "bereit");
            MannequinCompat.showDescription(e);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setSilent(true);
            e.setCollidable(false);
            e.setImmovable(true);
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            if (skin != null) {
                MannequinCompat.skin(e, skin);
            }
        });
        id = m.getUniqueId();
        plugin.getConfig().set("npc.uuid", id.toString());
        plugin.saveConfig();
        LOG.info(() -> String.format("npc | gespawnt | uuid=%s welt=%s x=%d y=%d z=%d", id,
                loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        return m;
    }

    void remove() {
        Mannequin m = get();
        if (m != null) {
            m.remove();
        }
        id = null;
        plugin.getConfig().set("npc.uuid", "");
        plugin.saveConfig();
    }

    void status(String text) {
        Mannequin m = get();
        if (m != null) {
            MannequinCompat.description(m, text);
        }
    }

    void hold(Material material) {
        hold(get(), material);
    }

    void lookAt(Location target) {
        lookAt(get(), target);
    }

    boolean stepToward(Location goal, Location lookTarget, double maxStep) {
        return stepToward(get(), goal, lookTarget, maxStep);
    }

    // ---------------------------------------------------------------- für Haupt-NPC und Helfer

    static void hold(Mannequin m, Material material) {
        if (m == null) {
            return;
        }
        EntityEquipment eq = m.getEquipment();
        if (eq != null) {
            eq.setItemInMainHand(material == null || !material.isItem() || material.isAir()
                    ? null : new ItemStack(material));
        }
    }

    static void lookAt(Mannequin m, Location target) {
        if (m != null) {
            Location l = facing(m.getLocation(), target);
            m.setRotation(l.getYaw(), l.getPitch());
        }
    }

    /**
     * Ein Stück auf das Ziel zu, Blick auf lookTarget. Liefert true, wenn angekommen.
     * Sprünge über 32 Blöcke (anderer Bauplatz) werden direkt teleportiert.
     */
    static boolean stepToward(Mannequin m, Location goal, Location lookTarget, double maxStep) {
        if (m == null) {
            return false;
        }
        Location cur = m.getLocation();
        if (!cur.getWorld().equals(goal.getWorld()) || cur.distanceSquared(goal) > 32 * 32) {
            m.teleport(facing(goal.clone(), lookTarget));
            return true;
        }
        Vector delta = goal.toVector().subtract(cur.toVector());
        double dist = delta.length();
        Location next = dist <= maxStep ? goal.clone() : cur.clone().add(delta.multiply(maxStep / dist));
        m.teleport(facing(next, lookTarget));
        return dist <= maxStep;
    }

    /**
     * Helfer für große Bauten: wie der Baumeister, aber nicht persistent – nach einem Absturz
     * oder Neustart stünden sonst herrenlose Helfer herum. Die PDC-Marke lässt
     * {@link BaumeisterListener} übrig gebliebene beim Chunk-Laden entfernen.
     */
    Mannequin spawnHelper(Location loc, int number) {
        return loc.getWorld().spawn(loc, Mannequin.class, e -> {
            e.getPersistentDataContainer().set(helperMarker, PersistentDataType.BYTE, (byte) 1);
            e.setCustomName(name + "s Helfer " + number);
            e.setCustomNameVisible(true);
            MannequinCompat.description(e, "hilft mit");
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setSilent(true);
            e.setCollidable(false);
            e.setImmovable(true);
            e.setPersistent(false);
            e.setRemoveWhenFarAway(false);
        });
    }

    boolean isHelper(Entity e) {
        return e.getPersistentDataContainer().has(helperMarker, PersistentDataType.BYTE);
    }

    static Location eye(Location feet) {
        return feet.clone().add(0, EYE_HEIGHT, 0);
    }

    /** Setzt Yaw/Pitch von from so, dass die Augen auf target zeigen. */
    static Location facing(Location from, Location target) {
        Location eye = eye(from);
        Vector d = target.toVector().subtract(eye.toVector());
        if (d.lengthSquared() < 1.0e-6) {
            return from;
        }
        Location out = from.clone();
        out.setDirection(d);
        return out;
    }
}
