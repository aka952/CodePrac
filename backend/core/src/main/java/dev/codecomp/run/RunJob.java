package dev.codecomp.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One code run in the queue. State changes go through {@link RunJobRepository}'s conditional updates. */
@Entity
@Table(name = "run_jobs")
public class RunJob {

    public enum Kind { SNIPPET, REPO_TEST }

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @Id
    private UUID id;
    @Column(name = "user_id")
    private UUID userId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private String language;
    private String code;
    @Column(name = "repo_slug")
    private String repoSlug;
    @Enumerated(EnumType.STRING)
    private Status status;
    private int attempts;
    private String worker;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    private String result;
    private String error;

    protected RunJob() {}

    RunJob(UUID userId, Kind kind, String language, String code, String repoSlug) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.kind = kind;
        this.language = language;
        this.code = code;
        this.repoSlug = repoSlug;
        this.status = Status.QUEUED;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Kind getKind() { return kind; }
    public String getLanguage() { return language; }
    public String getCode() { return code; }
    public String getRepoSlug() { return repoSlug; }
    public Status getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
}
