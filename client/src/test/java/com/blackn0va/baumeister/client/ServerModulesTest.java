package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** modul_json aus baumeister:module (Vertrag v012, AK-39): gültig, Grenzen, kaputt (2026-11-22). */
class ServerModulesTest {

    private static final String VALID = """
            {"version":1,"modules":[
             {"id":"stadtleben","name":"Stadtleben","description":"KI-Bewohner in der Stadt","on":true,
              "state":"wartet","reason":"KI nicht erreichbar","needs":["ki"]},
             {"id":"bau-ki","name":"Bau-KI","description":"Pläne vom LLM","on":true,"state":"aktiv"}]}
            """;

    @AfterEach
    void reset() {
        ServerModules.clear();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String module(String fields) {
        return "{\"version\":1,\"modules\":[{" + fields + "}]}";
    }

    private static String reason(String json) {
        return ServerModules.parse(utf8(json)).reason();
    }

    @Test
    void validMessageIsReadWithAllFields() {
        ServerModules.Result r = ServerModules.parse(utf8(VALID));
        assertTrue(r.ok(), r.reason());
        assertEquals(2, r.modules().size());
        ServerModules.Module m = r.modules().get(0);
        assertEquals(new ServerModules.Module("stadtleben", "Stadtleben", "KI-Bewohner in der Stadt", true, "wartet",
                "KI nicht erreichbar", List.of("ki")), m);
        ServerModules.Module b = r.modules().get(1);
        assertEquals("", b.reason(), "aktives Modul ohne Grund");
        assertEquals(List.of(), b.needs());
    }

    @Test
    void emptyModuleListIsValid() {
        assertTrue(ServerModules.parse(utf8("{\"version\":1,\"modules\":[]}")).ok());
    }

    @Test
    void limitsFromTheContractHoldExactly() {
        String id32 = "a".repeat(32);
        String name40 = "Ä".repeat(40);  // Zeichen, nicht Byte: 40 Umlaute sind 80 Byte und erlaubt
        String desc160 = "ü".repeat(160);
        String reason120 = "ß".repeat(120);
        String ok = module("\"id\":\"" + id32 + "\",\"name\":\"" + name40 + "\",\"description\":\"" + desc160
                + "\",\"on\":false,\"state\":\"aus\",\"reason\":\"" + reason120 + "\"");
        assertTrue(ServerModules.parse(utf8(ok)).ok(), reason(ok));
        assertEquals("id:0", reason(ok.replace(id32, id32 + "a")));
        assertEquals("name:" + id32, reason(ok.replace(name40, name40 + "Ä")));
        assertEquals("description:" + id32, reason(ok.replace(desc160, desc160 + "ü")));
        assertEquals("reason:" + id32, reason(ok.replace(reason120, reason120 + "ß")));
    }

    @Test
    void idMustMatchTheContractPattern() {
        for (String bad : new String[] {"", "Stadt", "stadt leben", "stadt_leben", "städte"}) {
            String json = module("\"id\":\"" + bad + "\",\"name\":\"X\",\"on\":true,\"state\":\"aktiv\"");
            assertEquals("id:0", reason(json), bad);
        }
    }

    @Test
    void brokenMessagesAreRejectedWithReason() {
        assertEquals("leer", ServerModules.parse(new byte[0]).reason());
        assertEquals("leer", ServerModules.parse(null).reason());
        assertEquals("kein_json", reason("{ kaputt"));
        assertEquals("kein_json", reason("{version:1,modules:[]}"), "nachsichtiges JSON ist kein modul_json");
        assertEquals("kein_json", reason("{\"version\":1,\"modules\":[]} {}"), "Daten nach dem JSON");
        assertEquals("kein_objekt", reason("[1,2]"));
        assertEquals("version", reason("{\"version\":2,\"modules\":[]}"));
        assertEquals("version", reason("{\"version\":1.5,\"modules\":[]}"));
        assertEquals("version", reason("{\"modules\":[]}"));
        assertEquals("modules_fehlt", reason("{\"version\":1}"));
        assertEquals("unbekanntes_feld:spieler", reason("{\"version\":1,\"modules\":[],\"spieler\":\"x\"}"));
        assertEquals("unbekanntes_feld:0.adresse",
                reason(module("\"id\":\"a\",\"name\":\"A\",\"on\":true,\"state\":\"aktiv\",\"adresse\":\"x\"")));
        assertEquals("state:a", reason(module("\"id\":\"a\",\"name\":\"A\",\"on\":true,\"state\":\"läuft\"")));
        assertEquals("on:a", reason(module("\"id\":\"a\",\"name\":\"A\",\"on\":\"ja\",\"state\":\"aktiv\"")));
        assertEquals("name:a", reason(module("\"id\":\"a\",\"name\":\" \",\"on\":true,\"state\":\"aktiv\"")));
        assertEquals("needs:a", reason(module(
                "\"id\":\"a\",\"name\":\"A\",\"on\":true,\"state\":\"aktiv\",\"needs\":[\"KI\"]")));
        assertEquals("id_doppelt:a", reason("{\"version\":1,\"modules\":[{\"id\":\"a\",\"name\":\"A\",\"on\":true,"
                + "\"state\":\"aktiv\"},{\"id\":\"a\",\"name\":\"B\",\"on\":true,\"state\":\"aktiv\"}]}"));
        assertEquals("modul_kein_objekt:0", reason("{\"version\":1,\"modules\":[\"a\"]}"));
    }

    @Test
    void formattingAndControlCharactersAreRejected() {
        assertEquals("reason:a", reason(module(
                "\"id\":\"a\",\"name\":\"A\",\"on\":true,\"state\":\"fehler\",\"reason\":\"§kgeheim\"")));
        assertEquals("name:a", reason(module("\"id\":\"a\",\"name\":\"A\\nB\",\"on\":true,\"state\":\"aktiv\"")));
    }

    @Test
    void tooLargeOrTooManyIsRejected() {
        byte[] big = new byte[ServerModules.MAX_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        assertEquals("zu_gross", ServerModules.parse(big).reason());
        StringBuilder sb = new StringBuilder("{\"version\":1,\"modules\":[");
        for (int i = 0; i <= ServerModules.MAX_MODULES; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":\"m").append(i)
                    .append("\",\"name\":\"M\",\"on\":true,\"state\":\"aktiv\"}");
        }
        assertEquals("zu_viele_module", reason(sb.append("]}").toString()));
    }

    @Test
    void invalidUtf8IsRejectedNotReplaced() {
        byte[] b = utf8("{\"version\":1,\"modules\":[{\"id\":\"a\",\"name\":\"AX\",\"on\":true,\"state\":\"aktiv\"}]}");
        int x = new String(b, StandardCharsets.UTF_8).indexOf("AX") + 1;
        b[x] = (byte) 0xC3;  // angefangenes Zwei-Byte-Zeichen, danach ein Anführungszeichen
        assertEquals("kein_utf8", ServerModules.parse(b).reason());
    }

    @Test
    void brokenMessageKeepsThePreviousState() {
        assertNull(ServerModules.current(), "vor der ersten Meldung: nichts (Bildschirm sagt „meldet keine Module“)");
        assertTrue(ServerModules.accept(utf8(VALID)).ok());
        assertEquals(2, ServerModules.current().size());
        assertFalse(ServerModules.accept(utf8("{ kaputt")).ok());
        assertEquals(2, ServerModules.current().size(), "kaputt verworfen, alter Stand bleibt");
        ServerModules.clear();
        assertNull(ServerModules.current(), "Verlassen/Beitritt leert");
    }

    @Test
    void payloadReadsRawBytesAndSkipsOversizedOnes() {
        byte[] json = utf8(VALID);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(json));
        ModulePayload p = ModulePayload.read(buf);
        assertEquals(0, buf.readableBytes(), "ganzer Puffer gelesen (ohne Längenpräfix wie sendPluginMessage)");
        assertTrue(ServerModules.parse(p.data()).ok());
        FriendlyByteBuf big = new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[ServerModules.MAX_BYTES + 1]));
        ModulePayload q = ModulePayload.read(big);
        assertNull(q.data());
        assertEquals(ServerModules.MAX_BYTES + 1, q.size());
        assertEquals(0, big.readableBytes(), "übersprungen statt Rest liegen gelassen (sonst trennt Minecraft)");
    }
}
