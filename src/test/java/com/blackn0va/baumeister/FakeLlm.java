package com.blackn0va.baumeister;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Messprobe (2026-10-28): Ersatz für llama-server auf 127.0.0.1, der immer denselben Plan liefert. So läuft ein
 * Auftrag auf dem lokalen Testserver durch Parser, Compiler und Bau, ohne dass ein Modell hier rechnet – geprüft wird
 * der Weg Plan → Welt, nicht die Planqualität eines LLM.
 *
 *   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.FakeLlm &lt;port&gt; &lt;plan.json&gt;
 */
public final class FakeLlm {

    private FakeLlm() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        String plan = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/health", ex -> send(ex, "{\"status\":\"ok\"}"));
        server.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "assistant");
            msg.addProperty("content", plan);
            JsonObject choice = new JsonObject();
            choice.add("message", msg);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject root = new JsonObject();
            root.add("choices", choices);
            System.out.println("anfrage beantwortet");
            send(ex, root.toString());
        });
        server.start();
        System.out.println("FakeLlm auf 127.0.0.1:" + port);
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }
}
