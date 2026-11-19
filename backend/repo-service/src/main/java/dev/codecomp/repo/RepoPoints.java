package dev.codecomp.repo;

/** The scoring rules for repo exercises. Points are always derived from stored test runs, never stored themselves. */
public final class RepoPoints {

    private RepoPoints() {}

    public static int max(Difficulty difficulty) {
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
    public static int earned(Difficulty difficulty, int baseline, int passed, int total) {
        if (total <= 0) return 0;
        int max = max(difficulty);
        if (passed >= total) return max;
        int winnable = total - baseline;
        int won = passed - baseline;
        if (winnable <= 0 || won <= 0) return 0;
        return max * 80 / 100 * won / winnable;
    }
}
