package dev.codecomp.grading;

import dev.codecomp.exercise.Exercise.Difficulty;

/**
 * The scoring rules for snippet exercises. Points are always derived from stored attempts, never stored themselves.
 * Repo exercise points are repo-service's (RepoPoints).
 */
public final class Points {

    private Points() {}

    public static int snippetMax(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 10;
            case MEDIUM -> 20;
            case HARD -> 30;
        };
    }

    /** @param attempt which attempt was the first correct one (1-based). The answer is revealed after the third. */
    public static int snippet(Difficulty difficulty, int attempt) {
        int percent = switch (attempt) {
            case 1 -> 100;
            case 2 -> 70;
            case 3 -> 40;
            default -> 0;
        };
        return snippetMax(difficulty) * percent / 100;
    }
}
