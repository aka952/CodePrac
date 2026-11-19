package dev.codecomp.run;

import dev.codecomp.contracts.RunResult;
import dev.codecomp.contracts.RunResult.RunStatus;

/** What a finished job holds and the browser receives: the sandbox's result, and whether it came from the cache. */
public record RunResponse(RunStatus status, String stdout, String stderr, int exitCode, long durationMs, boolean truncated, boolean cached) {

    public static RunResponse of(RunResult r, boolean cached) {
        return new RunResponse(r.status(), r.stdout(), r.stderr(), r.exitCode(), r.durationMs(), r.truncated(), cached);
    }
}
