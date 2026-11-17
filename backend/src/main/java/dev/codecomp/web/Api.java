package dev.codecomp.web;

import dev.codecomp.exercise.Exercise.Difficulty;
import dev.codecomp.grading.Grader.ExerciseType;
import dev.codecomp.repo.RepoContent.Issue;
import dev.codecomp.repo.RepoContent.RepoFile;
import dev.codecomp.repo.ReviewGrader.ExtraFinding;
import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.RunResult.RunStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface Api {

    record ExerciseSummary(String slug, String title, String language, ExerciseType type, Difficulty difficulty,
                           List<String> tags, String status, int points, int maxPoints) {}

    record ExerciseView(String slug, String title, String language, ExerciseType type, Difficulty difficulty, List<String> tags,
                        String code, String question, String hint, boolean solved, Solution solution, int points, int maxPoints) {}

    record Solution(String answer, String explanation) {}

    record AttemptRequest(@Size(max = 5000) String answer, boolean giveUp) {}

    /** {@code points} is what the user has earned on this exercise after this attempt. */
    record AttemptResult(boolean correct, long attempts, Solution solution, int points) {}

    record PointsSummary(int total, int snippets, int repos) {}

    record RunRequest(@NotBlank @Size(max = 20) String language, @NotBlank @Size(max = 20000) String code) {}

    record RunResponse(RunStatus status, String stdout, String stderr, int exitCode, long durationMs, boolean truncated, boolean cached) {
        public static RunResponse of(RunResult r, boolean cached) {
            return new RunResponse(r.status(), r.stdout(), r.stderr(), r.exitCode(), r.durationMs(), r.truncated(), cached);
        }
    }

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

    /** {@code points} is what the user has earned on this exercise so far (their best run counts). */
    record TestRunResult(List<TestCase> tests, int passed, int total, boolean allPassed, RunResponse run, int points, int maxPoints) {}

    record TestRunSummary(long id, Instant createdAt, int passed, int total, boolean allPassed, List<TestCase> tests) {}

    record QuestionRequest(@NotBlank @Size(max = 1000) String question) {}

    record QuestionAnswer(String answer, int questionsLeft) {}

    record EditRequest(@NotBlank @Size(max = 20) String language, @NotBlank @Size(max = 50000) String code,
                       @NotBlank @Size(max = 20000) String selection, @NotBlank @Size(max = 1000) String instruction) {}

    /** {@code note} is set when the assistant left the selection unchanged and explains why. */
    record EditResult(String replacement, String note) {}
}
