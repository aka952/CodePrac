package dev.codecomp.run;

import dev.codecomp.exercise.ExerciseRepository;
import dev.codecomp.grading.Grader;
import dev.codecomp.grading.Grader.ExerciseType;
import dev.codecomp.repo.RepoService;
import dev.codecomp.sandbox.RunResult.RunStatus;
import dev.codecomp.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
class SandboxWarmer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxWarmer.class);

    private final boolean enabled;
    private final Sandbox sandbox;
    private final ExerciseRepository exercises;
    private final RunService runs;
    private final RepoService repos;

    SandboxWarmer(@Value("${app.warm-on-startup}") boolean enabled, Sandbox sandbox, ExerciseRepository exercises, RunService runs,
                  RepoService repos) {
        this.enabled = enabled;
        this.sandbox = sandbox;
        this.exercises = exercises;
        this.runs = runs;
        this.repos = repos;
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
        repos.verifyAll();
    }
}
