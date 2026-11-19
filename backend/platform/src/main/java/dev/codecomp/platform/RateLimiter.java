package dev.codecomp.platform;

import java.util.UUID;

/**
 * At most {@code app.runs-per-minute} costly actions (code runs, AI calls) per user per minute.
 *
 * <p>{@link InMemoryRateLimiter} counts in this process only, so with N instances of a service a user gets N times the
 * limit. Running more than one instance needs a shared counter, for example in Redis (ElastiCache).
 */
public interface RateLimiter {

    /** Counts one action, or throws {@link RateLimitedException} when the user is over the limit. */
    void acquire(UUID user);

    class RateLimitedException extends RuntimeException {
        private final long retryAfterSeconds;

        public RateLimitedException(long retryAfterSeconds) {
            super("Run limit reached, try again in " + retryAfterSeconds + "s");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() { return retryAfterSeconds; }
    }
}
