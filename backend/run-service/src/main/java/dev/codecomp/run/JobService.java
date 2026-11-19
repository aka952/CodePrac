package dev.codecomp.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.contracts.JobView;
import dev.codecomp.contracts.RunEvent;
import dev.codecomp.contracts.RunRequested;
import dev.codecomp.contracts.SandboxConfig;
import dev.codecomp.messaging.MessageSender;
import dev.codecomp.platform.RateLimiter;
import dev.codecomp.run.RunJob.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Accepts runs. A repeat of code that ran before is answered from the cache; anything else becomes a QUEUED row plus
 * a message on the run-requests queue, which a worker takes. Workers report back through events ({@link RunEvents}).
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final List<Status> ACTIVE = List.of(Status.QUEUED, Status.RUNNING);

    private final RunJobRepository jobs;
    private final RunCache cache;
    private final RateLimiter limiter;
    private final SandboxConfig sandbox;
    private final MessageSender messages;
    private final Queues queues;
    private final ObjectMapper json;
    private final int maxQueued;
    private final int maxActivePerUser;

    JobService(RunJobRepository jobs, RunCache cache, RateLimiter limiter, SandboxConfig sandbox, MessageSender messages, Queues queues,
               ObjectMapper json, @Value("${app.queue.max-queued}") int maxQueued, @Value("${app.queue.max-active-per-user}") int maxActivePerUser) {
        this.jobs = jobs;
        this.cache = cache;
        this.limiter = limiter;
        this.sandbox = sandbox;
        this.messages = messages;
        this.queues = queues;
        this.json = json;
        this.maxQueued = maxQueued;
        this.maxActivePerUser = maxActivePerUser;
    }

    /** A snippet run from the browser: answered at once from the cache when the same code ran before, otherwise queued. */
    public JobView submitSnippet(UUID user, String language, String code) {
        checkLanguage(language);
        return cache.get(language, code)
                .map(hit -> new JobView(null, Status.DONE.name(), null, json.valueToTree(RunResponse.of(hit, true)), null))
                .orElseGet(() -> enqueue(UUID.randomUUID(), user, language, code));
    }

    /**
     * A run for another service (repo-service's test runs), under the id that service chose, so it can record the job
     * before any event about it can arrive. Every outcome, a cache hit included, is announced as a run event, so the
     * caller has one way to learn the result. {@code user} is null for the system's own runs, which skip the user limits.
     * Submitting the same id twice returns the first job.
     */
    public JobView submitInternal(UUID id, UUID user, String language, String code) {
        checkLanguage(language);
        var existing = jobs.findById(id);
        if (existing.isPresent()) return view(existing.get(), queuePositions());
        var hit = cache.get(language, code);
        if (hit.isEmpty()) return enqueue(id, user, language, code);

        var job = jobs.save(RunJob.done(id, user, language, code, write(RunResponse.of(hit.get(), true))));
        try {
            messages.publish(queues.runEventsTopic, new RunEvent.Completed(id, hit.get(), true));
        } catch (RuntimeException e) {
            // without the event the caller would wait forever: undo, and let it retry
            jobs.deleteById(id);
            log.warn("Could not announce cached run {}: {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not start the run, try again in a moment");
        }
        return view(job, List.of());
    }

    /**
     * Saves the job, then sends its message. They cannot be one transaction (a database and a queue), so a failed send
     * leaves the job QUEUED with no sent_at, and {@link #resendUnsent} sends it again. Order matters in the checks:
     * the caps are checked before the rate limiter so a refused run does not use up one of the user's runs for the minute.
     */
    JobView enqueue(UUID id, UUID user, String language, String code) {
        if (user != null) {
            if (jobs.countByUserIdAndStatusIn(user, ACTIVE) >= maxActivePerUser)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You already have runs in progress. Wait for them to finish.");
            if (jobs.countByStatus(Status.QUEUED) >= maxQueued)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The run queue is full, try again in a moment");
            limiter.acquire(user);
        }
        var job = jobs.save(new RunJob(id, user, language, code));
        send(job);
        return view(job, queuePositions());
    }

    /**
     * Sends the queued runs whose message never got out. If the first send did succeed and only marking it failed, the
     * code runs twice; harmless, because the second Completed event finds the job already DONE and changes nothing.
     */
    void resendUnsent(Instant cutoff) {
        for (var id : jobs.unsentBefore(cutoff)) jobs.findById(id).ifPresent(this::send);
    }

    private void send(RunJob job) {
        try {
            messages.send(queues.runRequests, new RunRequested(job.getId(), job.getLanguage(), job.getCode()));
            jobs.markSent(job.getId(), Instant.now());
        } catch (RuntimeException e) {
            log.warn("Could not queue run {}, it will be sent again: {}", job.getId(), e.getMessage());
        }
    }

    /** The job as its owner may see it. Someone else's job is reported as not found, so job ids reveal nothing. */
    @Transactional(readOnly = true)
    public JobView view(UUID user, UUID id) {
        return jobs.findById(id).filter(j -> user.equals(j.getUserId()))
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

    private void checkLanguage(String language) {
        if (!sandbox.languages().containsKey(language))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported language: " + language);
    }

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

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
