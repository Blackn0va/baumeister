package com.blackn0va.baumeister.plan;

/** Bauplan ist unbrauchbar. Die Meldung ist deutsch und geht so in den Chat und an das LLM zurück. */
public final class PlanException extends Exception {

    private static final long serialVersionUID = 1L;

    public PlanException(String message) {
        super(message);
    }
}
