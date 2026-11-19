package dev.codecomp.content;

import dev.codecomp.repo.RepoApi.TestCase;
import dev.codecomp.repo.RepoContent;
import dev.codecomp.repo.RepoExercise;
import dev.codecomp.repo.RepoScoring;
import dev.codecomp.repo.RepoSeeder;
import dev.codecomp.sandbox.DockerSandbox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Every repo exercise must be honest: hidden tests fail on the original code and all pass on the reference solution. */
@EnabledIf("dev.codecomp.content.Sandboxes#dockerAvailable")
class RepoExercisesTest {

    static DockerSandbox sandbox;

    @BeforeAll
    static void setUp() throws Exception {
        sandbox = Sandboxes.start();
    }

    @AfterAll
    static void tearDown() { sandbox.close(); }

    static List<RepoExercise.Def> defs() throws Exception {
        return new RepoSeeder(null, Sandboxes.config(), null).load();
    }

    static RepoContent content(RepoExercise.Def d) {
        return new RepoContent(d.files(), d.tests(), d.issues(), d.solution());
    }

    @Test
    void hiddenTestsFailOnTheOriginalCode() throws Exception {
        for (var d : defs()) {
            var run = sandbox.run(RepoExercise.projectLanguage(d.language()), RepoScoring.bundle(content(d), Map.of()));
            var tests = RepoScoring.parseTests(run.stdout());
            assertThat(tests).as("%s: tests should run (stderr: %s)", d.slug(), run.stderr()).isNotEmpty();
            assertThat(tests).as(d.slug()).anyMatch(t -> !t.passed());
        }
    }

    @Test
    void referenceSolutionPassesEveryHiddenTest() throws Exception {
        for (var d : defs()) {
            var fixes = d.solution().stream().collect(Collectors.toMap(RepoContent.RepoFile::path, RepoContent.RepoFile::content));
            var run = sandbox.run(RepoExercise.projectLanguage(d.language()), RepoScoring.bundle(content(d), fixes));
            var tests = RepoScoring.parseTests(run.stdout());
            assertThat(tests).as("%s: tests should run (stderr: %s)", d.slug(), run.stderr()).isNotEmpty();
            assertThat(tests).as("%s: %s", d.slug(), run.stdout()).allMatch(TestCase::passed);
            assertThat(run.exitCode()).isZero();
        }
    }
}
