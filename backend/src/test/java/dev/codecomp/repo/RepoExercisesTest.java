package dev.codecomp.repo;

import dev.codecomp.sandbox.DockerSandbox;
import dev.codecomp.sandbox.Sandbox;
import dev.codecomp.sandbox.SandboxConfig;
import dev.codecomp.web.Api.TestCase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Every repo exercise must be honest: hidden tests fail on the original code and all pass on the reference solution. */
@EnabledIf("dockerAvailable")
class RepoExercisesTest {

    static DockerSandbox sandbox;

    static boolean dockerAvailable() {
        try {
            return new ProcessBuilder("docker", "info").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        var env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml")).forEach(env.getPropertySources()::addLast);
        sandbox = new DockerSandbox(Binder.get(env).bind("sandbox", SandboxConfig.class).get());
        sandbox.prepare();
    }

    @AfterAll
    static void tearDown() { sandbox.close(); }

    static List<RepoSeeder.Def> defs() throws Exception {
        Sandbox languages = new Sandbox() {
            public Set<String> languages() { return Set.of("java-project"); }
            public dev.codecomp.sandbox.RunResult run(String l, String c) { throw new UnsupportedOperationException(); }
        };
        return new RepoSeeder(null, languages, null).load();
    }

    static RepoContent content(RepoSeeder.Def d) {
        return new RepoContent(d.files(), d.tests(), d.issues(), d.solution());
    }

    @Test
    void hiddenTestsFailOnTheOriginalCode() throws Exception {
        for (var d : defs()) {
            var run = sandbox.run(RepoSeeder.projectLanguage(d.language()), RepoService.bundle(content(d), java.util.Map.of()));
            var tests = RepoService.parseTests(run.stdout());
            assertThat(tests).as("%s: tests should run (stderr: %s)", d.slug(), run.stderr()).isNotEmpty();
            assertThat(tests).as(d.slug()).anyMatch(t -> !t.passed());
        }
    }

    @Test
    void referenceSolutionPassesEveryHiddenTest() throws Exception {
        for (var d : defs()) {
            var fixes = d.solution().stream().collect(Collectors.toMap(RepoContent.RepoFile::path, RepoContent.RepoFile::content));
            var run = sandbox.run(RepoSeeder.projectLanguage(d.language()), RepoService.bundle(content(d), fixes));
            var tests = RepoService.parseTests(run.stdout());
            assertThat(tests).as("%s: tests should run (stderr: %s)", d.slug(), run.stderr()).isNotEmpty();
            assertThat(tests).as("%s: %s", d.slug(), run.stdout()).allMatch(TestCase::passed);
            assertThat(run.exitCode()).isZero();
        }
    }
}
