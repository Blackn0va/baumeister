package com.blackn0va.baumeister.plan;

/** Welche Block-IDs der Server kennt. Im Plugin über Material, in Tests über eine feste Liste. */
public interface BlockCatalog {

    boolean isBlock(String id);

    /** Alle Block-IDs ohne „minecraft:“ – für die Namenssuche (ItemNames); leer = keine Suche. */
    default java.util.Set<String> allIds() {
        return java.util.Set.of();
    }
}
