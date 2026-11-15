package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sicherheitsprüfung 2026-11-15 (OWASP A01, ASVS V4.1.3): Ein Spieler darf sich keinen Bauteilnamen merken, den schon
 * eine Bauwerk-Datei, eine Vorlage oder eine Maschine trägt – sonst baute ein Admin später sein Bauteil.
 */
class SchematicLibraryReservedTest {

    @Test
    void namesOfCollectionFilesCannotBeTakenByPlayers(@TempDir Path dir) {
        SchematicLibrary lib = new SchematicLibrary(dir.toFile());
        assertNull(lib.reservedProblem("mein_haus"), "ohne Reservierung frei");
        Set<String> taken = Set.of("sp_iglu_1", "eisenfarm");
        lib.reserveNames(taken::contains);
        assertNotNull(lib.reservedProblem("sp_iglu_1"));
        assertNotNull(lib.reservedProblem("SP_IGLU_1"), "Groß/klein spielt keine Rolle");
        assertNotNull(lib.reservedProblem("eisenfarm"));
        assertNull(lib.reservedProblem("mein_haus"));
    }
}
