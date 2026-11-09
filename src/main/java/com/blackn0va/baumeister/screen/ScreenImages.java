package com.blackn0va.baumeister.screen;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.NodeList;

/**
 * Bilder und GIF-Animationen für den Bildschirm laden – aus dem Plugin-Ordner (bildschirm/), nie aus dem Netz.
 *
 * GIF: Jedes Einzelbild kann nur ein Ausschnitt sein und hat eine Entsorgungsart (disposalMethod); ImageIO liefert die
 * Ausschnitte roh. Hier wird wie im Browser zusammengesetzt (none/doNotDispose: stehen lassen, restoreToBackground:
 * Ausschnitt löschen, restoreToPrevious: vorigen Stand zurück) – sonst flackern viele GIFs oder zeigen nur Fetzen.
 *
 * Grenzen gegen Last und Missbrauch (ASVS V12.1, OWASP A04): Dateiname nur [A-Za-z0-9_-] mit Endung gif/png/jpg (kein
 * Pfad, kein „..“), höchstens MAX_BYTES Datei, MAX_FRAMES Bilder, Kantenlänge MAX_EDGE; Bildfolge ab 200 ms je Bild
 * (5 Bilder/s, ScreenContent.FPS_MAX).
 *
 * Threads: zustandslos, darf in einem Hintergrund-Faden laufen (Dekodieren dauert). Fehler: IOException mit deutschem
 * Satz.
 */
public final class ScreenImages {

    public static final long MAX_BYTES = 8L * 1024 * 1024;
    public static final int MAX_FRAMES = 200;
    static final int MAX_EDGE = 2048;
    static final int MIN_DELAY_MS = 200;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,48}\\.(?i:gif|png|jpg|jpeg)");

    /** Einzelbilder (RGB, w × h) mit Anzeigedauer in ms. */
    public record Frames(List<int[]> rgb, List<Integer> delays) {
    }

    private ScreenImages() {
    }

    /** Erlaubter Dateiname? */
    public static boolean safeName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Laden und auf w × h einpassen (Seitenverhältnis bleibt, Rand schwarz). */
    public static Frames load(InputStream in, String name, int w, int h) throws IOException {
        if (!safeName(name)) {
            throw new IOException("Ungültiger Dateiname: nur Buchstaben, Ziffern, _ und - mit .gif/.png/.jpg");
        }
        byte[] data = in.readNBytes((int) MAX_BYTES + 1);
        if (data.length > MAX_BYTES) {
            throw new IOException("Datei größer als " + MAX_BYTES / 1024 / 1024 + " MB");
        }
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".gif")) {
            return gif(data, w, h);
        }
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
        if (img == null) {
            throw new IOException("Kein lesbares Bild");
        }
        check(img.getWidth(), img.getHeight());
        return new Frames(List.of(fit(img, w, h)), List.of(1000));
    }

    private static Frames gif(byte[] data, int w, int h) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
            Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("gif");
            if (!it.hasNext()) {
                throw new IOException("Kein GIF-Leser vorhanden");
            }
            ImageReader r = it.next();
            try {
                r.setInput(iis, false);
                int count = r.getNumImages(true);
                if (count <= 0) {
                    throw new IOException("GIF ohne Bilder");
                }
                if (count > MAX_FRAMES) {
                    throw new IOException("GIF mit mehr als " + MAX_FRAMES + " Bildern");
                }
                int[] size = logicalSize(r);
                check(size[0], size[1]);
                BufferedImage canvas = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
                List<int[]> out = new ArrayList<>();
                List<Integer> delays = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    BufferedImage part = r.read(i);
                    IIOMetadataNode gce = node(r.getImageMetadata(i), "GraphicControlExtension");
                    IIOMetadataNode desc = node(r.getImageMetadata(i), "ImageDescriptor");
                    int x = desc == null ? 0 : Integer.parseInt(desc.getAttribute("imageLeftPosition"));
                    int y = desc == null ? 0 : Integer.parseInt(desc.getAttribute("imageTopPosition"));
                    String disposal = gce == null ? "none" : gce.getAttribute("disposalMethod");
                    int delay = gce == null ? 100 : Integer.parseInt(gce.getAttribute("delayTime")) * 10;
                    BufferedImage before = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
                    java.awt.Graphics2D g = canvas.createGraphics();
                    g.drawImage(part, x, y, null);
                    g.dispose();
                    out.add(fit(canvas, w, h));
                    delays.add(Math.max(MIN_DELAY_MS, delay));
                    if ("restoreToBackgroundColor".equals(disposal)) {
                        java.awt.Graphics2D c = canvas.createGraphics();
                        c.setComposite(java.awt.AlphaComposite.Clear);
                        c.fillRect(x, y, part.getWidth(), part.getHeight());
                        c.dispose();
                    } else if (before != null) {
                        canvas = before;
                    }
                }
                return new Frames(List.copyOf(out), List.copyOf(delays));
            } finally {
                r.dispose();
            }
        }
    }

    private static int[] logicalSize(ImageReader r) throws IOException {
        IIOMetadata stream = r.getStreamMetadata();
        if (stream != null) {
            IIOMetadataNode lsd = node(stream, "LogicalScreenDescriptor", "javax_imageio_gif_stream_1.0");
            if (lsd != null) {
                int w = Integer.parseInt(lsd.getAttribute("logicalScreenWidth"));
                int h = Integer.parseInt(lsd.getAttribute("logicalScreenHeight"));
                if (w > 0 && h > 0) {
                    return new int[] {w, h};
                }
            }
        }
        return new int[] {r.getWidth(0), r.getHeight(0)};
    }

    private static IIOMetadataNode node(IIOMetadata meta, String name) {
        return node(meta, name, "javax_imageio_gif_image_1.0");
    }

    private static IIOMetadataNode node(IIOMetadata meta, String name, String format) {
        if (meta == null) {
            return null;
        }
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
        NodeList list = root.getElementsByTagName(name);
        return list.getLength() == 0 ? null : (IIOMetadataNode) list.item(0);
    }

    private static void check(int w, int h) throws IOException {
        if (w <= 0 || h <= 0 || w > MAX_EDGE || h > MAX_EDGE) {
            throw new IOException("Bild zu groß (höchstens " + MAX_EDGE + " Punkte je Seite)");
        }
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = c.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return c;
    }

    /** Einpassen: größte Skalierung, die ganz hineinpasst, mittig, Rand schwarz; Durchsichtiges wird schwarz. */
    static int[] fit(BufferedImage img, int w, int h) {
        double s = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
        int sw = Math.max(1, (int) Math.round(img.getWidth() * s));
        int sh = Math.max(1, (int) Math.round(img.getHeight() * s));
        int ox = (w - sw) / 2;
        int oy = (h - sh) / 2;
        int[] rgb = new int[w * h];
        for (int y = 0; y < sh; y++) {
            int iy = Math.min(img.getHeight() - 1, (int) (y / s));
            for (int x = 0; x < sw; x++) {
                int ix = Math.min(img.getWidth() - 1, (int) (x / s));
                int argb = img.getRGB(ix, iy);
                int a = argb >>> 24;
                int c = argb & 0xFFFFFF;
                if (a < 255) {
                    // Vor Schwarz mischen: halbdurchsichtige Kanten werden dunkler statt grell.
                    int r = (c >> 16 & 0xFF) * a / 255;
                    int g = (c >> 8 & 0xFF) * a / 255;
                    int b = (c & 0xFF) * a / 255;
                    c = r << 16 | g << 8 | b;
                }
                rgb[(oy + y) * w + ox + x] = c;
            }
        }
        return rgb;
    }
}
