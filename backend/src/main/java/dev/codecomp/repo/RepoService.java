package dev.codecomp.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.repo.RepoContent.RepoFile;
import dev.codecomp.repo.RepoSubmission.Kind;
import dev.codecomp.run.RateLimiter;
import dev.codecomp.run.RunService;
import dev.codecomp.sandbox.RunResult;
import dev.codecomp.web.Api.IssueOutcome;
import dev.codecomp.web.Api.QuestionAnswer;
import dev.codecomp.web.Api.RepoSummary;
import dev.codecomp.web.Api.RepoView;
import dev.codecomp.web.Api.ReviewResult;
import dev.codecomp.web.Api.RunResponse;
import dev.codecomp.web.Api.TestCase;
import dev.codecomp.web.Api.TestRunResult;
import dev.codecomp.web.Api.TestRunSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

@Service
public class RepoService {

    private static final Logger log = LoggerFactory.getLogger(RepoService.class);
    private static final Pattern PASS = Pattern.compile("^PASS (.+)$");
    private static final Pattern FAIL = Pattern.compile("^FAIL (.+?): (.*)$");
    static final int QUESTIONS_PER_EXERCISE = 5;
    private static final int MAX_DETAIL = 20000;

    private final RepoExerciseRepository repos;
    private final RepoSubmissionRepository submissions;
    private final RunService runs;
    private final RateLimiter limiter;
    private final ReviewGrader grader;
    private final SyntaxAssistant assistant;
    private final ObjectMapper json;

    RepoService(RepoExerciseRepository repos, RepoSubmissionRepository submissions, RunService runs, RateLimiter limiter,
                ReviewGrader grader, SyntaxAssistant assistant, ObjectMapper json) {
        this.repos = repos;
        this.submissions = submissions;
        this.runs = runs;
        this.limiter = limiter;
        this.grader = grader;
        this.assistant = assistant;
        this.json = json;
    }

    public List<RepoSummary> list(UUID user) {
        var attempted = Set.copyOf(submissions.attemptedIds(user));
        var solved = Set.copyOf(submissions.solvedIds(user));
        return repos.findAllByOrderBySortOrderAsc().stream().map(r -> {
            var c = content(r);
            return new RepoSummary(r.getSlug(), r.getTitle(), r.getLanguage(), r.getDifficulty(), r.getTags(), c.files().size(),
                    c.issues().size(), solved.contains(r.getId()) ? "SOLVED" : attempted.contains(r.getId()) ? "ATTEMPTED" : "NEW");
        }).toList();
    }

    public RepoView get(UUID user, String slug) {
        var r = find(slug);
        var c = content(r);
        boolean revealed = revealed(user, r);
        return new RepoView(r.getSlug(), r.getTitle(), r.getLanguage(), r.getDifficulty(), r.getTags(), r.getDescription(),
                c.files(), revealed, revealed ? c.issues() : null, questionsLeft(user, r));
    }

    /** Answers a general language question. The assistant is never shown the exercise, so it cannot give it away. */
    public QuestionAnswer ask(UUID user, String slug, String question) {
        var r = find(slug);
        if (questionsLeft(user, r) == 0)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You have used all " + QUESTIONS_PER_EXERCISE + " questions for this exercise");
        var answer = assistant.answer(r.getLanguage(), question);
        submissions.save(new RepoSubmission(user, r.getId(), Kind.ASK, 0, 0));
        return new QuestionAnswer(answer, questionsLeft(user, r));
    }

    private int questionsLeft(UUID user, RepoExercise r) {
        return (int) Math.max(0, QUESTIONS_PER_EXERCISE - submissions.countByUserIdAndRepoExerciseIdAndKind(user, r.getId(), Kind.ASK));
    }

    public List<TestRunSummary> testRuns(UUID user, String slug) {
        var r = find(slug);
        return submissions.findTop20ByUserIdAndRepoExerciseIdAndKindOrderByIdDesc(user, r.getId(), Kind.FIX).stream()
                .map(s -> new TestRunSummary(s.getId(), s.getCreatedAt(), s.getScore(), s.getMaxScore(),
                        s.getMaxScore() > 0 && s.getScore() == s.getMaxScore(), tests(s.getDetail())))
                .toList();
    }

    private List<TestCase> tests(String detail) {
        if (detail == null) return List.of();
        try {
            return json.readValue(detail, new TypeReference<List<TestCase>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** Reveals the planted issues and unlocks fixing without a graded review. */
    public RepoView reveal(UUID user, String slug) {
        var r = find(slug);
        if (!revealed(user, r)) submissions.save(new RepoSubmission(user, r.getId(), Kind.SKIP, 0, content(r).issues().size()));
        return get(user, slug);
    }

    private boolean revealed(UUID user, RepoExercise r) {
        return submissions.existsByUserIdAndRepoExerciseIdAndKindIn(user, r.getId(), List.of(Kind.REVIEW, Kind.SKIP));
    }

    public ReviewResult review(UUID user, String slug, String review) {
        var r = find(slug);
        var c = content(r);
        limiter.acquire(user);
        var grade = grader.grade(r, c, review);
        var verdicts = grade.issues() == null ? Map.<Integer, ReviewGrader.IssueVerdict>of()
                : grade.issues().stream().collect(Collectors.toMap(ReviewGrader.IssueVerdict::issueId, Function.identity(), (a, b) -> a));
        var outcomes = c.issues().stream().map(issue -> {
            var v = verdicts.get(issue.id());
            return v == null ? new IssueOutcome(issue, false, false, "")
                    : new IssueOutcome(issue, v.found(), v.found() && v.fixCorrect(), v.feedback());
        }).toList();
        int found = (int) outcomes.stream().filter(IssueOutcome::found).count();
        int fixes = (int) outcomes.stream().filter(IssueOutcome::fixCorrect).count();
        submissions.save(new RepoSubmission(user, r.getId(), Kind.REVIEW, found, outcomes.size()));
        return new ReviewResult(found, outcomes.size(), fixes, outcomes,
                grade.extraFindings() == null ? List.of() : grade.extraFindings(), grade.overallFeedback());
    }

    public TestRunResult runTests(UUID user, String slug, Map<String, String> edited) {
        var r = find(slug);
        var c = content(r);
        var known = c.files().stream().map(RepoFile::path).collect(Collectors.toSet());
        var unknown = edited.keySet().stream().filter(p -> !known.contains(p)).toList();
        if (!unknown.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown project files: " + unknown);

        var result = runs.run(user, RepoSeeder.projectLanguage(r.getLanguage()), bundle(c, edited));
        var tests = parseTests(result.stdout());
        int passed = (int) tests.stream().filter(TestCase::passed).count();
        boolean allPassed = !tests.isEmpty() && passed == tests.size() && result.exitCode() == 0;
        submissions.save(new RepoSubmission(user, r.getId(), Kind.FIX, allPassed ? tests.size() : passed, tests.size(), detail(tests)));
        return new TestRunResult(tests, passed, tests.size(), allPassed, result);
    }

    /** Runs every exercise's hidden tests against the original code (must fail) and the solution (must pass). */
    public void verifyAll() {
        for (var r : repos.findAllByOrderBySortOrderAsc()) {
            var c = content(r);
            var lang = RepoSeeder.projectLanguage(r.getLanguage());
            try {
                var original = parseTests(runs.runTrusted(lang, bundle(c, Map.of())).stdout());
                if (original.isEmpty() || original.stream().allMatch(TestCase::passed))
                    log.warn("Repo exercise '{}': hidden tests do not fail on the original code ({} tests)", r.getSlug(), original.size());
                if (c.solution().isEmpty()) continue;
                var fixes = c.solution().stream().collect(Collectors.toMap(RepoFile::path, RepoFile::content));
                RunResult solved = runs.runTrusted(lang, bundle(c, fixes));
                var tests = parseTests(solved.stdout());
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

    private String detail(List<TestCase> tests) {
        try {
            var detail = json.writeValueAsString(tests);
            return detail.length() <= MAX_DETAIL ? detail : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    static List<TestCase> parseTests(String stdout) {
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
    static String bundle(RepoContent c, Map<String, String> edited) {
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

    private RepoContent content(RepoExercise r) {
        try {
            return json.readValue(r.getContent(), RepoContent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt content for repo exercise " + r.getSlug(), e);
        }
    }

    private RepoExercise find(String slug) {
        return repos.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo exercise not found: " + slug));
    }
}
