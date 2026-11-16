package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.TunnelOrder;
import com.blackn0va.baumeister.plan.TunnelScan;
import com.blackn0va.baumeister.plan.TunnelShape;
import com.blackn0va.baumeister.plan.TunnelStyle;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    private final BaumeisterPlugin plugin;
    private final BuildService service;
    private final Settings settings;
    private final BuilderNpc npc;

    TunnelService(BaumeisterPlugin plugin, BuildService service, Settings settings, BuilderNpc npc) {
        this.plugin = plugin;
        this.service = service;
        this.settings = settings;
        this.npc = npc;
    }

    /** Alles, was ein Stück braucht – auch das nächste beim Mitbauen ohne Ende. */
    private record Job(BuildService.Request request, TunnelOrder order, TunnelOrder.Kind kind, SiteFrame frame,
            int half, int height, int length, int start, int anchor, boolean entrance, TunnelStyle style,
            MaterialChest chest, boolean free, boolean follow, boolean endless, Set<String> ownIds, boolean resumed) {
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
        Cardinal dir = direction(order.turn(), r.facing());
        Player p = r.sender() instanceof Player pl ? pl : null;
        SiteFrame here = new SiteFrame(world, feet.getBlockX(), feet.getBlockY(), feet.getBlockZ(),
                new Orientation(dir), 0);
        TunnelScan.Found found = null;
        if (order.resume()) {
            found = TunnelScan.scan(look(here)).orElse(null);
            if (found == null && order.kind() == null) {
                return false;
            }
            if (found == null) {
                say(r, "Hier erkenne ich " + noun(order.kind(), false) + " zum Weiterbauen – ich fange hier neu an.");
            } else if (order.kind() != null && order.kind() != found.kind()) {
                say(r, "Das hier ist " + noun(found.kind(), true) + " – den baue ich weiter.");
            }
        }
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
        // Mitbauen nur mit einem Spieler, auf den er warten kann: Von der Konsole (bauenbei) stünde der Bau sonst fünf
        // Minuten vor dem ersten Abschnitt (BuildJob.GATE_LIMIT_MS) – dort gilt ein festes Stück.
        boolean follow = order.follow() && p != null;
        boolean endless = follow && order.length() == 0;
        int maxExtent = settings.maxExtent();
        int length = kind == TunnelOrder.Kind.STAIRS_UP ? Math.min(order.length(), maxExtent)
                : order.length() > 0 ? Math.min(order.length(), maxExtent)
                        : follow ? FOLLOW_SEGMENT : FAST_DEFAULT;

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
        Job job = new Job(r, order, kind, frame, half, height, length, start, anchor, found == null, style, chest,
                free, follow, endless, Set.copyOf(ownIds), found != null);
        TunnelOrder.Kind k = kind;
        LOG.info(() -> String.format("tunnel | auftrag | besitzer=%s art=%s weiter=%s länge=%d mitbauen=%s endlos=%s "
                + "schnell=%s frei=%s kiste=%s stil=%s richtung=%s", r.ownerName(), k, job.resumed(), job.length(),
                follow, endless, order.fast(), free, chest == null ? "-" : at(chest.location()), job.style().name(),
                dir));
        prepare(job, true);
        return true;
    }

    /** Chunks entlang des Wegs vorladen, dann planen und starten. announce: neuer Auftrag (sonst nächstes Stück). */
    private void prepare(Job job, boolean announce) {
        int reach = job.start() + Math.min(settings.maxExtent(), job.length()
                + (job.entrance() && job.kind() == TunnelOrder.Kind.TUNNEL ? APPROACH : 0));
        if (job.kind() == TunnelOrder.Kind.STAIRS_UP && job.length() == 0) {
            reach = job.start() + settings.maxExtent();
        }
        long[] keys = pathChunks(job.frame(), job.start(), reach, job.half() + 3);
        int limit = reach;
        service.digThinking(true);
        ChunkPrefetch.ensure(ChunkPrefetch.of(plugin, job.frame().world()), keys, 2, held -> {
            service.digThinking(false);
            try {
                plan(job, limit, announce);
            } finally {
                if (held != null) {
                    Bukkit.getScheduler().runTaskLater(plugin, held::release, 100L);
                }
            }
        });
    }

    private void plan(Job job, int scanLimit, boolean announce) {
        BuildService.Request r = job.request();
        Set<Pos> ownFace = job.start() > 0 ? faceCells(job) : Set.of();
        TunnelSite site = new TunnelSite(job.frame(), ownFace, job.ownIds());
        if (job.kind() == TunnelOrder.Kind.STAIRS_UP && job.start() > 0 && site.sky(new Pos(0, 0, 0))) {
            say(r, "Die Treppe ist schon oben angekommen.");
            return;
        }
        long t0 = System.nanoTime();
        TunnelShape.Result result = TunnelShape.plan(new TunnelShape.Spec(job.kind(), job.half(), job.height(),
                job.length(), scanLimit, job.start(), job.anchor(), job.entrance(), job.style()), site);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOG.info(() -> String.format("tunnel | plan | besitzer=%s abschnitte=%d stopp=%s aktionen=%d licht=%d ms=%d",
                r.ownerName(), result.slices(), result.stop(), result.build().actions().size(), result.lights(), ms));
        boolean stairs = job.kind() == TunnelOrder.Kind.STAIRS_UP;
        if (result.empty() || stairs && (result.stop() == TunnelShape.Stop.NO_SURFACE
                || result.stop() == TunnelShape.Stop.WATER)) {
            say(r, refusal(job, result));
            return;
        }
        if (announce) {
            say(r, announcement(job, result));
        } else if (result.stop() != TunnelShape.Stop.LENGTH) {
            say(r, stopText(job, result, result.slices()));
        }
        Run run = new Run(job, result);
        if (!service.startDig(r, result.build(), job.frame(), run)) {
            run.release();
        }
    }

    // ---------------------------------------------------------------- Bau-Haken

    /** Was der BuildJob beim Graben fragt (BuildJob.Dig). */
    private final class Run implements BuildJob.Dig {
        private final Job job;
        private final TunnelShape.Result result;
        private final World world;
        private final boolean nether;
        private final UUID owner;
        private boolean spillSaid;
        /** Chunk der Kiste: bleibt geladen, solange gebaut wird – sonst läge sie beim langen Weg im Entladenen. */
        private long ticket = Long.MIN_VALUE;

        Run(Job job, TunnelShape.Result result) {
            this.job = job;
            this.result = result;
            this.world = job.frame().world();
            this.nether = world.getEnvironment() == World.Environment.NETHER;
            this.owner = job.request().ownerId();
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
            if (!job.follow()) {
                return true;
            }
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
            if (!before.getMaterial().isAir() && chest != null && chest.present()) {
                Collection<ItemStack> drops = b.getDrops(TOOL);
                if (chest.deposit(drops) && !spillSaid) {
                    spillSaid = true;
                    say(job.request(), "Die Kiste ist voll – was ich abbaue, lege ich daneben.");
                }
            }
        }

        @Override
        public boolean foreign(Block b) {
            TunnelShape.Ground g = TunnelSite.classify(b, nether);
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
            if (job.endless() && result.stop() == TunnelShape.Stop.LENGTH) {
                return "";  // es geht im nächsten Stück weiter
            }
            int n = result.slices();
            String text = switch (job.kind()) {
                case TUNNEL -> "Fertig! Der Tunnel ist " + n + " Blöcke lang";
                case STAIRS_UP -> "Fertig! " + Math.max(0, n - (job.start() == 0 ? 1 : 0)) + " Stufen nach oben";
                case BRIDGE -> "Fertig! Die Brücke ist " + n + " Blöcke lang";
            };
            text += result.lights() > 0 ? ", " + result.lights() + " Lichter." : ".";
            if (result.stop() != TunnelShape.Stop.LENGTH && result.stop() != TunnelShape.Stop.SURFACE) {
                text += " " + stopText(job, result, n);
            }
            return text + (job.free() ? " Gefällt's dir nicht? /bm rückgängig" : "");
        }

        @Override
        public void finished(BuildJob done, boolean complete) {
            release();
            if (!complete || !job.endless() || result.stop() != TunnelShape.Stop.LENGTH || !plugin.isEnabled()) {
                return;
            }
            // Ohne Ende (Wunsch 2026-11-16): Der Spieler ist bis ans Ende mitgegangen – das nächste Stück im selben
            // Rahmen, Licht und Pfeiler laufen im selben Abstand weiter, kein Eingangsbogen.
            Job next = new Job(job.request(), job.order(), job.kind(), job.frame(), job.half(), job.height(),
                    FOLLOW_SEGMENT, result.last() + 1, job.anchor() != TunnelShape.NO_ANCHOR ? job.anchor()
                            : result.first(), false, job.style(), job.chest(), job.free(), true, true, job.ownIds(),
                    true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (service.busy()) {
                    say(job.request(), "Ich mache hier eine Pause – sag „bau weiter“, wenn's weitergehen soll.");
                    return;
                }
                LOG.info(() -> "tunnel | nächstes_stück | besitzer=" + job.request().ownerName() + " ab="
                        + next.start());
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
            case BRIDGE -> definite ? "eine Brücke" : "keine Brücke";
        };
    }

    private static String needs(TunnelOrder.Kind kind) {
        return switch (kind) {
            case TUNNEL -> "Bausteine (z. B. Steinziegel) und Laternen, gern auch passende Treppen für den Bogen";
            case STAIRS_UP -> "Bausteine, Treppenstufen und Laternen";
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
        String stop = result.stop() == TunnelShape.Stop.LENGTH || result.stop() == TunnelShape.Stop.SURFACE ? ""
                : " " + stopText(job, result, result.slices());
        String head = switch (job.kind()) {
            case TUNNEL -> job.resumed() ? "Ich grabe den Tunnel weiter" + look + "."
                    : job.follow() ? "Los geht's: Ich grabe vor dir her" + look + " – geh einfach mit, „stopp“ beendet."
                            : "Ich grabe dir einen Tunnel, " + result.slices() + " Blöcke"
                                    + (job.order().fast() ? ", im Schnellgang" : "") + look + ".";
            case STAIRS_UP -> "Ich baue " + (job.resumed() ? "die Treppe weiter" : "dir eine Treppe nach oben") + " – "
                    + Math.max(0, result.slices() - (job.start() == 0 ? 1 : 0)) + " Stufen"
                    + (result.stop() == TunnelShape.Stop.SURFACE ? " bis zur Oberfläche" : "") + look + ".";
            case BRIDGE -> job.resumed() ? "Ich baue die Brücke weiter" + look + "."
                    : job.follow() ? "Ich baue die Brücke vor dir her" + look + " – geh einfach mit"
                            + (job.endless() ? ", sie hört erst auf, wenn du „stopp“ sagst." : ".")
                            : "Ich baue dir eine Brücke, " + result.slices() + " Blöcke"
                                    + (job.order().fast() ? ", im Schnellgang" : "") + look + ".";
        };
        if (job.resumed() && job.follow()) {
            head += " Geh einfach mit.";
        }
        return head + stop + where;
    }

    /** Warum der Bau früher endet als gewünscht. */
    private String stopText(Job job, TunnelShape.Result result, int n) {
        String at = result.stopAt() == null ? "" : " bei " + at(job.frame().blockAt(result.stopAt()).getLocation());
        String what = result.stopId() == null ? "" : TunnelStyle.german(result.stopId());
        return switch (result.stop()) {
            case BUILT -> "Nach " + n + " Blöcken steht etwas Gebautes (" + what + at + ") – davor höre ich auf.";
            case FORBIDDEN -> "Nach " + n + " Blöcken kommt Grundgestein – weiter geht es nicht.";
            case THROUGH -> "Nach " + n + " Blöcken bin ich durch – dort geht es ins Freie.";
            case WATER -> job.kind() == TunnelOrder.Kind.BRIDGE
                    ? "Nach " + n + " Blöcken steht das Wasser höher als der Weg – dort höre ich auf."
                    : "Nach " + n + " Blöcken käme Wasser von oben – dort höre ich auf.";
            case HILL -> "Nach " + n + " Blöcken kommt ein Berg – dort höre ich auf. Für weiter: „grab einen Tunnel“.";
            case NO_SURFACE -> "Bis zur Oberfläche reichen " + n + " Stufen nicht.";
            case LENGTH, SURFACE, ALREADY_UP -> "";
        };
    }

    private String refusal(Job job, TunnelShape.Result result) {
        String at = result.stopAt() == null ? "" : " bei " + at(job.frame().blockAt(result.stopAt()).getLocation());
        String what = result.stopId() == null ? "" : TunnelStyle.german(result.stopId());
        return switch (result.stop()) {
            case BUILT -> "Direkt vor dir steht etwas Gebautes (" + what + at + ") – da baue ich nicht.";
            case FORBIDDEN -> "Da ist Grundgestein oder der Rand der Welt im Weg.";
            case HILL -> "Direkt vor dir ist ein Berg – für einen Weg hindurch sag „grab einen Tunnel“.";
            case WATER -> job.kind() == TunnelOrder.Kind.STAIRS_UP
                    ? "Oben ist Wasser – da mache ich keinen Ausgang. Versuch es in eine andere Richtung."
                    : job.kind() == TunnelOrder.Kind.BRIDGE
                            ? "Hier steht das Wasser höher als der Weg – stell dich ans Ufer, auf Wasserhöhe "
                                    + "oder darüber."
                            : "Da kommt gleich Wasser von oben – dort grabe ich nicht.";
            case ALREADY_UP -> "Du stehst doch schon oben.";
            case NO_SURFACE -> "Bis zur Oberfläche komme ich nicht – mehr als " + settings.maxExtent()
                    + " Stufen. Grab erst ein Stück höher.";
            case THROUGH, LENGTH, SURFACE -> job.kind() == TunnelOrder.Kind.TUNNEL
                    ? "Hier ist nichts zum Graben – stell dich vor Fels oder Erde."
                    : "Hier kann ich nichts bauen.";
        };
    }

    private void say(BuildService.Request r, String text) {
        Location at = npc.get() != null ? npc.get().getLocation() : r.feet();
        Texts.say(npc.name(), at, r.sender(), text);
    }

    private static String at(Location l) {
        return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    // ---------------------------------------------------------------- Hilfen

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
        return new TunnelStyle(TunnelStyle.german(f.wall()), wall, floor, rib,
                f.arch() != null ? BlockSpec.of(f.arch()) : null,
                f.step() != null ? BlockSpec.of(f.step()) : base.step(), light,
                f.chain() != null ? BlockSpec.of(f.chain()) : base.chain(),
                f.railing() != null ? BlockSpec.of(f.railing()) : base.railing());
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

    /** Was TunnelScan sieht: Block an der lokalen Stelle mit lokalen Zuständen (facing=back = vorwärts). */
    private static TunnelScan.Look look(SiteFrame frame) {
        Orientation o = frame.orientation();
        return p -> {
            Block b = frame.blockAt(p);
            String text = b.getBlockData().getAsString();
            try {
                return o.toLocal(BlockSpec.parseTrusted(text));
            } catch (PlanException | RuntimeException e) {
                // Zustände, die der Leser nicht kennt: ohne Zustände genügt für Wand, Boden und Licht.
                return BlockSpec.of(b.getType().getKey().getKey());
            }
        };
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
