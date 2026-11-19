package dev.codecomp.run;

import dev.codecomp.exercise.ExerciseRepository;
import dev.codecomp.grading.Grader;
import dev.codecomp.grading.Grader.ExerciseType;
import dev.codecomp.repo.RepoContent.RepoFile;
import dev.codecomp.repo.RepoExercise;
import dev.codecomp.repo.RepoExerciseRepository;
import dev.codecomp.repo.RepoScoring;
import dev.codecomp.run.RunResults.TestCase;
import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.RunResult.RunStatus;
import dev.codecomp.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/** Pulls the sandbox images, runs every exercise to fill the cache, and checks the repo exercises. */
@Component
@Order(2)
class SandboxWarmer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxWarmer.class);

    private final boolean enabled;
    private final Sandbox sandbox;
    private final ExerciseRepository exercises;
    private final RunService runs;
    private final RepoExerciseRepository repos;
    private final RepoScoring scoring;

    SandboxWarmer(@Value("${app.warm-on-startup}") boolean enabled, Sandbox sandbox, ExerciseRepository exercises, RunService runs,
                  RepoExerciseRepository repos, RepoScoring scoring) {
        this.enabled = enabled;
        this.sandbox = sandbox;
        this.exercises = exercises;
        this.runs = runs;
        this.repos = repos;
        this.scoring = scoring;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (enabled) Thread.ofVirtual().name("sandbox-warmer").start(this::warm);
    }

    void warm() {
        sandbox.prepare();
        int mismatches = 0;
        var all = exercises.findAllByOrderBySortOrderAsc();
        for (var e : all) {
            try {
                var r = runs.runTrusted(e.getLanguage(), e.getCode());
                if (r.status() == RunStatus.SANDBOX_ERROR) {
                    log.warn("Sandbox error while warming '{}': {}", e.getSlug(), r.stderr());
                } else if (e.getType() == ExerciseType.PREDICT_OUTPUT
                        && !Grader.normalizeOutput(e.getAnswer()).equals(Grader.normalizeOutput(r.stdout()))) {
                    mismatches++;
                    log.warn("Exercise '{}' expected answer does not match real output:\n{}", e.getSlug(), r.stdout());
                }
            } catch (RuntimeException ex) {
                log.warn("Could not warm '{}': {}", e.getSlug(), ex.getMessage());
            }
        }
        log.info("Sandbox warm-up finished: {} exercises, {} answer mismatches", all.size(), mismatches);
        verifyRepos();
    }

    /** Runs every exercise's hidden tests against the original code (must fail) and the solution (must pass). */
    private void verifyRepos() {
        for (var r : repos.findAllByOrderBySortOrderAsc()) {
            var c = scoring.content(r);
            var lang = RepoExercise.projectLanguage(r.getLanguage());
            try {
                var original = RepoScoring.parseTests(runs.runTrusted(lang, RepoScoring.bundle(c, Map.of())).stdout());
                if (original.isEmpty() || original.stream().allMatch(TestCase::passed))
                    log.warn("Repo exercise '{}': hidden tests do not fail on the original code ({} tests)", r.getSlug(), original.size());
                if (c.solution().isEmpty()) continue;
                var fixes = c.solution().stream().collect(Collectors.toMap(RepoFile::path, RepoFile::content));
                RunResult solved = runs.runTrusted(lang, RepoScoring.bundle(c, fixes));
                var tests = RepoScoring.parseTests(solved.stdout());
                var failing = tests.stream().filter(t -> !t.passed()).map(TestCase::name).toList();
                if (tests.isEmpty() || !failing.isEmpty() || solved.exitCode() != 0)
                    log.warn("Repo exercise '{}': reference solution does not pass ({}): failing={} stderr={}",
                            r.getSlug(), solved.status(), failing, solved.stderr());
                else log.info("Repo exercise '{}' verified: {} hidden tests", r.getSlug(), tests.size());
            } catch (RuntimeException e) {
                log.warn("Could not verify repo exercise '{}': {}", r.getSlug(), e.getMessage());
            }
        }
    }
}
