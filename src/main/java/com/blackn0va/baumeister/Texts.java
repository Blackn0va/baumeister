package com.blackn0va.baumeister;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Chat-Ausgabe des NPC und Bereinigung von Text, der von Spielern oder vom LLM kommt. */
final class Texts {

    /** Farbcodes (§x) und Steuerzeichen – sonst könnte das LLM oder ein Spieler Chat-Formatierung einschleusen. */
    private static final Pattern UNSAFE = Pattern.compile("§.|[\\p{Cntrl}&&[^\\n]]");
    private static final double HEAR_RADIUS = 48;

    private Texts() {
    }

    static String clean(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String s = UNSAFE.matcher(text).replaceAll("").replace('\n', ' ').trim();
        return s.length() > maxLength ? s.substring(0, maxLength - 1) + "…" : s;
    }

    /** Spricht wie ein Spieler: hörbar im Umkreis des NPC plus beim Auftraggeber (auch Konsole). */
    /** Wer spricht, wo, was – für die Sprachausgabe (LocalVoice, 2026-11-08). null = nur Chat. */
    @FunctionalInterface
    interface Spoken {
        void said(String npcName, Location where, String text);
    }

    static volatile Spoken spoken;

    static void say(String npcName, Location where, CommandSender extra, String text) {
        Spoken s = spoken;
        if (s != null) {
            s.said(npcName, where, text);
        }
        chat(npcName, where, extra, text);
    }

    /**
     * Wie say, aber nur in den Chat, nicht vorlesen (2026-11-15). Für Zeilen, die man lesen, aber nicht hören will:
     * Technik („Schnellgang, 440 Blöcke/s“, „2 Helfer“), Quellen, Hinweise auf Varianten. Vorher sprach der Baumeister
     * bei jedem Katalogbau „Quelle: Technik Wiki (de.minecraft.wiki), Redstone-Welt 1.21a, CC BY-NC-SA 3.0“ – der
     * Nutzer: „spricht Dinge aus, die wirklich nervig sind und unnötig“.
     */
    static void chat(String npcName, Location where, CommandSender extra, String text) {
        String line = ChatColor.GOLD + "[" + npcName + "] " + ChatColor.WHITE + text;
        Set<CommandSender> receivers = new HashSet<>();
        if (where != null && where.getWorld() != null) {
            for (Player p : where.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(where) <= HEAR_RADIUS * HEAR_RADIUS) {
                    receivers.add(p);
                }
            }
        }
        if (extra != null) {
            receivers.add(extra);
        }
        receivers.forEach(r -> r.sendMessage(line));
    }

    static void info(CommandSender to, String text) {
        to.sendMessage(ChatColor.GOLD + "[Baumeister] " + ChatColor.GRAY + text);
    }

    /**
     * Wie info, aber link ist anklickbar (führt command aus) und zeigt hover beim Überfahren. Die Konsole kann weder
     * klicken noch überfahren – sie bekommt dieselbe Zeile als Text, sonst sähe sie den Ortsnamen gar nicht.
     */
    static void infoLink(CommandSender to, String link, String command, String hover, String tail) {
        if (to instanceof Player p) {
            p.spigot().sendMessage(linkLine(link, command, hover, tail));
        } else {
            info(to, link + tail);
        }
    }

    /** Ein Knopf im Chat: Beschriftung, Befehl beim Klick, Text beim Überfahren. */
    record Choice(String label, String command, String hover) {
    }

    /**
     * Knöpfe in einer Zeile ([Ja] [Nein] …), danach tail. Die Konsole kann nicht klicken – sie bekommt die Befehle als
     * Text zum Abtippen.
     */
    static void choices(CommandSender to, java.util.List<Choice> choices, String tail) {
        if (to instanceof Player p) {
            p.spigot().sendMessage(choiceLine(choices, tail));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Choice c : choices) {
            sb.append(sb.isEmpty() ? "" : " · ").append(c.label()).append(": ").append(c.command());
        }
        info(to, sb + tail);
    }

    /** Ohne Server baubar (nur bungeecord-chat) – deshalb testbar. */
    static BaseComponent[] choiceLine(java.util.List<Choice> choices, String tail) {
        java.util.List<BaseComponent> parts = new java.util.ArrayList<>();
        TextComponent prefix = new TextComponent("[Baumeister] ");
        prefix.setColor(net.md_5.bungee.api.ChatColor.GOLD);
        parts.add(prefix);
        for (Choice c : choices) {
            TextComponent b = new TextComponent("[" + c.label() + "]");
            b.setColor(net.md_5.bungee.api.ChatColor.AQUA);
            b.setBold(true);
            b.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, c.command()));
            b.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(c.hover())));
            parts.add(b);
            TextComponent gap = new TextComponent(" ");
            parts.add(gap);
        }
        TextComponent rest = new TextComponent(tail);
        rest.setColor(net.md_5.bungee.api.ChatColor.GRAY);
        parts.add(rest);
        return parts.toArray(new BaseComponent[0]);
    }

    /** Ohne Server baubar (nur bungeecord-chat) – deshalb testbar. Die Teile erben nichts voneinander. */
    static BaseComponent[] linkLine(String link, String command, String hover, String tail) {
        TextComponent prefix = new TextComponent("[Baumeister] ");
        prefix.setColor(net.md_5.bungee.api.ChatColor.GOLD);
        TextComponent name = new TextComponent(link);
        name.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        name.setUnderlined(true);
        name.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        name.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
        TextComponent rest = new TextComponent(tail);
        rest.setColor(net.md_5.bungee.api.ChatColor.GRAY);
        return new BaseComponent[] {prefix, name, rest};
    }
}
