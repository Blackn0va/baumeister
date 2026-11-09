package com.blackn0va.baumeister.relay;

/**
 * Verstoß gegen Kanal v1: Die Verbindung endet mit Close(reason.closeCode(), reason.code()).
 *
 * Ohne Stack-Trace: Die Ausnahme kommt aus fremden Bytes und kann im Sekundentakt fallen; ein Stack-Trace kostete dann
 * nur Zeit und stünde nie im Log (dort steht der Grund).
 */
final class ProtocolException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Reason reason;

    ProtocolException(Reason reason) {
        super(reason.code(), null, false, false);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
