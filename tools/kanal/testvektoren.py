"""Erzeugt docs/kanal-v1-testvektoren.json (Kanal v1, 2026-11-08). Aufruf: python -I tools/kanal/testvektoren.py tools/kanal/testvektor.pem docs/kanal-v1-testvektoren.json

Rahmen nach RFC 6455, Nutzlast = u16 BE Kopflänge + Kopf (JSON, UTF-8) + Datenstück. Gültige Vektoren prüfen das
LESEN (Rahmen → Nachricht) auf beiden Seiten; die Kopf-Bytes sind kompakt geschrieben, Leser müssen aber jede
Schlüsselreihenfolge annehmen. Große Rahmen stehen nur als SHA-256 mit Erzeugungsregel drin.
"""
import base64
import hashlib
import json
import sys


def ws_frame(opcode, payload, mask=None, fin=True, rsv=0):
    b0 = (0x80 if fin else 0) | (rsv << 4) | opcode
    n = len(payload)
    if n < 126:
        head = bytes([b0, (0x80 if mask else 0) | n])
    elif n < 65536:
        head = bytes([b0, (0x80 if mask else 0) | 126]) + n.to_bytes(2, 'big')
    else:
        head = bytes([b0, (0x80 if mask else 0) | 127]) + n.to_bytes(8, 'big')
    if mask:
        masked = bytes(c ^ mask[i % 4] for i, c in enumerate(payload))
        return head + mask + masked
    return head + payload


def message(kopf, daten=b''):
    k = json.dumps(kopf, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    return len(k).to_bytes(2, 'big') + k + daten


MASK = bytes.fromhex('37fa213d')
gueltig = []


def add(name, richtung, kopf, daten=b'', groß=False):
    masked = richtung == 'app->relay'
    payload = message(kopf, daten)
    frame = ws_frame(0x2, payload, MASK if masked else None)
    v = {'name': name, 'richtung': richtung, 'maske': MASK.hex() if masked else None, 'kopf': kopf,
         'daten_laenge': len(daten), 'daten_sha256': hashlib.sha256(daten).hexdigest()}
    if groß:
        v['rahmen_sha256'] = hashlib.sha256(frame).hexdigest()
        v['rahmen_laenge'] = len(frame)
        v['daten_regel'] = 'bytes(i % 251 for i in range(daten_laenge))'
    else:
        v['daten_hex'] = daten.hex()
        v['rahmen_hex'] = frame.hex()
    gueltig.append(v)


add('hallo der App', 'app->relay', {'t': 'hallo', 'v': 1, 'app': 'BaumeisterHost/test', 'max': 6})
add('hallo des Relays', 'relay->app', {'t': 'hallo', 'v': 1, 'relay': 'baumeister-relay/test'})
add('anfrage komplett in einem Rahmen', 'relay->app',
    {'t': 'anfrage', 'id': 1, 'm': 'POST', 'p': '/v1/chat/completions', 'ct': 'application/json', 'len': 7},
    b'{"a":1}')
add('anfrage GET ohne Body mit Abfrage', 'relay->app',
    {'t': 'anfrage', 'id': 2, 'm': 'GET', 'p': '/baumeister/v1/begleiter/gehoert?imspiel=Alex', 'len': 0})
add('anfrage in Stücken, erstes Stück 4 von 10', 'relay->app',
    {'t': 'anfrage', 'id': 3, 'm': 'POST', 'p': '/baumeister/v1/stadt', 'ct': 'application/json', 'len': 10},
    b'{"x"')
add('daten, zweites Stück 6 von 10', 'relay->app', {'t': 'daten', 'id': 3}, b':123}\n')
add('antwort klein', 'app->relay', {'t': 'antwort', 'id': 1, 's': 200, 'ct': 'application/json', 'len': 2}, b'{}')
add('antwort 503 des Hosts unverändert', 'app->relay',
    {'t': 'antwort', 'id': 2, 's': 503, 'ct': 'application/json', 'len': 24}, b'{"error":"beschaeftigt"}')
add('antwort mit 16-Bit-Länge (126)', 'app->relay',
    {'t': 'antwort', 'id': 4, 's': 200, 'ct': 'application/octet-stream', 'len': 190},
    bytes(i % 251 for i in range(190)))
add('daten mit 64-Bit-Länge (127), volles Stück 65536', 'app->relay', {'t': 'daten', 'id': 4},
    bytes(i % 251 for i in range(65536)), groß=True)
add('abbruch vom Relay (Plugin hat aufgelegt)', 'relay->app', {'t': 'abbruch', 'id': 1})
add('abbruch von der App (eigener Server nicht erreichbar)', 'app->relay', {'t': 'abbruch', 'id': 3})
add('Kopf mit Umlaut und anderer Schlüsselreihenfolge', 'relay->app',
    {'len': 0, 'p': '/baumeister/v1/begleiter/gehoert?imspiel=J%C3%BCrgen', 'm': 'GET', 'id': 5, 't': 'anfrage'})


def raw(name, frame, grund, richtung='app->relay'):
    return {'name': name, 'richtung': richtung, 'rahmen_hex': frame.hex(), 'grund': grund}


ungueltig = [
    raw('Textrahmen statt Binär', ws_frame(0x1, message({'t': 'hallo', 'v': 1}), MASK), 'textrahmen'),
    raw('RSV-Bit gesetzt (keine Erweiterungen)', ws_frame(0x2, message({'t': 'hallo', 'v': 1}), MASK, rsv=4),
        'rsv'),
    raw('Client-Rahmen ohne Maske', ws_frame(0x2, message({'t': 'hallo', 'v': 1})), 'maske_fehlt'),
    raw('Server-Rahmen mit Maske', ws_frame(0x2, message({'t': 'hallo', 'v': 1}), MASK), 'maske_vom_server',
        'relay->app'),
    raw('Fragmentiert (FIN=0)', ws_frame(0x2, message({'t': 'hallo', 'v': 1}), MASK, fin=False), 'fragment'),
    raw('Ping mit Nutzlast > 125', ws_frame(0x9, b'x' * 126, MASK), 'steuerrahmen_zu_lang'),
    raw('Kopflänge 0', ws_frame(0x2, (0).to_bytes(2, 'big'), MASK), 'kopf_leer'),
    raw('Kopflänge 4097', ws_frame(0x2, (4097).to_bytes(2, 'big') + b'{' + b' ' * 4095 + b'}', MASK),
        'kopf_zu_lang'),
    raw('Kopflänge größer als Nutzlast', ws_frame(0x2, (50).to_bytes(2, 'big') + b'{}', MASK), 'kopf_abgeschnitten'),
    raw('Kopf kein UTF-8', ws_frame(0x2, (3).to_bytes(2, 'big') + b'\xff\xfe\xfd', MASK), 'kopf_kein_utf8'),
    raw('Kopf ist JSON-Feld statt Objekt', ws_frame(0x2, message([1, 2]), MASK), 'kopf_kein_objekt'),
    raw('Unbekannter Typ', ws_frame(0x2, message({'t': 'rede', 'id': 1}), MASK), 'typ_unbekannt'),
    raw('antwort ohne id', ws_frame(0x2, message({'t': 'antwort', 's': 200, 'len': 0}), MASK), 'id_fehlt'),
    raw('id negativ', ws_frame(0x2, message({'t': 'antwort', 'id': -1, 's': 200, 'len': 0}), MASK), 'id_ungueltig'),
    raw('id über u32', ws_frame(0x2, message({'t': 'antwort', 'id': 4294967296, 's': 200, 'len': 0}), MASK),
        'id_ungueltig'),
    raw('Status außerhalb 200..599', ws_frame(0x2, message({'t': 'antwort', 'id': 1, 's': 700, 'len': 0}), MASK),
        'status_ungueltig'),
    # 1xx (2026-11-08, Sicherheitsprüfung M1): Zwischenantworten verwirft der HttpClient und liest den Body als Antwort.
    raw('Status 1xx (Zwischenantwort)', ws_frame(0x2, message({'t': 'antwort', 'id': 1, 's': 102, 'len': 0}), MASK),
        'status_ungueltig'),
    {'name': 'Datenstück über 65536', 'richtung': 'app->relay', 'grund': 'stueck_zu_gross',
     'regel': 'Kopf {"t":"daten","id":1} kompakt + 65537 Nullbytes, Binärrahmen FIN, maskiert mit 37fa213d',
     'rahmen_laenge': len(ws_frame(0x2, message({'t': 'daten', 'id': 1}, b'\0' * 65537), MASK)),
     'rahmen_sha256': hashlib.sha256(ws_frame(0x2, message({'t': 'daten', 'id': 1}, b'\0' * 65537), MASK))
     .hexdigest()},
    raw('len negativ', ws_frame(0x2, message({'t': 'antwort', 'id': 1, 's': 200, 'len': -5}), MASK),
        'laenge_ungueltig'),
    raw('Pfad mit Leerzeichen', ws_frame(0x2, message({'t': 'anfrage', 'id': 1, 'm': 'GET', 'p': '/v1/a b',
                                                       'len': 0})), 'pfad_ungueltig', 'relay->app'),
    raw('Pfad mit CRLF', ws_frame(0x2, message({'t': 'anfrage', 'id': 1, 'm': 'GET', 'p': '/v1/a\r\nX: y',
                                                'len': 0})), 'pfad_ungueltig', 'relay->app'),
    raw('Methode außerhalb GET/POST', ws_frame(0x2, message({'t': 'anfrage', 'id': 1, 'm': 'DELETE', 'p': '/health',
                                                              'len': 0})), 'methode_ungueltig', 'relay->app'),
    raw('Content-Type mit CRLF', ws_frame(0x2, message({'t': 'antwort', 'id': 1, 's': 200,
                                                        'ct': 'text/plain\r\nX: y', 'len': 0}), MASK),
        'typ_ungueltig'),
]

pfade = {
    'erlaubt': ['/v1/chat/completions', '/health', '/baumeister/v1/stadt', '/baumeister/v1/ergebnis',
                '/baumeister/v1/recherche', '/baumeister/v1/begleiter/sagen',
                '/baumeister/v1/begleiter/gehoert?imspiel=Alex', '/baumeister/v1/begleiter/hoeren?spieler=Alex',
                '/baumeister/v1/begleiter/gehoert?imspiel=J%C3%BCrgen',
                # Komma (2026-11-08): das Plugin listet die Spieler im Voice-Chat so (CompanionVoiceLink.heardUri).
                '/baumeister/v1/begleiter/gehoert?imspiel=Alex,Steve'],
    'verboten': ['/', '/v1', '/healthz', '/health/', '/baumeister/v2/stadt', '/baumeister/v1', '/../etc/passwd',
                 '/baumeister/v1/../../x', '/v1/%2e%2e/x', '/v1/%2E%2E/x', '//v1/chat', '/v1/chat\\..\\x',
                 'http://evil/v1/chat', '/v1/chat completions', '/baumeister/v1/stadt#x',
                 # Abfrage (2026-11-08, App P2 strenger als die erste Fassung): %2e auch dort verboten, % nur mit zwei
                 # Hex-Ziffern.
                 '/baumeister/v1/begleiter/gehoert?imspiel=%2e', '/baumeister/v1/begleiter/gehoert?imspiel=%2E%2E',
                 '/baumeister/v1/stadt?x=%zz', '/baumeister/v1/stadt?x=%4', '/baumeister/v1/stadt?x=%',
                 # Kodierte Steuerzeichen (2026-11-08, N6): das Relay lehnte sie schon ab, die App noch nicht.
                 '/baumeister/v1/stadt?x=%00', '/baumeister/v1/stadt?x=%1F', '/baumeister/v1/stadt?x=%7f'],
}

pem = open(sys.argv[1], encoding='ascii').read().strip()
der = base64.b64decode(''.join(l for l in pem.splitlines() if not l.startswith('-----')))
pin = {'pem': pem, 'pin': 'sha256:' + hashlib.sha256(der).hexdigest(),
       'regel': 'SHA-256 über die DER-Bytes des Blattzertifikats, klein geschrieben, Präfix sha256:'}

ws = {'key': 'dGhlIHNhbXBsZSBub25jZQ==', 'accept': 's3pPLMBiTxaQ9kYGzzhZRbK+xOo=',
      'protokoll': 'baumeister-kanal.v1', 'quelle': 'RFC 6455 Abschnitt 1.3'}

out = {'version': 1, 'erzeugt': '2026-11-08', 'websocket': ws, 'pin': pin, 'gueltig': gueltig,
       'ungueltig': ungueltig, 'pfade': pfade}
with open(sys.argv[2], 'w', encoding='utf-8', newline='\n') as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
    f.write('\n')
print('gültig', len(gueltig), 'ungültig', len(ungueltig), 'pin', pin['pin'])
