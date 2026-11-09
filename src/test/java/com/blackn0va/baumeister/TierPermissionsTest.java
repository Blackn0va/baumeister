package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Tier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Die drei Stufen-Rechte stehen in plugin.yml (Vorgabe op) und sind Kinder von baumeister.admin – Admins in LuckPerms
 * bekommen baumeister.admin oder „*“ und dürfen dann alles wie ein OP (Nutzer 2026-11-08). Per Muster, weil snakeyaml
 * im Testpfad fehlt (wie BaumeisterCommandTest).
 */
class TierPermissionsTest {

    @Test
    void everyTierPermissionDefaultsToOpAndIsAChildOfAdmin() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8);
        int admin = yml.indexOf("  baumeister.admin:");
        String adminBlock = yml.substring(admin);
        for (Tier t : Tier.values()) {
            if (t.permission() == null) {
                continue;
            }
            String p = Pattern.quote(t.permission());
            // Ein Zeilenkommentar (umlaut-ok) hinter dem Schlüssel ist erlaubt.
            assertTrue(Pattern.compile("(?m)^  " + p + ":( +#.*)?\\n    description: .+\\n    default: op$")
                    .matcher(yml).find(), t.permission() + " mit default: op");
            assertTrue(Pattern.compile("(?m)^      " + p + ": true( +#.*)?$").matcher(adminBlock).find(),
                    t.permission() + " als Kind von baumeister.admin");
        }
    }

    /**
     * Sicherheitsprüfung 2026-11-08, Befund 10: Die Admin-Stufen liegen nicht unter baumeister.bauen.* – sonst gab
     * „baumeister.bauen.*“ in LuckPerms an Bauende Befehlsblöcke frei.
     */
    @Test
    void adminTiersAreOutsideTheBuildWildcard() {
        for (Tier t : new Tier[] {Tier.DANGEROUS, Tier.COMMANDS}) {
            assertFalse(t.permission().startsWith("baumeister.bauen."), t.permission());
            assertTrue(t.permission().startsWith("baumeister.admin."), t.permission());
        }
    }
}
