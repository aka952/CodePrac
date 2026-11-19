package dev.codecomp.run;

import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.RunResult.RunStatus;

import java.util.List;

/** What a finished run job holds: written by the worker into run_jobs.result, read back and sent to the browser by the API. */
public interface RunResults {

    record RunResponse(RunStatus status, String stdout, String stderr, int exitCode, long durationMs, boolean truncated, boolean cached) {
        public static RunResponse of(RunResult r, boolean cached) {
            return new RunResponse(r.status(), r.stdout(), r.stderr(), r.exitCode(), r.durationMs(), r.truncated(), cached);
        }
    }

    record TestCase(String name, boolean passed, String message) {}

    /** {@code points} is what the user has earned on this exercise so far (their best run counts). */
    record TestRunResult(List<TestCase> tests, int passed, int total, boolean allPassed, RunResponse run, int points, int maxPoints) {}
}
