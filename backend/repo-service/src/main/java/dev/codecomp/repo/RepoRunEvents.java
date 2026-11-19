package dev.codecomp.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.contracts.RunEvent;
import dev.codecomp.messaging.QueueListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.IOException;

/**
 * Learns the outcome of the runs this service submitted, from its own subscription to the run-events topic.
 * Only jobs in pending_runs count, and a pending row is deleted in the same transaction as the result is recorded, so
 * a duplicate event (delivery is at least once) finds nothing to do. Events about other jobs (snippet runs) are ignored.
 */
@Component
class RepoRunEvents {

    private static final Logger log = LoggerFactory.getLogger(RepoRunEvents.class);

    private final PendingRunRepository pending;
    private final RepoExerciseRepository repos;
    private final RepoScoring scoring;
    private final ObjectMapper json;

    RepoRunEvents(PendingRunRepository pending, RepoExerciseRepository repos, RepoScoring scoring, ObjectMapper json) {
        this.pending = pending;
        this.repos = repos;
        this.scoring = scoring;
        this.json = json;
    }

    @Transactional
    public void onRunEvent(String body) throws IOException {
        var event = json.readValue(body, RunEvent.class);
        if (event instanceof RunEvent.Started) return;
        var run = pending.findById(event.jobId()).orElse(null);
        if (run == null) return;
        if (event instanceof RunEvent.Completed done) {
            var exercise = repos.findById(run.getRepoExerciseId()).orElse(null);
            if (exercise != null) {
                switch (run.getPurpose()) {
                    case FIX -> scoring.recordTestRun(run.getUserId(), exercise, done.result(), run.getJobId());
                    case BASELINE -> {
                        int passing = (int) RepoScoring.parseTests(done.result().stdout()).stream().filter(RepoApi.TestCase::passed).count();
                        exercise.measured(passing);
                        repos.save(exercise);
                        log.info("Repo exercise '{}': {} hidden tests pass on the untouched code", exercise.getSlug(), passing);
                    }
                }
            }
        }
        // Completed or Failed: either way, nothing more to wait for
        pending.delete(run);
    }

    /** In its own class: the listener must call the handler through Spring's proxy, or @Transactional does nothing. */
    @Configuration
    static class Listener {
        @Bean
        @ConditionalOnProperty(name = "app.messaging.listen", havingValue = "true")
        QueueListener repoRunEventsListener(SqsClient sqs, @Value("${app.messaging.run-events-queue}") String queue, RepoRunEvents events) {
            return new QueueListener("run-events", sqs, queue, events::onRunEvent);
        }
    }
}
