package dev.codecomp.run;

import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.Sandbox;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

/** The result cache and the sandbox call. Users' runs reach the sandbox through the queue (JobService, JobWorker). */
@Service
public class RunService {

    private final Sandbox sandbox;
    private final RunCacheRepository cache;

    public RunService(Sandbox sandbox, RunCacheRepository cache) {
        this.sandbox = sandbox;
        this.cache = cache;
    }

    public Optional<RunResult> cached(String language, String code) {
        return cache.findById(hash(language, code)).map(RunCacheEntry::toResult);
    }

    /** Runs the code in the sandbox and caches the result when it depends only on the code. Worker side only. */
    public RunResult execute(String language, String code) {
        var result = sandbox.run(language, code);
        if (result.cacheable()) {
            try {
                cache.save(new RunCacheEntry(hash(language, code), language, result));
            } catch (DataIntegrityViolationException ignored) {
                // another worker cached the same code first
            }
        }
        return result;
    }

    /** Cached result or a fresh run, for the app's own checks (warm-up, exercise verification). Worker side only. */
    public RunResult runTrusted(String language, String code) {
        return cached(language, code).orElseGet(() -> execute(language, code));
    }

    static String hash(String language, String code) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(language.getBytes(UTF_8));
            md.update((byte) 0);
            return HexFormat.of().formatHex(md.digest(code.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
