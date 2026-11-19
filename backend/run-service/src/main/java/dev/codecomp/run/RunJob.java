package dev.codecomp.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One code run. State changes go through {@link RunJobRepository}'s conditional updates. */
@Entity
@Table(name = "run_jobs")
public class RunJob {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @Id
    private UUID id;
    /** Null for the system's own runs. */
    @Column(name = "user_id")
    private UUID userId;
    private String language;
    private String code;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String worker;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    private String result;
    private String error;

    protected RunJob() {}

    RunJob(UUID id, UUID userId, String language, String code) {
        this.id = id;
        this.userId = userId;
        this.language = language;
        this.code = code;
        this.status = Status.QUEUED;
        this.createdAt = Instant.now();
    }

    /** A run answered from the cache: finished before it was ever queued. */
    static RunJob done(UUID id, UUID userId, String language, String code, String result) {
        var job = new RunJob(id, userId, language, code);
        job.status = Status.DONE;
        job.result = result;
        job.finishedAt = job.createdAt;
        return job;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getLanguage() { return language; }
    public String getCode() { return code; }
    public Status getStatus() { return status; }
    public String getResult() { return result; }
    public String getError() { return error; }
}
