package com.blackn0va.baumeister.client;

import java.nio.file.Path;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Bildschirm „Module“ (AK-39, 2026-11-22): oben die Module der Mod als Schalter (ClientModules, gespeichert in
 * config/baumeister-client.json), darunter die Server-Module aus baumeister:module nur zur Anzeige mit Zustand und
 * Grund. Bis dahin hatte die Mod keinen eigenen Bildschirm – Aufbau daher wie die Optionsbildschirme von Minecraft
 * (Titel mittig, Knöpfe 150 breit, „Fertig“ unten), Farben wie die Statuszeile (BaumeisterClient.hud).
 *
 * Die Server-Liste liest ServerModules.current() bei jedem Bild: Kommt eine neue Meldung, während der Bildschirm offen
 * ist, steht sie sofort da. Mehr Module als Platz → Mausrad blättert.
 *
 * Threads: Client-Thread. Fehler: keine eigenen; Speichern meldet ClientSettings.save im Log.
 */
final class ModulesScreen extends Screen {

    private static final int ROW = 24;
    private static final int LINE = 12;
    private static final int WIDTH = 320;
    private static final int OK = 0xFF34D399;
    private static final int WAIT = 0xFFFBBF24;
    private static final int ERROR = 0xFFF87171;
    private static final int MUTED = 0xFF9CA3AF;
    private static final int TEXT = 0xFFFFFFFF;

    private final Screen parent;
    private final ClientSettings settings;
    private final Path settingsFile;
    private int serverTop;
    private int serverBottom;
    private int scroll;

    ModulesScreen(Screen parent, ClientSettings settings, Path settingsFile) {
        super(Component.translatable("baumeister-client.screen.title"));
        this.parent = parent;
        this.settings = settings;
        this.settingsFile = settingsFile;
    }

    @Override
    protected void init() {
        int left = width / 2 - WIDTH / 2;
        int y = 44;
        for (ClientModules.Module m : ClientModules.ALL) {
            Button b = Button.builder(label(m), btn -> {
                ClientModules.toggle(settings, m, settingsFile);
                btn.setMessage(label(m));
            }).bounds(left, y, 150, 20).build();
            b.setTooltip(Tooltip.create(Component.translatable(m.descriptionKey())));
            addRenderableWidget(b);
            y += ROW;
        }
        serverTop = y + 20;
        serverBottom = height - 34;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> onClose())
                .bounds(width / 2 - 75, height - 28, 150, 20).build());
    }

    private Component label(ClientModules.Module m) {
        return Component.translatable(m.nameKey()).append(": ")
                .append(m.on().test(settings) ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int left = width / 2 - WIDTH / 2;
        g.centeredText(font, title, width / 2, 12, TEXT);
        g.text(font, Component.translatable("baumeister-client.screen.own"), left, 32, MUTED);
        int y = 44;
        for (ClientModules.Module m : ClientModules.ALL) {
            // Beschreibung neben dem Schalter, gekürzt; ganz steht sie im Tooltip des Knopfs.
            String d = Component.translatable(m.descriptionKey()).getString();
            g.text(font, font.plainSubstrByWidth(d, WIDTH - 158), left + 158, y + 6, MUTED);
            y += ROW;
        }
        g.text(font, Component.translatable("baumeister-client.screen.server"), left, serverTop - 12, MUTED);
        List<ServerModules.Module> server = ServerModules.current();
        if (server == null || server.isEmpty()) {
            g.text(font, Component.translatable("baumeister-client.screen.server.none"), left, serverTop, TEXT);
            return;
        }
        int rows = Math.max(1, (serverBottom - serverTop) / LINE);
        scroll = Math.max(0, Math.min(scroll, server.size() - rows));
        int line = serverTop;
        for (int i = scroll; i < server.size() && i < scroll + rows; i++) {
            ServerModules.Module m = server.get(i);
            Component state = Component.translatable("baumeister-client.state." + m.state());
            String head = "● ";
            g.text(font, head, left, line, color(m.state()));
            int x = left + font.width(head);
            String name = m.name() + "  ";
            g.text(font, name, x, line, TEXT);
            x += font.width(name);
            g.text(font, state, x, line, color(m.state()));
            x += font.width(state);
            String rest = m.reason().isEmpty() ? "" : " – " + m.reason();
            if (!m.needs().isEmpty() && !"aktiv".equals(m.state())) {
                rest += Component.translatable("baumeister-client.screen.needs", String.join(", ", m.needs()))
                        .getString();
            }
            g.text(font, font.plainSubstrByWidth(rest, Math.max(0, left + WIDTH - x)), x, line, MUTED);
            if (!m.description().isEmpty() && mouseY >= line && mouseY < line + LINE && mouseX >= left
                    && mouseX < left + WIDTH) {
                g.setTooltipForNextFrame(font, Component.literal(m.description()), mouseX, mouseY);
            }
            line += LINE;
        }
        if (server.size() > rows) {
            g.text(font, (scroll + 1) + "–" + Math.min(server.size(), scroll + rows) + " / " + server.size(),
                    left + WIDTH - 40, serverTop - 12, MUTED);
        }
    }

    private static int color(String state) {
        return switch (state) {
            case "aktiv" -> OK;
            case "wartet" -> WAIT;
            case "fehler" -> ERROR;
            default -> MUTED;
        };
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0) {
            scroll += vertical > 0 ? -1 : 1;  // Begrenzung beim nächsten Bild (Liste kann sich inzwischen ändern)
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
