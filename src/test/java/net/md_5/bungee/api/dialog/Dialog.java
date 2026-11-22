package net.md_5.bungee.api.dialog;

/**
 * Nur für Tests: Platzhalter für die Dialog-Schnittstelle aus bungeecord-dialog-1.21-R0.4.jar.
 *
 * Warum: org.bukkit.entity.Player nennt sie in einer Methodensignatur (showDialog). Ein Proxy für Player (TownLifeTest)
 * braucht jeden Typ seiner Signaturen; das Jar liegt beim Spigot-Server unter bundler/libraries, aber nicht unter
 * lib/ – dort stehen nur die JARs, die das Plugin zum Kompilieren braucht (lib/README.md). Gemessen 2026-11-22: ohne
 * diese Datei wirft Proxy.newProxyInstance NoClassDefFoundError net/md_5/bungee/api/dialog/Dialog. Liegt das echte Jar
 * eines Tages im Testpfad, gewinnt dieser Platzhalter (Testklassen zuerst); kein Test ruft Dialog-Methoden.
 */
public interface Dialog {
}
