package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Anrede „Hey Vera, …“ und Steuerwörter ohne LLM. */
class CompanionChatTest {

    @Test
    void recognizesAddressWithAndWithoutGreeting() {
        assertEquals("gib mir Fackeln", CompanionChat.addressed("Vera", "Hey Vera, gib mir Fackeln"));
        assertEquals("gib mir Fackeln", CompanionChat.addressed("Vera", "hey vera gib mir Fackeln"));
        assertEquals("wie spät ist es?", CompanionChat.addressed("Vera", "Vera: wie spät ist es?"));
        assertEquals("komm", CompanionChat.addressed("Vera", "@Vera komm"));
        assertEquals("was geht", CompanionChat.addressed("Vera", "Hallo, Vera! was geht"));
        assertEquals("", CompanionChat.addressed("Vera", "Hey Vera!"));
        assertEquals("", CompanionChat.addressed("Vera", "vera"));
    }

    @Test
    void ignoresOtherNamesAndWordsStartingWithTheName() {
        assertNull(CompanionChat.addressed("Vera", "Veranda bauen"));
        assertNull(CompanionChat.addressed("Vera", "Baumeister, bau ein Haus"));
        assertNull(CompanionChat.addressed("Vera", "ich frage Vera später"));
        assertNull(CompanionChat.addressed("", "Hey Vera"));
    }

    @Test
    void nameWithRegexCharactersIsQuoted() {
        assertNull(CompanionChat.addressed("V.ra", "Vera hallo"));
        assertEquals("hallo", CompanionChat.addressed("V.ra", "V.ra hallo"));
    }

    @Test
    void shortControlWordsWorkWithoutLlm() {
        assertEquals(CompanionChat.Command.COME, CompanionChat.command("Komm her!"));
        assertEquals(CompanionChat.Command.STAY, CompanionChat.command("bleib hier"));
        assertEquals(CompanionChat.Command.FOLLOW, CompanionChat.command("folge mir"));
        assertEquals(CompanionChat.Command.DISMISS, CompanionChat.command("Tschüss"));
        assertNull(CompanionChat.command("komm, wir bauen eine Brücke"), "längerer Satz geht ans LLM");
        assertNull(CompanionChat.command(null));
    }

    @Test
    void confirmationNeedsAShortClearAnswer() {
        assertEquals(Boolean.TRUE, CompanionChat.confirm("Ja!"));
        assertEquals(Boolean.TRUE, CompanionChat.confirm("ja, mach das"));
        assertEquals(Boolean.FALSE, CompanionChat.confirm("Nein."));
        assertEquals(Boolean.FALSE, CompanionChat.confirm("lass es"));
        assertNull(CompanionChat.confirm("ja und dann bau mir ein Haus"));
        assertNull(CompanionChat.confirm("stopp"));
        assertNull(CompanionChat.confirm(null));
    }

    @Test
    void undoWordsWorkWithoutTheModel() {
        assertEquals(CompanionChat.Command.UNDO, CompanionChat.command("Mach das rückgängig!"));
        assertEquals(CompanionChat.Command.UNDO, CompanionChat.command("rückgängig"));
        assertNull(CompanionChat.command("mach das rückgängig und bau dann eine Brücke"));
    }

    @Test
    void clearBuildOrdersSkipTheModel() {
        // dev01 2026-11-04 19:10: „Bau mir einen Pool“ endete mit HTTP 503, weil der Host gerade einen Plan rechnete.
        assertEquals(java.util.Optional.of("bau mir einen Pool"), CompanionChat.buildOrder("Bau mir einen Pool."));
        assertEquals(java.util.Optional.of("bau ein Pull"), CompanionChat.buildOrder("Bauen wir ein Pull."));
        assertEquals(java.util.Optional.of("bau den Pool um, es ist kein Pool"),
                CompanionChat.buildOrder("Bau den Pool um, es ist kein Pool."));
        assertEquals(java.util.Optional.of("bau mir einen Turm"), CompanionChat.buildOrder("Kannst du mir einen Turm bauen?"));
    }

    @Test
    void questionsAndTalkStayWithTheModel() {
        for (String s : java.util.List.of("Was könntest du mir bauen?", "Wie baut man eine Farm?", "Baut mir Brot",
                "Bauen ist toll", "Kannst du mir Brot geben?", "Kannst du mir was bauen?", "ich will bauen")) {
            assertEquals(java.util.Optional.empty(), CompanionChat.buildOrder(s), s);
        }
    }

    /** dev01-Log bis 2026-11-07: Vorwörter und „du sollst“ schickten klare Aufträge ans Modell. */
    @Test
    void leadWordsAndShouldDoNotHideAnOrder() {
        assertEquals(java.util.Optional.of("bau mir eine spannende Redstone-Anlage"),
                CompanionChat.buildOrder("Ja, baue mir eine spannende Redstone-Anlage."));
        assertEquals(java.util.Optional.of("bau mir ein Haus"), CompanionChat.buildOrder("du sollst mir ein Haus bauen."));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Elitren", 0)),
                CompanionChat.giveOrder("Nein, ich will Elitren."));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Brot", 0)),
                CompanionChat.giveOrder("Kann ich mir Brot geben?"));
        assertEquals("mach Tag", CompanionChat.stripLead("Okay, mach Tag"));
        assertEquals("du sollst bauen", CompanionChat.stripLead("du sollst bauen"));
    }

    /** Craften (2026-11-07): Vera stellt aus dem Inventar her – Erkennung ohne Modell, ohne „gib“ und „bau“ zu fangen. */
    @Test
    void craftWishesAreRecognisedWithCount() {
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Fackeln", 4)),
                CompanionChat.craftOrder("Vera, crafte mir 4 Fackeln!"));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Werkbank", 1)),
                CompanionChat.craftOrder("stell mir bitte eine Werkbank her"));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Eimer", 2)),
                CompanionChat.craftOrder("Kannst du mir zwei Eimer craften?"));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Eisenspitzhacke", 0)),
                CompanionChat.craftOrder("craft mir mal Eisenspitzhacke"));
        assertTrue(CompanionChat.craftOrder("gib mir 4 Fackeln").isEmpty(), "das ist Geben");
        assertTrue(CompanionChat.craftOrder("bau mir ein Haus").isEmpty(), "das ist Bauen");
        assertTrue(CompanionChat.craftOrder("wie craftet man eine Werkbank").isEmpty(), "Frage, kein Auftrag");
        // Geben unverändert nach dem Herauslösen der Anzahl-Regeln:
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Fackeln", 16)),
                CompanionChat.giveOrder("gib mir 16 Fackeln"));
    }

    @Test
    void clearItemWishesSkipTheModel() {
        // dev01 2026-11-04 20:21: „Kannst du mir Elytren geben?“ – das Modell gab nichts.
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Elytren", 0)),
                CompanionChat.giveOrder("Kannst du mir Elytren geben?"));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Fackeln", 16)),
                CompanionChat.giveOrder("Gib mir 16 Fackeln!"));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Brot", 0)),
                CompanionChat.giveOrder("gib mir mal bitte Brot"));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Schwert", 1)),
                CompanionChat.giveOrder("gib mir ein Schwert"));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Fackeln", 64)),
                CompanionChat.giveOrder("Gib mir einen Stack Fackeln"));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Diamanten", 0)),
                CompanionChat.giveOrder("Kannst du mir ein paar Diamanten geben?"));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Elytren", 0)),
                CompanionChat.giveOrder("gib mir die Elytren"));
        assertEquals(java.util.Optional.of(new CompanionChat.GiveOrder("Kekse", 5)),
                CompanionChat.giveOrder("gib mir fünf Kekse"));
    }

    @Test
    void talkAndQuestionsStayWithTheModel() {
        for (String s : java.util.List.of("Was kannst du mir geben?", "gib mir einen Tipp, wie ich Eisen finde",
                "Ich gebe dir Brot", "Kannst du mir sagen, wo Eisen ist?", "gibt es hier Diamanten?",
                "gib Steve 5 Brot")) {
            assertEquals(java.util.Optional.empty(), CompanionChat.giveOrder(s), s);
        }
    }

    @Test
    void adventureSentencesAreRecognisedWithoutTheModel() {
        assertEquals(CompanionChat.AdventureCommand.START_NORMAL, CompanionChat.adventure("Lass uns Minecraft spielen!"));
        assertEquals(CompanionChat.AdventureCommand.START_NORMAL, CompanionChat.adventure("Starte das Abenteuer"));
        assertEquals(CompanionChat.AdventureCommand.START_HARDCORE,
                CompanionChat.adventure("Lass uns Minecraft hardcore spielen"));
        assertEquals(CompanionChat.AdventureCommand.START_HARDCORE,
                CompanionChat.adventure("Neues Abenteuer mit einem Leben"));
        assertEquals(CompanionChat.AdventureCommand.STOP, CompanionChat.adventure("Beende das Abenteuer"));
        assertEquals(CompanionChat.AdventureCommand.WAY, CompanionChat.adventure("Wo geht's lang?"));
        assertEquals(CompanionChat.AdventureCommand.WAY, CompanionChat.adventure("Wohin jetzt?"));
        assertEquals(CompanionChat.AdventureCommand.HINT, CompanionChat.adventure("Was jetzt?"));
        assertEquals(CompanionChat.AdventureCommand.HINT, CompanionChat.adventure("Was ist das nächste Ziel?"));
        assertEquals(CompanionChat.AdventureCommand.STATUS, CompanionChat.adventure("Wie weit bin ich?"));
        // dev01 2026-11-04 21:47: so gefragt, ging der Satz an das Modell, und nichts startete.
        assertEquals(CompanionChat.AdventureCommand.START_NORMAL,
                CompanionChat.adventure("Können wir ein Abenteuer machen?"));
    }

    @Test
    void ordinaryTalkIsNoAdventure() {
        for (String s : java.util.List.of("Was ist das Abenteuer?", "Bau mir ein Haus", "Gib mir 16 Fackeln",
                "Wie spät ist es?", "Ich habe Hunger", "Wo ist Steve?")) {
            assertNull(CompanionChat.adventure(s), s);
        }
    }

    @Test
    void deathQuestionsSkipTheModel() {
        for (String s : java.util.List.of("Wo bin ich gestorben?", "Vera, wo liegen meine Sachen?", "wo ist mein Zeug",
                "Bring mich zu meinen Sachen")) {
            assertEquals(true, CompanionChat.askDeath(s), s);
        }
        for (String s : java.util.List.of("Wo ist Steve?", "Ich bin fast gestorben", "Gib mir meine Sachen zurück")) {
            assertEquals(false, CompanionChat.askDeath(s), s);
        }
    }

    @Test
    void misheardWordsFromTheEveningLogStillWork() {
        // dev01 2026-11-04 22:26–22:31
        assertEquals(java.util.Optional.of("bau mir ein Haus"), CompanionChat.buildOrder("bom mir ein Haus."));
        assertEquals(java.util.Optional.of("bau mir ein Haus"), CompanionChat.buildOrder("Bauch mir ein Haus."));
        assertEquals(java.util.Optional.empty(), CompanionChat.buildOrder("Bauch weh"));
        assertEquals(CompanionChat.AdventureCommand.STOP, CompanionChat.adventure("Abendreuer, stopp."));
        assertEquals(CompanionChat.AdventureCommand.HINT, CompanionChat.adventure("Kannst du mir helfen?"));
        assertEquals("Abenteuer starten", CompanionChat.addressed("Vera", "Abenteuer starten, Vera."));
        assertNull(CompanionChat.addressed("Vera", "Ich mag Vera"));
        assertNull(CompanionChat.addressed("Vera", "Das ist Veras Haus, Veranda"));
    }

    @Test
    void jokesAndTheRankingNeedNoModel() {
        for (String s : java.util.List.of("Erzähl mir einen Witz", "Vera, kennst du einen Witz?", "noch einen Witz",
                "Mach mal einen Witz", "Bring mich zum Lachen")) {
            assertEquals(true, CompanionChat.askJoke(s), s);
        }
        for (String s : java.util.List.of("Das ist doch ein Witz", "Witzig!", "Erzähl mir was über Creeper")) {
            assertEquals(false, CompanionChat.askJoke(s), s);
        }
        assertEquals(true, CompanionChat.askRanking("Wer ist am weitesten?"));
        assertEquals(true, CompanionChat.askRanking("Zeig mir die Rangliste"));
        assertEquals(false, CompanionChat.askRanking("Wie weit bin ich?"));
    }

    @Test
    void theDiaryOpensOnRequest() {
        // „öffne mein Tagebuch, Vera“: ohne (?U) fand \b vor dem ö keine Wortgrenze (UmlautBoundaryTest, 2026-11-07).
        for (String s : java.util.List.of("Zeig mir mein Tagebuch", "Vera, öffne das Abenteuertagebuch!", "Tagebuch",
                "Was steht in meinem Tagebuch?", "öffne mein Tagebuch, Vera")) {
            assertEquals(true, CompanionChat.askDiary(s), s);
        }
        for (String s : java.util.List.of("Ich schreibe Tagebuch über Creeper", "Wo ist Steve?")) {
            assertEquals(false, CompanionChat.askDiary(s), s);
        }
    }

    @Test
    void rucksackSentencesNeedTheWordRucksack() {
        assertEquals(true, CompanionChat.rucksackOpen("Öffne meinen Rucksack!"));
        assertEquals(true, CompanionChat.rucksackOpen("Rucksack auf"));
        assertEquals(false, CompanionChat.rucksackOpen("Öffne die Tür"));

        assertEquals(new CompanionChat.GiveOrder("Fackeln", 32),
                CompanionChat.rucksackTake("Gib mir 32 Fackeln aus meinem Rucksack").orElseThrow());
        assertEquals(new CompanionChat.GiveOrder("Elytren", 0),
                CompanionChat.rucksackTake("Hol mir die Elytren aus dem Rucksack raus").orElseThrow());
        assertEquals(new CompanionChat.GiveOrder("Steak", 5),
                CompanionChat.rucksackTake("kannst du mir aus dem Rucksack 5 Steak geben?").orElseThrow());
        assertEquals(java.util.Optional.empty(), CompanionChat.rucksackTake("Gib mir 32 Fackeln"));

        for (String[] c : new String[][] {{"Wo ist mein Diamant im Rucksack?", "Diamant"},
                {"Hab ich noch Eisen im Rucksack?", "Eisen"}, {"Such mal nach Smaragden im Rucksack", "Smaragden"},
                {"Durchsuche den Rucksack nach Redstone", "Redstone"}, {"Wo im Rucksack liegt die Spitzhacke", "Spitzhacke"},
                {"Sind Fackeln im Rucksack?", "Fackeln"}}) {
            assertEquals(c[1], CompanionChat.rucksackSearch(c[0]).orElse(null), c[0]);
        }
        assertEquals(java.util.Optional.empty(), CompanionChat.rucksackSearch("Wo ist mein Pferd?"));

        assertEquals("Bruchstein", CompanionChat.rucksackCollect("Sammel Bruchstein automatisch in den Rucksack")
                .orElse(null));
        assertEquals("Erde", CompanionChat.rucksackCollect("pack ab jetzt die Erde immer in meinen Rucksack")
                .orElse(null));
        assertEquals(java.util.Optional.empty(), CompanionChat.rucksackCollect("Sammel Holz"));
    }

    @Test
    void redstoneQuestionsAreRecognized() {
        for (String s : java.util.List.of("Prüf mal meine Schaltung", "Vera, prüfe die Lampe",
                "Warum geht meine Lampe nicht?", "warum funktioniert die Tür nicht", "Die Schaltung geht nicht",
                "Warum leuchtet die Lampe da nicht", "was ist los mit meiner Farm?",
                "warum die Kolben nicht ausfährt", "Die Lampe leuchtet noch nicht")) {
            assertEquals(true, CompanionChat.askRedstoneCheck(s), s);
        }
        for (String s : java.util.List.of("Warum geht das nicht?", "Bau mir eine Lampe", "Prüf mal ob es regnet",
                "warum bist du nicht da", "Wer war an meiner Kiste?")) {
            assertEquals(false, CompanionChat.askRedstoneCheck(s), s);
        }
        assertEquals(true, CompanionChat.askRedstoneCheck("Vera, repariere meine Schaltung"));
        assertEquals(true, CompanionChat.askRedstoneRepair("Vera, repariere meine Schaltung"));
        assertEquals(false, CompanionChat.askRedstoneRepair("Prüf meine Schaltung"));
        assertEquals(false, CompanionChat.askRedstoneCheck("Reparier mein Haus"));
        for (String s : java.util.List.of("Was macht diese Schaltung?", "Vera, probier die Schaltung aus",
                "Zeig mir die Wahrheitstabelle", "Finde heraus, was die Schaltung macht", "welche Logik ist das")) {
            assertEquals(true, CompanionChat.askRedstoneLogic(s), s);
        }
        assertEquals(false, CompanionChat.askRedstoneLogic("Prüf meine Schaltung"));
        assertEquals(false, CompanionChat.askRedstoneLogic("Was macht der Creeper da"));
        assertEquals(true, CompanionChat.aboutRedstone("Erklär mir diese Schaltung"));
        assertEquals(true, CompanionChat.aboutRedstone("Wie baue ich ein Flipflop?"));
        assertEquals(false, CompanionChat.aboutRedstone("Erzähl mir einen Witz"));
    }

    @Test
    void chestAndRepairQuestionsAreRecognized() {
        for (String s : java.util.List.of("Wer war an meiner Kiste?", "Vera, wer hat was aus der Kiste genommen",
                "wer hat mich beklaut", "Wer hat meine Sachen geklaut?")) {
            assertEquals(true, CompanionChat.askChest(s), s);
        }
        assertEquals(false, CompanionChat.askChest("Bau mir eine Kiste"));
        for (String s : java.util.List.of("Reparier mein Haus", "Kannst du den Schaden reparieren?",
                "mach den Creeperschaden weg", "Bau mein Haus wieder auf")) {
            assertEquals(true, CompanionChat.askRepair(s), s);
        }
        assertEquals(false, CompanionChat.askRepair("Bau mir ein Haus"));
    }

    @Test
    void questQuestionsAreNotTheAdventureRanking() {
        assertEquals(true, CompanionChat.askQuests("Was sind meine Aufgaben?"));
        assertEquals(true, CompanionChat.askQuests("Vera, was soll ich heute machen"));
        assertEquals(true, CompanionChat.askQuests("Tagesaufgaben"));
        assertEquals(true, CompanionChat.askQuestRanking("Wer hat die meisten Punkte?"));
        assertEquals(false, CompanionChat.askQuests("Wer hat die meisten Punkte?"));
        assertEquals(false, CompanionChat.askQuestRanking("Wer ist am weitesten?"));
        assertEquals(false, CompanionChat.askQuests("Bau mir ein Haus"));
    }

    @Test
    void invitesNameAPlayerNotAPronoun() {
        assertEquals("Alex_99", CompanionChat.adventureInvite("Lade Alex_99 ins Abenteuer ein").orElse(null));
        assertEquals("Steve", CompanionChat.adventureInvite("Spiel mit Steve zusammen!").orElse(null));
        assertEquals("Steve", CompanionChat.adventureInvite("wir spielen jetzt mit Steve").orElse(null));
        assertEquals("Alex", CompanionChat.adventureInvite("Alex soll auch mitspielen").orElse(null));
        assertEquals(java.util.Optional.empty(), CompanionChat.adventureInvite("Spiel mit mir"));
        assertEquals(java.util.Optional.empty(), CompanionChat.adventureInvite("spielen mit uns zusammen"));
    }

    @Test
    void buildingsAreRememberedAndRebuiltByName() {
        assertEquals(1, CompanionChat.corner("Ecke eins"));
        assertEquals(2, CompanionChat.corner("zweite Ecke hier!"));
        assertEquals(0, CompanionChat.corner("Ecke"));
        assertEquals("tuerme", CompanionChat.rememberBuild("Merk dir das Bauwerk als Türme").orElse(null));
        assertEquals(java.util.Optional.empty(), CompanionChat.rememberBuild("merk dir das hier als zuhause"));
        assertEquals(new CompanionChat.Rebuild("turm", false, false),
                CompanionChat.rebuild("Bau turm hier").orElseThrow());
        assertEquals(new CompanionChat.Rebuild("haus", true, true),
                CompanionChat.rebuild("bau mein haus nochmal gespiegelt doppelt so groß").orElseThrow());
        assertEquals(java.util.Optional.empty(), CompanionChat.rebuild("Bau mir ein großes Haus mit Garten"));
    }

    @Test
    void helpQuestionsOpenTheBookButQuestsStayQuests() {
        for (String s : java.util.List.of("Hilfe", "Was kann ich hier alles machen?", "ich bin neu",
                "Wie funktioniert der Server?")) {
            assertEquals(true, CompanionChat.askHelp(s), s);
        }
        assertEquals(false, CompanionChat.askHelp("Was kann ich heute machen?"), "das sind die Tagesaufgaben");
        assertEquals(false, CompanionChat.askHelp("Hilf mir beim Bauen"));
    }
}
