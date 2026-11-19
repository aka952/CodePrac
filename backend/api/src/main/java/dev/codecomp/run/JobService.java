package dev.codecomp.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.run.RunJob.Kind;
import dev.codecomp.run.RunJob.Status;
import dev.codecomp.sandbox.Sandbox;
import dev.codecomp.sandbox.SandboxConfig;
import dev.codecomp.web.Api.JobView;
import dev.codecomp.run.RunResults.RunResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The API side of the run queue: submits jobs and reads them. Workers claim and finish them (JobQueue in the worker). */
@Service
public class JobService {

    private static final List<Status> ACTIVE = List.of(Status.QUEUED, Status.RUNNING);

    private final RunJobRepository jobs;
    private final RunCache cache;
    private final RateLimiter limiter;
    private final SandboxConfig sandbox;
    private final ObjectMapper json;
    private final int maxQueued;
    private final int maxActivePerUser;

    JobService(RunJobRepository jobs, RunCache cache, RateLimiter limiter, SandboxConfig sandbox, ObjectMapper json,
               @Value("${app.queue.max-queued}") int maxQueued, @Value("${app.queue.max-active-per-user}") int maxActivePerUser) {
        this.jobs = jobs;
        this.cache = cache;
        this.limiter = limiter;
        this.sandbox = sandbox;
        this.json = json;
        this.maxQueued = maxQueued;
        this.maxActivePerUser = maxActivePerUser;
    }

    /** A snippet run: answered at once from the result cache when the same code ran before, otherwise queued. */
    public JobView submitSnippet(UUID user, String language, String code) {
        if (!sandbox.languages().containsKey(language)) throw new Sandbox.UnsupportedLanguageException(language);
        return cache.get(language, code)
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
