package com.blackn0va.baumeister.client;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Nachricht im Plugin-Kanal baumeister:module (Vertrag v012, plugin_zu_mod): Das Plugin schickt modul_json als rohe
 * UTF-8-Bytes über Player.sendPluginMessage – ohne Längenpräfix, ohne Minecraft-Codec. Deshalb liest der Codec den
 * ganzen Rest des Puffers statt eines writeUtf-Strings (der erwartete ein VarInt davor und läse sonst Müll).
 *
 * Registriert wird nur S2C (clientboundPlay): Damit meldet Fabric den Kanal beim Beitritt per minecraft:register an,
 * und erst dann schickt Bukkit dem Spieler überhaupt Nachrichten auf diesem Kanal.
 *
 * Threads: Netz-Thread (Dekodieren). Fehler: Zu groß → Bytes überspringen und als zu groß markieren statt werfen; eine
 * Ausnahme im Codec trennt die Verbindung zum Server, und das für eine reine Anzeige.
 */
record ModulePayload(byte[] data, int size) implements CustomPacketPayload {

    static final Type<ModulePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("baumeister", "module"));

    static final StreamCodec<FriendlyByteBuf, ModulePayload> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeBytes(p.data()), ModulePayload::read);

    /** data == null ⇔ größer als ServerModules.MAX_BYTES; size ist die gemeldete Länge fürs Log. */
    static ModulePayload read(FriendlyByteBuf buf) {
        int n = buf.readableBytes();
        if (n > ServerModules.MAX_BYTES) {
            buf.skipBytes(n);  // Rest muss gelesen sein, sonst meldet Minecraft „extra bytes“ und trennt
            return new ModulePayload(null, n);
        }
        byte[] b = new byte[n];
        buf.readBytes(b);
        return new ModulePayload(b, n);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
