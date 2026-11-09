package com.blackn0va.baumeister.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Einstieg der Client-Mod: Befehl /kbau, Taste K (Pause/Weiter, in den Steuerungs-Einstellungen änderbar), Anzeige
 * oben links, Tick. „kbau“ statt „bm“: /bm gehört dem Server-Plugin; beide sollen nebeneinander gehen.
 *
 * Threads: Client-Thread. Fehler: Ohne Ressourcen (system-prompt, plan-schema) startet der Planer nicht – dann sagt
 * jeder Auftrag das im Chat, das Spiel läuft weiter.
 */
public final class BaumeisterClient implements ClientModInitializer {

    private static final Logger LOG = Logger.getLogger("Baumeister-Client");
    static final String ID = "baumeister-client";

    private static BaumeisterClient instance;

    private ClientSettings settings;
    private Path settingsFile;
    private ClientBuilder builder;
    private KeyMapping toggle;

    /** Für den Spieltest (gametest): derselbe Ablauf wie über /kbau. */
    public static BaumeisterClient get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        settingsFile = FabricLoader.getInstance().getConfigDir().resolve("baumeister-client.json");
        settings = ClientSettings.load(settingsFile);
        try {
            ClientPlanner planner = new ClientPlanner(settings, id -> BuiltInRegistries.BLOCK.containsKey(
                    Identifier.parse(id.contains(":") ? id : "minecraft:" + id)));
            builder = new ClientBuilder(settings, planner, new ResultSender(settings), BaumeisterClient::say);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.SEVERE, "start | planer_fehlt – Aufträge gehen nicht", e);
        }
        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.baumeister-client.toggle", 75,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"))));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggle.consumeClick()) {
                if (builder != null) {
                    builder.pauseOrResume();
                }
            }
            if (builder != null) {
                builder.tick(mc);
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "status"), (g, delta) -> hud(g));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(literal("kbau")
                .executes(c -> help())
                .then(literal("stop").executes(c -> run(() -> builder.stop())))
                .then(literal("pause").executes(c -> run(() -> builder.pauseOrResume())))
                .then(literal("weiter").executes(c -> run(() -> builder.resume())))
                .then(literal("status").executes(c -> run(() -> say("Zustand: " + builder.status()))))
                .then(literal("rückgängig").executes(c -> run(() -> builder.undo(Minecraft.getInstance()))))
                // Dasselbe ohne Umlaut (Tastatur ohne ü):
                .then(literal("rueckgaengig")  // umlaut-ok
                        .executes(c -> run(() -> builder.undo(Minecraft.getInstance()))))
                .then(literal("modus")
                        .then(literal("spieler").executes(c -> mode("spieler")))
                        .then(literal("befehl").executes(c -> mode("befehl"))))
                .then(literal("tempo").then(argument("n", IntegerArgumentType.integer(1, 1000))
                        .executes(c -> speed(IntegerArgumentType.getInteger(c, "n")))))
                .then(literal("host").then(argument("url", StringArgumentType.greedyString())
                        .executes(c -> host(StringArgumentType.getString(c, "url")))))
                .then(argument("auftrag", StringArgumentType.greedyString())
                        .executes(c -> run(() -> builder.start(Minecraft.getInstance(),
                                StringArgumentType.getString(c, "auftrag")))))));
    }

    /** Für den Spieltest: Auftrag wie /kbau, Modus und Tempo setzen, Zustand lesen. */
    public void order(String prompt) {
        builder.start(Minecraft.getInstance(), prompt);
    }

    public void setMode(String mode) {
        settings.mode = mode;
    }

    public void setSpeed(int perSecond) {
        settings.blocksPerSecond = perSecond;
        settings.commandsPerSecond = perSecond;
    }

    public String state() {
        ClientBuild j = builder.job();
        return builder.planning() ? "PLANNING" : j == null ? "NONE" : j.state.name();
    }

    public int[] counts() {
        ClientBuild j = builder.job();
        return j == null ? new int[] {0, 0, 0, 0} : new int[] {j.next, j.total(), j.placed, j.skipped};
    }

    public String pauseReason() {
        ClientBuild j = builder.job();
        return j == null ? "" : j.pauseReason;
    }

    public void resume() {
        builder.resume();
    }

    public void undo() {
        builder.undo(Minecraft.getInstance());
    }

    private int run(Runnable r) {
        if (builder == null) {
            say("Der Planer ist nicht gestartet (siehe Log) – Aufträge gehen nicht.");
            return 0;
        }
        r.run();
        return 1;
    }

    private int help() {
        say("/kbau <Auftrag> – z. B. /kbau bau einen Bunker · stop (auch während der Planung) · pause · weiter · "
                + "status · rückgängig · modus spieler|befehl · tempo <n/s> · host <url>. Jede eigene Eingabe hält an, "
                + "[K] macht weiter.");
        say("Modus: " + settings.mode + ", Tempo " + (settings.commandMode() ? settings.commandsPerSecond + " Befehle/s"
                : settings.blocksPerSecond + " Blöcke/s") + ", Host " + settings.llmUrl);
        return 1;
    }

    private int mode(String m) {
        settings.mode = m;
        settings.save(settingsFile);
        say("Bauweise: " + (settings.commandMode() ? "Befehle (/setblock, /fill – braucht OP bzw. Cheats)"
                : "wie ein Spieler (Rechtsklick, Kreativmodus)") + ".");
        return 1;
    }

    private int speed(int n) {
        if (settings.commandMode()) {
            settings.commandsPerSecond = n;
        } else {
            settings.blocksPerSecond = n;
        }
        settings.save(settingsFile);
        say("Tempo: " + n + (settings.commandMode() ? " Befehle/s." : " Blöcke/s."));
        return 1;
    }

    private int host(String url) {
        // Vorher nur das Präfix geprüft: „http://a b“ ging durch und warf erst in ClientPlanner.ask aus dem Befehl
        // heraus.
        if (!ClientSettings.validLlmUrl(url)) {
            say("Das ist keine gültige Adresse – sie muss mit http:// oder https:// und einem Host beginnen.");
            return 0;
        }
        settings.llmUrl = url.trim();
        settings.save(settingsFile);
        // Vorher hieß es „gilt nach einem Neustart“ – ClientPlanner.ask und ResultSender lesen llmUrl aber bei jeder
        // Anfrage neu. Bis zum Neustart bleiben nur maxBlocks/maxExtent (im ClientPlanner-Konstruktor eingefroren).
        say("Host: " + settings.llmUrl + " – gilt ab dem nächsten Auftrag.");
        return 1;
    }

    private void hud(GuiGraphicsExtractor g) {
        if (builder == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientBuild j = builder.job();
        String line;
        int color;
        if (builder.planning()) {
            line = "Baumeister: plant …";
            color = 0xFFB8C4D8;
        } else if (j != null && j.active()) {
            boolean paused = j.state == ClientBuild.State.PAUSED;
            line = "Baumeister: " + (paused ? "PAUSE (" + j.pauseReason + ") – [K] weiter, /kbau stop"
                    : (j.state == ClientBuild.State.VERIFYING ? "prüft " : "baut ") + j.next + "/" + j.total()
                            + " · " + (settings.commandMode() ? "Befehle" : "Spieler") + " · [K] Pause");
            color = paused ? 0xFFFBBF24 : 0xFF34D399;
        } else {
            return;
        }
        g.text(mc.font, line, 6, 6, color);
    }

    static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§6[Baumeister] §f" + text));
        }
        LOG.info(() -> "chat | " + text);
    }
}
