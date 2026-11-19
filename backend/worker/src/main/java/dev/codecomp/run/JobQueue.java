package dev.codecomp.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** The worker side of the run queue: claims jobs, finishes them, and recovers jobs from workers that died. */
@Service
public class JobQueue {

    static final int MAX_ATTEMPTS = 3;
    static final Duration STALE_AFTER = Duration.ofSeconds(60);
    static final Duration KEEP_FINISHED = Duration.ofDays(1);

    private final RunJobRepository jobs;
    private final ObjectMapper json;

    JobQueue(RunJobRepository jobs, ObjectMapper json) {
        this.jobs = jobs;
        this.json = json;
    }

    /** Claims the oldest queued job. A conditional update makes the claim atomic, so concurrent workers never share a job. */
    @Transactional
    public Optional<RunJob> claimNext(String worker) {
        for (var id : jobs.queuedIds(PageRequest.of(0, 10))) {
            if (jobs.claim(id, worker, Instant.now()) == 1) return jobs.findById(id);
        }
        return Optional.empty();
    }

    /** False when the job is no longer this worker's (it was presumed dead and the job handed to another worker). */
    @Transactional
    public boolean complete(UUID id, String worker, Object result) {
        try {
            return jobs.complete(id, worker, json.writeValueAsString(result), Instant.now()) == 1;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public void fail(UUID id, String worker, String error) {
        jobs.fail(id, worker, error.length() > 1000 ? error.substring(0, 1000) : error, Instant.now());
    }

    @Transactional
    public void release(UUID id, String worker) {
        jobs.release(id, worker);
    }

    @Transactional
    public void heartbeat(Collection<UUID> ids, String worker) {
        if (!ids.isEmpty()) jobs.heartbeat(ids, worker, Instant.now());
    }

    /** Requeues jobs whose worker stopped sending heartbeats. Returns how many were requeued. */
    @Transactional
    public int recoverStale() {
        var cutoff = Instant.now().minus(STALE_AFTER);
        jobs.failStale(cutoff, MAX_ATTEMPTS, "The run stopped unexpectedly. Try again.", Instant.now());
        return jobs.requeueStale(cutoff, MAX_ATTEMPTS);
    }

    @Transactional
    public int deleteOldFinished() {
        return jobs.deleteFinishedBefore(Instant.now().minus(KEEP_FINISHED));
    }
}
