package dev.codecomp.run;

import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.Sandbox;
import org.springframework.stereotype.Service;

/** Runs code in the sandbox and keeps the result cache filled. */
@Service
public class RunService {

    private final Sandbox sandbox;
    private final RunCache cache;

    public RunService(Sandbox sandbox, RunCache cache) {
        this.sandbox = sandbox;
        this.cache = cache;
    }

    public RunResult execute(String language, String code) {
        var result = sandbox.run(language, code);
        cache.put(language, code, result);
        return result;
    }

    /** Cached result or a fresh run, for the app's own checks (warm-up, exercise verification). */
    public RunResult runTrusted(String language, String code) {
        return cache.get(language, code).orElseGet(() -> execute(language, code));
    }
}
