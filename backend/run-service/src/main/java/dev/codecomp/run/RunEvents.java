package dev.codecomp.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.contracts.RunEvent;
import dev.codecomp.contracts.RunRequested;
import dev.codecomp.messaging.QueueListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Applies what the workers report to the jobs, and keeps the queue healthy. Every handler is idempotent: the
 * repository's conditional updates only move a job forward, so duplicate and out-of-order events change nothing.
 */
@Configuration
class RunEvents {

    private static final Logger log = LoggerFactory.getLogger(RunEvents.class);

    static final Duration RESEND_AFTER = Duration.ofSeconds(30);
    static final Duration GIVE_UP_AFTER = Duration.ofMinutes(15);
    static final Duration KEEP_FINISHED = Duration.ofDays(1);
    static final String STOPPED = "The run stopped unexpectedly. Try again.";

    private final RunJobRepository jobs;
    private final RunCache cache;
    private final JobService service;
    private final ObjectMapper json;

    RunEvents(RunJobRepository jobs, RunCache cache, JobService service, ObjectMapper json) {
        this.jobs = jobs;
        this.cache = cache;
        this.service = service;
        this.json = json;
    }

    /** A message from this service's subscription to the run-events topic. */
    void onRunEvent(String body) throws IOException {
        switch (json.readValue(body, RunEvent.class)) {
            case RunEvent.Started e -> jobs.markRunning(e.jobId(), e.worker(), Instant.now());
            case RunEvent.Completed e -> jobs.findById(e.jobId()).ifPresent(job -> {
                if (jobs.complete(job.getId(), service.write(RunResponse.of(e.result(), e.cached())), Instant.now()) == 1 && !e.cached())
                    cache.put(job.getLanguage(), job.getCode(), e.result());
            });
            case RunEvent.Failed e -> jobs.fail(e.jobId(), e.error(), Instant.now());
        }
    }

    /** A run request that workers received several times without finishing it (they crashed, or it crashes them). */
    void onDeadRequest(String body) throws IOException {
        var request = json.readValue(body, RunRequested.class);
        if (jobs.fail(request.jobId(), STOPPED, Instant.now()) == 1) log.warn("Run {} failed: no worker could finish it", request.jobId());
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    void resendUnsent() {
        service.resendUnsent(Instant.now().minus(RESEND_AFTER));
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void failForgotten() {
        int failed = jobs.failOlderThan(Instant.now().minus(GIVE_UP_AFTER), STOPPED, Instant.now());
        if (failed > 0) log.warn("Failed {} runs that never finished", failed);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    void deleteOldFinished() {
        jobs.deleteFinishedBefore(Instant.now().minus(KEEP_FINISHED));
    }

    @Bean
    @ConditionalOnProperty(name = "app.messaging.listen", havingValue = "true")
    QueueListener runEventsListener(SqsClient sqs, @Value("${app.messaging.run-events-queue}") String queue) {
        return new QueueListener("run-events", sqs, queue, this::onRunEvent);
    }

    @Bean
    @ConditionalOnProperty(name = "app.messaging.listen", havingValue = "true")
    QueueListener deadRequestsListener(SqsClient sqs, @Value("${app.messaging.run-requests-dlq}") String queue) {
        return new QueueListener("run-requests-dlq", sqs, queue, this::onDeadRequest);
    }
}
