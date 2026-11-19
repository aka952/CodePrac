package dev.codecomp.repo;

/** Answers general programming-language questions. It is never given the exercise, so it cannot help solve it. */
public interface SyntaxAssistant {

    String answer(String language, String question);
}
