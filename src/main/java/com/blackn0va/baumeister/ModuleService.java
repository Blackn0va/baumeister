package com.blackn0va.baumeister;

import com.blackn0va.baumeister.module.ModuleDef;
import com.blackn0va.baumeister.module.ModuleDirectory;
import com.blackn0va.baumeister.module.ModuleEntry;
import com.blackn0va.baumeister.module.ModuleJson;
import com.blackn0va.baumeister.module.ModuleLines;
import com.blackn0va.baumeister.module.ModuleState;
import com.blackn0va.baumeister.module.ModuleSwitch;
import com.blackn0va.baumeister.module.SendGate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Modulverzeichnis im laufenden Plugin (Vertrag v012, AK-37): sammelt die Fakten für ModuleDirectory, zeigt und
 * schaltet Module (/bm module, Kistenmenü ModuleMenu) und meldet modul_json an Host und Mod.
 *
 * Zwei Wege, beide im Vertrag festgelegt, weil Host-App (T-031) und Mod (T-032) parallel dagegen bauen:
 * - Host: POST &lt;Adresse wie HostReporter&gt;/baumeister/v1/module, asynchron, bei Änderung und höchstens alle 60 s
 *   (SendGate). Reine Anzeige, deshalb fail-open: Fehler stehen nur im Log (OutageLog, eine Zeile je Ausfall statt
 *   je Versuch), der Rest des Plugins merkt nichts.
 * - Mod: Plugin-Kanal baumeister:module, Nutzlast modul_json als rohe UTF-8-Bytes ohne Längenpräfix, an jeden Spieler,
 *   dessen Client den Kanal angemeldet hat – sobald er ihn anmeldet (PlayerRegisterChannelEvent), beim Beitritt
 *   (falls die Anmeldung vor dem Join-Ereignis kam) und bei jeder Änderung. Spieler ohne Mod bekommen nichts.
 *
 * Takt: alle REFRESH_TICKS auf dem Hauptthread neu rechnen (ein paar Konfig- und Plugin-Abfragen, kein Netz), die
 * KI-Prüfung der Bau-KI höchstens alle HEALTH_NANOS asynchron über LlmClient.health().
 *
 * Threads: Hauptthread; Abschluss von POST und Health im HttpClient-Pool (nur SendGate und volatile llmUp).
 * Fehler: wirft nicht.
 */
final class ModuleService implements Listener {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final String CHANNEL = "baumeister:module";
    static final String PATH = "/baumeister/v1/module";
    static final long REFRESH_TICKS = 100L;
    static final long HEALTH_NANOS = 60_000_000_000L;

    /** Was der Dienst von außen braucht; im Spiel BukkitEnv, im Test ein Fake ohne Server. */
    interface Env {
        ModuleDirectory.Facts facts();

        Collection<? extends Player> players();

        /** Nutzlast an einen Spieler (Player.sendPluginMessage). */
        void send(Player p, byte[] payload);

        /** Bau-KI prüfen; Ergebnis über den Rückruf, beliebiger Thread. */
        void checkLlm(java.util.function.Consumer<Boolean> done);

        /** Später auf dem Hauptthread. */
        void later(Runnable r, long ticks);

        ModuleSwitch.Target target(CommandSender by);

        void openMenu(Player p);
    }

    private final Env env;
    /** null = keine brauchbare Host-Adresse, dann nur der Kanal. */
    private final URI uri;
    private final HostReporter.Transport transport;
    private final LongSupplier clock;
    private final SendGate gate = new SendGate();
    private final OutageLog outage;
    private String lastJson;
    private List<ModuleEntry> last = List.of();
    private long lastHealth = Long.MIN_VALUE / 2;
    private BukkitTask task;

    ModuleService(Env env, URI uri, HostReporter.Transport transport, LongSupplier clock) {
        this.env = env;
        this.uri = uri;
        this.transport = transport;
        this.clock = clock;
        this.outage = new OutageLog(clock);
    }

    /** Im Spiel: Adresse aus gui.url/llm.url wie ResultReporter, gleiche HTTP-Einstellungen. */
    static ModuleService from(BaumeisterPlugin plugin, ModuleMenu menu) {
        URI base = HostReporter.target(plugin.getConfig().getString("gui.url", ""), plugin.settings().llmUrl());
        if (base == null) {
            LOG.warning("module | keine_host_adresse | gui.url/llm.url – Module nur im Spiel und für die Mod");
        }
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HostReporter.Transport t = (u, json) -> http.sendAsync(HttpRequest.newBuilder(u)
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
        return new ModuleService(new BukkitEnv(plugin, menu), base == null ? null : base.resolve(PATH), t,
                System::nanoTime);
    }

    void start(Plugin plugin) {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 40L, REFRESH_TICKS);
        LOG.info(() -> "module | bereit | module=" + ModuleDirectory.ALL.size() + " kanal=" + CHANNEL + " host="
                + (uri == null ? "aus" : uri));
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Neu rechnen, bei Änderung an die Mod, und an den Host, wenn SendGate es erlaubt. Hauptthread. */
    void refresh() {
        long now = clock.getAsLong();
        if (now - lastHealth >= HEALTH_NANOS) {
            lastHealth = now;
            env.checkLlm(ok -> llmUp = ok);
        }
        List<ModuleEntry> fresh = ModuleDirectory.evaluate(env.facts());
        String json = ModuleJson.encode(fresh);
        if (!json.equals(lastJson)) {
            String before = lastJson;
            List<ModuleEntry> old = last;
            lastJson = json;
            last = fresh;
            if (before != null) {
                LOG.info(() -> "module | geändert | " + changes(old, fresh));
            }
            broadcast(json);
        }
        postIfDue(json);
    }

    /** Ergebnis der letzten KI-Prüfung (null = noch keine); geschrieben aus dem HttpClient-Pool. */
    private volatile Boolean llmUp;

    Boolean llmUp() {
        return llmUp;
    }

    List<ModuleEntry> entries() {
        return ModuleDirectory.evaluate(env.facts());
    }

    String lastJson() {
        return lastJson;
    }

    private void broadcast(String json) {
        byte[] payload = ModuleJson.bytes(json);
        for (Player p : env.players()) {
            sendTo(p, payload);
        }
    }

    private void sendTo(Player p, byte[] payload) {
        if (p.getListeningPluginChannels().contains(CHANNEL)) {
            env.send(p, payload);
        }
    }

    /** Der Client meldet den Kanal an (die Mod tut das gleich nach dem Beitritt): sofort den Stand schicken. */
    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent e) {
        if (CHANNEL.equals(e.getChannel())) {
            sendTo(e.getPlayer(), ModuleJson.bytes(current()));
        }
    }

    /**
     * Beim Beitritt zwei Sekunden später: Hat der Client den Kanal schon VOR dem Join-Ereignis angemeldet, kam
     * onRegister zu früh für einen Spieler, der noch gar nicht in der Welt war. Doppelt schadet nicht (gleicher Stand).
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        env.later(() -> {
            if (p.isOnline()) {
                sendTo(p, ModuleJson.bytes(current()));
            }
        }, 40L);
    }

    private String current() {
        if (lastJson == null) {
            lastJson = ModuleJson.encode(entries());
        }
        return lastJson;
    }

    private void postIfDue(String json) {
        if (uri == null) {
            return;
        }
        String sending = gate.next(json, clock.getAsLong());
        if (sending == null) {
            return;
        }
        java.util.concurrent.CompletableFuture<Integer> f;
        try {
            f = transport.post(uri, sending);
        } catch (RuntimeException e) {
            // Bewusst fail-open (Klassenkopf): reine Anzeige, ein kaputter Sender darf den Takt nicht beenden.
            f = java.util.concurrent.CompletableFuture.failedFuture(e);
        }
        f.whenComplete((status, err) -> {
            boolean ok = err == null && status != null && status >= 200 && status < 300;
            gate.done(sending, ok);
            if (ok) {
                long minutes = outage.success();
                if (minutes != OutageLog.UP) {
                    LOG.info(() -> "module | host_wieder_da | ziel=" + uri + " ausfall_min=" + minutes);
                }
                return;
            }
            long minutes = outage.failure();
            if (minutes != OutageLog.UP) {
                // sendAsync verpackt den echten Fehler in CompletionException – live 2026-11-22 stand nur das im Log.
                Throwable cause = err instanceof java.util.concurrent.CompletionException && err.getCause() != null
                        ? err.getCause() : err;
                String why = cause != null ? cause.getClass().getSimpleName() : "HTTP " + status;
                LOG.info(() -> "module | host_nicht_erreichbar | ziel=" + uri + " grund=" + why
                        + (minutes > 0 ? " seit_min=" + minutes : "") + " – reine Anzeige");
            }
        });
    }

    /** /bm module [liste|menü|an &lt;id&gt;|aus &lt;id&gt;]. args[0] ist „module“. */
    void command(CommandSender s, String[] args) {
        String verb = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "liste";
        switch (verb) {
            case "an", "aus" -> {
                String id = args.length > 2 ? args[2] : null;
                ModuleSwitch.Result r = ModuleSwitch.run(s.hasPermission(ModuleSwitch.ADMIN), verb, id,
                        env.target(s));
                for (String line : r.lines()) {
                    Texts.info(s, line);
                }
                if (r.changed()) {
                    LOG.info(() -> "module | geschaltet | modul="
                            + ModuleDirectory.find(id).map(ModuleDef::id).orElse("?") + " an=" + verb.equals("an")
                            + " sofort=" + (r.outcome() == ModuleSwitch.Outcome.SWITCHED_NOW) + " von="
                            + Texts.clean(s.getName(), 16));
                    refresh();
                }
            }
            case "menü", "menu", "kiste" -> { // umlaut-ok: Eingabe ohne Umlaut-Tastatur
                if (s instanceof Player p) {
                    env.openMenu(p);
                } else {
                    list(s);
                }
            }
            default -> list(s);
        }
    }

    private void list(CommandSender s) {
        for (String line : ModuleLines.lines(entries(), s.hasPermission(ModuleSwitch.ADMIN))) {
            Texts.info(s, line);
        }
    }

    /** Für das Log: welche Module ihren Zustand gewechselt haben, sonst die Zählung. */
    static String changes(List<ModuleEntry> before, List<ModuleEntry> after) {
        Map<String, ModuleState> old = new HashMap<>();
        for (ModuleEntry m : before) {
            old.put(m.def().id(), m.state());
        }
        StringBuilder b = new StringBuilder();
        Map<ModuleState, Integer> count = new EnumMap<>(ModuleState.class);
        for (ModuleEntry m : after) {
            count.merge(m.state(), 1, Integer::sum);
            ModuleState was = old.get(m.def().id());
            if (was != null && was != m.state()) {
                b.append(b.length() == 0 ? "" : " ").append(m.def().id()).append('=').append(was.wire())
                        .append("→").append(m.state().wire());
            }
        }
        if (b.length() == 0) {
            b.append("gründe");
        }
        for (ModuleState st : ModuleState.values()) {
            b.append(' ').append(st.wire()).append('=').append(count.getOrDefault(st, 0));
        }
        return b.toString();
    }

    /**
     * Ein Problem der Server-Stimme ohne Dateipfad: LocalVoice.Settings.problem() nennt den Pfad zu Piper bzw. zum
     * Modell, und der enthält oft den Benutzernamen des Rechners (C:\Users\…). modul_json geht an Host und jede Mod –
     * dort hat ein Pfad nichts verloren (Vertrag v012: keine Adressen; ASVS V8.3.4).
     */
    static String voiceProblem(String problem) {
        if (problem == null || problem.isBlank()) {
            return null;
        }
        return problem.startsWith("sprache.piper") ? "sprache.piper zeigt auf kein ausführbares Piper"
                : "Stimmenmodell oder seine .json fehlt (sprache.stimme)";
    }

    /** Schlüssel aller schaltbaren Module, mit ihrem Wert in c. */
    static Map<String, Boolean> keys(ConfigurationSection c) {
        Map<String, Boolean> out = new HashMap<>();
        for (ModuleDef d : ModuleDirectory.ALL) {
            if (d.key() != null) {
                out.put(d.key(), c.getBoolean(d.key()));
            }
        }
        return out;
    }

    /** Das echte Plugin. Liest bei jedem facts() frisch – die Dienste ändern sich mit /bm neuladen. */
    static final class BukkitEnv implements Env {

        private final BaumeisterPlugin plugin;
        private final ModuleMenu menu;
        /** Schalterwerte beim Start: Unterschied heißt „wirkt nach Neustart“ (ModuleDirectory). */
        private final Map<String, Boolean> atStart;

        BukkitEnv(BaumeisterPlugin plugin, ModuleMenu menu) {
            this.plugin = plugin;
            this.menu = menu;
            this.atStart = keys(plugin.getConfig());
        }

        @Override
        public ModuleDirectory.Facts facts() {
            TownLife town = plugin.town();
            Set<String> plugins = new HashSet<>();
            for (String name : List.of("WorldEdit", "voicechat", "CoreProtect")) {
                Plugin p = Bukkit.getPluginManager().getPlugin(name);
                if (p != null && p.isEnabled()) {
                    plugins.add(name.toLowerCase(Locale.ROOT));
                }
            }
            ConfigurationSection apps = plugin.getConfig().getConfigurationSection(VoiceAppBinding.SECTION);
            ModuleService self = plugin.modules();
            return new ModuleDirectory.Facts(keys(plugin.getConfig()), atStart, self == null ? null : self.llmUp(),
                    town != null && town.kiAnswered(), town != null && town.kiUp(), plugin.earthWorld() != null,
                    plugins, plugin.localVoice() != null || plugin.veraVoice() != null,
                    voiceProblem(LocalVoice.Settings.from(plugin.getConfig()).problem().orElse(null)),
                    plugin.modDownloads() != null, plugin.hostReporter() != null,
                    plugin.city() != null && plugin.city().running(),
                    plugin.weltbau() != null && plugin.weltbau().auto(), apps == null ? 0 : apps.getKeys(false).size(),
                    town != null && town.withoutKi());
        }

        @Override
        public Collection<? extends Player> players() {
            return Bukkit.getOnlinePlayers();
        }

        @Override
        public void send(Player p, byte[] payload) {
            p.sendPluginMessage(plugin, CHANNEL, payload);
        }

        @Override
        public void checkLlm(java.util.function.Consumer<Boolean> done) {
            BuildService s = plugin.service();
            if (s == null) {
                return;
            }
            s.llm().health().whenComplete((ok, err) -> done.accept(err == null && Boolean.TRUE.equals(ok)));
        }

        @Override
        public void later(Runnable r, long ticks) {
            Bukkit.getScheduler().runTaskLater(plugin, r, ticks);
        }

        @Override
        public void openMenu(Player p) {
            menu.open(p);
        }

        @Override
        public ModuleSwitch.Target target(CommandSender by) {
            return new ModuleSwitch.Target() {
                @Override
                public boolean current(String key) {
                    return plugin.getConfig().getBoolean(key);
                }

                @Override
                public void write(String key, boolean on) {
                    plugin.getConfig().set(key, on);
                    plugin.saveConfig();
                }

                @Override
                public boolean applyLive(ModuleDef def, boolean on) {
                    switch (def.id()) {
                        case "stadtleben" -> {
                            if (plugin.town() == null) {
                                return false;
                            }
                            // Schreibt stadtleben.an selbst (TownLife.Parts.saveEnabled) – derselbe Weg wie
                            // /bm stadtleben an|aus, also auch dieselben Bewohner-Folgen (aus = alle sofort weg).
                            plugin.town().setEnabled(on, by);
                            return true;
                        }
                        case "sprache" -> {
                            // Wie /bm sprache an|aus (VoiceControl): schreiben, dann die Stimmen neu öffnen.
                            write(def.key(), on);
                            plugin.reopenVoice();
                            return true;
                        }
                        default -> {
                            return false;
                        }
                    }
                }
            };
        }
    }
}
