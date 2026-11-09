package com.blackn0va.baumeister;

import java.util.regex.Matcher;

/** Testzugang zum paketprivaten Chat-Muster des Listeners (Logik-Kurzbefehl). */
public final class BaumeisterListenerAccess {

    private BaumeisterListenerAccess() {
    }

    /** Formel hinter „logik …“/„schaltung …“, sonst null. */
    public static String logicChat(String prompt) {
        Matcher m = BaumeisterListener.LOGIC_CHAT.matcher(prompt);
        return m.matches() ? m.group(1) : null;
    }
}
