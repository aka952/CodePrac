package dev.codecomp.exercise;

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
}
