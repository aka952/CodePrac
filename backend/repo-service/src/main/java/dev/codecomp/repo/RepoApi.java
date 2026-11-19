package dev.codecomp.repo;

import dev.codecomp.repo.RepoContent.Issue;
import dev.codecomp.repo.RepoContent.RepoFile;
import dev.codecomp.repo.ReviewGrader.ExtraFinding;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The JSON this service sends and receives. */
public interface RepoApi {

    record RepoSummary(String slug, String title, String language, Difficulty difficulty, List<String> tags, int files, int issues,
                       String status, int points, int maxPoints) {}

    /** {@code issues} is only present once revealed: after a graded review, or when the user skips the review. */
    record RepoView(String slug, String title, String language, Difficulty difficulty, List<String> tags, String description,
                    List<RepoFile> files, boolean revealed, List<Issue> issues, int questionsLeft, int points, int maxPoints) {}

    record ReviewRequest(@NotBlank @Size(max = 10000) String review) {}

    record IssueOutcome(Issue issue, boolean found, boolean fixCorrect, String feedback) {}

    record ReviewResult(int found, int total, int fixesCorrect, List<IssueOutcome> issues, List<ExtraFinding> extraFindings,
                        String overallFeedback) {}

    record TestRunRequest(@NotNull @Size(max = 50) Map<String, @NotNull @Size(max = 50000) String> files) {}

    record TestCase(String name, boolean passed, String message) {}

    /**
     * A recorded test run, scored. {@code points} is what the user has earned on this exercise so far (their best run
     * counts). The run's raw output is run-service's job result, which the page already has.
     */
    record TestRunResult(List<TestCase> tests, int passed, int total, boolean allPassed, int points, int maxPoints) {}

    record TestRunSummary(long id, Instant createdAt, int passed, int total, boolean allPassed, List<TestCase> tests) {}

    record QuestionRequest(@NotBlank @Size(max = 1000) String question) {}

    record QuestionAnswer(String answer, int questionsLeft) {}

    record EditRequest(@NotBlank @Size(max = 20) String language, @NotBlank @Size(max = 50000) String code,
                       @NotBlank @Size(max = 20000) String selection, @NotBlank @Size(max = 1000) String instruction) {}

    /** {@code note} is set when the assistant left the selection unchanged and explains why. */
    record EditResult(String replacement, String note) {}

    /** The user's total from repo exercises. The page adds exercise-service's total to it. */
    record PointsTotal(int points) {}
}
