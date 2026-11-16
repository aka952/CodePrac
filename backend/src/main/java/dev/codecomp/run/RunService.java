package dev.codecomp.run;

import dev.codecomp.sandbox.RunResult;
import dev.codecomp.sandbox.Sandbox;
import dev.codecomp.web.Api.RunResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

@Service
public class RunService {

    private final Sandbox sandbox;
    private final RunCacheRepository cache;
    private final RateLimiter limiter;

    public RunService(Sandbox sandbox, RunCacheRepository cache, RateLimiter limiter) {
        this.sandbox = sandbox;
        this.cache = cache;
        this.limiter = limiter;
    }

    public RunResponse run(UUID user, String language, String code) {
        if (!sandbox.languages().contains(language)) throw new Sandbox.UnsupportedLanguageException(language);
        var hash = hash(language, code);
        var hit = cache.findById(hash);
        if (hit.isPresent()) return RunResponse.of(hit.get().toResult(), true);
        limiter.acquire(user);
        return RunResponse.of(executeAndCache(hash, language, code), false);
    }

    public RunResult runTrusted(String language, String code) {
        var hash = hash(language, code);
        return cache.findById(hash).map(RunCacheEntry::toResult).orElseGet(() -> executeAndCache(hash, language, code));
    }

    private RunResult executeAndCache(String hash, String language, String code) {
        var result = sandbox.run(language, code);
        if (result.cacheable()) {
            try {
                cache.save(new RunCacheEntry(hash, language, result));
            } catch (DataIntegrityViolationException ignored) {
            }
        }
        return result;
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
