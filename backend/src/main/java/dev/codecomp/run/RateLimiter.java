package dev.codecomp.run;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimiter {

    private static final long WINDOW_MS = 60_000;

    public static class RateLimitedException extends RuntimeException {
        private final long retryAfterSeconds;

        RateLimitedException(long retryAfterSeconds) {
            super("Run limit reached, try again in " + retryAfterSeconds + "s");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    private final int perMinute;
    private final Map<UUID, Deque<Long>> hits = new ConcurrentHashMap<>();

    RateLimiter(@Value("${app.runs-per-minute}") int perMinute) {
        this.perMinute = perMinute;
    }

    public void acquire(UUID user) {
        long now = System.currentTimeMillis();
        var q = hits.computeIfAbsent(user, k -> new ArrayDeque<>());
        synchronized (q) {
            evict(q, now);
            if (q.size() >= perMinute) throw new RateLimitedException(Math.max(1, (q.peekFirst() + WINDOW_MS - now) / 1000));
            q.addLast(now);
        }
    }

    @Scheduled(fixedDelay = WINDOW_MS)
    void cleanup() {
        long now = System.currentTimeMillis();
        hits.values().removeIf(q -> {
            synchronized (q) {
                evict(q, now);
                return q.isEmpty();
            }
        });
    }

    private static void evict(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
    }
}
