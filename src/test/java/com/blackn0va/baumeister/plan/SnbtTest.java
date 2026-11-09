package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Strenger SNBT-Leser (Plan B2): nur die Grundform, alles andere abgelehnt; an Minecraft geht nur die neu
 * geschriebene Fassung.
 */
class SnbtTest {

    private static Nbt.Compound parse(String s) throws PlanException {
        return Snbt.parseCompound(s, Snbt.MAX_TRUSTED);
    }

    private static String reject(String s) {
        return assertThrows(PlanException.class, () -> parse(s), s).getMessage();
    }

    @Test
    void parsesTheSubsetAndWritesTheCanonicalForm() throws Exception {
        Nbt.Compound c = parse("{ Items:[{Slot:0b,id:\"minecraft:stone\",count:3}], CustomName:'Kiste', lit:true, "
                + "arr:[I;1,2,3], bytes:[B;1b,-2b], longs:[L;5L,6], f:1.5f, d:-2.25d, plain:2.5, l:5L, s:7s, "
                + "word:north }");
        assertEquals("{Items:[{Slot:0b,id:\"minecraft:stone\",count:3}],CustomName:\"Kiste\",lit:1b,"
                + "arr:[I;1,2,3],bytes:[B;1b,-2b],longs:[L;5L,6L],f:1.5f,d:-2.25d,plain:2.5d,l:5L,s:7s,"
                + "word:\"north\"}", Snbt.write(c));
    }

    @Test
    void canonicalFormReadsBackToTheSameTree() throws Exception {
        Nbt.Compound c = parse("{\"minecraft:custom_data\":{a:'x\"y',b:\"z\\\\w\",n:\"zwei\\nZeilen\",t:\"a\\tb\"},"
                + "e:[],k:{}}");
        String written = Snbt.write(c);
        assertEquals(c, parse(written));
        assertFalse(written.contains("\n"), "kein echter Zeilenumbruch im Befehl: " + written);
        assertTrue(written.startsWith("{\"minecraft:custom_data\":"), "Schlüssel mit : in Anführungszeichen");
    }

    @Test
    void unicodeHexAndNamedEscapesAreRejected() {
        assertTrue(reject("{a:\"\\u0041\"}").contains("\\u"));
        reject("{a:\"\\x41\"}");
        reject("{a:\"\\N{LATIN CAPITAL LETTER A}\"}");
        reject("{a:'\\''}");
        reject("{a:\"\\b\"}");
    }

    @Test
    void functionsLikeBoolAndUuidAreRejected() {
        assertTrue(reject("{a:bool(1)}").contains("bool"));
        reject("{UUID:uuid(\"069a79f4-44e9-4726-a5be-fca90e38aaf5\")}");
        reject("{a: bool (1)}");
    }

    @Test
    void textAfterTheDataAndASecondCompoundAreRejected() {
        reject("{a:1}{b:2}");
        reject("{a:1} run op Gast");
        reject("{a:1");
        reject("a:1}");
        reject("{a:1,}");
    }

    @Test
    void duplicateKeysAreRejected() {
        assertTrue(reject("{Command:\"say a\",Command:\"op Gast\"}").contains("doppelt"));
    }

    @Test
    void exoticNumberFormsAndOutOfRangeValuesAreRejected() {
        reject("{a:128b}");
        reject("{a:0x10}");
        reject("{a:1_000}");
        reject("{a:1e5}");
        reject("{a:1ub}");
        reject("{a:1sb}");
        reject("{a:2147483648}");
        reject("{a:[B;1,300]}");
        reject("{a:[I;1L]}");
        reject("{a:-}");
    }

    @Test
    void rawControlCharactersAndLoneSurrogatesAreRejected() {
        reject("{a:\"x\u0007y\"}");
        reject("{a:\"x\ny\"}");
        reject("{a:\"\uD800\"}");
    }

    @Test
    void deepNestingAndOverlongTextAreRejected() {
        String deep = "{a:".repeat(40) + "1" + "}".repeat(40);
        assertTrue(reject(deep).contains("verschachtelt"));
        assertThrows(PlanException.class, () -> Snbt.parseCompound("{a:\"" + "x".repeat(1000) + "\"}",
                Snbt.MAX_FROM_MODEL));
    }

    @Test
    void booleansAreBytes() throws Exception {
        assertEquals(Nbt.Num.ofByte(1), parse("{a:true}").get("a"));
        assertEquals(Nbt.Num.ofByte(0), parse("{a:false}").get("a"));
        assertEquals(new Nbt.Str("True"), parse("{a:True}").get("a"), "nur kleingeschrieben ein Wahrheitswert");
    }

    /** Befund 12: Was der Leser ablehnt, schreibt der Schreiber nie roh (Konsolenbefehl). \n und \t maskiert. */
    @Test
    void writerRefusesRawControlCharacters() {
        for (int c : new int[] {0, 13, 27, 0x7F}) {
            Nbt.Compound bad = new Nbt.Compound(java.util.Map.of("Text", new Nbt.Str("a" + (char) c + "b")));
            assertThrows(IllegalArgumentException.class, () -> Snbt.write(bad), "U+" + c);
        }
        String nl = "a" + (char) 10 + "b" + (char) 9 + "c";
        assertEquals("{Text:\"a" + "\\" + "nb" + "\\" + "tc\"}",
                Snbt.write(new Nbt.Compound(java.util.Map.of("Text", new Nbt.Str(nl)))));
    }
}
