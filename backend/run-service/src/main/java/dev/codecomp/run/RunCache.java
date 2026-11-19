package dev.codecomp.run;

import dev.codecomp.contracts.RunResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Run results keyed by sha256(language + code). Filled from the workers' Completed events, read to answer repeated runs. */
@Service
public class RunCache {

    private final RunCacheRepository cache;

    public RunCache(RunCacheRepository cache) {
        this.cache = cache;
    }

    public Optional<RunResult> get(String language, String code) {
        return cache.findById(hash(language, code)).map(RunCacheEntry::toResult);
    }

    /** Stores the result when it depends only on the code (not timeouts or sandbox errors). */
    public void put(String language, String code, RunResult result) {
        if (!result.cacheable()) return;
        try {
            cache.save(new RunCacheEntry(hash(language, code), language, result));
        } catch (DataIntegrityViolationException ignored) {
            // another instance cached the same code first
        }
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
