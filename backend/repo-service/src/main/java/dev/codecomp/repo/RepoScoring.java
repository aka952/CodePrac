package dev.codecomp.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.contracts.RunResult;
import dev.codecomp.repo.RepoApi.TestCase;
import dev.codecomp.repo.RepoApi.TestRunResult;
import dev.codecomp.repo.RepoSubmission.Kind;
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
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Repo exercise test runs: how a project is packed for the sandbox, how its output is read, and how a run is recorded and scored. */
@Service
public class RepoScoring {

    private static final Pattern PASS = Pattern.compile("^PASS (.+)$");
    private static final Pattern FAIL = Pattern.compile("^FAIL (.+?): (.*)$");
    private static final int MAX_DETAIL = 20000;

    private final RepoSubmissionRepository submissions;
    private final ObjectMapper json;

    RepoScoring(RepoSubmissionRepository submissions, ObjectMapper json) {
        this.submissions = submissions;
        this.json = json;
    }

    /** Records a finished test run in the user's history. Recording the same job again does nothing. */
    public void recordTestRun(UUID user, RepoExercise r, RunResult result, UUID jobId) {
        if (submissions.existsByJobId(jobId)) return;
        var tests = parseTests(result.stdout());
        int passed = (int) tests.stream().filter(TestCase::passed).count();
        boolean allPassed = !tests.isEmpty() && passed == tests.size() && result.exitCode() == 0;
        submissions.save(new RepoSubmission(user, r.getId(), Kind.FIX, allPassed ? tests.size() : passed, tests.size(), detail(tests), jobId));
    }

    /** A recorded test run with the points the user has earned on the exercise so far. */
    public TestRunResult scored(UUID user, RepoExercise r, RepoSubmission run) {
        var tests = tests(run.getDetail());
        return new TestRunResult(tests, run.getScore(), run.getMaxScore(), run.getMaxScore() > 0 && run.getScore() == run.getMaxScore(),
                points(r, bestRuns(user).get(r.getId())), RepoPoints.max(r.getDifficulty()));
    }

    /** The user's best test run per repo exercise id. */
    public Map<Long, RepoSubmission> bestRuns(UUID user) {
        return submissions.findByUserIdAndKind(user, Kind.FIX).stream()
                .filter(s -> s.getMaxScore() > 0)
                .collect(Collectors.toMap(RepoSubmission::getRepoExerciseId, Function.identity(),
                        (a, b) -> b.getScore() > a.getScore() ? b : a));
    }

    /** Until the baseline is measured, nothing but a full pass scores. */
    public int points(RepoExercise r, RepoSubmission best) {
        int baseline = r.getBaseline() == null ? Integer.MAX_VALUE : r.getBaseline();
        return best == null ? 0 : RepoPoints.earned(r.getDifficulty(), baseline, best.getScore(), best.getMaxScore());
    }

    public RepoContent content(RepoExercise r) {
        try {
            return json.readValue(r.getContent(), RepoContent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt content for repo exercise " + r.getSlug(), e);
        }
    }

    public List<TestCase> tests(String detail) {
        if (detail == null) return List.of();
        try {
            return json.readValue(detail, new TypeReference<List<TestCase>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
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
