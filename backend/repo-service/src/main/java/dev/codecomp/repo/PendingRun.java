package dev.codecomp.repo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A run this service submitted to run-service and is waiting to hear about. See V1__repos.sql. */
@Entity
@Table(name = "pending_runs")
public class PendingRun {

    /** FIX: a user's test run, recorded and scored. BASELINE: the untouched code, to measure the exercise's baseline. */
    public enum Purpose { FIX, BASELINE }

    @Id
    @Column(name = "job_id")
    private UUID jobId;
    /** Null for BASELINE. */
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "repo_exercise_id")
    private Long repoExerciseId;
    @Enumerated(EnumType.STRING)
    private Purpose purpose;
    @Column(name = "created_at")
    private Instant createdAt;

    protected PendingRun() {}

    PendingRun(UUID jobId, UUID userId, Long repoExerciseId, Purpose purpose) {
        this.jobId = jobId;
        this.userId = userId;
        this.repoExerciseId = repoExerciseId;
        this.purpose = purpose;
        this.createdAt = Instant.now();
    }

    public UUID getJobId() { return jobId; }
    public UUID getUserId() { return userId; }
    public Long getRepoExerciseId() { return repoExerciseId; }
    public Purpose getPurpose() { return purpose; }
}
