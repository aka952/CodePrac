package dev.codecomp.content;

import dev.codecomp.contracts.RunResult.RunStatus;
import dev.codecomp.exercise.ExerciseSeeder;
import dev.codecomp.grading.Grader;
import dev.codecomp.grading.Grader.ExerciseType;
import dev.codecomp.sandbox.DockerSandbox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every snippet exercise runs, and every "predict the output" answer is what the code really prints. The worker used
 * to check this at startup and only log a warning; as a test, a wrong answer cannot be deployed.
 */
@EnabledIf("dev.codecomp.content.Sandboxes#dockerAvailable")
class SnippetAnswersTest {

    static DockerSandbox sandbox;

    @BeforeAll
    static void setUp() throws Exception {
        sandbox = Sandboxes.start();
    }

    @AfterAll
    static void tearDown() { sandbox.close(); }

    @Test
    void predictOutputAnswersMatchTheRealOutput() throws Exception {
        for (var e : new ExerciseSeeder(null, Sandboxes.config()).load()) {
            var run = sandbox.run(e.language(), e.code());
            assertThat(run.status()).as("%s: %s", e.slug(), run.stderr()).isNotEqualTo(RunStatus.SANDBOX_ERROR);
            if (e.type() == ExerciseType.PREDICT_OUTPUT)
                assertThat(Grader.normalizeOutput(run.stdout())).as(e.slug()).isEqualTo(Grader.normalizeOutput(e.answer()));
        }
    }
}
