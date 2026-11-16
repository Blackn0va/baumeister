package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.TunnelNature;
import com.blackn0va.baumeister.plan.TunnelOrder;
import com.blackn0va.baumeister.plan.TunnelScan;
import com.blackn0va.baumeister.plan.TunnelShape;
import com.blackn0va.baumeister.plan.TunnelStyle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Tunnel, Treppe nach oben und Brücke ohne LLM (Wunsch 2026-11-16): „ich will mit dem Baumeister einen Tunnel graben,
 * und er macht mit, auch im Schnellverfahren“, „Treppen bis zur Oberfläche“, „eine Brücke, die kein Ende hat und auch
 * über Meere geht“, „er muss Tunnel und Brücken erkennen und dort weiterbauen“, „wenn ich Admin bin, baut er einfach,
 * ansonsten braucht er eine Kiste mit Materialien“, Abgebautes „alles in die Kiste“.
 *
 * Ablauf: Auftrag lesen (TunnelOrder) → beim Weiterbauen die Stelle erkennen (TunnelScan) → Kiste und Palette
 * (TunnelStyle) → Chunks entlang des Wegs vorladen → planen (TunnelShape über TunnelSite) → bauen (BuildJob mit Dig).
 * Ohne Länge baut er mit: nie weiter als {@link #LEAD} Abschnitte vor dem Spieler und Stück für Stück
 * ({@link #FOLLOW_SEGMENT}) ohne Ende weiter, solange der Spieler mitgeht. „schnell“ = Schnellgang allein.
 *
 * Frei bauen nur Admins und Spieler im Kreativmodus (wie die Stufe „Inhalte“, BuildRights). Alle anderen brauchen eine
 * Kiste; was sie liefert, bestimmt das Aussehen, und jeder Block wird beim Setzen bezahlt. Rückgängig gibt es dann
 * nicht: Das Abgebaute liegt schon in der Kiste – ein Rückgängig stellte den Fels wieder her, und Erz ließe sich so
 * beliebig oft abbauen.
 *
 * Gerade (Wunsch 2026-11-16, „ein Tunnel muss straight gerade gehen – er wird irgendwann durch ca. die komplette Karte
 * gehen“): Weiterbauen nimmt Richtung, Mitte, Höhe und Maße vom erkannten Gang ({@link #recognise}), nie vom Blick
 * des Spielers. Vorher galt nur die Blickrichtung: Wer bei „bau weiter“ zur Seite schaute, bekam einen neuen Tunnel
 * quer zum alten, und „grab einen Tunnel“ mitten im Gang setzte neu an – um die Füße des Spielers versetzt, mit
 * Eingangsbogen und neuem Lichttakt. Lange Aufträge (bis TunnelOrder.MAX_LENGTH) laufen Stück für Stück im selben
 * Rahmen, jedes Stück höchstens bau.max-ausdehnung lang – also auf derselben Linie und Höhe.
 *
 * Threads: Hauptthread. Fehler: keine (Absagen gehen als Satz an den Spieler).
 */
final class TunnelService {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Mitbauen ohne Länge: so viele Abschnitte je Stück, dann das nächste. */
    static final int FOLLOW_SEGMENT = 64;
    /** „schnell“ ohne Länge. */
    static final int FAST_DEFAULT = 48;
    /** Mitbauen: höchstens so viele Abschnitte vor dem Spieler. */
    static final int LEAD = 3;
    /** Seitlich so weit neben dem Weg zählt der Spieler noch als „dabei“. */
    static final int SIDE = 4;
    /** Ein neuer Tunnel darf vor dem ersten Fels so viele Abschnitte überspringen (Keller, Wiese, Höhle). */
    static final int APPROACH = 32;
    private static final ItemStack TOOL = new ItemStack(Material.DIAMOND_PICKAXE);
    /** So viele Abschnitte den Gang entlang zählen Innenraum und Steine eines Tunnels, aus dem eine Treppe steigt. */
    static final int ROOM_REACH = 8;
    /** Besitzer der Konsole (bauenbei), wie BuildService.CONSOLE. */
    private static final UUID CONSOLE = new UUID(0, 0);
    /** So viele Gänge kreuzt ein Stück mit „trotzdem“ höchstens (jede Kreuzung plant das Stück neu). */
    static final int MAX_CROSSINGS = 4;

    private final BaumeisterPlugin plugin;
    private final BuildService service;
    private final Settings settings;
    private final BuilderNpc npc;
    /**
     * Laufender Grabauftrag je Besitzer (Vorfall 2026-11-16 auf dev01): Ein „/bm stop“ in der Pause zwischen zwei
     * Stücken fand keinen laufenden Bau und verpuffte, das schon vorbereitete nächste Stück startete trotzdem – zweimal
     * hintereinander. Jetzt trägt jedes Stück die Nummer seines Auftrags; wer stoppt oder neu beauftragt, nimmt sie
     * hier heraus, und ein Stück, dessen Nummer nicht mehr gilt, startet nicht.
     */
    private final Map<UUID, Long> chains = new HashMap<>();
    /** Aufträge, deren nächstes Stück gerade vorgeladen und geplant wird – dann läuft kein Bau. */
    private final Set<Long> preparing = new HashSet<>();
    private long chainCounter;

    TunnelService(BaumeisterPlugin plugin, BuildService service, Settings settings, BuilderNpc npc) {
        this.plugin = plugin;
        this.service = service;
        this.settings = settings;
        this.npc = npc;
    }

    /**
     * Alles, was ein Stück braucht – auch das nächste beim Mitbauen ohne Ende oder bei einer langen Länge.
     *
     * @param resumed     der Auftrag setzt etwas Vorhandenes fort (bleibt für alle Stücke gleich)
     * @param rest        so viele Abschnitte kommen nach diesem Stück noch (feste Länge über bau.max-ausdehnung)
     * @param builtBefore so viele Abschnitte haben die Stücke davor gebaut – für die Schlussansage
     * @param chain       Nummer des Auftrags ({@link #chains}) – gilt sie nicht mehr, startet kein weiteres Stück
     * @param extraOwn    Stellen mit Steinen eines Gangs, aus dem die Treppe steigt (TunnelScan.Room.own), lokal
     * @param room        Innenraum dieses Gangs, lokal – dort baut die Treppe nichts hinein
     * @param force       „trotzdem“ eines Admins: auch durch Gebautes
     * @param depth       Treppe: lokale Fußhöhe, bei der sie endet (TunnelShape.NO_DEPTH ohne)
     * @param bedrock     Grundgestein über der untersten Schicht abbauen (Stufe „gefährlich“)
     * @param spoil       Abgebautes dieses Auftrags, über alle Stücke – volle Fässer kommen in die Tunnelwand
     */
    private record Job(BuildService.Request request, TunnelOrder order, TunnelOrder.Kind kind, SiteFrame frame,
            int half, int height, int length, int start, int anchor, boolean entrance, TunnelStyle style,
            MaterialChest chest, boolean free, boolean follow, boolean endless, Set<String> ownIds, boolean resumed,
            int rest, int builtBefore, long chain, Set<Pos> extraOwn, Set<Pos> room, boolean force, int depth,
            boolean bedrock, SpoilStore<Material> spoil) {
    }

    /**
     * Vor Warteschlange und LLM (BuildService.request; Vorfall 2026-11-16 auf dev01): „/bm tunnel stopp“ ging ans LLM
     * und setzte einen Redstone-Block; „/bm tunnel 20000 schnell“ wartete in der Warteschlange und höhlte dann – vom
     * LLM als Tunnel gedeutet – 49 × 43 × 4 Blöcke Fels aus; „/bm tunnel 2000 schnell“ beim Mitgehen wartete hinter dem
     * endlosen Tunnel und begann sofort, als der Spieler „stop“ sagte. Jetzt hält „tunnel stopp“ an, eine zu lange
     * Länge bekommt eine Absage, und ein neuer Grabauftrag löst den eigenen laufenden ab.
     *
     * @return true = erledigt (gestoppt oder abgesagt); false = weiter wie gewohnt
     */
    boolean early(BuildService.Request r) {
        String prompt = r.prompt();
        if (prompt == null) {
            return false;
        }
        Optional<TunnelOrder> parsed = TunnelOrder.parse(prompt);
        if (parsed.isEmpty()) {
            return switch (TunnelOrder.problem(prompt)) {
                case STOP -> {
                    service.stop(r.sender());
                    yield true;
                }
                case TOO_LONG -> {
                    say(r, "So weit grabe ich nicht am Stück – höchstens "
                            + String.format(Locale.GERMANY, "%,d", TunnelOrder.MAX_LENGTH) + " Blöcke. Danach im Gang "
                            + "einfach „bau weiter " + TunnelOrder.MAX_LENGTH + " schnell“.");
                    LOG.info(() -> "tunnel | abgesagt | besitzer=" + r.ownerName() + " grund=zu_lang");
                    yield true;
                }
                case NONE -> false;
            };
        }
        UUID key = key(r);
        if (chains.remove(key) != null) {
            LOG.info(() -> "tunnel | umgeschaltet | besitzer=" + r.ownerName());
            service.cancelDig(CONSOLE.equals(key) ? null : key, "Ich schalte um.");
        }
        return false;
    }

    /**
     * „stopp“ (BuildService.stop): Grabaufträge beenden, die mine dem Stoppenden erlaubt – auch in der Pause zwischen
     * zwei Stücken. true = gerade wurde ein Stück vorbereitet (dann läuft kein Bau, den BuildService abbrechen könnte).
     */
    boolean stop(Predicate<UUID> mine) {
        boolean between = false;
        for (Iterator<Map.Entry<UUID, Long>> it = chains.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Long> e = it.next();
            UUID owner = e.getKey();
            if (mine.test(CONSOLE.equals(owner) ? null : owner)) {
                boolean pending = preparing.contains(e.getValue());
                between |= pending;
                it.remove();
                LOG.info(() -> "tunnel | gestoppt | besitzer=" + owner + " zwischen_stücken=" + pending);
            }
        }
        return between;
    }

    private static UUID key(BuildService.Request r) {
        return r.ownerId() != null ? r.ownerId() : CONSOLE;
    }

    /** Gilt der Auftrag dieses Stücks noch (nicht gestoppt, nicht von einem neuen abgelöst)? */
    private boolean current(Job job) {
        return Objects.equals(chains.get(key(job.request())), job.chain());
    }

    private void endChain(Job job) {
        chains.remove(key(job.request()), job.chain());
    }

    /**
     * Gehört der Auftrag hierher? true = erledigt (gestartet, vorbereitet oder mit Begründung abgesagt); false = kein
     * Grabauftrag, oder „bau weiter“ ohne erkennbaren Tunnel/Brücke – dann der gewohnte Weg (Gedächtnis, LLM).
     */
    boolean request(BuildService.Request r, String prompt) {
        Optional<TunnelOrder> parsed = TunnelOrder.parse(prompt);
        if (parsed.isEmpty()) {
            return false;
        }
        TunnelOrder order = parsed.get();
        Location feet = r.feet();
        World world = feet.getWorld();
        Cardinal asked = direction(order.turn(), r.facing());
        Player p = r.sender() instanceof Player pl ? pl : null;
        TunnelScan.Directed hit = recognise(order, feet, asked);
        if (order.resume() && hit == null) {
            if (order.kind() == null) {
                return false;
            }
            // Vorher fing er hier neu an – in Blickrichtung, also womöglich quer oder versetzt zum vorhandenen Gang.
            say(r, "Hier finde ich " + noun(order.kind(), false) + " zum Weiterbauen. Stell dich hinein, höchstens "
                    + TunnelScan.MAX_WALK + " Blöcke vor sein Ende.");
            LOG.info(() -> "tunnel | abgesagt | besitzer=" + r.ownerName() + " grund=nichts_erkannt");
            return true;
        }
        TunnelScan.Found found = hit == null ? null : hit.found();
        if (found != null && order.kind() != null && order.kind() != found.kind()) {
            say(r, "Das hier ist " + noun(found.kind(), true) + " – den baue ich weiter.");
        }
        Cardinal dir = hit != null ? hit.dir() : asked;
        SiteFrame here = frameAt(feet, dir);
        TunnelOrder.Kind kind = found != null ? found.kind() : order.kind();
        if (kind == TunnelOrder.Kind.STAIRS_UP && world.getEnvironment() != World.Environment.NORMAL) {
            say(r, "Im Nether und im End gibt es keine Oberfläche, zu der eine Treppe führen könnte.");
            return true;
        }
        SiteFrame frame = here;
        int half = order.sizeOrNormal().half;
        int height = order.sizeOrNormal().height;
        int start = 0;
        int anchor = TunnelShape.NO_ANCHOR;
        if (found != null) {
            Pos w = new Orientation(dir).toWorldOffset(found.end());
            frame = new SiteFrame(world, feet.getBlockX() + w.x(), feet.getBlockY() + w.y(), feet.getBlockZ() + w.z(),
                    new Orientation(dir), 0);
            half = order.size() != null ? order.size().half : found.half();
            height = order.size() != null ? order.size().height : found.height();
            start = 1;
            anchor = found.anchor();
        }
        // Zielhöhe einer Treppe („bis Höhe -59“), lokal zur Fußhöhe am Start.
        int depth = TunnelShape.NO_DEPTH;
        if (order.targetY() != TunnelOrder.NO_TARGET && kind.stairs()) {
            depth = order.targetY() - frame.feetY();
            boolean down = kind == TunnelOrder.Kind.STAIRS_DOWN;
            if (down ? depth >= 0 : depth <= 0) {
                say(r, "Du stehst schon " + (down ? "auf oder unter" : "auf oder über") + " Höhe " + order.targetY()
                        + ".");
                return true;
            }
        }
        // Treppe aus einem Tunnel heraus (Nutzer 2026-11-16, „wie mache ich die Treppe nach oben?“): Steht der Spieler
        // in einem Gang, darf die Treppe dessen Hülle durchbrechen und baut nichts in den Gang hinein
        // (TunnelScan.room). Erkannt ohne sein Ende (TunnelScan.passage) – mitten im Tunnel quer über die Karte liegt
        // keins in Reichweite.
        TunnelScan.Room room = null;
        if (kind.stairs() && found == null) {
            TunnelScan.Directed inside = TunnelScan.passage(dir, d -> look(frameAt(feet, d))).orElse(null);
            if (inside != null) {
                room = TunnelScan.room(inside, dir, look(frameAt(feet, inside.dir())), ROOM_REACH);
            }
        }
        // „trotzdem“ (Wunsch 2026-11-16, „ich will auch override machen können“): nur Admins graben durch Gebautes.
        boolean force = order.force() && r.sender().hasPermission("baumeister.admin");
        if (order.force() && !force) {
            say(r, "Durch Gebautes grabe ich nur für Admins – ich höre davor auf.");
        }
        // Mitbauen nur mit einem Spieler, auf den er warten kann: Von der Konsole (bauenbei) stünde der Bau sonst fünf
        // Minuten vor dem ersten Abschnitt (BuildJob.GATE_LIMIT_MS) – dort gilt ein festes Stück.
        boolean follow = order.follow() && p != null;
        boolean endless = follow && order.length() == 0;
        int maxExtent = settings.maxExtent();
        int length;
        int rest = 0;
        if (kind.stairs()) {
            length = Math.min(order.length(), maxExtent);
        } else if (order.length() > 0) {
            // Quer über die Karte (Wunsch 2026-11-16): je Stück höchstens bau.max-ausdehnung, der Rest folgt im selben
            // Rahmen (Run.finished). Vorher kappte bau.max-ausdehnung die Länge stillschweigend.
            length = Math.min(order.length(), maxExtent);
            rest = order.length() - length;
        } else {
            length = follow ? FOLLOW_SEGMENT : FAST_DEFAULT;
        }

        // Frei bauen oder aus der Kiste (Wunsch 2026-11-16).
        boolean free = !order.fromChest() && (r.sender().hasPermission("baumeister.admin")
                || p != null && p.getGameMode() == GameMode.CREATIVE);
        MaterialChest chest = MaterialChest.find(feet, p);
        TunnelStyle.Realm realm = realm(world);
        TunnelOrder.Size size = sizeFor(half, height);
        String preferred = order.material() != null ? order.material()
                : found != null && found.wall() != null && !TunnelStyle.isRaw(found.wall()) ? found.wall() : null;
        TunnelStyle style;
        if (free) {
            TunnelStyle base = TunnelStyle.forPlace(realm, frame.feetY(), order.material(), kind, size);
            style = found != null && order.material() == null ? merged(base, found) : base;
        } else {
            if (chest == null) {
                say(r, "Dafür brauche ich eine Kiste mit Material neben dir (bis " + MaterialChest.RADIUS
                        + " Blöcke): " + needs(kind) + ". Was ich abbaue, lege ich hinein.");
                LOG.info(() -> "tunnel | abgesagt | besitzer=" + r.ownerName() + " grund=keine_kiste");
                return true;
            }
            TunnelStyle.Choice c = TunnelStyle.fromChest(chest.counts(), kind, size, preferred);
            if (c.style() == null) {
                say(r, "In der Kiste bei " + at(chest.location()) + " fehlen mir " + c.missing() + ".");
                LOG.info(() -> "tunnel | abgesagt | besitzer=" + r.ownerName() + " grund=material_fehlt");
                return true;
            }
            style = c.style();
        }
        style = onServer(style, realm, kind, size);
        Set<String> ownIds = new HashSet<>(style.ids());
        if (found != null) {
            for (String id : new String[] {found.wall(), found.floor(), found.rib()}) {
                if (id != null) {
                    ownIds.add(id);
                }
            }
        }
        if (room != null) {
            ownIds.addAll(room.ids());
        }
        long chain = ++chainCounter;
        chains.put(key(r), chain);
        // Grundgestein über der untersten Schicht nur, wer gefährliche Blöcke ändern darf (BlockTiers: Abreißen von
        // Grundgestein ist die Stufe „gefährlich“) – sonst ließe der Bau diese Zellen aus, und der Gang hätte Stufen.
        boolean bedrock = r.sender().hasPermission("baumeister.bauen.gefaehrlich"); // umlaut-ok: Rechtename
        Job job = new Job(r, order, kind, frame, half, height, length, start, anchor, found == null, style, chest,
                free, follow, endless, Set.copyOf(ownIds), found != null, rest, 0, chain,
                room == null ? Set.of() : room.own(), room == null ? Set.of() : room.inside(), force, depth, bedrock,
                new SpoilStore<>(Material::getMaxStackSize));
        TunnelOrder.Kind k = kind;
        boolean fromTunnel = room != null;
        LOG.info(() -> String.format("tunnel | auftrag | besitzer=%s art=%s weiter=%s länge=%d rest=%d mitbauen=%s "
                + "endlos=%s schnell=%s frei=%s kiste=%s stil=%s richtung=%s blick=%s bis_ins_freie=%s trotzdem=%s "
                + "aus_dem_gang=%s", r.ownerName(), k, job.resumed(), job.length(), job.rest(), follow, endless,
                order.fast(), free, chest == null ? "-" : at(chest.location()), job.style().name(), dir, r.facing(),
                order.daylight(), force, fromTunnel));
        prepare(job, true);
        return true;
    }

    /** Chunks entlang des Wegs vorladen, dann planen und starten. announce: neuer Auftrag (sonst nächstes Stück). */
    private void prepare(Job job, boolean announce) {
        int reach = job.start() + Math.min(settings.maxExtent(), job.length()
                + (job.entrance() && job.kind() == TunnelOrder.Kind.TUNNEL ? APPROACH : 0));
        if (job.kind().stairs() && job.length() == 0) {
            reach = job.start() + settings.maxExtent();
        }
        long[] keys = pathChunks(job.frame(), job.start(), reach, job.half() + 3);
        int limit = reach;
        preparing.add(job.chain());
        service.digThinking(true);
        ChunkPrefetch.ensure(ChunkPrefetch.of(plugin, job.frame().world()), keys, 2, held -> {
            preparing.remove(job.chain());
            service.digThinking(false);
            try {
                if (!current(job)) {
                    LOG.info(() -> "tunnel | nicht_gestartet | besitzer=" + job.request().ownerName() + " ab="
                            + job.start() + " grund=gestoppt");
                    return;
                }
                plan(job, limit, announce);
            } finally {
                if (held != null) {
                    Bukkit.getScheduler().runTaskLater(plugin, held::release, 100L);
                }
            }
        });
    }

    private void plan(Job job, int scanLimit, boolean announce) {
        plan(job, scanLimit, announce, 0);
    }

    /** crossings: so viele Gänge hat dieses Stück schon gekreuzt (je einmal neu geplant). */
    private void plan(Job job, int scanLimit, boolean announce, int crossings) {
        BuildService.Request r = job.request();
        TunnelSite site = new TunnelSite(job.frame(), ownCells(job), job.ownIds(),
                new WorldStructures(job.frame().world()), job.room(), job.force(), job.bedrock());
        if (job.kind() == TunnelOrder.Kind.STAIRS_UP && job.start() > 0 && site.sky(new Pos(0, 0, 0))) {
            endChain(job);
            say(r, "Die Treppe ist schon oben angekommen.");
            return;
        }
        long t0 = System.nanoTime();
        TunnelShape.Result result = TunnelShape.plan(new TunnelShape.Spec(job.kind(), job.half(), job.height(),
                job.length(), scanLimit, job.start(), job.anchor(), job.entrance(), job.style(),
                job.order().daylight(), job.depth()), site);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOG.info(() -> String.format("tunnel | plan | besitzer=%s abschnitte=%d stopp=%s aktionen=%d licht=%d "
                + "röhre_ab=%d ab=%d schacht=%d ms=%d", r.ownerName(), result.slices(), result.stop(),
                result.build().actions().size(), result.lights(), result.openFrom(), job.start(),
                site.mineshaftCells(), ms));
        // „trotzdem“ durch einen Gang (Wunsch 2026-11-16): Ohne diese Stelle setzte der Tunnel Wände und Front quer in
        // dessen Innenraum – der Gang wäre dort zu. Ein Tunnel quer zu ihm kreuzt ihn jetzt (Innenraum frei, seine
        // Steine im Weg abgebaut, dahinter weiter). Eine Treppe kreuzt keinen Gang – ihre Stufen stünden darin, nach
        // unten bräche sie durch seinen Boden –, ebenso kein Tunnel längs hinein: Dann ohne „trotzdem“ davor halten,
        // builtText sagt, wie es weitergeht. Höchstens MAX_CROSSINGS Gänge je Stück. Gesucht wird an jedem Gebauten,
        // das „trotzdem“ abträgt, nicht nur am ersten – stünden Bretter vor dem Gang, fände die Suche dort keinen
        // Gang, und die Wände gingen doch quer durch ihn.
        if (job.force() && job.kind() != TunnelOrder.Kind.BRIDGE) {
            TunnelScan.Crossing found = null;
            int searched = Integer.MIN_VALUE;
            for (int k : site.forcedSlices()) {
                if (k > searched) {
                    found = crossing(job, k);
                    if (found != null) {
                        break;
                    }
                    searched = k + TunnelScan.CROSSING_AHEAD;
                }
            }
            TunnelScan.Crossing x = found;
            if (x != null) {
                boolean cross = job.kind() == TunnelOrder.Kind.TUNNEL && !along(job, x) && crossings < MAX_CROSSINGS;
                Job next = cross ? withRoom(job, roomAt(job, x)) : withoutForce(job);
                LOG.info(() -> "tunnel | " + (cross ? "kreuzung" : "nicht_hindurch") + " | besitzer=" + r.ownerName()
                        + " gang_bei=" + at(job.frame().blockAt(x.feet()).getLocation()) + " richtung="
                        + x.tunnel().dir());
                plan(next, scanLimit, announce, crossings + 1);
                return;
            }
        }
        boolean stairs = job.kind().stairs();
        if (result.empty() || stairs && (result.stop() == TunnelShape.Stop.NO_SURFACE
                || result.stop() == TunnelShape.Stop.WATER)) {
            endChain(job);
            say(r, refusal(job, result));
            return;
        }
        if (announce) {
            say(r, announcement(job, result));
        } else if (result.stop() != TunnelShape.Stop.LENGTH) {
            say(r, stopText(job, result, job.builtBefore() + result.slices()));
        }
        Run run = new Run(job, result);
        if (!service.startDig(r, result.build(), job.frame(), run)) {
            endChain(job);
            run.release();
        }
    }

    /**
     * Gang ab Abschnitt k im Weg (TunnelScan.crossing), lokal zum Bau – null ohne, oder wenn es der Gang ist, aus dem
     * die Treppe steigt.
     */
    private TunnelScan.Crossing crossing(Job job, int k) {
        if (k < 0) {
            return null;
        }
        SiteFrame f = job.frame();
        Cardinal dir = f.orientation().forward();
        TunnelScan.Crossing x = TunnelScan.crossing(look(f), job.kind(), k,
                feet -> TunnelScan.passage(dir, d -> look(frameAt(f.blockAt(feet).getLocation(), d)))).orElse(null);
        return x == null || job.room().contains(x.feet()) ? null : x;
    }

    /** Der Gang läuft in Baurichtung (oder genau entgegen) – längs hinein statt quer hindurch. */
    private static boolean along(Job job, TunnelScan.Crossing x) {
        Cardinal dir = job.frame().orientation().forward();
        return x.tunnel().dir() == dir || x.tunnel().dir() == dir.opposite();
    }

    /** Innenraum und Steine des gekreuzten Gangs, lokal zum Bau (TunnelScan.room liefert sie lokal zu dessen Füßen). */
    private static TunnelScan.Room roomAt(Job job, TunnelScan.Crossing x) {
        SiteFrame f = job.frame();
        Location feet = f.blockAt(x.feet()).getLocation();
        return TunnelScan.room(x.tunnel(), f.orientation().forward(), look(frameAt(feet, x.tunnel().dir())),
                ROOM_REACH).shifted(x.feet());
    }

    private static Job withRoom(Job job, TunnelScan.Room room) {
        Set<Pos> own = new HashSet<>(job.extraOwn());
        own.addAll(room.own());
        Set<Pos> inside = new HashSet<>(job.room());
        inside.addAll(room.inside());
        Set<String> ids = new HashSet<>(job.ownIds());
        ids.addAll(room.ids());
        return new Job(job.request(), job.order(), job.kind(), job.frame(), job.half(), job.height(), job.length(),
                job.start(), job.anchor(), job.entrance(), job.style(), job.chest(), job.free(), job.follow(),
                job.endless(), Set.copyOf(ids), job.resumed(), job.rest(), job.builtBefore(), job.chain(),
                Set.copyOf(own), Set.copyOf(inside), job.force(), job.depth(), job.bedrock(), job.spoil());
    }

    private static Job withoutForce(Job job) {
        return new Job(job.request(), job.order(), job.kind(), job.frame(), job.half(), job.height(), job.length(),
                job.start(), job.anchor(), job.entrance(), job.style(), job.chest(), job.free(), job.follow(),
                job.endless(), job.ownIds(), job.resumed(), job.rest(), job.builtBefore(), job.chain(),
                job.extraOwn(), job.room(), false, job.depth(), job.bedrock(), job.spoil());
    }

    /** Eigene Steine, die der Bau abbauen darf: Abschluss des Stücks davor und Hülle des Gangs einer Treppe. */
    private static Set<Pos> ownCells(Job job) {
        Set<Pos> own = new HashSet<>(job.extraOwn());
        if (job.start() > 0) {
            own.addAll(faceCells(job));
        }
        return own;
    }

    // ---------------------------------------------------------------- Bau-Haken

    /** Was der BuildJob beim Graben fragt (BuildJob.Dig). */
    private final class Run implements BuildJob.Dig {
        private final Job job;
        private final TunnelShape.Result result;
        private final World world;
        private final boolean nether;
        private final UUID owner;
        /** Bauwerksgrenzen der Welt für foreign – je Stück frisch gelesen. */
        private final WorldStructures structures;
        /**
         * Eigenes Mauerwerk in Weltkoordinaten: Abschluss des Stücks davor bzw. Hülle des Gangs, aus dem eine Treppe
         * steigt (ownCells).
         */
        private final Set<Long> face = new HashSet<>();
        private boolean openSaid;
        /** Abschnitt, an dem der Bau gerade ist – beim Wechsel ist der vorige fertig (Nische für ein Fass). */
        private int lastSlice = -1;
        /** Chunk der Kiste: bleibt geladen, solange gebaut wird – sonst läge sie beim langen Weg im Entladenen. */
        private long ticket = Long.MIN_VALUE;

        Run(Job job, TunnelShape.Result result) {
            this.job = job;
            this.result = result;
            this.world = job.frame().world();
            this.nether = world.getEnvironment() == World.Environment.NETHER;
            this.owner = job.request().ownerId();
            this.structures = new WorldStructures(world);
            for (Pos p : ownCells(job)) {
                face.add(blockKey(job.frame().blockAt(p)));
            }
            if (job.chest() != null) {
                Location c = job.chest().location();
                ticket = ChunkPrefetch.key(c.getBlockX() >> 4, c.getBlockZ() >> 4);
                world.addPluginChunkTicket(c.getBlockX() >> 4, c.getBlockZ() >> 4, plugin);
            }
        }

        void release() {
            if (ticket != Long.MIN_VALUE) {
                world.removePluginChunkTicket(ChunkPrefetch.cx(ticket), ChunkPrefetch.cz(ticket), plugin);
                ticket = Long.MIN_VALUE;
            }
        }

        @Override
        public Location stand(int slice) {
            return job.frame().blockAt(TunnelShape.stand(job.kind(), slice)).getLocation().add(0.5, 0, 0.5);
        }

        @Override
        public boolean ready(int slice) {
            if (slice > lastSlice) {
                if (lastSlice >= 0) {
                    stow(lastSlice, false);
                }
                lastSlice = slice;
            }
            boolean go = !job.follow() || near(slice);
            // Mitbauen: Wer im Fels mitgeht, sieht erst an den Fenstern, dass es ins Freie geht – einmal sagen, was
            // passiert, wenn die Röhre in diesem Stück beginnt (nicht in jedem Stück einer langen Talquerung).
            if (go && job.follow() && !openSaid && result.openFrom() > result.first() && slice >= result.openFrom()) {
                openSaid = true;
                say(job.request(), "Hier geht es ins Freie – ich baue geradeaus als Röhre mit Fenstern weiter. "
                        + "„stopp“ beendet.");
            }
            return go;
        }

        /** Mitbauen: Der Spieler ist nah genug am Abschnitt (nicht weiter als LEAD dahinter, seitlich am Weg). */
        private boolean near(int slice) {
            Player p = owner == null ? null : Bukkit.getPlayer(owner);
            if (p == null || !p.getWorld().equals(world)) {
                return false;
            }
            Location l = p.getLocation();
            SiteFrame f = job.frame();
            Pos local = f.orientation().toLocalOffset(new Pos(l.getBlockX() - f.feetX(), l.getBlockY() - f.feetY(),
                    l.getBlockZ() - f.feetZ()));
            return Math.abs(local.x()) <= job.half() + SIDE && slice <= local.z() + LEAD;
        }

        @Override
        public Material missing(Block b, BlockData target) {
            if (job.free() || target.getMaterial().isAir() || b.getType() == target.getMaterial()) {
                return null;
            }
            Material item = target.getPlacementMaterial();
            MaterialChest chest = job.chest();
            return chest != null && chest.present() && chest.has(item) ? null : item;
        }

        @Override
        public void settle(Block b, BlockData before, BlockData target) {
            if (before.getMaterial() == target.getMaterial()) {
                return;  // nur ein anderer Zustand: kein Material, nichts abgebaut
            }
            MaterialChest chest = job.chest();
            if (!job.free() && !target.getMaterial().isAir() && chest != null) {
                chest.take(target.getPlacementMaterial());
            }
            // Abgebautes (Wunsch 2026-11-16, „stellt er immer in einer vollen Kiste ab – nicht nur für den Admin“):
            // erst in die Materialkiste, was nicht passt, sammelt sich für volle Fässer in der Tunnelwand (stow). Eine
            // Brücke ohne Kiste sammelt nicht – sie hat keine Wand, und Fässer stünden auf dem Deck.
            boolean present = chest != null && chest.present();
            if (!before.getMaterial().isAir() && (job.kind() != TunnelOrder.Kind.BRIDGE || present)) {
                Collection<ItemStack> drops = b.getDrops(TOOL);
                for (ItemStack s : present ? chest.store(drops) : drops) {
                    job.spoil().add(s.getType(), s.getAmount());
                }
            }
        }

        /** Wandreihe, in die ein Fass kommt: unten an der Seite (Tunnel: Fußhöhe, Treppe: neben der Stufe). */
        private int nicheRow(int k) {
            int base = TunnelShape.base(job.kind(), k);
            return job.kind().stairs() ? base : base + 1;
        }

        /**
         * Volle Fässer in die Wand – bei all auch den Rest. Gesucht wird ab Abschnitt k rückwärts eine Nische: eigene
         * Wand- oder Sockelsteine (kein Gurtbogen, kein Fenster, kein Licht) mit Fels dahinter – nicht über einem Tal
         * und nicht im Wasser.
         */
        private void stow(int k, boolean all) {
            SpoilStore<Material> s = job.spoil();
            int from = k;
            while (s.slots() >= SpoilStore.BARREL_SLOTS || all && !s.isEmpty()) {
                Block niche = null;
                int side = 0;
                for (int j = from; j >= Math.max(job.start(), from - 24) && niche == null; j--) {
                    for (int sd : new int[] {1, -1}) {
                        Pos local = new Pos(sd * (job.half() + 1), nicheRow(j), j);
                        if (isNiche(local, sd)) {
                            niche = job.frame().blockAt(local);
                            side = sd;
                            from = j;
                            break;
                        }
                    }
                }
                if (niche == null || !placeBarrel(niche, side, s)) {
                    break;
                }
            }
            if (all && !s.isEmpty()) {
                // Kein Platz in der Wand (Röhre im Freien) oder keine Fässer in der Kiste: neben die Materialkiste
                // bzw. an den Platz des Baumeisters – verloren geht nichts.
                List<ItemStack> rest = new ArrayList<>();
                for (Map.Entry<Material, Integer> e : s.take(Integer.MAX_VALUE)) {
                    rest.add(new ItemStack(e.getKey(), e.getValue()));
                }
                MaterialChest chest = job.chest();
                if (chest != null && chest.present()) {
                    chest.deposit(rest);
                } else {
                    Location at = stand(Math.max(job.start(), lastSlice));
                    for (ItemStack r : rest) {
                        world.dropItem(at, r);
                    }
                }
                LOG.info(() -> "tunnel | abraum_daneben | besitzer=" + job.request().ownerName() + " stapel="
                        + rest.size());
            }
        }

        private boolean isNiche(Pos local, int side) {
            Block b = job.frame().blockAt(local);
            String id = b.getType().getKey().getKey();
            TunnelStyle st = job.style();
            boolean lining = id.equals(st.wall().id()) || st.trim() != null && id.equals(st.trim().id());
            if (!lining) {
                return false;
            }
            Block behind = job.frame().blockAt(new Pos(local.x() + side, local.y(), local.z()));
            return behind.getType().isSolid() && !behind.isLiquid();
        }

        /** Ein Fass mit bis zu 27 Stapeln in die Nische, Öffnung zum Gang. false = kein Fass zu haben. */
        private boolean placeBarrel(Block niche, int side, SpoilStore<Material> s) {
            if (!job.free()) {
                // Wer aus der Kiste baut, bezahlt das Fass: ein Fass oder eine Kiste aus der Materialkiste.
                MaterialChest chest = job.chest();
                boolean paid = chest != null && chest.present()
                        && (chest.take(Material.BARREL) || chest.take(Material.CHEST));
                if (!paid) {
                    if (!s.noBarrelSaid) {
                        s.noBarrelSaid = true;
                        say(job.request(), "Die Kiste ist voll. Leg mir Fässer oder Kisten hinein, dann stelle ich das "
                                + "Abgebaute in vollen Fässern in die Tunnelwand.");
                    }
                    return false;
                }
            }
            niche.setType(Material.BARREL, false);
            if (niche.getBlockData() instanceof org.bukkit.block.data.Directional d) {
                Cardinal inward = job.frame().orientation().toWorld(side > 0 ? "left" : "right");
                d.setFacing(org.bukkit.block.BlockFace.valueOf(inward.name()));
                niche.setBlockData(d, false);
            }
            int stacks = 0;
            if (niche.getState() instanceof org.bukkit.block.Container c) {
                org.bukkit.inventory.Inventory inv = c.getInventory();
                for (Map.Entry<Material, Integer> e : s.take(SpoilStore.BARREL_SLOTS)) {
                    inv.addItem(new ItemStack(e.getKey(), e.getValue()));
                    stacks++;
                }
            }
            s.placed++;
            s.last = niche.getLocation();
            int n = stacks;
            LOG.info(() -> "tunnel | fass | besitzer=" + job.request().ownerName() + " nr=" + s.placed + " stapel=" + n
                    + " bei=" + at(niche.getLocation()));
            if (!s.told) {
                s.told = true;
                say(job.request(), "Ein volles Fass mit dem Abgebauten steht in der Tunnelwand bei "
                        + at(niche.getLocation()) + " – so geht es weiter, sobald wieder eins voll ist.");
            }
            return true;
        }

        @Override
        public boolean foreign(Block b) {
            // Den Abschluss des Stücks davor räumt der erste neue Abschnitt wieder aus (TunnelSite.ownFace beim
            // Planen). Ohne die Ausnahme hier hielt der Bau ihn für Gebautes und hörte am ersten Block auf – gemessen
            // 2026-11-16 auf Spigot: Weiterbauen hinter einer Röhre im Freien, „fremdes_im_weg … deepslate_bricks“.
            // Im Fels fiel das nie auf (dort steht Stein statt Abschluss); jede Stückgrenze einer langen Röhre über
            // ein Tal träfe es.
            if (face.contains(blockKey(b)) && job.ownIds().contains(b.getType().getKey().getKey())) {
                return false;
            }
            TunnelShape.Ground g = TunnelNature.forced(TunnelSite.classify(b, nether, structures), job.force());
            if (g == TunnelShape.Ground.FORBIDDEN && job.bedrock() && TunnelNature.bedrockDiggable(
                    b.getType().getKey().getKey(), b.getY(), world.getMinHeight(), nether)) {
                return false;
            }
            return g == TunnelShape.Ground.BUILT || g == TunnelShape.Ground.FORBIDDEN;
        }

        @Override
        public int fastPerTick(int actions) {
            // Schnellgang: jedes Stück in etwa drei Sekunden, mindestens 20 je Tick (400 Blöcke/s).
            return job.order().fast() ? Math.clamp(Math.ceilDiv(actions, 60), 20, 400) : 0;
        }

        @Override
        public boolean undoable() {
            return job.free();
        }

        @Override
        public String done(int placed, long seconds) {
            if (more()) {
                return "";  // es geht im nächsten Stück weiter
            }
            int n = job.builtBefore() + result.slices();
            String text = switch (job.kind()) {
                case TUNNEL -> job.resumed() ? "Fertig! " + n + " Blöcke weitergegraben"
                        : "Fertig! Der Tunnel ist " + n + " Blöcke lang";
                case STAIRS_UP -> "Fertig! " + Math.max(0, n - (job.start() == 0 ? 1 : 0)) + " Stufen nach oben";
                case STAIRS_DOWN -> "Fertig! " + Math.max(0, n - (job.start() == 0 ? 1 : 0)) + " Stufen nach unten"
                        + (result.stop() == TunnelShape.Stop.DEPTH ? " bis Höhe " + job.order().targetY() : "");
                case BRIDGE -> job.resumed() ? "Fertig! Die Brücke ist " + n + " Blöcke länger"
                        : "Fertig! Die Brücke ist " + n + " Blöcke lang";
            };
            text += result.lights() > 0 ? ", " + result.lights() + " Lichter." : ".";
            String why = stopText(job, result, n);
            if (!why.isEmpty()) {
                text += " " + why;
            }
            return text + (job.free() ? " Gefällt's dir nicht? /bm rückgängig" : "");
        }

        /** Kommt nach diesem Stück noch eines – ohne Ende, oder der Rest einer langen Länge? */
        private boolean more() {
            return result.stop() == TunnelShape.Stop.LENGTH && (job.endless() || job.rest() > 0);
        }

        @Override
        public void finished(BuildJob done, boolean complete) {
            release();
            if (!complete || !more() || !plugin.isEnabled() || !current(job)) {
                endChain(job);
                // Ende des Auftrags (fertig, gestoppt, abgelöst): auch der Rest kommt in ein Fass.
                if (plugin.isEnabled() && lastSlice >= 0) {
                    stow(lastSlice, true);
                    SpoilStore<Material> s = job.spoil();
                    if (s.placed > 1) {
                        say(job.request(), s.placed + " Fässer mit dem Abgebauten stehen in der Tunnelwand – das "
                                + "letzte bei " + at(s.last) + ".");
                    } else if (s.placed == 1 && !s.told) {
                        say(job.request(), "Das Abgebaute steht in einem Fass in der Tunnelwand bei " + at(s.last)
                                + ".");
                    }
                }
                return;
            }
            // Ohne Ende bzw. lange Länge (Wunsch 2026-11-16): das nächste Stück im selben Rahmen – dieselbe Linie und
            // Höhe –, Licht und Pfeiler laufen im selben Abstand weiter, kein Eingangsbogen.
            int length = job.endless() ? FOLLOW_SEGMENT : Math.min(job.rest(), settings.maxExtent());
            Job next = new Job(job.request(), job.order(), job.kind(), job.frame(), job.half(), job.height(),
                    length, result.last() + 1, job.anchor() != TunnelShape.NO_ANCHOR ? job.anchor() : result.first(),
                    false, job.style(), job.chest(), job.free(), job.follow(), job.endless(), job.ownIds(),
                    job.resumed(), job.endless() ? 0 : job.rest() - length, job.builtBefore() + result.slices(),
                    job.chain(), Set.of(), Set.of(), job.force(), job.depth(), job.bedrock(), job.spoil());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!current(job)) {
                    return;  // gestoppt oder von einem neuen Auftrag abgelöst
                }
                if (service.busy()) {
                    endChain(job);
                    say(job.request(), "Ich mache hier eine Pause – sag „bau weiter“, wenn's weitergehen soll.");
                    return;
                }
                LOG.info(() -> "tunnel | nächstes_stück | besitzer=" + job.request().ownerName() + " ab="
                        + next.start() + " länge=" + next.length() + " rest=" + next.rest());
                prepare(next, false);
            });
        }

        @Override
        public String chestAt() {
            return job.chest() == null ? "" : at(job.chest().location());
        }
    }

    // ---------------------------------------------------------------- Texte

    private static String noun(TunnelOrder.Kind kind, boolean definite) {
        return switch (kind) {
            case TUNNEL -> definite ? "ein Tunnel" : "keinen Tunnel";
            case STAIRS_UP -> definite ? "eine Treppe" : "keine Treppe";
            case STAIRS_DOWN -> definite ? "eine Treppe nach unten" : "keine Treppe nach unten";
            case BRIDGE -> definite ? "eine Brücke" : "keine Brücke";
        };
    }

    private static String needs(TunnelOrder.Kind kind) {
        return switch (kind) {
            case TUNNEL -> "Bausteine (z. B. Steinziegel) und Laternen, gern auch passende Treppen für den Bogen und "
                    + "Fässer für das Abgebaute";
            case STAIRS_UP, STAIRS_DOWN -> "Bausteine, Treppenstufen und Laternen";
            case BRIDGE -> "Bausteine, eine Mauer oder einen Zaun fürs Geländer und Laternen";
        };
    }

    private String announcement(Job job, TunnelShape.Result result) {
        String where = job.free() ? "" : " Material nehme ich aus der Kiste bei " + at(job.chest().location())
                + ", Abgebautes kommt hinein – rückgängig machen geht dabei nicht.";
        if (job.free() && job.chest() != null) {
            where = " Was ich abbaue, kommt in die Kiste bei " + at(job.chest().location()) + ".";
        }
        String look = " (" + job.style().name() + ")";
        String why = stopText(job, result, result.slices());
        String stop = why.isEmpty() ? "" : " " + why;
        String toward = " nach " + german(job.frame().orientation().forward());
        String blocks = job.rest() > 0 ? job.length() + job.rest() + " Blöcke Stück für Stück"
                : result.slices() + " Blöcke";
        String head = switch (job.kind()) {
            case TUNNEL -> job.resumed()
                    ? (job.order().resume() ? "Ich grabe den Tunnel geradeaus weiter"
                            : "Du stehst in einem Tunnel – ich grabe ihn geradeaus weiter") + toward
                            + (job.follow() ? "" : ", " + blocks) + look + "."
                    : job.follow() ? "Los geht's: Ich grabe vor dir her" + toward + look
                            + " – geh einfach mit, „stopp“ beendet."
                            : "Ich grabe dir einen Tunnel" + toward + ", " + blocks
                                    + (job.order().fast() ? ", im Schnellgang" : "") + look + ".";
            case STAIRS_UP -> "Ich baue " + (job.resumed() ? "die Treppe weiter" : "dir eine Treppe nach oben") + " – "
                    + Math.max(0, result.slices() - (job.start() == 0 ? 1 : 0)) + " Stufen"
                    + (result.stop() == TunnelShape.Stop.SURFACE ? " bis zur Oberfläche" : "") + look + ".";
            case STAIRS_DOWN -> "Ich baue " + (job.resumed() ? "die Treppe weiter nach unten"
                    : "dir eine Treppe nach unten") + " – " + Math.max(0, result.slices() - (job.start() == 0 ? 1 : 0))
                    + " Stufen" + (result.stop() == TunnelShape.Stop.DEPTH ? " bis Höhe " + job.order().targetY() : "")
                    + look + ".";
            case BRIDGE -> job.resumed() ? "Ich baue die Brücke weiter" + look + "."
                    : job.follow() ? "Ich baue die Brücke vor dir her" + look + " – geh einfach mit"
                            + (job.endless() ? ", sie hört erst auf, wenn du „stopp“ sagst." : ".")
                            : "Ich baue dir eine Brücke, " + result.slices() + " Blöcke"
                                    + (job.order().fast() ? ", im Schnellgang" : "") + look + ".";
        };
        if (job.resumed() && job.follow()) {
            head += " Geh einfach mit.";
        }
        if (job.kind() == TunnelOrder.Kind.TUNNEL && result.openFrom() >= 0) {
            head += " Über Tal und Wasser geht er als Röhre mit Fenstern weiter.";
        }
        return head + stop + where;
    }

    /** Warum der Bau früher endet als gewünscht. */
    private String stopText(Job job, TunnelShape.Result result, int n) {
        String at = result.stopAt() == null ? "" : " bei " + at(job.frame().blockAt(result.stopAt()).getLocation());
        String what = result.stopId() == null ? "" : TunnelStyle.german(result.stopId());
        return switch (result.stop()) {
            case BUILT -> builtText(job, result, "Nach " + n + " Blöcken steht etwas Gebautes (" + what + at
                    + ") – davor höre ich auf.");
            case FORBIDDEN -> TunnelSite.BORDER.equals(result.stopId())
                    ? "Nach " + n + " Blöcken ist der Rand der Welt erreicht – weiter geht es nicht."
                    : job.kind() == TunnelOrder.Kind.STAIRS_DOWN
                            ? "Unten ist die letzte Schicht Grundgestein – tiefer geht es nicht."
                            : "Nach " + n + " Blöcken kommt " + ("bedrock".equals(result.stopId())
                                    && !job.bedrock() ? "Grundgestein – das darf nur abbauen, wer gefährliche Blöcke "
                                            + "ändern darf." : TunnelStyle.german(result.stopId())
                                            + " – weiter geht es nicht.");
            case THROUGH -> "Nach " + n + " Blöcken bin ich durch – dort geht es ins Freie. Soll er als Röhre "
                    + "geradeaus weiter, sag dort „bau weiter“.";
            case WATER -> job.kind() == TunnelOrder.Kind.BRIDGE
                    ? "Nach " + n + " Blöcken steht das Wasser höher als der Weg – dort höre ich auf."
                    : "Nach " + n + " Blöcken käme Wasser von oben – dort höre ich auf.";
            case HILL -> "Nach " + n + " Blöcken kommt ein Berg – dort höre ich auf. Für weiter: „grab einen Tunnel“.";
            case NO_SURFACE -> "Bis zur Oberfläche reichen " + n + " Stufen nicht.";
            case LENGTH, SURFACE, ALREADY_UP, DEPTH -> "";
        };
    }

    private String refusal(Job job, TunnelShape.Result result) {
        String at = result.stopAt() == null ? "" : " bei " + at(job.frame().blockAt(result.stopAt()).getLocation());
        String what = result.stopId() == null ? "" : TunnelStyle.german(result.stopId());
        return switch (result.stop()) {
            case BUILT -> builtText(job, result, "Direkt vor dir steht etwas Gebautes (" + what + at
                    + ") – da baue ich nicht.");
            case FORBIDDEN -> "Da ist Grundgestein oder der Rand der Welt im Weg.";
            case HILL -> "Direkt vor dir ist ein Berg – für einen Weg hindurch sag „grab einen Tunnel“.";
            case WATER -> job.kind() == TunnelOrder.Kind.STAIRS_UP
                    ? "Oben ist Wasser – da mache ich keinen Ausgang. Versuch es in eine andere Richtung."
                    : job.kind() == TunnelOrder.Kind.BRIDGE
                            ? "Hier steht das Wasser höher als der Weg – stell dich ans Ufer, auf Wasserhöhe "
                                    + "oder darüber."
                            : "Da kommt gleich Wasser von oben – dort grabe ich nicht.";
            case ALREADY_UP -> "Du stehst doch schon oben.";
            case DEPTH -> "Da bist du schon.";
            case NO_SURFACE -> "Bis zur Oberfläche komme ich nicht – mehr als " + settings.maxExtent()
                    + " Stufen. Grab erst ein Stück höher.";
            case THROUGH, LENGTH, SURFACE -> job.kind() == TunnelOrder.Kind.TUNNEL
                    ? "Hier ist nichts zum Graben – stell dich vor Fels oder Erde."
                    : "Hier kann ich nichts bauen.";
        };
    }

    /**
     * Gebautes im Weg: Ist es ein Gang (TunnelScan.crossing), sagen, wie es dort weitergeht – „trotzdem“ hieße bei
     * einer Treppe oder einem Gang in Baurichtung nur, dass sie davor hält (plan). Sonst der Hinweis für Admins.
     */
    private String builtText(Job job, TunnelShape.Result result, String plain) {
        TunnelScan.Crossing x = result.stopAt() == null || job.kind() == TunnelOrder.Kind.BRIDGE ? null
                : crossing(job, result.stopAt().z());
        if (x == null) {
            return plain + forceHint(job);
        }
        String where = " (bei " + at(job.frame().blockAt(x.feet()).getLocation()) + ")";
        return plain + switch (job.kind()) {
            case TUNNEL -> along(job, x)
                    ? " Dort läuft ein Gang in dieselbe Richtung" + where + " – in ihn hinein grabe ich nicht. Stell "
                            + "dich hinein und sag „bau weiter“, dann geht er weiter."
                    : " Dort kreuzt ein Gang" + where + "." + (admin(job) ? " Mit „bau weiter trotzdem“ baue ich "
                            + "eine Kreuzung und grabe dahinter weiter." : "");
            case STAIRS_DOWN -> " Da unten verläuft ein Gang" + where + " – quer hindurch baue ich keine Treppe. "
                    + "Hinein geht es von dort: stell dich in den Gang, schau zur Seitenwand und sag „Treppe nach "
                    + "oben“.";
            case STAIRS_UP -> " Darüber verläuft ein Gang" + where + " – quer hindurch baue ich keine Treppe.";
            case BRIDGE -> forceHint(job);
        };
    }

    private static boolean admin(Job job) {
        return job.request().sender().hasPermission("baumeister.admin");
    }

    /** Für Admins: wie es trotzdem weitergeht (Wunsch 2026-11-16, „ich will auch override machen können“). */
    private static String forceHint(Job job) {
        return job.request().sender().hasPermission("baumeister.admin")
                ? " Soll ich trotzdem durch? Sag „bau weiter trotzdem“." : "";
    }

    private void say(BuildService.Request r, String text) {
        Location at = npc.get() != null ? npc.get().getLocation() : r.feet();
        Texts.say(npc.name(), at, r.sender(), text);
    }

    private static String at(Location l) {
        return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private static String german(Cardinal c) {
        return switch (c) {
            case NORTH -> "Norden";
            case EAST -> "Osten";
            case SOUTH -> "Süden";
            case WEST -> "Westen";
        };
    }

    // ---------------------------------------------------------------- Hilfen

    /**
     * Erkennen (Wunsch 2026-11-16, „ein Tunnel muss gerade gehen“): „bau weiter“ ohne Richtungsangabe sucht in allen
     * vier Richtungen – der Gang bestimmt, wohin es geht, nicht der Blick. Ein neuer Auftrag erkennt nur in der
     * angesagten Richtung, nur dieselbe Art und nur mit Fels am Ende: Wer im Tunnel steht und ihn entlangschaut,
     * meint ihn; wer zur Wand schaut, will abzweigen. Ein offenes Ende (Mündung ins Freie) setzt nur „bau weiter“
     * fort – sonst grübe „grab einen Tunnel“ mit Blick zum Eingang eine Röhre in die Wiese.
     */
    private static TunnelScan.Directed recognise(TunnelOrder order, Location feet, Cardinal asked) {
        if (order.resume()) {
            if (order.turn() == TunnelOrder.Turn.AHEAD) {
                return TunnelScan.around(asked, d -> look(frameAt(feet, d))).orElse(null);
            }
            return TunnelScan.scan(look(frameAt(feet, asked))).map(f -> new TunnelScan.Directed(asked, f))
                    .orElse(null);
        }
        return TunnelScan.scan(look(frameAt(feet, asked))).filter(f -> f.kind() == order.kind() && !f.open())
                .map(f -> new TunnelScan.Directed(asked, f)).orElse(null);
    }

    private static SiteFrame frameAt(Location feet, Cardinal dir) {
        return new SiteFrame(feet.getWorld(), feet.getBlockX(), feet.getBlockY(), feet.getBlockZ(),
                new Orientation(dir), 0);
    }

    /** Richtung aus dem Auftrag: geradeaus, links/rechts von der Blickrichtung oder eine Himmelsrichtung. */
    static Cardinal direction(TunnelOrder.Turn turn, Cardinal facing) {
        return switch (turn) {
            case AHEAD -> facing;
            case RIGHT -> facing.clockwise();
            case LEFT -> facing.clockwise().opposite();
            case NORTH -> Cardinal.NORTH;
            case EAST -> Cardinal.EAST;
            case SOUTH -> Cardinal.SOUTH;
            case WEST -> Cardinal.WEST;
        };
    }

    private static TunnelStyle.Realm realm(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> TunnelStyle.Realm.NETHER;
            case THE_END -> TunnelStyle.Realm.END;
            default -> TunnelStyle.Realm.OVERWORLD;
        };
    }

    /** Nächste Größe für die Palette (Kette erst ab Höhe 4). */
    private static TunnelOrder.Size sizeFor(int half, int height) {
        if (half == 0) {
            return TunnelOrder.Size.SMALL;
        }
        return height >= TunnelOrder.Size.LARGE.height || half >= TunnelOrder.Size.LARGE.half ? TunnelOrder.Size.LARGE
                : TunnelOrder.Size.NORMAL;
    }

    /**
     * Weiterbauen frei: das Erkannte genauso weiter (Wand, Boden, Bogen, Licht, Geländer), Fehlendes aus der Palette
     * der Gegend. Ein roher Gang (selbst gegraben, Wand aus Stein) hat keinen Stil – dann ganz die Palette der Gegend.
     */
    private static TunnelStyle merged(TunnelStyle base, TunnelScan.Found f) {
        if (f.wall() == null || TunnelStyle.isRaw(f.wall()) || !TunnelStyle.isWallBlock(f.wall())) {
            return base;
        }
        BlockSpec wall = BlockSpec.of(f.wall());
        BlockSpec floor = f.floor() != null && TunnelStyle.isWallBlock(f.floor()) ? BlockSpec.of(f.floor()) : wall;
        BlockSpec rib = f.rib() != null ? BlockSpec.of(f.rib()) : wall;
        BlockSpec light = f.light() != null ? BlockSpec.of(f.light()) : base.light();
        // Sockel und Mittelstreifen nur, wenn das Vorhandene sie hat (Wunsch 2026-11-16, „edel“): Ein Tunnel von
        // vorher geht einheitlich ohne weiter, statt mittendrin das Aussehen zu wechseln.
        return new TunnelStyle(TunnelStyle.german(f.wall()), wall, floor, rib,
                f.arch() != null ? BlockSpec.of(f.arch()) : null,
                f.step() != null ? BlockSpec.of(f.step()) : base.step(), light,
                f.chain() != null ? BlockSpec.of(f.chain()) : base.chain(),
                f.railing() != null ? BlockSpec.of(f.railing()) : base.railing(), base.window(),
                f.trim() != null ? BlockSpec.of(f.trim()) : null, f.runner() != null ? BlockSpec.of(f.runner()) : null);
    }

    /**
     * Jede ID muss dieser Server kennen (Paper 26.2 und Spigot 1.21.11 teilen die Paletten, TunnelStyleTest) – aus
     * der Kiste oder dem Erkannten kann aber eine kommen, die der Server nicht als Block führt. Dann die Palette der
     * Gegend.
     */
    private static TunnelStyle onServer(TunnelStyle style, TunnelStyle.Realm realm, TunnelOrder.Kind kind,
            TunnelOrder.Size size) {
        for (String id : style.ids()) {
            Material m = Material.matchMaterial(id);
            if (m == null || !m.isBlock()) {
                LOG.warning(() -> "tunnel | unbekannter_block | id=" + id + " – Palette der Gegend");
                return TunnelStyle.forPlace(realm, 64, null, kind, size);
            }
        }
        return style;
    }

    /**
     * Was TunnelScan sieht: Block an der lokalen Stelle mit lokalen Zuständen (facing=back = vorwärts), und ob die
     * Stelle über der Oberfläche liegt (offenes Ende eines Gangs, TunnelScan.Found.open).
     */
    private static TunnelScan.Look look(SiteFrame frame) {
        Orientation o = frame.orientation();
        TunnelSite site = new TunnelSite(frame, Set.of(), Set.of(), null);
        return new TunnelScan.Look() {
            @Override
            public BlockSpec at(Pos p) {
                Block b = frame.blockAt(p);
                String text = b.getBlockData().getAsString();
                try {
                    return o.toLocal(BlockSpec.parseTrusted(text));
                } catch (PlanException | RuntimeException e) {
                    // Zustände, die der Leser nicht kennt: ohne Zustände genügt für Wand, Boden und Licht.
                    return BlockSpec.of(b.getType().getKey().getKey());
                }
            }

            @Override
            public boolean sky(Pos p) {
                return site.sky(p);
            }
        };
    }

    /** Ein Block als Zahl (wie BlockPos.asLong: x und z je 26 Bit, y 12 Bit). */
    private static long blockKey(Block b) {
        return ((long) b.getX() & 0x3FFFFFFL) << 38 | ((long) b.getZ() & 0x3FFFFFFL) << 12 | b.getY() & 0xFFFL;
    }

    /** Innen des ersten neuen Abschnitts – dort darf der Abschluss des fertigen Stücks weg (TunnelSite.ownFace). */
    private static Set<Pos> faceCells(Job job) {
        Set<Pos> out = new HashSet<>();
        int k = job.start();
        int base = TunnelShape.base(job.kind(), k);
        for (int y = base; y <= base + job.height(); y++) {
            for (int x = -job.half(); x <= job.half(); x++) {
                out.add(new Pos(x, y, k));
            }
        }
        return out;
    }

    /** Chunks unter dem Weg von Abschnitt from bis to, side Blöcke zu beiden Seiten. */
    private static long[] pathChunks(SiteFrame f, int from, int to, int side) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x : new int[] {-side, side}) {
            for (int z : new int[] {from - 2, to + 2}) {
                Pos w = f.orientation().toWorldOffset(new Pos(x, 0, z));
                minX = Math.min(minX, (f.feetX() + w.x()) >> 4);
                minZ = Math.min(minZ, (f.feetZ() + w.z()) >> 4);
                maxX = Math.max(maxX, (f.feetX() + w.x()) >> 4);
                maxZ = Math.max(maxZ, (f.feetZ() + w.z()) >> 4);
            }
        }
        long[] out = new long[(maxX - minX + 1) * (maxZ - minZ + 1)];
        int n = 0;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                out[n++] = ChunkPrefetch.key(cx, cz);
            }
        }
        return out;
    }
}
