package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** /kbau stop während der Planung bricht die Anfrage an den Host ab, nicht nur ihr Ergebnis (2026-11-14). */
class PlanRequestTest {

    /**
     * Host-Ersatz: nimmt eine Verbindung an, liest die Anfrage und antwortet nie – wie der Host, der minutenlang
     * rechnet. closed zählt herunter, sobald die Gegenseite die Verbindung schließt (read liefert -1 oder wirft).
     */
    private static Thread silentHost(ServerSocket server, CountDownLatch received, CountDownLatch closed) {
        Thread t = new Thread(() -> {
            try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                byte[] buf = new byte[8192];
                boolean seen = false;
                while (true) {
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    if (!seen) {
                        seen = true;
                        received.countDown();
                    }
                }
            } catch (Exception e) {
                // Zurückgesetzt statt geschlossen: zählt genauso als „Gegenseite weg“.
            }
            closed.countDown();
        }, "stummer-host");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static CompletableFuture<HttpResponse<String>> post(HttpClient http, int port) {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[]}"))
                .build();
        return http.sendAsync(r, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void cancelClosesTheConnectionSoTheHostStopsComputing() throws Exception {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CountDownLatch received = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            silentHost(server, received, closed);
            PlanRequest request = new PlanRequest();
            assertTrue(request.track(post(http, server.getLocalPort())));
            assertTrue(received.await(10, TimeUnit.SECONDS), "Anfrage kam nicht an");
            assertFalse(closed.await(500, TimeUnit.MILLISECONDS), "Verbindung zu früh zu – Probe taugt nicht");
            request.cancel();
            assertTrue(closed.await(5, TimeUnit.SECONDS),
                    "Nach cancel() hält der Client die Verbindung offen – der Host rechnet weiter");
        }
    }

    @Test
    void aRoundThatStartsAfterTheStopIsCancelledAtOnce() throws Exception {
        PlanRequest request = new PlanRequest();
        request.cancel();
        CompletableFuture<String> next = new CompletableFuture<>();
        assertFalse(request.track(next), "Nachbesserungsrunde nach dem Stopp darf nicht laufen");
        assertTrue(next.isCancelled());
        assertTrue(request.cancelled());
    }
}
