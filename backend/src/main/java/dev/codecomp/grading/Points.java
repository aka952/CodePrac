package dev.codecomp.grading;

import dev.codecomp.exercise.Exercise.Difficulty;

/** The scoring rules. Points are always derived from stored attempts and test runs, never stored themselves. */
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

    public static int repoMax(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 50;
            case MEDIUM -> 100;
            case HARD -> 150;
        };
    }

    /**
     * 80% is shared equally between the hidden tests the user made pass, the rest is a bonus for passing them all.
     *
     * @param baseline tests that already pass on the untouched code; they earn nothing
     */
    public static int repo(Difficulty difficulty, int baseline, int passed, int total) {
        if (total <= 0) return 0;
        int max = repoMax(difficulty);
        if (passed >= total) return max;
        int winnable = total - baseline;
        int won = passed - baseline;
        if (winnable <= 0 || won <= 0) return 0;
        return max * 80 / 100 * won / winnable;
    }
}
