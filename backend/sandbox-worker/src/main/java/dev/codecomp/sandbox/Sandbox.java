package dev.codecomp.sandbox;

import dev.codecomp.contracts.RunResult;

public interface Sandbox {

    RunResult run(String language, String code);

    default void prepare() {}

    class UnsupportedLanguageException extends RuntimeException {
        public UnsupportedLanguageException(String language) { super("Unsupported language: " + language); }
    }

    class BusyException extends RuntimeException {
        public BusyException() { super("All sandbox slots are busy, retry in a moment"); }
    }
}
