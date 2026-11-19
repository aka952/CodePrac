package dev.codecomp.contracts;

/** What the sandbox reports for one run. The worker sends it in {@link RunEvent.Completed}. */
public record RunResult(RunStatus status, String stdout, String stderr, int exitCode, long durationMs, boolean truncated) {

    public enum RunStatus { OK, RUNTIME_ERROR, TIMEOUT, MEMORY_LIMIT, OUTPUT_LIMIT, SANDBOX_ERROR }

    public static RunResult sandboxError(String message, long durationMs) {
        return new RunResult(RunStatus.SANDBOX_ERROR, "", message, -1, durationMs, false);
    }

    /** Timeouts are not cached: they can be caused by the environment (e.g. an image pull on first run), not the code. */
    public boolean cacheable() { return status != RunStatus.SANDBOX_ERROR && status != RunStatus.TIMEOUT; }
}
