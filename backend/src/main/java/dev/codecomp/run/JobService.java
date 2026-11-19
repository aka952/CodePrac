package dev.codecomp.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.run.RunJob.Kind;
import dev.codecomp.run.RunJob.Status;
import dev.codecomp.sandbox.Sandbox;
import dev.codecomp.web.Api.JobView;
import dev.codecomp.web.Api.RunResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The run queue: the API side submits and reads jobs, the worker side claims and finishes them. */
@Service
public class JobService {

    static final int MAX_ATTEMPTS = 3;
    static final Duration STALE_AFTER = Duration.ofSeconds(60);
    static final Duration KEEP_FINISHED = Duration.ofDays(1);
    private static final List<Status> ACTIVE = List.of(Status.QUEUED, Status.RUNNING);

    private final RunJobRepository jobs;
    private final RunService runs;
    private final RateLimiter limiter;
    private final Sandbox sandbox;
    private final ObjectMapper json;
    private final int maxQueued;
    private final int maxActivePerUser;

    JobService(RunJobRepository jobs, RunService runs, RateLimiter limiter, Sandbox sandbox, ObjectMapper json,
               @Value("${app.queue.max-queued}") int maxQueued, @Value("${app.queue.max-active-per-user}") int maxActivePerUser) {
        this.jobs = jobs;
        this.runs = runs;
        this.limiter = limiter;
        this.sandbox = sandbox;
        this.json = json;
        this.maxQueued = maxQueued;
        this.maxActivePerUser = maxActivePerUser;
    }

    /** A snippet run: answered at once from the result cache when the same code ran before, otherwise queued. */
    public JobView submitSnippet(UUID user, String language, String code) {
        if (!sandbox.languages().contains(language)) throw new Sandbox.UnsupportedLanguageException(language);
        return runs.cached(language, code)
                .map(hit -> done(RunResponse.of(hit, true)))
                .orElseGet(() -> enqueue(user, Kind.SNIPPET, language, code, null));
    }

    /** A finished job that never went through the queue (a cache hit). */
    public JobView done(Object result) {
        return new JobView(null, Status.DONE.name(), null, json.valueToTree(result), null);
    }

    /**
     * Adds a job, after the backpressure checks. Order matters: the caps are checked before the rate limiter so a refused
     * job does not use up one of the user's runs for the minute.
     */
    @Transactional
    public JobView enqueue(UUID user, Kind kind, String language, String code, String repoSlug) {
        if (jobs.countByUserIdAndStatusIn(user, ACTIVE) >= maxActivePerUser)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You already have runs in progress. Wait for them to finish.");
        if (jobs.countByStatus(Status.QUEUED) >= maxQueued)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The run queue is full, try again in a moment");
        limiter.acquire(user);
        var job = jobs.save(new RunJob(user, kind, language, code, repoSlug));
        return view(job, queuePositions());
    }

    /** The job as its owner may see it. Someone else's job is reported as not found, so job ids reveal nothing. */
    @Transactional(readOnly = true)
    public JobView view(UUID user, UUID id) {
        return jobs.findById(id).filter(j -> j.getUserId().equals(user))
                .map(j -> view(j, queuePositions()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    /** Several jobs at once, with one query for the jobs and one for queue positions. Used to push live updates. */
    @Transactional(readOnly = true)
    public Map<UUID, JobView> views(Collection<UUID> ids) {
        var positions = queuePositions();
        var views = new HashMap<UUID, JobView>();
        for (var job : jobs.findAllById(ids)) views.put(job.getId(), view(job, positions));
        return views;
    }

    // ---- worker side ----

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

    // ---- helpers ----

    private List<UUID> queuePositions() {
        return jobs.queuedIds(PageRequest.of(0, maxQueued));
    }

    private JobView view(RunJob job, List<UUID> queued) {
        Integer position = null;
        if (job.getStatus() == Status.QUEUED) {
            int index = queued.indexOf(job.getId());
            position = index >= 0 ? index + 1 : null;
        }
        try {
            var result = job.getResult() == null ? null : json.readTree(job.getResult());
            return new JobView(job.getId(), job.getStatus().name(), position, result, job.getError());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt result for job " + job.getId(), e);
        }
    }
}
