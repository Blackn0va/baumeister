package com.blackn0va.baumeister.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Postleitzahl statt Ortsname (Nutzer 2026-10-30). Antwort wie Nominatim sie für 71735 liefert (gekürzt). */
class PostcodeSearchTest {

    private static final String EBERDINGEN = "[{\"osm_type\":\"relation\",\"lat\":\"48.8834912\",\"lon\":\"8.9617881\","
            + "\"class\":\"boundary\",\"type\":\"postal_code\",\"addresstype\":\"postcode\",\"name\":\"71735\","
            + "\"display_name\":\"71735, Eberdingen, VVG der Stadt Vaihingen an der Enz, Landkreis Ludwigsburg, "
            + "Baden-Württemberg, Deutschland\",\"address\":{\"postcode\":\"71735\",\"village\":\"Eberdingen\","
            + "\"country_code\":\"de\"},\"boundingbox\":[\"48.8545376\",\"48.9134347\",\"8.9066750\",\"9.0319862\"]}]";

    @Test
    void germanPostcodeUsesTheStructuredSearchInGermany() {
        String url = OsmClient.searchUrl("https://n/search", "71735", "de");
        assertTrue(url.contains("&postalcode=71735&countrycodes=de"), url);
        assertTrue(!url.contains("&q="), url);
        assertTrue(OsmClient.searchUrl("https://n/search", "PLZ 71735", "de").contains("&postalcode=71735"));
    }

    @Test
    void otherCountriesByCodeAndFourDigitsWithoutRestriction() {
        assertTrue(OsmClient.searchUrl("https://n/search", "8001 ch", "de").endsWith("&postalcode=8001&countrycodes=ch"));
        assertTrue(OsmClient.searchUrl("https://n/search", "1010", "de").endsWith("&postalcode=1010"));
        assertTrue(OsmClient.searchUrl("https://n/search", "71735", "").endsWith("&postalcode=71735"));
    }

    @Test
    void namesStayFreeText() {
        assertTrue(OsmClient.searchUrl("https://n/search", "Bad Kreuznach", "de").endsWith("&q=Bad+Kreuznach"));
        assertTrue(OsmClient.searchUrl("https://n/search", "Route 66", "de").contains("&q="));
    }

    @Test
    void postcodeAreaGetsAShortNameAndItsWholeExtent() throws Exception {
        AtomicReference<String> asked = new AtomicReference<>();
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/search", ex -> {
            asked.set(ex.getRequestURI().getRawQuery());
            byte[] body = EBERDINGEN.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        });
        s.start();
        try {
            OsmClient osm = new OsmClient(List.of("http://127.0.0.1:1/unused"),
                    "http://127.0.0.1:" + s.getAddress().getPort() + "/search", "de");
            OsmClient.Place p = osm.resolve("71735").get(10, TimeUnit.SECONDS);
            assertTrue(asked.get().contains("postalcode=71735&countrycodes=de"), asked.get());
            assertEquals("71735 Eberdingen", p.name());
            assertEquals(48.8834912, p.lat(), 1e-9);
            assertTrue(p.extent() > 3000 && p.extent() < 7000, "ganze PLZ-Fläche: " + p.extent());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void ordinaryPlaceKeepsItsFullName() {
        JsonObject o = JsonParser.parseString("{\"display_name\":\"Bad Kreuznach, Rheinland-Pfalz\",\"addresstype\":"
                + "\"town\",\"address\":{\"town\":\"Bad Kreuznach\",\"postcode\":\"55543\"}}").getAsJsonObject();
        assertEquals("Bad Kreuznach, Rheinland-Pfalz", OsmClient.placeName(o, "Bad Kreuznach"));
    }
}
