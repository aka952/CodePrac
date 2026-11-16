package dev.codecomp.sandbox;

import java.util.Set;

public interface Sandbox {

    Set<String> languages();

    RunResult run(String language, String code);

    default void prepare() {}

    class UnsupportedLanguageException extends RuntimeException {
        public UnsupportedLanguageException(String language) { super("Unsupported language: " + language); }
    }

    class BusyException extends RuntimeException {
        public BusyException() { super("All sandbox slots are busy, retry in a moment"); }
    }
}
