package dev.codecomp;

import dev.codecomp.grading.Points;
import org.junit.jupiter.api.Test;

import static dev.codecomp.exercise.Exercise.Difficulty.EASY;
import static dev.codecomp.exercise.Exercise.Difficulty.HARD;
import static dev.codecomp.exercise.Exercise.Difficulty.MEDIUM;
import static org.assertj.core.api.Assertions.assertThat;

class PointsTest {

    @Test
    void snippetsAreWorthMoreWhenHarderAndWhenSolvedSooner() {
        assertThat(Points.snippetMax(EASY)).isEqualTo(10);
        assertThat(Points.snippetMax(MEDIUM)).isEqualTo(20);
        assertThat(Points.snippetMax(HARD)).isEqualTo(30);

        assertThat(Points.snippet(MEDIUM, 1)).isEqualTo(20);
        assertThat(Points.snippet(MEDIUM, 2)).isEqualTo(14);
        assertThat(Points.snippet(MEDIUM, 3)).isEqualTo(8);
        assertThat(Points.snippet(HARD, 2)).isEqualTo(21);
    }

    @Test
    void snippetsSolvedAfterTheAnswerWasRevealedEarnNothing() {
        assertThat(Points.snippet(HARD, 4)).isZero();
        assertThat(Points.snippet(EASY, 9)).isZero();
    }

    @Test
    void repoPointsGrowWithEachNewlyPassingTestAndPayABonusForAll() {
        assertThat(Points.repoMax(EASY)).isEqualTo(50);
        assertThat(Points.repoMax(MEDIUM)).isEqualTo(100);
        assertThat(Points.repoMax(HARD)).isEqualTo(150);

        // 10 tests, 2 already pass on the original code: 8 to win, each worth a share of 80 points
        assertThat(Points.repo(MEDIUM, 2, 6, 10)).isEqualTo(40);
        assertThat(Points.repo(MEDIUM, 2, 9, 10)).isEqualTo(70);
        assertThat(Points.repo(MEDIUM, 2, 10, 10)).isEqualTo(100);
        assertThat(Points.repo(HARD, 2, 10, 10)).isEqualTo(150);
    }

    @Test
    void testsThatAlreadyPassedOnTheOriginalCodeEarnNothing() {
        assertThat(Points.repo(MEDIUM, 2, 2, 10)).isZero();
        assertThat(Points.repo(MEDIUM, 2, 1, 10)).isZero();
        assertThat(Points.repo(MEDIUM, 2, 0, 0)).isZero();
    }
}
