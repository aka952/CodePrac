package dev.codecomp.repo;

import dev.codecomp.contracts.JobView;

import java.util.UUID;

/** Submits runs to run-service. {@link HttpRunClient} in production; a fake in tests. */
public interface RunClient {

    /** {@code jobId} is chosen by the caller; {@code userId} is null for the system's own runs. */
    JobView submit(UUID jobId, UUID userId, String language, String code);

    /** run-service refused or could not be reached. Passed on to the browser with the same status and message. */
    class RunServiceException extends RuntimeException {
        private final int status;
        private final String retryAfter;

        public RunServiceException(int status, String message, String retryAfter) {
            super(message);
            this.status = status;
            this.retryAfter = retryAfter;
        }

        public int status() { return status; }

        /** The Retry-After header of a 429, else null. */
        public String retryAfter() { return retryAfter; }
    }
}
