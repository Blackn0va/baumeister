package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.Pos;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/** Chat-Aufträge an den NPC, Hinweis bei Rechtsklick und Aufräumen alter NPC-Kopien. */
final class BaumeisterListener implements Listener {

    /** Gedächtnis löschen (BuildService.Memory): „vergiss“, „vergiss das“, „neu anfangen“. */
    static final Pattern FORGET_CHAT = Pattern.compile("^(?:vergiss(?: das| alles)?|neu anfangen|fang neu an)[.!]?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** „logik: …“, „schaltung …“, „logic …“ – der Rest ist die Formel. */
    static final Pattern LOGIC_CHAT = Pattern.compile("^(?:logik|logic|schaltung)\\s*[:=]?\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    private final BaumeisterPlugin plugin;

    BaumeisterListener(BaumeisterPlugin plugin) {
        this.plugin = plugin;
    }

    /** "Baumeister, bau …", "Baumeister: …", "@Baumeister …" – Groß-/Kleinschreibung egal. */
    static String extractPrompt(String npcName, String message) {
        Pattern p = Pattern.compile("^\\s*@?" + Pattern.quote(npcName) + "\\s*[,:!]?\\s+(.+)$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
        Matcher m = p.matcher(message);
        return m.matches() ? m.group(1).trim() : null;
    }

    /**
     * Die Nachricht bleibt sichtbar – andere sollen sehen, was in Auftrag gegeben wurde.
     * Async-Thread: hier nur erkennen, alles mit Welt und NPC auf den Hauptthread.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage();
        String prompt = extractPrompt(plugin.npc().name(), message);
        Player player = event.getPlayer();
        // Jede Zeile auf den Hauptthread: Ob sie dem Begleiter gilt, hängt auch am Gespräch (Zustand im Hauptthread),
        // nicht nur am Namen. Der Baumeister bekommt sie nur, wenn sie ihn anspricht und der Begleiter sie nicht nimmt.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (plugin.companion() != null && plugin.companion().onChat(player, message)) {
                return;
            }
            if (prompt == null) {
                return;
            }
            if (!player.hasPermission("baumeister.bauen")) {
                Texts.info(player, "Du darfst dem Baumeister keine Aufträge geben.");
                return;
            }
            // „los“ und „<Ort> nachbauen“ gehen ohne LLM an den Stadtbau.
            if (plugin.city().handleChat(player, prompt)) {
                return;
            }
            // Bauvorschau: „zeig mir …“ plant nur; „ja“/„nein“ wirken nur bei offener Vorschau, sonst geht es weiter.
            if (plugin.service().comfort().previewChat(player, prompt)) {
                return;
            }
            // „logik <Formel>“ baut Admins die Schaltung sofort (LogicCircuit, kein LLM). Andere Spieler gehen den
            // normalen Weg: Das LLM kennt op logic, und der Bau läuft durch die Rechteprüfung.
            if (FORGET_CHAT.matcher(prompt).matches()) {
                Texts.info(player, plugin.service().forget(player.getUniqueId())
                        ? "Vergessen – der nächste Auftrag fängt neu an." : "Ich hatte mir nichts gemerkt.");
                return;
            }
            Matcher logic = LOGIC_CHAT.matcher(prompt);
            if (logic.matches() && player.hasPermission("baumeister.admin")) {
                Location feet = player.getLocation().getBlock().getLocation();
                Cardinal facing = Cardinal.fromYaw(player.getLocation().getYaw());
                Pos ahead = new Orientation(facing).toWorldOffset(new Pos(0, 0, 2));
                plugin.service().requestLogicAt(player, feet.clone().add(ahead.x(), ahead.y(), ahead.z()), facing,
                        logic.group(1));
                return;
            }
            // Steuerwörter nie ans LLM (2026-10-28): Vorher wirkten sie nur bei laufender Stadt, sonst ging „stopp“
            // während eines Normalbaus als neuer Auftrag ans LLM, statt den Bau anzuhalten. Regeln in ChatIntent.
            ChatIntent.Kind kind = ChatIntent.parse(prompt);
            switch (ChatIntent.route(kind, plugin.city().running(), plugin.service().busy())) {
                case CITY -> controlCity(player, kind);
                case BUILD_SERVICE -> controlBuild(player, kind);
                case NOT_RUNNING -> Texts.info(player, "Gerade läuft kein Stadtbau.");
                case LLM -> plugin.service().requestFromPlayer(player, prompt);
            }
        });
    }

    /** Dasselbe Rechte-Gate wie /bm: steuern nur Admins, „komm“ und „fortschritt“ darf jeder mit baumeister.bauen. */
    private void controlCity(Player player, ChatIntent.Kind kind) {
        if (kind != ChatIntent.Kind.COME && kind != ChatIntent.Kind.STATUS
                && !player.hasPermission("baumeister.admin")) {
            Texts.info(player, "Den Stadtbau steuern dürfen nur Admins.");
            return;
        }
        // false nur, wenn die Stadt zwischen route() und hier geendet hat – dann wenigstens antworten.
        if (!plugin.city().control(player, ChatIntent.cityAction(kind), null)) {
            Texts.info(player, "Gerade läuft kein Stadtbau.");
        }
    }

    /** Stopp, Status, Helfer und Rückgängig für den normalen Bau – Rechte prüft BuildService (mayTouch). */
    private void controlBuild(Player player, ChatIntent.Kind kind) {
        switch (kind) {
            // Während der Planung antwortet stop() „Ich plane noch – stoppen geht erst, wenn ich baue.“
            case STOP -> plugin.service().stop(player);
            case STATUS -> Texts.info(player, "Zustand: " + plugin.service().statusLine());
            // Je zwei wie im Stadtbau, damit man nicht achtmal schreiben muss.
            case MORE_HELPERS -> plugin.service().helpers(player, "+2");
            case FEWER_HELPERS -> plugin.service().helpers(player, "-2");
            case UNDO -> {
                // Wie /bm rückgängig: Ein laufender Stadtbau legt seinen Rückgängig-Eintrag erst beim Ende ab.
                if (plugin.city().running() && player.hasPermission("baumeister.admin")) {
                    plugin.city().control(player, "stop", null);
                }
                plugin.service().undo(player);
            }
            // route() schickt nur STOP, STATUS und UNDO hierher; alles andere wäre ein Fehler in ChatIntent.
            default -> throw new IllegalStateException("Steuerwort " + kind + " gehört nicht zum normalen Bau");
        }
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (plugin.companion() != null) {
            plugin.companion().join(event.getPlayer());
        }
    }

    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (plugin.companion() != null) {
            plugin.companion().died(event.getEntity());
        }
    }

    @EventHandler
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.EnderDragon && plugin.companion() != null) {
            plugin.companion().dragonKilled(event.getEntity().getWorld());
        }
    }

    /**
     * Spielmodus gewechselt: Vera und das Abenteuer erfahren es sofort (ModeWatch). Das Ereignis kommt VOR dem Wechsel,
     * deshalb einen Tick später – dann liefert getGameMode() schon den neuen Modus. MONITOR + ignoreCancelled: Hat ein
     * anderes Plugin den Wechsel abgelehnt, ist nichts passiert.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(org.bukkit.event.player.PlayerGameModeChangeEvent event) {
        if (plugin.companion() != null) {
            org.bukkit.entity.Player p = event.getPlayer();
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    plugin.companion().gameModeChanged(p);
                }
            });
        }
    }

    /** Levelaufstieg: Vera freut sich mit (CompanionService.cheer). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevel(org.bukkit.event.player.PlayerLevelChangeEvent event) {
        if (plugin.companion() != null && event.getNewLevel() > event.getOldLevel()) {
            plugin.companion().cheer(event.getPlayer());
        }
    }

    /** Schaden am Spieler: Vera kommentiert Stürze und kaum noch Leben (CompanionService.hurt, mit Pause je Anlass). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(org.bukkit.event.entity.EntityDamageEvent event) {
        if (plugin.companion() != null && event.getEntity() instanceof org.bukkit.entity.Player p) {
            plugin.companion().hurt(p, event.getCause(), event.getFinalDamage());
        }
    }

    /** Abgebaut: Diamanten und Antiker Schrott (CompanionService.mined). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMined(org.bukkit.event.block.BlockBreakEvent event) {
        if (plugin.companion() != null) {
            plugin.companion().mined(event.getPlayer(), event.getBlock().getType());
        }
    }

    /** Hunger sinkt (CompanionService.hungry). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFood(org.bukkit.event.entity.FoodLevelChangeEvent event) {
        if (plugin.companion() != null && event.getEntity() instanceof org.bukkit.entity.Player p
                && event.getFoodLevel() < p.getFoodLevel()) {
            plugin.companion().hungry(p, event.getFoodLevel());
        }
    }

    /** Fortschritt geschafft – Rezepte zählen nicht (die „schafft“ man beim Aufheben dutzendfach). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
        if (plugin.companion() != null && !event.getAdvancement().getKey().getKey().startsWith("recipes/")) {
            plugin.companion().cheer(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.companion() != null) {
            plugin.companion().quit(event.getPlayer());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (plugin.companion() != null && plugin.companion().isCompanion(event.getRightClicked())) {
            event.setCancelled(true);
            Texts.info(event.getPlayer(), "Sprich mich im Chat an: " + plugin.companion().name()
                    + ", was kann ich für dich tun?");
            return;
        }
        if (plugin.npc().isOurs(event.getRightClicked())) {
            event.setCancelled(true);
            Texts.info(event.getPlayer(), "Schreib in den Chat: " + plugin.npc().name()
                    + ", bau mir ein kleines Haus aus Fichtenholz");
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            // Alte NPC-Kopien und Helfer, die einen Absturz überlebt haben (Helfer sind nicht persistent,
            // aber ein harter Abbruch zwischen Speichern und Entfernen ist nicht ausgeschlossen).
            if (plugin.npc().isOurs(e) && !plugin.npc().isCurrent(e) || plugin.npc().isHelper(e)) {
                e.remove();
            } else if (plugin.companion() != null && plugin.companion().isStale(e)) {
                // Begleiter sind nicht persistent; übrig bleibt einer nur nach einem harten Abbruch.
                e.remove();
            }
        }
    }
}
