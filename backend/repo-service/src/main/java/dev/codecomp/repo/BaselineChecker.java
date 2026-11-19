package dev.codecomp.repo;

import dev.codecomp.repo.PendingRun.Purpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Measures each repo exercise's baseline (how many hidden tests pass on the untouched code) by running it through
 * run-service like any other run, as the system rather than a user. Before the split, the worker measured it at
 * startup and the API read it from the shared result cache; now the cache is run-service's, so this service asks.
 * Runs until every exercise has a baseline, and again whenever the seed task changes an exercise's content.
 */
@Component
@ConditionalOnProperty(name = "app.baseline.enabled", havingValue = "true")
class BaselineChecker {

    private static final Logger log = LoggerFactory.getLogger(BaselineChecker.class);
    static final Duration RETRY_AFTER = Duration.ofMinutes(5);

    private final RepoExerciseRepository repos;
    private final PendingRunRepository pending;
    private final RepoScoring scoring;
    private final RunClient runs;

    BaselineChecker(RepoExerciseRepository repos, PendingRunRepository pending, RepoScoring scoring, RunClient runs) {
        this.repos = repos;
        this.pending = pending;
        this.scoring = scoring;
        this.runs = runs;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    void check() {
        var since = Instant.now().minus(RETRY_AFTER);
        for (var r : repos.findAllByOrderBySortOrderAsc()) {
            if (r.getBaseline() != null || pending.existsByRepoExerciseIdAndPurposeAndCreatedAtAfter(r.getId(), Purpose.BASELINE, since)) continue;
            var id = UUID.randomUUID();
            pending.save(new PendingRun(id, null, r.getId(), Purpose.BASELINE));
            try {
                runs.submit(id, null, RepoExercise.projectLanguage(r.getLanguage()), RepoScoring.bundle(scoring.content(r), Map.of()));
            } catch (RuntimeException e) {
                pending.deleteById(id);
                log.warn("Could not measure the baseline of '{}' yet: {}", r.getSlug(), e.getMessage());
                return;
            }
        }
    }
}
