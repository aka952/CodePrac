package dev.codecomp.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.grading.Points;
import dev.codecomp.repo.RepoSubmission.Kind;
import dev.codecomp.run.RunCache;
import dev.codecomp.run.RunResults.RunResponse;
import dev.codecomp.run.RunResults.TestCase;
import dev.codecomp.run.RunResults.TestRunResult;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Repo exercise test runs: how a project is packed for the sandbox, how its output is read, and how a run is recorded
 * and scored. Shared by the API (cache hits, points) and the worker (finished runs, startup verification).
 */
@Service
public class RepoScoring {

    private static final Pattern PASS = Pattern.compile("^PASS (.+)$");
    private static final Pattern FAIL = Pattern.compile("^FAIL (.+?): (.*)$");
    private static final int MAX_DETAIL = 20000;

    private final RepoExerciseRepository repos;
    private final RepoSubmissionRepository submissions;
    private final RunCache cache;
    private final ObjectMapper json;
    private final Map<String, Integer> baselines = new ConcurrentHashMap<>();

    RepoScoring(RepoExerciseRepository repos, RepoSubmissionRepository submissions, RunCache cache, ObjectMapper json) {
        this.repos = repos;
        this.submissions = submissions;
        this.cache = cache;
        this.json = json;
    }

    /** Turns a finished run into test results, records it in the user's history and scores it. */
    public TestRunResult completeTestRun(UUID user, String slug, RunResponse result) {
        var r = repos.findBySlug(slug).orElseThrow(() -> new IllegalStateException("Unknown repo exercise " + slug));
        var c = content(r);
        var tests = parseTests(result.stdout());
        int passed = (int) tests.stream().filter(TestCase::passed).count();
        boolean allPassed = !tests.isEmpty() && passed == tests.size() && result.exitCode() == 0;
        submissions.save(new RepoSubmission(user, r.getId(), Kind.FIX, allPassed ? tests.size() : passed, tests.size(), detail(tests)));
        return new TestRunResult(tests, passed, tests.size(), allPassed, result,
                points(r, c, bestRuns(user).get(r.getId())), Points.repoMax(r.getDifficulty()));
    }

    /** The user's best test run per repo exercise id. */
    public Map<Long, RepoSubmission> bestRuns(UUID user) {
        return submissions.findByUserIdAndKind(user, Kind.FIX).stream()
                .filter(s -> s.getMaxScore() > 0)
                .collect(Collectors.toMap(RepoSubmission::getRepoExerciseId, Function.identity(),
                        (a, b) -> b.getScore() > a.getScore() ? b : a));
    }

    public int points(RepoExercise r, RepoContent c, RepoSubmission best) {
        return best == null ? 0 : Points.repo(r.getDifficulty(), baseline(r, c), best.getScore(), best.getMaxScore());
    }

    /**
     * How many hidden tests pass on the untouched code; those earn no points. Read from the run cache, which the
     * worker fills at startup. Until it has, nothing but a full pass scores.
     */
    private int baseline(RepoExercise r, RepoContent c) {
        var known = baselines.get(r.getSlug());
        if (known != null) return known;
        // the API never runs code itself; the worker's startup check puts this result in the cache
        var result = cache.get(RepoExercise.projectLanguage(r.getLanguage()), bundle(c, Map.of()));
        if (result.isEmpty()) return Integer.MAX_VALUE;
        int passed = (int) parseTests(result.get().stdout()).stream().filter(TestCase::passed).count();
        baselines.put(r.getSlug(), passed);
        return passed;
    }

    public RepoContent content(RepoExercise r) {
        try {
            return json.readValue(r.getContent(), RepoContent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt content for repo exercise " + r.getSlug(), e);
        }
    }

    private String detail(List<TestCase> tests) {
        try {
            var detail = json.writeValueAsString(tests);
            return detail.length() <= MAX_DETAIL ? detail : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static List<TestCase> parseTests(String stdout) {
        var tests = new ArrayList<TestCase>();
        for (var line : stdout.split("\n")) {
            var pass = PASS.matcher(line);
            if (pass.matches()) {
                tests.add(new TestCase(pass.group(1).strip(), true, null));
                continue;
            }
            var fail = FAIL.matcher(line);
            if (fail.matches()) tests.add(new TestCase(fail.group(1).strip(), false, fail.group(2).strip()));
        }
        return tests;
    }

    /** Project files (with the user's edits applied) plus hidden tests, as a deterministic base64 zip so identical code hits the run cache. */
    public static String bundle(RepoContent c, Map<String, String> edited) {
        var files = new TreeMap<String, String>();
        c.files().forEach(f -> files.put(f.path(), edited.getOrDefault(f.path(), f.content())));
        c.tests().forEach(f -> files.put(f.path(), f.content()));
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, UTF_8)) {
            for (var e : files.entrySet()) {
                var entry = new ZipEntry(e.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(e.getValue().getBytes(UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
