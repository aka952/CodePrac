package dev.codecomp.repo;

import org.junit.jupiter.api.Test;

import static dev.codecomp.repo.Difficulty.EASY;
import static dev.codecomp.repo.Difficulty.HARD;
import static dev.codecomp.repo.Difficulty.MEDIUM;
import static org.assertj.core.api.Assertions.assertThat;

class RepoPointsTest {

    @Test
    void pointsGrowWithEachNewlyPassingTestAndPayABonusForAll() {
        assertThat(RepoPoints.max(EASY)).isEqualTo(50);
        assertThat(RepoPoints.max(MEDIUM)).isEqualTo(100);
        assertThat(RepoPoints.max(HARD)).isEqualTo(150);

        // 10 tests, 2 already pass on the original code: 8 to win, each worth a share of 80 points
        assertThat(RepoPoints.earned(MEDIUM, 2, 6, 10)).isEqualTo(40);
        assertThat(RepoPoints.earned(MEDIUM, 2, 9, 10)).isEqualTo(70);
        assertThat(RepoPoints.earned(MEDIUM, 2, 10, 10)).isEqualTo(100);
        assertThat(RepoPoints.earned(HARD, 2, 10, 10)).isEqualTo(150);
    }

    @Test
    void testsThatAlreadyPassedOnTheOriginalCodeEarnNothing() {
        assertThat(RepoPoints.earned(MEDIUM, 2, 2, 10)).isZero();
        assertThat(RepoPoints.earned(MEDIUM, 2, 1, 10)).isZero();
        assertThat(RepoPoints.earned(MEDIUM, 2, 0, 0)).isZero();
    }
}
