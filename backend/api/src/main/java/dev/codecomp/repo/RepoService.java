package dev.codecomp.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codecomp.grading.Points;
import dev.codecomp.repo.RepoContent.RepoFile;
import dev.codecomp.repo.RepoSubmission.Kind;
import dev.codecomp.run.JobService;
import dev.codecomp.run.RateLimiter;
import dev.codecomp.run.RunCache;
import dev.codecomp.run.RunJob;
import dev.codecomp.web.Api.IssueOutcome;
import dev.codecomp.web.Api.JobView;
import dev.codecomp.web.Api.QuestionAnswer;
import dev.codecomp.web.Api.RepoSummary;
import dev.codecomp.web.Api.RepoView;
import dev.codecomp.web.Api.ReviewResult;
import dev.codecomp.run.RunResults.RunResponse;
import dev.codecomp.run.RunResults.TestCase;
import dev.codecomp.web.Api.TestRunSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RepoService {

    static final int QUESTIONS_PER_EXERCISE = 5;

    private final RepoExerciseRepository repos;
    private final RepoSubmissionRepository submissions;
    private final RunCache cache;
    private final RepoScoring scoring;
    private final JobService jobs;
    private final RateLimiter limiter;
    private final ReviewGrader grader;
    private final SyntaxAssistant assistant;
    private final ObjectMapper json;

    RepoService(RepoExerciseRepository repos, RepoSubmissionRepository submissions, RunCache cache, RepoScoring scoring, JobService jobs,
                RateLimiter limiter, ReviewGrader grader, SyntaxAssistant assistant, ObjectMapper json) {
        this.repos = repos;
        this.submissions = submissions;
        this.cache = cache;
        this.scoring = scoring;
        this.jobs = jobs;
        this.limiter = limiter;
        this.grader = grader;
        this.assistant = assistant;
        this.json = json;
    }

    public List<RepoSummary> list(UUID user) {
        var attempted = Set.copyOf(submissions.attemptedIds(user));
        var solved = Set.copyOf(submissions.solvedIds(user));
        var best = scoring.bestRuns(user);
        return repos.findAllByOrderBySortOrderAsc().stream().map(r -> {
            var c = scoring.content(r);
            return new RepoSummary(r.getSlug(), r.getTitle(), r.getLanguage(), r.getDifficulty(), r.getTags(), c.files().size(),
                    c.issues().size(), solved.contains(r.getId()) ? "SOLVED" : attempted.contains(r.getId()) ? "ATTEMPTED" : "NEW",
                    scoring.points(r, c, best.get(r.getId())), Points.repoMax(r.getDifficulty()));
        }).toList();
    }

    public int totalPoints(UUID user) {
        var best = scoring.bestRuns(user);
        return repos.findAll().stream().filter(r -> best.containsKey(r.getId()))
                .mapToInt(r -> scoring.points(r, scoring.content(r), best.get(r.getId()))).sum();
    }

    public RepoView get(UUID user, String slug) {
        var r = find(slug);
        var c = scoring.content(r);
        boolean revealed = revealed(user, r);
        return new RepoView(r.getSlug(), r.getTitle(), r.getLanguage(), r.getDifficulty(), r.getTags(), r.getDescription(),
                c.files(), revealed, revealed ? c.issues() : null, questionsLeft(user, r),
                scoring.points(r, c, scoring.bestRuns(user).get(r.getId())), Points.repoMax(r.getDifficulty()));
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
        if (!revealed(user, r)) submissions.save(new RepoSubmission(user, r.getId(), Kind.SKIP, 0, scoring.content(r).issues().size()));
        return get(user, slug);
    }

    private boolean revealed(UUID user, RepoExercise r) {
        return submissions.existsByUserIdAndRepoExerciseIdAndKindIn(user, r.getId(), List.of(Kind.REVIEW, Kind.SKIP));
    }

    public ReviewResult review(UUID user, String slug, String review) {
        var r = find(slug);
        var c = scoring.content(r);
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

    /** Recorded at once when the same code ran before; otherwise queued, and recorded by the worker. */
    public JobView runTests(UUID user, String slug, Map<String, String> edited) {
        var r = find(slug);
        var c = scoring.content(r);
        var known = c.files().stream().map(RepoFile::path).collect(Collectors.toSet());
        var unknown = edited.keySet().stream().filter(p -> !known.contains(p)).toList();
        if (!unknown.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown project files: " + unknown);

        var language = RepoExercise.projectLanguage(r.getLanguage());
        var bundle = RepoScoring.bundle(c, edited);
        return cache.get(language, bundle)
                .map(hit -> jobs.done(scoring.completeTestRun(user, slug, RunResponse.of(hit, true))))
                .orElseGet(() -> jobs.enqueue(user, RunJob.Kind.REPO_TEST, language, bundle, slug));
    }

    private RepoExercise find(String slug) {
        return repos.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo exercise not found: " + slug));
    }
}
