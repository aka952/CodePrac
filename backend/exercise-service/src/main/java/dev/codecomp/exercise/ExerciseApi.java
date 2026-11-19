package dev.codecomp.exercise;

import dev.codecomp.exercise.Exercise.Difficulty;
import dev.codecomp.grading.Grader.ExerciseType;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The JSON this service sends and receives. */
public interface ExerciseApi {

    record ExerciseSummary(String slug, String title, String language, ExerciseType type, Difficulty difficulty,
                           List<String> tags, String status, int points, int maxPoints) {}

    record ExerciseView(String slug, String title, String language, ExerciseType type, Difficulty difficulty, List<String> tags,
                        String code, String question, String hint, boolean solved, Solution solution, int points, int maxPoints) {}

    record Solution(String answer, String explanation) {}

    record AttemptRequest(@Size(max = 5000) String answer, boolean giveUp) {}

    /** {@code points} is what the user has earned on this exercise after this attempt. */
    record AttemptResult(boolean correct, long attempts, Solution solution, int points) {}

    /** The user's total from snippet exercises. The page adds repo-service's total to it. */
    record PointsTotal(int points) {}
}
