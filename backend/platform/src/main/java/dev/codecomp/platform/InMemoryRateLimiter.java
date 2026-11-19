package dev.codecomp.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A sliding one-minute window per user, in this process's memory. See {@link RateLimiter} for the multi-instance caveat. */
@Component
public class InMemoryRateLimiter implements RateLimiter {

    private static final long WINDOW_MS = 60_000;

    private final int perMinute;
    private final Map<UUID, Deque<Long>> hits = new ConcurrentHashMap<>();

    InMemoryRateLimiter(@Value("${app.runs-per-minute:20}") int perMinute) {
        this.perMinute = perMinute;
    }

    @Override
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
