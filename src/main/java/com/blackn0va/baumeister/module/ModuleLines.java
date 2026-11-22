package com.blackn0va.baumeister.module;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;

/**
 * Chatzeilen für /bm module: Kopfzeile mit Zählung, je Modul eine Zeile in der Farbe seines Zustands, dazu der Grund.
 * Eine Zeile je Modul statt einer langen Kette: 20 Module in einer Zeile bricht der Chat unlesbar um.
 *
 * Threads: beliebig. Fehler: keine.
 */
public final class ModuleLines {

    private ModuleLines() {
    }

    public static List<String> lines(List<ModuleEntry> modules, boolean admin) {
        Map<ModuleState, Integer> count = new EnumMap<>(ModuleState.class);
        for (ModuleEntry m : modules) {
            count.merge(m.state(), 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        StringBuilder head = new StringBuilder(ChatColor.GOLD + "Module: ");
        boolean first = true;
        for (ModuleState s : ModuleState.values()) {
            int n = count.getOrDefault(s, 0);
            if (n == 0) {
                continue;
            }
            head.append(first ? "" : ChatColor.DARK_GRAY + " · ").append(s.color()).append(n).append(' ')
                    .append(s.wire());
            first = false;
        }
        out.add(head.toString());
        for (ModuleEntry m : modules) {
            out.add(line(m));
        }
        out.add(ChatColor.GRAY + (admin ? "Schalten: /bm module an|aus <modul> · als Kiste: /bm module menü"
                : "Als Kiste: /bm module menü · schalten dürfen nur Admins"));
        return out;
    }

    static String line(ModuleEntry m) {
        ModuleState s = m.state();
        return s.color() + "● " + m.def().name() + ChatColor.DARK_GRAY + " (" + m.def().id() + ") " + s.color()
                + s.wire() + (m.reason().isEmpty() ? "" : ChatColor.GRAY + " – " + m.reason());
    }
}
