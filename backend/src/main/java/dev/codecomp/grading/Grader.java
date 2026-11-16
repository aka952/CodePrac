package dev.codecomp.grading;

import java.util.stream.Collectors;

public final class Grader {

    public enum ExerciseType { PREDICT_OUTPUT, TRACE, SPOT_BUG }

    private Grader() {}

    public static boolean isCorrect(ExerciseType type, String expected, String given) {
        if (given == null || given.isBlank()) return false;
        return switch (type) {
            case PREDICT_OUTPUT -> normalizeOutput(expected).equals(normalizeOutput(given));
            case TRACE -> compact(expected).equals(compact(given));
            case SPOT_BUG -> expected.strip().equals(given.strip());
        };
    }

    public static String normalizeOutput(String s) {
        return s.replace("\r\n", "\n").lines().map(String::stripTrailing).collect(Collectors.joining("\n")).strip();
    }

    static String compact(String s) {
        return s.replaceAll("\\s+", "").replace('"', '\'');
    }
}
