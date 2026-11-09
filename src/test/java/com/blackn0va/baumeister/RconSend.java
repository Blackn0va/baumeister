package com.blackn0va.baumeister;

/**
 * Ein RCON-Befehl an den lokalen Testserver (Messproben, Neustart des Testservers). Aufruf: RconSend &lt;port&gt;
 * &lt;passwort&gt; &lt;befehl …&gt;. Gibt die Antwort aus.
 */
public final class RconSend {

    public static void main(String[] args) throws Exception {
        try (Rcon r = new Rcon(Integer.parseInt(args[0]), args[1])) {
            System.out.println(r.command(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))));
        }
    }
}
