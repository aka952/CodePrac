package dev.codecomp.run;

import dev.codecomp.contracts.RunResult;
import dev.codecomp.contracts.RunResult.RunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "run_cache")
public class RunCacheEntry {

    @Id
    private String hash;
    private String language;
    @Enumerated(EnumType.STRING)
    private RunStatus status;
    private String stdout;
    private String stderr;
    @Column(name = "exit_code")
    private int exitCode;
    @Column(name = "duration_ms")
    private long durationMs;
    private boolean truncated;
    @Column(name = "created_at")
    private Instant createdAt;

    protected RunCacheEntry() {}

    RunCacheEntry(String hash, String language, RunResult r) {
        this.hash = hash;
        this.language = language;
        this.status = r.status();
        this.stdout = r.stdout();
        this.stderr = r.stderr();
        this.exitCode = r.exitCode();
        this.durationMs = r.durationMs();
        this.truncated = r.truncated();
        this.createdAt = Instant.now();
    }

    RunResult toResult() { return new RunResult(status, stdout, stderr, exitCode, durationMs, truncated); }
}
