import com.blackn0va.baumeister.companion.CompanionChat;
import com.blackn0va.baumeister.plan.ItemNames;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.bukkit.Material;

/**
 * Messung: welcher Anteil echter Sätze an Vera läuft ohne Modell? Arg: Datei mit einem Satz je Zeile. Läuft gegen alten
 * und neuen Stand – fehlende Methoden (CompanionIntent, askHowTo …) gelten als „gibt es nicht“.
 */
public class Cover {
    static boolean call(String cls, String method, String s) {
        try {
            Class<?> c = Class.forName(cls);
            for (Method m : c.getMethods()) {
                if (m.getName().equals(method)) {
                    Object r = m.getParameterCount() == 1 ? m.invoke(null, s) : null;
                    if (m.getParameterCount() == 2) {
                        Class<?> k = Class.forName(cls + "$Known");
                        r = m.invoke(null, s, k.getConstructors()[0].newInstance(false));
                    }
                    if (r instanceof Optional<?> o) {
                        return o.isPresent();
                    }
                    if (r instanceof Boolean b) {
                        return b;
                    }
                    return r != null;
                }
            }
        } catch (ReflectiveOperationException e) {
            return false;
        }
        return false;
    }

    public static void main(String[] a) throws Exception {
        Set<String> items = new HashSet<>();
        for (Material m : Material.values()) {
            if (!m.isLegacy() && !m.name().equals("AIR")) {
                items.add(m.name().toLowerCase(Locale.ROOT));
            }
        }
        List<String> lines = Files.readAllLines(Path.of(a[0]));
        int handled = 0;
        int none = 0;
        StringBuilder rest = new StringBuilder();
        String chat = "com.blackn0va.baumeister.companion.CompanionChat";
        String intent = "com.blackn0va.baumeister.companion.CompanionIntent";
        for (String s : lines) {
            if (s.isBlank()) {
                continue;
            }
            boolean ok = CompanionChat.buildOrder(s).isPresent()
                    || CompanionChat.giveOrder(s).flatMap(g -> ItemNames.resolveStrict(g.item(), items)).isPresent()
                    || call(chat, "askRedstoneCheck", s) || call(chat, "askRedstoneLogic", s)
                    || call(chat, "askRepair", s) || call(chat, "adventure", s) || call(chat, "askHowTo", s)
                    || call(chat, "askHelp", s) || call(chat, "askDeath", s) || call(chat, "askChest", s)
                    || call(chat, "command", s) || call(chat, "askFix", s) || call(intent, "command", s) || call(intent, "chat", s);
            if (ok) {
                handled++;
            } else {
                none++;
                rest.append("  ").append(s).append('\n');
            }
        }
        System.out.printf("saetze=%d ohne_modell=%d modell_noetig=%d (%.0f %% ohne Modell)%n", handled + none, handled,
                none, 100.0 * handled / (handled + none));
        if (a.length > 1) {
            System.out.print(rest);
        }
    }
}
