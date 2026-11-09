package com.blackn0va.baumeister.plan;

/**
 * Stufen der Baurechte (Plan B1, Wunsch 2026-11-08). Jede Zelle eines Baus braucht genau eine Stufe – die höchste, die
 * ihr Block, ihr
 * Zustand, ihre Daten oder der Block, den sie überschreibt, verlangen.
 *
 * Geordnet: Eine höhere Stufe schließt die niedrigeren ein. Wer Befehlsblöcke setzen darf, könnte über sie ohnehin
 * jeden gefährlichen Block und jeden Inhalt erzeugen; wer Lava und Spawner darf, darf auch eine Truhe füllen. Wer die
 * Reihenfolge ändert, ändert, was die drei Rechte einschließen (BuildRights.allows).
 *
 * Die Admin-Stufen liegen unter baumeister.admin.* (Sicherheitsprüfung 2026-11-08, Befund 10). Vorher lagen sie
 * unter baumeister.bauen.* – wer in LuckPerms „baumeister.bauen.*“ an Bauende vergab, gab damit Befehlsblöcke
 * frei, und über die ist der Server zu haben. Wer sie zurück unter baumeister.bauen legt, öffnet das wieder.
 *
 * Threads: unveränderlich. Fehler: keine.
 */
public enum Tier {

    /** Jeder mit baumeister.bauen. */
    EVERYONE(null, "für alle"),
    /** Gegenstände in Behältern: Kreativmodus (online, in derselben Welt) oder baumeister.bauen.inhalte. */
    CONTENTS("baumeister.bauen.inhalte", "Inhalte"),
    /** Lava, TNT, Feuer, Spawner, Grundgestein, Portale … – Recht für gefährliche Blöcke (Name ASCII, Plan B1). */
    DANGEROUS("baumeister.admin.gefaehrlich", "gefährliche Blöcke"), // umlaut-ok: Rechtename
    /** Befehls-, Konstruktions- und Testblöcke, Klick-Aktionen, Entity-Daten, Beutetabellen – Admins. */
    COMMANDS("baumeister.admin.befehlsblock", "Befehle");

    private final String permission;
    private final String label;

    Tier(String permission, String label) {
        this.permission = permission;
        this.label = label;
    }

    /** Recht, das diese Stufe freigibt; null bei EVERYONE (dort reicht baumeister.bauen). */
    public String permission() {
        return permission;
    }

    /** Deutscher Name für Hinweise im Chat. */
    public String label() {
        return label;
    }

    public static Tier max(Tier a, Tier b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    public static Tier min(Tier a, Tier b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
