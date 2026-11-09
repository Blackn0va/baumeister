package com.blackn0va.baumeister.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.Test;

/** Bildschirm ohne Bukkit: Schrift, Inhalte, Bilder/GIF, Farben, Wand, Satzerkennung. */
class ScreenPartsTest {

    // ---------------------------------------------------------------- Schrift

    @Test
    void fontNormalizesAndMeasures() {
        assertEquals("STRASSE?", PixelFont.normalize("Straße~"));
        assertEquals(5, PixelFont.width("1", 1));
        assertEquals(11, PixelFont.width("12", 1), "5 + 1 Abstand + 5");
        assertEquals(22, PixelFont.width("12", 2));
        int[] rgb = new int[20 * 10];
        PixelFont.draw(rgb, 20, 10, "1", 0, 0, 1, 0xFFFFFF);
        int lit = 0;
        for (int v : rgb) {
            lit += v == 0xFFFFFF ? 1 : 0;
        }
        assertEquals(10, lit, "die 1 hat 1+2+1+1+1+1+3 Punkte");
        PixelFont.draw(rgb, 20, 10, "88888888", -30, -3, 1, 0x123456);
    }

    // ---------------------------------------------------------------- Inhalte

    @Test
    void frameRateStaysWithinTheBandwidthBudget() {
        assertEquals(5, ScreenContent.fps(ScreenContent.Kind.ANIMATION, 6), "3 × 2 Karten");
        assertEquals(1, ScreenContent.fps(ScreenContent.Kind.ANIMATION, 24), "6 × 4 Karten: 30/24 → 1");
        assertEquals(1, ScreenContent.fps(ScreenContent.Kind.UHR, 1));
        for (int maps = 1; maps <= 48; maps++) {
            assertTrue(ScreenContent.fps(ScreenContent.Kind.BILD, maps) * maps <= Math.max(maps,
                    ScreenContent.MAP_FRAMES_PER_SECOND), "Budget bei " + maps);
        }
    }

    @Test
    void clockTickerAndAnimationDrawSomething() {
        int w = 384;
        int h = 256;
        int[] clock = ScreenContent.clock(w, h, "12:34", "SA 07.11.");
        assertEquals(w * h, clock.length);
        assertTrue(count(clock, ScreenContent.YELLOW) > 500, "große Ziffern");
        assertTrue(count(clock, ScreenContent.CYAN) > 20, "Datum darunter");
        int scale = ScreenContent.tickerScale(h);
        int[] a = ScreenContent.ticker(w, h, "HALLO WELT", 100, scale);
        int[] b = ScreenContent.ticker(w, h, "HALLO WELT", 110, scale);
        assertNotEquals(java.util.Arrays.hashCode(a), java.util.Arrays.hashCode(b), "Laufschrift läuft");
        int period = ScreenContent.tickerPeriod(w, "HALLO WELT", scale);
        assertArrayEquals(a, ScreenContent.ticker(w, h, "HALLO WELT", 100 + period, scale), "wiederholt sich");
        int[] f1 = ScreenContent.animation(w, h, 1, "BAUMEISTERLAND");
        int[] f2 = ScreenContent.animation(w, h, 2, "BAUMEISTERLAND");
        assertArrayEquals(f1, ScreenContent.animation(w, h, 1, "BAUMEISTERLAND"), "gleiche Zeit, gleiches Bild");
        assertNotEquals(java.util.Arrays.hashCode(f1), java.util.Arrays.hashCode(f2), "bewegt sich");
    }

    private static int count(int[] rgb, int color) {
        int n = 0;
        for (int v : rgb) {
            n += v == color ? 1 : 0;
        }
        return n;
    }

    // ---------------------------------------------------------------- Bilder und GIF

    @Test
    void animatedGifIsComposedFittedAndThrottled() throws IOException {
        byte[] gif = twoFrameGif();
        ScreenImages.Frames f = ScreenImages.load(new ByteArrayInputStream(gif), "probe.gif", 256, 128);
        assertEquals(2, f.rgb().size());
        assertEquals(List.of(200, 300), f.delays(), "50 ms auf 200 hochgesetzt, 300 bleibt");
        int[] first = f.rgb().get(0);
        // 40 × 20 GIF in 256 × 128: Faktor 6,4 → 256 × 128 voll (Seitenverhältnis 2:1 passt genau).
        assertEquals(0xFF0000, first[64 * 256 + 128] & 0xFFFFFF, "erstes Bild rot");
        assertEquals(0x0000FF, f.rgb().get(1)[64 * 256 + 128] & 0xFFFFFF, "zweites Bild blau");
    }

    @Test
    void imagesAreLetterboxedAndNamesAreChecked() throws IOException {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                img.setRGB(x, y, 0x00FF00);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        // Dateinamen nur ASCII (ScreenImages.safeName) – deshalb hier ohne Umlaut. umlaut-ok
        ScreenImages.Frames f = ScreenImages.load(new ByteArrayInputStream(out.toByteArray()), "gruen.png", 256, 128); // umlaut-ok
        int[] rgb = f.rgb().get(0);
        assertEquals(0x00FF00, rgb[64 * 256 + 128], "Mitte grün");
        assertEquals(0x000000, rgb[64 * 256 + 5], "links schwarzer Rand (quadratisch in breit)");
        assertFalse(ScreenImages.safeName("../geheim.gif"));
        assertFalse(ScreenImages.safeName("a b.gif"));
        assertFalse(ScreenImages.safeName("x.exe"));
        assertTrue(ScreenImages.safeName("Katze_2.GIF"));
        assertThrows(IOException.class, () -> ScreenImages.load(new ByteArrayInputStream(new byte[] {1, 2}),
                "kaputt.png", 128, 128));
    }

    /** Zwei 40 × 20-Bilder: rot (50 ms), blau (300 ms). */
    private static byte[] twoFrameGif() throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
            w.setOutput(ios);
            w.prepareWriteSequence(null);
            int[][] colors = {{0xFF0000, 5}, {0x0000FF, 30}};
            for (int[] c : colors) {
                BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 20; y++) {
                    for (int x = 0; x < 40; x++) {
                        img.setRGB(x, y, c[0]);
                    }
                }
                ImageWriteParam param = w.getDefaultWriteParam();
                IIOMetadata meta = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param);
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode gce = new IIOMetadataNode("GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", Integer.toString(c[1]));
                gce.setAttribute("transparentColorIndex", "0");
                root.appendChild(gce);
                meta.setFromTree(format, root);
                w.writeToSequence(new IIOImage(img, null, meta), param);
            }
            w.endWriteSequence();
        } finally {
            w.dispose();
        }
        return bytes.toByteArray();
    }

    // ---------------------------------------------------------------- Farben und Kacheln

    @Test
    void paletteCachesAndTilesCut() {
        AtomicInteger calls = new AtomicInteger();
        ScreenPalette p = new ScreenPalette(rgb -> {
            calls.incrementAndGet();
            return (rgb >> 16) & 0x7F;
        });
        byte a = p.index(0xFF0000);
        byte b = p.index(0xFC0101);
        assertEquals(a, b, "gleiches 5-Bit-Fach");
        assertEquals(1, calls.get(), "einmal gerechnet, dann gemerkt");
        byte[] full = new byte[256 * 128];
        full[5 * 256 + 128 + 3] = 42;
        byte[] tile = ScreenPalette.tile(full, 256, 1, 0);
        assertEquals(42, tile[5 * 128 + 3]);
    }

    // ---------------------------------------------------------------- Wand

    @Test
    void wallStandsInFrontFacingThePlayerTopLeftFirst() {
        // Spieler bei (0, 64, 0) schaut nach Norden (−z): Rahmen 3 vor ihm (z = −3), Wand dahinter (z = −4).
        List<ScreenWall.Slot> s = ScreenWall.slots(0, 64, 0, ScreenWall.Facing.NORTH, 3, 2);
        assertEquals(6, s.size());
        ScreenWall.Slot topLeft = s.get(0);
        assertEquals(0, topLeft.tx());
        assertEquals(0, topLeft.ty());
        assertEquals(-3, topLeft.frameZ());
        assertEquals(-4, topLeft.wallZ());
        assertEquals(-1, topLeft.frameX(), "links aus Sicht des Spielers = Westen");
        assertEquals(66, topLeft.frameY(), "obere Reihe");
        ScreenWall.Slot bottomRight = s.get(5);
        assertEquals(1, bottomRight.frameX());
        assertEquals(65, bottomRight.frameY(), "untere Reihe auf Fußhöhe + 1");
        assertEquals(ScreenWall.Facing.NORTH, ScreenWall.Facing.ofYaw(180));
        assertEquals(ScreenWall.Facing.EAST, ScreenWall.Facing.ofYaw(-90));
        assertEquals(ScreenWall.Facing.SOUTH, ScreenWall.Facing.ofYaw(10));
        assertEquals(8 * 6, ScreenWall.slots(0, 64, 0, ScreenWall.Facing.EAST, 99, 99).size(), "gekappt");
    }

    @Test
    void secondScreenOnTheSamePlaceOverlaps() {
        // Testserver 2026-11-07: an derselben Stelle wurde ein zweiter Bildschirm gebaut.
        List<ScreenWall.Slot> a = ScreenWall.slots(0, 64, 0, ScreenWall.Facing.NORTH, 3, 2);
        assertTrue(ScreenWall.overlaps(a, ScreenWall.slots(0, 64, 0, ScreenWall.Facing.NORTH, 3, 2)));
        assertTrue(ScreenWall.overlaps(a, ScreenWall.slots(2, 64, 0, ScreenWall.Facing.NORTH, 3, 2)), "teilweise");
        assertFalse(ScreenWall.overlaps(a, ScreenWall.slots(10, 64, 0, ScreenWall.Facing.NORTH, 3, 2)));
        assertFalse(ScreenWall.overlaps(a, ScreenWall.slots(0, 70, 0, ScreenWall.Facing.NORTH, 3, 2)), "darüber");
    }

    // ---------------------------------------------------------------- Sätze

    @Test
    void requestsAreRecognised() {
        assertEquals(ScreenContent.Kind.ANIMATION, ScreenRequest.parse("bau mir ein Display").kind());
        assertEquals(ScreenContent.Kind.ANIMATION, ScreenRequest.parse("bei mir ein Display.").kind(),
                "so kam „bau mir ein Display“ aus der Spracherkennung (Log 2026-11-07)");
        assertEquals(ScreenContent.Kind.ANIMATION, ScreenRequest.parse("Bewegt Bilder.").kind(), "kurze Nachfrage");
        ScreenRequest.Request big = ScreenRequest.parse("mach mir einen großen Fernseher mit Uhr");
        assertEquals(ScreenContent.Kind.UHR, big.kind());
        assertEquals(5, big.width());
        ScreenRequest.Request t = ScreenRequest.parse("bau einen Bildschirm mit Laufschrift Willkommen im Baumeisterland");
        assertEquals(ScreenContent.Kind.LAUFSCHRIFT, t.kind());
        assertEquals("Willkommen im Baumeisterland", t.text());
        ScreenRequest.Request g = ScreenRequest.parse("zeig katze.gif auf einem 4x3 Bildschirm");
        assertEquals(ScreenContent.Kind.BILD, g.kind());
        assertEquals("katze.gif", g.file());
        assertEquals(4, g.width());
        assertEquals(3, g.height());
    }

    @Test
    void redstoneDisplaysAndSmallTalkAreNotScreens() {
        assertNull(ScreenRequest.parse("bau mir ein Redstone Display"), "bleibt die 7-Segment-Maschine");
        assertNull(ScreenRequest.parse("bau eine 7-Segment-Anzeige"));
        assertNull(ScreenRequest.parse("bau eine Zahlenanzeige"));
        assertNull(ScreenRequest.parse("das Display ist kaputt"));
        assertNull(ScreenRequest.parse("ich mag Filme"));
        assertNull(ScreenRequest.parse("bau mir ein Haus"));
    }
}
