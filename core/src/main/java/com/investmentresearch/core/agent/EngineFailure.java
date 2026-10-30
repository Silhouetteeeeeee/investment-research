package com.investmentresearch.core.agent;

import com.investmentresearch.core.run.RunRecord;

/** A failed ask that still carries the finished error run record (agent.py:563,720-723). */
public final class EngineFailure extends RuntimeException {
    private final RunRecord run;

    public EngineFailure(String message, RunRecord run, Throwable cause) {
        super(message, cause);
        this.run = run;
    }

    public RunRecord run() {
        return run;
    }
}
