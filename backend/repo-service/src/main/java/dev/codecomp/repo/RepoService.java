package dev.codecomp.repo;

import dev.codecomp.contracts.JobView;
import dev.codecomp.platform.RateLimiter;
import dev.codecomp.repo.PendingRun.Purpose;
import dev.codecomp.repo.RepoApi.IssueOutcome;
import dev.codecomp.repo.RepoApi.QuestionAnswer;
import dev.codecomp.repo.RepoApi.RepoSummary;
import dev.codecomp.repo.RepoApi.RepoView;
import dev.codecomp.repo.RepoApi.ReviewResult;
import dev.codecomp.repo.RepoApi.TestRunResult;
import dev.codecomp.repo.RepoApi.TestRunSummary;
import dev.codecomp.repo.RepoContent.RepoFile;
import dev.codecomp.repo.RepoSubmission.Kind;
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
    private final PendingRunRepository pending;
    private final RepoScoring scoring;
    private final RunClient runs;
    private final RateLimiter limiter;
    private final ReviewGrader grader;
    private final SyntaxAssistant assistant;

    RepoService(RepoExerciseRepository repos, RepoSubmissionRepository submissions, PendingRunRepository pending, RepoScoring scoring,
                RunClient runs, RateLimiter limiter, ReviewGrader grader, SyntaxAssistant assistant) {
        this.repos = repos;
        this.submissions = submissions;
        this.pending = pending;
        this.scoring = scoring;
        this.runs = runs;
        this.limiter = limiter;
        this.grader = grader;
        this.assistant = assistant;
    }

    public List<RepoSummary> list(UUID user) {
        var attempted = Set.copyOf(submissions.attemptedIds(user));
        var solved = Set.copyOf(submissions.solvedIds(user));
        var best = scoring.bestRuns(user);
        return repos.findAllByOrderBySortOrderAsc().stream().map(r -> {
            var c = scoring.content(r);
            return new RepoSummary(r.getSlug(), r.getTitle(), r.getLanguage(), r.getDifficulty(), r.getTags(), c.files().size(),
                    c.issues().size(), solved.contains(r.getId()) ? "SOLVED" : attempted.contains(r.getId()) ? "ATTEMPTED" : "NEW",
                    scoring.points(r, best.get(r.getId())), RepoPoints.max(r.getDifficulty()));
        }).toList();
    }

    public int totalPoints(UUID user) {
        var best = scoring.bestRuns(user);
        return repos.findAll().stream().filter(r -> best.containsKey(r.getId()))
                .mapToInt(r -> scoring.points(r, best.get(r.getId()))).sum();
    }

    public RepoView get(UUID user, String slug) {
        var r = find(slug);
        var c = scoring.content(r);
        boolean revealed = revealed(user, r);
        return new RepoView(r.getSlug(), r.getTitle(), r.getLanguage(), r.getDifficulty(), r.getTags(), r.getDescription(),
                c.files(), revealed, revealed ? c.issues() : null, questionsLeft(user, r),
                scoring.points(r, scoring.bestRuns(user).get(r.getId())), RepoPoints.max(r.getDifficulty()));
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
                        s.getMaxScore() > 0 && s.getScore() == s.getMaxScore(), scoring.tests(s.getDetail())))
                .toList();
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

    /**
     * Submits the hidden tests to run-service and returns its job for the browser to follow. The pending row is saved
     * first, under an id chosen here, so the result event cannot arrive before this service knows to expect it.
     * When the job is DONE, the browser fetches the scored result from {@link #testRun}.
     */
    public JobView runTests(UUID user, String slug, Map<String, String> edited) {
        var r = find(slug);
        var c = scoring.content(r);
        var known = c.files().stream().map(RepoFile::path).collect(Collectors.toSet());
        var unknown = edited.keySet().stream().filter(p -> !known.contains(p)).toList();
        if (!unknown.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown project files: " + unknown);

        var id = UUID.randomUUID();
        pending.save(new PendingRun(id, user, r.getId(), Purpose.FIX));
        try {
            return runs.submit(id, user, RepoExercise.projectLanguage(r.getLanguage()), RepoScoring.bundle(c, edited));
        } catch (RuntimeException e) {
            pending.deleteById(id);
            throw e;
        }
    }

    /**
     * One recorded test run, by its run-service job id. 404 until the result event has been processed, which is
     * usually well under a second after the job is DONE; the page retries briefly.
     */
    public TestRunResult testRun(UUID user, String slug, UUID jobId) {
        var r = find(slug);
        return submissions.findByJobIdAndUserId(jobId, user)
                .filter(s -> s.getRepoExerciseId().equals(r.getId()))
                .map(s -> scoring.scored(user, r, s))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This test run has not been recorded yet"));
    }

    private RepoExercise find(String slug) {
        return repos.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo exercise not found: " + slug));
    }
}
