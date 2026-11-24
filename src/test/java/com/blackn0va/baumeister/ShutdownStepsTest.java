package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ShutdownStepsTest {

    @Test
    void laterStepsRunAfterAStepThrowsLinkageError() {
        List<String> ran = new ArrayList<>();
        Object live = new Object();
        List<String> failed = new ShutdownSteps()
                .add("begleiter", live, () -> {
                    // Wie auf dev01: Klasse aus dem ersetzten Jar nicht mehr ladbar.
                    throw new NoClassDefFoundError("com/blackn0va/baumeister/CompanionVoicechat$Stream");
                })
                .add("stadtleben", live, () -> ran.add("stadtleben"))
                .add("rucksack", live, () -> ran.add("rucksack"))
                .run();
        assertEquals(List.of("stadtleben", "rucksack"), ran);
        assertEquals(List.of("begleiter"), failed);
    }

    @Test
    void laterStepsRunAfterARuntimeException() {
        List<String> ran = new ArrayList<>();
        Object live = new Object();
        List<String> failed = new ShutdownSteps()
                .add("a", live, () -> {
                    throw new IllegalStateException("kaputt");
                })
                .add("b", live, () -> ran.add("b"))
                .run();
        assertEquals(List.of("b"), ran);
        assertEquals(List.of("a"), failed);
    }

    @Test
    void stepsOfServicesThatNeverStartedAreSkippedAndOrderIsKept() {
        List<String> ran = new ArrayList<>();
        Object live = new Object();
        List<String> failed = new ShutdownSteps()
                .add("erst", live, () -> ran.add("erst"))
                .add("nie_gestartet", null, () -> ran.add("nie_gestartet"))
                .add("dann", live, () -> ran.add("dann"))
                .run();
        assertEquals(List.of("erst", "dann"), ran);
        assertEquals(List.of(), failed);
    }
}
