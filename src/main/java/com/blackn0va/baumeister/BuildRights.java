package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Was ein Auftraggeber im Moment des Setzens darf (Plan B1, Wunsch 2026-11-08).
 *
 * Die Stufen sind geordnet (Tier): wer Befehle darf, darf auch Gefährliches und Inhalte; wer Gefährliches darf, auch
 * Inhalte. Der Kreativmodus zählt nur für Inhalte und nur, wenn der Spieler in diesem Moment online, im Kreativmodus
 * und in der Welt des Baus ist – sonst ließe sich Kreativ in einer anderen Welt oder vor dem Dranwerden der
 * Warteschlange „mitnehmen“. Nur die echte Konsole und RCON dürfen alles (bauenbei, maschine von der Konsole);
 * andere Absender ohne Spieler (Befehlsblock, execute as) nur, was jeder darf ({@link #isConsole}).
 *
 * Gelesen wird je Bautakt neu (BuildJob), nicht beim Auftrag – so greifen entzogene Rechte und verlassener
 * Kreativmodus auch in Warteschlange, Vorschau und vorgemerkten Plänen (ASVS V4.1.3, fail-closed).
 *
 * Threads: unveränderlich; {@link #now} nur im Hauptthread. Fehler: keine (offline = nur „für alle“).
 */
record BuildRights(boolean full, boolean contents, boolean dangerous, boolean commands, boolean creativeHere) {

    /** Konsole, RCON, Kommandoblock: kein Spieler, alle Stufen. */
    static final BuildRights CONSOLE = new BuildRights(true, true, true, true, false);
    /** Spieler nicht online: nur, was jeder darf. */
    static final BuildRights OFFLINE = new BuildRights(false, false, false, false, false);

    /**
     * Spieler mit seinen Rechten. creative zählt nur zusammen mit online und sameWorld (siehe Klassenkommentar).
     */
    static BuildRights player(boolean contentsPermission, boolean dangerousPermission, boolean commandsPermission,
            boolean creative, boolean sameWorld) {
        return new BuildRights(false, contentsPermission, dangerousPermission, commandsPermission,
                creative && sameWorld);
    }

    /**
     * Darf dieser Absender wie die Konsole alles? Nur die echte Konsole und RCON (Sicherheitsprüfung 2026-11-08,
     * Befund 9): Vorher galt jeder Absender ohne Spieler als Konsole – auch ein Befehlsblock, „execute as“ oder ein
     * fremdes Plugin. Die bekommen jetzt nur, was jeder darf.
     */
    static boolean isConsole(org.bukkit.command.CommandSender s) {
        return s instanceof org.bukkit.command.ConsoleCommandSender
                || s instanceof org.bukkit.command.RemoteConsoleCommandSender;
    }

    /** Rechte eines Absenders in diesem Moment: Spieler nach seinen Rechten, Konsole/RCON alles, sonst OFFLINE. */
    static BuildRights ofSender(org.bukkit.command.CommandSender s, World buildWorld) {
        if (s instanceof Player p) {
            // Über die UUID neu holen: Der gespeicherte Spieler kann nach einem Neueinloggen veraltet sein.
            return now(p.getUniqueId(), buildWorld);
        }
        return isConsole(s) ? CONSOLE : OFFLINE;
    }

    /** Rechte von who in diesem Moment; null = Konsole. Hauptthread. */
    static BuildRights now(UUID who, World buildWorld) {
        if (who == null) {
            return CONSOLE;
        }
        Player p = Bukkit.getPlayer(who);
        if (p == null || !p.isOnline()) {
            return OFFLINE;
        }
        return of(p, buildWorld);
    }

    /** Rechte eines Spielers, der gerade online ist (z. B. für den Prompt des LLM). */
    static BuildRights of(Player p, World buildWorld) {
        return player(p.hasPermission(Tier.CONTENTS.permission()), p.hasPermission(Tier.DANGEROUS.permission()),
                p.hasPermission(Tier.COMMANDS.permission()), p.getGameMode() == GameMode.CREATIVE,
                buildWorld != null && buildWorld.equals(p.getWorld()));
    }

    boolean allows(Tier tier) {
        return switch (tier) {
            case EVERYONE -> true;
            case CONTENTS -> full || contents || dangerous || commands || creativeHere;
            case DANGEROUS -> full || dangerous || commands;
            case COMMANDS -> full || commands;
        };
    }

    /**
     * Höchstens bis max (Plan B5): Ein Plan, in dessen Prompt fremder Text stand (Recherche aus Wikipedia), baut
     * auch für einen Admin nur bis „Inhalte“ – eingeschleuster Text brächte sonst Lava, TNT oder Befehle in den Bau.
     * Unterhalb von max bleibt alles, wie es ist; COMMANDS ändert nichts.
     */
    BuildRights capped(Tier max) {
        if (max == Tier.COMMANDS) {
            return this;
        }
        boolean c = allows(Tier.CONTENTS) && max.ordinal() >= Tier.CONTENTS.ordinal();
        boolean d = allows(Tier.DANGEROUS) && max.ordinal() >= Tier.DANGEROUS.ordinal();
        return new BuildRights(false, c, d, false, false);
    }

    /** Alle erlaubten Stufen, aufsteigend – für den Prompt („du darfst: …“) und Hinweise. */
    List<Tier> allowed() {
        List<Tier> out = new ArrayList<>();
        for (Tier t : Tier.values()) {
            if (allows(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Das Recht, das für tier fehlt, als Satzteil: „das Recht baumeister.admin.befehlsblock“. */
    static String missing(Tier tier) {
        if (tier == Tier.CONTENTS) {
            return "den Kreativmodus oder das Recht " + tier.permission();
        }
        return "das Recht " + tier.permission();
    }
}
