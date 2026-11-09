package com.blackn0va.baumeister;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimaler RCON-Client (Source-RCON-Protokoll, wie Minecraft ihn spricht) für Messproben gegen den lokalen
 * Testserver. Nur 127.0.0.1 – das Passwort steht in der server.properties des Testservers, nie im Code.
 */
final class Rcon implements AutoCloseable {

    private static final int LOGIN = 3;
    private static final int COMMAND = 2;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    Rcon(int port, String password) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        socket.setSoTimeout(15000);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        int id = send(LOGIN, password);
        int answered = readId();
        if (answered != id) {
            throw new IOException("RCON-Anmeldung abgelehnt");
        }
    }

    String command(String cmd) throws IOException {
        send(COMMAND, cmd);
        return readPayload();
    }

    private int send(int type, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        int id = nextId++;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 + body.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(4 + 4 + body.length + 2).putInt(id).putInt(type).put(body).put((byte) 0).put((byte) 0);
        out.write(b.array());
        out.flush();
        return id;
    }

    private int readId() throws IOException {
        return readPacket().id;
    }

    private String readPayload() throws IOException {
        return readPacket().payload;
    }

    private record Packet(int id, String payload) {
    }

    private Packet readPacket() throws IOException {
        int len = Integer.reverseBytes(in.readInt());
        byte[] data = new byte[len];
        in.readFully(data);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = b.getInt();
        b.getInt();
        String payload = new String(data, 8, Math.max(0, len - 10), StandardCharsets.UTF_8);
        return new Packet(id, payload);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
