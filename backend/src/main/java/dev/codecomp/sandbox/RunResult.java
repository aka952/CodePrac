package dev.codecomp.sandbox;

public record RunResult(RunStatus status, String stdout, String stderr, int exitCode, long durationMs, boolean truncated) {

    public enum RunStatus { OK, RUNTIME_ERROR, TIMEOUT, MEMORY_LIMIT, OUTPUT_LIMIT, SANDBOX_ERROR }

    public static RunResult sandboxError(String message, long durationMs) {
        return new RunResult(RunStatus.SANDBOX_ERROR, "", message, -1, durationMs, false);
    }

    public boolean cacheable() { return status != RunStatus.SANDBOX_ERROR; }
}
