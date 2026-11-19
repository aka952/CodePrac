package dev.codecomp.repo;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** Grades a learner's free-text code review against an exercise's reference issues. */
public interface ReviewGrader {

    ReviewGrade grade(RepoExercise exercise, RepoContent content, String review);

    record IssueVerdict(
            @JsonPropertyDescription("id of the reference issue being judged") int issueId,
            @JsonPropertyDescription("true if the review identifies this same underlying problem") boolean found,
            @JsonPropertyDescription("true if the review proposes a fix that would actually resolve this issue") boolean fixCorrect,
            @JsonPropertyDescription("one or two sentences addressed to the learner about this issue") String feedback) {}

    record ExtraFinding(
            @JsonPropertyDescription("short restatement of a point from the review that matches no reference issue") String summary,
            @JsonPropertyDescription("true if it is a real problem in the code") boolean valid,
            @JsonPropertyDescription("one sentence addressed to the learner") String feedback) {}

    record ReviewGrade(
            @JsonPropertyDescription("exactly one verdict per reference issue") List<IssueVerdict> issues,
            List<ExtraFinding> extraFindings,
            @JsonPropertyDescription("two or three sentences of overall feedback addressed to the learner") String overallFeedback) {}

    class UnavailableException extends RuntimeException {
        public UnavailableException(String message) { super(message); }
    }
}
