package dev.codecomp.run;

import dev.codecomp.contracts.JobView;
import dev.codecomp.contracts.SandboxConfig;
import dev.codecomp.platform.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@RestController
class RunController {

    record RunRequest(@NotBlank @Size(max = 20) String language, @NotBlank @Size(max = 20000) String code) {}

    /** From another service. {@code userId} is null for the system's own runs. Bundles are well under SQS's 256 KB. */
    record InternalRunRequest(@NotNull UUID id, UUID userId, @NotBlank @Size(max = 30) String language,
                              @NotBlank @Size(max = 200_000) String code) {}

    private final JobService jobs;
    private final JobEvents events;
    private final SandboxConfig sandbox;

    RunController(JobService jobs, JobEvents events, SandboxConfig sandbox) {
        this.jobs = jobs;
        this.events = events;
        this.sandbox = sandbox;
    }

    @GetMapping("/api/languages")
    Set<String> languages() { return new TreeSet<>(sandbox.languages().keySet()); }

    /** 200 with the result when the same code ran before; otherwise 202 with a queued job to follow. */
    @PostMapping("/api/runs")
    ResponseEntity<JobView> run(@CurrentUser UUID user, @Valid @RequestBody RunRequest req) {
        return submitted(jobs.submitSnippet(user, req.language(), req.code()));
    }

    @GetMapping("/api/jobs/{id}")
    JobView job(@CurrentUser UUID user, @PathVariable("id") UUID id) {
        return jobs.view(user, id);
    }

    /** Server-Sent Events: one "job" event per change of the job, ending once it is DONE or FAILED. */
    @GetMapping(path = "/api/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> jobEvents(@CurrentUser UUID user, @PathVariable("id") UUID id) {
        // X-Accel-Buffering stops nginx from holding events back until the stream ends
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).header("X-Accel-Buffering", "no").body(events.watch(user, id));
    }

    /** Reachable only inside the network (see app.security.internal-paths). */
    @PostMapping("/internal/runs")
    ResponseEntity<JobView> internalRun(@Valid @RequestBody InternalRunRequest req) {
        return submitted(jobs.submitInternal(req.id(), req.userId(), req.language(), req.code()));
    }

    private static ResponseEntity<JobView> submitted(JobView job) {
        return ResponseEntity.status("DONE".equals(job.status()) ? HttpStatus.OK : HttpStatus.ACCEPTED).body(job);
    }
}
