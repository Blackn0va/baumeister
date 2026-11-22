package com.blackn0va.baumeister.town.presence;

import java.util.UUID;

/**
 * Ein Bewohner in der Tab-Liste: Profilname (= Bewohnername, das Rufwort im Chat), Profil-UUID (ResidentIdentity) und
 * angezeigter Ping in Millisekunden.
 *
 * Threads: unveränderlich. Fehler: keine.
 */
public record TabEntry(String name, UUID id, int latency) {
}
