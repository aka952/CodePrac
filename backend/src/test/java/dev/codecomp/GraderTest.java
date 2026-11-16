package dev.codecomp;

import dev.codecomp.grading.Grader;
import org.junit.jupiter.api.Test;

import static dev.codecomp.grading.Grader.ExerciseType.PREDICT_OUTPUT;
import static dev.codecomp.grading.Grader.ExerciseType.SPOT_BUG;
import static dev.codecomp.grading.Grader.ExerciseType.TRACE;
import static org.assertj.core.api.Assertions.assertThat;

class GraderTest {

    @Test
    void predictOutputIgnoresTrailingWhitespaceAndLineEndings() {
        assertThat(Grader.isCorrect(PREDICT_OUTPUT, "a\nb", "a  \r\nb\n\n")).isTrue();
        assertThat(Grader.isCorrect(PREDICT_OUTPUT, "a\nb", "a b")).isFalse();
        assertThat(Grader.isCorrect(PREDICT_OUTPUT, "true\nfalse", "true\ntrue")).isFalse();
        // spacing inside a line is part of the output, so formatting exercises can be graded
        assertThat(Grader.isCorrect(PREDICT_OUTPUT, "-1 2", "-1   2")).isFalse();
    }

    @Test
    void traceIgnoresSpacingAndQuoteStyle() {
        assertThat(Grader.isCorrect(TRACE, "[('a', 3)]", "[(\"a\",3)]")).isTrue();
        assertThat(Grader.isCorrect(TRACE, "10", " 10 ")).isTrue();
        assertThat(Grader.isCorrect(TRACE, "10", "11")).isFalse();
    }

    @Test
    void spotBugComparesLineNumbers() {
        assertThat(Grader.isCorrect(SPOT_BUG, "4", "4")).isTrue();
        assertThat(Grader.isCorrect(SPOT_BUG, "4", "5")).isFalse();
    }

    @Test
    void blankAnswerIsWrong() {
        assertThat(Grader.isCorrect(PREDICT_OUTPUT, "x", " ")).isFalse();
        assertThat(Grader.isCorrect(TRACE, "x", null)).isFalse();
    }
}
